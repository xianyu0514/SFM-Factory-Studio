package io.github.xianynomial.sfmfactorystudio.net;

import net.minecraft.core.BlockPos;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.neoforged.neoforge.network.registration.PayloadRegistrar;

/**
 * 服务端 → 客户端：操作学习的过程性诊断。hint =
 * {@link #INFO_NO_EXPOSURE}——机器界面的槽位在真实变化，但七个朝向的能力面
 * 全程无变化：该机器的槽位未暴露给 SFM 可见的任何管道（典型如 Mekanism
 * 需要先在机器侧面配置中开放输入/输出）。
 */
public record SlotCalibrationInfoPayload(BlockPos pos, int hint) implements CustomPacketPayload {
    public static final CustomPacketPayload.Type<SlotCalibrationInfoPayload> TYPE =
            new CustomPacketPayload.Type<>(net.minecraft.resources.ResourceLocation.fromNamespaceAndPath(
                    "sfmfactorystudio", "slot_calibration_info"));

    public static final int INFO_NO_EXPOSURE = 1;

    public static final StreamCodec<FriendlyByteBuf, SlotCalibrationInfoPayload> CODEC = CustomPacketPayload.codec(
            SlotCalibrationInfoPayload::write,
            SlotCalibrationInfoPayload::read);

    private static SlotCalibrationInfoPayload read(FriendlyByteBuf buf) {
        return new SlotCalibrationInfoPayload(BlockPos.of(buf.readLong()), buf.readVarInt());
    }

    private void write(FriendlyByteBuf buf) {
        buf.writeLong(pos.asLong());
        buf.writeVarInt(hint);
    }

    public static void registerClient(PayloadRegistrar registrar) {
        registrar.playToClient(TYPE, CODEC, (msg, ctx) -> ctx.enqueueWork(() ->
                io.github.xianynomial.sfmfactorystudio.client.blocks.BlockEditorScreen
                        .acceptSlotCalibrationInfo(msg.pos(), msg.hint())));
    }

    @Override
    public CustomPacketPayload.Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
