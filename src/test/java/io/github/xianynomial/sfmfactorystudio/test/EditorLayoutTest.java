package io.github.xianynomial.sfmfactorystudio.test;

import io.github.xianynomial.sfmfactorystudio.client.blocks.model.BProgram;
import io.github.xianynomial.sfmfactorystudio.client.blocks.model.BlocksToSfml;
import io.github.xianynomial.sfmfactorystudio.client.blocks.model.EditorLayout;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Equivalence guards for the incremental layout engine: geometry maintained
 * through the dirty-card / shift-cache fast paths must be byte-identical to a
 * fresh full relayout of the same model. These are the regression tests for
 * the drag-frame optimization (only the dragged card's cache is touched).
 */
public class EditorLayoutTest {

    private static BProgram.Statement.Input input(String label) {
        BProgram.Statement.Input in = new BProgram.Statement.Input();
        in.access.labels.add(label);
        in.limits.add(new BProgram.ResourceLimit());
        return in;
    }

    private static BProgram.Statement.Output output(String label) {
        BProgram.Statement.Output out = new BProgram.Statement.Output();
        out.access.labels.add(label);
        out.limits.add(new BProgram.ResourceLimit());
        return out;
    }

    private static BProgram sampleProgram() {
        BProgram p = new BProgram();
        BProgram.TimerTrigger t1 = new BProgram.TimerTrigger();
        t1.count = 20;
        t1.body.add(input("a"));
        t1.body.add(output("b"));
        BProgram.Statement.If iff = new BProgram.Statement.If();
        BProgram.Branch br = new BProgram.Branch();
        br.cond = new BProgram.Bool.Const(true);
        br.body.add(input("c"));
        iff.branches.add(br);
        iff.hasElse = true;
        iff.elseBody.add(output("d"));
        t1.body.add(iff);
        p.triggers.add(t1);
        BProgram.TimerTrigger t2 = new BProgram.TimerTrigger();
        t2.count = 40;
        t2.body.add(input("e"));
        t2.body.add(new BProgram.Statement.Forget());
        p.triggers.add(t2);
        BProgram.PulseTrigger t3 = new BProgram.PulseTrigger();
        t3.body.add(output("f"));
        p.triggers.add(t3);
        return p;
    }

    private static List<Long> allStatementIds(BProgram p) {
        List<Long> ids = new ArrayList<>();
        for (BProgram.Trigger t : p.triggers) collect(t.body, ids);
        return ids;
    }

    private static void collect(List<BProgram.Statement> list, List<Long> out) {
        for (BProgram.Statement s : list) {
            out.add(s.id);
            if (s instanceof BProgram.Statement.If iff) {
                for (BProgram.Branch b : iff.branches) collect(b.body, out);
                collect(iff.elseBody, out);
            }
        }
    }

    /** Compare every query surface of two layouts laid out from the same model. */
    private static void assertSameGeometry(EditorLayout expected, EditorLayout actual, BProgram p) {
        assertEquals(expected.cards().size(), actual.cards().size(), "卡数一致");
        for (int i = 0; i < expected.cards().size(); i++) {
            EditorLayout.CardL e = expected.cards().get(i);
            EditorLayout.CardL a = actual.cards().get(i);
            assertEquals(e.trigger().id, a.trigger().id, "卡顺序一致");
            assertEquals(e.x(), a.x(), "卡 x 一致");
            assertEquals(e.y(), a.y(), "卡 y 一致");
            assertEquals(e.w(), a.w(), "卡宽一致");
            assertEquals(e.h(), a.h(), "卡高一致");
        }
        for (Long id : allStatementIds(p)) {
            int[] er = expected.rowRectOf(id);
            int[] ar = actual.rowRectOf(id);
            assertNotNull(er, "参考布局应有行矩形 " + id);
            assertNotNull(ar, "增量布局应有行矩形 " + id);
            assertArrayEquals(er, ar, "行矩形一致 id=" + id);
            assertEquals(expected.heightOf(stmt(p, id)), actual.heightOf(stmt(p, id)), "行高一致 id=" + id);
        }
        // 内容包围盒
        assertEquals(expected.contentMinX(), actual.contentMinX());
        assertEquals(expected.contentMinY(), actual.contentMinY());
        assertEquals(expected.contentW(), actual.contentW());
        assertEquals(expected.contentH(), actual.contentH());
    }

    private static BProgram.Statement stmt(BProgram p, long id) {
        for (BProgram.Trigger t : p.triggers) {
            BProgram.Statement s = findStmt(t.body, id);
            if (s != null) return s;
        }
        throw new AssertionError("statement not found " + id);
    }

    private static BProgram.Statement findStmt(List<BProgram.Statement> list, long id) {
        for (BProgram.Statement s : list) {
            if (s.id == id) return s;
            if (s instanceof BProgram.Statement.If iff) {
                for (BProgram.Branch b : iff.branches) {
                    BProgram.Statement r = findStmt(b.body, id);
                    if (r != null) return r;
                }
                BProgram.Statement r = findStmt(iff.elseBody, id);
                if (r != null) return r;
            }
        }
        return null;
    }

    @Test
    public void incrementalRelayoutMatchesFreshRelayout() {
        BProgram p = sampleProgram();
        EditorLayout inc = new EditorLayout();
        inc.setProgram(p);
        inc.relayout(false, -1);

        // 1) 拖动一张卡：只改坐标（shift 分支）
        BProgram.Trigger moved = p.triggers.get(1);
        int[] pos = inc.cardPosOf(moved.id);
        inc.setCardPos(moved.id, pos[0] + 400, pos[1] + 64);
        inc.relayout(false, -1);

        // 2) 积木在两个触发器之间搬家（markBodyDirty 精确标脏）
        BProgram.Statement.Input traveller = input("g");
        p.triggers.get(0).body.add(traveller);          // 先放进卡 1
        inc.markBodyDirty(p.triggers.get(0).body);
        inc.relayout(false, -1);
        p.triggers.get(0).body.remove(p.triggers.get(0).body.size() - 1);
        p.triggers.get(1).body.add(traveller);          // 再搬到卡 2
        inc.markBodyDirty(p.triggers.get(0).body);
        inc.markBodyDirty(p.triggers.get(1).body);
        inc.relayout(false, -1);

        // 3) 展开一块 Input（markAllDirty 全量路径）
        Set<Long> expanded = new HashSet<>();
        expanded.add(((BProgram.Statement.Input) p.triggers.get(0).body.get(0)).id);
        inc.setExpandedIds(expanded);
        inc.markAllDirty();
        inc.relayout(false, -1);

        // 4) 新增一张卡
        BProgram.PulseTrigger t4 = new BProgram.PulseTrigger();
        t4.body.add(output("h"));
        p.triggers.add(t4);
        inc.markAllDirty();
        inc.relayout(false, -1);

        // 参考布局：同一模型 + 同一坐标/展开状态，从零全量重排
        EditorLayout fresh = new EditorLayout();
        fresh.setProgram(p);
        for (BProgram.Trigger t : p.triggers) {
            int[] cp = inc.cardPosOf(t.id);
            if (cp != null) fresh.setCardPos(t.id, cp[0], cp[1]);
        }
        fresh.setExpandedIds(expanded);
        fresh.relayout(false, -1);

        assertSameGeometry(fresh, inc, p);
    }

    @Test
    public void overlapSeparationPushesStackedCardsApartIncrementally() {
        BProgram p = sampleProgram();
        EditorLayout el = new EditorLayout();
        el.setProgram(p);
        // 全部叠到同一列同一位置：避让必须把它们错开（增量缓存路径）
        for (BProgram.Trigger t : p.triggers) el.setCardPos(t.id, 0, 0);
        el.relayout(false, -1);
        for (int i = 1; i < el.cards().size(); i++) {
            EditorLayout.CardL prev = el.cards().get(i - 1);
            EditorLayout.CardL cur = el.cards().get(i);
            assertTrue(cur.y() >= prev.y() + prev.h() + 1, "卡 " + i + " 应排在上一张下方");
        }
        // 避让只动 y，不动 x
        for (EditorLayout.CardL c : el.cards()) assertEquals(0, c.x());
    }

    @Test
    public void expandedInputMakesItsRowTaller() {
        BProgram p = sampleProgram();
        EditorLayout el = new EditorLayout();
        el.setProgram(p);
        el.relayout(false, -1);
        BProgram.Statement first = p.triggers.get(0).body.get(0);
        int before = el.heightOf(first);
        Set<Long> expanded = new HashSet<>();
        expanded.add(first.id);
        el.setExpandedIds(expanded);
        el.markAllDirty();
        el.relayout(false, -1);
        assertTrue(el.heightOf(first) > before, "展开扩展面板后行高应增加");
    }

    @Test
    public void nearestGapFindsInsertionIndexAndRejectsFarCursor() {
        BProgram p = sampleProgram();
        EditorLayout el = new EditorLayout();
        el.setProgram(p);
        el.setCardPos(p.triggers.get(0).id, 0, 0);
        el.setCardPos(p.triggers.get(1).id, 1000, 0);
        el.setCardPos(p.triggers.get(2).id, 2000, 0);
        el.relayout(false, -1);
        // 卡 1 有 3 个顶层积木（input/if）→ 顶层 gaps 索引 0..2
        int[] firstRow = el.rowRectOf(p.triggers.get(0).body.get(0).id);
        assertNotNull(firstRow);
        EditorLayout.Gap g = el.nearestGap(firstRow[0] + 50, firstRow[1] + firstRow[3] + 6);
        assertNotNull(g);
        assertEquals(p.triggers.get(0).body, g.body().list());
        assertEquals(1, g.index(), "第一行下方最近的缝隙应是插入索引 1");
        // 光标在所有卡片 x 范围之外 → 没有可插入位置
        assertNull(el.nearestGap(99999, 0));
    }

    @Test
    public void draggingSkipsOverlapResolutionAndReleaseResettles() {
        BProgram p = sampleProgram();
        EditorLayout el = new EditorLayout();
        el.setProgram(p);
        el.relayout(false, -1);
        // 两张卡叠在同一个位置：拖动帧不清重叠，松手（dragging=false）才清
        BProgram.Trigger a = p.triggers.get(0);
        BProgram.Trigger b = p.triggers.get(1);
        int[] pa = el.cardPosOf(a.id);
        el.setCardPos(b.id, pa[0], pa[1]);
        el.relayout(true, a.id);   // 拖动中：保持重叠
        assertEquals(pa[0], el.cardRectOf(b.id)[0]);
        assertEquals(pa[1], el.cardRectOf(b.id)[1]);
        el.relayout(false, a.id);  // 松手：b 让位到 a 下方
        int[] rb = el.cardRectOf(b.id);
        int[] ra = el.cardRectOf(a.id);
        assertTrue(rb[1] >= ra[1] + ra[3], "松手后避让应把 b 推到 a 下方");
    }

    @Test
    public void fieldEditOnlyRelayoutsChangedCard() {
        BProgram p = sampleProgram();
        EditorLayout el = new EditorLayout();
        el.setProgram(p);
        el.relayout(false, -1);
        assertEquals(3, el.cardsLaidLastPass(), "首次全量应排 3 张卡");

        // 第一次哈希差分是"补课"：首次全量布局时无哈希可比，全部重排并记录哈希
        el.markModelEdited(BlocksToSfml.snapshot(p).triggerHashes());
        el.relayout(false, -1);
        assertEquals(3, el.cardsLaidLastPass(), "首次差分应补记全部卡的哈希");

        // 内容未变 → 零卡重排
        el.markModelEdited(BlocksToSfml.snapshot(p).triggerHashes());
        el.relayout(false, -1);
        assertEquals(0, el.cardsLaidLastPass(), "内容未变不应重排任何卡");

        // 改 t2 的一个字段（模拟 pushUndo 后的字段编辑）→ 只有 t2 的卡重排
        ((BProgram.TimerTrigger) p.triggers.get(1)).count = 60;
        el.markModelEdited(BlocksToSfml.snapshot(p).triggerHashes());
        el.relayout(false, -1);
        assertEquals(1, el.cardsLaidLastPass(), "只有被编辑的卡重排");

        // 增量结果与全量重排几何等价
        EditorLayout full = new EditorLayout();
        full.setProgram(p);
        full.relayout(false, -1);
        assertSameGeometry(full, el, p);

        // 结构性变化（markAllDirty，如展开/折叠）仍全量
        el.markAllDirty();
        el.relayout(false, -1);
        assertEquals(3, el.cardsLaidLastPass(), "markAllDirty 仍应全量重排");
    }

    /**
     * "积木延迟出现"回归（2026-09-09 用户反馈）：pushUndo 曾把"编辑前"快照
     * 哈希当新内容哈希存——缓存哈希滞后一位。当卡 A 被卡 B 的编辑"追平"
     * （stored == 当前内容）后再编辑 A，差分误判"未变化"→ A 不重排 →
     * 新积木没有行矩形，renderStatement 直接跳过 = 点别处才冒出来。
     * 无参 markModelEdited()（relayout 现算编辑后哈希）修复后，本用例锁定：
     * 被追平的卡再次编辑时必须重排，且新语句立刻有行矩形。
     */
    @Test
    public void editAfterCatchupStillRelayoutsAndAssignsRowRect() {
        BProgram p = sampleProgram();
        EditorLayout el = new EditorLayout();
        el.setProgram(p);
        el.relayout(false, -1);                       // 首次全量（无可比哈希）
        assertEquals(3, el.cardsLaidLastPass());

        // 编辑 t1（模拟 pushUndo：先标记脏、此刻模型已是编辑后状态）
        p.triggers.get(0).body.add(input("a2"));
        el.markModelEdited();
        el.relayout(false, -1);                       // 现算哈希并补记全部卡
        BProgram.Statement added = p.triggers.get(0).body.get(p.triggers.get(0).body.size() - 1);
        assertNotNull(el.rowRectOf(added.id), "编辑后新积木必须立刻有行矩形");

        // 编辑 t2（另一张卡）：t1 内容未变 → 只重排 t2
        int[] rectBefore = el.rowRectOf(added.id).clone();
        ((BProgram.TimerTrigger) p.triggers.get(1)).count = 60;
        el.markModelEdited();
        el.relayout(false, -1);
        assertEquals(1, el.cardsLaidLastPass(), "只有被编辑的卡重排");
        assertArrayEquals(rectBefore, el.rowRectOf(added.id), "t1 的行矩形原样保留");

        // 关键回归点：再编辑 t1（它的哈希已被上一轮"追平"）
        p.triggers.get(0).body.add(input("a3"));
        el.markModelEdited();
        el.relayout(false, -1);
        assertEquals(1, el.cardsLaidLastPass(), "被追平的卡再次编辑必须重排（旧实现漏判=延迟出现）");
        BProgram.Statement added2 = p.triggers.get(0).body.get(p.triggers.get(0).body.size() - 1);
        assertNotNull(el.rowRectOf(added2.id), "新积木必须立刻有行矩形（否则渲染跳过=不出现）");

        // 增量结果与全量重排几何等价
        EditorLayout full = new EditorLayout();
        full.setProgram(p);
        full.relayout(false, -1);
        assertSameGeometry(full, el, p);
    }

    @Test
    public void copyStackStaysGluedWhenMemberHeightChanges() {
        BProgram p = new BProgram();
        BProgram.TimerTrigger a = new BProgram.TimerTrigger();
        a.count = 20;
        a.body.add(input("box"));
        p.triggers.add(a);
        p.triggers.add(a.copy()); // ＋ 复制出的副本
        EditorLayout el = new EditorLayout();
        el.setProgram(p);
        el.setCardPos(a.id, 0, 0);
        el.setCardPos(p.triggers.get(1).id, 0, 0);
        el.relayout(false, -1);
        int h = el.cardRectOf(a.id)[3];
        el.setCardPos(p.triggers.get(1).id, 0, h); // 紧贴成叠
        el.relayout(false, -1);
        assertEquals(h, el.cardRectOf(p.triggers.get(1).id)[1]);

        // 顶部卡加一条积木变高（正文已与副本不同=触发头身份）：下方副本
        // 必须整体下移保持零间距紧贴——没有重对齐时链断出 ±8px 紧贴带：
        // 页脚 − 消失、＋ 再复制贴着原卡放把旧副本挤飞（用户反馈场景）。
        a.body.add(input("box2"));
        el.markModelEdited();
        el.relayout(false, -1);
        int h2 = el.cardRectOf(a.id)[3];
        assertTrue(h2 > h, "顶部卡确实变高");
        assertEquals(h2, el.cardRectOf(p.triggers.get(1).id)[1],
                "副本必须跟随上方成员的高度变化保持紧贴");
    }
}
