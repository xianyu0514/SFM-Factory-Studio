package io.github.xianynomial.sfmfactorystudio.net;

import net.minecraft.core.BlockPos;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.neoforged.neoforge.network.registration.PayloadRegistrar;

/**
 * 客户端 → 服务端：请求目标方块的能力槽内容（槽位可视化编号校准）。
 * 服务端只读能力面（不构造菜单、不改任何状态），回包见 {@link SlotCapabilityPayload}。
 */
public record SlotCapabilityRequestPayload(BlockPos pos) implements CustomPacketPayload {
    public static final CustomPacketPayload.Type<SlotCapabilityRequestPayload> TYPE =
            new CustomPacketPayload.Type<>(net.minecraft.resources.ResourceLocation.fromNamespaceAndPath(
                    "sfmfactorystudio", "slot_capability_request"));

    public static final StreamCodec<FriendlyByteBuf, SlotCapabilityRequestPayload> CODEC = CustomPacketPayload.codec(
            SlotCapabilityRequestPayload::write,
            SlotCapabilityRequestPayload::read);

    private static SlotCapabilityRequestPayload read(FriendlyByteBuf buf) {
        return new SlotCapabilityRequestPayload(BlockPos.of(buf.readLong()));
    }

    private void write(FriendlyByteBuf buf) {
        buf.writeLong(pos.asLong());
    }

    public static void registerServer(PayloadRegistrar registrar) {
        registrar.playToServer(TYPE, CODEC, SlotCapabilityRequestHandler::handle);
    }

    @Override
    public CustomPacketPayload.Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
