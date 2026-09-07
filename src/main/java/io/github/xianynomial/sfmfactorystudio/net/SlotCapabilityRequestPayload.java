package io.github.xianynomial.sfmfactorystudio.net;

import net.minecraft.core.BlockPos;
import net.minecraft.network.FriendlyByteBuf;

/**
 * 客户端 → 服务端（Forge 1.20.1 SimpleChannel）：请求目标方块的能力槽内容
 * （槽位可视化编号校准）。sides = 语句当前的侧面限定（SFML 侧面名，逗号分隔，
 * 如 "null" / "top,bottom" / "each side" 的全部 7 面）——服务端用它走与
 * SFM 本体完全一致的解析路径。
 */
public class SlotCapabilityRequestPayload {
    public final BlockPos pos;
    public final String sides;

    public SlotCapabilityRequestPayload(BlockPos pos, String sides) {
        this.pos = pos;
        this.sides = sides;
    }

    public static void encode(SlotCapabilityRequestPayload msg, FriendlyByteBuf buf) {
        buf.writeLong(msg.pos.asLong());
        buf.writeUtf(msg.sides == null ? "null" : msg.sides);
    }

    public static SlotCapabilityRequestPayload decode(FriendlyByteBuf buf) {
        return new SlotCapabilityRequestPayload(BlockPos.of(buf.readLong()), buf.readUtf());
    }

    public static void handle(SlotCapabilityRequestPayload msg,
                              java.util.function.Supplier<net.minecraftforge.network.NetworkEvent.Context> ctx) {
        SlotCapabilityRequestHandler.handle(msg, ctx);
    }
}
