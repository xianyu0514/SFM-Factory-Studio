package io.github.xianynomial.sfmfactorystudio.test;

import io.github.xianynomial.sfmfactorystudio.client.blocks.model.SfmlSyntax;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** SFML 语法白名单唯一裁决点的行为护栏。 */
public class SfmlSyntaxTest {

    @Test
    public void encodableTags() {
        assertTrue(SfmlSyntax.isEncodableTag("minecraft:logs"));
        assertTrue(SfmlSyntax.isEncodableTag("logs"));
        assertTrue(SfmlSyntax.isEncodableTag("c:ingots/iron"));
        assertTrue(SfmlSyntax.isEncodableTag("*"));
        assertTrue(SfmlSyntax.isEncodableTag("minecraft:*"));
        assertTrue(SfmlSyntax.isEncodableTag("foo*bar"));
    }

    @Test
    public void unencodableTags() {
        assertFalse(SfmlSyntax.isEncodableTag("c:foo-bar"), "连字符 SFML 不允许");
        assertFalse(SfmlSyntax.isEncodableTag("has.some.dot"), "点号 SFML 不允许");
        assertFalse(SfmlSyntax.isEncodableTag(""), "空串");
        assertFalse(SfmlSyntax.isEncodableTag(null));
        assertFalse(SfmlSyntax.isEncodableTag("  "), "纯空白");
        assertFalse(SfmlSyntax.isEncodableTag("含中文"), "非 ASCII");
    }

    @Test
    public void sanitizeStripsHashAndTrims() {
        assertEquals("minecraft:logs", SfmlSyntax.sanitizeTagMatcher("  #minecraft:logs  "));
        assertEquals("logs", SfmlSyntax.sanitizeTagMatcher("##logs"));
        assertNull(SfmlSyntax.sanitizeTagMatcher("bad-name"));
        assertNull(SfmlSyntax.sanitizeTagMatcher(null));
    }
}
