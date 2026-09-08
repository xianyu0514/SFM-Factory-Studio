package io.github.xianynomial.sfmfactorystudio.net;

import net.minecraft.core.BlockPos;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.neoforged.neoforge.network.registration.PayloadRegistrar;

/**
 * 服务端 → 客户端：一个操作学习锚点——玩家某次真实操作恰好使能力槽 capIndex
 * 的内容发生变化，即"该视觉格（menuClass/containerSlot/x,y）= 该真实槽位"被
 * 数据流证实。客户端按菜单类名写入捕获缓存（跨坐标、跨同款机器共享）。
 */
public record SlotAnchorPayload(BlockPos pos, String menuClass, int dir, int containerSlot,
                                int x, int y, int capIndex)
        implements CustomPacketPayload {
    public static final CustomPacketPayload.Type<SlotAnchorPayload> TYPE =
            new CustomPacketPayload.Type<>(net.minecraft.resources.ResourceLocation.fromNamespaceAndPath(
                    "sfmfactorystudio", "slot_anchor"));

    public static final StreamCodec<FriendlyByteBuf, SlotAnchorPayload> CODEC = CustomPacketPayload.codec(
            SlotAnchorPayload::write,
            SlotAnchorPayload::read);

    private static SlotAnchorPayload read(FriendlyByteBuf buf) {
        BlockPos pos = BlockPos.of(buf.readLong());
        String menuClass = buf.readUtf();
        return new SlotAnchorPayload(pos, menuClass, buf.readVarInt(), buf.readVarInt(),
                buf.readVarInt(), buf.readVarInt(), buf.readVarInt());
    }

    private void write(FriendlyByteBuf buf) {
        buf.writeLong(pos.asLong());
        buf.writeUtf(menuClass == null ? "" : menuClass);
        buf.writeVarInt(dir);
        buf.writeVarInt(containerSlot);
        buf.writeVarInt(x);
        buf.writeVarInt(y);
        buf.writeVarInt(capIndex);
    }

    public static void registerClient(PayloadRegistrar registrar) {
        registrar.playToClient(TYPE, CODEC, (msg, ctx) -> ctx.enqueueWork(() ->
                io.github.xianynomial.sfmfactorystudio.client.blocks.BlockEditorScreen
                        .acceptSlotAnchor(msg.pos(), msg.menuClass(), msg.dir(), msg.containerSlot(),
                                msg.x(), msg.y(), msg.capIndex())));
    }

    @Override
    public CustomPacketPayload.Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
