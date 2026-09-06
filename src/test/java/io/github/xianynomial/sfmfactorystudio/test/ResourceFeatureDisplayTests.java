package io.github.xianynomial.sfmfactorystudio.test;

import io.github.xianynomial.sfmfactorystudio.client.TagDisplayNames;
import io.github.xianynomial.sfmfactorystudio.client.blocks.model.BProgram;
import io.github.xianynomial.sfmfactorystudio.client.blocks.model.BlocksToSfml;
import net.minecraft.resources.ResourceLocation;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class ResourceFeatureDisplayTests {
    @Test
    public void commonTagGetsChineseDisplayWithoutChangingRawCode() {
        ResourceLocation id = new ResourceLocation("c:ingots/iron");
        assertEquals("铁锭类", TagDisplayNames.display(id, List.of("铁锭")));

        BProgram program = new BProgram();
        BProgram.TimerTrigger timer = new BProgram.TimerTrigger();
        BProgram.Statement.Input input = new BProgram.Statement.Input();
        input.access.labels.add("a");
        BProgram.ResourceLimit limit = new BProgram.ResourceLimit();
        limit.resources.add(BProgram.ResourceRef.parse("minecraft:iron_ingot"));
        limit.with = new BProgram.WithFilter();
        limit.with.expr = new BProgram.WithExpr.Tag(id.toString());
        input.limits.add(limit);
        timer.body.add(input);
        program.triggers.add(timer);

        String sfml = BlocksToSfml.toSfml(program);
        assertTrue(sfml.contains("with #c:ingots/iron"));
    }

    @Test
    public void commonMiningTagsHavePurposeFirstChineseNames() {
        assertEquals("金制工具无法正确采掘", TagDisplayNames.display(
                new ResourceLocation("minecraft:incorrect_for_gold_tool"), List.of()));
        assertEquals("需要石制或更好的工具", TagDisplayNames.display(
                new ResourceLocation("minecraft:needs_stone_tool"), List.of()));
        assertEquals("可用多功能工具挖掘", TagDisplayNames.display(
                new ResourceLocation("silentgear:mineable/paxel"), List.of()));
        assertEquals("勘探锤可探测的方块", TagDisplayNames.display(
                new ResourceLocation("silentgear:prospector_hammer_targets"), List.of()));
        assertTrue(TagDisplayNames.isResourceCategory(new ResourceLocation("c:ores/iron")));
        assertTrue(!TagDisplayNames.isResourceCategory(new ResourceLocation("minecraft:mineable/pickaxe")));
    }

    @Test
    public void unknownInternalTagUsesAChineseExampleInsteadOfEnglishAsItsTitle() {
        assertEquals("包含“铁锭”等的分类", TagDisplayNames.display(
                new ResourceLocation("some_mod:opaque_internal_group"), List.of("铁锭", "金锭")));
    }
}
