package io.github.xianynomial.sfmfactorystudio.client.blocks.model;

import java.util.ArrayList;
import java.util.List;

/**
 * Pure model factories for the one-click examples shown by the editor. Keeping
 * them outside the screen makes their generated SFML and basic data flow
 * testable without starting Minecraft.
 */
public final class BlockTemplates {
    private BlockTemplates() {
    }

    /** 原料、燃料和成品分三段处理，避免上一段未送出的资源混入下一段。 */
    public static List<BProgram.Statement> smeltingLine() {
        List<BProgram.Statement> statements = new ArrayList<>();
        statements.add(input("原料箱"));
        statements.add(output("熔炉", BProgram.Side.TOP));
        statements.add(new BProgram.Statement.Forget());
        statements.add(input("燃料箱"));
        statements.add(output("熔炉", BProgram.Side.NORTH));
        statements.add(new BProgram.Statement.Forget());
        statements.add(input("熔炉", BProgram.Side.BOTTOM));
        statements.add(output("成品箱"));
        return statements;
    }

    /** 当来源中至少有一组 64 个同类物品时，搬运一组到目标箱。 */
    public static BProgram.Statement.If fullStackSort() {
        BProgram.Statement.If iff = new BProgram.Statement.If();
        BProgram.Branch branch = new BProgram.Branch();
        BProgram.Bool.Has has = new BProgram.Bool.Has();
        has.setMode = BProgram.Bool.SetMode.OVERALL;
        has.access.labels.add("原料箱");
        has.comparison = BProgram.Bool.Comparison.GE;
        has.number = 64;
        has.resources.add(BProgram.ResourceRef.forKind(BProgram.ResourceKind.ITEM));
        branch.cond = has;

        BProgram.Statement.Input input = input("原料箱");
        BProgram.ResourceLimit inputLimit = input.limits.get(0);
        inputLimit.quantity = 64L;
        inputLimit.resources.add(BProgram.ResourceRef.forKind(BProgram.ResourceKind.ITEM));
        branch.body.add(input);

        BProgram.Statement.Output output = output("目标箱");
        output.limits.get(0).quantity = 64L;
        branch.body.add(output);
        iff.branches.add(branch);
        return iff;
    }

    /** 从一个来源取出，再按标签在两个目标之间轮流分配。 */
    public static List<BProgram.Statement> balancedDistribution() {
        BProgram.Statement.Output output = output("分配箱1");
        output.access.labels.add("分配箱2");
        output.access.roundRobin = BProgram.RoundRobinMode.LABEL;
        return List.of(input("原料箱"), output);
    }

    /** 三个彼此完整的并行搬运任务；每一个单独运行也有实际效果。 */
    public static List<BProgram.Trigger> parallelTransfers() {
        List<BProgram.Trigger> triggers = new ArrayList<>();
        for (int i = 0; i < 3; i++) {
            BProgram.TimerTrigger trigger = new BProgram.TimerTrigger();
            trigger.body.add(input("来源箱"));
            trigger.body.add(output("目标箱"));
            triggers.add(trigger);
        }
        return triggers;
    }

    /** A complete, predicate-free SFM energy transfer: INPUT followed by OUTPUT. */
    public static List<BProgram.Statement> energyTransfer(String source, String target) {
        BProgram.Statement.Input input = input(source);
        input.limits.get(0).resources.add(BProgram.ResourceRef.forKind(BProgram.ResourceKind.FORGE_ENERGY));
        // 能量接口按真实方向暴露，空面查询抽不到——能量搬运固定逐面传输
        input.access.eachSide = true;
        BProgram.Statement.Output output = output(target);
        output.limits.get(0).resources.add(BProgram.ResourceRef.forKind(BProgram.ResourceKind.FORGE_ENERGY));
        output.access.eachSide = true;
        return List.of(input, output);
    }

    private static BProgram.Statement.Input input(String label, BProgram.Side... sides) {
        BProgram.Statement.Input input = new BProgram.Statement.Input();
        input.access.labels.add(label);
        input.access.sides.addAll(List.of(sides));
        input.limits.add(new BProgram.ResourceLimit());
        return input;
    }

    private static BProgram.Statement.Output output(String label, BProgram.Side... sides) {
        BProgram.Statement.Output output = new BProgram.Statement.Output();
        output.access.labels.add(label);
        output.access.sides.addAll(List.of(sides));
        output.limits.add(new BProgram.ResourceLimit());
        return output;
    }
}
