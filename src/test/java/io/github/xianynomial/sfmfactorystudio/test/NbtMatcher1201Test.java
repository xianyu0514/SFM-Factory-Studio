package io.github.xianynomial.sfmfactorystudio.test;

import io.github.xianynomial.sfmfactorystudio.net.NbtMatcherHook;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.IntTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.StringTag;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 1.20.1 原生 NBT 语义的伪标签匹配护栏（纯 CompoundTag，无头可测）：
 * 线格式与 1.21.1 组件版一致（nbt:ns/name[/selector[/ops]]），
 * 后端落在物品 NBT 树上。
 */
public class NbtMatcher1201Test {

    /** 附魔剑的 NBT：ench = [{id, lvl}]。 */
    private static CompoundTag enchSword(String enchId, int lvl) {
        CompoundTag tag = new CompoundTag();
        ListTag ench = new ListTag();
        CompoundTag e = new CompoundTag();
        e.putString("id", enchId);
        e.putInt("lvl", lvl);
        ench.add(e);
        tag.put("ench", ench);
        return tag;
    }

    @Test
    public void parseLineFormat() {
        var p = NbtMatcherHook.parseNbt("nbt:minecraft/enchantments/sharpness");
        assertNotNull(p);
        assertEquals("minecraft:enchantments", p.componentId());
        assertEquals("ench", p.rootKey());
        assertEquals(1, p.selector().size());
        assertEquals("sharpness", p.selector().get(0));

        assertNull(NbtMatcherHook.parseNbt("common_tag"));
        assertNull(NbtMatcherHook.parseNbt("nbt:only-one-segment"));
    }

    @Test
    public void enchantMatching() {
        CompoundTag s = enchSword("minecraft:sharpness", 5);
        assertTrue(NbtMatcherHook.matchesTag("nbt:minecraft/enchantments/sharpness", s), "A1");
        assertTrue(NbtMatcherHook.matchesTag("nbt:minecraft/enchantments/sharpness/5", s), "A2");
        assertFalse(NbtMatcherHook.matchesTag("nbt:minecraft/enchantments/sharpness/4", s), "A3");
        assertFalse(NbtMatcherHook.matchesTag("nbt:minecraft/enchantments/efficiency", s), "A4");
        // 存在性（v1 语义）：有 ench 键即命中
        assertTrue(NbtMatcherHook.matchesTag("nbt:minecraft/enchantments", s), "A5");
        // 干净物品：无 NBT → 全部不命中
        assertFalse(NbtMatcherHook.matchesTag("nbt:minecraft/enchantments/sharpness", new CompoundTag()), "A6");
        assertFalse(NbtMatcherHook.matchesTag("nbt:minecraft/enchantments", new CompoundTag()), "A7");
        assertFalse(NbtMatcherHook.matchesTag("nbt:minecraft/enchantments", null), "A8");
        // 模组附魔：__ 双下划线编码命名空间
        CompoundTag modded = enchSword("apotheosis:severing", 1);
        assertTrue(NbtMatcherHook.matchesTag("nbt:minecraft/enchantments/apotheosis__severing", modded), "A9");
    }

    @Test
    public void damageNumericCompare() {
        CompoundTag tag = new CompoundTag();
        tag.put("Damage", IntTag.valueOf(800));
        assertTrue(NbtMatcherHook.matchesTag("nbt:minecraft/damage/gt500", tag), "A10");
        assertTrue(NbtMatcherHook.matchesTag("nbt:minecraft/damage/le800", tag), "A11");
        assertFalse(NbtMatcherHook.matchesTag("nbt:minecraft/damage/lt100", tag), "A12");
        // 未受损物品无 Damage 键 → 任何条件都不命中
        assertFalse(NbtMatcherHook.matchesTag("nbt:minecraft/damage/gt0", new CompoundTag()), "A13");
    }

    @Test
    public void customDataPathAndOperators() {
        CompoundTag energy = new CompoundTag();
        energy.put("max", IntTag.valueOf(5000));
        CompoundTag thermal = new CompoundTag();
        thermal.put("energy", energy);
        CompoundTag tag = new CompoundTag();
        tag.put("thermal", thermal);
        assertTrue(NbtMatcherHook.matchesTag("nbt:minecraft/thermal/energy__max/gt1000", tag), "A14");
        assertTrue(NbtMatcherHook.matchesTag("nbt:minecraft/thermal/energy__max/eq5000", tag), "A15");
        assertFalse(NbtMatcherHook.matchesTag("nbt:minecraft/thermal/energy__max/gt9000", tag), "A16");
        assertTrue(NbtMatcherHook.matchesTag("nbt:minecraft/thermal/energy__max", tag), "A17");
        // 字符串子键 eq
        CompoundTag named = new CompoundTag();
        named.put("tier", StringTag.valueOf("gt2"));
        assertTrue(NbtMatcherHook.matchesTag("nbt:minecraft/tier/eqgt2", named), "A18");
        // custom_data 语义：整棵树任意路径
        assertTrue(NbtMatcherHook.matchesTag("nbt:minecraft/custom_data/thermal__energy__max/eq5000", tag), "A19");
    }

    @Test
    public void vanillaPseudoTagNotHijacked() {
        // NbtMatcherHook 对非 nbt: 前缀返回 false（不劫持普通标签，SFM 原逻辑继续）
        CompoundTag s = enchSword("minecraft:sharpness", 5);
        assertFalse(NbtMatcherHook.matchesTag("common_ingot", s), "A20");
    }

    @Test
    public void displayNameWildcard() {
        CompoundTag display = new CompoundTag();
        display.put("Name", StringTag.valueOf("{\"text\":\"魔剑\"}"));
        CompoundTag s = new CompoundTag();
        s.put("display", display);
        assertTrue(NbtMatcherHook.matchesTag("nbt:minecraft/custom_name", s), "A21");
        assertFalse(NbtMatcherHook.matchesTag("nbt:minecraft/custom_name", new CompoundTag()), "A22");

        CompoundTag d2 = new CompoundTag();
        d2.put("Name", StringTag.valueOf("{\"text\":\"Magic Sword\"}"));
        CompoundTag en = new CompoundTag();
        en.put("display", d2);
        assertTrue(NbtMatcherHook.matchesTag("nbt:minecraft/custom_name/magic__sword", en), "A23");
        assertTrue(NbtMatcherHook.matchesTag("nbt:minecraft/custom_name/*sword", en), "A24");
        assertFalse(NbtMatcherHook.matchesTag("nbt:minecraft/custom_name/fire", en), "A25");
    }

    @Test
    public void andOrCombinationLineFormatStillParses() {
        var p = NbtMatcherHook.parseNbt("nbt:minecraft/custom_data/energy__max/gt1000");
        assertNotNull(p);
        assertEquals("minecraft:custom_data", p.componentId());
        assertEquals("", p.rootKey());
        assertEquals(2, p.selector().size());
    }
}