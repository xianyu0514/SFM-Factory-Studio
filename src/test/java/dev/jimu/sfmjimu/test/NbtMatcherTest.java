package dev.jimu.sfmjimu.test;

import ca.teamdman.sfmjimu.client.blocks.model.BProgram;
import ca.teamdman.sfmjimu.client.blocks.model.BlocksToSfml;
import ca.teamdman.sfmjimu.client.blocks.model.SfmlToBlocks;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The NBT component condition is a pseudo-tag (`nbt:ns/path`), so it must
 * parse and round-trip through the VANILLA SFM grammar untouched — the fork
 * only changes what the tag matcher does at transfer time, not the syntax.
 */
public class NbtMatcherTest {

    @Test
    public void nbtPseudoTagParsesAndRoundTripsVanilla() {
        String sfml = "NAME \"nbt\"\nevery 20 ticks do\n"
                + "input * with #nbt:minecraft/enchantments from a\n"
                + "end\n";
        SfmlToBlocks.Result r = SfmlToBlocks.parse(sfml);
        assertTrue(r.ok(), "原版 SFM 语法必须接受 nbt: 伪标签");
        String back = BlocksToSfml.toSfml(r.program());
        assertTrue(back.contains("#nbt:minecraft/enchantments"), "往返必须保留伪标签: " + back);
        // 再往返一次确保稳定
        SfmlToBlocks.Result r2 = SfmlToBlocks.parse(back);
        assertTrue(r2.ok());
        assertTrue(BlocksToSfml.toSfml(r2.program()).contains("#nbt:minecraft/enchantments"));
    }

    @Test
    public void nbtPseudoTagSupportsNotAndOr() {
        String sfml = "NAME \"nbt2\"\nevery 20 ticks do\n"
                + "input * with #nbt:minecraft/custom_data and #c:ingots or not #nbt:minecraft/damage from a\n"
                + "end\n";
        SfmlToBlocks.Result r = SfmlToBlocks.parse(sfml);
        assertTrue(r.ok(), "伪标签必须能与普通标签自由组合");
        String back = BlocksToSfml.toSfml(r.program());
        assertTrue(back.contains("#nbt:minecraft/custom_data"));
        assertTrue(back.contains("#nbt:minecraft/damage"));
    }

    @Test
    public void programModelKeepsMatcherVerbatim() {
        SfmlToBlocks.Result r = SfmlToBlocks.parse(
                "NAME \"n\"\nevery 20 ticks do\ninput * with #nbt:minecraft/potion_contents from a\nend\n");
        assertTrue(r.ok());
        BProgram.Statement.Input in = (BProgram.Statement.Input) r.program().triggers.get(0).body.get(0);
        String matcher = in.limits.get(0).with.expr instanceof BProgram.WithExpr.Tag tag
                ? tag.matcher : null;
        assertTrue("nbt:minecraft/potion_contents".equals(matcher),
                "模型应原样保留伪标签串，实际=" + matcher);
    }
}
