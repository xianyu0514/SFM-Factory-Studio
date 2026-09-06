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
