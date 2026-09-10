package io.github.xianynomial.sfmfactorystudio.client;

import ca.teamdman.sfm.client.screen.ManagerScreen;
import io.github.xianynomial.sfmfactorystudio.SFMGui;
import io.github.xianynomial.sfmfactorystudio.net.OpenEditorHelper;
import io.github.xianynomial.sfmfactorystudio.net.PullLabelsPacket;
import io.github.xianynomial.sfmfactorystudio.net.SFMGuiNetwork;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.client.event.ScreenEvent;
import net.minecraftforge.event.TagsUpdatedEvent;

/**
 * Injects addon buttons ("Visual Edit" and "Pull Labels") onto SFM's manager
 * screen without modifying SFM. The buttons are real vanilla {@link Button}
 * widgets rendered from the render event, so they look exactly like SFM's own
 * buttons (user feedback: hand-drawn flat fills read as "not clickable").
 * Clicks are still handled from the mouse event with the same bounds.
 */
@Mod.EventBusSubscriber(modid = SFMGui.MOD_ID, value = Dist.CLIENT)
public final class SFMGuiClientEvents {
    public static final Loc VISUAL_EDIT = new Loc("gui.sfmfactorystudio.manager.visual_edit", "Visual Edit");
    public static final Loc PULL_LABELS = new Loc("gui.sfmfactorystudio.manager.pull_labels", "Pull Labels");

    /** Button of the most recent screen press; JEI ghost drags read this (1 = right). */
    public static int lastPressButton = 0;

    @SubscribeEvent
    public static void onPressPre(ScreenEvent.MouseButtonPressed.Pre event) {
        lastPressButton = event.getButton();
    }

    @SubscribeEvent
    public static void onTagsUpdated(TagsUpdatedEvent event) {
        ResourceTagIndex.invalidate();
    }

    // Match SFM's left button column: x = guiLeft - 120, w = 120, h = 16. The two
    // addon buttons sit stacked in the 34px gap between SFM's "Paste from clipboard"
    // (bottom at guiTop+32) and "Edit" (top at guiTop+66) buttons.
    private static final int BTN_W = 120;
    private static final int BTN_H = 16;
    private static final int COL_DX = 120; // left column offset from guiLeft

    /** Render-only vanilla widgets; recreated on every screen init (covers resize). */
    private static Button visualBtn;
    private static Button pullBtn;

    private SFMGuiClientEvents() {
    }

    private static int colX(AbstractContainerScreen<?> s) {
        return s.getGuiLeft() - COL_DX;
    }

    /** Visual-edit button: first of the two, at guiTop + 32. */
    private static int visualY(AbstractContainerScreen<?> s) {
        return s.getGuiTop() + 32;
    }

    /** Pull-labels button: stacked directly below, at guiTop + 49. */
    private static int pullY(AbstractContainerScreen<?> s) {
        return s.getGuiTop() + 49;
    }

    @SubscribeEvent
    public static void onInitPost(ScreenEvent.Init.Post event) {
        if (!(event.getScreen() instanceof ManagerScreen ms)) {
            visualBtn = null;
            pullBtn = null;
            return;
        }
        int x = colX(ms);
        visualBtn = Button.builder(VISUAL_EDIT.getComponent(), b -> {
        }).bounds(x, visualY(ms), BTN_W, BTN_H).build();
        pullBtn = Button.builder(PULL_LABELS.getComponent(), b -> {
        }).bounds(x, pullY(ms), BTN_W, BTN_H).build();
    }

    @SubscribeEvent
    public static void onRenderPost(ScreenEvent.Render.Post event) {
        if (!(event.getScreen() instanceof ManagerScreen ms) || visualBtn == null || pullBtn == null) {
            return;
        }
        visualBtn.render(event.getGuiGraphics(), event.getMouseX(), event.getMouseY(), event.getPartialTick());
        pullBtn.render(event.getGuiGraphics(), event.getMouseX(), event.getMouseY(), event.getPartialTick());
        // 专属标识（用户反馈：要能和原生按钮区分）——左缘竖向强调条，与编辑器卡片的左色条同一视觉语言
        GuiGraphics g = event.getGuiGraphics();
        int x = colX(ms);
        g.fill(x + 2, visualY(ms) + 2, x + 5, visualY(ms) + BTN_H - 2, 0xFF2F6FED);
        g.fill(x + 2, pullY(ms) + 2, x + 5, pullY(ms) + BTN_H - 2, 0xFF2F6FED);
    }

    @SubscribeEvent
    public static void onMousePressedPre(ScreenEvent.MouseButtonPressed.Pre event) {
        if (!(event.getScreen() instanceof ManagerScreen ms)) {
            return;
        }
        if (event.getButton() != 0) {
            return;
        }
        double mx = event.getMouseX(), my = event.getMouseY();
        int x = colX(ms);
        if (inside(mx, my, x, visualY(ms))) {
            try {
                OpenEditorHelper.open(ms);
            } catch (Throwable t) {
                SFMGui.LOGGER.error("Failed to open visual editor", t);
            }
            event.setCanceled(true);
        } else if (inside(mx, my, x, pullY(ms))) {
            try {
                SFMGuiNetwork.sendToServer(new PullLabelsPacket(ms.getMenu().MANAGER_POSITION));
            } catch (Throwable t) {
                SFMGui.LOGGER.error("Failed to pull labels", t);
            }
            event.setCanceled(true);
        }
    }

    private static boolean inside(double mx, double my, int x, int y) {
        return mx >= x && mx <= x + BTN_W && my >= y && my <= y + BTN_H;
    }
}
