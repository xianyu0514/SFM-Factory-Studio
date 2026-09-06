package io.github.xianynomial.sfmfactorystudio.net;

import ca.teamdman.sfm.client.screen.ManagerScreen;
import ca.teamdman.sfm.common.containermenu.ManagerContainerMenu;
import io.github.xianynomial.sfmfactorystudio.client.blocks.BlockEditorScreen;
import net.minecraft.client.Minecraft;

/**
 * Bridges the block editor to SFM's manager screen using only SFM's public API:
 * reads the current program string, opens the block editor, and saves by sending
 * SFM's own program packet (the editor saves directly).
 */
public final class OpenEditorHelper {
    private OpenEditorHelper() {
    }

    public static void open(ManagerScreen managerScreen) {
        ManagerContainerMenu menu = managerScreen.getMenu();
        String program = menu.program == null ? "" : menu.program;

        BlockEditorScreen editor = new BlockEditorScreen(
                menu,
                program,
                () -> Minecraft.getInstance().setScreen(managerScreen)
        );
        Minecraft.getInstance().setScreen(editor);
    }
}
