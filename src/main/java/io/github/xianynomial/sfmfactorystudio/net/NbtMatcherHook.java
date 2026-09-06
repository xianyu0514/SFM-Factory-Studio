package io.github.xianynomial.sfmfactorystudio.net;

import ca.teamdman.sfml.ast.TagMatcher;
import ca.teamdman.sfml.ast.WithTag;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NumericTag;
import net.minecraft.nbt.StringTag;
import net.minecraft.nbt.Tag;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraftforge.fluids.FluidStack;

import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

/**
 * NBT 伪标签（#nbt:...）的深度值匹配 —— 1.20.1 原生 NBT 语义。
 *
 * 线格式与 1.21.1 组件版保持一致：nbt:&lt;ns&gt;/&lt;name&gt;[/&lt;selector&gt;[/&lt;ops&gt;]]
 *  - component id 恒为前两段（ns/name），选择器内部的冒号写成点（minecraft.sharpness）。
 *  - 1.20.1 的物品数据在 ItemStack.getTag() 原生 NBT 树上，组件 id 按下表映射到根键：
 *      minecraft:enchantments        → ench（列表：id=字符串注册名, lvl=int）
 *      minecraft:stored_enchantments → StoredEnchantments（同上结构）
 *      minecraft:potion_contents     → Potion（字符串注册名）
 *      minecraft:damage              → Damage（int，未受损物品无此键=非默认天然成立）
 *      minecraft:repair_cost         → RepairCost（int）
 *      minecraft:custom_name         → display.Name（JSON 文本，*通配*子串匹配）
 *      minecraft:custom_data         → 整个 tag（Forge 惯例：模组数据在根下任意路径）
 *      其它 ns:name                  → 根键 name（直接支持 Forge 模组的自定义根键）
 *  - enchantments / stored_enchantments：selector=附魔 id，尾段可附等级（=精确等级）。
 *  - potion_contents：selector=药水 id。
 *  - custom_data：selector=点路径子键，尾段可为 gt/ge/lt/le/eq+数字（数值比较）
 *    或 eq+字符串（精确匹配）；无尾段=子键存在。
 *  - 数值键（damage/repair_cost/…）：selector 直接是 gt/ge/lt/le/eq+数字。
 *  - 名称键：selector 支持 *通配*（大小写不敏感）。
 *  - 无 selector（只有组件两段）= 根键存在（向后兼容 v1）。
 */
public final class NbtMatcherHook {
    private NbtMatcherHook() {
    }

    /** 由 Mixin 挂到 WithTag 上的标记接口（注入成功的运行时证据）。 */
    public interface NbtAware {
    }

    /** 解析后的伪标签：组件 id + 值选择器段（点格式，未做类型分派）。 */
    public record Parsed(String componentId, List<String> selector) {
    }

    /** "ns/name/…"（点格式选择器）→ 组件 id + 选择器段；不是 nbt: 前缀返回 null。 */
    public static @Nullable Parsed parse(String matcher) {
        if (!matcher.startsWith("nbt:")) return null;
        String rest = matcher.substring(4);
        String[] seg = rest.split("/");
        if (seg.length < 2 || seg[0].isEmpty() || seg[1].isEmpty()) return null;
        String componentId = seg[0] + ":" + seg[1];
        List<String> selector = new ArrayList<>();
        for (int i = 2; i < seg.length; i++) selector.add(seg[i]);
        return new Parsed(componentId, selector);
    }

    /** 解析后的伪标签（含根键映射）：rootKey 为 tag 根键；"" 表示整棵树。 */
    public record ParsedNbt(String componentId, String rootKey, List<String> selector) {
    }

    /** "ns/name/…" → 组件 id + tag 根键 + 选择器段；不是 nbt: 前缀返回 null。 */
    public static @Nullable ParsedNbt parseNbt(String matcher) {
        Parsed p = parse(matcher);
        if (p == null) return null;
        return new ParsedNbt(p.componentId(), rootKey(p.componentId()), p.selector());
    }

    /** 组件 id → 1.20.1 NBT 根键；"" = 整棵 tag 树（custom_data）。 */
    private static String rootKey(String componentId) {
        return switch (componentId) {
            case "minecraft:enchantments" -> "ench";
            case "minecraft:stored_enchantments" -> "StoredEnchantments";
            case "minecraft:potion_contents" -> "Potion";
            case "minecraft:damage" -> "Damage";
            case "minecraft:repair_cost" -> "RepairCost";
            case "minecraft:custom_name", "minecraft:item_name" -> "display";
            case "minecraft:custom_data" -> "";
            default -> componentId.substring(componentId.indexOf(':') + 1);
        };
    }

    /** 服务器是否真正具备 NBT 区分能力（Mixin 已生效）。 */
    public static boolean isAvailable() {
        try {
            return (Object) new WithTag(TagMatcher.fromPath(List.of("sfmfactorystudio_probe")))
                    instanceof NbtAware;
        } catch (Throwable t) {
            return false;
        }
    }

