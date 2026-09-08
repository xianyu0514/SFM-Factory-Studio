package io.github.xianynomial.sfmfactorystudio.test;

import io.github.xianynomial.sfmfactorystudio.client.blocks.model.BProgram;
import io.github.xianynomial.sfmfactorystudio.client.blocks.model.BlocksToSfml;
import io.github.xianynomial.sfmfactorystudio.client.blocks.model.ExamplePrograms;
import io.github.xianynomial.sfmfactorystudio.client.blocks.model.SfmlToBlocks;
import io.github.xianynomial.sfmfactorystudio.client.blocks.model.SfmlValidate;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 示例工厂上线门槛（2026-09-09 反馈批）：每个示例必须
 * ① 通过 SFM 4.34 真编译器零错误；② 往返后语句与中文备注逐条保留。
 * 任何一个示例编译不过 = 不允许发版。
 */
public class ExampleProgramsTest {

    private static int countStatements(List<BProgram.Statement> body) {
        int n = body.size();
        for (BProgram.Statement s : body) {
            if (s instanceof BProgram.Statement.If iff) {
                for (BProgram.Branch b : iff.branches) n += countStatements(b.body);
                n += countStatements(iff.elseBody);
            }
        }
        return n;
    }

    private void assertExampleCompiles(String id) {
        ExamplePrograms.Example example = ExamplePrograms.all().stream()
                .filter(e -> e.id().equals(id)).findFirst().orElseThrow();
        String sfml = BlocksToSfml.toSfml(example.program());
        List<String> errors = SfmlValidate.check(sfml);
        assertTrue(errors.isEmpty(), "示例 " + id + " 必须通过 SFM 编译器: " + errors + "\n" + sfml);
    }

    private void assertRoundTripKeepsStatements(String id) {
        ExamplePrograms.Example example = ExamplePrograms.all().stream()
                .filter(e -> e.id().equals(id)).findFirst().orElseThrow();
        BProgram original = example.program();
        int before = countStatements(original.triggers.get(0).body);
        String sfml = BlocksToSfml.toSfml(original);
        SfmlToBlocks.Result back = SfmlToBlocks.parse(sfml);
        assertTrue(back.errors().isEmpty(), back.errors().toString());
        assertEquals(1, back.program().triggers.size());
        int after = countStatements(back.program().triggers.get(0).body);
        assertEquals(before, after, "往返后语句数必须一致（含备注）");
    }

    @Test
    public void smeltingExample_compilesAndRoundTrips() {
        assertExampleCompiles("ex_smelt");
        assertRoundTripKeepsStatements("ex_smelt");
    }

    @Test
    public void energyExample_compilesAndRoundTrips() {
        assertExampleCompiles("ex_energy");
        assertRoundTripKeepsStatements("ex_energy");
        // 能量示例必须自动携带 each side（默认面=空面的语义坑）
        ExamplePrograms.Example e = ExamplePrograms.all().stream()
                .filter(x -> x.id().equals("ex_energy")).findFirst().orElseThrow();
        assertTrue(BlocksToSfml.toSfml(e.program()).contains("each side"));
    }

    @Test
    public void fullStackExample_compilesAndRoundTrips() {
        assertExampleCompiles("ex_fullstack");
        assertRoundTripKeepsStatements("ex_fullstack");
        // 「当…时」示例的条件必须是 has 数量比较
        ExamplePrograms.Example e = ExamplePrograms.all().stream()
                .filter(x -> x.id().equals("ex_fullstack")).findFirst().orElseThrow();
        assertTrue(BlocksToSfml.toSfml(e.program()).contains("has >= 64"));
    }

    @Test
    public void recoveryExample_compilesAndRoundTrips() {
        assertExampleCompiles("ex_recovery");
        assertRoundTripKeepsStatements("ex_recovery");
    }

    @Test
    public void everyExampleIsReachableById() {
        List<String> ids = new ArrayList<>(ExamplePrograms.all().stream().map(ExamplePrograms.Example::id).toList());
        assertEquals(List.of("ex_smelt", "ex_energy", "ex_fullstack", "ex_recovery"), ids);
        for (String id : ids) {
            assertTrue(ExamplePrograms.byId(id) != null);
        }
        assertTrue(ExamplePrograms.byId("nope") == null);
    }
}
