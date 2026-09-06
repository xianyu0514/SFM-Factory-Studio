package io.github.xianynomial.sfmfactorystudio.test;

import io.github.xianynomial.sfmfactorystudio.client.blocks.model.BProgram;
import io.github.xianynomial.sfmfactorystudio.client.blocks.model.BlocksToSfml;
import io.github.xianynomial.sfmfactorystudio.client.blocks.model.SfmlToBlocks;
import io.github.xianynomial.sfmfactorystudio.client.blocks.model.SfmlValidate;
import ca.teamdman.langs.SFMLLexer;
import org.antlr.v4.runtime.CharStreams;
import org.antlr.v4.runtime.CommonTokenStream;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.fail;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Coverage proof: every official SFM template program must round-trip through
 * the block model without changing semantics. "Semantics preserved" is checked
 * three ways:
 *  1. re-import of the generated text is byte-identical (stability), and
 *  2. SFM's own compiler reports the exact same diagnostics for the generated
 *     text as for the original (so anything the headless environment can't
 *     verify equally applies to both).
 *  3. when the headless compiler can fully build the program, SFM's canonical
 *     compiled AST representation is identical before and after the round trip.
 * Additionally, targeted edge cases exercise every grammar production the
 * editor claims to support structurally.
 */
public class SFMCoverageTests {

    private static List<Path> templates() throws IOException {
        Path dir = Paths.get("src", "test", "resources", "sfm_templates");
        try (Stream<Path> s = Files.list(dir)) {
            return s.filter(p -> p.toString().endsWith(".sfml")).sorted().collect(Collectors.toList());
        }
    }

    private static void assertRoundTrip(String original, String label) {
        SfmlToBlocks.Result r1 = SfmlToBlocks.parse(original);
        assertTrue(r1.ok(), label + ": parse failed: " + r1.errors());
        assertNoCompatibilityNodes(r1.program(), label);
        String gen1 = BlocksToSfml.toSfml(r1.program());

        SfmlToBlocks.Result r2 = SfmlToBlocks.parse(gen1);
        assertTrue(r2.ok(), label + ": re-parse failed: " + r2.errors());
        String gen2 = BlocksToSfml.toSfml(r2.program());
        assertEquals(gen1, gen2, label + ": round trip not stable");
        assertEquals(commentTexts(original), commentTexts(gen1), label + ": comments changed");

        List<String> errOriginal = sorted(SfmlValidate.check(original));
        List<String> errGenerated = sorted(SfmlValidate.check(gen1));
        assertEquals(errOriginal, errGenerated, label + ": compile diagnostics changed");
        if (errOriginal.isEmpty()) {
            assertEquals(SfmlTestSupport.semanticFingerprint(original), SfmlTestSupport.semanticFingerprint(gen1),
                    label + ": compiled SFM meaning changed");
        }
    }

    private static List<String> commentTexts(String sfml) {
        var lexer = new SFMLLexer(CharStreams.fromString(sfml == null ? "" : sfml));
        var tokens = new CommonTokenStream(lexer);
        tokens.fill();
        return tokens.getTokens().stream()
                .filter(token -> token.getType() == SFMLLexer.LINE_COMMENT)
                .map(token -> token.getText().replaceFirst("^-- ?", "").stripTrailing())
                .toList();
    }

    private static void assertNoCompatibilityNodes(BProgram program, String label) {
        for (BProgram.Trigger trigger : program.triggers) {
            for (BProgram.Statement statement : trigger.body) {
                assertNoCompatibilityStatement(statement, label);
            }
        }
    }

    private static void assertNoCompatibilityStatement(BProgram.Statement statement, String label) {
        if (statement instanceof BProgram.Statement.Raw raw) {
            fail(label + ": fell back to raw statement: " + raw.text);
        }
        if (statement instanceof BProgram.Statement.If iff) {
            for (BProgram.Branch branch : iff.branches) {
                assertNoCompatibilityBool(branch.cond, label);
                for (BProgram.Statement child : branch.body) assertNoCompatibilityStatement(child, label);
            }
            for (BProgram.Statement child : iff.elseBody) assertNoCompatibilityStatement(child, label);
        }
    }

    private static void assertNoCompatibilityBool(BProgram.Bool bool, String label) {
        if (bool instanceof BProgram.Bool.RawBool raw) {
            fail(label + ": fell back to raw condition: " + raw.text);
        } else if (bool instanceof BProgram.Bool.And and) {
            for (BProgram.Bool part : and.parts) assertNoCompatibilityBool(part, label);
        } else if (bool instanceof BProgram.Bool.Or or) {
            for (BProgram.Bool part : or.parts) assertNoCompatibilityBool(part, label);
        } else if (bool instanceof BProgram.Bool.Not not) {
            assertNoCompatibilityBool(not.inner, label);
        }
    }

