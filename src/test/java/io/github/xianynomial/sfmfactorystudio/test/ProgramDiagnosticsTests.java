package io.github.xianynomial.sfmfactorystudio.test;

import io.github.xianynomial.sfmfactorystudio.client.blocks.model.BProgram;
import io.github.xianynomial.sfmfactorystudio.client.blocks.model.ProgramDiagnostics;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Block-level semantic diagnostics: every new rule must point at the offending
 * block, explain itself in Chinese, and (where offered) its quick fix must
 * actually clear the issue. These all compile fine in SFM — they are silent
 * runtime no-ops, which is exactly why the editor must catch them.
 */
public class ProgramDiagnosticsTests {

    private static BProgram.TimerTrigger timerWith(BProgram.Statement... statements) {
        BProgram.TimerTrigger timer = new BProgram.TimerTrigger();
        timer.count = 20;
        for (BProgram.Statement s : statements) timer.body.add(s);
        BProgram program = new BProgram();
        program.triggers.add(timer);
        return timer;
    }

    private static BProgram programOf(BProgram.TimerTrigger timer) {
        BProgram program = new BProgram();
        program.triggers.add(timer);
        return program;
    }

    private static BProgram.Statement.Input input(String label, long quantity, String... sfmlIds) {
        BProgram.Statement.Input input = new BProgram.Statement.Input();
        input.access.labels.add(label);
        BProgram.ResourceLimit limit = new BProgram.ResourceLimit();
        limit.quantity = quantity;
        for (String id : sfmlIds) limit.resources.add(BProgram.ResourceRef.parse(id));
        input.limits.add(limit);
        return input;
    }

    private static BProgram.Statement.Output output(String label) {
        BProgram.Statement.Output output = new BProgram.Statement.Output();
        output.access.labels.add(label);
        return output;
    }

    private static BProgram.Bool.Has has(String label) {
        BProgram.Bool.Has has = new BProgram.Bool.Has();
        has.access.labels.add(label);
        return has;
    }

    private static ProgramDiagnostics.Context context(
            Map<String, Integer> labelCounts, Map<String, Set<BProgram.ResourceKind>> kinds) {
        return new ProgramDiagnostics.Context(labelCounts, (ns, name) -> {
            if (ns != null) {
                Set<BProgram.ResourceKind> full = kinds.getOrDefault(ns + ":" + name, Set.of());
                if (!full.isEmpty()) return full;
            }
            return kinds.getOrDefault(":" + name, Set.of());
        });
    }

    private static List<ProgramDiagnostics.Issue> warnings(BProgram program, ProgramDiagnostics.Context ctx) {
        return ProgramDiagnostics.check(program, ctx).stream()
                .filter(i -> i.severity() == ProgramDiagnostics.Severity.WARNING).toList();
    }

    private static boolean hasMessage(List<ProgramDiagnostics.Issue> issues, String fragment) {
        return issues.stream().anyMatch(i -> i.message().contains(fragment));
    }

    // ------------------------------------------------------------ 未绑定标签

    @Test
    public void unboundLabelWarnsOnlyWithServerCounts() {
        BProgram.TimerTrigger timer = timerWith(input("ghost", 10, "stone"), output("chest"));
        BProgram program = programOf(timer);

        assertTrue(warnings(program, ProgramDiagnostics.Context.EMPTY).stream()
                .noneMatch(i -> i.message().contains("还没有绑定方块")));

        List<ProgramDiagnostics.Issue> issues = warnings(program,
                context(Map.of("ghost", 0, "chest", 2), Map.of()));
        assertTrue(hasMessage(issues, "标签「ghost」还没有绑定方块"));
        assertFalse(hasMessage(issues, "标签「chest」还没有绑定方块"));
    }

