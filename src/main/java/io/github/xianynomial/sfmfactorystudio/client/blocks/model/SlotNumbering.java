package io.github.xianynomial.sfmfactorystudio.client.blocks.model;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;

/**
 * 槽位可视化编号校准（纯逻辑，MC-free 可单测）。
 *
 * <p>目标：玩家点选的每个格子的编号 = SFM 实际寻址的能力槽索引（所见即所得）。
 * 三层证据，可靠性递减：
 * <ol>
 * <li>实例匹配（capHint）：捕获时菜单槽的 container 与能力面是同一实例
 * → 容器内索引即能力槽索引，精确；</li>
 * <li>内容签名匹配：服务端回报每个能力槽的（物品 id, 数量），与捕获时界面
 * 显示内容比对，唯一命中即锁定；</li>
 * <li>空间顺序拉链：歧义组按（先 y 后 x）排序配对；完全无服务端数据时整体
 * 退回纯空间编号并明示"未校准"。</li>
 * </ol>
 *
 * <p>能力槽数少于界面格子数（升级卡等不可寻址格）时，多余格子置灰不可选；
 * 能力槽数多于界面格子数时回报 hiddenCaps，由界面提示"另有 N 个槽位不在此界面"。
 */
public final class SlotNumbering {
    private SlotNumbering() {
    }

    /** 界面上捕获到的一个格子。capHint 非 null = 实例匹配结果（精确）。 */
    public record MenuSlot(int seq, int x, int y, String item, int count, Integer capHint) {
        public boolean hasSignature() {
            return item != null && !item.isEmpty();
        }
    }

    /** 服务端回报的一个能力槽（SFM slot N 的真实语义）。 */
    public record CapSlot(int index, String item, int count) {
        public boolean hasSignature() {
            return item != null && !item.isEmpty();
        }
    }

    /**
     * 编号结果。number[seq] = 显示编号（校准失败时为空间序号；校准后不可寻址格为 -1）；
     * addressable[seq] = 是否可寻址（可点选、可输出）。
     */
    public record Result(List<MenuSlot> slots, int[] number, boolean[] addressable,
                         boolean calibrated, int capTotal, int hiddenCaps) {
        public int size() {
            return slots.size();
        }

        public boolean isAddressable(int seq) {
            return seq >= 0 && seq < size() && addressable[seq];
        }

        public int number(int seq) {
            return number[seq];
        }

        /** 显示编号 → 可寻址菜单格 seq（未命中 -1）。 */
        public int seqOfNumber(int n) {
            for (int i = 0; i < size(); i++) {
                if (addressable[i] && number[i] == n) return i;
            }
            return -1;
        }
    }

