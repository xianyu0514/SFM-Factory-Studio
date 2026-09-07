package io.github.xianynomial.sfmfactorystudio.net;

import net.minecraft.core.BlockPos;
import net.minecraft.network.FriendlyByteBuf;

import java.util.ArrayList;
import java.util.List;

/**
 * 服务端 → 客户端（Forge 1.20.1 SimpleChannel）：目标方块能力槽内容。
 * total=-1 = 方块实体不存在/超出距离；total=0 = 没有物品能力面；
 * items/counts 与能力槽索引一一对应（空槽 = ""/0）。
 */
public class SlotCapabilityPayload {
    public final BlockPos pos;
    public final int total;
    public final List<String> items;
    public final List<Integer> counts;

    public SlotCapabilityPayload(BlockPos pos, int total, List<String> items, List<Integer> counts) {
        this.pos = pos;
        this.total = total;
        this.items = items;
        this.counts = counts;
    }

    public static void encode(SlotCapabilityPayload msg, FriendlyByteBuf buf) {
        buf.writeLong(msg.pos.asLong());
        buf.writeVarInt(msg.total);
        buf.writeVarInt(msg.items.size());
        for (int i = 0; i < msg.items.size(); i++) {
            buf.writeUtf(msg.items.get(i));
            buf.writeVarInt(msg.counts.get(i));
        }
    }

    public static SlotCapabilityPayload decode(FriendlyByteBuf buf) {
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

    public static void handle(SlotCapabilityPayload msg,
                              java.util.function.Supplier<net.minecraftforge.network.NetworkEvent.Context> ctx) {
        ctx.get().enqueueWork(() ->
                io.github.xianynomial.sfmfactorystudio.client.blocks.BlockEditorScreen
                        .acceptSlotCapability(msg.pos, msg.total, msg.items, msg.counts));
        ctx.get().setPacketHandled(true);
    }
}
