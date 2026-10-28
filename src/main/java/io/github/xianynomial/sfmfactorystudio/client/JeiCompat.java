package io.github.xianynomial.sfmfactorystudio.client;

import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.world.item.ItemStack;
import java.util.List;

/**
 * Optional bridge to JEI. All JEI API references live behind this class and the
 * {@link SfmGuiJeiPlugin} that feeds it a runtime; when JEI is not installed every
 * method degrades gracefully (returns null / falls back to vanilla rendering), so the
 * addon works with or without JEI — mirroring the existing Mekanism guard pattern.
 * <p>
 * Why JEI: enumerating {@code BuiltInRegistries.ITEM} yields one bare {@code ItemStack}
 * per item, some of which render blank (need data components) and don't distinguish
 * subtypes/variants. JEI's ingredient list already contains the display-ready stacks and
 * a renderer that draws them correctly, so when present we use it for the picker grid.
 */
public final class JeiCompat {
    private JeiCompat() {
    }

    private static volatile boolean runtimeReady = false;
    /** 一旦 JEI 运行时就绪过就置为 true，永不复位。
     *  JEI 会在进世界时重置 runtime，但“装了 JEI”事实不会变。 */
    private static volatile boolean everReady = false;
    /** 诊断日志只打一次。 */
    private static volatile boolean diagnosed = false;

    /**
     * JEI 模组是否安装。
     *
     * <p><b>布局判断必须用这个</b>：{@link #isAvailable()} 依赖 JEI 的运行时，
     * 而 JEI 会在进世界 / 资源重载时调 {@code onRuntimeUnavailable} 把它打回 false。
     * 实测：20:16:55 可用，20:16:59 失效 ⇒ 面板从 74% 跳回 94%，JEI 侧栏没地方。
     */
    public static boolean isInstalled() {
        if (everReady) {
            if (!diagnosed) { diagnosed = true; io.github.xianynomial.sfmfactorystudio.SFMGui.LOGGER.info("[jei] isInstalled=true (everReady)"); }
            return true;   // 运行时证据最硬，不依赖 ModList
        }
        try {
            boolean loaded = net.minecraftforge.fml.ModList.get().isLoaded("jei");
            if (!diagnosed) {
                diagnosed = true;
                io.github.xianynomial.sfmfactorystudio.SFMGui.LOGGER.info("[jei] isInstalled={} (ModList) loader={}", loaded, JeiCompat.class.getClassLoader());
            }
            return loaded;
        } catch (Throwable t) {
            if (!diagnosed) {
                diagnosed = true;
                io.github.xianynomial.sfmfactorystudio.SFMGui.LOGGER.warn("[jei] isInstalled: ModList lookup threw", t);
            }
            return false;
        }
    }

    /** 诊断用：不依赖运行时就绪也不报错，只返回可读状态。 */
    public static String overlayState() {
        try {
            return JeiRuntimeHolder.overlayState();
        } catch (Throwable t) {
            return "unavailable";
        }
    }

    /** 诊断用：把 JEI 判定"是否画侧栏"的三个条件逐个读出来（见 JeiRuntimeHolder）。 */
    public static String overlayDiagnostics() {
        try {
            return JeiRuntimeHolder.overlayDiagnostics();
        } catch (Throwable t) {
            return "unavailable";
        }
    }

    /** True if JEI is loaded AND its runtime is available (set by the plugin). */
    public static boolean isAvailable() {
        // Only JEI's own plugin can set this flag, so a ModList lookup on every
        // rendered item icon is redundant.
        return runtimeReady;
    }

    // ===== called by SfmGuiJeiPlugin (only classloaded when JEI is present) =====

    static void onRuntimeAvailable() {
        runtimeReady = true;
        everReady = true;
    }

    static void onRuntimeUnavailable() {
        runtimeReady = false;
    }

    /**
     * All item stacks JEI would display, or {@code null} if JEI is unavailable. Isolated
     * so JEI classes are only touched when {@link #isAvailable()} is true.
     */
    public static List<ItemStack> itemStacksOrNull() {
        if (!isAvailable()) {
            return null;
        }
        try {
            return JeiRuntimeHolder.itemStacks();
        } catch (Throwable t) {
            return null;
        }
    }

    /**
     * Render an item icon via JEI's renderer when available, else via vanilla
     * {@link GuiGraphics#renderItem}. JEI's renderer draws at (0,0), so we translate.
     */
    public static void renderItem(GuiGraphics g, ItemStack stack, int x, int y) {
        if (isAvailable()) {
            try {
                JeiRuntimeHolder.renderItem(g, stack, x, y);
                return;
            } catch (Throwable ignored) {
                // fall through to vanilla
            }
        }
        g.renderItem(stack, x, y);
    }
}
