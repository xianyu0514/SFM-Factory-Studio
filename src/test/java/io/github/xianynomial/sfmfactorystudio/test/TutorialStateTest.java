package io.github.xianynomial.sfmfactorystudio.test;

import io.github.xianynomial.sfmfactorystudio.client.blocks.model.BProgram;
import io.github.xianynomial.sfmfactorystudio.client.blocks.tutorial.TutorialState;
import io.github.xianynomial.sfmfactorystudio.client.blocks.tutorial.TutorialStep;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 引导判定与进度的单测。
 *
 * <p>重点锁死两件容易写错的事：
 * <ol>
 *   <li><b>占位标签不等于"已选好"</b>——新卡默认带 {@code "a"}/{@code "b"}，
 *       若判定写成"labels 非空"，第 2、3 屏会被瞬间跳过；</li>
 *   <li><b>进度只能单调前进</b>——玩家加完又撤销，引导不该退回第一屏。</li>
 * </ol>
 */
public class TutorialStateTest {

    // ---------------------------------------------------------------- 构造工具

    /** 与 BlockEditorScreen.newTriggerCard("timer") 等价：一张卡 + 默认标签的取出/放入。 */
    private static BProgram.TimerTrigger addTimerCard(BProgram p) {
        BProgram.TimerTrigger t = new BProgram.TimerTrigger();
        // 新卡不再预填占位标签（2026-10-24 起 newInput()/newOutput() 就是空标签），
        // 测试这里同步成空，才能真实模拟「刚拖进来的卡」。
        BProgram.Statement.Input in = new BProgram.Statement.Input();
        in.limits.add(new BProgram.ResourceLimit());
        BProgram.Statement.Output out = new BProgram.Statement.Output();
        out.limits.add(new BProgram.ResourceLimit());
        t.body.add(in);
        t.body.add(out);
        p.triggers.add(t);
        return t;
    }

    private static BProgram.Statement.Input inputOf(BProgram p) {
        return (BProgram.Statement.Input) p.triggers.get(0).body.get(0);
    }

    private static BProgram.Statement.Output outputOf(BProgram p) {
        return (BProgram.Statement.Output) p.triggers.get(0).body.get(1);
    }

    // ------------------------------------------------------------ 判定：逐屏

    @Test
    public void emptyProgramSatisfiesNothing() {
        BProgram p = new BProgram();
        assertFalse(TutorialState.satisfiedBy(TutorialStep.ADD_CARD, p, false));
        assertFalse(TutorialState.satisfiedBy(TutorialStep.PICK_IN_LABEL, p, false));
        assertFalse(TutorialState.satisfiedBy(TutorialStep.PICK_OUT_LABEL, p, false));
        assertFalse(TutorialState.satisfiedBy(TutorialStep.EACH_SIDE, p, false));
        assertFalse(TutorialState.satisfiedBy(TutorialStep.TO_FLUID, p, false));
        assertFalse(TutorialState.satisfiedBy(TutorialStep.PICK_RESOURCE, p, false));
    }

    @Test
    public void newCardCompletesAddCardButNotLabelSteps() {
        BProgram p = new BProgram();
        addTimerCard(p);
        assertTrue(TutorialState.satisfiedBy(TutorialStep.ADD_CARD, p, false),
                "拖进一张定时卡后，建卡这一步应该完成");
        assertFalse(TutorialState.satisfiedBy(TutorialStep.PICK_IN_LABEL, p, false),
                "默认占位标签 \"a\" 不算选好标签，否则第 2 屏会被瞬间跳过");
        assertFalse(TutorialState.satisfiedBy(TutorialStep.PICK_OUT_LABEL, p, false),
                "默认占位标签 \"b\" 同理");
    }

    @Test
    public void realLabelCompletesLabelSteps() {
        BProgram p = new BProgram();
        addTimerCard(p);

        inputOf(p).access.labels.clear();
        inputOf(p).access.labels.add("storage");
        assertTrue(TutorialState.satisfiedBy(TutorialStep.PICK_IN_LABEL, p, false));

        outputOf(p).access.labels.clear();
        outputOf(p).access.labels.add("furnace");
        assertTrue(TutorialState.satisfiedBy(TutorialStep.PICK_OUT_LABEL, p, false));
    }

    @Test
    public void eachSideCompletesSideStep() {
        BProgram p = new BProgram();
        addTimerCard(p);
        assertFalse(TutorialState.satisfiedBy(TutorialStep.EACH_SIDE, p, false));
        inputOf(p).access.eachSide = true;
        assertTrue(TutorialState.satisfiedBy(TutorialStep.EACH_SIDE, p, false));
    }

    @Test
    public void fluidStepNeedsExplicitFluid() {
        BProgram p = new BProgram();
        addTimerCard(p);
        // 空 resources 在界面上按隐式 item 处理，不能算成流体
        assertFalse(TutorialState.satisfiedBy(TutorialStep.TO_FLUID, p, false));
        assertFalse(TutorialState.satisfiedBy(TutorialStep.PICK_RESOURCE, p, false));

        inputOf(p).limits.get(0).resources.add(
                BProgram.ResourceRef.forKind(BProgram.ResourceKind.FLUID));
        assertTrue(TutorialState.satisfiedBy(TutorialStep.TO_FLUID, p, false),
                "显式选了流体后，换资源类型这一步应完成");
        assertFalse(TutorialState.satisfiedBy(TutorialStep.PICK_RESOURCE, p, false),
                "通配的 fluid:* 还没选具体资源，第 6 屏不该完成");
    }

