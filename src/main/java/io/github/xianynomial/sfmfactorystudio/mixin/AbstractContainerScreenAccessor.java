package io.github.xianynomial.sfmfactorystudio.mixin;

import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/**
 * 26.1.2 的 AbstractContainerScreen 仍然有 {@code protected int leftPos/topPos}，
 * 但移除了 1.21.1 的 public getter（getGuiLeft/getGuiTop）。
 * 加一个 accessor 拿回这两个字段，让界面定位与 1.21.1 完全一致，
 * 不必再猜「界面左上角在哪」。
 */
@Mixin(AbstractContainerScreen.class)
public interface AbstractContainerScreenAccessor {

    @Accessor("leftPos")
    int sfmfactorystudio$leftPos();

    @Accessor("topPos")
    int sfmfactorystudio$topPos();
}
