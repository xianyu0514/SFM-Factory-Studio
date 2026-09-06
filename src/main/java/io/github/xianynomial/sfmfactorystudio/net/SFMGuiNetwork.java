package io.github.xianynomial.sfmfactorystudio.net;

import io.github.xianynomial.sfmfactorystudio.SFMGui;
import net.minecraft.resources.ResourceLocation;
import net.minecraftforge.network.NetworkRegistry;
import net.minecraftforge.network.simple.SimpleChannel;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.event.lifecycle.FMLCommonSetupEvent;

/**
 * Registers the addon's own Forge 1.20.1 {@link SimpleChannel}
 * (independent of SFM's channel).
 *
 * The channel is fully optional: the version predicates accept anything,
 * so servers without this mod can still be joined and {@link #sendToServer}
 * simply no-ops when the remote end lacks the channel.
 */
@Mod.EventBusSubscriber(modid = SFMGui.MOD_ID, bus = Mod.EventBusSubscriber.Bus.MOD)
public final class SFMGuiNetwork {
    private SFMGuiNetwork() {
    }

    private static final String PROTOCOL = "2";

    public static final SimpleChannel CHANNEL = NetworkRegistry.newSimpleChannel(
            new ResourceLocation(SFMGui.MOD_ID, "main"),
            () -> PROTOCOL,
            // Accept any/absent remote version so vanilla-SFM servers stay joinable.
            remoteVersion -> true,
            remoteVersion -> true
    );

    @SubscribeEvent
    public static void onCommonSetup(FMLCommonSetupEvent event) {
        event.enqueueWork(() -> {
            int id = 0;
            // 客户端 → 服务端
            CHANNEL.registerMessage(id++, PullLabelsPacket.class,
                    PullLabelsPacket::encode,
                    PullLabelsPacket::decode,
                    PullLabelsPacket::handle);
            CHANNEL.registerMessage(id++, RequestLabelsPayload.class,
                    RequestLabelsPayload::encode,
                    RequestLabelsPayload::decode,
                    RequestLabelsHandler::handle);
            CHANNEL.registerMessage(id++, SlotLayoutRequestPayload.class,
                    SlotLayoutRequestPayload::encode,
                    SlotLayoutRequestPayload::decode,
                    SlotLayoutRequestHandler::handle);
            // 服务端 → 客户端
            CHANNEL.registerMessage(id++, UpdateLabelsPayload.class,
                    UpdateLabelsPayload::encode,
                    UpdateLabelsPayload::decode,
                    UpdateLabelsPayload::handle,
                    java.util.Optional.of(net.minecraftforge.network.NetworkDirection.PLAY_TO_CLIENT));
            // NBT 能力宣告（服务端 → 客户端，登录时由 SfmCapabilityPusher 发送）。
            CHANNEL.registerMessage(id++, SfmCapabilitiesPayload.class,
                    SfmCapabilitiesPayload::encode,
                    SfmCapabilitiesPayload::decode,
                    SfmCapabilitiesPayload::handle,
                    java.util.Optional.of(net.minecraftforge.network.NetworkDirection.PLAY_TO_CLIENT));
            // 槽位布局快照（服务端 → 客户端，槽位可视化 beta）
            CHANNEL.registerMessage(id++, SlotLayoutPayload.class,
                    SlotLayoutPayload::encode,
                    SlotLayoutPayload::decode,
                    SlotLayoutPayload::handle,
                    java.util.Optional.of(net.minecraftforge.network.NetworkDirection.PLAY_TO_CLIENT));
        });
    }

    /**
     * Best-effort send that never throws on servers without the addon.
     */
    public static void sendToServer(Object message) {
        try {
            CHANNEL.sendToServer(message);
        } catch (Throwable ignored) {
            // remote end does not have our optional channel
        }
    }

    /**
     * 同上，但返回是否真的发出去了（未装服务端时 false，槽位可视化的
     * β 入口据此决定是否继续等待回包）。
     */
    public static boolean sendToServerBestEffortChecked(Object message) {
        try {
            CHANNEL.sendToServer(message);
            return true;
        } catch (Throwable ignored) {
            return false;
        }
    }
}
