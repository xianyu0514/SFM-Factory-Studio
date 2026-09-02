package dev.jimu.sfmjimu.test;

import ca.teamdman.sfmjimu.client.blocks.model.BProgram;
import ca.teamdman.sfmjimu.client.blocks.model.BlocksToSfml;
import ca.teamdman.sfmjimu.client.blocks.model.SfmlToBlocks;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Round-trip tests: blocks → SFML must compile with SFM's own parser, and
 * SFML → blocks → SFML must be stable (re-parse yields identical text).
 */
public class BlockCodegenTests {

    private static final String BS_N = "\n";

    private static String roundTrip(String sfml) {
        SfmlToBlocks.Result r1 = SfmlToBlocks.parse(sfml);
        assertTrue(r1.ok(), "parse failed: " + r1.errors());
        assertNotNull(r1.program());
        String text = BlocksToSfml.toSfml(r1.program());
        SfmlTestSupport.assertNoCompileErrors(text);
        SfmlToBlocks.Result r2 = SfmlToBlocks.parse(text);
        assertTrue(r2.ok(), "re-parse failed: " + r2.errors());
        assertEquals(text, BlocksToSfml.toSfml(r2.program()), "round trip not stable");
        return text;
    }

    @Test
    public void simpleMove() {
        BProgram p = new BProgram();
        p.name = "move items";
        var t = new BProgram.TimerTrigger();
        t.count = 20;
        var in = new BProgram.Statement.Input();
        in.access.labels.add("a");
        t.body.add(in);
        var out = new BProgram.Statement.Output();
        out.access.labels.add("b");
        t.body.add(out);
        p.triggers.add(t);

        String sfml = BlocksToSfml.toSfml(p);
        SfmlTestSupport.assertNoCompileErrors(sfml);
        assertEquals("name \"move items\"\n\nevery 20 ticks do\n    input from a\n    output to b\nend\n", sfml);
    }

    @Test
    public void quantitiesLimitsSidesSlots() {
        String sfml = roundTrip("""
                name "limits"
                every 30 ticks do
                    input 5 retain 10 each minecraft:iron_ingot from a top side slots 1-4
                    output 20 to each b
                end
                every redstone pulse do
                    input from a, b round robin by label north, east side
                    output to empty slots in c
                end
                """);
        assertTrue(sfml.contains("input 5 retain 10 each minecraft:iron_ingot from a top side slot 1-4"));
        assertTrue(sfml.contains("output 20 to each b"));
        assertTrue(sfml.contains("every redstone pulse do"));
        assertTrue(sfml.contains("input from a, b round robin by label north, east side"));
        assertTrue(sfml.contains("output to empty slots in c"));
    }

    @Test
    public void conditions() {
        String sfml = roundTrip("""
                every 20 ticks do
                    if a has > 10 iron_ingot then
                        output to b
                    else if redstone > 0 and b has >= 5 stone then
                        output to c
                    else
                        forget
                    end
                end
                every 1 seconds do
                    if not overall a has < 3 coal or some b has = 0 diamond then
                        input 1 from a
                    end
                end
                """);
        assertTrue(sfml.contains("if a has > 10 iron_ingot then"));
        assertTrue(sfml.contains("else if redstone > 0 and b has >= 5 stone then"));
        assertTrue(sfml.contains("else\n        forget"));
        assertTrue(sfml.contains("not overall a has < 3 coal) or some b has = 0 diamond"));
    }

    @Test
    public void resourceTypesAndQuoting() {
        String sfml = roundTrip("""
                every 20 ticks do
                    input fluid:minecraft:water, mekanism:hydrogen from "my tank"
                    output forge_energy:minecraft:energy to "输入"
                    input item:* from a
                end
                """);
        assertTrue(sfml.contains("input fluid:minecraft:water, mekanism:hydrogen from \"my tank\""));
        assertTrue(sfml.contains("to \"输入\""));
        assertTrue(sfml.contains("input item:* from a"));
    }

    @Test
    public void exceptAndWithSurvive() {
        roundTrip("""
                every 20 ticks do
                    input except minecraft:dirt, minecraft:stone from a
                    output to b
                    input minecraft:* with #minecraft:logs from a
                end
                """);
    }

    @Test
    public void hasWithClauseIsFullyStructured() {
        String text = roundTrip("""
                every 20 ticks do
                    if a has > 0 iron_ingot with #minecraft:beacon_base_blocks then
                        output to b
                    end
                end
                """);
        assertTrue(text.contains("with #minecraft:beacon_base_blocks"));
    }

    @Test
    public void sfmTemplateProgramsRoundTrip() {
        for (String sfml : SAMPLES) {
            String once = roundTrip(sfml);
            String twice = roundTrip(once);
            assertEquals(once, twice);
        }
    }

