package io.github.xianynomial.sfmfactorystudio.client;



import net.minecraft.resources.ResourceLocation;

import net.neoforged.fml.ModList;



import java.util.List;

import java.util.Map;

import java.util.regex.Matcher;

import java.util.regex.Pattern;



/** Deterministic Chinese display names for tag ids. Raw ids remain the source of truth. */

public final class TagDisplayNames {

    private static final Map<String, String> EXACT = Map.ofEntries(

            Map.entry("c:ingots", new Loc("gui.sfmfactorystudio.tagname.all_ingots", "所有金属锭").getString()),

            Map.entry("c:nuggets", new Loc("gui.sfmfactorystudio.tagname.all_nuggets", "所有金属粒").getString()),

            Map.entry("c:ores", new Loc("gui.sfmfactorystudio.tagname.all_ores", "所有矿石").getString()),

            Map.entry("c:dusts", new Loc("gui.sfmfactorystudio.tagname.all_dusts", "所有粉末").getString()),

            Map.entry("c:gems", new Loc("gui.sfmfactorystudio.tagname.all_gems", "所有宝石").getString()),

            Map.entry("c:storage_blocks", new Loc("gui.sfmfactorystudio.tagname.all_storage_blocks", "所有储存方块").getString()),

            Map.entry("minecraft:logs", new Loc("gui.sfmfactorystudio.tagname.all_logs", "所有原木").getString()),

            Map.entry("minecraft:planks", new Loc("gui.sfmfactorystudio.tagname.all_planks", "所有木板").getString()),

            Map.entry("minecraft:wool", new Loc("gui.sfmfactorystudio.tagname.all_wool", "所有羊毛").getString()),

            Map.entry("minecraft:coals", new Loc("gui.sfmfactorystudio.tagname.all_coals", "所有煤炭燃料").getString()),

            Map.entry("minecraft:mineable/axe", new Loc("gui.sfmfactorystudio.tagname.mineable_axe", "可用斧挖掘").getString()),

            Map.entry("minecraft:mineable/pickaxe", new Loc("gui.sfmfactorystudio.tagname.mineable_pickaxe", "可用镐挖掘").getString()),

            Map.entry("minecraft:mineable/shovel", new Loc("gui.sfmfactorystudio.tagname.mineable_shovel", "可用锹挖掘").getString()),

            Map.entry("minecraft:mineable/hoe", new Loc("gui.sfmfactorystudio.tagname.mineable_hoe", "可用锄挖掘").getString()),

            Map.entry("minecraft:beacon_payment_items", new Loc("gui.sfmfactorystudio.tagname.beacon_payment", "信标可用材料").getString()),

            Map.entry("minecraft:iron_ores", new Loc("gui.sfmfactorystudio.tagname.iron_ores", "铁矿石").getString()),

            Map.entry("minecraft:incorrect_for_gold_tool", new Loc("gui.sfmfactorystudio.tagname.incorrect_gold", "金制工具无法正确采掘").getString()),

            Map.entry("minecraft:incorrect_for_wooden_tool", new Loc("gui.sfmfactorystudio.tagname.incorrect_wood", "木制工具无法正确采掘").getString()),

            Map.entry("minecraft:needs_stone_tool", new Loc("gui.sfmfactorystudio.tagname.needs_stone", "需要石制或更好的工具").getString()),

            Map.entry("minecraft:overworld_carver_replaceables", new Loc("gui.sfmfactorystudio.tagname.carver_replaceables", "主世界洞穴可替换方块").getString()),

            Map.entry("minecraft:snaps_goat_horn", new Loc("gui.sfmfactorystudio.tagname.snaps_goat_horn", "会折断山羊角").getString()),

            Map.entry("c:mineable/paxel", new Loc("gui.sfmfactorystudio.tagname.mineable_paxel", "可用多功能工具挖掘").getString()),

            Map.entry("c:ore_rates/singular", new Loc("gui.sfmfactorystudio.tagname.rate_singular", "单份掉落矿石").getString()),

            Map.entry("c:ores_in_ground/stone", new Loc("gui.sfmfactorystudio.tagname.ground_stone", "生成在石头中的矿石").getString()),

            Map.entry("silentgear:mineable/paxel", new Loc("gui.sfmfactorystudio.tagname.mineable_paxel", "可用多功能工具挖掘").getString()),

            Map.entry("silentgear:mineable/pickaxe_with_spoon", new Loc("gui.sfmfactorystudio.tagname.mineable_spoon_pickaxe", "可用勺镐挖掘").getString()),

            Map.entry("silentgear:prospector_hammer_targets", new Loc("gui.sfmfactorystudio.tagname.prospector_targets", "勘探锤可探测的方块").getString())

    );



