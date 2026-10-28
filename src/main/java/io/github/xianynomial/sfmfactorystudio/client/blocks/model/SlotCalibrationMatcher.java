package io.github.xianynomial.sfmfactorystudio.client.blocks.model;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** Passive matching over immutable item/count snapshots; no game objects or inventory writes. */
public final class SlotCalibrationMatcher {
    public record Anchor(int menuSlot, int direction, int capabilitySlot) {}

    private static final class Pair {
        final int index;
        int streak;
        int jointChanges;
        Pair(int index) { this.index = index; }
    }

    private static final class Face {
        // Every index below this bound has been considered. Removed pairs stay retired,
        // including when a capability shrinks and later grows back to its former size.
        int seenSlots;
        final List<Pair> candidates = new ArrayList<>();
    }

    private Face[][] faces = new Face[0][];

    public void reset() { faces = new Face[0][]; }

    public List<Anchor> compare(long[] previousMenu, long[] menu,
                                long[][] previousCapabilities, long[][] capabilities) {
        if (faces.length != menu.length) faces = new Face[menu.length][capabilities.length];
        Map<Long, Integer> menuCounts = frequencies(menu);
        List<Map<Long, Integer>> faceCounts = new ArrayList<>();
        for (long[] side : capabilities) faceCounts.add(frequencies(side));
        List<Anchor> anchors = new ArrayList<>();
        for (int j = 0; j < menu.length; j++) {
            if (menu[j] == Long.MIN_VALUE) continue;
            for (int d = 0; d < capabilities.length; d++) {
                Face face = faces[j][d];
                if (face == null) faces[j][d] = face = new Face();
                long[] now = capabilities[d];
                long[] before = previousCapabilities[d];
                boolean unique = menu[j] != 0 && menuCounts.get(menu[j]) == 1
                        && faceCounts.get(d).getOrDefault(menu[j], 0) == 1;
                // Only surviving candidates are revisited; order remains menu / side / index.
                int retained = 0;
                for (Pair pair : face.candidates) {
                    if (pair.index >= now.length) {
                        face.candidates.set(retained++, pair);
                    } else if (!matches(pair.index, previousMenu[j], menu[j], before, now)) {
                        // Permanently disqualified, just as in the original matcher.
                    } else if (advance(pair, previousMenu[j], menu[j], unique)) {
                        anchors.add(new Anchor(j, d, pair.index));
                    } else {
                        face.candidates.set(retained++, pair);
                    }
                }
                face.candidates.subList(retained, face.candidates.size()).clear();
                for (int k = face.seenSlots; k < now.length; k++) {
                    if (!matches(k, previousMenu[j], menu[j], before, now)) continue;
                    Pair pair = new Pair(k);
                    advance(pair, previousMenu[j], menu[j], unique);
                    face.candidates.add(pair);
                }
                face.seenSlots = Math.max(face.seenSlots, now.length);
            }
        }
        return anchors;
    }

    private static boolean matches(int k, long previous, long current, long[] before, long[] now) {
        return current == now[k] && previous == (k < before.length ? before[k] : Long.MIN_VALUE);
    }

    private static boolean advance(Pair pair, long previous, long current, boolean unique) {
        pair.streak++;
        if (previous != current && current != 0) pair.jointChanges++;
        return (unique && pair.streak >= 3)
                || (pair.streak >= 5 && pair.jointChanges >= 1 && current != 0);
    }

    private static Map<Long, Integer> frequencies(long[] values) {
        Map<Long, Integer> result = new HashMap<>();
        for (long value : values) result.merge(value, 1, Integer::sum);
        return result;
    }

    /** Useful for verifying that rejected/finished pairs no longer consume candidate state. */
    public int candidateCount() {
        int count = 0;
        for (Face[] row : faces) for (Face face : row)
            if (face != null) count += face.candidates.size();
        return count;
    }
}
