package io.github.xianynomial.sfmfactorystudio.client.blocks.model;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** Preserves the first slot within a 10-pixel radius without scanning all previous slots. */
public final class SlotCaptureCollector {
    private final Map<Long, List<SlotLayoutData.SlotCapture>> cells = new HashMap<>();
    private final List<SlotLayoutData.SlotCapture> slots = new ArrayList<>();
    public boolean add(SlotLayoutData.SlotCapture slot) {
        int x = Math.floorDiv(slot.x(), 10), y = Math.floorDiv(slot.y(), 10);
        for (int dx = -1; dx <= 1; dx++) for (int dy = -1; dy <= 1; dy++) {
            var nearby = cells.get(key(x + dx, y + dy));
            if (nearby == null) continue;
            for (var other : nearby) {
                long distanceX = (long) other.x() - slot.x(), distanceY = (long) other.y() - slot.y();
                if (distanceX * distanceX + distanceY * distanceY < 100) return false;
            }
        }
        cells.computeIfAbsent(key(x, y), unused -> new ArrayList<>()).add(slot);
        slots.add(slot);
        return true;
    }
    private static long key(int x, int y) { return (long) x << 32 | (y & 0xffffffffL); }
    public List<SlotLayoutData.SlotCapture> slots() { return new ArrayList<>(slots); }
}
