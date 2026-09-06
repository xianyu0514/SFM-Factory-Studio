package io.github.xianynomial.sfmfactorystudio.client.blocks;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.StringTag;
import net.minecraft.nbt.Tag;
import net.minecraft.nbt.NumericTag;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.enchantment.Enchantment;

import io.github.xianynomial.sfmfactorystudio.client.Loc;
import net.minecraftforge.registries.ForgeRegistries;

import java.util.Map;

/**
 * 1.20.1 原生 NBT 键（= 1.21 的数据组件）的中文显示名与值预览。
 *
 * 枚举与显示都以 NBT 根键驱动：Forge 模组写在物品 tag 根下的自定义键一样会被
 * 列出；未收录的键走「NBT 键名」兜底，值预览走子键清单/toString 截断兜底。
 */
public final class ComponentNames {
    private static final Map<String, Loc> ZH = Map.ofEntries(
            Map.entry("minecraft:enchantments", new Loc("gui.sfmfactorystudio.nbtcn.enchantments", "附魔")),
            Map.entry("minecraft:stored_enchantments", new Loc("gui.sfmfactorystudio.nbtcn.stored_enchantments", "附魔书附魔")),
            Map.entry("minecraft:custom_data", new Loc("gui.sfmfactorystudio.nbtcn.custom_data", "自定义数据")),
            Map.entry("minecraft:custom_name", new Loc("gui.sfmfactorystudio.nbtcn.custom_name", "自定义名称")),
            Map.entry("minecraft:item_name", new Loc("gui.sfmfactorystudio.nbtcn.item_name", "物品名称")),
            Map.entry("minecraft:potion_contents", new Loc("gui.sfmfactorystudio.nbtcn.potion", "药水")),
            Map.entry("minecraft:damage", new Loc("gui.sfmfactorystudio.nbtcn.damage", "损耗值")),
            Map.entry("minecraft:repair_cost", new Loc("gui.sfmfactorystudio.nbtcn.repair_cost", "修复费用")),
            Map.entry("display", new Loc("gui.sfmfactorystudio.nbtcn.display_info", "显示信息")),
            Map.entry("Unbreakable", new Loc("gui.sfmfactorystudio.nbtcn.unbreakable", "不可破坏")),
            Map.entry("AttributeModifiers", new Loc("gui.sfmfactorystudio.nbtcn.attribute_modifiers", "属性修饰符")),
            Map.entry("HideFlags", new Loc("gui.sfmfactorystudio.nbtcn.hidden_flags", "隐藏提示")),
            Map.entry("SkullOwner", new Loc("gui.sfmfactorystudio.nbtcn.skull_owner", "玩家头颅")),
            Map.entry("BlockEntityTag", new Loc("gui.sfmfactorystudio.nbtcn.block_entity_data", "方块实体数据")),
            Map.entry("ChargedProjectiles", new Loc("gui.sfmfactorystudio.nbtcn.charged_projectiles", "弩箭")),
            Map.entry("Fireworks", new Loc("gui.sfmfactorystudio.nbtcn.fireworks", "烟花")),
            Map.entry("BucketVariantTag", new Loc("gui.sfmfactorystudio.nbtcn.bucket_variant", "桶变体")),
            Map.entry("pages", new Loc("gui.sfmfactorystudio.nbtcn.book_pages", "成书页面")));

    private ComponentNames() {
    }

    /** 组件 id → 当前语言显示名（Loc）；未收录返回 null。 */
    public static Loc nameLoc(String componentId) {
        return ZH.get(componentId);
    }

    /** 展示名：跟随游戏语言；未知模组键直接显示键名（键名本身即英文标识符）。 */
    public static String display(String componentId) {
        Loc loc = ZH.get(componentId);
        if (loc != null) return loc.getString();
        return componentId.startsWith("minecraft:")
                ? componentId.substring(componentId.indexOf(':') + 1) : componentId;
    }

    /** 值预览：附魔/药水/名称/数字/复合键清单特判，其余 toString 截断。 */
    public static String preview(String componentId, ItemStack stack) {
        CompoundTag tag = stack.getTag();
        if (tag == null) return "";
        String rootKey = switch (componentId) {
            case "minecraft:enchantments" -> "ench";
            case "minecraft:stored_enchantments" -> "StoredEnchantments";
            case "minecraft:potion_contents" -> "Potion";
            case "minecraft:damage" -> "Damage";
            case "minecraft:repair_cost" -> "RepairCost";
            case "minecraft:custom_name", "minecraft:item_name" -> "display";
            case "minecraft:custom_data" -> "";
            default -> componentId.startsWith("minecraft:")
                    ? componentId.substring(componentId.indexOf(':') + 1) : componentId;
        };
        if (rootKey.equals("ench") || rootKey.equals("StoredEnchantments")) {
            ListTag list = tag.getList(rootKey, Tag.TAG_COMPOUND);
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < list.size(); i++) {
                CompoundTag e = list.getCompound(i);
                ResourceLocation loc = ResourceLocation.tryParse(e.getString("id"));
                String name = loc == null ? e.getString("id")
                        : Component.translatable("enchantment." + loc.getNamespace() + "." + loc.getPath()).getString();
                if (!sb.isEmpty()) sb.append("、");
                sb.append(name).append(' ').append(e.getInt("lvl"));
            }
            return clip(sb.toString());
        }
        if (rootKey.equals("Potion")) {
            String potion = tag.getString("Potion");
            if (potion.isEmpty()) return "";
            ResourceLocation loc = ResourceLocation.tryParse(potion);
            return loc == null ? potion
                    : Component.translatable("item.minecraft.potion.effect." + loc.getPath()).getString();
        }
        if (rootKey.equals("display")) {
            CompoundTag display = tag.getCompound("display");
            if (!display.contains("Name")) {
                String lore = display.getString("Lore");
                return clip(lore.isEmpty() ? display.getAllKeys().toString() : lore);
            }
            String json = display.getString("Name");
            // Name 是 JSON 文本组件：剥出 text 字段或引号原文，避免整串 JSON 进 UI
            int idx = json.indexOf("\"text\"");
            if (idx >= 0) {
                int q1 = json.indexOf('"', json.indexOf(':', idx) + 1);
                if (q1 >= 0) {
                    int q2 = json.indexOf('"', q1 + 1);
                    while (q2 > 0 && json.charAt(q2 - 1) == '\\') q2 = json.indexOf('"', q2 + 1);
                    if (q2 > q1) return clip(json.substring(q1 + 1, q2));
                }
            }
            return clip(json.replace("\"", ""));
        }
        Tag value = rootKey.isEmpty() ? tag : tag.get(rootKey);
        if (value == null) return "";
        if (value instanceof NumericTag n) return String.valueOf(n.getAsDouble());
        if (value instanceof StringTag s) return clip(s.getAsString());
        if (value instanceof CompoundTag c) return clip(String.join("、", c.getAllKeys()));
        return clip(value.toString());
    }

    private static String clip(String s) {
        String out = s.replace('\n', ' ');
        return out.length() > 24 ? out.substring(0, 24) + "…" : out;
    }
}
