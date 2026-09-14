package io.github.xianynomial.sfmfactorystudio.client.blocks.model;

import java.util.List;

/**
 * 完整示例工厂（2026-09-09 反馈批）：每个示例 = 一个可直接保存运行的程序，
 * 带注释逐行讲解。供空画布引导卡与帮助页「载入示例」共用（单一事实源）。
 *
 * <p>国际化（2026-09-10）：标题/说明/程序名/注释/示例标签全部按构建参数
 * {@code en} 双语生成——示例是脚手架内容（标签要参与程序匹配、注释会随
 * 程序一起保存），必须跟随游戏语言生成，而不是把一种语言的文字塞给所有人。
 * 无参重载按当前游戏语言自动选择；测试传显式参数以验证两种语言的 SFML
 * 都能通过真编译器。
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

    /** 按当前游戏语言选择（非 zh 前缀一律英文）。headless 测试 JVM 回退中文。 */
    private static boolean english() {
        try {
            return !net.minecraft.client.Minecraft.getInstance()
                    .getLanguageManager().getSelected().startsWith("zh");
        } catch (Throwable t) {
            return false;
        }
    }

    public static Example smelting() {
        return smelting(english());
    }

    /** ① 熔炉流水线：把仓库里的煤和矿石送进熔炉。教 定时/取出/放入/资源。 */
    public static Example smelting(boolean en) {
        BProgram program = base(en ? "Furnace Line" : "熔炉流水线");
        var trigger = (BProgram.TimerTrigger) program.triggers.get(0);
        trigger.body.add(comment(en
                ? "Send coal and raw iron from the warehouse into the furnace. Not moving? Click the 'default side' pill on the block and try 'every side'"
                : "把仓库里的煤炭和铁矿送进熔炉。机器不动？点积木上的「的默认面」改成「的每一面」试试"));
        trigger.body.add(input(en ? "warehouse" : "仓库", "minecraft:coal", 64L));
        trigger.body.add(output(en ? "furnace" : "熔炉", 64L, "minecraft:coal"));
        trigger.body.add(input(en ? "warehouse" : "仓库", "minecraft:raw_iron", 64L));
        trigger.body.add(output(en ? "furnace" : "熔炉", 64L, "minecraft:raw_iron"));
        return new Example("ex_smelt",
                en ? "Furnace Line" : "熔炉流水线",
                en ? "Timed refuel: warehouse → furnace" : "定时补料：仓库 → 熔炉",
                program);
    }

    public static Example energy() {
        return energy(english());
    }

    /** ② 能量自动供电：储能单元 → 机器。教 能量类资源与「自动每一面」。 */
    public static Example energy(boolean en) {
        BProgram program = base(en ? "Auto Power" : "自动供电");
        var trigger = (BProgram.TimerTrigger) program.triggers.get(0);
        trigger.body.add(comment(en
                ? "Feed energy storage into the machine. Energy resources automatically use 'every side', no manual side setup needed"
                : "把储能单元的电送给机器。能量类资源会自动按「每一面」进出，不用手动指定"));
        var in = new BProgram.Statement.Input();
        energyIO(in.access, in.limits, en ? "energy cell" : "储能单元");
        trigger.body.add(in);
        var out = new BProgram.Statement.Output();
        energyIO(out.access, out.limits, en ? "machine" : "机器");
        trigger.body.add(out);
        return new Example("ex_energy",
                en ? "Auto Power" : "能量自动供电",
                en ? "Timed power: energy cell → machine" : "定时送电：储能 → 机器",
                program);
    }

    public static Example fullStack() {
        return fullStack(english());
    }

    /** ③ 整组转运：凑满 64 才发车。教 条件与按量控制。 */
    public static Example fullStack(boolean en) {
        BProgram program = base(en ? "Full Stack Transfer" : "整组转运");
        var trigger = (BProgram.TimerTrigger) program.triggers.get(0);
        trigger.body.add(comment(en
                ? "Only ship a full stack (64) at a time, so half stacks don't clog the pipeline"
                : "凑满一组（64 个）才发车，避免半组货堵住后续管道"));
        var iff = new BProgram.Statement.If();
        var branch = new BProgram.Branch();
        var has = new BProgram.Bool.Has();
        has.access.labels.add(en ? "warehouse" : "仓库");
        has.comparison = BProgram.Bool.Comparison.GE;
        has.number = 64;
        has.resources.add(BProgram.ResourceRef.parse("minecraft:iron_ingot"));
        branch.cond = has;
        branch.body.add(input(en ? "warehouse" : "仓库", "minecraft:iron_ingot", 64L));
        branch.body.add(output(en ? "target chest" : "目标箱", 64L, "minecraft:iron_ingot"));
        iff.branches.add(branch);
        trigger.body.add(iff);
        return new Example("ex_fullstack",
                en ? "Full Stack Transfer" : "整组转运",
                en ? "Ship only full stacks of 64 (condition)" : "凑满 64 才发车（条件判断）",
                program);
    }

    public static Example recovery() {
        return recovery(english());
    }

    /** ④ 产物回收：资源留空 = 不挑种类全部搬走。教 通配。 */
    public static Example recovery(boolean en) {
        BProgram program = base(en ? "Product Recovery" : "产物回收");
        var trigger = (BProgram.TimerTrigger) program.triggers.get(0);
        trigger.body.add(comment(en
                ? "Move everything from the machine back to the warehouse. Empty resource slot = take all kinds"
                : "把机器里的东西全部搬回仓库。资源槽留空=不挑种类"));
        trigger.body.add(input(en ? "furnace" : "熔炉", null, null));
        trigger.body.add(output(en ? "warehouse" : "仓库", null, null));
        return new Example("ex_recovery",
                en ? "Product Recovery" : "产物回收",
                en ? "Empty resource slot = take everything" : "资源留空=全部搬走",
                program);
    }

    public static List<Example> all() {
        return all(english());
    }

    public static List<Example> all(boolean en) {
        return List.of(smelting(en), energy(en), fullStack(en), recovery(en));
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
