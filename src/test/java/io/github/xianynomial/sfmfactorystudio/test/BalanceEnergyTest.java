package io.github.xianynomial.sfmfactorystudio.test;

import io.github.xianynomial.sfmfactorystudio.client.blocks.model.BProgram;
import io.github.xianynomial.sfmfactorystudio.client.blocks.model.BlocksToSfml;
import io.github.xianynomial.sfmfactorystudio.client.blocks.model.SfmlValidate;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 定时触发器修饰语组合的文法护栏（2026-09-09 用 SFM 4.34 真编译器实测锁定）。
 *
 * <p>合法形式由 <b>序列化顺序</b> 决定，与直觉相反：
 * <ul>
 *   <li>{@code every N global ticks do} —— 合法；</li>
 *   <li>{@code every N global plus M ticks do} —— 合法（平衡优化的错峰输出，BlocksToSfml 顺序）；</li>
 *   <li>{@code every N ticks plus M do} —— 非法（plus 必须在 ticks 之前）；</li>
 *   <li>{@code every N global ticks plus M do} —— 非法（2026-09-07 曾据此误判
 *       "组合非法"并回滚错峰，实际只是旧序列化顺序的问题）。</li>
 * </ul>
 * 这组断言锁住 BlocksToSfml 的输出顺序：任何人改 writeTrigger 的拼接顺序，
 * 这里会立刻红。
 */
public class BalanceEnergyTest {

    private boolean parses(String timerLine) {
        String sfml = "NAME \"t\"\n" + timerLine + "\n    input fe:: from a each side\nend\n";
        return SfmlValidate.check(sfml).isEmpty();
    }

    @Test
    public void plainTimerParses() {
        assertTrue(parses("every 20 ticks do"), "基础定时触发必须可编译");
        assertTrue(parses("every 20 global ticks do"), "全局时钟形式必须可编译");
    }

    @Test
    public void serializerOrderGlobalPlusOffsetsIsLegal() {
        // BlocksToSfml.writeTrigger 的输出顺序：every N global plus M ticks
        assertTrue(parses("every 20 global plus 5 ticks do"),
                "global+错峰偏移（序列化顺序）必须可编译——这是「平衡优化」的输出形态");
        assertTrue(parses("every 20 plus 5 ticks do"),
                "本地时钟错峰偏移（序列化顺序）必须可编译");
    }

    @Test
    public void rejectedOrdersStayRejected() {
        // 这两种顺序看似等价实则非法——若哪天变合法了，说明上游文法变了，
        // 值得回来复核 BlocksToSfml 的注释与诊断
        assertFalse(parses("every 20 ticks plus 5 do"), "plus 在 ticks 之后：非法");
        assertFalse(parses("every 20 global ticks plus 5 do"), "global 在 ticks 之后：非法");
    }

    @Test
    public void serializedBalancedProgramCompiles() {
        BProgram p = new BProgram();
        BProgram.TimerTrigger tt = new BProgram.TimerTrigger();
        tt.count = 20;
        tt.global = true;
        tt.plus = 5;
        tt.body.add(new BProgram.Statement.Input());
        p.triggers.add(tt);
        String sfml = BlocksToSfml.toSfml(p);
        assertTrue(sfml.contains("every 20 global plus 5 ticks"), "序列化必须产出 global plus 在 ticks 前的顺序: " + sfml);
        assertTrue(SfmlValidate.check(sfml).isEmpty(), "平衡优化产出的程序必须过真编译器");
    }
}
