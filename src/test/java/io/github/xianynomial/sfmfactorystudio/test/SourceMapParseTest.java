package io.github.xianynomial.sfmfactorystudio.test;

import io.github.xianynomial.sfmfactorystudio.client.blocks.model.BProgram;
import io.github.xianynomial.sfmfactorystudio.client.blocks.model.SfmlToBlocks;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * {@code parseWithMap} 是否把每个积木的源码区间记对了。
 *
 * <p>双向联动全靠这张表：区间错一位，跳转就落到别的行上。
 * 所以这里不只看"有没有记录"，而是拿 {@code substring(start, end)}
 * 回读原文，确认起点确实是该语句的第一个关键字。
 */
class SourceMapParseTest {

    private static final String SAMPLE =
            "every 20 ticks do\n    input from a\n    output to b\nend";

    @Test
    void recordsTriggerAndStatementRanges() {
        SfmlToBlocks.Parsed parsed = SfmlToBlocks.parseWithMap(SAMPLE);
        assertNotNull(parsed.program(), "样例应能解析");
        assertTrue(parsed.errors().isEmpty(), "解析不应报错：" + parsed.errors());
        assertEquals(1, parsed.program().triggers.size());

        BProgram.Trigger trigger = parsed.program().triggers.get(0);
        int[] tr = parsed.sourceMap().rangeOf(trigger.id);
        assertNotNull(tr, "trigger 必须记录区间");
        String triggerText = SAMPLE.substring(tr[0], tr[1]);
        assertTrue(triggerText.startsWith("every"), "trigger 区间起点应对上 every，实际：" + triggerText);

        assertEquals(2, trigger.body.size(), "样例有两条语句");
        int[] inRange = parsed.sourceMap().rangeOf(trigger.body.get(0).id);
        assertNotNull(inRange, "input 语句必须记录区间");
        assertTrue(SAMPLE.substring(inRange[0], inRange[1]).startsWith("input"),
                "第一条语句的区间起点应对上 input");

        int[] outRange = parsed.sourceMap().rangeOf(trigger.body.get(1).id);
        assertNotNull(outRange, "output 语句必须记录区间");
        assertTrue(SAMPLE.substring(outRange[0], outRange[1]).startsWith("output"));
    }

    @Test
    void plainParseDoesNotPopulateAnyMap() {
        // 普通 parse 不收集位置，不能因为 ThreadLocal 残留而串味
        SfmlToBlocks.Result r = SfmlToBlocks.parse(SAMPLE);
        assertTrue(r.ok());
        // 再跑一次 parseWithMap，确认 map 是新建的、不累积
        SfmlToBlocks.Parsed p1 = SfmlToBlocks.parseWithMap(SAMPLE);
        int size1 = p1.sourceMap().size();
        SfmlToBlocks.Parsed p2 = SfmlToBlocks.parseWithMap(SAMPLE);
        assertEquals(size1, p2.sourceMap().size(), "每次解析都应得到独立的映射表");
    }

    @Test
    void cursorInsideStatementResolvesToThatStatement() {
        SfmlToBlocks.Parsed parsed = SfmlToBlocks.parseWithMap(SAMPLE);
        BProgram.Trigger trigger = parsed.program().triggers.get(0);
        int[] inRange = parsed.sourceMap().rangeOf(trigger.body.get(0).id);

        long hit = parsed.sourceMap().blockAt(inRange[0] + 3);
        assertEquals(trigger.body.get(0).id, hit, "光标落在 input 区间内应命中该语句");
    }

    @Test
    void emptyProgramProducesEmptyMap() {
        SfmlToBlocks.Parsed parsed = SfmlToBlocks.parseWithMap("");
        assertTrue(parsed.sourceMap().isEmpty(), "空程序不应产生任何区间");
        assertEquals(-1, parsed.sourceMap().blockAt(0));
    }

    @Test
    void rangeStaysInsideTheSource() {
        SfmlToBlocks.Parsed parsed = SfmlToBlocks.parseWithMap(SAMPLE);
        for (BProgram.Trigger t : parsed.program().triggers) {
            int[] r = parsed.sourceMap().rangeOf(t.id);
            assertNotNull(r);
            assertTrue(r[0] >= 0 && r[1] <= SAMPLE.length(),
                    "区间必须落在源码长度内：" + r[0] + ".." + r[1] + " / " + SAMPLE.length());
            assertTrue(r[1] > r[0], "区间必须非空");
        }
    }

    // ---------------------------------------------------------------- 生成方向

    @Test
    void generatedMapRangesMatchOutput() {
        String source = "every 20 ticks do\n    input from a\n    output to b\nend";
        SfmlToBlocks.Parsed parsed = SfmlToBlocks.parseWithMap(source);
        assertTrue(parsed.errors().isEmpty(), "样例应能解析：" + parsed.errors());

        var mapped = io.github.xianynomial.sfmfactorystudio.client.blocks.model.BlocksToSfml
                .toSfmlWithMap(parsed.program());
        String out = mapped.sfml();
        BProgram.Trigger trigger = parsed.program().triggers.get(0);

        int[] tr = mapped.sourceMap().rangeOf(trigger.id);
        assertNotNull(tr, "生成的映射必须包含 trigger");
        assertTrue(out.substring(tr[0], tr[1]).contains("every"),
                "trigger 区间应包含 every，实际：" + out.substring(tr[0], tr[1]));

        assertEquals(2, trigger.body.size());
        int[] inRange = mapped.sourceMap().rangeOf(trigger.body.get(0).id);
        assertNotNull(inRange, "生成的映射必须包含第一条语句");
        assertTrue(out.substring(inRange[0], inRange[1]).strip().startsWith("input"),
                "第一条语句区间应以 input 开头，实际：" + out.substring(inRange[0], inRange[1]));

        int[] outRange = mapped.sourceMap().rangeOf(trigger.body.get(1).id);
        assertNotNull(outRange);
        assertTrue(out.substring(outRange[0], outRange[1]).strip().startsWith("output"));
    }

    @Test
    void plainToSfmlDoesNotLeakMapState() {
        String source = "every 20 ticks do\n    input from a\nend";
        SfmlToBlocks.Parsed parsed = SfmlToBlocks.parseWithMap(source);
        // 先跑一次带 map 的，再跑普通 toSfml，确认 ThreadLocal 不残留
        io.github.xianynomial.sfmfactorystudio.client.blocks.model.BlocksToSfml
                .toSfmlWithMap(parsed.program());
        String plain = io.github.xianynomial.sfmfactorystudio.client.blocks.model.BlocksToSfml
                .toSfml(parsed.program());
        assertNotNull(plain);
        var again = io.github.xianynomial.sfmfactorystudio.client.blocks.model.BlocksToSfml
                .toSfmlWithMap(parsed.program());
        assertEquals(plain, again.sfml(), "带不带 map 生成的文本必须完全一致");
    }
}
