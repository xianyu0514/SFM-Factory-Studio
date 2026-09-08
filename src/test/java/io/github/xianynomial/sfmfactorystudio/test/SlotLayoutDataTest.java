package io.github.xianynomial.sfmfactorystudio.test;

import com.google.gson.JsonParseException;
import io.github.xianynomial.sfmfactorystudio.client.blocks.model.SlotLayoutData;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class SlotLayoutDataTest {

    @Test
    public void v2RoundTripPreservesAllFields() {
        LinkedHashMap<String, SlotLayoutData.Layout> byPos = new LinkedHashMap<>();
        byPos.put("1|2|3", new SlotLayoutData.Layout("箱子", List.of(
                new SlotLayoutData.SlotCapture(10, 20, 5, "minecraft:iron_ingot", 64, 5),
                new SlotLayoutData.SlotCapture(30, 40, -1, "", 0, null),
                new SlotLayoutData.SlotCapture(50, 60, 7, "minecraft:gold_ingot", 1, null)
        )));
        String json = SlotLayoutData.writeAll(byPos);
        LinkedHashMap<String, SlotLayoutData.Layout> back = SlotLayoutData.readAll(json);
        assertEquals(1, back.size());
        SlotLayoutData.Layout layout = back.get("1|2|3");
        assertEquals("箱子", layout.title());
        assertEquals(3, layout.slots().size());
        SlotLayoutData.SlotCapture first = layout.slots().get(0);
        assertEquals(10, first.x());
        assertEquals(20, first.y());
        assertEquals(5, first.containerSlot());
        assertEquals("minecraft:iron_ingot", first.item());
        assertEquals(64, first.count());
        assertEquals(5, first.capIndex());
        assertTrue(first.hasSignature());
        SlotLayoutData.SlotCapture second = layout.slots().get(1);
        assertEquals(-1, second.containerSlot());
        assertFalse(second.hasSignature());
        assertNull(second.capIndex());
        assertEquals(3, layout.menuSlotCount());
    }

    /** 旧版 slot-layouts.json（v1 数组条目 [seq,x,y]）必须无损读入。 */
    @Test
    public void legacyV1EntriesAreUpgradedLosslessly() {
        String legacy = "{\"10|20|30\": {\"title\": \"熔炉\", \"slots\": [[0, 8, 10], [1, 26, 10], [2, 44, 10]]}}";
        LinkedHashMap<String, SlotLayoutData.Layout> back = SlotLayoutData.readAll(legacy);
        assertEquals(1, back.size());
        SlotLayoutData.Layout layout = back.get("10|20|30");
        assertEquals("熔炉", layout.title());
        assertEquals(3, layout.slots().size());
        SlotLayoutData.SlotCapture c = layout.slots().get(1);
        assertEquals(26, c.x());
        assertEquals(10, c.y());
        assertEquals(-1, c.containerSlot());
        assertEquals("", c.item());
        assertEquals(0, c.count());
        assertNull(c.capIndex());
    }

    @Test
    public void corruptEntriesAreSkippedNotFatal() {
        String json = "{"
                + "\"a\": {\"title\": \"ok\", \"slots\": [{\"x\":1,\"y\":2,\"c\":0,\"i\":\"minecraft:a\",\"n\":3,\"cap\":0}, {\"bogus\":true}, \"junk\"]},"
                + "\"b\": \"not an object\","
                + "\"c\": {\"slots\": \"weird\"}"
                + "}";
        LinkedHashMap<String, SlotLayoutData.Layout> back = SlotLayoutData.readAll(json);
        // "b" 非对象被跳过；"a" 里的坏元素被跳过；"c" 是合法形状的空布局（宽容保留）
        assertEquals(2, back.size());
        assertEquals(1, back.get("a").slots().size());
        assertEquals("minecraft:a", back.get("a").slots().get(0).item());
        assertTrue(back.get("c").slots().isEmpty());
    }

    @Test
    public void garbageRootThrowsForCallerFallback() {
        assertThrows(JsonParseException.class, () -> SlotLayoutData.readAll("not json at all"));
        assertTrue(SlotLayoutData.readAll("").isEmpty());
        assertTrue(SlotLayoutData.readAll(null).isEmpty());
    }

    @Test
    public void preferCaptureFavorsMoreSlotsThenFresherSignatures() {
        List<SlotLayoutData.SlotCapture> few = List.of(
                new SlotLayoutData.SlotCapture(0, 0, 0, "", 0, null));
        List<SlotLayoutData.SlotCapture> many = List.of(
                new SlotLayoutData.SlotCapture(0, 0, 0, "", 0, null),
                new SlotLayoutData.SlotCapture(1, 0, 0, "minecraft:a", 1, null));
        // 更多格子优先
        assertTrue(SlotLayoutData.preferCapture(many, few));
        assertFalse(SlotLayoutData.preferCapture(few, many));
        // 打平看内容签名（更新鲜）
        List<SlotLayoutData.SlotCapture> oldEmpty = List.of(
                new SlotLayoutData.SlotCapture(0, 0, 0, "", 0, null),
                new SlotLayoutData.SlotCapture(1, 0, 0, "", 0, null));
        assertTrue(SlotLayoutData.preferCapture(many, oldEmpty));
        assertFalse(SlotLayoutData.preferCapture(oldEmpty, many));
        // 全打平 → 接受新捕获（内容可能已变化）
        List<SlotLayoutData.SlotCapture> freshEmpty = List.of(
                new SlotLayoutData.SlotCapture(9, 9, 0, "", 0, null),
                new SlotLayoutData.SlotCapture(8, 8, 1, "", 0, null));
        assertTrue(SlotLayoutData.preferCapture(freshEmpty, oldEmpty));
        // 旧缓存为空时一律接受
        assertTrue(SlotLayoutData.preferCapture(few, null));
    }

    @Test
    public void anchorsMergeDedupeAndApplyToSlots() {
        SlotLayoutData.Layout l = new SlotLayoutData.Layout("Factory", List.of(
                new SlotLayoutData.SlotCapture(0, 0, 2, "", 0, null),
                new SlotLayoutData.SlotCapture(20, 0, 3, "", 0, null)));
        SlotLayoutData.Layout l2 = SlotLayoutData.withAnchor(l, new SlotLayoutData.SlotAnchor("M", 0, 2, 0, 0, 5));
        assertEquals(5, l2.slots().get(0).capIndex());
        assertEquals(1, l2.anchors().size());
        // 同朝向同索引再次学习 = 覆盖
        SlotLayoutData.Layout l3 = SlotLayoutData.withAnchor(l2, new SlotLayoutData.SlotAnchor("M", 0, 2, 0, 0, 7));
        assertEquals(7, l3.slots().get(0).capIndex());
        assertEquals(1, l3.anchors().size());
        assertNull(l3.slots().get(1).capIndex());   // 另一格不受影响
        // merge：旧锚点保留、新锚点补充
        assertEquals(2, SlotLayoutData.mergeAnchors(l2.anchors(),
                List.of(new SlotLayoutData.SlotAnchor("M", 0, 3, 20, 0, 9))).size());
        // 旧格式条目（containerSlot=-1）按坐标兜底匹配
        SlotLayoutData.Layout legacy = new SlotLayoutData.Layout("t",
                List.of(new SlotLayoutData.SlotCapture(9, 9, -1, "", 0, null)));
        SlotLayoutData.Layout legacy2 = SlotLayoutData.withAnchor(legacy, new SlotLayoutData.SlotAnchor("M", 0, -1, 9, 9, 4));
        assertEquals(4, legacy2.slots().get(0).capIndex());
    }
}
