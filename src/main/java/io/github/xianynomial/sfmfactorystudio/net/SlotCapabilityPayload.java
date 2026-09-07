package io.github.xianynomial.sfmfactorystudio.net;

import net.minecraft.core.BlockPos;
import net.minecraft.network.FriendlyByteBuf;

import java.util.ArrayList;
import java.util.List;

/**
 * 服务端 → 客户端（Forge 1.20.1 SimpleChannel）：目标方块能力槽内容
 * （按语句侧面限定解析，SFM 同源路径）。
 *
 * <p>state：{@code 0}=正常；{@code 1}=限定方向无槽位但其他朝向有（refDir 指明
 * 实际用于编号的朝向）；{@code 2}=所有朝向都没有物品能力面；{@code -1}=方块
 * 不存在或距离过远。items/counts 与能力槽索引一一对应（空槽 = ""/0）。
 * dirTotals = 七个朝向各自暴露的槽位数（[0]=无侧面，[1..6]=down,up,north,
 * south,west,east；-1=该朝向无能力面），用于"各朝向槽数不同"的提示。
 */
public class SlotCapabilityPayload {
    public final BlockPos pos;
    public final int state;
    public final String refDir;
    public final int total;
    public final int[] dirTotals;
    public final List<String> items;
    public final List<Integer> counts;

    public SlotCapabilityPayload(BlockPos pos, int state, String refDir, int total,
                                 int[] dirTotals, List<String> items, List<Integer> counts) {
        this.pos = pos;
        this.state = state;
        this.refDir = refDir;
        this.total = total;
        this.dirTotals = dirTotals;
        this.items = items;
        this.counts = counts;
    }

    public static void encode(SlotCapabilityPayload msg, FriendlyByteBuf buf) {
        buf.writeLong(msg.pos.asLong());
        buf.writeVarInt(msg.state);
        buf.writeUtf(msg.refDir == null ? "" : msg.refDir);
        buf.writeVarInt(msg.total);
        for (int i = 0; i < 7; i++) buf.writeVarInt(msg.dirTotals == null ? -1 : msg.dirTotals[i]);
        buf.writeVarInt(msg.items.size());
        for (int i = 0; i < msg.items.size(); i++) {
            buf.writeUtf(msg.items.get(i));
            buf.writeVarInt(msg.counts.get(i));
        }
    }

    public static SlotCapabilityPayload decode(FriendlyByteBuf buf) {
        BlockPos pos = BlockPos.of(buf.readLong());
        int state = buf.readVarInt();
        String refDir = buf.readUtf();
        int total = buf.readVarInt();
        int[] dirTotals = new int[7];
        for (int i = 0; i < 7; i++) dirTotals[i] = buf.readVarInt();
        int n = buf.readVarInt();
        List<String> items = new ArrayList<>(Math.max(0, n));
        List<Integer> counts = new ArrayList<>(Math.max(0, n));
        for (int i = 0; i < n; i++) {
            items.add(buf.readUtf());
            counts.add(buf.readVarInt());
        }
        return new SlotCapabilityPayload(pos, state, refDir, total, dirTotals, items, counts);
    }

    public static void handle(SlotCapabilityPayload msg,
                              java.util.function.Supplier<net.minecraftforge.network.NetworkEvent.Context> ctx) {
        ctx.get().enqueueWork(() ->
                io.github.xianynomial.sfmfactorystudio.client.blocks.BlockEditorScreen
                        .acceptSlotCapability(msg.pos, msg.state, msg.refDir, msg.total,
                                msg.dirTotals, msg.items, msg.counts));
        ctx.get().setPacketHandled(true);
    }
}
