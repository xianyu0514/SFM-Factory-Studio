package io.github.xianynomial.sfmfactorystudio.client;

import io.github.xianynomial.sfmfactorystudio.SFMGui;
import io.github.xianynomial.sfmfactorystudio.client.blocks.model.SlotLayoutData;
import io.github.xianynomial.sfmfactorystudio.client.blocks.model.DeferredLayoutWriter;
import io.github.xianynomial.sfmfactorystudio.client.blocks.model.DeferredLayoutCache;
import io.github.xianynomial.sfmfactorystudio.client.blocks.model.SlotCaptureCollector;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ScreenEvent;
import net.neoforged.neoforge.capabilities.Capabilities;
import net.neoforged.neoforge.event.entity.player.PlayerInteractEvent;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

/**
 * 槽位布局捕获（按方块坐标键，纯客户端）：
 *
 * <p>玩家右键打开任何容器界面时——
 * ① {@link PlayerInteractEvent.RightClickBlock}（客户端也触发）记录刚点的方块坐标；
 * ② 容器界面初始化（{@link ScreenEvent.Init.Post}）时，该界面拥有**真实的槽位
 * 坐标**（就是此刻屏幕上画的原版布局），按坐标键存入本地 JSON；晚绑定槽位的
 * 模组菜单由首帧渲染（Render.Foreground）补捕。
 *
 * <p>除坐标外每格还记录两项校准证据：
 * <ul>
 * <li>内容签名（物品 id + 数量）——供服务端能力槽内容比对；</li>
 * <li>实例匹配结果（capIndex）——菜单槽容器与该方块能力面是同一实例时，
 * 容器内索引就是 SFM 实际寻址的能力槽索引（精确）。</li>
 * </ul>
 *
 * <p>编号与能力槽索引的最终对应关系由 {@link SlotNumbering} 在选择器打开时合成；
 * 本类只负责采集与持久化。
 */
@EventBusSubscriber(modid = SFMGui.MOD_ID, value = Dist.CLIENT)
public final class ClientGuiLayoutCache {
    private ClientGuiLayoutCache() {
    }

    private static final java.util.concurrent.Executor IO =
            java.util.concurrent.Executors.newSingleThreadExecutor(task -> {
                Thread thread = new Thread(task, "sfmstudio-layout-io");
                thread.setDaemon(true);
                return thread;
            });
    private static final DeferredLayoutCache CACHE = new DeferredLayoutCache(IO,
            () -> Files.exists(file()) ? SlotLayoutData.readAll(Files.readString(file())) : Map.of(),
            failure -> SFMGui.LOGGER.warn("Failed to read slot-layouts.json, continuing with empty cache: {}", failure.toString()));
    private static final DeferredLayoutWriter WRITER = new DeferredLayoutWriter(
            IO, () -> System.nanoTime() / 1_000_000L,
            snapshot -> DeferredLayoutWriter.writeAtomically(file(), snapshot),
            failure -> SFMGui.LOGGER.warn("Failed to write slot-layouts.json; retry scheduled: {}", failure.toString()));
    private static BlockPos lastClickedPos = null;
    private static long lastClickedAt = 0;

    private static Path file() {
        return net.neoforged.fml.loading.FMLPaths.CONFIGDIR.get()
                .resolve("sfmfactorystudio").resolve("slot-layouts.json");
    }

    private static String key(BlockPos pos) {
        return pos.getX() + "|" + pos.getY() + "|" + pos.getZ();
    }

    /** 玩家右键方块（客户端事件）：记住坐标，供容器界面打开时关联。 */
    @SubscribeEvent
    public static void onRightClickBlock(PlayerInteractEvent.RightClickBlock event) {
        if (!event.getLevel().isClientSide()) return;
        lastClickedPos = event.getPos().immutable();
        lastClickedAt = System.currentTimeMillis();
    }

    /**
     * 待渲染期补捕的界面：模组 GUI（如 Mekanism）的槽位布局晚绑定且会动态
     * 调整，首帧可能还没摆好——保留多帧，逐帧用更完整的结果覆盖。
     */
    private record Pending(BlockPos pos, int framesLeft) {
    }

    private static final int RECAPTURE_FRAMES = 20;
    private static final Map<Screen, Pending> PENDING = new java.util.concurrent.ConcurrentHashMap<>();

    /** 容器界面初始化：按最近点击的方块坐标捕获真实槽位布局。 */
    @SubscribeEvent
    public static void onScreenInit(ScreenEvent.Init.Post event) {
        if (!(event.getScreen() instanceof AbstractContainerScreen<?> screen)) return;
        BlockPos pos = lastClickedPos;
        boolean fresh = pos != null && System.currentTimeMillis() - lastClickedAt < 3000;
        lastClickedPos = null;
        if (!fresh) return;
        PENDING.clear();
        PENDING.put(screen, new Pending(pos, RECAPTURE_FRAMES));
        // 开启操作学习会话：玩家在该界面里的每次真实点击，服务端都会用能力面
        // 差分证实"视觉格 ↔ 真实槽位"，锚点回存到捕获缓存（对一切模组生效）
        io.github.xianynomial.sfmfactorystudio.net.SFMGuiNetwork.sendToServerBestEffort(
                new io.github.xianynomial.sfmfactorystudio.net.SlotCalibrationBeginPayload(
                        pos, screen.getMenu() != null ? screen.getMenu().containerId : -1,
                        screen.getMenu() != null ? screen.getMenu().getClass().getSimpleName() : ""));
        try {
            capture(pos, screen);   // 尽早尝试；渲染期会持续补捕
        } catch (Throwable t) {
            SFMGui.LOGGER.debug("slot layout capture failed at {}", pos, t);
        }
    }

