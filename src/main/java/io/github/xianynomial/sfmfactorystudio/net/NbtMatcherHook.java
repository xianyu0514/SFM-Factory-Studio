package io.github.xianynomial.sfmfactorystudio.net;

import ca.teamdman.sfml.ast.TagMatcher;
import ca.teamdman.sfml.ast.WithTag;
import net.minecraft.core.Holder;
import net.minecraft.core.component.DataComponentType;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NumericTag;
import net.minecraft.nbt.StringTag;
import net.minecraft.nbt.Tag;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.alchemy.PotionContents;
import net.minecraft.world.item.enchantment.ItemEnchantments;
import net.neoforged.neoforge.fluids.FluidStack;

import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.regex.Pattern;

/**
 * NBT 伪标签（#nbt:...）的深度值匹配。
 *
 * 线格式：nbt:&lt;ns&gt;/&lt;name&gt;[/&lt;selector&gt;[/&lt;ops&gt;]]
 *  - 组件 id 恒为前两段（ns/name），选择器内部的冒号写成点（minecraft.sharpness）。
 *  - enchantments / stored_enchantments：selector=附魔 id，尾段可附等级（=精确等级）。
 *  - potion_contents：selector=药水 id。
 *  - custom_data：selector=点路径子键，尾段可为 gt/ge/lt/le/eq+数字（数值比较）
 *    或 eq+字符串（精确匹配）；无尾段=子键存在。
 *  - 数值型组件（damage/repair_cost/…）：selector 直接是 gt/ge/lt/le/eq+数字。
 *  - 名称类组件（custom_name/item_name）：selector 支持 *通配*（大小写不敏感）。
 *  - 无 selector（只有组件两段）= 组件存在且非默认（向后兼容 v1）。
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

    /** 服务器是否真正具备 NBT 区分能力（Mixin 已生效）。 */
    public static boolean isAvailable() {
        try {
            return (Object) new WithTag(TagMatcher.fromPath(List.of("sfmfactorystudio_probe")))
                    instanceof NbtAware;
        } catch (Throwable t) {
            return false;
        }
    }

    /** 入口：匹配伪标签（含值选择器）。 */
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
        Parsed p = parse(matcher);
        if (p == null || stack == null) return false;
        ResourceLocation key = ResourceLocation.tryParse(p.componentId());
        if (key == null) return false;
        var type = BuiltInRegistries.DATA_COMPONENT_TYPE.get(key);
        if (type == null) return false;
        Object value = valueOf(stack, type);
        if (value == null) return false;
        if (p.selector().isEmpty()) return true; // v1 存在性
        return matchValue(p.componentId(), value, p.selector());
    }

    private static Object valueOf(Object stack, DataComponentType<?> type) {
        if (stack instanceof ItemStack is) {
            return is.getComponents().get(type) != null
                    && !Objects.equals(is.getComponents().get(type), is.getItem().components().get(type))
                    ? is.getComponents().get(type) : null;
        }
        if (stack instanceof FluidStack fs) {
            for (var e : fs.getComponentsPatch().entrySet()) {
                if (e.getKey() == type) return e.getValue().orElse(null);
            }
        }
        return null;
    }

    /** 组件"非默认"判定（选择器枚举与 UI 复用）。 */
    public static boolean hasNonDefault(Object stack, DataComponentType<?> type) {
        return valueOf(stack, type) != null;
    }

    private static boolean matchValue(String componentId, Object value, List<String> selector) {
        String sel0 = selector.get(0);
        String extra = selector.size() > 1 ? selector.get(1) : null;
        // 附魔类：selector=附魔路径，__编码命名空间（modid__enchant→modid:enchant）
        if (value instanceof ItemEnchantments ench) {
            String want = decodeId(sel0);
            for (Map.Entry<Holder<net.minecraft.world.item.enchantment.Enchantment>, Integer> e
                    : ench.entrySet()) {
                var k = e.getKey().unwrapKey();
                if (k.isEmpty()) continue;
                if (!k.get().location().toString().equals(want)) continue;
                if (extra == null) return true;
                try {
                    return Integer.parseInt(extra) == e.getValue();
                } catch (NumberFormatException ignored) {
                    return true;
                }
            }
            return false;
        }
        // 药水：同 __ 编码
        if (value instanceof PotionContents pc) {
            String want = decodeId(sel0);
            return pc.potion().map(h -> {
                var k = BuiltInRegistries.POTION.getKey(h.value());
                return k != null && k.toString().equals(want);
            }).orElse(false);
        }
        // 名称类：*通配* 子串
        if (value instanceof net.minecraft.network.chat.Component text) {
            return wildcardMatches(sel0.replace("__", " "), text.getString());
        }
        // custom_data：多级路径（/分隔），最后一段可为算子；__ 解码为点号
        if (value instanceof net.minecraft.world.item.component.CustomData customData) {
            CompoundTag tag = customData.copyTag();
            // 收集路径段（非算子的选择器元素），__ 解码为 .
            StringBuilder path = new StringBuilder();
            String op = null;
            for (int i = 0; i < selector.size(); i++) {
                String seg = selector.get(i);
                if (i > 0 && isOperator(seg)) { op = seg; break; }
                if (i > 0) path.append(".");
                path.append(seg.replace("__", "."));
            }
            Tag leaf = walk(tag, path.toString());
            if (leaf == null) return false;
            if (op == null) return true;
            return compare(leaf, op);
        }
        // 数值型组件：selector 本身是比较算子
        if (value instanceof Number || value instanceof NumericTag) {
            return compareNumber(toDouble(value), sel0);
        }
        // 其他类型：字符串形式通配匹配
        return wildcardMatches(sel0.replace("__", " "), String.valueOf(value));
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
        if (leaf instanceof CompoundTag c) {
            return false; // 复合标签比较不支持，保持子键路径判定
        }
        return false;
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

    private static double toDouble(Object v) {
        if (v instanceof Number n) return n.doubleValue();
        if (v instanceof NumericTag t) return t.getAsDouble();
        return Double.NaN;
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
