package io.github.xianynomial.sfmfactorystudio.client.blocks.model;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;
import com.google.gson.JsonParser;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 槽位布局捕获数据（纯数据 + JSON 编解码，MC-free 可单测）。
 *
 * <p>存储格式 v2 = 每槽一个对象 {@code {"x":..,"y":..,"c":容器内索引,"i":物品id,
 * "n":数量,"cap":实例匹配到的能力槽索引}}；旧版 v1 条目 {@code [seq,x,y]} 读入时
 * 缺省 {@code c=-1, i="", n=0, cap=null}——旧 slot-layouts.json 无损升级。
 */
public final class SlotLayoutData {
    private SlotLayoutData() {
    }

    /** 单个捕获槽位。capIndex 非 null = 菜单槽容器与能力面是同一实例（索引精确）。 */
    public record SlotCapture(int x, int y, int containerSlot, String item, int count, Integer capIndex) {
        /** 是否带内容签名（捕获到非空物品）。 */
        public boolean hasSignature() {
            return item != null && !item.isEmpty();
        }
    }

    /**
     * 操作学习锚点：玩家在机器界面的一次真实点击改变了能力槽 capIndex 的内容
     * ——"这个视觉格 = 这个真实槽位"由实际数据流证实，是最高优先级证据。
     * dir = 七朝向索引（0=无侧面，1..6=down,up,north,south,west,east）。
     */
    public record SlotAnchor(int dir, int containerSlot, int x, int y, int capIndex) {
    }

    /** 一个容器的布局快照（anchors = 操作学习累积的锚点，与捕获相互独立）。 */
    public record Layout(String title, List<SlotCapture> slots, List<SlotAnchor> anchors) {
        public Layout(String title, List<SlotCapture> slots) {
            this(title, slots, List.of());
        }

        public int menuSlotCount() {
            return slots.size();
        }
    }

    /** 按（朝向, 容器内索引）去重合并锚点：已有学习不丢失。 */
    public static List<SlotAnchor> mergeAnchors(List<SlotAnchor> old, List<SlotAnchor> fresh) {
        List<SlotAnchor> out = new ArrayList<>(old == null ? List.of() : old);
        if (fresh != null) {
            for (SlotAnchor a : fresh) {
                boolean exists = false;
                for (SlotAnchor b : out) {
                    if (b.dir() == a.dir() && b.containerSlot() == a.containerSlot()) {
                        exists = true;
                        break;
                    }
                }
                if (!exists) out.add(a);
            }
        }
        return out;
    }

    /**
     * 在布局上应用一个学习锚点：锚点表按（朝向, 容器内索引）去重更新，
     * 并把 capIndex 写到对应的捕获格上（containerSlot 优先，坐标兜底）。
     */
    public static Layout withAnchor(Layout layout, SlotAnchor anchor) {
        List<SlotAnchor> anchors = new ArrayList<>();
        for (SlotAnchor b : layout.anchors()) {
            if (b.dir() == anchor.dir() && b.containerSlot() == anchor.containerSlot()) continue;
            anchors.add(b);
        }
        anchors.add(anchor);
        List<SlotCapture> slots = new ArrayList<>();
        for (SlotCapture s : layout.slots()) {
            if (matchesAnchor(s, anchor)) {
                slots.add(new SlotCapture(s.x(), s.y(), s.containerSlot(), s.item(), s.count(), anchor.capIndex()));
            } else {
                slots.add(s);
            }
        }
        return new Layout(layout.title(), slots, anchors);
    }

    private static boolean matchesAnchor(SlotCapture s, SlotAnchor a) {
        if (a.containerSlot() >= 0 && s.containerSlot() == a.containerSlot()) return true;
        return s.x() == a.x() && s.y() == a.y();
    }

    /** 参照方向（refDir 名）→ 七朝向索引（"null"=0，其余 = Direction.ordinal()+1）。 */
    public static int refDirIndex(String refDir) {
        if (refDir == null || refDir.isEmpty() || refDir.equals("null")) return 0;
        switch (refDir) {
            case "down": return 1;
            case "up": return 2;
            case "north": return 3;
            case "south": return 4;
            case "west": return 5;
            case "east": return 6;
            default: return 0;
        }
    }

    /** 序列化整个缓存（pretty print，写文件用）。 */
    public static String writeAll(Map<String, Layout> byPos) {
        JsonObject root = new JsonObject();
        for (Map.Entry<String, Layout> e : byPos.entrySet()) {
            root.add(e.getKey(), writeLayout(e.getValue()));
        }
        return new com.google.gson.GsonBuilder().setPrettyPrinting().create().toJson(root);
    }

