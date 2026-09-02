package dev.jimu.sfmjimu.test;

import ca.teamdman.sfmjimu.client.blocks.model.BProgram;
import ca.teamdman.sfmjimu.client.blocks.model.CardLayouts;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Pure-logic tests for the free-coordinate card layout engine (方案 A):
 * grid snapping, overlap separation, and saved-position fingerprint matching.
 * No Minecraft instance needed — these are the regression guards for
 * layouts.json persistence and undo position preservation.
 */
public class CardLayoutsTest {

    // ---------------------------------------------------------------- snap

    @Test
    public void snapRoundsToGrid() {
        assertEquals(0, CardLayouts.snap(0));
        assertEquals(0, CardLayouts.snap(3));    // 3/8 = 0.375 → 0
        assertEquals(8, CardLayouts.snap(5));    // 0.625 → 8
        assertEquals(8, CardLayouts.snap(8));
        assertEquals(16, CardLayouts.snap(13));  // 1.625 → 2×8
        assertEquals(-8, CardLayouts.snap(-5));
    }

    @Test
    public void completeCardCloneIsPlacedDirectlyBelowOnTheSameGrid() {
        assertEquals(184, CardLayouts.directlyBelow(16, 143));
        assertEquals(-16, CardLayouts.directlyBelow(-200, 163));
    }

    // ------------------------------------------------------- matchByKeys

    @Test
    public void matchReorderedKeys() {
        // 卡片被 ◀/▶ 重排后：指纹相同但顺序对调，各自匹配回自己的条目
        int[] m = CardLayouts.matchByKeys(List.of("a", "b"), List.of("b", "a"));
        assertArrayEquals(new int[]{1, 0}, m);
    }

    @Test
    public void matchDuplicatesInOrder() {
        // 两个相同脉冲卡：按出现顺序一一对应，不能都抢第一条
        int[] m = CardLayouts.matchByKeys(List.of("p", "p", "p"), List.of("p", "p"));
        assertArrayEquals(new int[]{0, 1, -1}, m);
    }

    @Test
    public void matchMissingKeyIsMinusOne() {
        // 新增卡片（指纹没存过）→ -1 → 调用方回退自动排布
        int[] m = CardLayouts.matchByKeys(List.of("a", "x", "b"), List.of("b", "a"));
        assertArrayEquals(new int[]{1, -1, 0}, m);
    }

    @Test
    public void matchEmptyHave() {
        assertArrayEquals(new int[]{-1, -1}, CardLayouts.matchByKeys(List.of("a", "b"), List.of()));
    }

    // ---------------------------------------------------- resolveOverlaps

    @Test
    public void overlappingCardIsPushedBelow() {
        // 两卡同位：程序顺序在前的保持原位，后者被推到其下方（含 24px 间距，吸附 8px 网格）
        int[] ys = CardLayouts.resolveOverlaps(
                new int[]{0, 0}, new int[]{0, 0}, new int[]{100, 100}, new int[]{40, 40}, -1);
        assertEquals(0, ys[0]);
        assertEquals(CardLayouts.snap(40 + CardLayouts.CARD_GAP), ys[1]);
    }

    @Test
    public void sideBySideCardsDoNotMove() {
        // x 不相交（并排布局）→ 谁都不动
        int[] ys = CardLayouts.resolveOverlaps(
                new int[]{0, 400}, new int[]{0, 0}, new int[]{380, 380}, new int[]{100, 100}, -1);
        assertArrayEquals(new int[]{0, 0}, ys);
    }

    @Test
    public void keepIndexWinsOverProgramOrder() {
        // keepIdx=1：第二张卡是刚落位的卡，第一张撞上它也要让路
        int[] ys = CardLayouts.resolveOverlaps(
                new int[]{0, 0}, new int[]{0, 0}, new int[]{100, 100}, new int[]{40, 40}, 1);
        assertEquals(0, ys[1]);
        assertEquals(CardLayouts.snap(40 + CardLayouts.CARD_GAP), ys[0]);
    }

