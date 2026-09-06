package io.github.xianynomial.sfmfactorystudio.client;

import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;
import io.github.xianynomial.sfmfactorystudio.SFMGui;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.inventory.Slot;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ScreenEvent;

import java.io.IOException;
import java.lang.reflect.Type;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 客户端 GUI 布局捕获（槽位可视化 beta 的核心）：
 * 玩家在游戏里正常打开任何容器界面时，把该界面**真实的槽位坐标**记录到
 * 本地 JSON。之后槽位可视化弹窗按这份坐标渲染——像素级还原原版界面布局，
 * 对所有模组容器天然生效（数据就来自玩家亲眼见过的界面本身）。
 *
 * <p>键 = 菜单类简名（同一种机器的菜单类固定）；只记录容器自身的槽
 * （剔除玩家物品栏槽，按 container 引用分组取非玩家背包的最大组）。
 */
@EventBusSubscriber(modid = SFMGui.MOD_ID, value = Dist.CLIENT)
public final class ClientGuiLayoutCache {
    private ClientGuiLayoutCache() {
    }

    public record Layout(String title, List<int[]> slots) {
    }

    private static final Gson GSON = new Gson();
    private static final Map<String, Layout> CACHE = new LinkedHashMap<>();
    private static boolean loaded = false;

    private static Path file() {
        return net.neoforged.fml.loading.FMLPaths.CONFIGDIR.get()
                .resolve("sfmfactorystudio").resolve("gui-layouts.json");
    }

    @SubscribeEvent
    public static void onScreenInit(ScreenEvent.Init.Post event) {
        if (!(event.getScreen() instanceof AbstractContainerScreen<?> screen)) return;
        try {
            capture(screen);
        } catch (Throwable t) {
            SFMGui.LOGGER.debug("gui layout capture failed", t);
        }
    }

    private static void capture(AbstractContainerScreen<?> screen) {
        var menu = screen.getMenu();
        if (menu == null || menu.slots.size() < 2) return;
        ensureLoaded();

        // 剔除玩家物品栏槽：container 引用 == 玩家背包 的跳过；
        // 剩余按 container 分组取最大组（容器自身的槽共享同一 container）
        Inventory playerInv = Minecraft.getInstance().player != null
                ? Minecraft.getInstance().player.getInventory() : null;
        Map<Object, List<Slot>> groups = new LinkedHashMap<>();
        for (Slot slot : menu.slots) {
            if (playerInv != null && slot.container == playerInv) continue;
            // 幽灵槽过滤：isActive=false 的槽不渲染（如 AE2 压印器的图案提示格），
            // 它们的坐标无意义且会以 (0,0) 叠堆污染布局
            if (!slot.isActive()) continue;
            groups.computeIfAbsent(slot.container, k -> new ArrayList<>()).add(slot);
        }
        List<Slot> primary = null;
        for (List<Slot> g : groups.values()) {
            if (primary == null || g.size() > primary.size()) primary = g;
        }
        if (primary == null || primary.isEmpty()) return;

        String key = menu.getClass().getSimpleName();
        // 按容器内索引去重（同 index 只保留第一个）：幽灵槽可能与实槽同 index
        Map<Integer, int[]> byIndex = new java.util.TreeMap<>();
        for (Slot slot : primary) {
            int idx = slot.getContainerSlot();
            if (idx < 0) continue;
            byIndex.putIfAbsent(idx, new int[]{slot.x, slot.y});
        }
        if (byIndex.isEmpty()) return;
        // (0,0) 叠堆残留防护：多个槽挤在原点说明坐标不可信，放弃本次捕获
        long origin = byIndex.values().stream().filter(c -> c[0] == 0 && c[1] == 0).count();
        if (origin > 1 || (origin == 1 && byIndex.size() > 1)) return;
        List<int[]> slots = new ArrayList<>();
        for (var e : byIndex.entrySet()) slots.add(new int[]{e.getKey(), e.getValue()[0], e.getValue()[1]});
        String title = screen.getTitle() != null ? screen.getTitle().getString() : "";

        Layout existing = CACHE.get(key);
        if (existing != null && existing.slots().size() == slots.size()) {
            return; // 未变化不重写
        }
        CACHE.put(key, new Layout(title, slots));
        save();
    }

    /** 按菜单类简名查询捕获到的真实布局；没有返回 null。 */
    public static Layout get(String menuClassName) {
        ensureLoaded();
        return CACHE.get(menuClassName);
    }

    private static synchronized void ensureLoaded() {
        if (loaded) return;
        loaded = true;
        try {
            if (Files.exists(file())) {
                Type type = new TypeToken<LinkedHashMap<String, Layout>>() {
                }.getType();
                Map<String, Layout> read = GSON.fromJson(Files.readString(file()), type);
                if (read != null) CACHE.putAll(read);
            }
        } catch (IOException | RuntimeException t) {
            SFMGui.LOGGER.warn("gui-layouts.json 读取失败，按空缓存继续: {}", t.toString());
        }
    }

    private static synchronized void save() {
        try {
            Files.createDirectories(file().getParent());
            Files.writeString(file(), GSON.newBuilder().setPrettyPrinting().create().toJson(CACHE));
        } catch (IOException t) {
            SFMGui.LOGGER.warn("gui-layouts.json 写入失败: {}", t.toString());
        }
    }
}
