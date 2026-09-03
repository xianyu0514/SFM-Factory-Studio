package io.github.xianynomial.sfmfactorystudio.net;

import io.github.xianynomial.sfmfactorystudio.SFMGui;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

import java.util.ArrayList;
import java.util.List;

/**
 * Clientbound reply: label names and how many blocks are actually bound to
 * each one on the requested manager's disk.
 */
public record UpdateLabelsPayload(List<LabelInfo> labels) implements CustomPacketPayload {
    public record LabelInfo(String name, int blockCount) {
    }

    public static final Type<UpdateLabelsPayload> TYPE =
            new Type<>(ResourceLocation.fromNamespaceAndPath(SFMGui.MOD_ID, "update_labels"));

    public static final StreamCodec<FriendlyByteBuf, UpdateLabelsPayload> CODEC = StreamCodec.of(
            (buf, msg) -> {
                buf.writeVarInt(msg.labels.size());
                for (LabelInfo label : msg.labels) {
                    buf.writeUtf(label.name, 256);
                    buf.writeVarInt(Math.max(0, label.blockCount));
                }
            },
            buf -> {
                int n = buf.readVarInt();
                List<LabelInfo> labels = new ArrayList<>(n);
                for (int i = 0; i < n; i++) {
                    labels.add(new LabelInfo(buf.readUtf(256), buf.readVarInt()));
                }
                return new UpdateLabelsPayload(labels);
            }
    );

    @Override
    public Type<UpdateLabelsPayload> type() {
        return TYPE;
    }
}
