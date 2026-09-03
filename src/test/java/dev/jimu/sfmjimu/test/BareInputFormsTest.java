package dev.jimu.sfmjimu.test;

import ca.teamdman.sfmjimu.client.blocks.model.SfmlValidate;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 记录 SFM 4.34 真编译器对各种 input 语句形态的接受度（实验结论）：
 * 即使没有资源种类（{@code input with #x from a}）也能编译。物品标签
 * "不出现在代码里"的根因在编辑器的代码窗同步，不在 SFML 语法。
 */
public class BareInputFormsTest {

    private static void expect(String label, String stmt) {
        String sfml = "NAME \"t\"\nevery 20 ticks do\n" + stmt + "\nend\n";
        List<String> errors = SfmlValidate.check(sfml);
        assertTrue(errors.isEmpty(), label + " 应能编译，实际错误: " + errors);
    }

    @Test
    public void forms() {
        expect("带 * 和 with", "input * with #minecraft:logs from a");
        expect("只有 with 无资源种类", "input with #minecraft:logs from a");
        expect("资源种类 + 逗号 + with", "input *, with #minecraft:logs from a");
        expect("只有 with（NBT 版）", "input with #nbt:minecraft/custom_name from a");
        expect("input * 单独", "input * from a");
    }
}
