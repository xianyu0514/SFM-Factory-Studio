package io.github.xianynomial.sfmfactorystudio.test;

import io.github.xianynomial.sfmfactorystudio.client.blocks.model.BProgram;
import io.github.xianynomial.sfmfactorystudio.client.blocks.model.BlocksToSfml;
import io.github.xianynomial.sfmfactorystudio.client.blocks.model.SfmlToBlocks;
import io.github.xianynomial.sfmfactorystudio.client.blocks.model.SfmlValidate;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 标签降级回归：含 SFML 语法不支持字符（- 或 .）的标签 id 会被序列化降级成
 * #*——修复后：纯 #* 的 with 子句直接省略（通配=不过滤），生成代码必须可编译。
 */
public class WildcardWithTest {

    @Test
    public void forms() {
        List<String> e1 = SfmlValidate.check("""
                NAME "t"
                every 20 ticks do
                    input with #* from "AE箱子"
                    output to "AE箱子"
                end
                """);
        assertTrue(e1.isEmpty(), "with #* 本身可编译: " + e1);
    }

    @Test
    public void wildcardOnlyWithIsDropped() {
        String base = "NAME \"t\"\nevery 20 ticks do\ninput * from a\nend\n";
        SfmlToBlocks.Result r = SfmlToBlocks.parse(base);
        assertTrue(r.ok());
        BProgram p = r.program();
        BProgram.ResourceLimit rl = new BProgram.ResourceLimit();
        rl.with = new BProgram.WithFilter();
        rl.with.expr = new BProgram.WithExpr.Tag("*");   // 通配标签
        ((BProgram.Statement.Input) p.triggers.get(0).body.get(0)).limits.add(rl);

        String sfml = BlocksToSfml.toSfml(p);
        assertFalse(sfml.contains("#*"), "纯通配标签应省略 with 子句: " + sfml);
        assertTrue(SfmlValidate.check(sfml).isEmpty(), "省略后必须可编译: " + sfml);
    }

    @Test
    public void dashTagMixedWithValid() {
        // 含 - 的标签（会被降级成 *）与正常标签混在 and 里：降级项省略，正常项保留
        String base = "NAME \"t\"\nevery 20 ticks do\ninput * from a\nend\n";
        SfmlToBlocks.Result r = SfmlToBlocks.parse(base);
        assertTrue(r.ok());
        BProgram p = r.program();
        BProgram.ResourceLimit rl = new BProgram.ResourceLimit();
        rl.with = new BProgram.WithFilter();
        BProgram.WithExpr.And and = new BProgram.WithExpr.And();
        and.parts.add(new BProgram.WithExpr.Tag("minecraft:logs"));
        and.parts.add(new BProgram.WithExpr.Tag("c:foo-bar"));   // 不可编码
        rl.with.expr = and;
        ((BProgram.Statement.Input) p.triggers.get(0).body.get(0)).limits.add(rl);

        String sfml = BlocksToSfml.toSfml(p);
        assertFalse(sfml.contains("#*"), "降级项不应产生 #*: " + sfml);
        assertTrue(sfml.contains("#minecraft:logs"), "正常标签保留: " + sfml);
        assertTrue(SfmlValidate.check(sfml).isEmpty(), "必须可编译: " + sfml);
    }
}
