package io.github.xianynomial.sfmfactorystudio.net;

import net.minecraft.core.BlockPos;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.neoforged.neoforge.network.registration.PayloadRegistrar;

/**
 * 客户端 → 服务端：请求某个方块位置的容器槽位布局（槽位可视化 beta）。
 * 双端 optional 注册：只装客户端时服务端不回复，客户端按钮保持隐藏。
 */
public record SlotLayoutRequestPayload(BlockPos pos) implements CustomPacketPayload {
    public static final CustomPacketPayload.Type<SlotLayoutRequestPayload> TYPE =
            new CustomPacketPayload.Type<>(net.minecraft.resources.ResourceLocation.fromNamespaceAndPath(
                    "sfmfactorystudio", "slot_layout_request"));

    public static final StreamCodec<FriendlyByteBuf, SlotLayoutRequestPayload> CODEC = CustomPacketPayload.codec(
            SlotLayoutRequestPayload::write,
            SlotLayoutRequestPayload::read);

    private static SlotLayoutRequestPayload read(FriendlyByteBuf buf) {
        return new SlotLayoutRequestPayload(BlockPos.of(buf.readLong()));
    }

    private void write(FriendlyByteBuf buf) {
        buf.writeLong(pos.asLong());
    }

    public static void registerServer(PayloadRegistrar registrar) {
        registrar.playToServer(TYPE, CODEC, SlotLayoutRequestHandler::handle);
    }

    @Override
    public CustomPacketPayload.Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
