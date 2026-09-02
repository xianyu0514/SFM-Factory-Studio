package ca.teamdman.sfmjimu.net;

import ca.teamdman.sfmjimu.SFMGui;
import net.minecraft.core.BlockPos;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

/**
 * Clientbound request: ask for the label names known to the manager at {@code pos}
 * (disk-bound labels + names referenced by its program) so block-editor fields can
 * offer them as dropdown suggestions.
 */
public record RequestLabelsPayload(BlockPos pos) implements CustomPacketPayload {
    public static final Type<RequestLabelsPayload> TYPE =
            new Type<>(ResourceLocation.fromNamespaceAndPath(SFMGui.MOD_ID, "request_labels"));

    public static final StreamCodec<FriendlyByteBuf, RequestLabelsPayload> CODEC = StreamCodec.of(
            (buf, msg) -> buf.writeBlockPos(msg.pos),
            buf -> new RequestLabelsPayload(buf.readBlockPos())
    );

    @Override
    public Type<RequestLabelsPayload> type() {
        return TYPE;
    }
}
