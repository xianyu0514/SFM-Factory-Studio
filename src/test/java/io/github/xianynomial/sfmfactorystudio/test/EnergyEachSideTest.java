package io.github.xianynomial.sfmfactorystudio.test;

import io.github.xianynomial.sfmfactorystudio.client.blocks.model.BProgram;
import io.github.xianynomial.sfmfactorystudio.client.blocks.model.BlocksToSfml;
import io.github.xianynomial.sfmfactorystudio.client.blocks.model.SfmlToBlocks;
import io.github.xianynomial.sfmfactorystudio.client.blocks.model.SfmlValidate;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 能量搬运的空面根因回归：SFM 对未指定侧面只查空面（SideQualifier
 * DEFAULT=NULL），而能量接口按真实方向暴露——纯能量语句序列化时必须自动
 * 补 each side（逐面查询），否则零传输。物品语句、用户显式侧面不受影响。
 */
public class EnergyEachSideTest {

    private static BProgram energyProgram() {
        String base = "NAME \"t\"\nevery 20 ticks do\ninput forge_energy:: from a\noutput forge_energy:: to b\nend\n";
        SfmlToBlocks.Result r = SfmlToBlocks.parse(base);
        assertTrue(r.ok(), "基础能量程序必须可解析: " + r.errors());
        return r.program();
    }

    @Test
    public void eachSideForms() {
        List<String> e1 = SfmlValidate.check("""
                NAME "t"
                every 20 ticks do
                    input forge_energy:: from a each side
                    output forge_energy:: to b each side
                end
                """);
        assertTrue(e1.isEmpty(), "each side 应能编译，实际: " + e1);

        List<String> e2 = SfmlValidate.check("""
                NAME "t"
                every 20 ticks do
                    input * from a each side
                end
                """);
        assertTrue(e2.isEmpty(), "物品语句也可用 each side: " + e2);
    }

    @Test
    public void energyStatementsGetEachSideAutomatically() {
        String sfml = BlocksToSfml.toSfml(energyProgram());
        assertTrue(sfml.contains("from a each side"), "能量 input 应自动补 each side: " + sfml);
        assertTrue(sfml.contains("to b each side"), "能量 output 应自动补 each side: " + sfml);
        assertTrue(SfmlValidate.check(sfml).isEmpty(), "生成代码必须过 SFM 编译器: " + sfml);

        // 再解析回来：each side 落进模型，二次序列化文本稳定
        SfmlToBlocks.Result back = SfmlToBlocks.parse(sfml);
        assertTrue(back.ok());
        assertEquals(sfml, BlocksToSfml.toSfml(back.program()), "each side 往返必须逐字稳定");
    }

    @Test
    public void itemStatementsUnchanged() {
        String base = "NAME \"t\"\nevery 20 ticks do\ninput * from a\nend\n";
        SfmlToBlocks.Result r = SfmlToBlocks.parse(base);
        assertTrue(r.ok());
        String sfml = BlocksToSfml.toSfml(r.program());
        assertFalse(sfml.contains("each side"), "物品语句不应被注入 each side: " + sfml);
    }

    @Test
    public void explicitSidesRespected() {
        // 用户显式选了侧面：完全尊重，不注入 each side
        String base = "NAME \"t\"\nevery 20 ticks do\ninput forge_energy:: from a top side\nend\n";
        SfmlToBlocks.Result r = SfmlToBlocks.parse(base);
        assertTrue(r.ok(), "显式侧面必须可解析: " + r.errors());
        String sfml = BlocksToSfml.toSfml(r.program());
        assertTrue(sfml.contains("top side"), "显式侧面应保留: " + sfml);
        assertFalse(sfml.contains("each side"), "有侧面时不应注入: " + sfml);
    }
}
