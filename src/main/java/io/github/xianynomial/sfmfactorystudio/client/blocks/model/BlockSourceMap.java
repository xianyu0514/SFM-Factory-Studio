package io.github.xianynomial.sfmfactorystudio.client.blocks.model;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 积木 id ↔ 源码字符区间 {@code [start, end)} 的双向映射。
 *
 * <p>服务于两件事：
 * <ul>
 *   <li>代码光标落在哪张卡片上（{@link #blockAt(int)}）；</li>
 *   <li>点卡片时跳到哪一行（{@link #rangeOf(long)} / {@link #startOf(long)}）。</li>
 * </ul>
 *
 * <p><b>生命周期</b>：与一次 {@link BProgram} 解析绑定。{@code BProgram} 的
 * {@code Statement}/{@code Trigger} id 是全局自增的，每次解析都会拿到新 id，
 * 所以本表也必须在每次解析时重建——两边天然一致，不需要额外失效逻辑。
 *
 * <p><b>为什么是旁路表</b>：{@code BProgram} 是纯声明式 AST（258 个回归测试依赖它），
 * 往里加位置字段会污染序列化。位置只在这里维护。
 */
public final class BlockSourceMap {
    /** 空表：未解析、代码未同步或程序为空时使用，所有查询返回"没有"。 */
    public static final BlockSourceMap EMPTY = new BlockSourceMap();

    /** 回溯深度：嵌套区间（if 里套 input）最多几层，8 层足够且保证 O(1) 上界。 */
    private static final int MAX_NEST_LOOKBACK = 8;

    private final Map<Long, int[]> byBlock = new HashMap<>();
    /** 扁平化的 [id, start, end]，按 start 升序（懒排序）。 */
    private final List<long[]> spans = new ArrayList<>();
    private boolean sorted = true;

    /**
     * 记录一个积木的源码区间。
     *
     * @param blockId 积木 id（{@code Statement.id} 或 {@code Trigger.id}）
     * @param start   起始字符偏移（含）
     * @param end     结束字符偏移（不含）
     */
    public void put(long blockId, int start, int end) {
        if (blockId <= 0 || end <= start || start < 0) return;
        byBlock.put(blockId, new int[]{start, end});
        spans.add(new long[]{blockId, start, end});
        sorted = false;
    }

    /**
     * 光标偏移落在哪个积木上。
     *
     * <p>取<b>最内层</b>区间：嵌套语句（{@code if} 内的 {@code input}）的区间会包住子语句，
     * 光标落在子语句里时应定位到子语句那张卡片，而不是外层 {@code if}。
     *
     * @return 积木 id；没有命中返回 {@code -1}
     */
    public long blockAt(int offset) {
        if (spans.isEmpty()) return -1;
        ensureSorted();
        // 二分：最后一个 start <= offset 的位置
        int lo = 0, hi = spans.size() - 1, hit = -1;
        while (lo <= hi) {
            int mid = (lo + hi) >>> 1;
            if (spans.get(mid)[1] <= offset) {
                hit = mid;
                lo = mid + 1;
            } else {
                hi = mid - 1;
            }
        }
        if (hit < 0) return -1;
        // 向前回溯有限个，挑包含 offset 且长度最短的（最内层）
        long best = -1;
        long bestLen = Long.MAX_VALUE;
        int floor = Math.max(0, hit - MAX_NEST_LOOKBACK + 1);
        for (int i = hit; i >= floor; i--) {
            long[] s = spans.get(i);
            if (offset >= s[1] && offset < s[2]) {
                long len = s[2] - s[1];
                if (len < bestLen) {
                    bestLen = len;
                    best = s[0];
                }
            }
        }
        return best;
    }

    /** 积木对应的源码区间 {@code [start, end)}；无记录返回 {@code null}。 */
    public int[] rangeOf(long blockId) {
        return byBlock.get(blockId);
    }

    /** 积木对应的起始偏移；无记录返回 {@code -1}。 */
    public int startOf(long blockId) {
        int[] r = byBlock.get(blockId);
        return r == null ? -1 : r[0];
    }

    public boolean isEmpty() {
        return byBlock.isEmpty();
    }

    public int size() {
        return byBlock.size();
    }

    private void ensureSorted() {
        if (sorted) return;
        spans.sort(Comparator.comparingLong(s -> s[1]));
        sorted = true;
    }
}
