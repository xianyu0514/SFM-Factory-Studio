package io.github.xianynomial.sfmfactorystudio.net;

import io.github.xianynomial.sfmfactorystudio.SFMGui;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import net.neoforged.neoforge.network.PacketDistributor;

import java.util.List;

/**
 * 服务器端（装了本附属时）在玩家登录后推送能力声明。只有 Mixin 注入真正
 * 生效（NbtMatcherHook.isAvailable）才宣告 with_component——SFM 更新导致
 * 注入失效时静默不宣告，编辑器功能保持隐藏而不是误开。
 */
@EventBusSubscriber(modid = SFMGui.MOD_ID)
public final class SfmCapabilityPusher {
    private SfmCapabilityPusher() {
    }

    @SubscribeEvent
    public static void onLogin(PlayerEvent.PlayerLoggedInEvent event) {
        if (!(event.getEntity() instanceof ServerPlayer player)) return;
        if (!NbtMatcherHook.isAvailable()) return;
        try {
            PacketDistributor.sendToPlayer(player,
                    new SfmCapabilitiesPayload(List.of("with_component")));
        } catch (Throwable ignored) {
            // 客户端没有对应的 optional 通道：跳过即可
        }
    }
}