    @Test
    public void unboundLabelShowsOneEntryPerLabelRegardlessOfUsageCount() {
        // 同一标签被取出、存入、条件各引用一次：仍然只出一条提醒（2026-09-02 用户拍板）
        BProgram.Bool.Has cond = has("ghost");
        BProgram.Statement.If iff = new BProgram.Statement.If();
        iff.branches.add(new BProgram.Branch());
        iff.branches.get(0).cond = cond;
        iff.branches.get(0).body.add(output("dst"));
        BProgram.TimerTrigger timer = timerWith(input("ghost", 10, "stone"), output("ghost"), iff);
        BProgram program = programOf(timer);

        List<ProgramDiagnostics.Issue> issues = warnings(program,
                context(Map.of("ghost", 0), Map.of()));
        assertEquals(1, issues.stream().filter(i -> i.message().contains("「ghost」")).count(),
                "同一标签只出一条未绑定提醒: " + issues);
    }

    // ------------------------------------------------------------ 同标签进出 = 合法用法

    @Test
    public void sameLabelForInputAndOutputNeverWarns() {
        // 用户拍板（2026-09-02）：取出和存入使用同一标签（分拣、重新分配的常见写法）
        // 完全合法，不产生任何提醒。
        BProgram.Statement.Input input = input("store", 10, "stone");
        BProgram.Statement.Output output = output("store");
        List<ProgramDiagnostics.Issue> issues = warnings(
                programOf(timerWith(input, output)), ProgramDiagnostics.Context.EMPTY);
        assertTrue(issues.isEmpty(), "同标签进出不应产生任何提醒: " + issues);
    }

    // ------------------------------------------------------------ 类别与 ID 不符

    @Test
    public void mismatchedResourceKindWarnsAndFixCorrectsIt() {
        BProgram.Statement.Input input = input("src", 10, "minecraft:water"); // parsed as an item id
        BProgram.TimerTrigger timer = timerWith(input, output("dst"));
        BProgram program = programOf(timer);

        List<ProgramDiagnostics.Issue> issues = warnings(program,
                context(Map.of(), Map.of(":water", Set.of(BProgram.ResourceKind.FLUID))));
        assertTrue(hasMessage(issues, "不是物品，而是流体"));

        ProgramDiagnostics.Issue issue = issues.stream()
                .filter(i -> i.message().contains("不是物品，而是流体")).findFirst().orElseThrow();
        assertNotNull(issue.fix(), "类别不符应提供一键修复");
        issue.fix().run();
        assertEquals(BProgram.ResourceKind.FLUID, input.limits.get(0).resources.get(0).kind());
        assertTrue(warnings(program, ProgramDiagnostics.Context.EMPTY).stream()
                .noneMatch(i -> i.message().contains("不是物品")));
    }

    @Test
    public void unknownResourceIdWarnsWhenOracleKnowsNothing() {
        List<ProgramDiagnostics.Issue> issues = warnings(
                programOf(timerWith(input("src", 10, "minecraft:quantum_frood"), output("dst"))),
                context(Map.of(), Map.of()));
        assertTrue(hasMessage(issues, "quantum_frood"));
    }

    @Test
    public void matchingKindDoesNotWarn() {
        List<ProgramDiagnostics.Issue> issues = warnings(
                programOf(timerWith(input("src", 10, "fluid:minecraft:water"), output("dst"))),
                context(Map.of(), Map.of(":water", Set.of(BProgram.ResourceKind.FLUID))));
        assertFalse(hasMessage(issues, "不是流体"));
    }

    // ------------------------------------------------------------ 排除全覆盖

    @Test
    public void exceptCoveringAllResourcesWarnsAndFixClearsIt() {
        BProgram.Statement.Input input = input("src", 10, "stone");
        input.except.add(BProgram.ResourceRef.parse("stone"));
        BProgram.TimerTrigger timer = timerWith(input, output("dst"));
        BProgram program = programOf(timer);

        List<ProgramDiagnostics.Issue> issues = warnings(program, ProgramDiagnostics.Context.EMPTY);
        assertTrue(hasMessage(issues, "全部排除"));
        issues.stream().filter(i -> i.message().contains("全部排除")).findFirst().orElseThrow()
                .fix().run();
        assertTrue(input.except.isEmpty());
        assertFalse(hasMessage(warnings(program, ProgramDiagnostics.Context.EMPTY), "全部排除"));
    }

