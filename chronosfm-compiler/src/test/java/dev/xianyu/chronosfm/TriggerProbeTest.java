package dev.xianyu.chronosfm;

import dev.xianyu.chronosfm.compiler.ChronoSfmCompiler;
import dev.xianyu.chronosfm.model.ProgramModel;
import dev.xianyu.chronosfm.model.TriggerModel;
import dev.xianyu.chronosfm.runtime.TriggerProbe;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class TriggerProbeTest {
    @Test
    void inactiveTimerCanSkipExpensiveContext() {
        var program = new ProgramModel(List.of(
                new TriggerModel.Timer(20, TriggerModel.Alignment.LOCAL, 0, List.of())
        ));
        var probe = new TriggerProbe(new ChronoSfmCompiler().compile(program));

        assertFalse(probe.probe(0, 0).maySkipFullContext());
        for (int tick = 1; tick < 20; tick++) {
            assertTrue(probe.probe(tick, tick).maySkipFullContext());
        }
    }

    @Test
    void opaqueTriggerCanNeverBeSuppressedByFastGate() {
        var program = new ProgramModel(List.of(
                new TriggerModel.Opaque("redstone/custom trigger", List.of())
        ));
        var result = new TriggerProbe(new ChronoSfmCompiler().compile(program)).probe(123, 123);

        assertFalse(result.maySkipFullContext());
        assertTrue(result.requiresLegacyContext());
    }
}
