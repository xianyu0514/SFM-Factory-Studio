package io.github.xianynomial.sfmfactorystudio.net;

import net.minecraft.core.BlockPos;
import net.minecraft.network.FriendlyByteBuf;

import java.util.function.Supplier;

/**
 * 服务端 → 客户端（Forge 1.20.1 SimpleChannel）：操作学习的过程性诊断。hint =
 * INFO_NO_EXPOSURE——机器界面的槽位在真实变化，但七个朝向的能力面全程无变化：
 * 该机器的槽位未暴露给 SFM 可见的任何管道（典型如 Mekanism 需要先在机器
 * 侧面配置中开放输入/输出）。
 */
public class SlotCalibrationInfoPayload {
    public static final int INFO_NO_EXPOSURE = 1;

    public final BlockPos pos;
    public final int hint;

    public SlotCalibrationInfoPayload(BlockPos pos, int hint) {
        this.pos = pos;
        this.hint = hint;
    }

    public static void encode(SlotCalibrationInfoPayload msg, FriendlyByteBuf buf) {
        buf.writeLong(msg.pos.asLong());
        buf.writeVarInt(msg.hint);
    }

    public static SlotCalibrationInfoPayload decode(FriendlyByteBuf buf) {
        return new SlotCalibrationInfoPayload(BlockPos.of(buf.readLong()), buf.readVarInt());
    }

    public static void handle(SlotCalibrationInfoPayload msg, Supplier<net.minecraftforge.network.NetworkEvent.Context> ctx) {
        ctx.get().enqueueWork(() ->
                io.github.xianynomial.sfmfactorystudio.client.blocks.BlockEditorScreen
                        .acceptSlotCalibrationInfo(msg.pos, msg.hint));
        ctx.get().setPacketHandled(true);
    }
}
