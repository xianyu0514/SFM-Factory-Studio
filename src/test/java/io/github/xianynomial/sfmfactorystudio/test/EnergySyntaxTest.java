package io.github.xianynomial.sfmfactorystudio.test;

import io.github.xianynomial.sfmfactorystudio.client.blocks.model.SfmlValidate;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertTrue;

/** 验证玩家报告的能量搬运程序在 SFM 真编译器下的语法合法性。 */
public class EnergySyntaxTest {

    @Test
    public void energyProgramForms() {
        List<String> e1 = SfmlValidate.check("""
                NAME "t"
                every 1 ticks do
                    input forge_energy:: from a
                    output forge_energy:: to b
                end
                """);
        assertTrue(e1.isEmpty(), "玩家的原程序应能编译，实际错误: " + e1);

        List<String> e2 = SfmlValidate.check("""
                NAME "t"
                every 20 ticks do
                    input fe:: from a
                    output fe:: to b
                end
                """);
        assertTrue(e2.isEmpty(), "fe:: 别名应能编译: " + e2);

        List<String> e3 = SfmlValidate.check("""
                NAME "t"
                every 20 ticks do
                    input sfm:forge_energy:*:* from a
                    output sfm:forge_energy:*:* to b
                end
                """);
        assertTrue(e3.isEmpty(), "展开形式应能编译: " + e3);
    }
}
