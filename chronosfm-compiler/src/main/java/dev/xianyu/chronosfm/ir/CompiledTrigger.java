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
        FAST_REDSTONE,
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

    public boolean isDue(long localTick, long globalTick, int redstonePulses) {
        return switch (mode) {
            case LEGACY -> true;
            case FAST_REDSTONE -> redstonePulses > 0;
            case FAST_TIMER -> {
                long tick = alignment == TriggerModel.Alignment.LOCAL ? localTick : globalTick;
                yield Math.floorMod(tick, intervalTicks) == offset;
            }
        };
    }

    public boolean isDue(long localTick, long globalTick) {
        return isDue(localTick, globalTick, 0);
    }
}