    private static final Map<String, String> CATEGORY = Map.ofEntries(

            Map.entry("ingots", new Loc("gui.sfmfactorystudio.tagname.cat_ingots", "锭").getString()), Map.entry("nuggets", new Loc("gui.sfmfactorystudio.tagname.cat_nuggets", "粒").getString()),

            Map.entry("ores", new Loc("gui.sfmfactorystudio.tagname.cat_ores", "矿石").getString()), Map.entry("raw_materials", new Loc("gui.sfmfactorystudio.tagname.cat_raw", "粗矿").getString()),

            Map.entry("storage_blocks", new Loc("gui.sfmfactorystudio.tagname.cat_storage", "储存方块").getString()), Map.entry("dusts", new Loc("gui.sfmfactorystudio.tagname.cat_dusts", "粉末").getString()),

            Map.entry("gems", new Loc("gui.sfmfactorystudio.tagname.cat_gems", "宝石").getString()), Map.entry("plates", new Loc("gui.sfmfactorystudio.tagname.cat_plates", "板").getString()),

            Map.entry("gears", new Loc("gui.sfmfactorystudio.tagname.cat_gears", "齿轮").getString()), Map.entry("rods", new Loc("gui.sfmfactorystudio.tagname.cat_rods", "杆").getString()),

            Map.entry("wires", new Loc("gui.sfmfactorystudio.tagname.cat_wires", "线缆").getString()), Map.entry("tools", new Loc("gui.sfmfactorystudio.tagname.cat_tools", "工具").getString()),

            Map.entry("armors", new Loc("gui.sfmfactorystudio.tagname.cat_armors", "盔甲").getString()), Map.entry("seeds", new Loc("gui.sfmfactorystudio.tagname.cat_seeds", "种子").getString()),

            Map.entry("crops", new Loc("gui.sfmfactorystudio.tagname.cat_crops", "作物").getString())

    );



    private static final Map<String, String> MATERIAL = Map.ofEntries(

            Map.entry("iron", new Loc("gui.sfmfactorystudio.tagname.mat_iron", "铁").getString()), Map.entry("gold", new Loc("gui.sfmfactorystudio.tagname.mat_gold", "金").getString()), Map.entry("copper", new Loc("gui.sfmfactorystudio.tagname.mat_copper", "铜").getString()),

            Map.entry("tin", new Loc("gui.sfmfactorystudio.tagname.mat_tin", "锡").getString()), Map.entry("lead", new Loc("gui.sfmfactorystudio.tagname.mat_lead", "铅").getString()), Map.entry("silver", new Loc("gui.sfmfactorystudio.tagname.mat_silver", "银").getString()),

            Map.entry("nickel", new Loc("gui.sfmfactorystudio.tagname.mat_nickel", "镍").getString()), Map.entry("aluminum", new Loc("gui.sfmfactorystudio.tagname.mat_aluminum", "铝").getString()), Map.entry("aluminium", new Loc("gui.sfmfactorystudio.tagname.mat_aluminum", "铝").getString()),

            Map.entry("uranium", new Loc("gui.sfmfactorystudio.tagname.mat_uranium", "铀").getString()), Map.entry("osmium", new Loc("gui.sfmfactorystudio.tagname.mat_osmium", "锇").getString()), Map.entry("zinc", new Loc("gui.sfmfactorystudio.tagname.mat_zinc", "锌").getString()),

            Map.entry("bronze", new Loc("gui.sfmfactorystudio.tagname.mat_bronze", "青铜").getString()), Map.entry("steel", new Loc("gui.sfmfactorystudio.tagname.mat_steel", "钢").getString()),

            Map.entry("netherite", new Loc("gui.sfmfactorystudio.tagname.mat_netherite", "下界合金").getString()), Map.entry("diamond", new Loc("gui.sfmfactorystudio.tagname.mat_diamond", "钻石").getString()),

            Map.entry("emerald", new Loc("gui.sfmfactorystudio.tagname.mat_emerald", "绿宝石").getString()), Map.entry("quartz", new Loc("gui.sfmfactorystudio.tagname.mat_quartz", "石英").getString()),

            Map.entry("coal", new Loc("gui.sfmfactorystudio.tagname.mat_coal", "煤").getString()), Map.entry("redstone", new Loc("gui.sfmfactorystudio.ed.f_pulse_short", "红石").getString()), Map.entry("lapis", new Loc("gui.sfmfactorystudio.tagname.mat_lapis", "青金石").getString()),

            Map.entry("stone", new Loc("gui.sfmfactorystudio.tagname.mat_stone", "石头").getString()), Map.entry("deepslate", new Loc("gui.sfmfactorystudio.tagname.mat_deepslate", "深板岩").getString()),

            Map.entry("wooden", new Loc("gui.sfmfactorystudio.tagname.mat_wood", "木制").getString()), Map.entry("wood", new Loc("gui.sfmfactorystudio.tagname.mat_wood", "木制").getString()), Map.entry("golden", new Loc("gui.sfmfactorystudio.tagname.mat_golden", "金制").getString())

    );



