package io.github.xianynomial.sfmfactorystudio.test;

import io.github.xianynomial.sfmfactorystudio.net.NbtMatcherHook;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 端到端验证：完整匹配串（与 WithTagMixin 收到的完全一致）→ parse → 选择器拆段。
 * 附魔/药水的实际匹配需要 ItemStack+注册表（游戏内验证），这里验证链路的
 * 前半段：语法解析一定正确，后续 matchValue 只是类型分派。
 */
public class NbtEndToEndTest {

    /** 模拟 Mixin 实际收到的完整匹配串，验证 parse 拆段结果。 */
    @Test
    public void fullMatcherParsesCorrectly() {
        // 这是 ANTLR 解析 #nbt:minecraft/enchantments/sharpness 后
        // TagMatcher.toString() 的输出，也是 Mixin 传给 matchesComponent 的输入
        String matcher = "nbt:minecraft/enchantments/sharpness";
        var p = NbtMatcherHook.parse(matcher);
        assertNotNull(p, "必须解析成功");
        assertEquals("minecraft:enchantments", p.componentId());
        assertEquals(1, p.selector().size());
        assertEquals("sharpness", p.selector().get(0));
    }

    @Test
    public void deepPathWithLevelParses() {
        var p = NbtMatcherHook.parse("nbt:minecraft/enchantments/fire_protection/3");
        assertNotNull(p);
        assertEquals("minecraft:enchantments", p.componentId());
        assertEquals(2, p.selector().size());
        assertEquals("fire_protection", p.selector().get(0)); // 带下划线的附魔名是合法标识符
        assertEquals("3", p.selector().get(1));
    }

    @Test
    public void customDataUnderscorePathParses() {
        var p = NbtMatcherHook.parse("nbt:minecraft/custom_data/energy_max");
        assertNotNull(p);
        assertEquals("minecraft:custom_data", p.componentId());
        assertEquals("energy_max", p.selector().get(0));
    }

    @Test
    public void potionPathOnlyParses() {
        var p = NbtMatcherHook.parse("nbt:minecraft/potion_contents/healing");
        assertNotNull(p);
        assertEquals("minecraft:potion_contents", p.componentId());
        assertEquals("healing", p.selector().get(0));
    }

    @Test
    public void simpleExistenceStillWorks() {
        var p = NbtMatcherHook.parse("nbt:minecraft/enchantments");
        assertNotNull(p);
        assertEquals("minecraft:enchantments", p.componentId());
        assertTrue(p.selector().isEmpty());
    }

    /** SFML 语法中所有合法标识符字符（[a-zA-Z0-9_*]）都能通过 parse。 */
    @Test
    public void legalIdentifierCharactersAllPass() {
        for (String matcher : new String[]{
                "nbt:minecraft/enchantments/sharpness",
                "nbt:minecraft/enchantments/fire_protection",
                "nbt:minecraft/custom_data/tier_2_level",
                "nbt:minecraft/damage",
                "nbt:minecraft/potion_contents/long_night_vision",
        }) {
            assertNotNull(NbtMatcherHook.parse(matcher), "合法标识符必须解析成功: " + matcher);
        }
    }
}
