package dev.jimu.sfmjimu.test;

import ca.teamdman.sfmjimu.client.blocks.model.BProgram;
import ca.teamdman.sfmjimu.client.blocks.model.BlocksToSfml;
import ca.teamdman.sfmjimu.client.blocks.model.SfmlToBlocks;
import ca.teamdman.sfmjimu.client.blocks.model.SfmlValidate;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

public class WithAndOrTest {

    @Test
    public void andOrTagsRoundTrip() {
        String sfml = "NAME \"t\"\nevery 20 ticks do\n"
                + "input * with #c:ingots and #c:gems or #minecraft:logs from a\n"
                + "end\n";
        SfmlToBlocks.Result r = SfmlToBlocks.parse(sfml);
        assertTrue(r.ok(), "AND/OR 标签必须能解析: " + r.errors());
        String back = BlocksToSfml.toSfml(r.program());
        assertTrue(back.contains("and"), "生成代码必须包含 and: " + back);
        assertTrue(SfmlValidate.check(back).isEmpty(), "生成代码必须编译通过: " + SfmlValidate.check(back));
    }

    @Test
    public void nbtAndTagCombo() {
        String sfml = "NAME \"t\"\nevery 20 ticks do\n"
                + "input * with #nbt:minecraft/enchantments/sharpness and #c:ingots from a\n"
                + "end\n";
        SfmlToBlocks.Result r = SfmlToBlocks.parse(sfml);
        assertTrue(r.ok(), "NBT+标签 AND 组合必须能解析: " + r.errors());
        String back = BlocksToSfml.toSfml(r.program());
        assertTrue(SfmlValidate.check(back).isEmpty(), "必须编译通过");
    }

    @Test
    public void complexAndOrChain() {
        String sfml = "NAME \"t\"\nevery 20 ticks do\n"
                + "input * with #c:ingots and #c:gems and #c:logs or #c:planks from a\n"
                + "end\n";
        SfmlToBlocks.Result r = SfmlToBlocks.parse(sfml);
        assertTrue(r.ok(), "多级 AND/OR 链必须能解析: " + r.errors());
        String back = BlocksToSfml.toSfml(r.program());
        assertTrue(SfmlValidate.check(back).isEmpty(), "必须编译通过");
    }
}
