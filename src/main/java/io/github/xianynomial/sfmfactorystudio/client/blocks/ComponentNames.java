package io.github.xianynomial.sfmfactorystudio.client.blocks;

import net.minecraft.core.component.DataComponentType;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.alchemy.PotionContents;
import net.minecraft.world.item.enchantment.ItemEnchantments;

import java.util.Map;

/**
 * 数据组件（= 1.21 的 NBT）的中文显示名与值预览。
 *
 * 枚举与显示都是注册表驱动的：模组注册的自定义组件类型一样会被列出；
 * 未收录的组件名走"组件(原始id)"兜底，值预览走 toString 截断兜底。
 */
public final class ComponentNames {
    private static final Map<String, String> ZH = Map.ofEntries(
            Map.entry("minecraft:enchantments", "附魔"),
            Map.entry("minecraft:stored_enchantments", "附魔书附魔"),
            Map.entry("minecraft:custom_data", "自定义数据"),
            Map.entry("minecraft:custom_name", "自定义名称"),
            Map.entry("minecraft:item_name", "物品名称"),
            Map.entry("minecraft:potion_contents", "药水内容"),
            Map.entry("minecraft:damage", "损耗值"),
            Map.entry("minecraft:max_damage", "最大耐久"),
            Map.entry("minecraft:unbreakable", "不可破坏"),
            Map.entry("minecraft:trim", "盔甲纹饰"),
            Map.entry("minecraft:attribute_modifiers", "属性修饰符"),
            Map.entry("minecraft:enchantment_glint_override", "附魔光效"),
            Map.entry("minecraft:repair_cost", "修复费用"),
            Map.entry("minecraft:lore", "物品描述"),
            Map.entry("minecraft:custom_model_data", "自定义模型数据"),
            Map.entry("minecraft:can_place_on", "可放置于"),
            Map.entry("minecraft:can_break", "可破坏方块"),
            Map.entry("minecraft:hide_additional_tooltip", "隐藏附加提示"),
            Map.entry("minecraft:hide_tooltip", "隐藏提示"),
            Map.entry("minecraft:fire_resistant", "防火"),
            Map.entry("minecraft:rarity", "稀有度"),
            Map.entry("minecraft:max_stack_size", "最大堆叠"));

    private ComponentNames() {
    }

    /** 组件 id → 中文名；未收录返回 null。 */
    public static String nameOf(String componentId) {
        return ZH.get(componentId);
    }

    /** 展示名：中文名（已知）或"组件 ns:path"。 */
    public static String display(String componentId) {
        String zh = ZH.get(componentId);
        return zh != null ? zh : "组件 " + componentId;
    }

    /** 值预览：附魔/名称/数字/药水特判，其余 toString 截断。 */
    public static String preview(DataComponentType<?> type, ItemStack stack) {
        Object value = stack.getComponents().get(type);
        if (value == null) return "";
        if (value instanceof ItemEnchantments ench) {
            StringBuilder sb = new StringBuilder();
            for (Map.Entry<net.minecraft.core.Holder<net.minecraft.world.item.enchantment.Enchantment>, Integer> e
                    : ench.entrySet()) {
                var key = e.getKey().unwrapKey();
                if (key.isEmpty()) continue;
                var loc = key.get().location();
                String name = Component.translatable(
                        "enchantment." + loc.getNamespace() + "." + loc.getPath()).getString();
                if (!sb.isEmpty()) sb.append("、");
                sb.append(name).append(' ').append(e.getValue());
            }
            return sb.toString();
        }
        if (value instanceof net.minecraft.network.chat.Component text) {
            return text.getString();
        }
        if (value instanceof Boolean b) return b ? "是" : "否";
        if (value instanceof Number n) return String.valueOf(n);
        if (value instanceof PotionContents pc) {
            return pc.potion().map(h -> {
                var k = net.minecraft.core.registries.BuiltInRegistries.POTION.getKey(h.value());
                return k == null ? "" : k.toString();
            }).orElse("");
        }
        String s = String.valueOf(value).replace('\n', ' ');
        return s.length() > 24 ? s.substring(0, 24) + "…" : s;
    }
}
