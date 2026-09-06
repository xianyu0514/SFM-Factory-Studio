package io.github.xianynomial.sfmfactorystudio.client;

import net.minecraft.resources.ResourceLocation;
import net.minecraftforge.fml.ModList;

import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Deterministic friendly display names for tag ids — bilingual now: every word
 * map holds a {@link Loc} (Chinese fallback, en_us key), and the composition
 * patterns themselves are Loc format strings, so e.g. 铁锭类 ↔ "Iron ingots".
 * Raw ids remain the source of truth.
 */
public final class TagDisplayNames {
    private static final String K = "gui.sfmfactorystudio.tagname.";

    private static Loc t(String name, String zh) {
        return new Loc(K + name, zh);
    }

    private static final Map<String, Loc> EXACT = Map.ofEntries(
            Map.entry("c:ingots", t("all_ingots", "所有金属锭")),
            Map.entry("c:nuggets", t("all_nuggets", "所有金属粒")),
            Map.entry("c:ores", t("all_ores", "所有矿石")),
            Map.entry("c:dusts", t("all_dusts", "所有粉末")),
            Map.entry("c:gems", t("all_gems", "所有宝石")),
            Map.entry("c:storage_blocks", t("all_storage_blocks", "所有储存方块")),
            Map.entry("minecraft:logs", t("all_logs", "所有原木")),
            Map.entry("minecraft:planks", t("all_planks", "所有木板")),
            Map.entry("minecraft:wool", t("all_wool", "所有羊毛")),
            Map.entry("minecraft:coals", t("all_coals", "所有煤炭燃料")),
            Map.entry("minecraft:mineable/axe", t("mineable_axe", "可用斧挖掘")),
            Map.entry("minecraft:mineable/pickaxe", t("mineable_pickaxe", "可用镐挖掘")),
            Map.entry("minecraft:mineable/shovel", t("mineable_shovel", "可用锹挖掘")),
            Map.entry("minecraft:mineable/hoe", t("mineable_hoe", "可用锄挖掘")),
            Map.entry("minecraft:beacon_payment_items", t("beacon_payment", "信标可用材料")),
            Map.entry("minecraft:iron_ores", t("iron_ores", "铁矿石")),
            Map.entry("minecraft:incorrect_for_gold_tool", t("incorrect_gold", "金制工具无法正确采掘")),
            Map.entry("minecraft:incorrect_for_wooden_tool", t("incorrect_wood", "木制工具无法正确采掘")),
            Map.entry("minecraft:needs_stone_tool", t("needs_stone", "需要石制或更好的工具")),
            Map.entry("minecraft:overworld_carver_replaceables", t("carver_replaceables", "主世界洞穴可替换方块")),
            Map.entry("minecraft:snaps_goat_horn", t("snaps_goat_horn", "会折断山羊角")),
            Map.entry("c:mineable/paxel", t("mineable_paxel", "可用多功能工具挖掘")),
            Map.entry("c:ore_rates/singular", t("rate_singular", "单份掉落矿石")),
            Map.entry("c:ores_in_ground/stone", t("ground_stone", "生成在石头中的矿石")),
            Map.entry("silentgear:mineable/paxel", t("mineable_paxel", "可用多功能工具挖掘")),
            Map.entry("silentgear:mineable/pickaxe_with_spoon", t("mineable_spoon_pickaxe", "可用勺镐挖掘")),
            Map.entry("silentgear:prospector_hammer_targets", t("prospector_targets", "勘探锤可探测的方块"))
    );

    private static final Map<String, Loc> CATEGORY = Map.ofEntries(
            Map.entry("ingots", t("cat_ingots", "锭")), Map.entry("nuggets", t("cat_nuggets", "粒")),
            Map.entry("ores", t("cat_ores", "矿石")), Map.entry("raw_materials", t("cat_raw", "粗矿")),
            Map.entry("storage_blocks", t("cat_storage", "储存方块")), Map.entry("dusts", t("cat_dusts", "粉末")),
            Map.entry("gems", t("cat_gems", "宝石")), Map.entry("plates", t("cat_plates", "板")),
            Map.entry("gears", t("cat_gears", "齿轮")), Map.entry("rods", t("cat_rods", "杆")),
            Map.entry("wires", t("cat_wires", "线缆")), Map.entry("tools", t("cat_tools", "工具")),
            Map.entry("armors", t("cat_armors", "盔甲")), Map.entry("seeds", t("cat_seeds", "种子")),
            Map.entry("crops", t("cat_crops", "作物"))
    );

