package dev.xianyu.chronosfm.runtime;

import dev.xianyu.chronosfm.ir.CompiledProgramPlan;
import dev.xianyu.chronosfm.ir.TransferRegion;

import java.util.List;
import java.util.Objects;

public final class ExactOrderTransferPlan {
    private final CompiledProgramPlan program;

    public ExactOrderTransferPlan(CompiledProgramPlan program) {
        this.program = Objects.requireNonNull(program, "program");
    }

    public List<TransferRegion> regionsForTick(long localTick, long globalTick) {
        int[] due = program.dueTriggerIndexes(localTick, globalTick);
        if (due.length == 0) return List.of();

        boolean[] dueFlags = new boolean[program.triggers().size()];
        for (int trigger : due) dueFlags[trigger] = true;

        return program.transferRegions().stream()
                .filter(region -> dueFlags[region.triggerIndex()])
                .sorted((a, b) -> Integer.compare(a.exactOrderOrdinal(), b.exactOrderOrdinal()))
                .toList();
    }
}