    /**
     * 计算编号。caps == null = 无服务端数据（未校准兜底：纯空间编号）。
     *
     * @param capTotal 服务端回报的能力槽总数（null 时取 caps.size()）
     */
    public static Result compute(List<MenuSlot> slots, List<CapSlot> caps, Integer capTotal) {
        int n = slots.size();
        int[] number = new int[n];
        boolean[] addressable = new boolean[n];
        Arrays.fill(addressable, true);

        // ---- 兜底：无能力槽数据 = 纯空间编号（先 y 后 x，左上 0 起）----
        if (caps == null) {
            Integer[] order = new Integer[n];
            for (int i = 0; i < n; i++) order[i] = i;
            Arrays.sort(order, (a, b) -> slots.get(a).y() != slots.get(b).y()
                    ? Integer.compare(slots.get(a).y(), slots.get(b).y())
                    : Integer.compare(slots.get(a).x(), slots.get(b).x()));
            for (int rank = 0; rank < n; rank++) number[order[rank]] = rank;
            return new Result(slots, number, addressable, false, capTotal == null ? -1 : capTotal, 0);
        }

        // ---- 校准 ----
        int total = capTotal != null ? capTotal : caps.size();
        int[] claimedCap = new int[n];
        Arrays.fill(claimedCap, -1);
        boolean[] capUsed = new boolean[Math.max(total, 0)];

        // ① 实例匹配提示（精确）；两格争同一槽 = 提示不可信，全部撤回
        for (int i = 0; i < n; i++) {
            Integer hint = slots.get(i).capHint();
            if (hint == null || hint < 0 || hint >= capUsed.length) continue;
            if (capUsed[hint]) {
                for (int j = 0; j < n; j++) if (claimedCap[j] == hint) claimedCap[j] = -1;
                capUsed[hint] = false;
                continue;
            }
            claimedCap[i] = hint;
            capUsed[hint] = true;
        }

        // ② 内容签名唯一匹配（按能力槽序号推进）
        List<Integer> deferredCaps = new ArrayList<>();
        for (int c = 0; c < total && c < caps.size(); c++) {
            if (capUsed[c]) continue;
            List<Integer> candidates = candidatesOf(slots, claimedCap, caps.get(c));
            if (candidates.size() == 1) {
                claimedCap[candidates.get(0)] = c;
                capUsed[c] = true;
            } else if (candidates.size() > 1) {
                deferredCaps.add(c);
            }
        }

        // ③ 歧义组：剩余能力槽（按序号）× 候选格（按空间序）拉链。
        //    不参与任何歧义槽的格子（内容对不上任何能力槽，如幽灵显示格）不拉入，
        //    留给置灰判定——比盲目拉链更诚实。
        List<Integer> remaining = new ArrayList<>();
        for (int i = 0; i < n; i++) if (claimedCap[i] < 0) remaining.add(i);
        Set<Integer> zippable = new HashSet<>();
        for (int c : deferredCaps) {
            for (int i : candidatesOf(slots, claimedCap, caps.get(c))) zippable.add(i);
        }
        List<Integer> zipSlots = new ArrayList<>();
        for (int i : remaining) if (zippable.contains(i)) zipSlots.add(i);
        zipSlots.sort((a, b) -> slots.get(a).y() != slots.get(b).y()
                ? Integer.compare(slots.get(a).y(), slots.get(b).y())
                : Integer.compare(slots.get(a).x(), slots.get(b).x()));
        deferredCaps.sort(Integer::compareTo);
        for (int k = 0; k < deferredCaps.size() && k < zipSlots.size(); k++) {
            claimedCap[zipSlots.get(k)] = deferredCaps.get(k);
            capUsed[deferredCaps.get(k)] = true;
        }

        // 编号与可寻址性：拿到能力槽的格子显示真实索引；其余置灰
        int hidden = 0;
        for (int c = 0; c < total && c < capUsed.length; c++) if (!capUsed[c]) hidden++;
        for (int i = 0; i < n; i++) {
            if (claimedCap[i] >= 0) {
                number[i] = claimedCap[i];
            } else {
                number[i] = -1;
                addressable[i] = false;
            }
        }
        return new Result(slots, number, addressable, true, total, hidden);
    }

    /** 与能力槽内容签名匹配的未认领格子。两边都空 = 弱匹配（交给歧义拉链）。 */
    private static List<Integer> candidatesOf(List<MenuSlot> slots, int[] claimedCap, CapSlot cap) {
        List<Integer> out = new ArrayList<>();
        for (int i = 0; i < slots.size(); i++) {
            if (claimedCap[i] >= 0) continue;
            MenuSlot m = slots.get(i);
            boolean mSig = m.hasSignature(), cSig = cap.hasSignature();
            if (mSig != cSig) continue;
            if (mSig && (!m.item().equals(cap.item()) || m.count() != cap.count())) continue;
            out.add(i);
        }
        return out;
    }

    /** 选中格子集合 → 升序槽号文本（不可寻址格自动剔除）；空 = "（全部槽位）"。 */
    public static String numbersOf(Result r, Collection<Integer> seqs) {
        TreeSet<Integer> nums = new TreeSet<>();
        for (int seq : seqs) {
            if (r.isAddressable(seq) && r.number(seq) >= 0) nums.add(r.number(seq));
        }
        return compress(nums);
    }

