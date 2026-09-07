package io.github.xianynomial.sfmfactorystudio.net;

import net.minecraft.core.BlockPos;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.neoforged.neoforge.network.registration.PayloadRegistrar;

/**
 * 客户端 → 服务端：请求目标方块的能力槽内容（槽位可视化编号校准）。
 * sides = 语句当前的侧面限定（SFML 侧面名，逗号分隔，如 "null" / "top,bottom" /
 * "each side" 的全部 7 面）——服务端用它走与 SFM 本体完全一致的解析路径。
 */
public record SlotCapabilityRequestPayload(BlockPos pos, String sides) implements CustomPacketPayload {
    public static final CustomPacketPayload.Type<SlotCapabilityRequestPayload> TYPE =
            new CustomPacketPayload.Type<>(net.minecraft.resources.ResourceLocation.fromNamespaceAndPath(
                    "sfmfactorystudio", "slot_capability_request"));

    public static final StreamCodec<FriendlyByteBuf, SlotCapabilityRequestPayload> CODEC = CustomPacketPayload.codec(
            SlotCapabilityRequestPayload::write,
            SlotCapabilityRequestPayload::read);

    private static SlotCapabilityRequestPayload read(FriendlyByteBuf buf) {
        return new SlotCapabilityRequestPayload(BlockPos.of(buf.readLong()), buf.readUtf());
    }

    private void write(FriendlyByteBuf buf) {
        buf.writeLong(pos.asLong());
        buf.writeUtf(sides == null ? "null" : sides);
    }

    public static void registerServer(PayloadRegistrar registrar) {
        registrar.playToServer(TYPE, CODEC, SlotCapabilityRequestHandler::handle);
    }

    @Override
    public CustomPacketPayload.Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
