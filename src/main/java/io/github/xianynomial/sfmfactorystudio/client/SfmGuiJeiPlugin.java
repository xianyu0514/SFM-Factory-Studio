package io.github.xianynomial.sfmfactorystudio.client;

import io.github.xianynomial.sfmfactorystudio.SFMGui;
import mezz.jei.api.IModPlugin;
import mezz.jei.api.JeiPlugin;
import mezz.jei.api.gui.handlers.IGuiProperties;
import mezz.jei.api.registration.IGuiHandlerRegistration;
import mezz.jei.api.runtime.IJeiRuntime;
import io.github.xianynomial.sfmfactorystudio.client.blocks.BlockEditorScreen;

import net.minecraft.resources.ResourceLocation;

/**
 * JEI plugin that captures the JEI runtime for {@link JeiCompat}. Only loaded by JEI
 * when JEI is installed; when JEI is absent this class is never touched.
 */
@JeiPlugin
public class SfmGuiJeiPlugin implements IModPlugin {
    private static final ResourceLocation UID =
            new ResourceLocation(SFMGui.MOD_ID, "jei_plugin");

    @Override
    public ResourceLocation getPluginUid() {
        return UID;
    }

    @Override
    public void registerGuiHandlers(IGuiHandlerRegistration registration) {
        SFMGui.LOGGER.info("[sfmfactorystudio] JEI: registering ghost-drop + screen handlers for the block editor");
        registration.addGhostIngredientHandler(BlockEditorScreen.class, new JeiGhostHandler());

        // BlockEditorScreen 是普通 Screen（非容器界面），JEI 只对注册了 IGuiProperties
        // 的这类界面尝试画侧栏/书签。所有值必须是 **GUI 逻辑坐标**（与 getScreenWidth 同系），
        // 不是 ED 面板坐标 —— 旧版本的 getUiK 换算就是因为当年 panelX 用的是另一套坐标系。
        registration.addGuiScreenHandler(BlockEditorScreen.class, screen -> new IGuiProperties() {
            @Override
            public Class<? extends net.minecraft.client.gui.screens.Screen> getScreenClass() {
                return BlockEditorScreen.class;
            }

            @Override
            public int getGuiLeft() {
                return screen.getPanelX();
            }

            @Override
            public int getGuiTop() {
                return screen.getPanelY();
            }

            @Override
            public int getGuiXSize() {
                // 报告值限流到屏幕一半以内（JEI 实测超过 50% 就当全屏界面、不画侧栏）。
                // 实际绘制仍用 panelW，只是告诉 JEI “GUI 只占到这里”。
                return screen.getPanelW();
            }

            @Override
            public int getGuiYSize() {
                return screen.getPanelH();
            }

            @Override
            public int getScreenWidth() {
                return screen.safeScreenWidth();
            }

            @Override
            public int getScreenHeight() {
                return screen.safeScreenHeight();
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
