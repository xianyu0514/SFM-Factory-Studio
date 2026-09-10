package io.github.xianynomial.sfmfactorystudio.test;

import io.github.xianynomial.sfmfactorystudio.client.blocks.model.BProgram;
import io.github.xianynomial.sfmfactorystudio.client.blocks.model.BlocksToSfml;
import io.github.xianynomial.sfmfactorystudio.client.blocks.model.SfmlToBlocks;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 用户反馈（2026-09-10）：资源类别要在主判断行可见，默认句 = 「当 仓库 中有 >= 1 物品 时」。
 * 护栏：默认条件生成的 SFML（物品通配 {@code *}）必须被真实 SFM 编译器接受，且编辑器往返无损。
 */
public class ConditionDefaultTest {

    /** 与 BlockEditorScreen.newConditionHas 的默认构造保持一致（GUI 类无法 headless 实例化）。 */
    private static BProgram.Bool.Has defaultHas() {
        BProgram.Bool.Has has = new BProgram.Bool.Has();
        has.access.labels.add("a");
        has.comparison = BProgram.Bool.Comparison.GE;
        has.number = 1;
        has.resources.add(BProgram.ResourceRef.forKind(BProgram.ResourceKind.ITEM));
        return has;
    }

    private static String programWithDefaultCondition() {
        return "NAME \"t\"\nevery 20 ticks do\n    if " + BlocksToSfml.writeBool(defaultHas())
                + " then\n        output to b\n    end\nend";
    }

    @Test
    public void defaultConditionCompilesWithRealCompiler() {
        SfmlTestSupport.assertNoCompileErrors(programWithDefaultCondition());
    }

    @Test
    public void defaultConditionRoundTrips() {
        SfmlToBlocks.Result r = SfmlToBlocks.parse(programWithDefaultCondition());
        assertTrue(r.ok(), "默认条件程序必须可解析: " + r.errors());
        BProgram.Bool cond = firstCondition(r.program());
        BProgram.Bool.Has has = assertInstanceOf(BProgram.Bool.Has.class, cond);
        assertEquals(1, has.resources.size(), "默认通配物品必须往返保留");
        assertTrue(has.resources.get(0).isWildcard(), "默认资源应为物品通配");
        assertEquals("item", has.resources.get(0).typeName);
        assertEquals(1, has.number, "默认数量 1（非空判断）");
    }

    private static BProgram.Bool firstCondition(BProgram program) {
        return program.triggers.get(0).body.stream()
                .filter(s -> s instanceof BProgram.Statement.If)
                .map(s -> (BProgram.Statement.If) s)
                .findFirst()
                .orElseThrow()
                .branches.get(0).cond;
    }
}