    /** 渲染期补捕：菜单完全成型/槽位就位后逐帧覆盖，保留更完整的结果。 */
    @SubscribeEvent
    public static void onContainerRender(net.neoforged.neoforge.client.event.ContainerScreenEvent.Render.Foreground event) {
        var screen = event.getContainerScreen();
        Pending pending = PENDING.get(screen);
        if (pending == null) return;
        if (pending.framesLeft() <= 0) {
            PENDING.remove(screen);
            return;
        }
        PENDING.put(screen, new Pending(pending.pos(), pending.framesLeft() - 1));
        try {
            capture(pending.pos(), screen);
        } catch (Throwable t) {
            SFMGui.LOGGER.debug("slot layout re-capture failed at {}", pending.pos(), t);
        }
    }

    private static void capture(BlockPos pos, AbstractContainerScreen<?> screen) {
        var menu = screen.getMenu();
        if (menu == null || menu.slots.isEmpty()) return;

        // 客户端能力面：菜单槽容器与它是同一实例 → 容器内索引 = SFM 寻址的能力槽索引
        Object capability = clientCapability(pos);

        Inventory playerInv = Minecraft.getInstance().player != null
                ? Minecraft.getInstance().player.getInventory() : null;
        SlotCaptureCollector collector = new SlotCaptureCollector();
        for (Slot slot : menu.slots) {
            if (playerInv != null && slot.container == playerInv) continue;
            if (!slot.isActive()) continue;
            Integer capIndex = null;
            if (capability != null && slot.container == capability) {
                capIndex = slot.getContainerSlot();
            }
            ItemStack stack = slot.getItem();
            String item = "";
            int count = 0;
            if (stack != null && !stack.isEmpty()) {
                try {
                    item = BuiltInRegistries.ITEM.getKey(stack.getItem()).toString();
                } catch (Throwable t) {
                    item = "";
                }
                count = stack.getCount();
            }
            collector.add(new SlotLayoutData.SlotCapture(slot.x, slot.y, slot.getContainerSlot(), item, count, capIndex));
        }
        List<SlotLayoutData.SlotCapture> all = collector.slots();
        if (all.isEmpty()) return;

        // 多个槽挤在 (0,0) = 坐标不可信（异常菜单），放弃本次捕获
        long origin = all.stream().filter(c -> c.x() == 0 && c.y() == 0).count();
        if (origin > 1) return;

        // 存储顺序 = 空间顺序（先 y 后 x）：刷选/范围选沿空间相邻方向进行，
        // slot-layouts.json 也按阅读习惯排列。真实编号由 SlotNumbering 校准。
        all.sort((a, b) -> a.y() != b.y() ? Integer.compare(a.y(), b.y())
                : Integer.compare(a.x(), b.x()));

        String title = screen.getTitle() != null ? screen.getTitle().getString() : "";
        String menuClass = menu.getClass().getSimpleName();
        CACHE.capture(key(pos), title, menuClass, all);
    }

    /** Apply learned anchors only to the indexed layouts of this menu class. */
    public static void applyAnchor(BlockPos pos, String menuClass, int dir,
                                   int containerSlot, int x, int y, int capIndex) {
        CACHE.applyAnchor(new SlotLayoutData.SlotAnchor(menuClass == null ? "" : menuClass,
                dir, containerSlot, x, y, capIndex));
    }

    public static void setNoExposure(BlockPos pos) {
        CACHE.setNoExposure(key(pos));
    }

    /** The slot picker waits in the current screen until the initial cache is ready. */
    public static boolean isLoaded() {
        return CACHE.poll();
    }

    /** 按方块坐标查询捕获的布局；玩家没打开过该容器返回 null。 */
    public static SlotLayoutData.Layout get(BlockPos pos) {
        return CACHE.get(key(pos));
    }

    /** 客户端能力面实例（供实例匹配）；查询失败返回 null，绝不抛出。 */
    private static Object clientCapability(BlockPos pos) {
        try {
            var level = Minecraft.getInstance().level;
            if (level == null) return null;
            return level.getCapability(Capabilities.ItemHandler.BLOCK, pos, null);
        } catch (Throwable t) {
            return null;
        }
    }

    /** Client ticks only coordinate debounced background work; they never serialize JSON. */
    @SubscribeEvent
    public static void onClientTick(net.neoforged.neoforge.client.event.ClientTickEvent.Post event) {
        if (!PENDING.isEmpty()) PENDING.keySet().removeIf(screen -> screen != Minecraft.getInstance().screen);
        if (!CACHE.poll()) return;
        if (CACHE.takeChanged()) WRITER.changed();
        WRITER.tick(CACHE::snapshot);
    }
}
