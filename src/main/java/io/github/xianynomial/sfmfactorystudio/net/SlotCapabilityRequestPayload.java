package io.github.xianynomial.sfmfactorystudio.net;

import net.minecraft.core.BlockPos;
import net.minecraft.network.FriendlyByteBuf;

/**
 * 客户端 → 服务端（Forge 1.20.1 SimpleChannel）：请求目标方块的能力槽内容
 * （槽位可视化编号校准）。服务端只读能力面（不构造菜单、不改任何状态），
 * 回包见 SlotCapabilityPayload。
 */
public class SlotCapabilityRequestPayload {
    public final BlockPos pos;

    public SlotCapabilityRequestPayload(BlockPos pos) {
        this.pos = pos;
    }

    public static void encode(SlotCapabilityRequestPayload msg, FriendlyByteBuf buf) {
        buf.writeLong(msg.pos.asLong());
    }

    public static SlotCapabilityRequestPayload decode(FriendlyByteBuf buf) {
        return new SlotCapabilityRequestPayload(BlockPos.of(buf.readLong()));
    }

    public static void handle(SlotCapabilityRequestPayload msg,
                              java.util.function.Supplier<net.minecraftforge.network.NetworkEvent.Context> ctx) {
        SlotCapabilityRequestHandler.handle(msg, ctx);
    }
}
