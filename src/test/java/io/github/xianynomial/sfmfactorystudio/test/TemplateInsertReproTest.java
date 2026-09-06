package io.github.xianynomial.sfmfactorystudio.test;

import io.github.xianynomial.sfmfactorystudio.client.blocks.model.BlockTemplates;
import io.github.xianynomial.sfmfactorystudio.client.blocks.model.BProgram;
import io.github.xianynomial.sfmfactorystudio.client.blocks.model.BlocksToSfml;
import io.github.xianynomial.sfmfactorystudio.client.blocks.model.SfmlToBlocks;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 复现"点击熔炉模板"的完整链路：插入 smeltingLine → 生成 SFML → 往返解析。
 * 护栏：模板插入后绝不降级成注释积木；往返语句数保持。
 */
public class TemplateInsertReproTest {

    /** 模拟 targetBody() 的空程序兜底 + templateSmelt 的插入。 */
    private static BProgram simulateSmeltClick() {
        BProgram p = new BProgram();
        if (p.triggers.isEmpty()) p.triggers.add(new BProgram.TimerTrigger());
        p.triggers.get(0).body.addAll(BlockTemplates.smeltingLine());
        return p;
    }

    @Test
    public void smeltTemplateRoundTrip() {
        BProgram p = simulateSmeltClick();
        assertEquals(8, p.triggers.get(0).body.size(), "模板应插入 8 条语句");

        String sfml = BlocksToSfml.toSfml(p);
        assertFalse(sfml.contains("--"), "生成的 SFML 不应有降级注释: " + sfml);

        SfmlToBlocks.Result r = SfmlToBlocks.parse(sfml);
        assertTrue(r.ok(), "生成的 SFML 应可解析: " + r.errors());
        assertNotNull(r.program());
        assertEquals(1, r.program().triggers.size());
        assertEquals(8, r.program().triggers.get(0).body.size(), "往返后语句数应保持");
    }

    @Test
    public void smeltTemplateSfmlCompilesViaProgramBuilder() {
        BProgram p = simulateSmeltClick();
        String sfml = BlocksToSfml.toSfml(p);
        var result = new ca.teamdman.sfml.program_builder.ProgramBuilder(sfml).useCache(false).build();
        assertTrue(result.isBuildSuccessful(), "模板 SFML 应能通过 SFM 编译器");
        assertNotNull(result.program());
    }

    @Test
    public void allFourTemplatesRoundTrip() {
        // 熔炉之外的三类模板：整卡（fast 是独立触发器）逐一往返
        BProgram sort = new BProgram();
        if (sort.triggers.isEmpty()) sort.triggers.add(new BProgram.TimerTrigger());
        sort.triggers.get(0).body.add(BlockTemplates.fullStackSort());
        String sortSfml = BlocksToSfml.toSfml(sort);
        assertFalse(sortSfml.contains("--"), "fullStackSort 不应降级: " + sortSfml);
        assertTrue(SfmlToBlocks.parse(sortSfml).ok());

        BProgram even = new BProgram();
        even.triggers.add(new BProgram.TimerTrigger());
        even.triggers.get(0).body.addAll(BlockTemplates.balancedDistribution());
        String evenSfml = BlocksToSfml.toSfml(even);
        assertFalse(evenSfml.contains("--"), "balancedDistribution 不应降级: " + evenSfml);
        assertTrue(SfmlToBlocks.parse(evenSfml).ok());

        BProgram fast = new BProgram();
        fast.triggers.addAll(BlockTemplates.parallelTransfers());
        String fastSfml = BlocksToSfml.toSfml(fast);
        assertFalse(fastSfml.contains("--"), "parallelTransfers 不应降级: " + fastSfml);
        assertTrue(SfmlToBlocks.parse(fastSfml).ok());
    }
}
