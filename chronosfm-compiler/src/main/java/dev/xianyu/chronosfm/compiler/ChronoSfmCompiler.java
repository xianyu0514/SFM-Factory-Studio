package dev.xianyu.chronosfm.compiler;

import dev.xianyu.chronosfm.ir.CompiledProgramPlan;
import dev.xianyu.chronosfm.ir.CompiledTrigger;
import dev.xianyu.chronosfm.ir.DependencyIndex;
import dev.xianyu.chronosfm.ir.ExactOperation;
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
        List<ExactOperation> operations = new ArrayList<>();

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
            } else if (trigger instanceof TriggerModel.Redstone) {
                triggers.add(new CompiledTrigger(
                        triggerIndex,
                        CompiledTrigger.Mode.FAST_REDSTONE,
                        0,
                        null,
                        0,
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
                int statementOrder = order++;

                if (statement instanceof StatementModel.Transfer transfer) {
                    regions.add(new TransferRegion(
                            regionId++,
                            triggerIndex,
                            statementIndex,
                            statementOrder,
                            transfer.sourceLabel(),
                            transfer.destinationLabel(),
                            transfer.resourceKey(),
                            transfer.each(),
                            transfer.maxQuantity(),
                            transfer.retainQuantity()
                    ));
                } else if (statement instanceof StatementModel.Input input) {
                    operations.add(new ExactOperation.InputOp(
                            triggerIndex,
                            statementIndex,
                            statementOrder,
                            input.selector(),
                            input.resources(),
                            input.each()
                    ));
                } else if (statement instanceof StatementModel.Output output) {
                    operations.add(new ExactOperation.OutputOp(
                            triggerIndex,
                            statementIndex,
                            statementOrder,
                            output.selector(),
                            output.resources(),
                            output.each(),
                            output.emptySlotsOnly()
                    ));
                } else if (statement instanceof StatementModel.Opaque opaque) {
                    operations.add(new ExactOperation.LegacyBarrier(
                            triggerIndex,
                            statementIndex,
                            statementOrder,
                            opaque.reason()
                    ));
                    requiresLegacy = true;
                } else {
                    throw new IllegalStateException("Unhandled statement type: " + statement.getClass());
                }
            }
        }

        List<TransferRegion> immutableRegions = List.copyOf(regions);
        List<ExactOperation> immutableOperations = List.copyOf(operations);
        return new CompiledProgramPlan(
                List.copyOf(triggers),
                immutableRegions,
                immutableOperations,
                DependencyIndex.build(immutableRegions, immutableOperations),
                requiresLegacy
        );
    }
}
