package io.github.xianynomial.sfmfactorystudio.client.blocks;

import io.github.xianynomial.sfmfactorystudio.client.Loc;

import net.minecraft.core.component.DataComponentType;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.alchemy.PotionContents;
import net.minecraft.world.item.enchantment.ItemEnchantments;

import java.util.Map;

/**
 * 数据组件（= 1.21 的 NBT）的双语显示名与值预览。
 *
 * 枚举与显示都是注册表驱动的：模组注册的自定义组件类型一样会被列出；
 * 未收录的组件名走"组件(原始id)"兜底，值预览走 toString 截断兜底。
 * 名字映射存 {@link Loc}（中文兜底 + en_us 键），显示时实时取当前语言。
 */
public final class ComponentNames {
    private static final Loc UNKNOWN = new Loc("gui.sfmfactorystudio.nbtcn.unknown_component", "组件 %s");
    private static final Loc YES = new Loc("gui.sfmfactorystudio.nbtcn.yes", "是");
    private static final Loc NO = new Loc("gui.sfmfactorystudio.nbtcn.no", "否");

    private static final Map<String, Loc> NAMES = Map.ofEntries(
            Map.entry("minecraft:enchantments", new Loc("gui.sfmfactorystudio.nbtcn.enchantments", "附魔")),
            Map.entry("minecraft:stored_enchantments", new Loc("gui.sfmfactorystudio.nbtcn.stored_enchantments", "附魔书附魔")),
            Map.entry("minecraft:custom_data", new Loc("gui.sfmfactorystudio.nbtcn.custom_data", "自定义数据")),
            Map.entry("minecraft:custom_name", new Loc("gui.sfmfactorystudio.nbtcn.custom_name", "自定义名称")),
            Map.entry("minecraft:item_name", new Loc("gui.sfmfactorystudio.nbtcn.item_name", "物品名称")),
            Map.entry("minecraft:potion_contents", new Loc("gui.sfmfactorystudio.nbtcn.potion_contents", "药水")),
            Map.entry("minecraft:damage", new Loc("gui.sfmfactorystudio.nbtcn.damage", "损耗值")),
            Map.entry("minecraft:max_damage", new Loc("gui.sfmfactorystudio.nbtcn.max_damage", "最大耐久")),
            Map.entry("minecraft:unbreakable", new Loc("gui.sfmfactorystudio.nbtcn.unbreakable", "不可破坏")),
            Map.entry("minecraft:trim", new Loc("gui.sfmfactorystudio.nbtcn.trim", "盔甲纹饰")),
            Map.entry("minecraft:attribute_modifiers", new Loc("gui.sfmfactorystudio.nbtcn.attribute_modifiers", "属性修饰符")),
            Map.entry("minecraft:enchantment_glint_override", new Loc("gui.sfmfactorystudio.nbtcn.glint_override", "附魔光效")),
            Map.entry("minecraft:repair_cost", new Loc("gui.sfmfactorystudio.nbtcn.repair_cost", "修复费用")),
            Map.entry("minecraft:lore", new Loc("gui.sfmfactorystudio.nbtcn.lore", "物品描述")),
            Map.entry("minecraft:custom_model_data", new Loc("gui.sfmfactorystudio.nbtcn.custom_model_data", "自定义模型数据")),
            Map.entry("minecraft:can_place_on", new Loc("gui.sfmfactorystudio.nbtcn.can_place_on", "可放置于")),
            Map.entry("minecraft:can_break", new Loc("gui.sfmfactorystudio.nbtcn.can_break", "可破坏方块")),
            Map.entry("minecraft:hide_additional_tooltip", new Loc("gui.sfmfactorystudio.nbtcn.hide_additional_tooltip", "隐藏附加提示")),
            Map.entry("minecraft:hide_tooltip", new Loc("gui.sfmfactorystudio.nbtcn.hide_tooltip", "隐藏提示")),
            Map.entry("minecraft:fire_resistant", new Loc("gui.sfmfactorystudio.nbtcn.fire_resistant", "防火")),
            Map.entry("minecraft:rarity", new Loc("gui.sfmfactorystudio.nbtcn.rarity", "稀有度")),
            Map.entry("minecraft:max_stack_size", new Loc("gui.sfmfactorystudio.nbtcn.max_stack_size", "最大堆叠")));

    private ComponentNames() {
    }

    /** 组件 id → 当前语言显示名；未收录返回 null。 */
    public static String nameOf(String componentId) {
        Loc loc = NAMES.get(componentId);
        return loc != null ? loc.getString() : null;
    }

    /** 展示名：已知名或"组件 ns:path"。 */
    public static String display(String componentId) {
        Loc loc = NAMES.get(componentId);
        return loc != null ? loc.getString() : UNKNOWN.getString(componentId);
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
                if (!sb.isEmpty()) sb.append(", ");
                sb.append(name).append(' ').append(e.getValue());
            }
            return sb.toString();
        }
        if (value instanceof net.minecraft.network.chat.Component text) {
            return text.getString();
        }
        if (value instanceof Boolean b) return b ? YES.getString() : NO.getString();
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
