package io.github.xianynomial.sfmfactorystudio.net;

import net.minecraft.core.BlockPos;
import net.minecraft.network.FriendlyByteBuf;

import java.util.function.Supplier;

/**
 * 服务端 → 客户端：一个操作学习锚点——玩家某次真实操作恰好使能力槽 capIndex
 * 的内容发生变化，即"该视觉格（menuClass/containerSlot/x,y）= 该真实槽位"被
 * 数据流证实。客户端按菜单类名写入捕获缓存（跨坐标、跨同款机器共享）。
 */
public class SlotAnchorPayload {
    public final BlockPos pos;
    public final String menuClass;
    public final int dir;
    public final int containerSlot;
    public final int x;
    public final int y;
    public final int capIndex;

    public SlotAnchorPayload(BlockPos pos, String menuClass, int dir, int containerSlot,
                             int x, int y, int capIndex) {
        this.pos = pos;
        this.menuClass = menuClass;
        this.dir = dir;
        this.containerSlot = containerSlot;
        this.x = x;
        this.y = y;
        this.capIndex = capIndex;
    }

    public static void encode(SlotAnchorPayload msg, FriendlyByteBuf buf) {
        buf.writeLong(msg.pos.asLong());
        buf.writeUtf(msg.menuClass == null ? "" : msg.menuClass);
        buf.writeVarInt(msg.dir);
        buf.writeVarInt(msg.containerSlot);
        buf.writeVarInt(msg.x);
        buf.writeVarInt(msg.y);
        buf.writeVarInt(msg.capIndex);
    }

    public static SlotAnchorPayload decode(FriendlyByteBuf buf) {
        BlockPos pos = BlockPos.of(buf.readLong());
        String menuClass = buf.readUtf();
        return new SlotAnchorPayload(pos, menuClass, buf.readVarInt(), buf.readVarInt(),
                buf.readVarInt(), buf.readVarInt(), buf.readVarInt());
    }

    public static void handle(SlotAnchorPayload msg, Supplier<net.minecraftforge.network.NetworkEvent.Context> ctx) {
        ctx.get().enqueueWork(() ->
                io.github.xianynomial.sfmfactorystudio.client.blocks.BlockEditorScreen
                        .acceptSlotAnchor(msg.pos, msg.menuClass, msg.dir, msg.containerSlot,
                                msg.x, msg.y, msg.capIndex));
        ctx.get().setPacketHandled(true);
    }
}
