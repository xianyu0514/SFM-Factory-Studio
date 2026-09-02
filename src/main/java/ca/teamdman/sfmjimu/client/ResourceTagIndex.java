package ca.teamdman.sfmjimu.client;

import net.minecraft.resources.ResourceLocation;
import net.minecraft.tags.TagKey;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * One-pass, client-side index of the exact item + block tags that SFM checks
 * for an ItemStack. It supports both "item -> its tags" and global tag search.
 */
public final class ResourceTagIndex {
    public record TagEntry(ResourceLocation id, String displayName, String searchText,
                           int resourceCount, List<String> examples, List<String> memberIds) {
    }

    private static volatile List<TagEntry> ALL;
    private static volatile Map<String, List<TagEntry>> BY_ITEM;
    private static volatile Map<String, TagEntry> BY_ID;

    private ResourceTagIndex() {
    }

    public static List<TagEntry> all() {
        ensureBuilt();
        return ALL;
    }

    public static List<TagEntry> forItem(String itemId) {
        ensureBuilt();
        return BY_ITEM.getOrDefault(itemId, List.of());
    }

    public static String displayName(String matcher) {
        Map<String, TagEntry> built = BY_ID;
        if (built != null) {
            TagEntry entry = built.get(matcher);
            if (entry != null) return entry.displayName();
        }
        ResourceLocation id = ResourceLocation.tryParse(matcher);
        return id == null ? "#" + matcher : TagDisplayNames.display(id, List.of());
    }

    public static synchronized void invalidate() {
        ALL = null;
        BY_ITEM = null;
        BY_ID = null;
    }

    private static void ensureBuilt() {
        if (ALL == null) build();
    }

    private static synchronized void build() {
        if (ALL != null) return;
        Map<ResourceLocation, MutableTag> tags = new LinkedHashMap<>();
        Map<String, Set<ResourceLocation>> itemTags = new LinkedHashMap<>();

        for (ResourceIndex.Entry entry : ResourceIndex.forKind(
                ca.teamdman.sfmjimu.client.blocks.model.BProgram.ResourceKind.ITEM)) {
            ItemStack stack = entry.stack();
            if (stack == null || stack.isEmpty()) continue;
            String itemId = entry.sfmlId();
            if (itemTags.containsKey(itemId)) continue; // JEI variants share tags

            Set<ResourceLocation> found = new LinkedHashSet<>();
            //noinspection deprecation
            stack.getItem().builtInRegistryHolder().tags().map(TagKey::location).forEach(found::add);
            Block block = Block.byItem(stack.getItem());
            if (block != Blocks.AIR) {
                // SFM intentionally includes block tags for block items.
                //noinspection deprecation
                block.builtInRegistryHolder().tags().map(TagKey::location).forEach(found::add);
            }
            itemTags.put(itemId, found);
            for (ResourceLocation id : found) {
                MutableTag tag = tags.computeIfAbsent(id, ignored -> new MutableTag());
                tag.resourceIds.add(itemId);
                if (tag.examples.size() < 5 && !tag.examples.contains(entry.displayName())) {
                    tag.examples.add(entry.displayName());
                }
            }
        }

        Map<String, TagEntry> byId = new HashMap<>();
        for (Map.Entry<ResourceLocation, MutableTag> e : tags.entrySet()) {
            ResourceLocation id = e.getKey();
            MutableTag tag = e.getValue();
            String display = TagDisplayNames.display(id, tag.examples);
            String search = (display + " " + TagDisplayNames.sourceName(id) + " "
                    + id + " " + String.join(" ", tag.examples))
                    .toLowerCase(Locale.ROOT);
            byId.put(id.toString(), new TagEntry(id, display, search,
                    tag.resourceIds.size(), List.copyOf(tag.examples), List.copyOf(tag.resourceIds)));
        }

        List<TagEntry> all = new ArrayList<>(byId.values());
        all.sort(Comparator.comparing(TagEntry::displayName).thenComparing(e -> e.id().toString()));
        Map<String, List<TagEntry>> byItem = new LinkedHashMap<>();
        for (Map.Entry<String, Set<ResourceLocation>> e : itemTags.entrySet()) {
            List<TagEntry> list = e.getValue().stream().map(id -> byId.get(id.toString()))
                    .filter(java.util.Objects::nonNull)
                    .sorted(Comparator.comparing(TagEntry::displayName).thenComparing(t -> t.id().toString()))
                    .toList();
            byItem.put(e.getKey(), list);
        }

        BY_ID = byId;
        BY_ITEM = byItem;
        ALL = List.copyOf(all); // publish sentinel last
    }

    private static final class MutableTag {
        final Set<String> resourceIds = new LinkedHashSet<>();
        final List<String> examples = new ArrayList<>();
    }
}