    @Test
    public void wildcardExceptWarnsAndFixRemovesIt() {
        BProgram.Statement.Input input = input("src", 10, "stone");
        input.except.add(BProgram.ResourceRef.parse("*"));
        BProgram.TimerTrigger timer = timerWith(input, output("dst"));
        BProgram program = programOf(timer);

        List<ProgramDiagnostics.Issue> issues = warnings(program, ProgramDiagnostics.Context.EMPTY);
        assertTrue(hasMessage(issues, "包含「全部资源」"));
        issues.stream().filter(i -> i.message().contains("包含「全部资源」")).findFirst().orElseThrow()
                .fix().run();
        assertTrue(input.except.isEmpty());
    }

    @Test
    public void partialExceptDoesNotWarn() {
        BProgram.Statement.Input input = input("src", 10, "stone", "dirt");
        input.except.add(BProgram.ResourceRef.parse("dirt"));
        assertFalse(hasMessage(warnings(programOf(timerWith(input, output("dst"))),
                ProgramDiagnostics.Context.EMPTY), "全部排除"));
    }

    // ------------------------------------------------------------ 恒假/恒真条件

    @Test
    public void andWithNegatedSelfNeverHolds() {
        BProgram.Bool.Has cond = has("src");
        BProgram.Bool.Not negated = new BProgram.Bool.Not();
        negated.inner = cond.copy();
        BProgram.Bool.And and = new BProgram.Bool.And();
        and.parts.add(cond);
        and.parts.add(negated);
        BProgram.Statement.If iff = new BProgram.Statement.If();
        iff.branches.add(new BProgram.Branch());
        iff.branches.get(0).cond = and;
        iff.branches.get(0).body.add(output("dst"));

        List<ProgramDiagnostics.Issue> issues = warnings(programOf(timerWith(iff)),
                ProgramDiagnostics.Context.EMPTY);
        assertTrue(hasMessage(issues, "永远不成立"));
        issues.stream().filter(i -> i.message().contains("永远不成立")).findFirst().orElseThrow()
                .fix().run();
        assertEquals(1, and.parts.size());
        assertFalse(hasMessage(warnings(programOf(timerWith(iff)), ProgramDiagnostics.Context.EMPTY), "永远不成立"));
    }

    @Test
    public void orWithNegatedSelfIsAlwaysTrue() {
        BProgram.Bool.Has cond = has("src");
        BProgram.Bool.Not negated = new BProgram.Bool.Not();
        negated.inner = cond.copy();
        BProgram.Bool.Or or = new BProgram.Bool.Or();
        or.parts.add(cond);
        or.parts.add(negated);
        BProgram.Statement.If iff = new BProgram.Statement.If();
        iff.branches.add(new BProgram.Branch());
        iff.branches.get(0).cond = or;
        iff.branches.get(0).body.add(output("dst"));

        List<ProgramDiagnostics.Issue> issues = warnings(programOf(timerWith(iff)),
                ProgramDiagnostics.Context.EMPTY);
        assertTrue(hasMessage(issues, "永远为真"));
    }

    @Test
    public void duplicateConditionInsideAndWarns() {
        BProgram.Bool.And and = new BProgram.Bool.And();
        and.parts.add(has("src"));
        and.parts.add(has("src"));
        BProgram.Statement.If iff = new BProgram.Statement.If();
        iff.branches.add(new BProgram.Branch());
        iff.branches.get(0).cond = and;
        iff.branches.get(0).body.add(output("dst"));

        List<ProgramDiagnostics.Issue> issues = warnings(programOf(timerWith(iff)),
                ProgramDiagnostics.Context.EMPTY);
        assertTrue(hasMessage(issues, "完全相同的条件"));
    }