    private static final Map<String, Loc> MATERIAL = Map.ofEntries(
            Map.entry("iron", t("mat_iron", "铁")), Map.entry("gold", t("mat_gold", "金")),
            Map.entry("copper", t("mat_copper", "铜")),
            Map.entry("tin", t("mat_tin", "锡")), Map.entry("lead", t("mat_lead", "铅")),
            Map.entry("silver", t("mat_silver", "银")),
            Map.entry("nickel", t("mat_nickel", "镍")), Map.entry("aluminum", t("mat_aluminum", "铝")),
            Map.entry("aluminium", t("mat_aluminum", "铝")),
            Map.entry("uranium", t("mat_uranium", "铀")), Map.entry("osmium", t("mat_osmium", "锇")),
            Map.entry("zinc", t("mat_zinc", "锌")),
            Map.entry("bronze", t("mat_bronze", "青铜")), Map.entry("steel", t("mat_steel", "钢")),
            Map.entry("netherite", t("mat_netherite", "下界合金")), Map.entry("diamond", t("mat_diamond", "钻石")),
            Map.entry("emerald", t("mat_emerald", "绿宝石")), Map.entry("quartz", t("mat_quartz", "石英")),
            Map.entry("coal", t("mat_coal", "煤")), Map.entry("redstone", t("mat_redstone", "红石")),
            Map.entry("lapis", t("mat_lapis", "青金石")),
            Map.entry("stone", t("mat_stone", "石头")), Map.entry("deepslate", t("mat_deepslate", "深板岩")),
            Map.entry("wooden", t("mat_wood", "木制")), Map.entry("wood", t("mat_wood", "木制")),
            Map.entry("golden", t("mat_golden", "金制"))
    );

    private static final Map<String, Loc> TOOL = Map.ofEntries(
            Map.entry("axe", t("tool_axe", "斧")), Map.entry("pickaxe", t("tool_pickaxe", "镐")),
            Map.entry("shovel", t("tool_shovel", "锹")),
            Map.entry("hoe", t("tool_hoe", "锄")), Map.entry("paxel", t("tool_paxel", "多功能工具")),
            Map.entry("pickaxe_with_spoon", t("tool_spoon_pickaxe", "勺镐")),
            Map.entry("hammer", t("tool_hammer", "锤"))
    );

    private static final Map<String, Loc> RATE = Map.ofEntries(
            Map.entry("singular", t("rate_singular_word", "单份")), Map.entry("sparse", t("rate_sparse_word", "少量")),
            Map.entry("dense", t("rate_dense_word", "富集")), Map.entry("poor", t("rate_poor_word", "贫瘠"))
    );

