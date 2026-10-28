package io.github.xianynomial.sfmfactorystudio.test;

import io.github.xianynomial.sfmfactorystudio.client.blocks.model.BProgram;
import io.github.xianynomial.sfmfactorystudio.client.blocks.model.BlockTemplates;
import io.github.xianynomial.sfmfactorystudio.client.blocks.model.BlocksToSfml;
import io.github.xianynomial.sfmfactorystudio.client.blocks.model.ProgramDiagnostics;
import io.github.xianynomial.sfmfactorystudio.client.blocks.model.TimerRules;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class TimerRulesTests {
    @Test
    public void energyPairUsesEnergySpecificMinimum() {
        BProgram.TimerTrigger timer = new BProgram.TimerTrigger();
        timer.body.addAll(BlockTemplates.energyTransfer("a", "b"));
        assertTrue(TimerRules.usesOnlyEnergyIO(timer.body));
        timer.count = TimerRules.energyMinimumTicks();
        BProgram program = new BProgram();
        program.triggers.add(timer);
        assertTrue(ProgramDiagnostics.errorMessages(program).isEmpty());
        assertTrue(BlocksToSfml.toSfml(program).contains("every " + timer.count + " ticks do"));
    }

    @Test
    public void itemTransferUsesNormalMinimumAndMalformedStateIsMadeCompilable() {
        BProgram.TimerTrigger timer = new BProgram.TimerTrigger();
        BProgram.Statement.Input input = new BProgram.Statement.Input();
        input.access.labels.add("a");
        input.limits.add(new BProgram.ResourceLimit());
        timer.body.add(input);
        timer.count = Math.max(0, TimerRules.normalMinimumTicks() - 1);
        assertFalse(TimerRules.usesOnlyEnergyIO(timer.body));
        BProgram program = new BProgram();
        program.triggers.add(timer);
        assertFalse(ProgramDiagnostics.errorMessages(program).isEmpty());
        assertTrue(BlocksToSfml.toSfml(program).contains("every " + TimerRules.normalMinimumTicks() + " ticks do"));
    }
}
