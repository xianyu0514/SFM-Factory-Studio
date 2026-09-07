package io.github.xianynomial.sfmfactorystudio.net;

import net.minecraft.core.BlockPos;
import net.minecraft.network.FriendlyByteBuf;

import java.util.function.Supplier;

/**
 * 服务端 → 客户端（Forge 1.20.1 SimpleChannel）：一个操作学习锚点——玩家某次
 * 真实点击恰好使能力槽 capIndex 的内容发生变化，即"该视觉格（containerSlot /
 * x,y）= 该真实槽位"被数据流证实。客户端把它写入捕获缓存持久化。
 */
public class SlotAnchorPayload {
    public final BlockPos pos;
    public final int dir;
    public final int containerSlot;
    public final int x;
    public final int y;
    public final int capIndex;

    public SlotAnchorPayload(BlockPos pos, int dir, int containerSlot, int x, int y, int capIndex) {
        this.pos = pos;
        this.dir = dir;
        this.containerSlot = containerSlot;
        this.x = x;
        this.y = y;
        this.capIndex = capIndex;
    }

    public static void encode(SlotAnchorPayload msg, FriendlyByteBuf buf) {
        buf.writeLong(msg.pos.asLong());
        buf.writeVarInt(msg.dir);
        buf.writeVarInt(msg.containerSlot);
        buf.writeVarInt(msg.x);
        buf.writeVarInt(msg.y);
        buf.writeVarInt(msg.capIndex);
    }

    public static SlotAnchorPayload decode(FriendlyByteBuf buf) {
        BlockPos pos = BlockPos.of(buf.readLong());
        return new SlotAnchorPayload(pos, buf.readVarInt(), buf.readVarInt(),
                buf.readVarInt(), buf.readVarInt(), buf.readVarInt());
    }

    public static void handle(SlotAnchorPayload msg, Supplier<net.minecraftforge.network.NetworkEvent.Context> ctx) {
        ctx.get().enqueueWork(() ->
                io.github.xianynomial.sfmfactorystudio.client.blocks.BlockEditorScreen
                        .acceptSlotAnchor(msg.pos, msg.dir, msg.containerSlot,
                                msg.x, msg.y, msg.capIndex));
        ctx.get().setPacketHandled(true);
    }
}
