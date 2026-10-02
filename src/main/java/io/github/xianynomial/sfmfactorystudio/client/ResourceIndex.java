package io.github.xianynomial.sfmfactorystudio.client;

import ca.teamdman.sfm.common.registry.registration.SFMResourceTypes;
import ca.teamdman.sfm.common.resourcetype.RegistryBackedResourceType;
import ca.teamdman.sfm.common.resourcetype.ResourceType;
import io.github.xianynomial.sfmfactorystudio.SFMGui;
import io.github.xianynomial.sfmfactorystudio.client.blocks.model.BProgram;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.inventory.InventoryMenu;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.material.Fluid;
import net.minecraft.world.level.material.Fluids;
import net.minecraftforge.client.extensions.common.IClientFluidTypeExtensions;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * A shared, lazily-built catalog of every pickable resource (items, fluids,
 * chemicals, and any other SFM registry-backed type). The block editor's resource
 * slots draw icons from this so the registry is only enumerated once per session.
 * <p>
 * Entries carry everything needed to render an icon (item model, tinted block-atlas
 * sprite, or a text fallback) and to search/tooltip by name. {@link #lookup(String)}
 * resolves a stored SFML id back to its entry so callers can show the icon/name of a
 * previously selected resource.
 *
 * <p><b>分帧构建</b>：数万物品的目录若一次性构建，会在客户端线程造成数百毫秒级停顿
 * （编辑器打开时的图标回显也会触发）。构建改为时间切片——进世界后由客户端 tick 预热，
 * 每帧最多花 {@value #FRAME_BUDGET_MS}ms；若使用方在预热完成前就访问
 * {@link #all()}/{@link #lookup(String)}，则同步补完剩余切片（与旧一次性构建同成本，
 * 预热使其几乎不再发生）。游戏对象（ItemStack/流体纹理）只在客户端线程触碰，
 * 这是刻意设计——它们不是线程安全对象。
 */
public final class ResourceIndex {
    /** How a grid entry is drawn. */
    public enum Kind {ITEM, SPRITE, TEXT}

    /**
     * One pickable resource. {@code sfmlId} is what gets returned on selection;
     * {@code displayName} feeds search + tooltip; the icon fields are used per kind.
     */
    public record Entry(
            String sfmlId,
            String displayName,
            String searchText,
            BProgram.ResourceKind resourceKind,
            Kind kind,
            ItemStack stack,                 // ITEM
            ResourceLocation sprite,         // SPRITE
            int tint                         // SPRITE
    ) {
        static Entry item(ResourceLocation id, ItemStack stack) {
            String name = stack.getHoverName().getString();
            String search = (name + " " + id).toLowerCase(Locale.ROOT);
            // Items return the bare namespace:path id (no type prefix), matching codegen.
            return new Entry(id.toString(), name, search, BProgram.ResourceKind.ITEM,
                    Kind.ITEM, stack, null, 0);
        }

        static Entry sprite(String sfmlId, String displayName, ResourceLocation sprite, int tint) {
            String search = (displayName + " " + sfmlId).toLowerCase(Locale.ROOT);
            return new Entry(sfmlId, displayName, search, resourceKindOf(sfmlId),
                    Kind.SPRITE, ItemStack.EMPTY, sprite, tint);
        }

        static Entry text(String sfmlId, String displayName) {
            String search = (displayName + " " + sfmlId).toLowerCase(Locale.ROOT);
            return new Entry(sfmlId, displayName, search, resourceKindOf(sfmlId),
                    Kind.TEXT, ItemStack.EMPTY, null, 0);
        }

        private static BProgram.ResourceKind resourceKindOf(String sfmlId) {
            try {
                return BProgram.ResourceRef.parse(sfmlId).kind();
            } catch (IllegalArgumentException ignored) {
                return BProgram.ResourceKind.CUSTOM;
            }
        }
    }

    private static final int FRAME_BUDGET_MS = 2;

    private static volatile List<Entry> ENTRIES = null;
    private static volatile Map<String, Entry> BY_ID = null;
    private static volatile Map<BProgram.ResourceKind, List<Entry>> BY_RESOURCE_KIND = null;

    // ---- 分帧构建状态（仅客户端线程触碰）----
    private enum Phase {ITEMS, FLUIDS, CHEMICALS, SFM_TYPES, INDEX, DONE}

    private static Phase phase;
    private static List<ItemStack> sourceItems = List.of();
    private static List<Fluid> sourceFluids = List.of();
    private static List<Object> sourceChemicals = List.of();
    private static List<Entry> building;
    private static int cursor;
    private static long buildStartNanos;

    private ResourceIndex() {
    }

    /** All entries, built on first access (client thread; registries must be ready). */
    public static List<Entry> all() {
        ensureBuilt();
        return ENTRIES;
    }

    /** Resolve a stored SFML id back to its entry, or null if unknown. */
    public static Entry lookup(String sfmlId) {
        if (sfmlId == null) {
            return null;
        }
        ensureBuilt();
        return BY_ID.get(sfmlId);
    }

    /** Entries already classified once during index construction. */
    public static List<Entry> forKind(BProgram.ResourceKind kind) {
        ensureBuilt();
        return BY_RESOURCE_KIND.getOrDefault(kind, List.of());
    }

    /** 索引是否已就绪（预热完成）。 */
    public static boolean ready() {
        return ENTRIES != null;
    }

    /**
     * 客户端 tick 驱动的预热：进世界后开始分帧构建，主菜单不动。
     * 由 {@code SFMGuiClientEvents} 的客户端 tick 事件调用。
     */
    public static void clientTick() {
        if (ENTRIES != null || phase != null) return;
        try {
            if (Minecraft.getInstance().level == null) return;
        } catch (Throwable unavailable) {
            return;
        }
        begin();
        runSlice(FRAME_BUDGET_MS);
    }

    private static void ensureBuilt() {
        if (ENTRIES != null) return;
        if (phase == null) begin();
        runSlice(Long.MAX_VALUE); // 同步兜底：预热未完成即被使用（与旧一次性构建同成本）
    }

    private static void begin() {
        phase = Phase.ITEMS;
        cursor = 0;
        building = new ArrayList<>();
        buildStartNanos = System.nanoTime();
        List<ItemStack> jei = null;
        try {
            jei = JeiCompat.itemStacksOrNull(); // headless/无 JEI 时类初始化可能失败，回退注册表
        } catch (Throwable unavailable) {
            jei = null;
        }
        if (jei != null && !jei.isEmpty()) {
            sourceItems = jei;
        } else {
            List<ItemStack> stacks = new ArrayList<>();
            for (Item item : BuiltInRegistries.ITEM) {
                ItemStack stack = new ItemStack(item);
                if (!stack.isEmpty()) stacks.add(stack);
            }
            sourceItems = stacks;
        }
        List<Fluid> fluids = new ArrayList<>();
        for (Fluid fluid : BuiltInRegistries.FLUID) {
            if (fluid == Fluids.EMPTY) continue;
            if (!fluid.isSource(fluid.defaultFluidState())) continue; // source fluids only
            fluids.add(fluid);
        }
        sourceFluids = fluids;
        List<Object> chemicals = new ArrayList<>();
        try {
            if (isClassPresent("mekanism.api.chemical.Chemical")) {
                collectChemicals(chemicals);
            }
        } catch (Throwable ignored) {
            // Mekanism not present — skip
        }
        sourceChemicals = chemicals;
    }

    private static void runSlice(long budgetMs) {
        long deadline = System.nanoTime() + budgetMs * 1_000_000L;
        while (phase != Phase.DONE && (budgetMs == Long.MAX_VALUE || System.nanoTime() < deadline)) {
            step();
        }
        if (phase == Phase.DONE) publish();
    }

    private static void step() {
        switch (phase) {
            case ITEMS -> {
                if (cursor >= sourceItems.size()) {
                    nextPhase();
                    return;
                }
                ItemStack stack = sourceItems.get(cursor++);
                if (stack == null || stack.isEmpty()) return;
                ResourceLocation id = BuiltInRegistries.ITEM.getKey(stack.getItem());
                if (id != null) building.add(Entry.item(id, stack));
            }
            case FLUIDS -> {
                if (cursor >= sourceFluids.size()) {
                    nextPhase();
                    return;
                }
                Fluid fluid = sourceFluids.get(cursor++);
                ResourceLocation fluidId = BuiltInRegistries.FLUID.getKey(fluid);
                String sfmlId = "fluid:" + fluidId.getNamespace() + ":" + fluidId.getPath();
                try {
                    IClientFluidTypeExtensions ext = IClientFluidTypeExtensions.of(fluid);
                    ResourceLocation stillTex = ext.getStillTexture();
                    String displayName = fluid.getFluidType().getDescription().getString();
                    if (stillTex != null) {
                        building.add(Entry.sprite(sfmlId, displayName, stillTex, ext.getTintColor()));
                    } else {
                        building.add(Entry.text(sfmlId, displayName));
                    }
                } catch (Throwable ignored) {
                    building.add(Entry.text(sfmlId, fluidId.toString()));
                }
            }
            case CHEMICALS -> {
                if (cursor >= sourceChemicals.size()) {
                    nextPhase();
                    return;
                }
                building.add(chemicalEntry(sourceChemicals.get(cursor++)));
            }
            case SFM_TYPES -> {
                buildSfmTypes();
                nextPhase();
            }
            case INDEX -> {
                Map<String, Entry> byId = new HashMap<>(building.size() * 2);
                Map<BProgram.ResourceKind, List<Entry>> byKind = new EnumMap<>(BProgram.ResourceKind.class);
                for (Entry e : building) {
                    byId.putIfAbsent(e.sfmlId(), e);
                    byKind.computeIfAbsent(e.resourceKind(), ignored -> new ArrayList<>()).add(e);
                }
                BY_ID = byId;
                BY_RESOURCE_KIND = byKind;
                phase = Phase.DONE;
            }
            default -> phase = Phase.DONE;
        }
    }

    private static void nextPhase() {
        cursor = 0;
        phase = Phase.values()[phase.ordinal() + 1];
    }

    private static void publish() {
        // Publish the sentinel last so readers never observe a half-built index.
        ENTRIES = building;
        building = null;
        SFMGui.LOGGER.debug("resource index ready: {} entries in {} ms",
                ENTRIES.size(), (System.nanoTime() - buildStartNanos) / 1_000_000L);
    }

    /** Other SFM registry-backed types (text fallback) — cheap enough for one step. */
    private static void buildSfmTypes() {
        try {
            var registry = SFMResourceTypes.registry();
            for (var entry : registry.entries()) {
                ResourceLocation typeId = entry.getKey().location();
                String path = typeId.getPath();
                // item/fluid/chemical already covered with icons above
                if (path.equals("item") || path.equals("fluid") || path.equals("chemical")) continue;
                ResourceType<?, ?, ?> rt = entry.getValue();
                if (rt instanceof RegistryBackedResourceType<?, ?, ?> backed) {
                    for (ResourceLocation resId : backed.getRegistryKeys()) {
                        String sfmlId = path + ":" + resId.getNamespace() + ":" + resId.getPath();
                        building.add(Entry.text(sfmlId, sfmlId));
                    }
                }
            }
        } catch (Throwable ignored) {
            // SFM registry not yet available — items/fluids only
        }
    }

    /**
     * Isolated so all Mekanism access stays reflective and guarded: the API jar
     * is not on the 1.20.1 compile classpath, and the registry field's shape
     * (raw {@code IForgeRegistry} vs {@code Supplier}) differs across versions.
     * Any miss simply means the chemical page stays empty.
     */
    private static void collectChemicals(List<Object> out) {
        try {
            Class<?> api = Class.forName("mekanism.api.MekanismAPI");
            Object registry = api.getField("CHEMICAL_REGISTRY").get(null);
            // unwrap common holder shapes: Supplier / RegistryObject-ish get()
            for (int i = 0; i < 3 && !(registry instanceof Iterable<?>); i++) {
                registry = registry.getClass().getMethod("get").invoke(registry);
            }
            if (!(registry instanceof Iterable<?> iterable)) return;
            for (Object chemical : iterable) {
                try {
                    if ((Boolean) chemical.getClass().getMethod("isEmptyType").invoke(chemical)) continue;
                    out.add(chemical);
                } catch (Throwable ignored) {
                    // one broken chemical must not kill the whole page
                }
            }
        } catch (Throwable ignored) {
            // Mekanism absent — the chemical page is simply empty
        }
    }

    private static Entry chemicalEntry(Object chemical) {
        try {
            ResourceLocation regName = (ResourceLocation)
                    chemical.getClass().getMethod("getRegistryName").invoke(chemical);
            if (regName == null) return Entry.text("chemical:unknown", "chemical:unknown");
            String sfmlId = "chemical:" + regName.getNamespace() + ":" + regName.getPath();
            Object component = chemical.getClass().getMethod("getTextComponent").invoke(chemical);
            String displayName = (String) component.getClass().getMethod("getString").invoke(component);
            return Entry.sprite(sfmlId, displayName,
                    (ResourceLocation) chemical.getClass().getMethod("getIcon").invoke(chemical),
                    (Integer) chemical.getClass().getMethod("getTint").invoke(chemical));
        } catch (Throwable ignored) {
            return Entry.text("chemical:unknown", "chemical:unknown");
        }
    }

    private static boolean isClassPresent(String className) {
        try {
            return Class.forName(className, false, ResourceIndex.class.getClassLoader()) != null;
        } catch (ClassNotFoundException e) {
            return false;
        }
    }

    // ===== shared icon rendering (used by picker grid and the code-editor bar) =====

    /** Draw a single entry's icon in a 16x16 box at (x,y): item model, sprite, or text. */
    public static void renderIcon(GuiGraphics graphics, net.minecraft.client.gui.Font font, Entry entry, int x, int y) {
        switch (entry.kind()) {
            case ITEM -> JeiCompat.renderItem(graphics, entry.stack(), x, y);
            case SPRITE -> {
                if (entry.sprite() != null) {
                    renderSpriteIcon(graphics, x, y, entry.sprite(), entry.tint());
                } else {
                    renderTextIcon(graphics, font, entry, x, y);
                }
            }
            case TEXT -> renderTextIcon(graphics, font, entry, x, y);
        }
    }

    /** Text fallback: draw the first 2 chars of the display name centered in the cell. */
    public static void renderTextIcon(GuiGraphics graphics, net.minecraft.client.gui.Font font, Entry entry, int x, int y) {
        String name = entry.displayName();
        String abbrev = name.isEmpty() ? "?" : name.substring(0, Math.min(2, name.length()));
        graphics.fill(x, y, x + 16, y + 16, 0xFFEDF0F5);
        graphics.drawString(font, abbrev, x + 8 - font.width(abbrev) / 2, y + 4, 0xFF6B7688, false);
    }

    /** Renders a 16x16 sprite from the block atlas with a tint color. */
    public static void renderSpriteIcon(GuiGraphics graphics, int x, int y, ResourceLocation spriteLocation, int tint) {
        TextureAtlasSprite sprite = Minecraft.getInstance()
                .getTextureAtlas(InventoryMenu.BLOCK_ATLAS)
                .apply(spriteLocation);
        float a = ((tint >> 24) & 0xFF) / 255f;
        float r = ((tint >> 16) & 0xFF) / 255f;
        float g = ((tint >> 8) & 0xFF) / 255f;
        float b = (tint & 0xFF) / 255f;
        if (a == 0f) a = 1f; // treat fully transparent tint as opaque (0xRRGGBB without alpha)
        graphics.blit(x, y, 0, 16, 16, sprite, r, g, b, a);
    }
}
