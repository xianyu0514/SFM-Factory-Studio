package io.github.xianynomial.sfmfactorystudio.test;

import io.github.xianynomial.sfmfactorystudio.client.blocks.model.BProgram;
import io.github.xianynomial.sfmfactorystudio.client.blocks.model.BlockTemplates;
import io.github.xianynomial.sfmfactorystudio.client.blocks.model.BlocksToSfml;
import io.github.xianynomial.sfmfactorystudio.client.blocks.model.ProgramDiagnostics;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class BlockTemplateTests {
    @Test
    public void smeltingLineHasThreeIndependentTransferStages() {
        BProgram program = withTimer(BlockTemplates.smeltingLine());
        String sfml = assertUsefulAndCompilable(program);
        assertTrue(sfml.contains("output to \"熔炉\" top side\n    forget\n    input from \"燃料箱\""));
        assertTrue(sfml.contains("output to \"熔炉\" north side\n    forget\n    input from \"熔炉\" bottom side"));
    }

    @Test
    public void fullStackSortTakesBeforeItOutputs() {
        BProgram program = withTimer(List.of(BlockTemplates.fullStackSort()));
        String sfml = assertUsefulAndCompilable(program);
        assertTrue(sfml.contains("if overall \"原料箱\" has >= 64 * then\n        input 64 * from \"原料箱\"\n        output 64 to \"目标箱\""));
    }

    @Test
    public void balancedDistributionHasARealInputAndTwoTargets() {
        BProgram program = withTimer(BlockTemplates.balancedDistribution());
        String sfml = assertUsefulAndCompilable(program);
        assertTrue(sfml.contains("input from \"原料箱\""));
        assertTrue(sfml.contains("output to \"分配箱1\", \"分配箱2\" round robin by label"));
    }

    @Test
    public void allParallelTransfersAreCompletePrograms() {
        BProgram program = new BProgram();
        program.triggers.addAll(BlockTemplates.parallelTransfers());
        String sfml = assertUsefulAndCompilable(program);
        assertEquals(3, program.triggers.size());
        assertEquals(3, sfml.split("input from \\\"来源箱\\\"", -1).length - 1);
        assertEquals(3, sfml.split("output to \\\"目标箱\\\"", -1).length - 1);
    }

    @Test
    public void energyTransferIsACompletePredicateFreePair() {
        BProgram program = withTimer(BlockTemplates.energyTransfer("供能端", "用能端"));
        BProgram.TimerTrigger timer = (BProgram.TimerTrigger) program.triggers.get(0);
        timer.count = 1;
        String sfml = assertUsefulAndCompilable(program);
        assertTrue(sfml.contains("every 1 ticks do"));
        assertTrue(sfml.contains("input forge_energy:: from \"供能端\""));
        assertTrue(sfml.contains("output forge_energy:: to \"用能端\""));
        assertTrue(!sfml.contains(" with ") && !sfml.contains(" without "));
    }

    private static BProgram withTimer(List<BProgram.Statement> statements) {
        BProgram program = new BProgram();
        BProgram.TimerTrigger trigger = new BProgram.TimerTrigger();
        trigger.body.addAll(statements);
        program.triggers.add(trigger);
        return program;
    }

    private static String assertUsefulAndCompilable(BProgram program) {
        assertTrue(ProgramDiagnostics.errorMessages(program).isEmpty(),
                ProgramDiagnostics.errorMessages(program).toString());
        String sfml = BlocksToSfml.toSfml(program);
        SfmlTestSupport.assertNoCompileErrors(sfml);
        return sfml;
    }
}