    /** 程序里已写的槽号区间 → 初始选中格子集合。 */
    public static TreeSet<Integer> selectionForRanges(Result r, List<BProgram.SlotRange> ranges) {
        TreeSet<Integer> sel = new TreeSet<>();
        if (ranges == null) return sel;
        for (BProgram.SlotRange range : ranges) {
            for (long v = range.first(); v <= range.last(); v++) {
                int seq = r.seqOfNumber((int) v);
                if (seq >= 0) sel.add(seq);
            }
        }
        return sel;
    }

    /** {1,3,4,5,9} → "1,3-5,9"（与 SFML slot 文本同一形式）。 */
    public static String compress(TreeSet<Integer> set) {
        StringBuilder sb = new StringBuilder();
        Integer prev = null, start = null;
        for (Integer v : set) {
            if (prev != null && v == prev + 1) {
                prev = v;
                continue;
            }
            flushRange(sb, start, prev);
            start = v;
            prev = v;
        }
        flushRange(sb, start, prev);
        return sb.toString();
    }

    private static void flushRange(StringBuilder sb, Integer start, Integer end) {
        if (start == null) return;
        if (sb.length() > 0) sb.append(',');
        if (start.equals(end)) sb.append(start);
        else sb.append(start).append('-').append(end);
    }

    // ---- 视图布局（纯逻辑可单测）：归一化 + 防重叠缩放 ----

    /**
     * 校准后没有任何界面格对应的能力槽编号——机器能力面上真实存在、SFM 可寻址、
     * 但 GUI 里没画出来的那些槽（内部缓冲槽、翻页槽等）。调用方可以把它们以
     * 合成格形式补显（编号 = 真实槽位序号，仍然可选）。
     */
    public static List<Integer> unclaimedCapIndexes(Result r, int capTotal) {
        List<Integer> out = new ArrayList<>();
        for (int c = 0; c < capTotal; c++) {
            if (r.seqOfNumber(c) < 0) out.add(c);
        }
        return out;
    }

    /** 格子渲染的最小边长（px）。缩放下限 = 此值/格距，保证格子永不互相重叠。 */
    public static final float MIN_CELL_PX = 8f;

    /**
     * 视图布局结果。shiftX/shiftY = 坐标归一化位移（把最小坐标平移到 0,0，
     * 兼容负数/偏移原点的模组 GUI）；scale ≥ MIN_CELL_PX/格距（防重叠下限），
     * contentW/H = 归一化后按 scale 缩放的内容尺寸（可能大于可用区 → 需要滚动）。
     */
    public record ViewLayout(float scale, int contentW, int contentH, int shiftX, int shiftY) {
    }

    /**
     * 计算选择器视图布局。
     *
     * @param cell   格子逻辑边长（选择器 CELL 常量）
     * @param availW 可用区宽度（已含边距下限）
     * @param availH 可用区高度（已含边距下限）
     */
    public static ViewLayout computeView(int cell, List<MenuSlot> slots, int availW, int availH) {
        if (slots.isEmpty()) {
            return new ViewLayout(1f, cell * 9, cell, 0, 0);
        }
        int minX = Integer.MAX_VALUE, minY = Integer.MAX_VALUE;
        int maxX = Integer.MIN_VALUE, maxY = Integer.MIN_VALUE;
        for (MenuSlot s : slots) {
            minX = Math.min(minX, s.x());
            minY = Math.min(minY, s.y());
            maxX = Math.max(maxX, s.x() + cell);
            maxY = Math.max(maxY, s.y() + cell);
        }
        int rawW = Math.max(maxX - minX, cell * 9);   // 最小宽度兜底：空旷布局也协调
        int rawH = Math.max(maxY - minY, cell);
        float fit = Math.min(1f, Math.min(availW / (float) rawW, availH / (float) rawH));
        float scale = Math.max(fit, MIN_CELL_PX / cell);
        return new ViewLayout(scale,
                Math.round(rawW * scale), Math.round(rawH * scale),
                -minX, -minY);
    }
}
