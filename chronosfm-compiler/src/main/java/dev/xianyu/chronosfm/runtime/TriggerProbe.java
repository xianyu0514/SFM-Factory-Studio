package dev.xianyu.chronosfm.runtime;

import dev.xianyu.chronosfm.ir.CompiledProgramPlan;
import dev.xianyu.chronosfm.ir.CompiledTrigger;

import java.util.Arrays;
import java.util.Objects;

/**
 * Allocation-light preflight used before an expensive SFM ProgramContext is built.
 * LEGACY triggers are treated as due so this gate can never suppress unknown semantics.
 */
public final class TriggerProbe {
    private final CompiledProgramPlan plan;

    public TriggerProbe(CompiledProgramPlan plan) {
        this.plan = Objects.requireNonNull(plan, "plan");
    }

    public Result probe(long localTick, long globalTick) {
        int[] scratch = new int[plan.triggers().size()];
        int count = 0;
        boolean legacyDue = false;

        for (CompiledTrigger trigger : plan.triggers()) {
            if (!trigger.isDue(localTick, globalTick)) continue;
            scratch[count++] = trigger.triggerIndex();
            legacyDue |= trigger.mode() == CompiledTrigger.Mode.LEGACY;
        }

        return new Result(Arrays.copyOf(scratch, count), legacyDue);
    }

    public record Result(int[] dueTriggerIndexes, boolean requiresLegacyContext) {
        public Result {
            dueTriggerIndexes = dueTriggerIndexes.clone();
        }

        public boolean maySkipFullContext() {
            return dueTriggerIndexes.length == 0;
        }

        @Override
        public int[] dueTriggerIndexes() {
            return dueTriggerIndexes.clone();
        }
    }
}
