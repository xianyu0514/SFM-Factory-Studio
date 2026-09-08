package io.github.xianynomial.sfmfactorystudio.test;

import io.github.xianynomial.sfmfactorystudio.client.blocks.model.BProgram;
import io.github.xianynomial.sfmfactorystudio.client.blocks.model.BlocksToSfml;
import io.github.xianynomial.sfmfactorystudio.client.blocks.model.SfmlToBlocks;
import io.github.xianynomial.sfmfactorystudio.client.blocks.model.SfmlValidate;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 物品资源多选（2026-09-08 反馈批）的语法护栏：多选写入的资源组 =
 * 「和」备选链，SFML 用 or 连接——必须能过真编译器，且积木↔代码往返无损。
 * 同时锁住主行侧面芯片的能量自动语义（energyOnly 行未指定侧面时自动 each side）。
 */
public class MultiResourcePickTest {

    private BProgram programWithResources(List<String> sfmlIds) {
        BProgram program = new BProgram();
        program.name = "t";
        var trigger = new BProgram.TimerTrigger();
        var input = new BProgram.Statement.Input();
        var rl = new BProgram.ResourceLimit();
        for (String id : sfmlIds) {
            rl.resources.add(BProgram.ResourceRef.parse(id));
        }
        input.limits.add(rl);
        input.access.labels.add("storage");
        trigger.body.add(input);
        program.triggers.add(trigger);
        return program;
    }

    @Test
    public void multiResources_joinWithOr_andCompiles() {
        BProgram program = programWithResources(List.of("minecraft:iron_ingot", "minecraft:gold_ingot", "minecraft:copper_ingot"));
        String sfml = BlocksToSfml.toSfml(program);
        assertTrue(sfml.contains("minecraft:iron_ingot or minecraft:gold_ingot or minecraft:copper_ingot"),
                "多选资源必须以 or 连接（=「和」备选链）：" + sfml);
        List<String> errors = SfmlValidate.check(sfml);
        assertTrue(errors.isEmpty(), "多选资源程序必须通过 SFM 编译器: " + errors + "\n" + sfml);
    }

    @Test
    public void multiResources_roundTripPreservesAll() {
        BProgram program = programWithResources(List.of("minecraft:iron_ingot", "minecraft:gold_ingot"));
        String sfml = BlocksToSfml.toSfml(program);
        SfmlToBlocks.Result back = SfmlToBlocks.parse(sfml);
        assertTrue(back.errors().isEmpty(), "往返解析不得报错: " + back.errors());
        assertEquals(1, back.program().triggers.size());
        var body = back.program().triggers.get(0).body;
        var input = (BProgram.Statement.Input) body.get(0);
        assertEquals(2, input.limits.get(0).resources.size(), "往返后两个备选资源都还在");
    }

    @Test
    public void singlePick_staysPlainOr() {
        BProgram program = programWithResources(List.of("minecraft:iron_ingot"));
        String sfml = BlocksToSfml.toSfml(program);
        assertTrue(sfml.contains("minecraft:iron_ingot"), sfml);
        assertTrue(SfmlValidate.check(sfml).isEmpty(), sfml);
    }

    @Test
    public void energyRow_sideChipAutoState_matchesCodegen() {
        // 能量行未指定侧面：芯片显示「自动每一面」，代码必须真的自动补 each side
        BProgram program = new BProgram();
        program.name = "e";
        var trigger = new BProgram.TimerTrigger();
        var input = new BProgram.Statement.Input();
        var rl = new BProgram.ResourceLimit();
        rl.resources.add(BProgram.ResourceRef.parse("fe::"));
        input.limits.add(rl);
        input.access.labels.add("cell");
        trigger.body.add(input);
        program.triggers.add(trigger);
        String sfml = BlocksToSfml.toSfml(program);
        assertTrue(BlocksToSfml.energyOnly(input.limits), "纯能量判定必须为真（芯片「自动每一面」的依据）");
        assertTrue(sfml.contains("each side"), "能量行未指定侧面时代码必须自动补 each side：" + sfml);
        assertTrue(SfmlValidate.check(sfml).isEmpty(), sfml);
    }
}