    public static JsonObject writeLayout(Layout layout) {
        JsonObject o = new JsonObject();
        o.addProperty("title", layout.title() == null ? "" : layout.title());
        o.addProperty("v", 2);
        JsonArray arr = new JsonArray();
        for (SlotCapture s : layout.slots()) {
            JsonObject e = new JsonObject();
            e.addProperty("x", s.x());
            e.addProperty("y", s.y());
            if (s.containerSlot() >= 0) e.addProperty("c", s.containerSlot());
            if (s.hasSignature()) {
                e.addProperty("i", s.item());
                e.addProperty("n", s.count());
            }
            if (s.capIndex() != null) e.addProperty("cap", s.capIndex());
            arr.add(e);
        }
        o.add("slots", arr);
        if (!layout.anchors().isEmpty()) {
            JsonArray anchors = new JsonArray();
            for (SlotAnchor a : layout.anchors()) {
                JsonObject e = new JsonObject();
                e.addProperty("d", a.dir());
                e.addProperty("cs", a.containerSlot());
                e.addProperty("x", a.x());
                e.addProperty("y", a.y());
                e.addProperty("cap", a.capIndex());
                anchors.add(e);
            }
            o.add("anchors", anchors);
        }
        return o;
    }

    /**
     * 解析整个缓存文件；损坏条目跳过而不是炸整个文件。
     *
     * @throws JsonParseException 顶层不是 JSON 对象时（调用方兜底为空缓存）
     */
    public static LinkedHashMap<String, Layout> readAll(String json) {
        LinkedHashMap<String, Layout> out = new LinkedHashMap<>();
        if (json == null || json.isBlank()) return out;
        JsonElement root = JsonParser.parseString(json);
        if (!root.isJsonObject()) throw new JsonParseException("root is not an object");
        for (Map.Entry<String, JsonElement> e : root.getAsJsonObject().entrySet()) {
            try {
                Layout layout = readLayout(e.getValue());
                if (layout != null) out.put(e.getKey(), layout);
            } catch (RuntimeException ignored) {
                // 单个条目损坏不影响其它条目
            }
        }
        return out;
    }

    /** 解析单个容器布局；兼容 v1 数组条目与 v2 对象条目。 */
    public static Layout readLayout(JsonElement element) {
        if (element == null || !element.isJsonObject()) return null;
        JsonObject o = element.getAsJsonObject();
        String title = o.has("title") && o.get("title").isJsonPrimitive()
                ? o.get("title").getAsString() : "";
        List<SlotCapture> slots = new ArrayList<>();
        if (o.has("slots") && o.get("slots").isJsonArray()) {
            for (JsonElement el : o.getAsJsonArray("slots")) {
                SlotCapture c = readCapture(el);
                if (c != null) slots.add(c);
            }
        }
        List<SlotAnchor> anchors = new ArrayList<>();
        if (o.has("anchors") && o.get("anchors").isJsonArray()) {
            for (JsonElement el : o.getAsJsonArray("anchors")) {
                SlotAnchor a = readAnchor(el);
                if (a != null) anchors.add(a);
            }
        }
        return new Layout(title, slots, anchors);
    }

    private static SlotAnchor readAnchor(JsonElement el) {
        try {
            if (!el.isJsonObject()) return null;
            JsonObject o = el.getAsJsonObject();
            if (!o.has("d") || !o.has("cap")) return null;
            return new SlotAnchor(o.get("d").getAsInt(),
                    o.has("cs") ? o.get("cs").getAsInt() : -1,
                    o.has("x") ? o.get("x").getAsInt() : Integer.MIN_VALUE,
                    o.has("y") ? o.get("y").getAsInt() : Integer.MIN_VALUE,
                    o.get("cap").getAsInt());
        } catch (RuntimeException e) {
            return null;
        }
    }

    private static SlotCapture readCapture(JsonElement el) {
        try {
            if (el.isJsonArray()) {
                JsonArray a = el.getAsJsonArray();
                if (a.size() < 3) return null;
                // v1：[seq, x, y]
                return new SlotCapture(a.get(1).getAsInt(), a.get(2).getAsInt(), -1, "", 0, null);
            }
            if (!el.isJsonObject()) return null;
            JsonObject o = el.getAsJsonObject();
            if (!o.has("x") || !o.has("y")) return null;
            int x = o.get("x").getAsInt();
            int y = o.get("y").getAsInt();
            int c = o.has("c") ? o.get("c").getAsInt() : -1;
            String item = o.has("i") ? o.get("i").getAsString() : "";
            int n = o.has("n") ? o.get("n").getAsInt() : 0;
            Integer cap = o.has("cap") && !o.get("cap").isJsonNull() ? o.get("cap").getAsInt() : null;
            return new SlotCapture(x, y, c, item, n, cap);
        } catch (RuntimeException e) {
            return null;
        }
    }

    /**
     * 捕获新鲜度比较。偏向新捕获（模组 GUI 的槽位布局会晚绑定/动态调整，
     * 新打开界面的一次更可信）；仅当旧捕获格子数超过新捕获两倍以上时
     * 才认为旧的是明显更完整的另一容器布局而保留。
     */
    public static boolean preferCapture(List<SlotCapture> fresh, List<SlotCapture> existing) {
        if (existing == null) return true;
        if (existing.size() > fresh.size() * 2) return false;
        if (fresh.size() != existing.size()) return fresh.size() > existing.size();
        return countSignatures(fresh) >= countSignatures(existing);
    }

    private static int countSignatures(List<SlotCapture> slots) {
        int n = 0;
        for (SlotCapture s : slots) if (s.hasSignature()) n++;
        return n;
    }
}
