package io.github.xianynomial.sfmfactorystudio.net;

import net.minecraft.core.BlockPos;
import net.minecraft.network.FriendlyByteBuf;

/**
 * 客户端 → 服务端（Forge 1.20.1 SimpleChannel）：开启操作学习会话。
 * 锚点按菜单类名共享，对一切模组容器生效。
 */
public class SlotCalibrationBeginPayload {
    public final BlockPos pos;
    public final int containerId;
    public final String menuClass;

    public SlotCalibrationBeginPayload(BlockPos pos, int containerId, String menuClass) {
        this.pos = pos;
        this.containerId = containerId;
        this.menuClass = menuClass;
    }

    public static void encode(SlotCalibrationBeginPayload msg, FriendlyByteBuf buf) {
        buf.writeLong(msg.pos.asLong());
        buf.writeVarInt(msg.containerId);
        buf.writeUtf(msg.menuClass == null ? "" : msg.menuClass);
    }

    public static SlotCalibrationBeginPayload decode(FriendlyByteBuf buf) {
        BlockPos pos = BlockPos.of(buf.readLong());
        int containerId = buf.readVarInt();
        String menuClass = buf.readUtf();
        return new SlotCalibrationBeginPayload(pos, containerId, menuClass);
    }

    public static void handle(SlotCalibrationBeginPayload msg,
                              java.util.function.Supplier<net.minecraftforge.network.NetworkEvent.Context> ctx) {
        ctx.get().enqueueWork(() -> {
            if (ctx.get().getSender() != null) {
                SlotCalibrationManager.begin(ctx.get().getSender(), msg.pos, msg.containerId, msg.menuClass);
            }
        });
        ctx.get().setPacketHandled(true);
    }
}
