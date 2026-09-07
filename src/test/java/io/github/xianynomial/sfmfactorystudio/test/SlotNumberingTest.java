package io.github.xianynomial.sfmfactorystudio.test;

import io.github.xianynomial.sfmfactorystudio.client.blocks.model.BProgram;
import io.github.xianynomial.sfmfactorystudio.client.blocks.model.SlotNumbering;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.TreeSet;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class SlotNumberingTest {

    private static SlotNumbering.MenuSlot slot(int seq, int x, int y, String item, int count, Integer capHint) {
        return new SlotNumbering.MenuSlot(seq, x, y, item, count, capHint);
    }

    private static SlotNumbering.CapSlot cap(int index, String item, int count) {
        return new SlotNumbering.CapSlot(index, item, count);
    }

    /** 一行格子：y=0，x 按 20px 步进。 */
    private static List<SlotNumbering.MenuSlot> row(int n, java.util.function.IntFunction<String> item,
                                                    java.util.function.IntFunction<Integer> count) {
        List<SlotNumbering.MenuSlot> out = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            out.add(slot(i, i * 20, 0, item.apply(i), count.apply(i), null));
        }
        return out;
    }

    // ---- 兜底：无服务端数据 = 纯空间编号，明示未校准 ----

    @Test
    public void fallbackUsesSpatialOrderAndReportsUncalibrated() {
        List<SlotNumbering.MenuSlot> slots = new ArrayList<>();
        // 两行三列，故意乱序加入
        slots.add(slot(0, 40, 20, "", 0, null));
        slots.add(slot(1, 0, 0, "", 0, null));
        slots.add(slot(2, 20, 0, "", 0, null));
        slots.add(slot(3, 0, 20, "", 0, null));
        slots.add(slot(4, 40, 0, "", 0, null));
        slots.add(slot(5, 20, 20, "", 0, null));
        SlotNumbering.Result r = SlotNumbering.compute(slots, null, null);
        assertFalse(r.calibrated());
        assertEquals(0, r.number(1)); // (0,0)
        assertEquals(1, r.number(2)); // (20,0)
        assertEquals(2, r.number(4)); // (40,0)
        assertEquals(3, r.number(3)); // (0,20)
        assertEquals(4, r.number(5)); // (20,20)
        assertEquals(5, r.number(0)); // (40,20)
        for (int i = 0; i < 6; i++) assertTrue(r.isAddressable(i));
    }

    // ---- ① 实例匹配提示：精确 ----

    @Test
    public void capHintIsExactAndUnmatchedCapsAreCounted() {
        List<SlotNumbering.MenuSlot> slots = new ArrayList<>();
        slots.add(slot(0, 0, 0, "", 0, 2));
        slots.add(slot(1, 20, 0, "", 0, 0));
        slots.add(slot(2, 40, 0, "", 0, 3));
        SlotNumbering.Result r = SlotNumbering.compute(slots,
                List.of(cap(0, "", 0), cap(1, "", 0), cap(2, "", 0), cap(3, "", 0)), 4);
        assertTrue(r.calibrated());
        assertEquals(2, r.number(0));
        assertEquals(0, r.number(1));
        assertEquals(3, r.number(2));
        assertEquals(1, r.hiddenCaps()); // 能力槽 1 无对应界面格
        for (int i = 0; i < 3; i++) assertTrue(r.isAddressable(i));
    }

    @Test
    public void conflictingHintsAreDroppedAndZipTakesOver() {
        List<SlotNumbering.MenuSlot> slots = new ArrayList<>();
        slots.add(slot(0, 0, 0, "", 0, 1));
        slots.add(slot(1, 20, 0, "", 0, 1)); // 与上一格争同一槽 → 提示全部作废
        SlotNumbering.Result r = SlotNumbering.compute(slots,
                List.of(cap(0, "", 0), cap(1, "", 0)), 2);
        assertTrue(r.calibrated());
        // 空签名全部歧义 → 空间拉链：左格 0、右格 1
        assertEquals(0, r.number(0));
        assertEquals(1, r.number(1));
        assertEquals(0, r.hiddenCaps());
    }

    // ---- ② 内容签名唯一匹配 ----

    @Test
    public void uniqueSignatureMatchAssignsRealIndexes() {
        List<SlotNumbering.MenuSlot> slots = row(3,
                i -> switch (i) {
                    case 0 -> "minecraft:iron_ingot";
                    case 1 -> "minecraft:gold_ingot";
                    default -> "";
                },
                i -> switch (i) {
                    case 0 -> 64;
                    case 1 -> 3;
                    default -> 0;
                });
        SlotNumbering.Result r = SlotNumbering.compute(slots,
                List.of(cap(0, "", 0), cap(1, "minecraft:iron_ingot", 64), cap(2, "minecraft:gold_ingot", 3)), 3);
        assertTrue(r.calibrated());
        assertEquals(1, r.number(0)); // 铁锭格 = 能力槽 1
        assertEquals(2, r.number(1)); // 金锭格 = 能力槽 2
        assertEquals(0, r.number(2)); // 空格 = 能力槽 0（唯一空候选）
        assertEquals(0, r.hiddenCaps());
    }

    // ---- ③ 歧义拉链：空间顺序 ----

    @Test
    public void ambiguousEmptySlotsZipInSpatialOrder() {
        List<SlotNumbering.MenuSlot> slots = row(4, i -> "", i -> 0);
        SlotNumbering.Result r = SlotNumbering.compute(slots,
                List.of(cap(0, "", 0), cap(1, "", 0), cap(2, "", 0), cap(3, "", 0)), 4);
        assertTrue(r.calibrated());
        for (int i = 0; i < 4; i++) assertEquals(i, r.number(i));
    }

    // ---- 置灰：升级卡等不可寻址格 ----

    @Test
    public void excessMenuSlotsBecomeUnaddressable() {
        List<SlotNumbering.MenuSlot> slots = row(6, i -> i < 4 ? "minecraft:item_" + i : "",
                i -> i < 4 ? 1 : 0);
        SlotNumbering.Result r = SlotNumbering.compute(slots,
                List.of(cap(0, "minecraft:item_0", 1), cap(1, "minecraft:item_1", 1),
                        cap(2, "minecraft:item_2", 1), cap(3, "minecraft:item_3", 1)), 4);
        assertTrue(r.calibrated());
        for (int i = 0; i < 4; i++) {
            assertTrue(r.isAddressable(i));
            assertEquals(i, r.number(i));
        }
        assertFalse(r.isAddressable(4)); // 空签名格，且能力槽已全部认领 → 置灰
        assertFalse(r.isAddressable(5));
        assertEquals(-1, r.number(4));
        assertEquals(0, r.hiddenCaps());
    }

    @Test
    public void unmatchedCapsAreReportedAsHidden() {
        List<SlotNumbering.MenuSlot> slots = row(2,
                i -> "minecraft:item_" + i, i -> 1);
        SlotNumbering.Result r = SlotNumbering.compute(slots,
                List.of(cap(0, "minecraft:item_0", 1), cap(1, "minecraft:item_1", 1),
                        cap(2, "minecraft:extra_a", 1), cap(3, "minecraft:extra_b", 1)), 4);
        assertTrue(r.calibrated());
        assertEquals(2, r.hiddenCaps());
        assertTrue(r.isAddressable(0));
        assertTrue(r.isAddressable(1));
    }

    // ---- 选择与输出 ----

    @Test
    public void numbersOfDropsUnaddressableSelection() {
        List<SlotNumbering.MenuSlot> slots = row(5, i -> i < 3 ? "minecraft:item_" + i : "",
                i -> i < 3 ? 1 : 0);
        SlotNumbering.Result r = SlotNumbering.compute(slots,
                List.of(cap(0, "minecraft:item_0", 1), cap(1, "minecraft:item_1", 1),
                        cap(2, "minecraft:item_2", 1)), 3);
        TreeSet<Integer> sel = new TreeSet<>(List.of(0, 2, 3, 4)); // 3、4 是灰格
        assertEquals("0,2", SlotNumbering.numbersOf(r, sel));
        assertEquals("", SlotNumbering.numbersOf(r, new TreeSet<>(List.of(3, 4))));
    }

    @Test
    public void selectionForRangesMapsRealIndexes() {
        List<SlotNumbering.MenuSlot> slots = new ArrayList<>();
        slots.add(slot(0, 0, 0, "minecraft:a", 1, null));
        slots.add(slot(1, 20, 0, "minecraft:b", 1, null));
        SlotNumbering.Result r = SlotNumbering.compute(slots,
                List.of(cap(0, "minecraft:b", 1), cap(1, "minecraft:a", 1)), 2);
        // 签名互换：左格真实编号 1，右格真实编号 0
        assertEquals(new TreeSet<>(List.of(0)),
                SlotNumbering.selectionForRanges(r, List.of(new BProgram.SlotRange(1, 1))));
        assertEquals(new TreeSet<>(List.of(0, 1)),
                SlotNumbering.selectionForRanges(r, List.of(new BProgram.SlotRange(0, 10))));
        assertEquals(new TreeSet<>(),
                SlotNumbering.selectionForRanges(r, List.of(new BProgram.SlotRange(7, 9))));
    }

    // ---- 视图布局：归一化 + 防重叠 ----

    @Test
    public void viewNormalizesNegativeOrigins() {
        // 个别模组 GUI 槽位坐标带负数：必须平移到 0,0 起，不画出背景框外
        List<SlotNumbering.MenuSlot> slots = new ArrayList<>();
        slots.add(slot(0, -40, -20, "", 0, null));
        slots.add(slot(1, -20, -20, "", 0, null));
        slots.add(slot(2, -40, 0, "", 0, null));
        SlotNumbering.ViewLayout vl = SlotNumbering.computeView(20, slots, 400, 300);
        assertEquals(40, vl.shiftX());
        assertEquals(20, vl.shiftY());
        assertTrue(vl.contentW() > 0);
        assertTrue(vl.contentH() > 0);
    }

    @Test
    public void viewScaleNeverProducesOverlappingCells() {
        // 134 格抽屉（15 行）在很小的可用区里：缩放被下限托住，格距永 ≥ 格边长
        List<SlotNumbering.MenuSlot> slots = new ArrayList<>();
        for (int i = 0; i < 134; i++) {
            slots.add(slot(i, (i % 9) * 20, (i / 9) * 20, "", 0, null));
        }
        SlotNumbering.ViewLayout vl = SlotNumbering.computeView(20, slots, 300, 100);
        assertEquals(SlotNumbering.MIN_CELL_PX / 20f, vl.scale(), 0.0001f);
        // 内容尺寸按该缩放单调：相邻格间距(px) = 20*scale ≥ MIN_CELL_PX
        assertTrue(20 * vl.scale() >= SlotNumbering.MIN_CELL_PX);
        // 内容超出可用区 → 视口需要滚动（这正是滚动条存在的条件）
        assertTrue(vl.contentH() > 100);
    }

    @Test
    public void viewFitsWithoutScrollWhenPossible() {
        // 27 格箱子在常规可用区：1:1 显示，内容不超可用区
        List<SlotNumbering.MenuSlot> slots = new ArrayList<>();
        for (int i = 0; i < 27; i++) {
            slots.add(slot(i, (i % 9) * 20, (i / 9) * 20, "", 0, null));
        }
        SlotNumbering.ViewLayout vl = SlotNumbering.computeView(20, slots, 400, 300);
        assertEquals(1f, vl.scale(), 0.0001f);
        assertTrue(vl.contentW() <= 400);
        assertTrue(vl.contentH() <= 300);
    }

    // ---- 压缩文本 ----

    @Test
    public void compressProducesRangeText() {
        TreeSet<Integer> set = new TreeSet<>(List.of(1, 3, 4, 5, 9));
        assertEquals("1,3-5,9", SlotNumbering.compress(set));
        assertEquals("", SlotNumbering.compress(new TreeSet<>()));
        assertEquals("7", SlotNumbering.compress(new TreeSet<>(List.of(7))));
    }

    // ---- 编号唯一性护栏：任何输入下显示编号不得重复 ----

    @Test
    public void calibratedNumbersAreUniqueAcrossAddressableSlots() {
        // 混合场景：部分有提示、部分唯一签名、部分歧义、部分灰
        List<SlotNumbering.MenuSlot> slots = new ArrayList<>();
        slots.add(slot(0, 0, 0, "minecraft:x", 2, 4));
        slots.add(slot(1, 20, 0, "minecraft:y", 1, null));
        slots.add(slot(2, 40, 0, "", 0, null));
        slots.add(slot(3, 0, 20, "", 0, null));
        slots.add(slot(4, 20, 20, "minecraft:ghost", 1, null));
        List<SlotNumbering.CapSlot> caps = new ArrayList<>();
        caps.add(cap(0, "", 0));
        caps.add(cap(1, "", 0));
        caps.add(cap(2, "minecraft:y", 1));
        caps.add(cap(3, "", 0));
        caps.add(cap(4, "minecraft:x", 2));
        SlotNumbering.Result r = SlotNumbering.compute(slots, caps, 5);
        assertTrue(r.calibrated());
        java.util.Set<Integer> seen = new java.util.HashSet<>();
        for (int i = 0; i < r.size(); i++) {
            if (!r.isAddressable(i)) continue;
            assertTrue(seen.add(r.number(i)), "重复编号 " + r.number(i));
            assertTrue(r.number(i) >= 0 && r.number(i) < 5);
        }
        // 幽灵签名（diamond 之外对不上任何能力槽的显示格）不应被强行配对
        // seq4 的内容对不上任何剩余能力槽 → 置灰或拉链，但不得影响唯一性
    }
}
