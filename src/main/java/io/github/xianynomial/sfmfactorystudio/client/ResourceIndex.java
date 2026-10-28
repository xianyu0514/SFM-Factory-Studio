package io.github.xianynomial.sfmfactorystudio.client;

import ca.teamdman.sfm.common.registry.registration.SFMResourceTypes;
import ca.teamdman.sfm.common.resourcetype.RegistryBackedResourceType;
import ca.teamdman.sfm.common.resourcetype.ResourceType;
import io.github.xianynomial.sfmfactorystudio.SFMGui;
import io.github.xianynomial.sfmfactorystudio.client.blocks.model.BProgram;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.renderer.block.FluidModel;
import net.minecraft.client.renderer.block.FluidStateModelSet;
import net.minecraft.client.renderer.texture.MissingTextureAtlasSprite;
import net.minecraft.client.renderer.texture.TextureAtlas;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.world.inventory.InventoryMenu;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.material.Fluid;
import net.minecraft.world.level.material.Fluids;
import net.neoforged.neoforge.client.fluid.FluidTintSource;
import net.neoforged.neoforge.fluids.FluidStack;

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
            Identifier sprite,         // SPRITE
            int tint                         // SPRITE
    ) {
        static Entry item(Identifier id, ItemStack stack) {
            String name = stack.getHoverName().getString();
            String search = (name + " " + id).toLowerCase(Locale.ROOT);
            // Items return the bare namespace:path id (no type prefix), matching codegen.
            return new Entry(id.toString(), name, search, BProgram.ResourceKind.ITEM,
                    Kind.ITEM, stack, null, 0);
        }

        static Entry sprite(String sfmlId, String displayName, Identifier sprite, int tint) {
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
        // 26.1.2 没有 Mekanism 发布，化学品收集已移除。
        // SFM 的 chemical 资源类型仍由 SFM_TYPES 阶段从 SFM 注册表枚举，不受影响。
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
                Identifier id = BuiltInRegistries.ITEM.getKey(stack.getItem());
                if (id != null) building.add(Entry.item(id, stack));
            }
            case FLUIDS -> {
                if (cursor >= sourceFluids.size()) {
                    nextPhase();
                    return;
                }
                Fluid fluid = sourceFluids.get(cursor++);
                Identifier fluidId = BuiltInRegistries.FLUID.getKey(fluid);
                String sfmlId = "fluid:" + fluidId.getNamespace() + ":" + fluidId.getPath();
                building.add(fluidEntry(fluid, fluidId, sfmlId));
            }
            case CHEMICALS -> {
                // 26.1.2 无 Mekanism：化学源恒空，直接跳过该阶段
                //（与原「有 Mekanism 检测、无 Mekanism 时源为空」的行为完全一致）。
                nextPhase();
                return;
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
                Identifier typeId = entry.getKey().identifier();
                String path = typeId.getPath();
                // item/fluid/chemical already covered with icons above
                if (path.equals("item") || path.equals("fluid") || path.equals("chemical")) continue;
                ResourceType<?, ?, ?> rt = entry.getValue();
                if (rt instanceof RegistryBackedResourceType<?, ?, ?> backed) {
                    for (Identifier resId : backed.getRegistryKeys()) {
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
     * 构建一个流体条目：优先「静置纹理 + 染色」，其次「桶图标」，最后退文字。
     *
     * <p><b>为什么不再用 {@code IClientFluidTypeExtensions}：</b>26.1 把流体纹理从
     * 「客户端扩展 API」改成了<b>模型驱动</b>——{@code getStillTexture()} /
     * {@code getFlowingTexture()} / {@code getTintColor()} 已从接口删除（现在整个接口
     * 只剩 overlay 与 fog 四项），纹理改由 {@link FluidModel} 的 {@code Material} 声明，
     * 因此自动进入 block atlas，也不再需要旧版的 {@code TextureStitchEvent}。
     *
     * <p>取法与 JEI 29.47 的 {@code IPlatformFluidHelperInternal#getStillFluidSprite}
     * 逐条一致（字节码核对过）：{@code ModelManager#getFluidStateModelSet()} →
     * {@code FluidStateModelSet#get(FluidState)} → {@code FluidModel#stillMaterial()}。
     *
     * <p><b>三个必须处理的事实</b>：
     * <ol>
     *   <li>{@code FluidStateModelSet#get} 的实际实现是
     *       {@code modelByFluid.getOrDefault(fluid, missingModel)}，而 {@code bake()}
     *       只烘焙水与熔岩两种默认模型。所以<b>没在 {@code RegisterFluidModelsEvent}
     *       注册过的模组流体一律拿到 missingModel</b>，其 sprite <b>不是 null</b>，
     *       而是紫黑格纹理 ⇒ 必须比对 {@link MissingTextureAtlasSprite#getLocation()}
     *       才能识别（JEI 也是这么过滤的），单独判 null 形同虚设。</li>
     *   <li>{@code FluidModel#fluidTintSource()} 标注 {@code @Nullable}：为空时按白色
     *       {@code 0xFFFFFFFF} 处理（纹理自带颜色，如熔岩，再染白等于不改）。</li>
     *   <li>染色只能走 {@code colorAsStack}：{@code colorInWorld} 需要世界坐标，
     *       GUI 里没有。水在世界里的 biome 变色正是走后者，所以这里得到的是标准水色
     *       （{@code FluidTintSources.water()} 的常量 {@code 0xFF3F76E4}）。</li>
     * </ol>
     *
     * <p>显示名仍取 {@code fluid.getFluidType().getDescription()}：{@code Fluid} 在
     * NeoForge patch 版里 {@code implements IFluidExtension}，该方法<b>并未移除</b>
     * （纯 MC 的 client.jar 里看不到，所以容易误判）。这样 26.1.2 与 1.21.1 / 1.20.1
     * 显示的是同一个本地化名（「水」而不是 {@code minecraft:water}）。
     */
    private static Entry fluidEntry(Fluid fluid, Identifier fluidId, String sfmlId) {
        String displayName;
        try {
            displayName = fluid.getFluidType().getDescription().getString();
        } catch (Throwable t) {
            displayName = fluidId.toString();
        }

        // ① 模型驱动的静置纹理 + tint（与原版 / JEI 同一视觉）
        try {
            FluidStateModelSet modelSet = Minecraft.getInstance().getModelManager().getFluidStateModelSet();
            FluidModel model = modelSet == null ? null : modelSet.get(fluid.defaultFluidState());
            TextureAtlasSprite sprite = model == null ? null : model.stillMaterial().sprite();
            if (sprite != null
                    && !MissingTextureAtlasSprite.getLocation().equals(sprite.atlasLocation())) {
                FluidTintSource tintSource = model.fluidTintSource();
                int tint = tintSource == null
                        ? 0xFFFFFFFF
                        : tintSource.colorAsStack(new FluidStack(fluid, 1));
                return Entry.sprite(sfmlId, displayName, sprite.contents().name(), tint);
            }
        } catch (Throwable t) {
            SFMGui.LOGGER.debug("fluid still-texture unavailable for {}", sfmlId, t);
        }

        // ② 没有模型（多数是未适配 26.1 的模组流体）→ 桶图标比文字更易辨认。
        // 不能直接用 Entry.item(...)：那个工厂会把 sfmlId 写成桶的物品 id、
        // resourceKind 写成 ITEM，选中后生成的 SFML 会变成 'minecraft:water'
        // 而不是 'fluid:minecraft:water'。这里沿用流体的 sfmlId 与 kind，
        // 只借用 ITEM 的绘制方式（Kind.ITEM 走 item 渲染）。
        try {
            Item bucket = fluid.getBucket();
            if (bucket != null && bucket != net.minecraft.world.item.Items.AIR) {
                ItemStack bucketStack = new ItemStack(bucket);
                String search = (displayName + " " + bucketStack.getHoverName().getString()
                        + " " + sfmlId).toLowerCase(Locale.ROOT);
                return new Entry(sfmlId, displayName, search, Entry.resourceKindOf(sfmlId),
                        Kind.ITEM, bucketStack, null, 0);
            }
        } catch (Throwable t) {
            SFMGui.LOGGER.debug("fluid bucket unavailable for {}", sfmlId, t);
        }

        // ③ 彻底兜底：文字条目仍可搜索、可选中、能生成正确的 SFML
        return Entry.text(sfmlId, displayName);
    }

    // ===== shared icon rendering (used by picker grid and the code-editor bar) =====

    /** Draw a single entry's icon in a 16x16 box at (x,y): item model, sprite, or text. */
    public static void renderIcon(GuiGraphicsExtractor graphics, net.minecraft.client.gui.Font font, Entry entry, int x, int y) {
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
    public static void renderTextIcon(GuiGraphicsExtractor graphics, net.minecraft.client.gui.Font font, Entry entry, int x, int y) {
        String name = entry.displayName();
        String abbrev = name.isEmpty() ? "?" : name.substring(0, Math.min(2, name.length()));
        graphics.fill(x, y, x + 16, y + 16, 0xFFEDF0F5);
        graphics.text(font, abbrev, x + 8 - font.width(abbrev) / 2, y + 4, 0xFF6B7688, false);
    }

    /** Renders a 16x16 sprite from the block atlas with a tint color. */
    public static void renderSpriteIcon(GuiGraphicsExtractor graphics, int x, int y, Identifier spriteLocation, int tint) {
        TextureAtlasSprite sprite = ((net.minecraft.client.renderer.texture.TextureAtlas) Minecraft.getInstance()
                .getTextureManager()
                .getTexture(TextureAtlas.LOCATION_BLOCKS)).getSprite(spriteLocation);
        int a = (tint >> 24) & 0xFF;
        int r = (tint >> 16) & 0xFF;
        int g = (tint >> 8) & 0xFF;
        int b = tint & 0xFF;
        if (a == 0) a = 0xFF; // treat fully transparent tint as opaque (0xRRGGBB without alpha)
        int argb = (a << 24) | (r << 16) | (g << 8) | b;
        // 26.1 的 blit 不再收 TextureAtlasSprite + 逐通道颜色；改用 blitSprite
        //（GUI_TEXTURED 的顶点格式是 POSITION_TEX_COLOR，与 sprite 匹配）
        graphics.blitSprite(net.minecraft.client.renderer.RenderPipelines.GUI_TEXTURED,
                sprite, x, y, 16, 16, argb);
    }
}
