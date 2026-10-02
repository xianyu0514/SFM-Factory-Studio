package io.github.xianynomial.sfmfactorystudio.test;

import io.github.xianynomial.sfmfactorystudio.client.blocks.model.SfmlKeywordDocs;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 关键词悬停文档护栏：数据表完整性 + 切词/查表/整词匹配的纯逻辑行为。 */
public class SfmlKeywordDocsTest {

    @Test
    public void tableIsSubstantialAndComplete() {
        assertTrue(SfmlKeywordDocs.size() >= 45,
                "关键词文档应覆盖大规模词表（骤降=数据丢失）: " + SfmlKeywordDocs.size());
        for (String key : List.of("if", "else", "then", "do", "end", "every", "input", "output",
                "retain", "forget", "with", "without", "except", "slots", "redstone", "pulse",
                "name", "round", "robin", "by", "top", "bottom", "north", "south", "east", "west",
                "side", "tick", "ticks", "second", "seconds", "overall", "some", "one", "each",
                "not", "and", "or", "gt", "lt", "eq", "le", "ge")) {
            var entry = SfmlKeywordDocs.lookup(key);
            assertNotNull(entry, "缺少关键词文档: " + key);
            assertFalse(entry.en().isBlank(), key + " 缺英文说明");
            assertFalse(entry.zh().isBlank(), key + " 缺中文说明");
            assertFalse(entry.examples().isEmpty(), key + " 缺示例");
            for (String example : entry.examples()) {
                assertFalse(example.isBlank(), key + " 存在空示例");
            }
        }
        for (String symbol : new String[]{">", "<", "=", "<=", ">="}) {
            assertNotNull(SfmlKeywordDocs.lookup(symbol), "缺少比较符文档: " + symbol);
        }
        assertNotNull(SfmlKeywordDocs.lookup("INPUT"), "查表应大小写不敏感");
    }

    @Test
    public void wordAtExtractsWordsAndSymbolRuns() {
        String text = "input retain 64 coal from chest";
        assertEquals("input", SfmlKeywordDocs.wordAt(text, 0));
        assertEquals("retain", SfmlKeywordDocs.wordAt(text, 8));
        assertEquals("chest", SfmlKeywordDocs.wordAt(text, text.length() - 1));
        assertNull(SfmlKeywordDocs.wordAt(text, 5), "空白处无词");
        assertNull(SfmlKeywordDocs.wordAt("", 0));

        String compact = "if redstone<=5 then";
        assertEquals("<=", SfmlKeywordDocs.wordAt(compact, compact.indexOf('<')),
                "紧凑写法 a<=b 中的 < 应切出符号词");
        assertEquals("<=", SfmlKeywordDocs.wordAt(compact, compact.indexOf('=')));
        assertEquals("if", SfmlKeywordDocs.wordAt(compact, 0));
        assertEquals("redstone", SfmlKeywordDocs.wordAt(compact, compact.indexOf("redstone") + 3));

        String tagged = "input with #minecraft:logs";
        assertEquals("#minecraft:logs", SfmlKeywordDocs.wordAt(tagged, tagged.indexOf('#') + 2));

        String spaced = "if redstone > 5 then";
        assertEquals(">", SfmlKeywordDocs.wordAt(spaced, spaced.indexOf('>')));
    }

    @Test
    public void occurrencesMatchWholeTokensOnly() {
        String text = "input from chest, output to chest10, input retain";
        var hits = SfmlKeywordDocs.occurrences(text, "chest");
        assertEquals(1, hits.size(), "chest10 内的 chest 不是整词，不得匹配");
        assertEquals(text.indexOf("chest"), hits.get(0)[0]);
        assertEquals(2, SfmlKeywordDocs.occurrences(text, "input").size());
        assertTrue(SfmlKeywordDocs.occurrences(text, "zebra").isEmpty());
        // 符号整词：a<=b 里的 <= 也应被高亮
        var le = SfmlKeywordDocs.occurrences("a<=b <= c", "<=");
        assertEquals(2, le.size());
        assertTrue(SfmlKeywordDocs.occurrences(null, "x").isEmpty());
        assertTrue(SfmlKeywordDocs.occurrences("abc", "").isEmpty());
    }
}
