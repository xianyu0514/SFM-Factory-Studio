package io.github.xianynomial.sfmfactorystudio.mixin;

import io.github.xianynomial.sfmfactorystudio.net.SlotCalibrationManager;
import net.minecraft.network.protocol.game.ServerboundContainerClickPacket;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.network.ServerGamePacketListenerImpl;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * 操作学习拦截：在容器点击处理的前后各注入一次，交给
 * {@link SlotCalibrationManager} 做能力槽内容差分——玩家的一次真实点击
 * 恰好改变某个能力槽时，"视觉格 ↔ 真实槽位"即被数据流证实（锚点）。
 * 只读观测，不改变任何原版行为。
 *
 * <p>1.20.1 Forge：方法名同时列出 Mojmap（开发环境）与 SRG（生产环境）
 * 两种形态并关闭 remap——运行时按实际存在的名字命中，两环境都生效。
 */
@Mixin(ServerGamePacketListenerImpl.class)
public abstract class ContainerClickMixin {

    @Shadow
    @Final
    public ServerPlayer player;

    @Inject(method = {"handleContainerClick", "m_5914_"}, at = @At("HEAD"), remap = false)
    private void sfmfactorystudio$beforeContainerClick(ServerboundContainerClickPacket packet, CallbackInfo ci) {
        SlotCalibrationManager.beforeClick(this.player, packet);
    }

    @Inject(method = {"handleContainerClick", "m_5914_"}, at = @At("RETURN"), remap = false)
    private void sfmfactorystudio$afterContainerClick(ServerboundContainerClickPacket packet, CallbackInfo ci) {
        SlotCalibrationManager.afterClick(this.player, packet);
    }
}