    @Test
    public void stackedChainResolves() {
        // 三张卡完全叠在一起 → 按程序顺序纵向排开、互不重叠
        int[] ys = CardLayouts.resolveOverlaps(
                new int[]{0, 0, 0}, new int[]{0, 0, 0}, new int[]{100, 100, 100}, new int[]{40, 40, 40}, -1);
        assertEquals(0, ys[0]);
        int y1 = CardLayouts.snap(40 + CardLayouts.CARD_GAP);
        assertEquals(y1, ys[1]);
        assertEquals(CardLayouts.snap(y1 + 40 + CardLayouts.CARD_GAP), ys[2]);
        for (int i = 0; i < 3; i++) {
            for (int j = i + 1; j < 3; j++) {
                assertTrue(!CardLayouts.intersects(0, ys[i], 100, 40, 0, ys[j], 100, 40),
                        "cards " + i + " and " + j + " still overlap");
            }
        }
    }

    @Test
    public void manyStackedCardsStillResolveWithoutOverlap() {
        int count = 64;
        int[] xs = new int[count];
        int[] ys = new int[count];
        int[] ws = new int[count];
        int[] hs = new int[count];
        java.util.Arrays.fill(ws, 380);
        java.util.Arrays.fill(hs, 40);
        int[] resolved = CardLayouts.resolveOverlaps(xs, ys, ws, hs, -1);
        for (int i = 0; i < count; i++) {
            for (int j = i + 1; j < count; j++) {
                assertTrue(!CardLayouts.intersects(xs[i], resolved[i], ws[i], hs[i],
                                xs[j], resolved[j], ws[j], hs[j]),
                        "large stack still overlaps at " + i + "/" + j);
            }
        }
    }

    @Test
    public void positionsNeverMoveUp() {
        // 避让只能往下推，永不把卡往上挪
        int[] ys = CardLayouts.resolveOverlaps(
                new int[]{0, 0, 400, 400}, new int[]{0, 500, 0, 1000},
                new int[]{100, 100, 100, 100}, new int[]{40, 40, 40, 40}, -1);
        assertTrue(ys[0] >= 0 && ys[1] >= 500 && ys[2] >= 0 && ys[3] >= 1000,
                "some card moved up: " + java.util.Arrays.toString(ys));
    }

    // ------------------------------------------------------------ triggerKey

    @Test
    public void timerHeaderFieldsFormTheKey() {
        var a = new BProgram.TimerTrigger();
        var b = new BProgram.TimerTrigger();
        assertEquals(CardLayouts.triggerKey(a), CardLayouts.triggerKey(b));

        b.count = 40;   // 改头部数值 → 另一张卡
        assertNotEquals(CardLayouts.triggerKey(a), CardLayouts.triggerKey(b));

        var c = new BProgram.TimerTrigger();
        c.global = true;
        assertNotEquals(CardLayouts.triggerKey(a), CardLayouts.triggerKey(c));
    }

    @Test
    public void bodyEditsDoNotChangeTheKey() {
        // 编辑卡内积木块是最常见操作，绝不能因此丢位置
        var a = new BProgram.TimerTrigger();
        var b = new BProgram.TimerTrigger();
        b.body.add(new BProgram.Statement.Input());
        assertEquals(CardLayouts.triggerKey(a), CardLayouts.triggerKey(b));
    }

    @Test
    public void pulseKeysAreAllEqual() {
        assertEquals(CardLayouts.triggerKey(new BProgram.PulseTrigger()),
                CardLayouts.triggerKey(new BProgram.PulseTrigger()));
    }

    // ------------------------------------------------------------- keysOf

    @Test
    public void keysOfKeepsProgramOrder() {
        var t1 = new BProgram.TimerTrigger();
        var t2 = new BProgram.PulseTrigger();
        var t3 = new BProgram.TimerTrigger();
        t3.count = 99;
        List<String> keys = CardLayouts.keysOf(List.of(t1, t2, t3));
        assertEquals(List.of(CardLayouts.triggerKey(t1), "p", CardLayouts.triggerKey(t3)), keys);
    }
}
