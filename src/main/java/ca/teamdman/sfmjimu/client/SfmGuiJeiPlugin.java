package ca.teamdman.sfmjimu.client;

import ca.teamdman.sfmjimu.SFMGui;
import mezz.jei.api.IModPlugin;
import mezz.jei.api.JeiPlugin;
import mezz.jei.api.gui.handlers.IGuiProperties;
import mezz.jei.api.registration.IGuiHandlerRegistration;
import mezz.jei.api.runtime.IJeiRuntime;
import ca.teamdman.sfm.client.screen.ManagerScreen;
import ca.teamdman.sfmjimu.client.blocks.BlockEditorScreen;
import mezz.jei.api.gui.handlers.IGuiContainerHandler;

import java.util.List;
import net.minecraft.resources.ResourceLocation;

/**
 * JEI plugin that captures the JEI runtime for {@link JeiCompat}. Only loaded by JEI
 * when JEI is installed; when JEI is absent this class is never touched.
 */
@JeiPlugin
public class SfmGuiJeiPlugin implements IModPlugin {
    private static final ResourceLocation UID =
            ResourceLocation.fromNamespaceAndPath(SFMGui.MOD_ID, "jei_plugin");

    @Override
    public ResourceLocation getPluginUid() {
        return UID;
    }

    @Override
    public void registerGuiHandlers(IGuiHandlerRegistration registration) {
        ca.teamdman.sfmjimu.SFMGui.LOGGER.info("[sfmjimu] JEI: registering ghost-drop + screen handlers for the block editor");
        registration.addGhostIngredientHandler(BlockEditorScreen.class, new JeiGhostHandler());
        // dedicated adapter: JEI draws its ingredient list (with tabs) beside the
        // editor window — the editor reserves the right margin so nothing overlaps
        // The manager screen already shows SFM's own log/program UI; claim the
        // whole screen as "extra gui area" so JEI hides its overlay there.
        registration.addGuiContainerHandler(ManagerScreen.class, new IGuiContainerHandler<>() {
            @Override
            public List<net.minecraft.client.renderer.Rect2i> getGuiExtraAreas(ManagerScreen screen) {
                return List.of(new net.minecraft.client.renderer.Rect2i(
                        0, 0, screen.width, screen.height));
            }
        });

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
        });
    }

    @Override
    public void onRuntimeAvailable(IJeiRuntime jeiRuntime) {
        ca.teamdman.sfmjimu.SFMGui.LOGGER.info("[sfmjimu] JEI: runtime available — JEI integration active");
        JeiRuntimeHolder.setRuntime(jeiRuntime);
        JeiCompat.onRuntimeAvailable();
    }

    @Override
    public void onRuntimeUnavailable() {
        ca.teamdman.sfmjimu.SFMGui.LOGGER.warn("[sfmjimu] JEI: runtime unavailable");
        JeiRuntimeHolder.setRuntime(null);
        JeiCompat.onRuntimeUnavailable();
    }
}