    /**
     * 自定义名称 → 可编码匹配段：空格转 __，仅 [a-zA-Z0-9_]；
     * 含中文/符号/星号返回 null（调用方退回存在性匹配）。
     */
    public static String encodeNameMatcher(String name) {
        if (name == null) return null;
        String s = name.trim();
        if (s.isEmpty()) return null;
        StringBuilder sb = new StringBuilder();
        for (char c : s.toCharArray()) {
            if (c == ' ') sb.append("__");
            else if ((c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z')
                    || (c >= '0' && c <= '9') || c == '_') sb.append(c);
            else return null;
        }
        return sb.toString();
    }


    public static boolean matchesComponent(String matcher, Object stack) {
        return matchesTag(matcher, tagOf(stack));
    }

    /** 纯逻辑入口（可单测）：直接对 NBT 树匹配；tag 为 null = 无 NBT，全部不命中。 */
    public static boolean matchesTag(String matcher, @Nullable CompoundTag tag) {
        ParsedNbt p = parseNbt(matcher);
        if (p == null || tag == null) return false;
        if (p.rootKey().isEmpty()) {
            // custom_data：整棵树，selector=根下路径
            if (p.selector().isEmpty()) return !tag.getAllKeys().isEmpty();
            return matchPath(tag, p.selector());
        }
        if (!tag.contains(p.rootKey())) return false;
        if (p.selector().isEmpty()) return true; // v1 存在性
        Tag value = tag.get(p.rootKey());
        return matchValue(p.componentId(), p.rootKey(), value, p.selector());
    }

    /** 物品/流体的原生 NBT；无则 null。 */
    private static @Nullable CompoundTag tagOf(Object stack) {
        if (stack instanceof ItemStack is) {
            if (is.isEmpty()) return null;
            return is.getTag();
        }
        if (stack instanceof FluidStack fs) {
            if (fs.isEmpty()) return null;
            return fs.getTag();
        }
        return null;
    }

    /** 组件"存在且非默认"判定（选择器枚举与 UI 复用）：根键存在于 NBT。 */
    public static boolean hasNonDefault(Object stack, String componentId) {
        CompoundTag tag = tagOf(stack);
        String rootKey = rootKey(componentId);
        if (tag == null) return false;
        if (rootKey.isEmpty()) return !tag.getAllKeys().isEmpty();
        if (rootKey.equals("display")) {
            return tag.contains("display", net.minecraft.nbt.CompoundTag.TAG_COMPOUND)
                    && tag.getCompound("display").contains("Name");
        }
        return tag.contains(rootKey);
    }

    /** 该物品 NBT 全部"非默认根键"（选择器枚举用），display 展开为 minecraft:custom_name。 */
    public static List<String> nonDefaultComponentIds(Object stack) {
        List<String> out = new ArrayList<>();
        CompoundTag tag = tagOf(stack);
        if (tag == null) return out;
        for (String key : tag.getAllKeys()) {
            out.add(componentIdOf(key, tag));
        }
        return out;
    }

    /** NBT 根键 → 组件 id（选择器显示用；display.Name 特判为 custom_name）。 */
    public static String componentIdOf(String rootKey, CompoundTag tag) {
        return switch (rootKey) {
            case "ench" -> "minecraft:enchantments";
            case "StoredEnchantments" -> "minecraft:stored_enchantments";
            case "Potion" -> "minecraft:potion_contents";
            case "Damage" -> "minecraft:damage";
            case "RepairCost" -> "minecraft:repair_cost";
            case "display" -> tag.getCompound("display").contains("Name")
                    ? "minecraft:custom_name" : rootKey;
            default -> "minecraft:" + rootKey;
        };
    }

    private static boolean matchValue(String componentId, String rootKey, Tag value, List<String> selector) {
        String sel0 = selector.get(0);
        String extra = selector.size() > 1 ? selector.get(1) : null;
        // 附魔类：value=列表 [{id:"ns:name", lvl:int}]；selector=附魔 id，尾段=精确等级
        if (value instanceof net.minecraft.nbt.ListTag list && (rootKey.equals("ench") || rootKey.equals("StoredEnchantments"))) {
            String want = decodeId(sel0);
            for (int i = 0; i < list.size(); i++) {
                CompoundTag e = list.getCompound(i);
                String id = e.getString("id");
                if (id.isEmpty()) continue;
                if (!id.equals(want)) continue;
                if (extra == null) return true;
                try {
                    return Integer.parseInt(extra) == e.getInt("lvl");
                } catch (NumberFormatException ignored) {
                    return true;
                }
            }
            return false;
        }
        // 药水：value=字符串注册名；selector=药水 id（__ 编码）
        if (rootKey.equals("Potion") && value instanceof StringTag potion) {
            return decodeId(sel0).equals(potion.getAsString());
        }
        // 显示名：display.Name 是 JSON 文本组件串；*通配*子串匹配（剥出纯文本后）
        if (rootKey.equals("display")) {
            CompoundTag display = value instanceof CompoundTag c ? c : null;
            if (display == null || !display.contains("Name")) return false;
            return wildcardMatches(sel0.replace("__", " "), stripJsonText(display.getString("Name")));
        }
        // custom_data / 通用根键：多级点路径 + 算子
        if (value instanceof CompoundTag branch) {
            return matchPath(branch, selector);
        }
        // 叶子根键 + 比较算子（gt/ge/lt/le/eq+数字或字符串）
        if (isOperator(sel0)) {
            return compare(value, sel0);
        }
        // 数值根键（Damage/RepairCost/…）：selector 本身是比较算子
        if (value instanceof NumericTag) {
            return compareNumber(((NumericTag) value).getAsDouble(), sel0);
        }
        if (value instanceof StringTag s) {
            return wildcardMatches(sel0.replace("__", " "), s.getAsString());
        }
        return false;
    }

    /** custom_data/通用：selector[0..]=路径段（__ 解码为点），尾段可为算子。 */
    private static boolean matchPath(CompoundTag root, List<String> selector) {
        StringBuilder path = new StringBuilder();
        String op = null;
        for (int i = 0; i < selector.size(); i++) {
            String seg = selector.get(i);
            if (i > 0 && isOperator(seg)) { op = seg; break; }
            if (i > 0) path.append(".");
            path.append(seg.replace("__", "."));
        }
        Tag leaf = walk(root, path.toString());
        if (leaf == null) return false;
        if (op == null) return true;
        return compare(leaf, op);
    }

    /** 选择器 id 解码：ns__path → ns:path；无 __ 时补 minecraft: 前缀。 */
    private static String decodeId(String sel) {
        int idx = sel.indexOf("__");
        if (idx > 0) return sel.substring(0, idx) + ":" + sel.substring(idx + 2);
        return "minecraft:" + sel;
    }

    private static boolean isOperator(String s) {
        return s.startsWith("gt") || s.startsWith("ge") || s.startsWith("lt")
                || s.startsWith("le") || s.startsWith("eq");
    }

    private static @Nullable Tag walk(CompoundTag root, String dotPath) {
        Tag cur = root;
        for (String part : dotPath.split("\\.")) {
            if (!(cur instanceof CompoundTag c)) return null;
            cur = c.get(part);
            if (cur == null) return null;
        }
        return cur;
    }

    /** 算子比较：gt/ge/lt/le/eq + 数字；字符串值走 eq 精确匹配。 */
    private static boolean compare(Tag leaf, String op) {
        if (leaf instanceof NumericTag n) {
            return compareNumber(n.getAsDouble(), op);
        }
        if (leaf instanceof StringTag s) {
            return op.startsWith("eq") && s.getAsString().equals(op.substring(2));
        }
        return false; // 复合标签比较不支持，保持子键路径判定
    }

    private static boolean compareNumber(double v, String op) {
        String name;
        double target;
        if (op.startsWith("gt")) name = "gt";
        else if (op.startsWith("ge")) name = "ge";
        else if (op.startsWith("lt")) name = "lt";
        else if (op.startsWith("le")) name = "le";
        else if (op.startsWith("eq")) name = "eq";
        else return false;
        String num = op.substring(name.length());
        try {
            target = Double.parseDouble(num);
        } catch (NumberFormatException ignored) {
            return false;
        }
        return switch (name) {
            case "gt" -> v > target;
            case "ge" -> v >= target;
            case "lt" -> v < target;
            case "le" -> v <= target;
            default -> Math.abs(v - target) < 1e-9;
        };
    }

    /** JSON 文本组件 → 纯文本：优先剥 text 字段，无则去引号。 */
    public static String stripJsonText(String json) {
        int idx = json.indexOf("\"text\"");
        if (idx >= 0) {
            int q1 = json.indexOf('"', json.indexOf(':', idx) + 1);
            if (q1 >= 0) {
                int q2 = json.indexOf('"', q1 + 1);
                while (q2 > 0 && json.charAt(q2 - 1) == '\\') q2 = json.indexOf('"', q2 + 1);
                if (q2 > q1) return json.substring(q1 + 1, q2);
            }
        }
        return json.replace("\"", "");
    }

    /** *通配*：星号=任意段，其余字面量；全小写比较。 */
    public static boolean wildcardMatches(String pattern, String text) {
        String p = pattern.toLowerCase(Locale.ROOT);
        String t = text.toLowerCase(Locale.ROOT);
        if (!p.contains("*")) return t.contains(p);
        String[] parts = p.split("\\*", -1);
        StringBuilder rx = new StringBuilder();
        for (int i = 0; i < parts.length; i++) {
            if (i > 0) rx.append(".*");
            rx.append(Pattern.quote(parts[i]));
        }
        return t.matches(rx.toString());
    }
}
