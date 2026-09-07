package io.github.xianynomial.sfmfactorystudio.net;

import io.github.xianynomial.sfmfactorystudio.SFMGui;
import io.github.xianynomial.sfmfactorystudio.client.blocks.BlockEditorScreen;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ClientPlayerNetworkEvent;
import net.neoforged.neoforge.network.PacketDistributor;
import net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent;
import net.neoforged.neoforge.network.registration.PayloadRegistrar;

/**
 * Registers the addon's own network payloads (independent of SFM's channel).
 *
 * All payloads are optional: servers without this mod can still be joined.
 * Label sync silently degrades — unbound-label diagnostics disable themselves
 * on empty data and the label popup hides binding counts. Saving keeps working
 * because it goes through SFM's own packet (SFM is always on the server).
 */
@EventBusSubscriber(modid = SFMGui.MOD_ID)
public final class SFMGuiNetwork {
    private SFMGuiNetwork() {
    }

    /** Set once a send fails because the server lacks our optional channel. */
    private static volatile boolean serverUnsupported = false;

    @SubscribeEvent
    public static void register(RegisterPayloadHandlersEvent event) {
        PayloadRegistrar registrar = event.registrar("1").optional();
        registrar.playToServer(
                PullLabelsPayload.TYPE,
                PullLabelsPayload.CODEC,
                PullLabelsHandler::handle
        );
        registrar.playToServer(
                RequestLabelsPayload.TYPE,
                RequestLabelsPayload.CODEC,
                RequestLabelsHandler::handle
        );
        registrar.playToClient(
                UpdateLabelsPayload.TYPE,
                UpdateLabelsPayload.CODEC,
                (msg, ctx) -> ctx.enqueueWork(() -> BlockEditorScreen.acceptLabels(msg.labels()))
        );
        // SFM fork 的能力宣告（原版 SFM 服不会发，收到才解锁 NBT 区分等功能）
        SfmCapabilitiesPayload.registerClient(registrar);
        // 槽位可视化（beta）编号校准：客户端请求能力槽内容，服务端只读回包。
        // 只装客户端时请求无回应，选择器按超时降级为"未校准"模式并明示。
        SlotCapabilityRequestPayload.registerServer(registrar);
        SlotCapabilityPayload.registerClient(registrar);
        // 操作学习：会话登记（C2S）+ 差分锚点回传（S2C）+ 过程诊断（S2C）
        SlotCalibrationBeginPayload.registerServer(registrar);
        SlotAnchorPayload.registerClient(registrar);
        SlotCalibrationInfoPayload.registerClient(registrar);
    }

    /** 换服/断线时能力集清空：所有服务端门控功能回到默认隐藏。 */
    @SubscribeEvent
    public static void onLogout(ClientPlayerNetworkEvent.LoggingOut event) {
        try {
            SfmCaps.reset();
        } catch (Throwable t) {
            // 防御：类加载异常（如 jar 被运行中替换）不能炸掉登出事件链，
            // 否则后续所有模组的登出监听都被跳过
            SFMGui.LOGGER.warn("[sfmjimu] SfmCaps reset skipped: {}", t.toString());
        }
    }

    /**
     * Best-effort send that never throws on servers without the addon.
     * The first failure latches {@link #serverUnsupported} for the session.
     */
    public static void sendToServerBestEffort(CustomPacketPayload payload) {
        sendToServerBestEffortChecked(payload);
    }

    /** 同上，但返回是否真的发出去了（未装服务端时 false）。 */
    public static boolean sendToServerBestEffortChecked(CustomPacketPayload payload) {
        if (serverUnsupported) return false;
        try {
            PacketDistributor.sendToServer(payload);
            return true;
        } catch (Throwable t) {
            serverUnsupported = true;
            return false;
        }
    }

    /** False when the current server does not have this mod (label info unknown). */
    public static boolean labelsSupported() {
        return !serverUnsupported;
    }
}
