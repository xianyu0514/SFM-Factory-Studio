package io.github.xianynomial.sfmfactorystudio.client;

import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;
import io.github.xianynomial.sfmfactorystudio.SFMGui;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.inventory.Slot;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ScreenEvent;
import net.neoforged.neoforge.event.entity.player.PlayerInteractEvent;

import java.io.IOException;
import java.lang.reflect.Type;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 槽位布局捕获（按方块坐标键，100% 可靠的实现方式）：
 *
 * <p>玩家右键打开任何容器界面时——
 * ① {@link PlayerInteractEvent.RightClickBlock}（客户端也触发）记录刚点的方块坐标；
 * ② 容器界面初始化（{@link ScreenEvent.Init.Post}）时，该界面拥有**真实的槽位
 * 坐标**（就是此刻屏幕上画的原版布局），按坐标键存入本地 JSON。
 *
 * <p>之后编辑器的槽位可视化用同一个方块坐标查询——拿到的布局与原版界面
 * 完全一致（压印器的箭头形、融合工厂的彩色分排），对一切模组容器生效，
 * 且**不依赖服务端**：单人/服务端只装客户端均可。
 *
 * <p>槽位索引 = {@link Slot#getContainerSlot()}（容器内真实索引，与 SFML
 * {@code slot N} 语义一致）。
 */
@EventBusSubscriber(modid = SFMGui.MOD_ID, value = Dist.CLIENT)
public final class ClientGuiLayoutCache {
    private ClientGuiLayoutCache() {
    }

    /** 槽位布局：title 为容器界面标题；slots = [容器槽索引, x, y]。 */
    public record Layout(String title, List<int[]> slots) {
        public int totalSlots() {
            int max = -1;
            for (int[] s : slots) max = Math.max(max, s[0]);
            return max + 1;
        }
    }

    private static final Gson GSON = new Gson();
    private static final Map<String, Layout> BY_POS = new LinkedHashMap<>();
    private static BlockPos lastClickedPos = null;
    private static long lastClickedAt = 0;
    private static boolean loaded = false;

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

    /** Init 后尚未完成渲染期捕获的界面（晚绑定槽位的模组菜单需要渲染期补捕）。 */
    private static final Map<Screen, BlockPos> PENDING = new java.util.concurrent.ConcurrentHashMap<>();

    /** 容器界面初始化：按最近点击的方块坐标捕获真实槽位布局。 */
    @SubscribeEvent
    public static void onScreenInit(ScreenEvent.Init.Post event) {
        if (!(event.getScreen() instanceof AbstractContainerScreen<?> screen)) return;
        BlockPos pos = lastClickedPos;
        boolean fresh = pos != null && System.currentTimeMillis() - lastClickedAt < 3000;
        lastClickedPos = null;
        if (!fresh) return;
        PENDING.put(screen, pos);
        try {
            capture(pos, screen);   // 尽早尝试；若槽位晚绑定，渲染期会再补一次
        } catch (Throwable t) {
            SFMGui.LOGGER.debug("slot layout capture failed at {}", pos, t);
        }
    }

    /** 首帧渲染：菜单完全成型，晚绑定槽位此时已就位——补捕并保留更完整的结果。 */
    @SubscribeEvent
    public static void onContainerRender(net.neoforged.neoforge.client.event.ContainerScreenEvent.Render.Foreground event) {
        var screen = event.getContainerScreen();
        BlockPos pos = PENDING.remove(screen);
        if (pos == null) return;
        try {
            capture(pos, screen);
        } catch (Throwable t) {
            SFMGui.LOGGER.debug("slot layout re-capture failed at {}", pos, t);
        }
    }

    private static void capture(BlockPos pos, AbstractContainerScreen<?> screen) {
        var menu = screen.getMenu();
        if (menu == null || menu.slots.isEmpty()) return;
        ensureLoaded();

        // 只捕获容器自身的槽：剔除玩家背包槽（container == 玩家背包），
        // 跳过不渲染的幽灵槽（isActive=false），按容器内索引去重
        Inventory playerInv = Minecraft.getInstance().player != null
                ? Minecraft.getInstance().player.getInventory() : null;
        // 全量捕获所有非玩家槽位（一个不漏），编号=菜单顺序位置
        List<int[]> all = new ArrayList<>();
        for (Slot slot : menu.slots) {
            if (playerInv != null && slot.container == playerInv) continue;
            if (!slot.isActive()) continue;
            all.add(new int[]{0, slot.x, slot.y}); // {占位索引, x, y}
        }
        if (all.isEmpty()) return;
        // 重编号：去重后按 x+y 排序（先横后纵），索引从 0 递增
        all.sort((a, b) -> a[2] != b[2] ? Integer.compare(a[2], b[2]) : Integer.compare(a[1], b[1]));
        Map<Integer, int[]> byIndex = new java.util.TreeMap<>();
        for (int i = 0; i < all.size(); i++) {
            byIndex.put(i, new int[]{i, all.get(i)[1], all.get(i)[2]});
        }

        // 多个槽挤在 (0,0) = 坐标不可信（异常菜单），放弃本次捕获
        long origin = byIndex.values().stream().filter(c -> c[1] == 0 && c[2] == 0).count();
        if (origin > 1) return;

        List<int[]> slots = new ArrayList<>(byIndex.values());
        String title = screen.getTitle() != null ? screen.getTitle().getString() : "";
        var existing = BY_POS.get(key(pos));
        if (existing != null && existing.slots().size() > slots.size()) return; // 保留更完整捕获
        BY_POS.put(key(pos), new Layout(title, slots));
        save();
    }

    /** 按方块坐标查询捕获的布局；玩家没打开过该容器返回 null。 */
    public static Layout get(BlockPos pos) {
        ensureLoaded();
        return BY_POS.get(key(pos));
    }

    private static synchronized void ensureLoaded() {
        if (loaded) return;
        loaded = true;
        try {
            if (Files.exists(file())) {
                Type type = new TypeToken<LinkedHashMap<String, Layout>>() {
                }.getType();
                Map<String, Layout> read = GSON.fromJson(Files.readString(file()), type);
                if (read != null) BY_POS.putAll(read);
            }
        } catch (IOException | RuntimeException t) {
            SFMGui.LOGGER.warn("slot-layouts.json 读取失败，按空缓存继续: {}", t.toString());
        }
    }

    private static synchronized void save() {
        try {
            Files.createDirectories(file().getParent());
            Files.writeString(file(), GSON.newBuilder().setPrettyPrinting().create().toJson(BY_POS));
        } catch (IOException t) {
            SFMGui.LOGGER.warn("slot-layouts.json 写入失败: {}", t.toString());
        }
    }
}
