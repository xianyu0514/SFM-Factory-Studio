package dev.xianyu.chronosfm.ir;

import dev.xianyu.chronosfm.model.TriggerModel;

import java.util.Objects;

public record CompiledTrigger(
        int triggerIndex,
        Mode mode,
        int intervalTicks,
        TriggerModel.Alignment alignment,
        int offset,
        String legacyReason
) {
    public enum Mode {
        FAST_TIMER,
        LEGACY
    }

    public CompiledTrigger {
        if (triggerIndex < 0) throw new IllegalArgumentException("triggerIndex must be >= 0");
        Objects.requireNonNull(mode, "mode");
        if (mode == Mode.FAST_TIMER) {
            if (intervalTicks <= 0) throw new IllegalArgumentException("fast timer interval must be > 0");
            Objects.requireNonNull(alignment, "alignment");
            offset = Math.floorMod(offset, intervalTicks);
        }
    }

    public boolean isDue(long localTick, long globalTick) {
        if (mode == Mode.LEGACY) return true;
        long tick = alignment == TriggerModel.Alignment.LOCAL ? localTick : globalTick;
        return Math.floorMod(tick, intervalTicks) == offset;
    }
}
