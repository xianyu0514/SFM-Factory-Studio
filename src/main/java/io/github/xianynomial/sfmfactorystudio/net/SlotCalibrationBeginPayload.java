package io.github.xianynomial.sfmfactorystudio.net;

import net.minecraft.core.BlockPos;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.neoforged.neoforge.network.registration.PayloadRegistrar;

/**
 * 客户端 → 服务端：开启操作学习会话。玩家打开容器界面时发送——此后该玩家
 * 在此界面里的每次真实点击，服务端都会差分能力槽内容，把"视觉格 ↔ 真实槽位"
 * 锚定后回传（见 SlotAnchorPayload）。对一切模组容器生效，无需模组适配。
 */
public record SlotCalibrationBeginPayload(BlockPos pos, int containerId) implements CustomPacketPayload {
    public static final CustomPacketPayload.Type<SlotCalibrationBeginPayload> TYPE =
            new CustomPacketPayload.Type<>(net.minecraft.resources.ResourceLocation.fromNamespaceAndPath(
                    "sfmfactorystudio", "slot_calibration_begin"));

    public static final StreamCodec<FriendlyByteBuf, SlotCalibrationBeginPayload> CODEC = CustomPacketPayload.codec(
            SlotCalibrationBeginPayload::write,
            SlotCalibrationBeginPayload::read);

    private static SlotCalibrationBeginPayload read(FriendlyByteBuf buf) {
        return new SlotCalibrationBeginPayload(BlockPos.of(buf.readLong()), buf.readVarInt());
    }

    private void write(FriendlyByteBuf buf) {
        buf.writeLong(pos.asLong());
        buf.writeVarInt(containerId);
    }

    public static void registerServer(PayloadRegistrar registrar) {
        registrar.playToServer(TYPE, CODEC,
                (msg, ctx) -> ctx.enqueueWork(() ->
                        {
                        if (!(ctx.player() instanceof net.minecraft.server.level.ServerPlayer sp)) return;
                        SlotCalibrationManager.begin(sp, msg.pos(), msg.containerId());
                    }));
    }

    @Override
    public CustomPacketPayload.Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
