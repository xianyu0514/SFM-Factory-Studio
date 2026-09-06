package io.github.xianynomial.sfmfactorystudio.test;

import io.github.xianynomial.sfmfactorystudio.client.blocks.model.BProgram;
import io.github.xianynomial.sfmfactorystudio.client.blocks.model.BlocksToSfml;
import io.github.xianynomial.sfmfactorystudio.client.blocks.model.ProgramDiagnostics;
import io.github.xianynomial.sfmfactorystudio.client.blocks.model.SfmlToBlocks;
import org.junit.jupiter.api.Test;

import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Proof that every state reachable through ordinary typed blocks emits valid SFML. */
public class BlockInvariantTests {

    @Test
    public void completeTriggerCopyKeepsAllNestedBlocksButSharesNoMutableState() {
        BProgram.TimerTrigger source = new BProgram.TimerTrigger();
        source.count = 40;
        BProgram.Statement.If iff = new BProgram.Statement.If();
        BProgram.Branch branch = new BProgram.Branch();
        BProgram.Bool.Has has = new BProgram.Bool.Has();
        has.access.labels.add("来源箱");
        has.resources.add(BProgram.ResourceRef.parse("fluid::water"));
        branch.cond = has;
        BProgram.Statement.Input input = new BProgram.Statement.Input();
        input.access.labels.add("来源箱");
        BProgram.ResourceLimit limit = new BProgram.ResourceLimit();
        limit.quantity = 1000L;
        limit.resources.add(BProgram.ResourceRef.parse("fluid::water"));
        input.limits.add(limit);
        branch.body.add(input);
        iff.branches.add(branch);
        iff.hasElse = true;
        BProgram.Statement.Output output = new BProgram.Statement.Output();
        output.access.labels.add("目标箱");
        iff.elseBody.add(output);
        source.body.add(iff);

        BProgram.TimerTrigger copy = source.copy();
        BProgram originalProgram = new BProgram();
        originalProgram.triggers.add(source);
        BProgram copiedProgram = new BProgram();
        copiedProgram.triggers.add(copy);
        assertEquals(BlocksToSfml.toSfml(originalProgram), BlocksToSfml.toSfml(copiedProgram));
        assertNotSame(source.body.get(0), copy.body.get(0));

        BProgram.Statement.If copiedIf = (BProgram.Statement.If) copy.body.get(0);
        BProgram.Statement.Input copiedInput = (BProgram.Statement.Input) copiedIf.branches.get(0).body.get(0);
        copiedInput.access.labels.set(0, "修改后的来源");
        BProgram.Statement.If originalIf = (BProgram.Statement.If) source.body.get(0);
        BProgram.Statement.Input originalInput = (BProgram.Statement.Input) originalIf.branches.get(0).body.get(0);
        assertEquals("来源箱", originalInput.access.labels.get(0));
    }

    @Test
    public void incompleteMutableStateStillSerializesAsValidSfml() {
        BProgram program = new BProgram();
        BProgram.TimerTrigger trigger = new BProgram.TimerTrigger();
        trigger.count = -5;

        BProgram.Statement.Input input = new BProgram.Statement.Input();
        input.limits.add(new BProgram.ResourceLimit());
        input.limits.add(new BProgram.ResourceLimit());
        input.access.eachSide = true;
        input.access.sides.add(BProgram.Side.TOP); // diagnostic catches it; renderer chooses one legal form
        trigger.body.add(input);
        trigger.body.add(new BProgram.Statement.Comment(""));

        BProgram.Statement.If emptyIf = new BProgram.Statement.If();
        trigger.body.add(emptyIf);
        trigger.body.add(new BProgram.Statement.Raw("this is not sfml"));
        program.triggers.add(trigger);

        String generated = BlocksToSfml.toSfml(program);
        SfmlTestSupport.assertNoCompileErrors(generated);
        assertFalse(ProgramDiagnostics.errorMessages(program).isEmpty());
        assertTrue(generated.contains("-- 无效兼容代码已停用"));
    }

