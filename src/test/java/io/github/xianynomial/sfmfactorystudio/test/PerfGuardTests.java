package io.github.xianynomial.sfmfactorystudio.test;

import io.github.xianynomial.sfmfactorystudio.client.blocks.model.BProgram;
import io.github.xianynomial.sfmfactorystudio.client.blocks.model.CardLayouts;
import io.github.xianynomial.sfmfactorystudio.client.blocks.model.ProgramDiagnostics;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Performance regression guards for the pure parts the editor exercises on
 * hot paths: card overlap resolution (runs after every card move) and the
 * diagnostics pass (recomputed whenever the program changes).
 *
 * The bounds are deliberately generous (an order of magnitude above what a
 * healthy implementation needs) — they exist to catch accidental O(n³)
 * blowups, not to benchmark.
 */
public class PerfGuardTests {

    /** 25 triggers × 20 statements ≈ 500 blocks — a "large" player program. */
    private static BProgram bigProgram() {
        BProgram program = new BProgram();
        program.name = "perf";
        for (int t = 0; t < 25; t++) {
            BProgram.TimerTrigger trigger = new BProgram.TimerTrigger();
            trigger.count = 20 + t;
            program.triggers.add(trigger);
            for (int s = 0; s < 16; s++) {
                BProgram.Statement.Input in = new BProgram.Statement.Input();
                in.access.labels.add("label" + (s % 8));
                BProgram.ResourceLimit limit = new BProgram.ResourceLimit();
                limit.quantity = 64L;
                limit.retain = 1L;
                try {
                    limit.resources.add(BProgram.ResourceRef.parse("minecraft:item_" + s));
                } catch (IllegalArgumentException ignored) {
                    // 构造失败不影响规模测试，跳过即可
                }
                if (!limit.resources.isEmpty()) in.limits.add(limit);
                trigger.body.add(in);
                BProgram.Statement.Output out = new BProgram.Statement.Output();
                out.access.labels.add("label" + (s % 8));
                out.limits.add(new BProgram.ResourceLimit());
                trigger.body.add(out);
            }
            BProgram.Statement.If iff = new BProgram.Statement.If();
            BProgram.Branch branch = new BProgram.Branch();
            BProgram.Bool.Has has = new BProgram.Bool.Has();
            has.access.labels.add("label0");
            has.number = 4;
            branch.cond = has;
            branch.body.add(new BProgram.Statement.Forget());
            iff.branches.add(branch);
            iff.hasElse = true;
            iff.elseBody.add(new BProgram.Statement.Comment("备注"));
            trigger.body.add(iff);
        }
        return program;
    }

    @Test
    public void diagnosticsOnFiveHundredBlocksStaysLinear() {
        BProgram program = bigProgram();
        Map<String, Integer> labelCounts = new HashMap<>();
        for (int i = 0; i < 8; i++) labelCounts.put("label" + i, 3);
        ProgramDiagnostics.Context ctx = new ProgramDiagnostics.Context(labelCounts, (ns, name) -> {
            // 有查询就要返回集合，逼着 id 类别核对路径真正跑起来
            return java.util.Set.of(BProgram.ResourceKind.ITEM);
        });
        long start = System.nanoTime();
        List<ProgramDiagnostics.Issue> issues = ProgramDiagnostics.check(program, ctx);
        long millis = (System.nanoTime() - start) / 1_000_000;
        assertTrue(issues.size() >= 0, "诊断必须能跑完");
        assertTrue(millis < 2000, "500 积木诊断应在 2 秒内完成，实际 " + millis + " ms");
    }

    @Test
    public void overlapResolutionOnThreeHundredStackedCardsStaysFast() {
        int n = 300;
        int[] xs = new int[n], ys = new int[n], ws = new int[n], hs = new int[n];
        for (int i = 0; i < n; i++) {
            xs[i] = (i % 3) * 400;   // 三列，每列 100 张全部叠在同一位置：最坏级联
            ys[i] = 0;
            ws[i] = 380;
            hs[i] = 120 + (i % 5) * 8;
        }
        long start = System.nanoTime();
        int[] out = CardLayouts.resolveOverlaps(xs, ys, ws, hs, -1);
        long millis = (System.nanoTime() - start) / 1_000_000;
        for (int col = 0; col < 3; col++) {
            List<Integer> cards = new ArrayList<>();
            for (int i = 0; i < n; i++) if (i % 3 == col) cards.add(i);
            for (int k = 1; k < cards.size(); k++) {
                int prev = cards.get(k - 1), cur = cards.get(k);
                boolean separated = out[cur] >= out[prev] + hs[prev] + CardLayouts.CARD_GAP;
                assertTrue(separated, "同列卡片 " + prev + "/" + cur + " 仍重叠");
            }
        }
        assertTrue(millis < 2000, "300 张级联卡避让应在 2 秒内完成，实际 " + millis + " ms");
    }

    @Test
    public void matchByKeysOnLargeListsStaysFast() {
        int n = 2000;
        List<String> want = new ArrayList<>(n);
        List<String> have = new ArrayList<>(n);
        for (int i = 0; i < n; i++) {
            want.add("t:" + (i % 7) + ":TICKS:false:0");
            have.add("t:" + (i % 7) + ":TICKS:false:0");
        }
        long start = System.nanoTime();
        int[] m = CardLayouts.matchByKeys(want, have);
        long millis = (System.nanoTime() - start) / 1_000_000;
        for (int i = 0; i < n; i++) assertTrue(m[i] >= 0, "同数同键应全部匹配");
        assertTrue(millis < 1000, "2000 键匹配应在 1 秒内完成，实际 " + millis + " ms");
    }
}
