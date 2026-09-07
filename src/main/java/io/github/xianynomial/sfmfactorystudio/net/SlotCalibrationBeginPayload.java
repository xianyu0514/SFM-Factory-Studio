package io.github.xianynomial.sfmfactorystudio.net;

import net.minecraft.core.BlockPos;
import net.minecraft.network.FriendlyByteBuf;

/**
 * 客户端 → 服务端（Forge 1.20.1 SimpleChannel）：开启操作学习会话。玩家打开
 * 容器界面时发送——此后该玩家在此界面里的每次真实点击，服务端都会差分能力槽
 * 内容，把"视觉格 ↔ 真实槽位"锚定后回传（见 SlotAnchorPayload）。
 * 对一切模组容器生效，无需模组适配。
 */
public class SlotCalibrationBeginPayload {
    public final BlockPos pos;
    public final int containerId;

    public SlotCalibrationBeginPayload(BlockPos pos, int containerId) {
        this.pos = pos;
        this.containerId = containerId;
    }

    public static void encode(SlotCalibrationBeginPayload msg, FriendlyByteBuf buf) {
        buf.writeLong(msg.pos.asLong());
        buf.writeVarInt(msg.containerId);
    }

    public static SlotCalibrationBeginPayload decode(FriendlyByteBuf buf) {
        return new SlotCalibrationBeginPayload(BlockPos.of(buf.readLong()), buf.readVarInt());
    }

    public static void handle(SlotCalibrationBeginPayload msg,
                              java.util.function.Supplier<net.minecraftforge.network.NetworkEvent.Context> ctx) {
        ctx.get().enqueueWork(() -> {
            if (ctx.get().getSender() != null) {
                SlotCalibrationManager.begin(ctx.get().getSender(), msg.pos, msg.containerId);
            }
        });
        ctx.get().setPacketHandled(true);
    }
}