    @Test
    public void constantFalseBranchWarnsAndLessThanZeroNeverHolds() {
        BProgram.Statement.If iff = new BProgram.Statement.If();
        iff.branches.add(new BProgram.Branch());
        iff.branches.get(0).cond = new BProgram.Bool.Const(false);
        iff.branches.get(0).body.add(output("dst"));

        BProgram.Bool.Has lt0 = has("src");
        lt0.comparison = BProgram.Bool.Comparison.LT;
        BProgram.Statement.If iff2 = new BProgram.Statement.If();
        iff2.branches.add(new BProgram.Branch());
        iff2.branches.get(0).cond = lt0;
        iff2.branches.get(0).body.add(output("dst"));

        BProgram.TimerTrigger timer = timerWith(iff, iff2);
        List<ProgramDiagnostics.Issue> issues = warnings(programOf(timer), ProgramDiagnostics.Context.EMPTY);
        assertTrue(hasMessage(issues, "固定的「否」"));
        assertTrue(hasMessage(issues, "「少于 0」永远不会成立"));
    }

    // ------------------------------------------------------------ 取出全部 = 合法意图

    @Test
    public void takeAllInputIsLegitimateAndDoesNotWarn() {
        // 用户拍板（2026-09-02）：不带数量/资源的 input 是"取出全部"，
        // 是清空容器等场景的正当写法，不做任何提醒（官方 filtering 示例同样在用）。
        BProgram.Statement.Input input = new BProgram.Statement.Input();
        input.access.labels.add("src");
        input.limits.add(new BProgram.ResourceLimit()); // 完全空 = input from src
        List<ProgramDiagnostics.Issue> issues = warnings(
                programOf(timerWith(input, output("dst"))), ProgramDiagnostics.Context.EMPTY);
        assertTrue(issues.stream().noneMatch(i -> i.message().contains("取出全部资源")));
        assertTrue(issues.stream().noneMatch(i -> i.message().contains("全部资源")));
    }

    // ------------------------------------------------------------ 新积木不报错

    @Test
    public void freshTriggerAndFreshBlockProduceNoErrors() {
        BProgram.TimerTrigger emptyTimer = new BProgram.TimerTrigger(); // 新拖入、还没放积木
        emptyTimer.count = 20;
        BProgram program = programOf(emptyTimer);
        assertTrue(ProgramDiagnostics.errorMessages(program).isEmpty(),
                "空触发器体在 SFM 里合法（官方 timer_triggers 示例），不应是错误");
        assertTrue(ProgramDiagnostics.warningMessages(program).stream()
                .anyMatch(m -> m.contains("还没有要执行的操作")));
    }

    @Test
    public void outputOnlyTriggerIsAWarningNotAnError() {
        BProgram.Statement.Output output = output("dst");
        BProgram program = programOf(timerWith(output));
        assertTrue(ProgramDiagnostics.errorMessages(program).isEmpty());
        assertTrue(ProgramDiagnostics.warningMessages(program).stream()
                .anyMatch(m -> m.contains("没有任何「取出资源」")));
    }

    // ------------------------------------------------------------ 模糊匹配不误报

    @Test
    public void fuzzyPatternResourcesAreNeverReportedMissing() {
        // 官方 filtering 示例的写法：*ingot* 与带引号的 ".*ingot.*" 是匹配模式，
        // 不是注册表 id；即便 oracle 一无所知也绝不能报"找不到"。
        BProgram.Statement.Input fuzzy = input("src", 10, "*ingot*");
        BProgram.Statement.Input quoted = input("src", 10, "\".*ingot.*\"");
        List<ProgramDiagnostics.Issue> issues = warnings(
                programOf(timerWith(fuzzy, quoted, output("dst"))),
                context(Map.of(), Map.of()));
        assertTrue(issues.stream().noneMatch(i -> i.message().contains("找不到")),
                "模糊匹配不是具体 id，不应做存在性核对: " + issues);
    }

