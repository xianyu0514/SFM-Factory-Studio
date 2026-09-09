package io.github.xianynomial.sfmfactorystudio.client.blocks.model;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Pure layout primitives for the block editor's trigger cards (方案 A 自由坐标).
 * No Minecraft / GuiGraphics dependencies, so this class is unit-testable.
 *
 * The screen owns the mutable state (IdentityHashMap of positions); these
 * functions take plain arrays/lists and return new values, keeping the tricky
 * parts — overlap resolution and saved-position matching — regression-testable.
 */
public final class CardLayouts {

    /** Snap grid: card positions always land on multiples of this. */
    public static final int GRID = 8;

    /** Vertical gap kept between cards that overlap in x. */
    public static final int CARD_GAP = 24;

    private CardLayouts() {
    }

    /** Round to the nearest grid multiple. */
    public static int snap(int v) {
        return Math.round(v / (float) GRID) * GRID;
    }

    /** Y coordinate for an exact same-column clone placed under its source card. */
    public static int directlyBelow(int sourceY, int sourceHeight) {
        return snap(sourceY + sourceHeight + CARD_GAP);
    }

    /**
     * Resolve vertical overlaps: whenever two cards intersect, the one later in
     * program order is pushed below the earlier one (program order = priority).
     * keepIdx (or -1) names a card that never moves — everything else yields to
     * it, including cards that come earlier in program order.
     *
     * @param xs, ys, ws, hs  card rectangles (ys is the live position array)
     * @param keepIdx         index of the protected card, or -1
     * @return final ys for every card; never smaller than the input ys
     */
    public static int[] resolveOverlaps(int[] xs, int[] ys, int[] ws, int[] hs, int keepIdx) {
        int n = xs.length;
        int[] out = ys.clone();
        for (int pass = 0; pass < 8; pass++) {
            boolean moved = false;
            if (keepIdx >= 0 && keepIdx < n) {
                for (int i = 0; i < n; i++) {
                    if (i == keepIdx) continue;
                    if (!intersects(xs[i], out[i], ws[i], hs[i], xs[keepIdx], out[keepIdx], ws[keepIdx], hs[keepIdx])) continue;
                    int ny = snap(out[keepIdx] + hs[keepIdx] + CARD_GAP);
                    if (out[i] != ny) {
                        out[i] = ny;
                        moved = true;
                    }
                }
            }
            for (int i = 0; i < n; i++) {
                for (int j = i + 1; j < n; j++) {
                    if (i == keepIdx || j == keepIdx) continue;
                    if (!intersects(xs[i], out[i], ws[i], hs[i], xs[j], out[j], ws[j], hs[j])) continue;
                    int ny = snap(out[i] + hs[i] + CARD_GAP);
                    if (out[j] != ny) {
                        out[j] = ny;
                        moved = true;
                    }
                }
            }
            if (!moved) break;
        }
        return out;
    }

    public static boolean intersects(int ax, int ay, int aw, int ah, int bx, int by, int bw, int bh) {
        return ax < bx + bw && ax + aw > bx && ay < by + bh && ay + ah > by;
    }

    /**
     * Card fingerprint for saved-position matching. Only the trigger header
     * counts — editing a card's blocks must not lose its position; changing
     * header values (e.g. every 20 → every 40) makes it a different card.
     * Identical cards (same fingerprint) are told apart by order of appearance.
     */
    public static String triggerKey(BProgram.Trigger t) {
        if (t instanceof BProgram.TimerTrigger tt) {
            return "t:" + tt.count + ":" + tt.unit + ":" + tt.global + ":" + tt.plus;
        }
        return "p";
    }

    /**
     * 副本栈走链（纯逻辑可单测）：候选为同列同指纹副本，给定各自 top/height
     * 与起点 self，沿"紧贴 ±band"先向上走到栈顶、再向下走到栈底，返回栈底
     * 候选下标（self 单独成栈时返回 self）。used 守卫保证每张卡至多访问一次，
     * 任何几何（同位/重叠/乱序）都严格终止——screen 层旧 while(true) 实现
     * 没有守卫且以"候选 top"对比当前 y（永远走不下去），栈中非栈顶卡会在
     * 栈顶卡上原地打转，而它被 renderCard 每帧每卡调用 = 主线程冻结
     * （2026-09-09 "游戏崩溃（Application Hang）"反馈的根因）。
     */
    public static int farthestInStack(int[] tops, int[] heights, int self, int band) {
        int[] chain = stackChain(tops, heights, self, band);
        return chain[chain.length - 1];
    }

