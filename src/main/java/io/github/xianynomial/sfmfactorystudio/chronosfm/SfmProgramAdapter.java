package io.github.xianynomial.sfmfactorystudio.chronosfm;

import ca.teamdman.sfml.ast.Interval;
import ca.teamdman.sfml.ast.Program;
import ca.teamdman.sfml.ast.RedstoneTrigger;
import ca.teamdman.sfml.ast.Statement;
import ca.teamdman.sfml.ast.TimerTrigger;
import ca.teamdman.sfml.ast.Trigger;
import dev.xianyu.chronosfm.adapter.ProgramModelAdapter;
import dev.xianyu.chronosfm.model.ProgramModel;
import dev.xianyu.chronosfm.model.StatementModel;
import dev.xianyu.chronosfm.model.TriggerModel;

import java.util.ArrayList;
import java.util.List;

/**
 * Conservative SFM 4.34.0 / MC 1.21.1 adapter.
 *
 * Trigger timing is compiled today. Trigger bodies remain opaque until each
 * statement pattern has an exact semantic equivalence proof and differential test.
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
        List<StatementModel> body = opaqueBody(trigger);

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

    private static List<StatementModel> opaqueBody(Trigger trigger) {
        List<StatementModel> result = new ArrayList<>();
        for (Statement statement : trigger.getBlock().statements()) {
            result.add(new StatementModel.Opaque(statement.getClass().getName()));
        }
        return List.copyOf(result);
    }
}