    @Test
    public void structuredWithAndHasExceptRoundTripWithoutRawFallback() {
        String source = """
                every 20 ticks do
                    input minecraft:* with (#forge:ores or #forge:gems) and not #forge:dusts from chest
                    if chest has >= 4 minecraft:iron_ingot with #forge:ingots except minecraft:gold_ingot then
                        output to target
                    end
                end
                """;

        SfmlToBlocks.Result parsed = SfmlToBlocks.parse(source);
        assertTrue(parsed.ok(), parsed.errors().toString());
        var trigger = parsed.program().triggers.get(0);
        var input = assertInstanceOf(BProgram.Statement.Input.class, trigger.body.get(0));
        assertTrue(input.limits.get(0).with != null);
        var iff = assertInstanceOf(BProgram.Statement.If.class, trigger.body.get(1));
        var has = assertInstanceOf(BProgram.Bool.Has.class, iff.branches.get(0).cond);
        assertTrue(has.with != null);
        assertTrue(has.except.contains(BProgram.ResourceRef.parse("minecraft:gold_ingot")));

        String generated = BlocksToSfml.toSfml(parsed.program());
        SfmlTestSupport.assertNoCompileErrors(generated);
        assertTrue(SfmlToBlocks.parse(generated).ok());
    }

    @Test
    public void typedFieldsNormalizeGrammarSynonymsAndConflicts() {
        SfmlToBlocks.Result parsed = SfmlToBlocks.parse("""
                every 20 ticks do
                    input from a each side slots 1, 3-5
                    if each a has ge 1 stone then
                        output to b round robin by block
                    end
                end
                """);
        assertTrue(parsed.ok(), parsed.errors().toString());
        var input = (BProgram.Statement.Input) parsed.program().triggers.get(0).body.get(0);
        assertTrue(input.access.eachSide);
        assertTrue(input.access.slots.contains(new BProgram.SlotRange(3, 5)));
        var iff = (BProgram.Statement.If) parsed.program().triggers.get(0).body.get(1);
        var has = (BProgram.Bool.Has) iff.branches.get(0).cond;
        assertTrue(has.setMode == BProgram.Bool.SetMode.EVERY);
        SfmlTestSupport.assertNoCompileErrors(BlocksToSfml.toSfml(parsed.program()));
    }

    @Test
    public void keywordAndNumericResourceIdsAreSafelyQuoted() {
        assertTrue(BlocksToSfml.quoteResourceIfNeeded("input").equals("\"input\""));
        assertTrue(BlocksToSfml.quoteResourceIfNeeded("123").equals("\"123\""));
        assertTrue(BlocksToSfml.quoteResourceIfNeeded("item::").equals("item::"));
    }

    @Test
    public void partialForgetKeepsOtherInputsAvailableButOutputAloneIsRejected() {
        BProgram program = new BProgram();
        BProgram.TimerTrigger trigger = new BProgram.TimerTrigger();
        BProgram.Statement.Input fromA = new BProgram.Statement.Input();
        fromA.access.labels.add("a");
        BProgram.Statement.Input fromB = new BProgram.Statement.Input();
        fromB.access.labels.add("b");
        BProgram.Statement.Forget forgetA = new BProgram.Statement.Forget();
        forgetA.labels.add("a");
        BProgram.Statement.Output output = new BProgram.Statement.Output();
        output.access.labels.add("target");
        trigger.body.add(fromA);
        trigger.body.add(fromB);
        trigger.body.add(forgetA);
        trigger.body.add(output);
        program.triggers.add(trigger);
        assertTrue(ProgramDiagnostics.errorMessages(program).isEmpty());

        trigger.body.clear();
        trigger.body.add(output);
        // 只有输出没有取出：SFML 能编译（运行期无事发生），按用户哲学提醒而非报错
        assertTrue(ProgramDiagnostics.warningMessages(program).stream()
                .anyMatch(message -> message.contains("没有任何「取出资源」")));
        assertTrue(ProgramDiagnostics.errorMessages(program).stream()
                .noneMatch(message -> message.contains("没有任何「取出资源」")));
    }

    @Test
    public void oneThousandGeneratedBlockProgramsCompile() {
        Random random = new Random(0x5F4D2026L);
        for (int iteration = 0; iteration < 1_000; iteration++) {
            BProgram program = randomProgram(random, iteration);
            String sfml = BlocksToSfml.toSfml(program);
            SfmlTestSupport.assertNoCompileErrors(sfml);
            assertTrue(ProgramDiagnostics.errorMessages(program).isEmpty(),
                    () -> "generated invalid model:\n" + sfml + "\n" + ProgramDiagnostics.errorMessages(program));
        }
    }

