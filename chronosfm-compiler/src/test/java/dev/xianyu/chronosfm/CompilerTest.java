package dev.xianyu.chronosfm;

import dev.xianyu.chronosfm.compiler.ChronoSfmCompiler;
import dev.xianyu.chronosfm.model.ProgramModel;
import dev.xianyu.chronosfm.model.StatementModel;
import dev.xianyu.chronosfm.model.TriggerModel;
import dev.xianyu.chronosfm.runtime.ExactOrderTransferPlan;
import dev.xianyu.chronosfm.runtime.InvalidationEngine;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class CompilerTest {
    private final ChronoSfmCompiler compiler = new ChronoSfmCompiler();

    @Test
    void timerSchedulePreservesEveryTickFrequency() {
        var plan = compiler.compile(new ProgramModel(List.of(
                new TriggerModel.Timer(1, TriggerModel.Alignment.LOCAL, 0, List.of())
        )));

        for (int tick = 0; tick < 100; tick++) {
            assertArrayEquals(new int[]{0}, plan.dueTriggerIndexes(tick, tick));
        }
    }

    @Test
    void timerOffsetIsExact() {
        var plan = compiler.compile(new ProgramModel(List.of(
                new TriggerModel.Timer(20, TriggerModel.Alignment.GLOBAL, 3, List.of())
        )));

        assertEquals(0, plan.dueTriggerIndexes(2, 2).length);
        assertArrayEquals(new int[]{0}, plan.dueTriggerIndexes(3, 3));
        assertArrayEquals(new int[]{0}, plan.dueTriggerIndexes(23, 23));
    }

    @Test
    void transferOrderIsPreserved() {
        var first = new StatementModel.Transfer("a", "b", "item:iron", false, 64, 0);
        var second = new StatementModel.Transfer("b", "c", "item:iron", false, 64, 0);
        var plan = compiler.compile(new ProgramModel(List.of(
                new TriggerModel.Timer(1, TriggerModel.Alignment.LOCAL, 0, List.of(first, second))
        )));

        var tickPlan = new ExactOrderTransferPlan(plan).regionsForTick(10, 10);
        assertEquals(2, tickPlan.size());
        assertEquals("a", tickPlan.get(0).sourceLabel());
        assertEquals("b", tickPlan.get(1).sourceLabel());
        assertTrue(tickPlan.get(0).exactOrderOrdinal() < tickPlan.get(1).exactOrderOrdinal());
    }

    @Test
    void invalidationTouchesOnlyDependentRegions() {
        var plan = compiler.compile(new ProgramModel(List.of(
                new TriggerModel.Timer(1, TriggerModel.Alignment.LOCAL, 0, List.of(
                        new StatementModel.Transfer("ore", "furnace", "item:iron", false, 64, 0),
                        new StatementModel.Transfer("wood", "generator", "item:coal", false, 64, 0)
                ))
        )));

        var invalidation = new InvalidationEngine(plan.dependencyIndex());
        invalidation.invalidateLabel("furnace");

        assertArrayEquals(new int[]{0}, invalidation.drainDirtyRegions());
        assertEquals(0, invalidation.dirtyCount());
    }

    @Test
    void opaqueSemanticsForceLegacyFallback() {
        var plan = compiler.compile(new ProgramModel(List.of(
                new TriggerModel.Opaque("redstone/custom", List.of(
                        new StatementModel.Opaque("unknown control flow")
                ))
        )));

        assertTrue(plan.requiresLegacyExecution());
        assertEquals(1, plan.dueTriggerIndexes(7, 7).length);
    }
}
