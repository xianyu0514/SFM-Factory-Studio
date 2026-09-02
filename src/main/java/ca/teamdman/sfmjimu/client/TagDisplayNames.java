package ca.teamdman.sfmjimu.client;

import net.minecraft.resources.ResourceLocation;
import net.neoforged.fml.ModList;

import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Deterministic Chinese display names for tag ids. Raw ids remain the source of truth. */
public final class TagDisplayNames {
    private static final Map<String, String> EXACT = Map.ofEntries(
            Map.entry("c:ingots", "所有金属锭"),
            Map.entry("c:nuggets", "所有金属粒"),
            Map.entry("c:ores", "所有矿石"),
            Map.entry("c:dusts", "所有粉末"),
            Map.entry("c:gems", "所有宝石"),
            Map.entry("c:storage_blocks", "所有储存方块"),
            Map.entry("minecraft:logs", "所有原木"),
            Map.entry("minecraft:planks", "所有木板"),
            Map.entry("minecraft:wool", "所有羊毛"),
            Map.entry("minecraft:coals", "所有煤炭燃料"),
            Map.entry("minecraft:mineable/axe", "可用斧挖掘"),
            Map.entry("minecraft:mineable/pickaxe", "可用镐挖掘"),
            Map.entry("minecraft:mineable/shovel", "可用锹挖掘"),
            Map.entry("minecraft:mineable/hoe", "可用锄挖掘"),
            Map.entry("minecraft:beacon_payment_items", "信标可用材料"),
            Map.entry("minecraft:iron_ores", "铁矿石"),
            Map.entry("minecraft:incorrect_for_gold_tool", "金制工具无法正确采掘"),
            Map.entry("minecraft:incorrect_for_wooden_tool", "木制工具无法正确采掘"),
            Map.entry("minecraft:needs_stone_tool", "需要石制或更好的工具"),
            Map.entry("minecraft:overworld_carver_replaceables", "主世界洞穴可替换方块"),
            Map.entry("minecraft:snaps_goat_horn", "会折断山羊角"),
            Map.entry("c:mineable/paxel", "可用多功能工具挖掘"),
            Map.entry("c:ore_rates/singular", "单份掉落矿石"),
            Map.entry("c:ores_in_ground/stone", "生成在石头中的矿石"),
            Map.entry("silentgear:mineable/paxel", "可用多功能工具挖掘"),
            Map.entry("silentgear:mineable/pickaxe_with_spoon", "可用勺镐挖掘"),
            Map.entry("silentgear:prospector_hammer_targets", "勘探锤可探测的方块")
    );

    private static final Map<String, String> CATEGORY = Map.ofEntries(
            Map.entry("ingots", "锭"), Map.entry("nuggets", "粒"),
            Map.entry("ores", "矿石"), Map.entry("raw_materials", "粗矿"),
            Map.entry("storage_blocks", "储存方块"), Map.entry("dusts", "粉末"),
            Map.entry("gems", "宝石"), Map.entry("plates", "板"),
            Map.entry("gears", "齿轮"), Map.entry("rods", "杆"),
            Map.entry("wires", "线缆"), Map.entry("tools", "工具"),
            Map.entry("armors", "盔甲"), Map.entry("seeds", "种子"),
            Map.entry("crops", "作物")
    );

    private static final Map<String, String> MATERIAL = Map.ofEntries(
            Map.entry("iron", "铁"), Map.entry("gold", "金"), Map.entry("copper", "铜"),
            Map.entry("tin", "锡"), Map.entry("lead", "铅"), Map.entry("silver", "银"),
            Map.entry("nickel", "镍"), Map.entry("aluminum", "铝"), Map.entry("aluminium", "铝"),
            Map.entry("uranium", "铀"), Map.entry("osmium", "锇"), Map.entry("zinc", "锌"),
            Map.entry("bronze", "青铜"), Map.entry("steel", "钢"),
            Map.entry("netherite", "下界合金"), Map.entry("diamond", "钻石"),
            Map.entry("emerald", "绿宝石"), Map.entry("quartz", "石英"),
            Map.entry("coal", "煤"), Map.entry("redstone", "红石"), Map.entry("lapis", "青金石"),
            Map.entry("stone", "石头"), Map.entry("deepslate", "深板岩"),
            Map.entry("wooden", "木制"), Map.entry("wood", "木制"), Map.entry("golden", "金制")
    );

    private static final Map<String, String> TOOL = Map.ofEntries(
            Map.entry("axe", "斧"), Map.entry("pickaxe", "镐"), Map.entry("shovel", "锹"),
            Map.entry("hoe", "锄"), Map.entry("paxel", "多功能工具"),
            Map.entry("pickaxe_with_spoon", "勺镐"), Map.entry("hammer", "锤")
    );

