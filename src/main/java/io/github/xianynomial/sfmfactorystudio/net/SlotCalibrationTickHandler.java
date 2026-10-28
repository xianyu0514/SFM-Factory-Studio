package io.github.xianynomial.sfmfactorystudio.net;

import io.github.xianynomial.sfmfactorystudio.SFMGui;
import net.minecraft.server.MinecraftServer;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.tick.ServerTickEvent;

/**
 * 服务端 tick 驱动：推动操作学习会话的被动采样
 * （菜单槽与能力面内容的周期对比，同变化即锚定）。
 */
@EventBusSubscriber(modid = SFMGui.MOD_ID)
public final class SlotCalibrationTickHandler {
    private SlotCalibrationTickHandler() {
    }

    @SubscribeEvent
    public static void onServerTick(ServerTickEvent.Post event) {
        MinecraftServer server = event.getServer();
        if (server != null) {
            SlotCalibrationManager.tick(server);
        }
    }

    /**
     * 服务器停止：清空校准会话表。SESSIONS 是静态表，不清会让整棵
     * {@link MinecraftServer}（含全部已加载区块）无法被回收 —— 单机反复进出
     * 世界时内存持续上涨，且旧会话的采样基线会残留到下一次进入。
     */
    @SubscribeEvent
    public static void onServerStopped(
            net.neoforged.neoforge.event.server.ServerStoppedEvent event) {
        SlotCalibrationManager.clearSessions();
    }
}