    // ------------------------------------------------------------ 保存门槛不回退

    @Test
    public void newSemanticChecksAreWarningsNotSaveBlockers() {
        BProgram.Statement.Input input = new BProgram.Statement.Input();
        input.access.labels.add("src");
        input.limits.add(new BProgram.ResourceLimit());
        BProgram program = programOf(timerWith(input, output("src")));

        assertTrue(ProgramDiagnostics.errorMessages(program).isEmpty(),
                "新语义检查只提醒，不阻止保存");
    }

    // ------------------------------------------------------------ 定位引用

    @Test
    public void issuesPointAtTheOffendingBlock() {
        BProgram.Statement.Input input = input("src", 10, "minecraft:water");
        BProgram.Statement.Output output = output("dst");
        BProgram.TimerTrigger timer = timerWith(input, output);
        BProgram program = programOf(timer);

        ProgramDiagnostics.Issue issue = warnings(program,
                        context(Map.of(), Map.of(":water", Set.of(BProgram.ResourceKind.FLUID))))
                .stream().filter(i -> i.message().contains("不是物品")).findFirst().orElseThrow();
        assertEquals(input, issue.block(), "问题应指向出问题的积木，供编辑器高亮定位");
    }

    // ------------------------------------------------------------ 不误报

    @Test
    public void differentWithFiltersAreNotDuplicates() {
        // 两个条件访问同一标签，但 with 前缀不同：不是重复，更不是矛盾
        BProgram.Bool.Has first = has("src");
        first.with = new BProgram.WithFilter();
        first.with.expr = new BProgram.WithExpr.Tag("forge:ingots");

        BProgram.Bool.Has second = has("src");
        second.with = new BProgram.WithFilter();
        second.with.expr = new BProgram.WithExpr.Tag("forge:ores");

        BProgram.Bool.And and = new BProgram.Bool.And();
        and.parts.add(first);
        and.parts.add(second);
        BProgram.Statement.If iff = new BProgram.Statement.If();
        iff.branches.add(new BProgram.Branch());
        iff.branches.get(0).cond = and;
        iff.branches.get(0).body.add(output("dst"));

        List<ProgramDiagnostics.Issue> issues = warnings(programOf(timerWith(iff)),
                ProgramDiagnostics.Context.EMPTY);
        assertFalse(hasMessage(issues, "完全相同的条件"));
        assertFalse(hasMessage(issues, "永远不成立"));
    }

    @Test
    public void officialTemplatesProduceNoBlockingErrors() throws Exception {
        // 保存门槛回归护栏：任何官方示例导入积木编辑器后都不能产生 ERROR
        try (var stream = java.nio.file.Files.list(
                java.nio.file.Paths.get("src", "test", "resources", "sfm_templates"))) {
            var templates = stream.filter(p -> p.toString().endsWith(".sfml")).sorted().toList();
            org.junit.jupiter.api.Assumptions.assumeTrue(!templates.isEmpty(), "无官方模板资源");
            for (var path : templates) {
                String name = path.getFileName().toString();
                if (List.of("changelog", "known_issues", "resource_types").stream().anyMatch(name::startsWith)) {
                    continue;
                }
                String sfml = java.nio.file.Files.readString(path);
                var result = io.github.xianynomial.sfmfactorystudio.client.blocks.model.SfmlToBlocks.parse(sfml);
                org.junit.jupiter.api.Assumptions.assumeTrue(result.ok(), name + " 无法解析，跳过");
                // 回归护栏：任何官方示例导入积木编辑器后都不能产生 ERROR
                // （空触发器体等"能编译"的形态已是 WARNING，不再需要豁免）
                var errors = ProgramDiagnostics.errorMessages(result.program());
                assertTrue(errors.isEmpty(), name + " 不应产生阻断保存的错误: " + errors);
            }
        }
    }
}