    private static final Map<String, String> RATE = Map.ofEntries(
            Map.entry("singular", "单份"), Map.entry("sparse", "少量"),
            Map.entry("dense", "富集"), Map.entry("poor", "贫瘠")
    );

    private static final Pattern INCORRECT_TOOL = Pattern.compile("incorrect_for_(.+)_tool");
    private static final Pattern NEEDS_TOOL = Pattern.compile("needs_(.+)_tool");
    private static final Pattern ORES = Pattern.compile("(.+)_ores");
    private static final Pattern SNAPS = Pattern.compile("snaps_(.+)");
    private static final Pattern TARGETS = Pattern.compile("(.+)_targets");

    private TagDisplayNames() {
    }

    public static String display(ResourceLocation id, List<String> examples) {
        String friendly = friendlyName(id);
        if (friendly != null) return friendly;

        // Compatibility tags often have opaque internal English names. A real
        // translated example explains their effect better than echoing that id.
        if (examples != null && examples.size() == 1) return examples.get(0) + "类";
        if (examples != null && !examples.isEmpty()) return "包含“" + examples.get(0) + "”等的分类";
        return "未命名的模组分类";
    }

    /** True when the title describes the tag's purpose rather than using an example fallback. */
    public static boolean hasFriendlyName(ResourceLocation id) {
        return friendlyName(id) != null;
    }

    /** Tags useful for sorting/transferring resources, excluding mining-rule internals. */
    public static boolean isResourceCategory(ResourceLocation id) {
        String path = id.getPath();
        if (path.startsWith("mineable/") || path.startsWith("incorrect_for_")
                || path.startsWith("needs_") || path.startsWith("snaps_")
                || path.contains("carver_replaceable") || path.endsWith("_targets")) {
            return false;
        }
        String first = path.split("/", 2)[0];
        if (CATEGORY.containsKey(first) || first.equals("ores_in_ground") || first.equals("ore_rates")) {
            return true;
        }
        return ORES.matcher(path).matches() || EXACT.containsKey(id.toString());
    }

    /** Source is deliberately separate from the purpose/title in the picker UI. */
    public static String sourceName(ResourceLocation id) {
        return switch (id.getNamespace()) {
            case "c" -> "通用";
            case "minecraft" -> "原版";
            case "forge" -> "Forge兼容";
            default -> ModList.get().getModContainerById(id.getNamespace())
                    .map(c -> c.getModInfo().getDisplayName())
                    .orElse("模组");
        };
    }

    private static String friendlyName(ResourceLocation id) {
        String exact = EXACT.get(id.toString());
        if (exact != null) return exact;

        String[] parts = id.getPath().split("/");
        if (parts.length == 2) {
            String category = CATEGORY.get(parts[0]);
            String material = MATERIAL.get(parts[1]);
            if (category != null && material != null) return material + category + "类";

            if (parts[0].equals("mineable")) {
                String tool = TOOL.get(parts[1]);
                if (tool != null) return "可用" + tool + "挖掘";
            }
            if (parts[0].equals("ores_in_ground")) {
                String ground = MATERIAL.get(parts[1]);
                if (ground != null) return "生成在" + ground + "中的矿石";
            }
            if (parts[0].equals("ore_rates")) {
                String rate = RATE.get(parts[1]);
                if (rate != null) return rate + "掉落矿石";
            }
        }
        if (parts.length == 1 && CATEGORY.containsKey(parts[0])) {
            return "所有" + CATEGORY.get(parts[0]);
        }

        if (parts.length == 1) {
            String path = parts[0];
            Matcher matcher = INCORRECT_TOOL.matcher(path);
            if (matcher.matches()) {
                String tier = materialWord(matcher.group(1));
                if (tier != null) return tier + "工具无法正确采掘";
            }
            matcher = NEEDS_TOOL.matcher(path);
            if (matcher.matches()) {
                String tier = materialWord(matcher.group(1));
                if (tier != null) return "需要" + tier + "或更好的工具";
            }
            matcher = ORES.matcher(path);
            if (matcher.matches()) {
                String material = materialWord(matcher.group(1));
                if (material != null) return material + "矿石";
            }
            matcher = SNAPS.matcher(path);
            if (matcher.matches() && matcher.group(1).equals("goat_horn")) return "会折断山羊角";
            matcher = TARGETS.matcher(path);
            if (matcher.matches()) {
                String subject = switch (matcher.group(1)) {
                    case "prospector_hammer" -> "勘探锤";
                    case "hammer" -> "锤";
                    default -> null;
                };
                if (subject != null) return subject + "可作用的方块";
            }
        }
        return null;
    }

    private static String materialWord(String raw) {
        String value = MATERIAL.get(raw);
        if (value != null) return value;
        return switch (raw) {
            case "gold" -> "金制";
            case "stone" -> "石制";
            case "wood", "wooden" -> "木制";
            default -> null;
        };
    }
}
