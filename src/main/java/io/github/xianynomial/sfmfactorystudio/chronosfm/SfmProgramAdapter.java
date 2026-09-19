package io.github.xianynomial.sfmfactorystudio.chronosfm;

import ca.teamdman.sfm.common.resourcetype.ResourceType;
import ca.teamdman.sfml.ast.InputStatement;
import ca.teamdman.sfml.ast.Interval;
import ca.teamdman.sfml.ast.LabelAccess;
import ca.teamdman.sfml.ast.OutputStatement;
import ca.teamdman.sfml.ast.Program;
import ca.teamdman.sfml.ast.RedstoneTrigger;
import ca.teamdman.sfml.ast.ResourceLimits;
import ca.teamdman.sfml.ast.Statement;
import ca.teamdman.sfml.ast.TimerTrigger;
import ca.teamdman.sfml.ast.Trigger;
import dev.xianyu.chronosfm.adapter.ProgramModelAdapter;
import dev.xianyu.chronosfm.model.ProgramModel;
import dev.xianyu.chronosfm.model.StatementModel;
import dev.xianyu.chronosfm.model.TriggerModel;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * Conservative SFM 4.34.0 / MC 1.21.1 adapter.
 *
 * Timer/Redstone trigger timing and flat INPUT/OUTPUT structure are compiled.
 * Any other statement becomes a LegacyBarrier in the core IR, preventing future
 * optimizers from moving operations across semantics that have not been proven.
 */
public final class SfmProgramAdapter implements ProgramModelAdapter<Program> {
    @Override
    public ProgramModel adapt(Program source) {
        List<TriggerModel> triggers = new ArrayList<>(source.triggers().size());
        for (Trigger trigger : source.triggers()) {
            triggers.add(adaptTrigger(trigger));
        }
        return new ProgramModel(triggers);
    }

    private TriggerModel adaptTrigger(Trigger trigger) {
        List<StatementModel> body = adaptBody(trigger);

        if (trigger instanceof TimerTrigger timer) {
            Interval interval = timer.interval();
            TriggerModel.Alignment alignment = switch (interval.alignment()) {
                case LOCAL -> TriggerModel.Alignment.LOCAL;
                case GLOBAL -> TriggerModel.Alignment.GLOBAL;
            };
            return new TriggerModel.Timer(interval.ticks(), alignment, interval.offset(), body);
        }

        if (trigger instanceof RedstoneTrigger) {
            return new TriggerModel.Redstone(body);
        }

        return new TriggerModel.Opaque("unsupported SFM trigger: " + trigger.getClass().getName(), body);
    }

    private static List<StatementModel> adaptBody(Trigger trigger) {
        List<StatementModel> result = new ArrayList<>();
        for (Statement statement : trigger.getBlock().statements()) {
            if (statement instanceof InputStatement input) {
                result.add(new StatementModel.Input(
                        adaptSelector(input.labelAccess()),
                        adaptResources(input.resourceLimits()),
                        input.each()
                ));
            } else if (statement instanceof OutputStatement output) {
                result.add(new StatementModel.Output(
                        adaptSelector(output.labelAccess()),
                        adaptResources(output.resourceLimits()),
                        output.each(),
                        output.emptySlotsOnly()
                ));
            } else {
                result.add(new StatementModel.Opaque(
                        "unsupported SFM statement: " + statement.getClass().getName()
                ));
            }
        }
        return List.copyOf(result);
    }

    private static StatementModel.EndpointSelector adaptSelector(LabelAccess access) {
        return new StatementModel.EndpointSelector(
                access.labels().stream().map(label -> label.name()).toList(),
                access.sides().sides().stream().map(Object::toString).toList(),
                access.slots().toString(),
                access.roundRobin().getBehaviour().name()
        );
    }

    private static StatementModel.ResourceSelector adaptResources(ResourceLimits limits) {
        ResourceType<?, ?, ?>[] referenced = limits.getReferencedResourceTypes();
        List<String> types = referenced == null
                ? List.of()
                : Arrays.stream(referenced)
                        .map(type -> type.getClass().getName() + "|" + type.displayAsCapabilityClass())
                        .toList();
        return new StatementModel.ResourceSelector(types, limits.toString());
    }
}