    @Test
    public void concreteResourceCompletesResourceStep() {
        BProgram p = new BProgram();
        addTimerCard(p);
        BProgram.ResourceRef ref = BProgram.ResourceRef.forKind(BProgram.ResourceKind.FLUID);
        ref.namespace = "minecraft";
        ref.name = "water";
        inputOf(p).limits.get(0).resources.add(ref);
        assertTrue(TutorialState.satisfiedBy(TutorialStep.PICK_RESOURCE, p, false));
    }

    @Test
    public void lastStepIsManualDone() {
        // SAVE 已从步骤里删掉（2026-10-25）：
        // 引导在「选具体资源」之后直接收尾，不再要求先存盘。
        assertTrue(TutorialStep.DONE.manual, "收尾屏必须靠点击推进");
        assertTrue(TutorialStep.DONE.isLast(), "DONE 应该是最后一步");
        assertTrue(TutorialState.satisfiedBy(TutorialStep.DONE, new BProgram(), false),
                "DONE 的判定恒为 true，靠 manual 拦住自动推进");
    }

    @Test
    public void nullProgramIsSafe() {
        assertFalse(TutorialState.satisfiedBy(TutorialStep.ADD_CARD, null, false));
    }

    // -------------------------------------------------------------- 推进逻辑

    @Test
    public void manualStepsDoNotAutoAdvance() {
        TutorialState s = new TutorialState();
        assertEquals(TutorialStep.WELCOME, s.current());
        // 欢迎屏是手动屏：即使程序状态"满足"，也不该自己跳过去
        assertFalse(s.advanceIfDone(new BProgram(), false));
        assertEquals(TutorialStep.WELCOME, s.current());

        s.advanceManual();
        assertEquals(TutorialStep.ADD_CARD, s.current());
    }

    @Test
    public void advanceSkipsAllSatisfiedStepsInOnePass() {
        TutorialState s = new TutorialState();
        s.advanceManual();                       // → ADD_CARD

        BProgram p = new BProgram();
        addTimerCard(p);
        inputOf(p).access.labels.clear();
        inputOf(p).access.labels.add("storage");
        outputOf(p).access.labels.clear();
        outputOf(p).access.labels.add("furnace");

        // 一次调用应连跳 3 步（建卡 + 两个标签），停在「每一面」
        assertTrue(s.advanceIfDone(p, false));
        assertEquals(TutorialStep.EACH_SIDE, s.current());
    }

    @Test
    public void progressNeverGoesBackwards() {
        TutorialState s = new TutorialState();
        s.advanceManual();
        BProgram p = new BProgram();
        addTimerCard(p);
        inputOf(p).access.labels.clear();
        inputOf(p).access.labels.add("storage");
        s.advanceIfDone(p, false);
        TutorialStep reached = s.current();

        // 玩家反悔，把标签改回占位符
        inputOf(p).access.labels.clear();
        inputOf(p).access.labels.add("a");
        s.advanceIfDone(p, false);

        assertEquals(reached, s.current(), "已经学过的步骤不该因为撤销而倒退");
    }

    @Test
    public void skipAndRestart() {
        TutorialState s = new TutorialState();
        s.skip();
        assertFalse(s.active());
        assertFalse(s.advanceIfDone(new BProgram(), false));

        s.restart();
        assertTrue(s.active());
        assertEquals(TutorialStep.WELCOME, s.current());
    }

    // ---------------------------------------------------------------- 编解码

    @Test
    public void jsonRoundTrip() {
        TutorialState s = new TutorialState();
        s.advanceManual();
        s.advanceManual();
        TutorialState back = TutorialState.fromJson(s.toJson().toString());
        assertEquals(s.reachedOrdinal(), back.reachedOrdinal());
        assertEquals(s.finished(), back.finished());
        assertEquals(s.skipped(), back.skipped());
    }

    @Test
    public void corruptedJsonFallsBackToFresh() {
        assertEquals(-1, TutorialState.fromJson("not json at all").reachedOrdinal());
        assertEquals(-1, TutorialState.fromJson("{\"formatVersion\":1,\"reachedOrdinal\":\"oops\"}")
                .reachedOrdinal());
        assertEquals(-1, TutorialState.fromJson(null).reachedOrdinal());
        assertEquals(-1, TutorialState.fromJson("").reachedOrdinal());
    }

    @Test
    public void unknownFormatVersionIsTreatedAsFresh() {
        assertEquals(-1, TutorialState.fromJson("{\"formatVersion\":99,\"reachedOrdinal\":5}")
                .reachedOrdinal());
    }

    @Test
    public void outOfRangeOrdinalIsClamped() {
        int max = TutorialStep.values().length - 1;
        assertEquals(max, TutorialState.fromJson("{\"formatVersion\":1,\"reachedOrdinal\":9999}")
                .reachedOrdinal());
        assertEquals(-1, TutorialState.fromJson("{\"formatVersion\":1,\"reachedOrdinal\":-99}")
                .reachedOrdinal());
    }
}
