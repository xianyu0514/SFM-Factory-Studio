package io.github.xianynomial.sfmfactorystudio.net;

import net.minecraft.core.BlockPos;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.neoforged.neoforge.network.registration.PayloadRegistrar;

import java.util.ArrayList;
import java.util.List;

/**
 * 服务端 → 客户端：目标方块能力槽内容。total=-1 = 方块实体不存在/超出距离；
 * total=0 = 没有物品能力面；items/counts 与能力槽索引一一对应（空槽 = ""/0）。
 */
public record SlotCapabilityPayload(BlockPos pos, int total, List<String> items, List<Integer> counts)
        implements CustomPacketPayload {
    public static final CustomPacketPayload.Type<SlotCapabilityPayload> TYPE =
            new CustomPacketPayload.Type<>(net.minecraft.resources.ResourceLocation.fromNamespaceAndPath(
                    "sfmfactorystudio", "slot_capability"));

    public static final StreamCodec<FriendlyByteBuf, SlotCapabilityPayload> CODEC = CustomPacketPayload.codec(
            SlotCapabilityPayload::write,
            SlotCapabilityPayload::read);

    private static SlotCapabilityPayload read(FriendlyByteBuf buf) {
        BlockPos pos = BlockPos.of(buf.readLong());
        int total = buf.readVarInt();
        int n = buf.readVarInt();
        List<String> items = new ArrayList<>(Math.max(0, n));
        List<Integer> counts = new ArrayList<>(Math.max(0, n));
        for (int i = 0; i < n; i++) {
            items.add(buf.readUtf());
            counts.add(buf.readVarInt());
        }
        return new SlotCapabilityPayload(pos, total, items, counts);
    }

    private void write(FriendlyByteBuf buf) {
        buf.writeLong(pos.asLong());
        buf.writeVarInt(total);
        buf.writeVarInt(items.size());
        for (int i = 0; i < items.size(); i++) {
            buf.writeUtf(items.get(i));
            buf.writeVarInt(counts.get(i));
        }
    }

    public static void registerClient(PayloadRegistrar registrar) {
        registrar.playToClient(TYPE, CODEC, (msg, ctx) -> ctx.enqueueWork(() ->
                io.github.xianynomial.sfmfactorystudio.client.blocks.BlockEditorScreen
                        .acceptSlotCapability(msg.pos(), msg.total(), msg.items(), msg.counts())));
    }

    @Override
    public CustomPacketPayload.Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