    private static final Map<String, String> TOOL = Map.ofEntries(

            Map.entry("axe", new Loc("gui.sfmfactorystudio.tagname.tool_axe", "斧").getString()), Map.entry("pickaxe", new Loc("gui.sfmfactorystudio.tagname.tool_pickaxe", "镐").getString()), Map.entry("shovel", new Loc("gui.sfmfactorystudio.tagname.tool_shovel", "锹").getString()),

            Map.entry("hoe", new Loc("gui.sfmfactorystudio.tagname.tool_hoe", "锄").getString()), Map.entry("paxel", new Loc("gui.sfmfactorystudio.tagname.tool_paxel", "多功能工具").getString()),

            Map.entry("pickaxe_with_spoon", new Loc("gui.sfmfactorystudio.tagname.tool_spoon_pickaxe", "勺镐").getString()), Map.entry("hammer", new Loc("gui.sfmfactorystudio.tagname.subject_hammer", "锤").getString())

    );



    private static final Map<String, String> RATE = Map.ofEntries(

            Map.entry("singular", new Loc("gui.sfmfactorystudio.tagname.rate_singular_word", "单份").getString()), Map.entry("sparse", new Loc("gui.sfmfactorystudio.tagname.rate_sparse_word", "少量").getString()),

            Map.entry("dense", new Loc("gui.sfmfactorystudio.tagname.rate_dense_word", "富集").getString()), Map.entry("poor", new Loc("gui.sfmfactorystudio.tagname.rate_poor_word", "贫瘠").getString())

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

        return new Loc("gui.sfmfactorystudio.tagname.unnamed", "未命名的模组分类").getString();

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

            case "c" -> new Loc("gui.sfmfactorystudio.tagname.src_common", "通用").getString();

            case "minecraft" -> new Loc("gui.sfmfactorystudio.tagname.src_vanilla", "原版").getString();

            case "forge" -> new Loc("gui.sfmfactorystudio.tagname.src_forge", "Forge兼容").getString();

            default -> ModList.get().getModContainerById(id.getNamespace())

                    .map(c -> c.getModInfo().getDisplayName())

                    .orElse(new Loc("gui.sfmfactorystudio.tagname.src_mod", "模组").getString());

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

                if (material != null) return material + new Loc("gui.sfmfactorystudio.tagname.cat_ores", "矿石").getString();

            }

            matcher = SNAPS.matcher(path);

            if (matcher.matches() && matcher.group(1).equals("goat_horn")) return new Loc("gui.sfmfactorystudio.tagname.snaps_goat_horn", "会折断山羊角").getString();

            matcher = TARGETS.matcher(path);

            if (matcher.matches()) {

                String subject = switch (matcher.group(1)) {

                    case "prospector_hammer" -> new Loc("gui.sfmfactorystudio.tagname.subject_prospector", "勘探锤").getString();

                    case "hammer" -> new Loc("gui.sfmfactorystudio.tagname.subject_hammer", "锤").getString();

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

            case "gold" -> new Loc("gui.sfmfactorystudio.tagname.mat_golden", "金制").getString();

            case "stone" -> "石制";

            case "wood", "wooden" -> new Loc("gui.sfmfactorystudio.tagname.mat_wood", "木制").getString();

            default -> null;

        };

    }

}
