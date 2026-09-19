package io.github.xianynomial.sfmfactorystudio.chronosfm;

import ca.teamdman.sfml.ast.Block;
import ca.teamdman.sfml.ast.Interval;
import ca.teamdman.sfml.ast.Program;
import ca.teamdman.sfml.ast.RedstoneTrigger;
import ca.teamdman.sfml.ast.TimerTrigger;
import dev.xianyu.chronosfm.compiler.ChronoSfmCompiler;
import dev.xianyu.chronosfm.model.TriggerModel;
import dev.xianyu.chronosfm.runtime.TriggerProbe;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

class SfmProgramAdapterTest {
    private final SfmProgramAdapter adapter = new SfmProgramAdapter();

    @Test
    void adaptsRealSfmTimerWithoutProgramContext() {
        Program program = new Program(
                null,
                "chrono-test",
                List.of(new TimerTrigger(
                        new Interval(20, Interval.IntervalAlignment.LOCAL, 3),
                        new Block(List.of())
                )),
                Set.of(),
                Set.of()
        );

        var model = adapter.adapt(program);
        assertInstanceOf(TriggerModel.Timer.class, model.triggers().get(0));

        var probe = new TriggerProbe(new ChronoSfmCompiler().compile(model));
        assertTrue(probe.probe(2, 2, 0).maySkipFullContext());
        assertFalse(probe.probe(3, 3, 0).maySkipFullContext());
        assertFalse(probe.probe(23, 23, 0).maySkipFullContext());
    }

    @Test
    void adaptsRealSfmRedstoneTrigger() {
        Program program = new Program(
                null,
                "chrono-redstone",
                List.of(new RedstoneTrigger(new Block(List.of()))),
                Set.of(),
                Set.of()
        );

        var probe = new TriggerProbe(new ChronoSfmCompiler().compile(adapter.adapt(program)));
        assertTrue(probe.probe(1, 1, 0).maySkipFullContext());
        assertFalse(probe.probe(1, 1, 2).maySkipFullContext());
    }
}
