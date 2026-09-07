package io.github.xianynomial.sfmfactorystudio.net;

import io.github.xianynomial.sfmfactorystudio.SFMGui;
import net.minecraft.server.MinecraftServer;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

/**
 * 服务端 tick 驱动：推动操作学习会话的被动采样
 * （菜单槽与能力面内容的周期对比，同变化即锚定）。
 */
@Mod.EventBusSubscriber(modid = SFMGui.MOD_ID)
public final class SlotCalibrationTickHandler {
    private SlotCalibrationTickHandler() {
    }

    @SubscribeEvent
    public static void onServerTick(TickEvent.ServerTickEvent event) {
        if (event.phase != TickEvent.Phase.END) return;
        MinecraftServer server = net.minecraftforge.server.ServerLifecycleHooks.getCurrentServer();
        if (server != null) {
            SlotCalibrationManager.tick(server);
        }
    }
}