    // 组合格式串：各语言的语序不同，如 铁锭类 ↔ "Iron ingots"
    private static final Loc F_MAT_CAT = t("f_mat_cat", "%s%s类");
    private static final Loc F_MINEABLE = t("f_mineable", "可用%s挖掘");
    private static final Loc F_IN_GROUND = t("f_in_ground", "生成在%s中的矿石");
    private static final Loc F_RATE_DROPS = t("f_rate_drops", "%s掉落矿石");
    private static final Loc F_ALL = t("f_all", "所有%s");
    private static final Loc F_INCORRECT = t("f_incorrect", "%s工具无法正确采掘");
    private static final Loc F_NEEDS = t("f_needs", "需要%s或更好的工具");
    private static final Loc F_ORES = t("f_ores", "%s矿石");
    private static final Loc F_TARGETS = t("f_targets", "%s可作用的方块");
    private static final Loc F_EXAMPLE_CAT = t("f_example_cat", "%s类");
    private static final Loc F_CONTAINS = t("f_contains", "包含“%s”等的分类");
    private static final Loc T_UNNAMED = t("unnamed", "未命名的模组分类");
    private static final Loc T_SUBJECT_HAMMER = t("subject_hammer", "锤");
    private static final Loc T_SUBJECT_PROSPECTOR = t("subject_prospector", "勘探锤");
    private static final Loc T_SRC_COMMON = t("src_common", "通用");
    private static final Loc T_SRC_VANILLA = t("src_vanilla", "原版");
    private static final Loc T_SRC_FORGE = t("src_forge", "Forge兼容");
    private static final Loc T_SRC_MOD = t("src_mod", "模组");

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
        if (examples != null && examples.size() == 1) return F_EXAMPLE_CAT.getString(examples.get(0));
        if (examples != null && !examples.isEmpty()) return F_CONTAINS.getString(examples.get(0));
        return T_UNNAMED.getString();
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
            case "c" -> T_SRC_COMMON.getString();
            case "minecraft" -> T_SRC_VANILLA.getString();
            case "forge" -> T_SRC_FORGE.getString();
            default -> ModList.get().getModContainerById(id.getNamespace())
                    .map(c -> c.getModInfo().getDisplayName())
                    .orElse(T_SRC_MOD.getString());
        };
    }

    private static String friendlyName(ResourceLocation id) {
        Loc exact = EXACT.get(id.toString());
        if (exact != null) return exact.getString();

        String[] parts = id.getPath().split("/");
        if (parts.length == 2) {
            Loc category = CATEGORY.get(parts[0]);
            Loc material = MATERIAL.get(parts[1]);
            if (category != null && material != null) {
                return F_MAT_CAT.getString(material.getString(), category.getString());
            }

            if (parts[0].equals("mineable")) {
                Loc tool = TOOL.get(parts[1]);
                if (tool != null) return F_MINEABLE.getString(tool.getString());
            }
            if (parts[0].equals("ores_in_ground")) {
                Loc ground = MATERIAL.get(parts[1]);
                if (ground != null) return F_IN_GROUND.getString(ground.getString());
            }
            if (parts[0].equals("ore_rates")) {
                Loc rate = RATE.get(parts[1]);
                if (rate != null) return F_RATE_DROPS.getString(rate.getString());
            }
        }
        if (parts.length == 1 && CATEGORY.containsKey(parts[0])) {
            return F_ALL.getString(CATEGORY.get(parts[0]).getString());
        }

        if (parts.length == 1) {
            String path = parts[0];
            Matcher matcher = INCORRECT_TOOL.matcher(path);
            if (matcher.matches()) {
                String tier = materialWord(matcher.group(1));
                if (tier != null) return F_INCORRECT.getString(tier);
            }
            matcher = NEEDS_TOOL.matcher(path);
            if (matcher.matches()) {
                String tier = materialWord(matcher.group(1));
                if (tier != null) return F_NEEDS.getString(tier);
            }
            matcher = ORES.matcher(path);
            if (matcher.matches()) {
                String material = materialWord(matcher.group(1));
                if (material != null) return F_ORES.getString(material);
            }
            matcher = SNAPS.matcher(path);
            if (matcher.matches() && matcher.group(1).equals("goat_horn")) {
                return EXACT.get("minecraft:snaps_goat_horn").getString();
            }
            matcher = TARGETS.matcher(path);
            if (matcher.matches()) {
                String subject = switch (matcher.group(1)) {
                    case "prospector_hammer" -> T_SUBJECT_PROSPECTOR.getString();
                    case "hammer" -> T_SUBJECT_HAMMER.getString();
                    default -> null;
                };
                if (subject != null) return F_TARGETS.getString(subject);
            }
        }
        return null;
    }

    private static String materialWord(String raw) {
        Loc value = MATERIAL.get(raw);
        if (value != null) return value.getString();
        return switch (raw) {
            case "gold" -> MATERIAL.get("golden").getString();
            case "stone" -> MATERIAL.get("stone").getString();
            case "wood", "wooden" -> MATERIAL.get("wood").getString();
            default -> null;
        };
    }
}
