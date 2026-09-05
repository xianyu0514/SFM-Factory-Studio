package io.github.xianynomial.sfmfactorystudio.net;

import net.minecraft.core.BlockPos;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.neoforged.neoforge.network.registration.PayloadRegistrar;

import java.util.ArrayList;
import java.util.List;

/**
 * 服务端 → 客户端：槽位布局快照。total=-1 表示目标不是容器；
 * slots 为空且 total>0 表示只知道槽数（客户端走自适应网格）。
 */
public record SlotLayoutPayload(BlockPos pos, int total, List<int[]> slots) implements CustomPacketPayload {
    public static final CustomPacketPayload.Type<SlotLayoutPayload> TYPE =
            new CustomPacketPayload.Type<>(net.minecraft.resources.ResourceLocation.fromNamespaceAndPath(
                    "sfmfactorystudio", "slot_layout"));

    public static final StreamCodec<FriendlyByteBuf, SlotLayoutPayload> CODEC = CustomPacketPayload.codec(
            SlotLayoutPayload::write,
            SlotLayoutPayload::read);

    private static SlotLayoutPayload read(FriendlyByteBuf buf) {
        BlockPos pos = BlockPos.of(buf.readLong());
        int total = buf.readVarInt();
        int n = buf.readVarInt();
        List<int[]> slots = new ArrayList<>(Math.max(0, n));
        for (int i = 0; i < n; i++) slots.add(new int[]{buf.readVarInt(), buf.readVarInt()});
        return new SlotLayoutPayload(pos, total, slots);
    }

    private void write(FriendlyByteBuf buf) {
        buf.writeLong(pos.asLong());
        buf.writeVarInt(total);
        buf.writeVarInt(slots.size());
        for (int[] c : slots) {
            buf.writeVarInt(c[0]);
            buf.writeVarInt(c[1]);
        }
    }

    public static void registerClient(PayloadRegistrar registrar) {
        registrar.playToClient(TYPE, CODEC, (msg, ctx) -> ctx.enqueueWork(() ->
                io.github.xianynomial.sfmfactorystudio.client.blocks.BlockEditorScreen
                        .acceptSlotLayout(msg.pos(), msg.total(), msg.slots())));
    }

    @Override
    public CustomPacketPayload.Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
