package io.github.xianynomial.sfmfactorystudio.client.blocks.model;

import java.util.ArrayList;
import java.util.List;

/**
 * 完整示例工厂（2026-09-09 反馈批）：每个示例 = 一个可直接保存运行的程序，
 * 带中文备注逐行讲解。供空画布引导卡与帮助页「载入示例」共用（单一事实源）。
 *
 * 语法安全红线：所有语句形态与 BlockTemplates 已被编译器测试覆盖的同源
 * （定时 IO / has 条件 / fe:: 能量 each side），ExampleProgramsTest 以
 * SFM 4.34 真编译器为零错误门槛，任何一个示例编译不过都不允许发版。
 */
public final class ExamplePrograms {
    private ExamplePrograms() {
    }

    public record Example(String id, String title, String description, BProgram program) {
    }

    /** ① 熔炉流水线：把仓库里的煤和矿石送进熔炉。教 定时/取出/放入/资源。 */
    public static Example smelting() {
        BProgram program = base("熔炉流水线");
        var trigger = (BProgram.TimerTrigger) program.triggers.get(0);
        trigger.body.add(comment("把仓库里的煤炭和铁矿送进熔炉。机器不动？点积木上的「的默认面」改成「的每一面」试试"));
        trigger.body.add(input("仓库", "minecraft:coal", 64L));
        trigger.body.add(output("熔炉", 64L, "minecraft:coal"));
        trigger.body.add(input("仓库", "minecraft:raw_iron", 64L));
        trigger.body.add(output("熔炉", 64L, "minecraft:raw_iron"));
        return new Example("ex_smelt", "熔炉流水线", "定时补料：仓库 → 熔炉", program);
    }

    /** ② 能量自动供电：储能单元 → 机器。教 能量类资源与「自动每一面」。 */
    public static Example energy() {
        BProgram program = base("自动供电");
        var trigger = (BProgram.TimerTrigger) program.triggers.get(0);
        trigger.body.add(comment("把储能单元的电送给机器。能量类资源会自动按「每一面」进出，不用手动指定"));
        var in = new BProgram.Statement.Input();
        energyIO(in.access, in.limits, "储能单元");
        trigger.body.add(in);
        var out = new BProgram.Statement.Output();
        energyIO(out.access, out.limits, "机器");
        trigger.body.add(out);
        return new Example("ex_energy", "能量自动供电", "定时送电：储能 → 机器", program);
    }

    /** ③ 整组转运：凑满 64 才发车。教 「当…时」条件与按量控制。 */
    public static Example fullStack() {
        BProgram program = base("整组转运");
        var trigger = (BProgram.TimerTrigger) program.triggers.get(0);
        trigger.body.add(comment("凑满一组（64 个）才发车，避免半组货堵住后续管道"));
        var iff = new BProgram.Statement.If();
        var branch = new BProgram.Branch();
        var has = new BProgram.Bool.Has();
        has.access.labels.add("仓库");
        has.comparison = BProgram.Bool.Comparison.GE;
        has.number = 64;
        has.resources.add(BProgram.ResourceRef.parse("minecraft:iron_ingot"));
        branch.cond = has;
        branch.body.add(input("仓库", "minecraft:iron_ingot", 64L));
        branch.body.add(output("目标箱", 64L, "minecraft:iron_ingot"));
        iff.branches.add(branch);
        trigger.body.add(iff);
        return new Example("ex_fullstack", "整组转运", "凑满 64 才发车（当…时）", program);
    }

    /** ④ 产物回收：资源留空 = 不挑种类全部搬走。教 通配。 */
    public static Example recovery() {
        BProgram program = base("产物回收");
        var trigger = (BProgram.TimerTrigger) program.triggers.get(0);
        trigger.body.add(comment("把机器里的东西全部搬回仓库。资源槽留空=不挑种类"));
        trigger.body.add(input("熔炉", null, null));
        trigger.body.add(output("仓库", null, null));
        return new Example("ex_recovery", "产物回收", "资源留空=全部搬走", program);
    }

    public static List<Example> all() {
        return List.of(smelting(), energy(), fullStack(), recovery());
    }

    /** 按示例 id 载入；返回 null 表示未知 id（调用方自行提示）。 */
    public static BProgram byId(String id) {
        for (Example e : all()) {
            if (e.id().equals(id)) return e.program();
        }
        return null;
    }

    // ---- 构造助手 ----------------------------------------------------------

    private static BProgram base(String name) {
        BProgram program = new BProgram();
        program.name = name;
        var trigger = new BProgram.TimerTrigger();
        trigger.count = 20;
        trigger.unit = BProgram.TimerTrigger.Unit.TICKS;
        program.triggers.add(trigger);
        return program;
    }

    private static BProgram.Statement.Comment comment(String text) {
        return new BProgram.Statement.Comment(text);
    }

    private static BProgram.Statement.Input input(String label, String itemId, Long quantity) {
        var input = new BProgram.Statement.Input();
        input.access.labels.add(label);
        var rl = new BProgram.ResourceLimit();
        if (itemId != null) rl.resources.add(BProgram.ResourceRef.parse(itemId));
        rl.quantity = quantity;
        input.limits.add(rl);
        return input;
    }

    private static BProgram.Statement.Output output(String label, Long quantity, String itemId) {
        var output = new BProgram.Statement.Output();
        output.access.labels.add(label);
        var rl = new BProgram.ResourceLimit();
        if (itemId != null) rl.resources.add(BProgram.ResourceRef.parse(itemId));
        rl.quantity = quantity;
        output.limits.add(rl);
        return output;
    }

    private static void energyIO(BProgram.LabelAccess access, List<BProgram.ResourceLimit> limits, String label) {
        access.labels.add(label);
        access.eachSide = true;
        var rl = new BProgram.ResourceLimit();
        rl.resources.add(BProgram.ResourceRef.parse("fe::"));
        limits.add(rl);
    }
}