    /**
     * 副本栈走链·完整链版：与 {@link #farthestInStack} 同一套"先向上到栈顶、
     * 再向下到栈底 ±band 紧贴"规则，但返回按"栈顶→栈底"排序的完整候选下标
     * 链，供副本栈随高度变化重对齐（EditorLayout.healCopyStacks）使用。
     * used 守卫保证每张卡至多访问一次，任何几何（同位/重叠/乱序）都严格终止。
     */
    public static int[] stackChain(int[] tops, int[] heights, int self, int band) {
        int n = tops.length;
        boolean[] used = new boolean[n];
        used[self] = true;
        // 向上：候选底边（top+height）贴当前 top，取离得最近的
        int[] up = new int[n];
        int upN = 0;
        int cur = self;
        while (true) {
            int best = -1, bestTop = Integer.MIN_VALUE;
            for (int i = 0; i < n; i++) {
                if (used[i]) continue;
                if (Math.abs(tops[i] + heights[i] - tops[cur]) <= band && tops[i] > bestTop) {
                    bestTop = tops[i];
                    best = i;
                }
            }
            if (best < 0) break;
            used[best] = true;
            up[upN++] = best;
            cur = best;
        }
        int[] out = new int[upN + n];
        for (int i = 0; i < upN; i++) out[i] = up[upN - 1 - i];
        out[upN] = self;
        int cnt = upN + 1;
        // 向下：候选 top 贴当前底边（top+height），取离得最近的
        cur = self;
        while (true) {
            int best = -1, bestTop = Integer.MAX_VALUE;
            int bottomEdge = tops[cur] + heights[cur];
            for (int i = 0; i < n; i++) {
                if (used[i]) continue;
                if (Math.abs(tops[i] - bottomEdge) <= band && tops[i] < bestTop) {
                    bestTop = tops[i];
                    best = i;
                }
            }
            if (best < 0) break;
            used[best] = true;
            out[cnt++] = best;
            cur = best;
        }
        return java.util.Arrays.copyOf(out, cnt);
    }

    /** 触发头等价（triggerKey 的键分量：类型/数量/单位/全局/偏移），按字段直比零分配——
     *  页脚 − 可见性、＋续叠与副本栈重对齐每帧/每 pass 调用，禁用字符串拼接指纹。 */
    public static boolean sameTriggerHeader(BProgram.Trigger a, BProgram.Trigger b) {
        if (a instanceof BProgram.TimerTrigger ta) {
            if (!(b instanceof BProgram.TimerTrigger tb)) return false;
            return ta.count == tb.count && ta.unit == tb.unit
                    && ta.global == tb.global && ta.plus == tb.plus;
        }
        return a instanceof BProgram.PulseTrigger && b instanceof BProgram.PulseTrigger;
    }

    /**
     * Match wanted keys against saved keys: each saved entry can be used once,
     * and duplicates are paired up in order of appearance.
     *
     * @return one index into {@code have} per entry of {@code want}, -1 if unmatched
     */
    public static int[] matchByKeys(List<String> want, List<String> have) {
        Map<String, ArrayDeque<Integer>> byKey = new LinkedHashMap<>();
        for (int i = 0; i < have.size(); i++) {
            byKey.computeIfAbsent(have.get(i), k -> new ArrayDeque<>()).add(i);
        }
        int[] out = new int[want.size()];
        for (int i = 0; i < want.size(); i++) {
            ArrayDeque<Integer> q = byKey.get(want.get(i));
            Integer idx = q == null ? null : q.poll();
            out[i] = idx == null ? -1 : idx;
        }
        return out;
    }

    /** Convenience: the keys of a program's triggers, in order. */
    public static List<String> keysOf(List<BProgram.Trigger> triggers) {
        List<String> out = new ArrayList<>(triggers.size());
        for (BProgram.Trigger t : triggers) out.add(triggerKey(t));
        return out;
    }
}
