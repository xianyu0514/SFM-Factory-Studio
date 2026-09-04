package io.github.xianynomial.sfmfactorystudio.client.blocks.model;

import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Deterministic two-phase layout engine for the block editor, extracted from
 * the screen so it stays Minecraft-free and unit-testable. Geometry is a
 * byte-for-byte port of the original screen layout; only the storage changed.
 *
 * Layout results are cached per trigger card and keyed by the session-stable
 * trigger id (see {@code BProgram.NEXT_ID}):
 * <ul>
 *   <li>a body edit only re-lays-out the owning card(s) — {@link #markBodyDirty};</li>
 *   <li>moving a card (drag) only shifts its cached rows, O(card rows) instead
 *       of O(whole program);</li>
 *   <li>overlap separation works on cheap card rectangles only and shifts the
 *       affected caches instead of re-measuring bodies.</li>
 * </ul>
 * The global query structures (rowRect / measuredHeights / addRowPos) are
 * maintained incrementally from the per-card caches, so renderers and hit
 * testing keep O(1) lookups without paying a full rebuild per frame.
 */
public final class EditorLayout {

    // ---- metrics (content px), single source of truth for screen + layout ----
    public static final int BAR_H = 20;
    /** 主组「或者也搬运」芯片每行数量（渲染与高度计算共用，改要同步）。 */
    public static final int RES_ALT_PER_ROW = 4;
    public static final int OPT_H = 20;
    public static final int ROW_GAP = 4;
    public static final int INDENT = 16;
    public static final int HEAD_H = 28;
    public static final int FOOT_H = 24;
    public static final int ADD_H = 16;
    public static final int CARD_W = 380;
    public static final int CARD_INNER = 10;

    public record CardL(BProgram.Trigger trigger, int x, int y, int w, int h) {
    }

    public record BodyRef(List<BProgram.Statement> list) {
    }

    public record Gap(int x, int y, int w, BodyRef body, int index) {
    }

    private BProgram program = new BProgram();
    private Set<Long> expandedIds = Set.of();
    private Set<Long> collapsedCards = Set.of();
    private Set<Long> collapsedIfs = Set.of();

    /** trigger id -> [x, y]. Survives setProgram so undo can migrate positions. */
    private final Map<Long, int[]> cardPos = new HashMap<>();
    private final Map<Long, CardCache> cardCaches = new HashMap<>();
    private boolean allDirty = true;
    private final Set<Long> dirtyCards = new HashSet<>();
    // 编辑热路径：pushUndo 顺带产出的每触发器内容哈希，下次 relayout 做差分，
    // 只有内容变了的卡才重新测量（代替曾经的 markAllDirty 全量重排）。
    private boolean hashDirty = false;
    private Map<Long, Long> pendingHashes = null;
    private int autoNextY = 0;
    /** 仅供测试断言：上一次 relayout 实际重新测量的卡数。 */
    private int cardsLaidLastPass = 0;

    // merged query structures, maintained incrementally from the card caches
    private final List<CardL> cards = new ArrayList<>();
    private final Map<Long, int[]> rowRect = new HashMap<>();            // statement id -> [x,y,w,h]
    private final Map<Long, Integer> measuredHeights = new HashMap<>();  // statement id -> height
    private final Map<List<BProgram.Statement>, int[]> addRowPos = new IdentityHashMap<>();
    private int contentMinX = 0, contentMinY = 0;
    private int contentW = 1, contentH = 1;

    /** Per-card layout cache. Rects/positions are shared with the global maps. */
    private static final class CardCache {
        int x, y;              // position the cached rows are laid out at
        int height;            // card height (position independent)
        /** 卡片宽度：默认 CARD_W，备选资源横向铺开放不下时按内容加长。 */
        int width = CARD_W;
        boolean dirty = true;
        long contentHash;      // trigger 内容哈希（hashed=true 时有效）
        boolean hashed = false;
        final List<Gap> gaps = new ArrayList<>();
        final Map<List<BProgram.Statement>, int[]> addRowPos = new IdentityHashMap<>();
        final Map<Long, int[]> rows = new HashMap<>();   // statement id -> rect (shared with global rowRect)
    }

    // ------------------------------------------------------------------ state

    /** Model was replaced (undo / code sync / draft restore): drop all caches. */
    public void setProgram(BProgram program) {
        this.program = program;
        allDirty = true;
        dirtyCards.clear();
        cardCaches.clear();
        rowRect.clear();
        measuredHeights.clear();
        addRowPos.clear();
        cards.clear();
        // cardPos is deliberately kept: callers migrate old coordinates back
        // via fingerprint matching before the next relayout.
    }

    /** Expanded IO blocks change their card's height; heights read this set. */
    public void setExpandedIds(Set<Long> expandedIds) {
        this.expandedIds = expandedIds;
    }

    /** Collapsed cards lay out as header + one summary line (no body rows). */
    public void setCollapsedCards(Set<Long> collapsedCards) {
        this.collapsedCards = collapsedCards;
    }

    /** Collapsed Ifs lay out as a single condition row (no nested bodies). */
    public void setCollapsedIfs(Set<Long> collapsedIfs) {
        this.collapsedIfs = collapsedIfs;
    }

    /** Any body edit that is not tracked per-card (fallback, O(program)). */
    public void markAllDirty() {
        allDirty = true;
    }

    /**
     * 普通字段编辑的精准失效：传入编辑时刻（pushUndo 时）全程序每个触发器
     * 的内容哈希。下次 relayout 与各卡缓存哈希比对，只有内容变化的卡重新
     * 测量。展开/折叠/整体替换等结构性变化仍走 {@link #markAllDirty()} 全量。
     */
    public void markModelEdited(Map<Long, Long> triggerHashes) {
        this.pendingHashes = triggerHashes;
        this.hashDirty = true;
    }

    /** 仅供测试断言：上一次 relayout 重新测量的卡数。 */
    public int cardsLaidLastPass() {
        return cardsLaidLastPass;
    }

    /**
     * Mark the card owning this body as needing a re-layout. Used on the
     * drag hot path where only one or two cards change per frame; when the
     * owner cannot be found (model was just replaced) everything is re-laid.
     */
    public void markBodyDirty(List<BProgram.Statement> body) {
        for (BProgram.Trigger t : program.triggers) {
            if (ownsBody(t.body, body)) {
                dirtyCards.add(t.id);
                return;
            }
        }
        allDirty = true;
    }

    private static boolean ownsBody(List<BProgram.Statement> list, List<BProgram.Statement> body) {
        if (list == body) return true;
        for (BProgram.Statement s : list) {
            if (s instanceof BProgram.Statement.If iff) {
                for (BProgram.Branch b : iff.branches) {
                    if (ownsBody(b.body, body)) return true;
                }
                if (ownsBody(iff.elseBody, body)) return true;
            }
        }
        return false;
    }

    // ---------------------------------------------------------------- layout

    /**
     * Bring every card's cache in sync with its position and resolve card
     * overlaps. Dragging skips overlap resolution (the dragged card must stay
     * under the cursor); it is resolved on release instead.
     */
    public void relayout(boolean dragging, long keepPosId) {
        ensureCardPositions();
        // 编辑热路径的哈希差分：与各卡缓存的内容哈希比对，未变的卡直接复用
        boolean hashPass = hashDirty;
        Map<Long, Long> hashes = hashPass ? pendingHashes : null;
        hashDirty = false;
        pendingHashes = null;
        cardsLaidLastPass = 0;
        for (BProgram.Trigger t : program.triggers) {
            int[] pos = cardPos.get(t.id);
            if (pos == null) continue; // ensureCardPositions always fills these
            CardCache cc = cardCaches.get(t.id);
            Long hash = hashes == null ? null : hashes.get(t.id);
            boolean stale = cc == null || allDirty || cc.dirty || dirtyCards.contains(t.id)
                    || (hash != null && (!cc.hashed || cc.contentHash != hash));
            if (stale) {
                if (cc == null) {
                    cc = new CardCache();
                    cardCaches.put(t.id, cc);
                }
                layoutCardInto(t, pos[0], pos[1], cc);
                cc.dirty = false;
                if (hash != null) {
                    cc.contentHash = hash;
                    cc.hashed = true;
                }
                cardsLaidLastPass++;
            } else if (cc.x != pos[0] || cc.y != pos[1]) {
                shiftCard(cc, pos[0] - cc.x, pos[1] - cc.y);
            }
        }
        dirtyCards.clear();
        allDirty = false;
        rebuildCardList();
        if (!dragging) {
            // resolveOverlaps converges in one call; a second pass is a guard.
            for (int pass = 0; pass < 2; pass++) {
                if (!resolveOverlapsOnce(keepPosId)) break;
                rebuildCardList();
            }
        }
    }

    /** Give cards without coordinates an initial slot; prune dead entries. */
    private void ensureCardPositions() {
        Set<Long> live = new HashSet<>();
        for (BProgram.Trigger t : program.triggers) live.add(t.id);
        cardPos.keySet().removeIf(id -> !live.contains(id));
        for (BProgram.Trigger t : program.triggers) {
            if (!cardPos.containsKey(t.id)) {
                cardPos.put(t.id, new int[]{0, CardLayouts.snap(autoNextY)});
                autoNextY += 120; // rough; the overlap pass fixes real spacing
            }
        }
    }

    /** One overlap-resolution pass; returns true when any card moved. */
    private boolean resolveOverlapsOnce(long keepPosId) {
        int n = cards.size();
        if (n < 2) return false;
        int keepIdx = -1;
        int[] xs = new int[n], ys = new int[n], ws = new int[n], hs = new int[n];
        for (int i = 0; i < n; i++) {
            CardL c = cards.get(i);
            if (c.trigger().id == keepPosId) keepIdx = i;
            xs[i] = c.x();
            ys[i] = c.y();
            ws[i] = c.w();
            hs[i] = c.h();
        }
        int[] out = CardLayouts.resolveOverlaps(xs, ys, ws, hs, keepIdx);
        boolean moved = false;
        for (int i = 0; i < n; i++) {
            if (out[i] == ys[i]) continue;
            moved = true;
            BProgram.Trigger t = cards.get(i).trigger();
            int[] p = cardPos.get(t.id);
            if (p != null) p[1] = out[i];
            CardCache cc = cardCaches.get(t.id);
            if (cc != null && cc.y != out[i]) shiftCard(cc, 0, out[i] - cc.y);
        }
        return moved;
    }

    /** Full body layout of one card into its cache (and the global maps). */
    private void layoutCardInto(BProgram.Trigger t, int x, int y, CardCache cc) {
        // detach the previous contents from the global query maps first
        for (Long id : cc.rows.keySet()) {
            rowRect.remove(id);
            measuredHeights.remove(id);
        }
        for (List<BProgram.Statement> list : cc.addRowPos.keySet()) addRowPos.remove(list);
        cc.rows.clear();
        cc.gaps.clear();
        cc.addRowPos.clear();
        cc.x = x;
        cc.y = y;
        // 宽度先于正文布局确定：备选资源横向铺开放不下时整卡加长，
        // 这样正文拿到的是"够用"的宽度，内容不会溢出卡片边界造成重叠。
        cc.width = cardWidth(t);
        if (collapsedCards.contains(t.id)) {
            // 折叠卡：标题 + 一行摘要 + 页脚间距，正文不布局（缝隙也不注册）
            cc.height = HEAD_H + 6 + BAR_H + ROW_GAP + FOOT_H + 4;
            return;
        }
        int by = y + HEAD_H + 6;
        int end = layoutBody(t.body, x + CARD_INNER, by, cc);
        end = end + ADD_H;                        // add-row
        cc.height = (end + FOOT_H + 4) - y;
    }

    /** Move a card's cached geometry without re-measuring anything. */
    private void shiftCard(CardCache cc, int dx, int dy) {
        if (dx == 0 && dy == 0) return;
        cc.x += dx;
        cc.y += dy;
        for (int[] r : cc.rows.values()) {  // shared arrays: global rowRect follows
            r[0] += dx;
            r[1] += dy;
        }
        for (int[] p : cc.addRowPos.values()) {
            p[0] += dx;
            p[1] += dy;
        }
        for (int i = 0; i < cc.gaps.size(); i++) {
            Gap g = cc.gaps.get(i);
            cc.gaps.set(i, new Gap(g.x() + dx, g.y() + dy, g.w(), g.body(), g.index()));
        }
    }

    private void rebuildCardList() {
        cards.clear();
        int minLeft = 0, minTop = 0, maxRight = CARD_W, maxBottom = 1;
        boolean haveCards = false;
        for (BProgram.Trigger t : program.triggers) {
            CardCache cc = cardCaches.get(t.id);
            int[] p = cardPos.get(t.id);
            if (cc == null || p == null) continue;
            int x = p[0], y = p[1];
            // 用卡片自己的宽度（可能被备选资源加长）——重叠检测按这个宽度算，
            // 卡片再宽也不会互相压住。
            int cw = Math.max(CARD_W, cc.width);
            cards.add(new CardL(t, x, y, cw, cc.height));
            if (!haveCards) {
                minLeft = x;
                minTop = y;
                maxRight = x + cw;
                maxBottom = y + cc.height;
                haveCards = true;
            } else {
                minLeft = Math.min(minLeft, x);
                minTop = Math.min(minTop, y);
                maxRight = Math.max(maxRight, x + cw);
                maxBottom = Math.max(maxBottom, y + cc.height);
            }
        }
        contentMinX = minLeft - 40;
        contentMinY = minTop - 40;
        contentW = Math.max(1, maxRight - minLeft + 80);
        contentH = Math.max(1, maxBottom - minTop + 80);
        autoNextY = maxBottom + CardLayouts.CARD_GAP;
    }

    private int layoutBody(List<BProgram.Statement> list, int x, int y, CardCache cc) {
        BodyRef ref = new BodyRef(list);
        int w = cc.width - CARD_INNER * 2;
        for (int i = 0; i < list.size(); i++) {
            BProgram.Statement s = list.get(i);
            cc.gaps.add(new Gap(x, y - ROW_GAP / 2 - 4, w, ref, i));
            int h = layoutStatement(s, x, y, w, cc);
            y += h + ROW_GAP;
        }
        cc.gaps.add(new Gap(x, y, w, ref, list.size()));
        int[] pos = new int[]{x, y};
        cc.addRowPos.put(list, pos);
        addRowPos.put(list, pos);
        return y + ADD_H;
    }

    /**
     * 主组「或者也搬运」现在改成**单行横向铺开**，不再换行，所以高度上不再额外
     * 占用行数——返回 0。宽度不足的部分由 {@link #cardWidth(BProgram.Trigger)} 把
     * 卡片整体加长，避免内容溢出卡片造成重叠。
     */
    public static int altResourceRows(java.util.List<BProgram.ResourceLimit> limits) {
        return 0;
    }

    // ---- 横向加长：备选资源单行铺开所需的卡片宽度 ----------------------------

    /** 备选区行首：缩进 12 + 「和」字（约 8px）+ 字距 5。 */
    private static final int ALT_ROW_LEAD = 12 + 13;
    /**
     * 单个备选资源芯片横向占位（保守取上限）：
     * 类别 pill max(32, 最长类别名「全部化学品」约 40+10=50) + 间距 4
     * + 资源槽 20 + 间距 4 + ✕ 图标 20 - 2 + 芯片间距 6 ≈ 102，取 104 留余量。
     */
    private static final int ALT_CHIP_W = 40;
    /** 行尾「＋」添加槽：加号槽 20 + 间距 + 呼吸位。 */
    private static final int ALT_ROW_TAIL = 40;

    /** 备选资源横向铺开后超出默认卡片宽度的部分（0 = 放得下，不必加长）。 */
    private static int altExtraWidth(java.util.List<BProgram.ResourceLimit> limits) {
        if (limits == null || limits.isEmpty()) return 0;
        int alts = Math.max(0, limits.get(0).resources.size() - 1);
        if (alts <= 0) return 0;
        int usable = CARD_W - CARD_INNER * 2;   // 语句在卡片内的可用宽度
        int need = ALT_ROW_LEAD + alts * ALT_CHIP_W + ALT_ROW_TAIL;
        return Math.max(0, need - usable);
    }

    /** 一张卡片正文横向铺开后需要的宽度（含嵌套 If 内的语句）。 */
    public static int cardWidth(BProgram.Trigger t) {
        return Math.max(CARD_W, measureBodyWidth(t.body));
    }

    private static int measureBodyWidth(List<BProgram.Statement> list) {
        int need = CARD_W;
        if (list == null) return need;
        for (BProgram.Statement s : list) {
            need = Math.max(need, measureStatementWidth(s));
        }
        return need;
    }

    private static int measureStatementWidth(BProgram.Statement s) {
        if (s instanceof BProgram.Statement.Input in) {
            return CARD_W + altExtraWidth(in.limits);
        }
        if (s instanceof BProgram.Statement.Output out) {
            return CARD_W + altExtraWidth(out.limits);
        }
        if (s instanceof BProgram.Statement.If iff) {
            // 嵌套体有 INDENT 缩进，加长需求要一起算进去
            int need = CARD_W;
            for (BProgram.Branch b : iff.branches) {
                need = Math.max(need, measureBodyWidth(b.body) + INDENT);
            }
            return Math.max(need, measureBodyWidth(iff.elseBody) + INDENT);
        }
        return CARD_W;
    }

    private int layoutStatement(BProgram.Statement s, int x, int y, int w, CardCache cc) {
        // 用 layoutBody 传入的实际宽度（卡片加长后 w 会大于默认），不再回夹到 CARD_W。
        int wEff = Math.max(60, w - 8);
        if (s instanceof BProgram.Statement.Input in) {
            int h = BAR_H + altResourceRows(in.limits);
            putRow(cc, s, new int[]{x, y, wEff, h});
            if (expandedIds.contains(in.id)) {
                h += ioOptionsHeight(in);
            }
            return rememberHeight(cc, s, h);
        }
        if (s instanceof BProgram.Statement.Output out) {
            int h = BAR_H + altResourceRows(out.limits);
            putRow(cc, s, new int[]{x, y, wEff, h});
            if (expandedIds.contains(out.id)) {
                h += ioOptionsHeight(out);
            }
            return rememberHeight(cc, s, h);
        }
        if (s instanceof BProgram.Statement.Forget || s instanceof BProgram.Statement.Comment
                || s instanceof BProgram.Statement.Raw) {
            putRow(cc, s, new int[]{x, y, wEff, BAR_H});
            return rememberHeight(cc, s, BAR_H);
        }
        if (s instanceof BProgram.Statement.If iff) {
            if (collapsedIfs.contains(iff.id)) {
                // 折叠 If：只剩一行条件 + 收口，嵌套体不布局
                putRow(cc, s, new int[]{x, y, wEff, BAR_H});
                return rememberHeight(cc, s, BAR_H + 8);
            }
            int cursor = y;
            for (int bi = 0; bi < iff.branches.size(); bi++) {
                BProgram.Branch b = iff.branches.get(bi);
                if (bi == 0) putRow(cc, s, new int[]{x, cursor, wEff, BAR_H});
                cursor += BAR_H + ROW_GAP;
                cursor = layoutBody(b.body, x + INDENT, cursor, cc) + ROW_GAP;
            }
            if (iff.hasElse || !iff.elseBody.isEmpty()) {
                cursor += BAR_H + ROW_GAP;
                cursor = layoutBody(iff.elseBody, x + INDENT, cursor, cc) + ROW_GAP;
            } else {
                cursor += BAR_H + ROW_GAP;
            }
            return rememberHeight(cc, s, cursor - y + 8); // closing lip
        }
        putRow(cc, s, new int[]{x, y, wEff, BAR_H});
        return rememberHeight(cc, s, BAR_H);
    }

    private void putRow(CardCache cc, BProgram.Statement s, int[] rect) {
        cc.rows.put(s.id, rect);
        rowRect.put(s.id, rect);
    }

    private int rememberHeight(CardCache cc, BProgram.Statement s, int height) {
        measuredHeights.put(s.id, height);
        return height;
    }

    // ------------------------------------------------------------- measuring

    /** Height of the optional extension panel of an Input/Output block. */
    public static int ioOptionsHeight(Object io) {
        return OPT_H * ioExtensionRows(io);
    }

    /** Row count of the extension panel — depends on the model only. */
    public static int ioExtensionRows(Object io) {
        List<BProgram.ResourceLimit> limits;
        List<BProgram.ResourceRef> except;
        BProgram.LabelAccess access;
        boolean each;
        boolean emptySlots = false;
        if (io instanceof BProgram.Statement.Input input) {
            limits = input.limits;
            except = input.except;
            access = input.access;
            each = input.each;
        } else {
            BProgram.Statement.Output output = (BProgram.Statement.Output) io;
            limits = output.limits;
            except = output.except;
            access = output.access;
            each = output.each;
            emptySlots = output.emptySlots;
        }
        int rows = 1; // bottom “add extension block” row
        for (int i = 0; i < limits.size(); i++) {
            BProgram.ResourceLimit limit = limits.get(i);
            if (i > 0) rows++;
            rows += Math.max(0, limit.resources.size() - 1);
            if (limit.quantity != null) rows++;
            if (limit.retain != null) rows++;
            if (limit.with != null) rows += withRows(limit.with);
        }
        rows += except.size();
        if (access.eachSide || !access.sides.isEmpty()) rows++;
        if (!access.slots.isEmpty()) rows++;
        if (access.roundRobin != BProgram.RoundRobinMode.NONE) rows++;
        if (each) rows++;
        if (emptySlots) rows++;
        return rows;
    }

    /**
     * 资源标签占几行：条件被画成一串小积木（每个条件一颗药丸），每行放
     * {@value #WITH_TAGS_PER_ROW} 颗，末尾那行还跟着「＋ 且…」「＋ 或…」。
     * 纯模型计算，不碰字体，保证布局与渲染用的行数完全一致。
     */
    public static int withRows(BProgram.WithFilter filter) {
        int tags = countWithTags(filter.expr);
        return Math.max(1, (tags + WITH_TAGS_PER_ROW - 1) / WITH_TAGS_PER_ROW);
    }

    /** 条件药丸链每行放几颗：行宽固定，这个数不能靠字体测出来。 */
    public static final int WITH_TAGS_PER_ROW = 2;

    private static int countWithTags(BProgram.WithExpr expr) {
        if (expr instanceof BProgram.WithExpr.Tag) return 1;
        if (expr instanceof BProgram.WithExpr.Not not) return countWithTags(not.inner);
        if (expr instanceof BProgram.WithExpr.And and) {
            int n = 0;
            for (BProgram.WithExpr part : and.parts) n += countWithTags(part);
            return n;
        }
        if (expr instanceof BProgram.WithExpr.Or or) {
            int n = 0;
            for (BProgram.WithExpr part : or.parts) n += countWithTags(part);
            return n;
        }
        return 0;
    }

    /** Height of a statement outside a layout pass (rare fallback path). */
    public int statementHeight(BProgram.Statement s) {
        if (s instanceof BProgram.Statement.Input in) {
            return BAR_H + (expandedIds.contains(in.id) ? ioOptionsHeight(in) : 0);
        }
        if (s instanceof BProgram.Statement.Output out) {
            return BAR_H + (expandedIds.contains(out.id) ? ioOptionsHeight(out) : 0);
        }
        if (s instanceof BProgram.Statement.If iff) {
            if (collapsedIfs.contains(iff.id)) {
                return BAR_H + 8;
            }
            int h = 0;
            for (BProgram.Branch b : iff.branches) {
                h += BAR_H + ROW_GAP;
                h += bodyHeight(b.body) + ROW_GAP;
            }
            if (iff.hasElse || !iff.elseBody.isEmpty()) {
                h += BAR_H + ROW_GAP;
                h += bodyHeight(iff.elseBody) + ROW_GAP;
            } else {
                h += BAR_H + ROW_GAP;
            }
            return h + 8;
        }
        return BAR_H;
    }

    private int bodyHeight(List<BProgram.Statement> list) {
        int y = 0;
        for (BProgram.Statement s : list) {
            y += statementHeight(s) + ROW_GAP;
        }
        return y + ADD_H;
    }

    // ---------------------------------------------------------------- lookup

    public List<CardL> cards() {
        return cards;
    }

    public int @Nullable [] rowRectOf(long statementId) {
        return rowRect.get(statementId);
    }

    public int @Nullable [] addRowPosOf(List<BProgram.Statement> body) {
        return addRowPos.get(body);
    }

    /** Measured height from the last layout; falls back to a fresh measure. */
    public int heightOf(BProgram.Statement s) {
        Integer measured = measuredHeights.get(s.id);
        return measured != null ? measured : statementHeight(s);
    }

    public int @Nullable [] cardRectOf(long triggerId) {
        for (CardL c : cards) {
            if (c.trigger().id == triggerId) return new int[]{c.x(), c.y(), c.w(), c.h()};
        }
        return null;
    }

    public int contentMinX() {
        return contentMinX;
    }

    public int contentMinY() {
        return contentMinY;
    }

    public int contentW() {
        return contentW;
    }

    public int contentH() {
        return contentH;
    }

    /**
     * Closest insertion gap to the cursor. Cards whose x-range excludes the
     * cursor are skipped wholesale, so the scan only walks the gaps of
     * plausibly relevant cards (order and tie-breaking match the original
     * full scan).
     */
    public @Nullable Gap nearestGap(double cx, double cy) {
        Gap best = null;
        double bestDy = Double.MAX_VALUE;
        for (CardL c : cards) {
            if (cx < c.x() - 30 || cx > c.x() + c.w() + 30) continue;
            CardCache cc = cardCaches.get(c.trigger().id);
            if (cc == null) continue;
            for (Gap g : cc.gaps) {
                double dy = Math.abs(g.y() + 4 - cy);
                if (dy < bestDy && cx >= g.x() - 30 && cx <= g.x() + g.w() + 30) {
                    bestDy = dy;
                    best = g;
                }
            }
        }
        return best;
    }

    // -------------------------------------------------------------- card pos

    public int @Nullable [] cardPosOf(long triggerId) {
        return cardPos.get(triggerId);
    }

    /** Move a card without scheduling a re-layout; relayout() shifts caches. */
    public void setCardPos(long triggerId, int x, int y) {
        int[] p = cardPos.get(triggerId);
        if (p != null) {
            p[0] = x;
            p[1] = y;
        } else {
            cardPos.put(triggerId, new int[]{x, y});
        }
    }

    public void removeCardPos(long triggerId) {
        cardPos.remove(triggerId);
    }

    public void clearCardPos() {
        cardPos.clear();
    }
}