    private static BProgram randomProgram(Random random, int index) {
        BProgram program = new BProgram();
        program.name = "组合测试 " + index;
        int triggers = 1 + random.nextInt(3);
        for (int t = 0; t < triggers; t++) {
            BProgram.Trigger trigger;
            if (random.nextInt(5) == 0) {
                trigger = new BProgram.PulseTrigger();
            } else {
                BProgram.TimerTrigger timer = new BProgram.TimerTrigger();
                timer.unit = random.nextBoolean()
                        ? BProgram.TimerTrigger.Unit.TICKS
                        : BProgram.TimerTrigger.Unit.SECONDS;
                timer.count = timer.unit == BProgram.TimerTrigger.Unit.TICKS
                        ? BlocksToSfml.minimumTimerIntervalInTicks() + random.nextInt(181)
                        : 1 + random.nextInt(200);
                timer.global = random.nextBoolean();
                timer.plus = random.nextBoolean() ? random.nextInt(20) : 0;
                trigger = timer;
            }

            BProgram.Statement.Input input = new BProgram.Statement.Input();
            input.access.labels.add(random.nextBoolean() ? "source" : "来源箱");
            input.each = random.nextBoolean();
            if (random.nextBoolean()) input.access.roundRobin = BProgram.RoundRobinMode.BLOCK;
            if (random.nextBoolean()) {
                input.access.eachSide = true;
            } else if (random.nextBoolean()) {
                input.access.sides.add(BProgram.Side.values()[random.nextInt(BProgram.Side.values().length)]);
            }
            if (random.nextBoolean()) input.access.slots.add(new BProgram.SlotRange(0, random.nextInt(9)));
            input.limits.add(randomLimit(random));
            trigger.body.add(input);

            if (random.nextBoolean()) {
                BProgram.Statement.If iff = new BProgram.Statement.If();
                BProgram.Branch branch = new BProgram.Branch();
                branch.cond = randomBool(random);
                branch.body.add(randomOutput(random));
                iff.branches.add(branch);
                if (random.nextBoolean()) {
                    iff.hasElse = true;
                    iff.elseBody.add(new BProgram.Statement.Comment("条件不满足时不搬运"));
                }
                trigger.body.add(iff);
            } else {
                trigger.body.add(randomOutput(random));
            }
            program.triggers.add(trigger);
        }
        return program;
    }

    private static BProgram.ResourceLimit randomLimit(Random random) {
        BProgram.ResourceLimit limit = new BProgram.ResourceLimit();
        if (random.nextBoolean()) {
            limit.quantity = (long) (1 + random.nextInt(64));
            limit.quantityEach = random.nextBoolean();
        }
        if (random.nextBoolean()) {
            limit.retain = (long) random.nextInt(32);
            limit.retainEach = random.nextBoolean();
        }
        if (random.nextBoolean()) {
            limit.resources.add(randomResource(random));
            if (random.nextBoolean()) limit.resources.add(randomResource(random));
        }
        if (random.nextInt(4) == 0) {
            BProgram.WithFilter with = new BProgram.WithFilter();
            with.mode = random.nextBoolean() ? BProgram.WithFilter.Mode.WITH : BProgram.WithFilter.Mode.WITHOUT;
            with.expr = randomWithExpr(random, 0);
            limit.with = with;
        }
        return limit;
    }

    private static BProgram.Statement.Output randomOutput(Random random) {
        BProgram.Statement.Output output = new BProgram.Statement.Output();
        output.access.labels.add(random.nextBoolean() ? "target" : "目标箱");
        output.each = random.nextBoolean();
        output.emptySlots = random.nextBoolean();
        if (random.nextBoolean()) output.access.roundRobin = BProgram.RoundRobinMode.BLOCK;
        if (random.nextBoolean()) output.except.add(randomResource(random));
        if (random.nextBoolean()) output.access.slots.add(new BProgram.SlotRange(0, random.nextInt(9)));
        output.limits.add(randomLimit(random));
        return output;
    }

