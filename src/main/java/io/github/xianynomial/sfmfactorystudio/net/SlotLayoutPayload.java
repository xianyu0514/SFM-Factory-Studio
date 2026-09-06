package io.github.xianynomial.sfmfactorystudio.net;

import net.minecraft.core.BlockPos;
import net.minecraft.network.FriendlyByteBuf;

import java.util.ArrayList;
import java.util.List;

/**
 * 服务端 → 客户端（Forge 1.20.1 SimpleChannel）：槽位布局快照。
 * total=-1 表示目标不是容器；slots 为空且 total>0 表示只知道槽数
 * （客户端走自适应网格）。
 */
public class SlotLayoutPayload {
    public final BlockPos pos;
    public final int total;
    public final List<int[]> slots;
    public final String menuClass;

    public SlotLayoutPayload(BlockPos pos, int total, List<int[]> slots, String menuClass) {
        this.pos = pos;
        this.total = total;
        this.slots = slots;
        this.menuClass = menuClass;
    }

    public static void encode(SlotLayoutPayload msg, FriendlyByteBuf buf) {
        buf.writeLong(msg.pos.asLong());
        buf.writeVarInt(msg.total);
        buf.writeVarInt(msg.slots.size());
        for (int[] c : msg.slots) {
            buf.writeVarInt(c[0]);
            buf.writeVarInt(c[1]);
        }
        buf.writeUtf(msg.menuClass == null ? "" : msg.menuClass);
    }

    public static SlotLayoutPayload decode(FriendlyByteBuf buf) {
        BlockPos pos = BlockPos.of(buf.readLong());
        int total = buf.readVarInt();
        int n = buf.readVarInt();
        List<int[]> slots = new ArrayList<>(Math.max(0, n));
        for (int i = 0; i < n; i++) slots.add(new int[]{buf.readVarInt(), buf.readVarInt()});
        String menuClass = buf.readUtf();
        return new SlotLayoutPayload(pos, total, slots, menuClass);
    }

    public static void handle(SlotLayoutPayload msg, java.util.function.Supplier<net.minecraftforge.network.NetworkEvent.Context> ctx) {
        ctx.get().enqueueWork(() ->
                io.github.xianynomial.sfmfactorystudio.client.blocks.BlockEditorScreen
                        .acceptSlotLayout(msg.pos, msg.total, msg.slots, msg.menuClass));
        ctx.get().setPacketHandled(true);
    }
}