    private static final List<String> SAMPLES = List.of(
            """
                    name "furnace"
                    every 20 ticks do
                        input from "furnaces"
                        output to "chests"
                    end
                    """,
            """
                    every 30 ticks do
                        if chest has gt 64 iron_ore then
                            output 64 to each furnace
                        end
                    end
                    """,
            """
                    every redstone pulse do
                        forget
                    end
                    """
    );

    @Test
    public void commentBlocks() {
        BProgram p = new BProgram();
        var t = new BProgram.TimerTrigger();
        t.count = 20;
        var c = new BProgram.Statement.Comment("先取出再存入，避免堆积");
        t.body.add(c);
        var in = new BProgram.Statement.Input();
        in.access.labels.add("a");
        t.body.add(in);
        p.triggers.add(t);

        String sfml = BlocksToSfml.toSfml(p);
        SfmlTestSupport.assertNoCompileErrors(sfml);
        assertTrue(sfml.contains("-- 先取出再存入，避免堆积"));

        // Imported programs must keep user documentation as editable blocks.
        SfmlToBlocks.Result r = SfmlToBlocks.parse(sfml);
        assertTrue(r.ok());
        assertEquals(1, countComments(r.program()));
        assertEquals("先取出再存入，避免堆积",
                ((BProgram.Statement.Comment) r.program().triggers.get(0).body.get(0)).text);
    }

    @Test
    public void importedCommentsKeepTheirStructure() {
        String original = """
                -- 文件顶部说明
                name "注释往返"
                -- 第一组任务
                every 20 ticks do
                    -- 输入前
                    input from a -- 输入后
                    if true then
                        -- 条件成立时
                        output to b
                    else
                        -- 暂时不执行
                    end
                end
                -- 第二组任务
                every redstone pulse do
                    -- 收到信号时
                    forget
                end
                -- 文件末尾说明
                """;

        SfmlToBlocks.Result imported = SfmlToBlocks.parse(original);
        assertTrue(imported.ok(), imported.errors().toString());
        assertEquals(9, countComments(imported.program()));
        assertEquals(List.of("文件顶部说明"), imported.program().fileHeaderComments);
        assertEquals(List.of("第一组任务"), imported.program().preambleComments);
        assertEquals(List.of("第二组任务"), imported.program().triggers.get(1).leadingComments);
        assertEquals(List.of("文件末尾说明"), imported.program().trailingComments);

        var iff = (BProgram.Statement.If) imported.program().triggers.get(0).body.stream()
                .filter(BProgram.Statement.If.class::isInstance)
                .findFirst()
                .orElseThrow();
        assertTrue(iff.hasElse);
        assertEquals("条件成立时", ((BProgram.Statement.Comment) iff.branches.get(0).body.get(0)).text);
        assertEquals("暂时不执行", ((BProgram.Statement.Comment) iff.elseBody.get(0)).text);

        String generated = BlocksToSfml.toSfml(imported.program());
        SfmlTestSupport.assertNoCompileErrors(generated);
        SfmlToBlocks.Result reimported = SfmlToBlocks.parse(generated);
        assertTrue(reimported.ok(), reimported.errors().toString());
        assertEquals(generated, BlocksToSfml.toSfml(reimported.program()));
        assertEquals(9, countComments(reimported.program()));
    }

    @Test
    public void emptyElseBranchIsNotSilentlyRemoved() {
        String original = """
                every 20 ticks do
                    if true then
                        forget
                    else
                    end
                end
                """;
        SfmlToBlocks.Result imported = SfmlToBlocks.parse(original);
        assertTrue(imported.ok(), imported.errors().toString());
        String generated = BlocksToSfml.toSfml(imported.program());
        assertTrue(generated.contains("    else\n"), generated);
        assertEquals(generated, BlocksToSfml.toSfml(SfmlToBlocks.parse(generated).program()));
    }

    private static long countComments(BProgram p) {
        long n = p.fileHeaderComments.size() + p.preambleComments.size() + p.trailingComments.size();
        for (BProgram.Trigger t : p.triggers) {
            n += t.leadingComments.size();
            for (BProgram.Statement s : t.body) {
                n += countComments(s);
            }
        }
        return n;
    }

    private static long countComments(BProgram.Statement statement) {
        if (statement instanceof BProgram.Statement.Comment) return 1;
        if (!(statement instanceof BProgram.Statement.If iff)) return 0;
        long count = 0;
        for (BProgram.Branch branch : iff.branches) {
            for (BProgram.Statement child : branch.body) count += countComments(child);
        }
        for (BProgram.Statement child : iff.elseBody) count += countComments(child);
        return count;
    }
}
