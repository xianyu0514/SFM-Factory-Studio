package io.github.xianynomial.sfmfactorystudio.test;

import io.github.xianynomial.sfmfactorystudio.client.blocks.model.SlotCalibrationMatcher;
import io.github.xianynomial.sfmfactorystudio.client.blocks.model.SlotCalibrationMatcher.Anchor;
import org.junit.jupiter.api.Test;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class SlotCalibrationMatcherTest {
    /** The original exhaustive matcher, kept independent as a behavior oracle. */
    private static final class Original {
        static final class Pair { int streak, joint; boolean retired; }
        final Map<String, Pair> pairs = new HashMap<>();
        List<Anchor> compare(long[] prev, long[] now, long[][] prevCap, long[][] cap) {
            List<Anchor> result = new ArrayList<>();
            for (int j = 0; j < now.length; j++) {
                if (now[j] == Long.MIN_VALUE) continue;
                for (int d = 0; d < cap.length; d++) for (int k = 0; k < cap[d].length; k++) {
                    Pair p = pairs.computeIfAbsent(j + ":" + d + ":" + k, ignored -> new Pair());
                    if (p.retired) continue;
                    long before = k < prevCap[d].length ? prevCap[d][k] : Long.MIN_VALUE;
                    if (before != prev[j] || cap[d][k] != now[j]) { p.retired = true; continue; }
                    p.streak++;
                    if (prev[j] != now[j] && before != cap[d][k] && now[j] != 0 && cap[d][k] != 0) p.joint++;
                    boolean uniqueSide = true, uniqueMenu = true;
                    for (int other = 0; other < cap[d].length; other++)
                        if (other != k && cap[d][other] == now[j]) uniqueSide = false;
                    for (int other = 0; other < now.length; other++)
                        if (other != j && now[other] == now[j]) uniqueMenu = false;
                    if ((now[j] != 0 && uniqueSide && uniqueMenu && p.streak >= 3)
                            || (p.streak >= 5 && p.joint >= 1 && now[j] != 0 && cap[d][k] != 0)) {
                        p.retired = true;
                        result.add(new Anchor(j, d, k));
                    }
                }
            }
            return result;
        }
    }

    private static long[][] sides(long... values) {
        long[][] result = new long[7][];
        for (int d = 0; d < 7; d++) result[d] = values.clone();
        return result;
    }

    @Test void randomDynamicTimelinesMatchOriginalExactly() {
        Random random = new Random(901);
        int totalAnchors = 0;
        for (int trial = 0; trial < 300; trial++) {
            SlotCalibrationMatcher matcher = new SlotCalibrationMatcher();
            Original original = new Original();
            long[] prev = {0, 0, 1, 2, 3, Long.MIN_VALUE};
            long[][] prevCap = sides(0, 0, 1, 2, 3, 8);
            for (int tick = 0; tick < 80; tick++) {
                long[] now = prev.clone();
                long[][] cap = new long[7][];
                if (tick % 7 == 0) now[random.nextInt(now.length)] = random.nextInt(5);
                if (tick % 13 == 0) now[5] = Long.MIN_VALUE;
                for (int d = 0; d < 7; d++) {
                    int length = tick % 11 == 0 ? random.nextInt(10) : prevCap[d].length;
                    cap[d] = Arrays.copyOf(prevCap[d], length);
                    for (int k = 0; k < length; k++) {
                        if (k < now.length && now[k] != Long.MIN_VALUE && random.nextInt(10) != 0) cap[d][k] = now[k];
                        else if (random.nextInt(8) == 0) cap[d][k] = random.nextInt(5);
                    }
                }
                List<Anchor> expected = original.compare(prev, now, prevCap, cap);
                assertEquals(expected, matcher.compare(prev, now, prevCap, cap), "trial " + trial + " sample " + tick);
                totalAnchors += expected.size();
                prev = now; prevCap = cap;
                if (tick == 40) { matcher.reset(); original.pairs.clear(); }
            }
        }
        assertTrue(totalAnchors > 1000, "Exercise successful learning as well as rejected candidates");
    }

    @Test void repeatedContentsStillLearnOnJointChangeAtTheSameSample() {
        SlotCalibrationMatcher matcher = new SlotCalibrationMatcher();
        long[] previous = {0, 0};
        long[][] before = sides(0, 0);
        for (int sample = 1; sample <= 5; sample++) {
            long[] now = sample < 3 ? new long[]{0, 0} : new long[]{9, 9};
            long[][] cap = sides(now);
            List<Anchor> anchors = matcher.compare(previous, now, before, cap);
            assertEquals(sample == 5 ? 28 : 0, anchors.size());
            previous = now; before = cap;
        }
        assertEquals(0, matcher.candidateCount());
    }

    @Test void rejectedPairsStayRejectedAfterShrinkAndRegrowth() {
        SlotCalibrationMatcher matcher = new SlotCalibrationMatcher();
        Original original = new Original();
        long[] menu = {7, 7};
        long[][] prev = sides(4, 7);
        for (int sample = 0; sample < 12; sample++) {
            long[][] now = sample == 2 ? sides() : sides(7, 7, 7);
            assertEquals(original.compare(menu, menu, prev, now), matcher.compare(menu, menu, prev, now));
            prev = now;
        }
        assertEquals(0, matcher.candidateCount()); // Regrowth after an absent baseline retires the surviving pairs too.
    }

    @Test void largeUniqueInventoryRetainsOnlyPlausiblePairsThenReleasesThem() {
        int count = 512;
        long[] menu = new long[count];
        for (int i = 0; i < count; i++) menu[i] = i + 1;
        long[][] cap = sides(menu);
        SlotCalibrationMatcher matcher = new SlotCalibrationMatcher();
        assertTrue(matcher.compare(menu, menu, cap, cap).isEmpty());
        assertEquals(count * 7, matcher.candidateCount()); // Exhaustive implementation allocated count * count * 7 states.
        assertTrue(matcher.compare(menu, menu, cap, cap).isEmpty());
        assertEquals(count * 7, matcher.compare(menu, menu, cap, cap).size());
        assertEquals(0, matcher.candidateCount());
        assertTrue(matcher.compare(menu, menu, cap, cap).isEmpty());
    }
}
