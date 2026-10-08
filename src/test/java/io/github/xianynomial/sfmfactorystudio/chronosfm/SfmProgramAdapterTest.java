package io.github.xianynomial.sfmfactorystudio.chronosfm;

import ca.teamdman.sfml.ast.Block;
import ca.teamdman.sfml.ast.Interval;
import ca.teamdman.sfml.ast.Program;
import ca.teamdman.sfml.ast.RedstoneTrigger;
import ca.teamdman.sfml.ast.TimerTrigger;
import ca.teamdman.sfml.program_builder.ProgramBuilder;
import dev.xianyu.chronosfm.compiler.ChronoSfmCompiler;
import dev.xianyu.chronosfm.ir.ExactOperation;
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

    @Test
    void compilesRealFlatInputOutputIntoExactOrderIr() {
        var result = new ProgramBuilder("""
                NAME "chrono-flat"
                EVERY 20 TICKS DO
                    INPUT FROM source
                    OUTPUT TO destination
                END
                """).useCache(false).build();

        assertNotNull(result.program(), () -> "SFM parser failed: " + result.metadata().errors());

        var plan = new ChronoSfmCompiler().compile(adapter.adapt(result.program()));
        var operations = plan.exactOperationsForTrigger(0);

        assertEquals(2, operations.size());
        assertInstanceOf(ExactOperation.InputOp.class, operations.get(0));
        assertInstanceOf(ExactOperation.OutputOp.class, operations.get(1));
        assertTrue(operations.get(0).exactOrderOrdinal() < operations.get(1).exactOrderOrdinal());

        var input = (ExactOperation.InputOp) operations.get(0);
        var output = (ExactOperation.OutputOp) operations.get(1);
        assertEquals(List.of("source"), input.selector().labels());
        assertEquals(List.of("destination"), output.selector().labels());
    }
}
