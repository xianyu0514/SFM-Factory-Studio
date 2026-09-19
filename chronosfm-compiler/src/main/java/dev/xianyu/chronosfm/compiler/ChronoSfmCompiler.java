package dev.xianyu.chronosfm.compiler;

import dev.xianyu.chronosfm.ir.CompiledProgramPlan;
import dev.xianyu.chronosfm.ir.CompiledTrigger;
import dev.xianyu.chronosfm.ir.DependencyIndex;
import dev.xianyu.chronosfm.ir.TransferRegion;
import dev.xianyu.chronosfm.model.ProgramModel;
import dev.xianyu.chronosfm.model.StatementModel;
import dev.xianyu.chronosfm.model.TriggerModel;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

public final class ChronoSfmCompiler {
    public CompiledProgramPlan compile(ProgramModel program) {
        Objects.requireNonNull(program, "program");

        List<CompiledTrigger> triggers = new ArrayList<>(program.triggers().size());
        List<TransferRegion> regions = new ArrayList<>();

        int regionId = 0;
        int order = 0;
        boolean requiresLegacy = false;

        for (int triggerIndex = 0; triggerIndex < program.triggers().size(); triggerIndex++) {
            TriggerModel trigger = program.triggers().get(triggerIndex);

            if (trigger instanceof TriggerModel.Timer timer) {
                triggers.add(new CompiledTrigger(
                        triggerIndex,
                        CompiledTrigger.Mode.FAST_TIMER,
                        timer.intervalTicks(),
                        timer.alignment(),
                        timer.offset(),
                        null
                ));
            } else if (trigger instanceof TriggerModel.Opaque opaque) {
                triggers.add(new CompiledTrigger(
                        triggerIndex,
                        CompiledTrigger.Mode.LEGACY,
                        0,
                        null,
                        0,
                        opaque.reason()
                ));
                requiresLegacy = true;
            } else {
                throw new IllegalStateException("Unhandled trigger type: " + trigger.getClass());
            }

            List<StatementModel> statements = trigger.statements();
            for (int statementIndex = 0; statementIndex < statements.size(); statementIndex++) {
                StatementModel statement = statements.get(statementIndex);
                if (statement instanceof StatementModel.Transfer transfer) {
                    regions.add(new TransferRegion(
                            regionId++,
                            triggerIndex,
                            statementIndex,
                            order++,
                            transfer.sourceLabel(),
                            transfer.destinationLabel(),
                            transfer.resourceKey(),
                            transfer.each(),
                            transfer.maxQuantity(),
                            transfer.retainQuantity()
                    ));
                } else if (statement instanceof StatementModel.Opaque) {
                    requiresLegacy = true;
                } else {
                    throw new IllegalStateException("Unhandled statement type: " + statement.getClass());
                }
            }
        }

        List<TransferRegion> immutableRegions = List.copyOf(regions);
        return new CompiledProgramPlan(
                List.copyOf(triggers),
                immutableRegions,
                DependencyIndex.build(immutableRegions),
                requiresLegacy
        );
    }
}
