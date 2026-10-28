package io.github.xianynomial.sfmfactorystudio.client;

import mezz.jei.api.constants.VanillaTypes;
import mezz.jei.api.ingredients.IIngredientRenderer;
import mezz.jei.api.runtime.IIngredientManager;
import mezz.jei.api.runtime.IJeiRuntime;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.world.item.ItemStack;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;

/**
 * Holds the live JEI runtime and does the actual JEI API calls. This class directly
 * references JEI types, so it is only ever classloaded when JEI is installed (all entry
 * points go through {@link JeiCompat}, which guards on {@code isAvailable()} first).
 */
final class JeiRuntimeHolder {
    private static @Nullable IJeiRuntime runtime = null;

    private JeiRuntimeHolder() {
    }

    static void setRuntime(@Nullable IJeiRuntime rt) {
        runtime = rt;
    }

    /** 诊断用：JEI 自己认为原料列表是否显示。 */
    static String overlayState() {
        if (runtime == null) return "no-runtime";
        try {
            return "displayed=" + runtime.getIngredientListOverlay().isListDisplayed();
        } catch (Throwable t) {
            return "err:" + t.getClass().getSimpleName();
        }
    }

    /**
     * 诊断用：把 JEI 判定 {@code isListDisplayed()} 的三个条件逐个读出来。
     *
     * <p>JEI 15.56 的判定是
     * {@code toggleState.isOverlayEnabled() && screenPropertiesCache.hasValidScreen() && contents.hasRoom()}，
     * 任何一个为 false 都不画。{@code isListDisplayed()} 只给聚合结果，排查时分不清是哪个，
     * 所以这里用反射把两个私有条件读出来（JEI 不混淆，dev 与生产都用真名）。
     *
     * <p>读失败时只返回聚合值，绝不抛异常 —— 这是诊断代码，不能影响界面。
     */
    static String overlayDiagnostics() {
        if (runtime == null) return "no-runtime";
        boolean displayed;
        try {
            displayed = runtime.getIngredientListOverlay().isListDisplayed();
        } catch (Throwable t) {
            return "err:" + t.getClass().getSimpleName();
        }
        String valid = "?", enabled = "?";
        try {
            var overlay = runtime.getIngredientListOverlay();
            var f = overlay.getClass().getDeclaredField("screenPropertiesCache");
            f.setAccessible(true);
            Object cache = f.get(overlay);
            if (cache != null) {
                var m = cache.getClass().getMethod("hasValidScreen");
                valid = String.valueOf(m.invoke(cache));
            }
        } catch (Throwable ignored) {
            // 字段名在 JEI 各版本间可能变化，读不到就留 "?"
        }
        try {
            var overlay = runtime.getIngredientListOverlay();
            var f = overlay.getClass().getDeclaredField("toggleState");
            f.setAccessible(true);
            Object ts = f.get(overlay);
            if (ts != null) {
                var m = ts.getClass().getMethod("isOverlayEnabled");
                enabled = String.valueOf(m.invoke(ts));
            }
        } catch (Throwable ignored) {
        }
        return "displayed=" + displayed + " validScreen=" + valid + " overlayEnabled=" + enabled;
    }

    /** All item stacks in JEI's ingredient list (display-ready, subtype-aware). */
    static List<ItemStack> itemStacks() {
        IJeiRuntime rt = runtime;
        if (rt == null) {
            return new ArrayList<>();
        }
        IIngredientManager mgr = rt.getIngredientManager();
        return new ArrayList<>(mgr.getAllIngredients(VanillaTypes.ITEM_STACK));
    }

    /** Render an item via JEI's ingredient renderer (draws at origin, so translate). */
    static void renderItem(GuiGraphicsExtractor g, ItemStack stack, int x, int y) {
        IJeiRuntime rt = runtime;
        if (rt == null) {
            g.item(stack, x, y);
            return;
        }
        IIngredientManager mgr = rt.getIngredientManager();
        IIngredientRenderer<ItemStack> renderer = mgr.getIngredientRenderer(VanillaTypes.ITEM_STACK);
        var pose = g.pose();
        pose.pushMatrix();
        pose.translate(x, y);
        try {
            renderer.render(g, stack);
        } finally {
            pose.popMatrix();
        }
    }
}