    private static List<String> sorted(List<String> in) {
        List<String> out = new ArrayList<>(in);
        out.sort(String::compareTo);
        return out;
    }

    @Test
    public void officialSfmTemplates() throws IOException {
        // changelog/known_issues contain intentionally-broken sample snippets;
        // everything else must round-trip cleanly.
        // resource_types is a datagen template with $placeholders, not valid SFML until processed
        List<String> allowBroken = List.of("changelog", "known_issues", "resource_types");
        int checked = 0;
        for (Path p : templates()) {
            String name = p.getFileName().toString().replace(".sfml", "");
            String text = Files.readString(p, StandardCharsets.UTF_8);
            if (allowBroken.stream().anyMatch(name::contains)) continue;
            assertRoundTrip(text, name);
            checked++;
        }
        assertTrue(checked >= 12, "expected at least 12 templates, got " + checked);
    }

    @Test
    public void officialRepositoryExamples() throws IOException {
        Path dir = Paths.get("..", "SuperFactoryManager", "examples");
        Assumptions.assumeTrue(Files.isDirectory(dir), "上游 SFM 源码目录不存在，跳过工作区联调");
        List<Path> examples;
        try (Stream<Path> files = Files.list(dir)) {
            examples = files
                    .filter(path -> path.getFileName().toString().endsWith(".sfm"))
                    .sorted()
                    .toList();
        }
        assertEquals(8, examples.size(), "expected the eight documented SFM examples");
        for (Path example : examples) {
            assertRoundTrip(Files.readString(example, StandardCharsets.UTF_8), example.getFileName().toString());
        }
    }

    @Test
    public void intervalGlobalAndPlus() {
        assertRoundTrip("""
                every 30 global plus 5 ticks do
                    input from a
                end
                every 1 second do
                    output to b
                end
                every 20g ticks do
                    forget
                end
                """, "intervals");
    }

    @Test
    public void withClauses() {
        assertRoundTrip("""
                every 20 ticks do
                    input with #forge:gems from chest
                    input with tag refinedstorage:disks/items/* from chest
                    input with not #needs_stone_tool from chest
                    input without #piglin_loved from chest
                    input * with #ores and not #needs_stone_tool from chest
                    input minecraft: with #ingots from chest
                end
                """, "with clauses");
    }

    @Test
    public void multiClauseLimitsAndExcept() {
        assertRoundTrip("""
                every 20 ticks do
                    input 3 retain 5 with tag #forge:ingots, 4 dirt, with #mineable/axe, stone except sand, gold_ingot from chest
                    output to other_chest
                end
                """, "multi clause limits");
    }

    @Test
    public void alternateWordOrder() {
        assertRoundTrip("""
                every 20 ticks do
                    from a input 5 iron_ingot
                    to each b output 20
                end
                """, "word order");
    }

    @Test
    public void nestedBoolExpressions() {
        assertRoundTrip("""
                every 20 ticks do
                    if (a has > 1 stone or b has > 1 dirt) and redstone > 0 then
                        output to c
                    end
                    if not (a has >= 1 iron and b has <= 2 gold) then
                        output to d
                    end
                    if one a has = 5 coal then
                        forget a
                    end
                end
                """, "nested bools");
    }

    @Test
    public void allSetOperators() {
        assertRoundTrip("""
                every 20 ticks do
                    if overall a has >= 10 iron_ingot then
                        forget
                    end
                    if some a has > 10 iron_ingot then
                        forget
                    end
                    if every a has < 10 iron_ingot then
                        forget
                    end
                    if each a has <= 10 iron_ingot then
                        forget
                    end
                    if one a has = 10 iron_ingot then
                        forget
                    end
                    if lone a has < 10 iron_ingot then
                        forget
                    end
                end
                """, "set operators");
    }

    @Test
    public void labelAccessVariants() {
        assertRoundTrip("""
                every 20 ticks do
                    input from "my chest", b round robin by block top, north side slot 1, 3-5
                    output to empty slots in each "输出箱"
                end
                """, "label access");
    }

    @Test
    public void stringResourcesAndQuotedIds() {
        assertRoundTrip("""
                every 20 ticks do
                    input "minecraft:redstone" from a
                    output fluid:minecraft:water to b
                end
                """, "string resources");
    }

    @Test
    public void pulseTriggerWithLogic() {
        assertRoundTrip("""
                every redstone pulse do
                    if redstone then
                        input from a
                    end
                    forget
                end
                """, "pulse logic");
    }

    @Test
    public void emptyAndNameOnlyPrograms() {
        assertRoundTrip("", "blank");
        assertRoundTrip("name \"only a name\"", "name only");
    }
}
