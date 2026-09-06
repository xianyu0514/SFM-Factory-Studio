package io.github.xianynomial.sfmfactorystudio.test;

import io.github.xianynomial.sfmfactorystudio.client.blocks.model.BProgram;
import io.github.xianynomial.sfmfactorystudio.client.blocks.model.BlocksToSfml;
import io.github.xianynomial.sfmfactorystudio.client.blocks.model.ProgramDiagnostics;
import io.github.xianynomial.sfmfactorystudio.client.blocks.model.SfmlToBlocks;
import io.github.xianynomial.sfmfactorystudio.client.blocks.model.SfmlValidate;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 复现玩家报告的整条保存链：UI 增量添加标签（含 且/或 组合）→ 模型诊断 →
 * 序列化 → SFM 真编译器。屏幕生命周期（init 重跑导致旧代码抢权威）无法在
 * 单测中复现，由 BlockEditorScreen.init 的回显分支 + blocksNewerThanCode
 * 标志修复；这里守住模型/序列化层不回归。
 */
public class AndOrSaveReproTest {

    /** 复刻 BlockEditorScreen.appendWithTag 的语义（UI 增量添加标签）。 */
    private static BProgram.WithExpr appendWithTag(BProgram.WithExpr current, String matcher, boolean useOr) {
        BProgram.WithExpr.Tag added = new BProgram.WithExpr.Tag(matcher);
        if (useOr && current instanceof BProgram.WithExpr.Or or) {
            or.parts.add(added);
            return or;
        }
        if (!useOr && current instanceof BProgram.WithExpr.And and) {
            and.parts.add(added);
            return and;
        }
        if (useOr) {
            BProgram.WithExpr.Or or = new BProgram.WithExpr.Or();
            or.parts.add(current);
            or.parts.add(added);
            return or;
        }
        BProgram.WithExpr.And and = new BProgram.WithExpr.And();
        and.parts.add(current);
        and.parts.add(added);
        return and;
    }

    /** 构造与 UI 相同的模型：every 20 ticks / input * with … from a。 */
    private static BProgram programWith(BProgram.WithExpr expr) {
        String base = "NAME \"t\"\nevery 20 ticks do\ninput * from a\nend\n";
        SfmlToBlocks.Result r = SfmlToBlocks.parse(base);
        assertTrue(r.ok(), "基础程序必须可解析: " + r.errors());
        BProgram p = r.program();
        BProgram.ResourceLimit limit = new BProgram.ResourceLimit();
        limit.with = new BProgram.WithFilter();
        limit.with.expr = expr;
        for (BProgram.Statement s : p.triggers.get(0).body) {
            if (s instanceof BProgram.Statement.Input in) in.limits.add(limit);
        }
        return p;
    }

    private static void assertSaves(String label, BProgram.WithExpr expr) {
        List<String> modelErrors = ProgramDiagnostics.errorMessages(programWith(expr));
        assertTrue(modelErrors.isEmpty(), label + " 模型诊断不应报错: " + modelErrors);

        String sfml = BlocksToSfml.toSfml(programWith(expr));
        List<String> compileErrors = SfmlValidate.check(sfml);
        assertTrue(compileErrors.isEmpty(), label + " 必须能通过 SFM 编译器，生成代码=[\n" + sfml + "\n] 错误=" + compileErrors);
    }

    /** UI 的幽灵行：无限制 input 渲染时会挂一颗空 ResourceLimit，with 落在它上面。 */
    private static BProgram phantomProgram(String matcher) {
        String base = "NAME \"t\"\nevery 20 ticks do\ninput from a\nend\n";
        SfmlToBlocks.Result r = SfmlToBlocks.parse(base);
        assertTrue(r.ok());
        BProgram p = r.program();
        for (BProgram.Statement s : p.triggers.get(0).body) {
            if (s instanceof BProgram.Statement.Input in) {
                BProgram.ResourceLimit phantom = new BProgram.ResourceLimit();
                BProgram.WithFilter f = new BProgram.WithFilter();
                f.expr = new BProgram.WithExpr.Tag(matcher);
                phantom.with = f;
                in.limits.add(phantom);
            }
        }
        return p;
    }

    @Test
    public void singleItemTag() {
        assertSaves("单个 minecraft:logs", new BProgram.WithExpr.Tag("minecraft:logs"));
    }

    @Test
    public void singleNbtTag() {
        assertSaves("单个 nbt:custom_name", new BProgram.WithExpr.Tag("nbt:minecraft/custom_name"));
    }

    @Test
    public void phantomLimitWithOnlyTag() {
        // 渲染期幽灵行 + 单个物品标签：序列化必须保留 with 条件
        String sfml = BlocksToSfml.toSfml(phantomProgram("minecraft:logs"));
        assertTrue(sfml.contains("with #minecraft:logs"), "幽灵行上的标签必须写入代码: " + sfml);
        assertTrue(SfmlValidate.check(sfml).isEmpty(), "必须能通过 SFM 编译器: " + sfml);
    }

    @Test
    public void nbtThenOrCategory() {
        BProgram.WithExpr expr = new BProgram.WithExpr.Tag("nbt:minecraft/custom_name");
        expr = appendWithTag(expr, "minecraft:logs", true);
        assertSaves("NBT + 或 minecraft:logs", expr);
    }

    @Test
    public void nbtThenAndCategory() {
        BProgram.WithExpr expr = new BProgram.WithExpr.Tag("nbt:minecraft/custom_name");
        expr = appendWithTag(expr, "c:ingots", false);
        assertSaves("NBT + 且 c:ingots", expr);
    }

    @Test
    public void plainTagThenOr() {
        BProgram.WithExpr expr = new BProgram.WithExpr.Tag("minecraft:logs");
        expr = appendWithTag(expr, "minecraft:planks", true);
        expr = appendWithTag(expr, "c:ingots", false);
        assertSaves("logs 或 planks 且 ingots", expr);
    }

    @Test
    public void threeLevelMix() {
        BProgram.WithExpr expr = new BProgram.WithExpr.Tag("nbt:minecraft/custom_name");
        expr = appendWithTag(expr, "minecraft:logs", true);
        expr = appendWithTag(expr, "minecraft:planks", true);
        assertSaves("NBT 或 logs 或 planks", expr);
    }

    @Test
    public void enchantDeepMatcher() {
        BProgram.WithExpr expr = new BProgram.WithExpr.Tag("minecraft:logs");
        expr = appendWithTag(expr, "nbt:minecraft/enchantments/minecraft__sharpness", true);
        assertSaves("logs 或 附魔伪标签", expr);
    }

    @Test
    public void dotMatcherIsFlaggedByDiagnostics() {
        // 带点的 matcher 语法非法：模型诊断必须拦下（而不是序列化成 #* 混过去）
        List<String> errors = ProgramDiagnostics.errorMessages(programWith(
                new BProgram.WithExpr.Tag("has.some.dot")));
        assertFalse(errors.isEmpty(), "带点 matcher 应被诊断拦截");
    }
}
