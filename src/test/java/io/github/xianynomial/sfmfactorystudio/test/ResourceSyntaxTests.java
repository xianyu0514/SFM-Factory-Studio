package io.github.xianynomial.sfmfactorystudio.test;

import io.github.xianynomial.sfmfactorystudio.client.blocks.model.BProgram;
import io.github.xianynomial.sfmfactorystudio.client.blocks.model.BlocksToSfml;
import io.github.xianynomial.sfmfactorystudio.client.blocks.model.SfmlToBlocks;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class ResourceSyntaxTests {
    @Test
    public void itemsUseShorthandButOtherCategoriesKeepTheirPrefix() {
        assertResource("stone", BProgram.ResourceKind.ITEM, "stone");
        assertResource("sfm:item:*:stone", BProgram.ResourceKind.ITEM, "stone");
        assertResource("minecraft:iron_ingot", BProgram.ResourceKind.ITEM, "minecraft:iron_ingot");
        assertResource("sfm:item:minecraft:iron_ingot", BProgram.ResourceKind.ITEM, "minecraft:iron_ingot");
        assertResource("totally_not_minecraft:iron_ingot", BProgram.ResourceKind.ITEM,
                "totally_not_minecraft:iron_ingot");

        assertResource("fluid::", BProgram.ResourceKind.FLUID, "fluid::");
        assertResource("fluid::water", BProgram.ResourceKind.FLUID, "fluid::water");
        assertResource("sfm:fluid:*:water", BProgram.ResourceKind.FLUID, "fluid::water");
        assertResource("chemical::", BProgram.ResourceKind.CHEMICAL, "chemical::");
        assertResource("forge_energy::", BProgram.ResourceKind.FORGE_ENERGY, "forge_energy::");
    }

    @Test
    public void everyDocumentedNonItemCategoryCompilesWithAnExplicitCategory() {
        String source = """
                name "Fluids and other resource types"
                every 20 ticks do
                    input stone from a
                    input fluid:: from a
                    output fluid:: to b
                    input fluid::water from a
                    input iron_ingot from a
                    input totally_not_minecraft:iron_ingot from a
                end
                every 20 ticks do
                    input forge_energy:: from a
                    input chemical:: from a
                    input fluid:: from a
                    input gas:: from a
                    input slurry:: from a
                    input pigment:: from a
                    input redstone:: from a
                    input infusion:: from a
                    input item:: from a
                end
                """;
        SfmlToBlocks.Result parsed = SfmlToBlocks.parse(source);
        assertTrue(parsed.ok(), parsed.errors().toString());
        String generated = BlocksToSfml.toSfml(parsed.program());
        SfmlTestSupport.assertNoCompileErrors(generated);
        for (String type : List.of("forge_energy", "chemical", "fluid", "gas", "slurry",
                "pigment", "redstone", "infusion")) {
            assertTrue(generated.contains("input " + type + ":: from a"), generated);
        }
        assertTrue(generated.contains("input * from a"), generated);
        assertFalse(generated.contains("item::"), "物品应使用省略类别的简写：\n" + generated);
    }

    @Test
    public void changingCategoryResetsToAnEmptySlotOfThatCategory() {
        BProgram.ResourceRef fluid = BProgram.ResourceRef.forKind(BProgram.ResourceKind.FLUID);
        assertTrue(fluid.isWildcard());
        assertEquals("", fluid.resourcePart());
        assertEquals("fluid::", fluid.sfml());
        assertEquals("fluid:minecraft:water", fluid.withResourcePart("minecraft:water").sfml());
    }

    private static void assertResource(String input, BProgram.ResourceKind kind, String expected) {
        BProgram.ResourceRef resource = BProgram.ResourceRef.parse(input);
        assertEquals(kind, resource.kind());
        assertEquals(expected, resource.sfml());
    }
}
