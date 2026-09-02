package dev.jimu.sfmjimu.test;

import ca.teamdman.sfmjimu.net.NbtMatcherHook;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 深度值匹配护栏：伪标签解析（组件 id + 选择器段）与通配匹配。
 * 附魔/药水/custom_data 的匹配语义依赖注册表与 ItemStack，游戏内验证。
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
    public void customDataKeyAndNumericOps() {
        var p = NbtMatcherHook.parse("nbt:minecraft/custom_data/energy.max");
        assertEquals("minecraft:custom_data", p.componentId());
        assertEquals("energy.max", p.selector().get(0));

        var p2 = NbtMatcherHook.parse("nbt:minecraft/custom_data/energy.stored/gt10000");
        assertEquals("gt10000", p2.selector().get(1));

        var p3 = NbtMatcherHook.parse("nbt:minecraft/damage/ge1");
        assertEquals("minecraft:damage", p3.componentId());
        assertEquals("ge1", p3.selector().get(0));
    }

    @Test
    public void wildcard() {
        assertTrue(NbtMatcherHook.wildcardMatches("excalibur", "Excalibur 之剑"));
        assertTrue(NbtMatcherHook.wildcardMatches("*calib*", "Excalibur"));
        assertFalse(NbtMatcherHook.wildcardMatches("*calib*", "木剑"));
        assertTrue(NbtMatcherHook.wildcardMatches("*", "任何东西"));
    }
}
