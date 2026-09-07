package io.github.xianynomial.sfmfactorystudio.net;

import net.minecraft.core.BlockPos;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.neoforged.neoforge.network.registration.PayloadRegistrar;

import java.util.ArrayList;
import java.util.List;

/**
 * 服务端 → 客户端：目标方块能力槽内容（按语句侧面限定解析，SFM 同源路径）。
 *
 * <p>state：{@code 0}=正常；{@code 1}=限定方向无槽位但其他朝向有（refDir 指明
 * 实际用于编号的朝向）；{@code 2}=所有朝向都没有物品能力面；{@code -1}=方块
 * 不存在或距离过远。items/counts 与能力槽索引一一对应（空槽 = ""/0）。
 */
public record SlotCapabilityPayload(BlockPos pos, int state, String refDir, int total,
                                    List<String> items, List<Integer> counts)
        implements CustomPacketPayload {
    public static final CustomPacketPayload.Type<SlotCapabilityPayload> TYPE =
            new CustomPacketPayload.Type<>(net.minecraft.resources.ResourceLocation.fromNamespaceAndPath(
                    "sfmfactorystudio", "slot_capability"));

    public static final StreamCodec<FriendlyByteBuf, SlotCapabilityPayload> CODEC = CustomPacketPayload.codec(
            SlotCapabilityPayload::write,
            SlotCapabilityPayload::read);

    private static SlotCapabilityPayload read(FriendlyByteBuf buf) {
        BlockPos pos = BlockPos.of(buf.readLong());
        int state = buf.readVarInt();
        String refDir = buf.readUtf();
        int total = buf.readVarInt();
        int n = buf.readVarInt();
        List<String> items = new ArrayList<>(Math.max(0, n));
        List<Integer> counts = new ArrayList<>(Math.max(0, n));
        for (int i = 0; i < n; i++) {
            items.add(buf.readUtf());
            counts.add(buf.readVarInt());
        }
        return new SlotCapabilityPayload(pos, state, refDir, total, items, counts);
    }

    private void write(FriendlyByteBuf buf) {
        buf.writeLong(pos.asLong());
        buf.writeVarInt(state);
        buf.writeUtf(refDir == null ? "" : refDir);
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
                        .acceptSlotCapability(msg.pos(), msg.state(), msg.refDir(), msg.total(),
                                msg.items(), msg.counts())));
    }

    @Override
    public CustomPacketPayload.Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
