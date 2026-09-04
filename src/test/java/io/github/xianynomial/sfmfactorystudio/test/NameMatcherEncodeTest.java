package io.github.xianomial.sfmfactorystudio.test;

import io.github.xianynomial.sfmfactorystudio.net.NbtMatcherHook;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/** 名称匹配段编码：英文精确匹配可编码，中文/符号退回存在性。 */
public class NameMatcherEncodeTest {

    @Test
    public void encodableNames() {
        assertEquals("Excalibur", NbtMatcherHook.encodeNameMatcher("Excalibur"));
        assertEquals("Sword__of__Doom", NbtMatcherHook.encodeNameMatcher("Sword of Doom"));
        assertEquals("blaze_rod_x3", NbtMatcherHook.encodeNameMatcher("blaze_rod_x3"));
        assertEquals("MK2", NbtMatcherHook.encodeNameMatcher("  MK2  "));   // 首尾空格忽略
    }

    @Test
    public void unencodableNamesFallBack() {
        assertNull(NbtMatcherHook.encodeNameMatcher("传说之剑"), "中文 → 存在性匹配");
        assertNull(NbtMatcherHook.encodeNameMatcher("Doom!"), "符号 → 存在性匹配");
        assertNull(NbtMatcherHook.encodeNameMatcher("*"), "星号是通配符 → 不当字面量编码");
        assertNull(NbtMatcherHook.encodeNameMatcher("   "), "空名 → 存在性匹配");
    }
}
