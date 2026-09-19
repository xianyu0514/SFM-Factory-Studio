package dev.xianyu.chronosfm.ir;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

public record CompiledProgramPlan(
        List<CompiledTrigger> triggers,
        List<TransferRegion> transferRegions,
        List<ExactOperation> exactOperations,
        DependencyIndex dependencyIndex,
        boolean requiresLegacyExecution
) {
    public CompiledProgramPlan {
        Objects.requireNonNull(triggers, "triggers");
        Objects.requireNonNull(transferRegions, "transferRegions");
        Objects.requireNonNull(exactOperations, "exactOperations");
        Objects.requireNonNull(dependencyIndex, "dependencyIndex");
        triggers = List.copyOf(triggers);
        transferRegions = List.copyOf(transferRegions);
        exactOperations = List.copyOf(exactOperations);
    }

    public int[] dueTriggerIndexes(long localTick, long globalTick, int redstonePulses) {
        int[] scratch = new int[triggers.size()];
        int count = 0;
        for (CompiledTrigger trigger : triggers) {
            if (trigger.isDue(localTick, globalTick, redstonePulses)) {
                scratch[count++] = trigger.triggerIndex();
            }
        }
        int[] result = new int[count];
        System.arraycopy(scratch, 0, result, 0, count);
        return result;
    }

    public int[] dueTriggerIndexes(long localTick, long globalTick) {
        return dueTriggerIndexes(localTick, globalTick, 0);
    }

    public List<TransferRegion> exactOrderRegionsForTrigger(int triggerIndex) {
        List<TransferRegion> result = new ArrayList<>();
        for (TransferRegion region : transferRegions) {
            if (region.triggerIndex() == triggerIndex) result.add(region);
        }
        result.sort((a, b) -> Integer.compare(a.exactOrderOrdinal(), b.exactOrderOrdinal()));
        return List.copyOf(result);
    }

    public List<ExactOperation> exactOperationsForTrigger(int triggerIndex) {
        List<ExactOperation> result = new ArrayList<>();
        for (ExactOperation operation : exactOperations) {
            if (operation.triggerIndex() == triggerIndex) result.add(operation);
        }
        result.sort((a, b) -> Integer.compare(a.exactOrderOrdinal(), b.exactOrderOrdinal()));
        return List.copyOf(result);
    }
}
