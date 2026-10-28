package io.github.xianynomial.sfmfactorystudio.client;

import io.github.xianynomial.sfmfactorystudio.SFMGui;
import mezz.jei.api.IModPlugin;
import mezz.jei.api.JeiPlugin;
import mezz.jei.api.gui.handlers.IGuiProperties;
import mezz.jei.api.registration.IGuiHandlerRegistration;
import mezz.jei.api.runtime.IJeiRuntime;
import io.github.xianynomial.sfmfactorystudio.client.blocks.BlockEditorScreen;

import net.minecraft.resources.Identifier;

/**
 * JEI plugin that captures the JEI runtime for {@link JeiCompat}.
 *
 * <p>BlockEditorScreen 是普通 Screen（非容器界面），JEI 只对注册了 IGuiProperties
 * 的这类界面尝试绘制原料列表/书签。所有值必须是 GUI 逻辑坐标。
 *
 * <p>关键点：JEI 的侧栏起点是 {@code guiRight = guiLeft + guiXSize}，它只画在
 * {@code guiRight} 右边，且右边空间不足时**直接不画**（不会压缩自己）。
 * 所以这里如实上报 {@code screen.getPanelW()}（= 真实面板宽，与 0.9.1 一致），
 * 面板是否靠左让位由 {@code BlockEditorScreen#computePanel} 按实际空间决定 ——
 * 既不会重叠，也不会出现"报了假宽度、侧栏压面板"的老问题。
 */
@JeiPlugin
public class SfmGuiJeiPlugin implements IModPlugin {
    private static final Identifier UID =
            Identifier.fromNamespaceAndPath(SFMGui.MOD_ID, "jei_plugin");

    @Override
    public Identifier getPluginUid() {
        return UID;
    }

    @Override
    public void registerGuiHandlers(IGuiHandlerRegistration registration) {
        SFMGui.LOGGER.info("[sfmfactorystudio] JEI: registering ghost-drop + screen handlers for the block editor");
        registration.addGhostIngredientHandler(BlockEditorScreen.class, new JeiGhostHandler());

        registration.addGuiScreenHandler(BlockEditorScreen.class, screen -> new IGuiProperties() {
            @Override
            public Class<? extends net.minecraft.client.gui.screens.Screen> screenClass() {
                return BlockEditorScreen.class;
            }

            @Override
            public int guiLeft() {
                return screen.getPanelX();
            }

            @Override
            public int guiTop() {
                return screen.getPanelY();
            }

            @Override
            public int guiXSize() {
                return screen.getPanelW();
            }

            @Override
            public int guiYSize() {
                return screen.getPanelH();
            }

            @Override
            public int screenWidth() {
                return screen.safeScreenWidth();
            }

            @Override
            public int screenHeight() {
                return screen.safeScreenHeight();
            }

            @Override
            public int guiRight() {
                return screen.getPanelX() + screen.getPanelW();
            }
        });
    }

    @Override
    public void onRuntimeAvailable(IJeiRuntime jeiRuntime) {
        SFMGui.LOGGER.info("[sfmfactorystudio] JEI: runtime available — JEI integration active");
        JeiRuntimeHolder.setRuntime(jeiRuntime);
        SFMGui.LOGGER.info("[sfmfactorystudio] JEI runtime available");
        JeiCompat.onRuntimeAvailable();
    }

    @Override
    public void onRuntimeUnavailable() {
        SFMGui.LOGGER.warn("[sfmfactorystudio] JEI: runtime unavailable");
        JeiRuntimeHolder.setRuntime(null);
        JeiCompat.onRuntimeUnavailable();
    }
}