    private static BProgram.Bool randomBool(Random random) {
        return randomBool(random, 0);
    }

    private static BProgram.Bool randomBool(Random random, int depth) {
        if (depth < 2) {
            int compound = random.nextInt(7);
            if (compound == 0) {
                BProgram.Bool.Not not = new BProgram.Bool.Not();
                not.inner = randomBool(random, depth + 1);
                return not;
            }
            if (compound == 1 || compound == 2) {
                BProgram.Bool.And and = new BProgram.Bool.And();
                and.parts.add(randomBool(random, depth + 1));
                and.parts.add(randomBool(random, depth + 1));
                return and;
            }
            if (compound == 3) {
                BProgram.Bool.Or or = new BProgram.Bool.Or();
                or.parts.add(randomBool(random, depth + 1));
                or.parts.add(randomBool(random, depth + 1));
                return or;
            }
        }
        if (random.nextInt(5) == 0) return new BProgram.Bool.Const(random.nextBoolean());
        if (random.nextInt(4) == 0) {
            BProgram.Bool.Redstone redstone = new BProgram.Bool.Redstone();
            if (random.nextBoolean()) {
                redstone.comparison = BProgram.Bool.Comparison.values()[random.nextInt(BProgram.Bool.Comparison.values().length)];
                redstone.number = random.nextInt(16);
            }
            return redstone;
        }
        BProgram.Bool.Has has = new BProgram.Bool.Has();
        has.access.labels.add("source");
        if (random.nextBoolean()) has.access.labels.add("source2");
        has.setMode = BProgram.Bool.SetMode.values()[random.nextInt(BProgram.Bool.SetMode.values().length)];
        has.comparison = BProgram.Bool.Comparison.values()[random.nextInt(BProgram.Bool.Comparison.values().length)];
        has.number = random.nextInt(128);
        if (random.nextBoolean()) has.resources.add(randomResource(random));
        if (random.nextBoolean()) has.access.slots.add(new BProgram.SlotRange(0, random.nextInt(5)));
        if (random.nextBoolean()) has.access.roundRobin = BProgram.RoundRobinMode.LABEL;
        if (random.nextBoolean()) {
            has.access.eachSide = true;
        } else if (random.nextBoolean()) {
            has.access.sides.add(BProgram.Side.NORTH);
        }
        if (random.nextInt(5) == 0) {
            BProgram.WithFilter with = new BProgram.WithFilter();
            with.expr = randomWithExpr(random, 0);
            has.with = with;
            has.except.add(randomResource(random));
        }
        return has;
    }

    private static BProgram.ResourceRef randomResource(Random random) {
        return BProgram.ResourceRef.parse(switch (random.nextInt(12)) {
            case 0 -> "minecraft:iron_ingot";
            case 1 -> "item::";
            case 2 -> "input"; // keyword: serializer must quote it
            case 3 -> "fluid:minecraft:water";
            case 4 -> "fluid::";
            case 5 -> "chemical::oxygen";
            case 6 -> "gas::hydrogen";
            case 7 -> "slurry::dirty_iron";
            case 8 -> "pigment::blue";
            case 9 -> "redstone::";
            case 10 -> "infusion::carbon";
            default -> "forge_energy::";
        });
    }

    private static BProgram.WithExpr randomWithExpr(Random random, int depth) {
        if (depth >= 2 || random.nextInt(3) == 0) {
            return new BProgram.WithExpr.Tag(random.nextBoolean() ? "forge:ingots" : "minecraft:logs");
        }
        return switch (random.nextInt(3)) {
            case 0 -> {
                BProgram.WithExpr.Not not = new BProgram.WithExpr.Not();
                not.inner = randomWithExpr(random, depth + 1);
                yield not;
            }
            case 1 -> {
                BProgram.WithExpr.And and = new BProgram.WithExpr.And();
                and.parts.add(randomWithExpr(random, depth + 1));
                and.parts.add(randomWithExpr(random, depth + 1));
                yield and;
            }
            default -> {
                BProgram.WithExpr.Or or = new BProgram.WithExpr.Or();
                or.parts.add(randomWithExpr(random, depth + 1));
                or.parts.add(randomWithExpr(random, depth + 1));
                yield or;
            }
        };
    }
}
