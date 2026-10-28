package io.github.xianynomial.sfmfactorystudio.net;

import net.minecraft.network.FriendlyByteBuf;

import java.util.ArrayList;
import java.util.List;

/**
 * 服务端 → 客户端（Forge 1.20.1 SimpleChannel）：标签绑定回包。
 * LabelInfo(标签名, 绑定方块数)。原版 SFM 服务端不会发；收到才解锁
 * "未绑定标签"类提醒。
 */
public class UpdateLabelsPayload {
    public record LabelInfo(String name, int blockCount) {
    }

    public final List<LabelInfo> labels;

    public UpdateLabelsPayload(List<LabelInfo> labels) {
        this.labels = labels;
    }

    public static void encode(UpdateLabelsPayload msg, FriendlyByteBuf buf) {
        buf.writeVarInt(msg.labels.size());
        for (LabelInfo info : msg.labels) {
            buf.writeUtf(info.name() == null ? "" : info.name());
            buf.writeVarInt(info.blockCount());
        }
    }

    public static UpdateLabelsPayload decode(FriendlyByteBuf buf) {
        int n = buf.readVarInt();
        // 防御：n 来自对端。正常标签数远小于此；畸形/恶意包若声明超大 n，
        // new ArrayList<>(n) 会直接 OOM 把客户端崩掉。上限取剩余可读字节数
        // ——每项至少 2 字节（1 字节长度前缀 + 1 字节 VarInt 计数），超过它
        // 必然不可能成立。抛异常让整包被丢弃，不会造成后续包解析错位。
        if (n < 0 || n > buf.readableBytes()) {
            throw new IllegalArgumentException("UpdateLabels: implausible label count " + n);
        }
        List<LabelInfo> out = new ArrayList<>(n);
        for (int i = 0; i < n; i++) out.add(new LabelInfo(buf.readUtf(), buf.readVarInt()));
        return new UpdateLabelsPayload(out);
    }

    public static void handle(UpdateLabelsPayload msg, java.util.function.Supplier<net.minecraftforge.network.NetworkEvent.Context> ctx) {
        ctx.get().enqueueWork(() ->
                io.github.xianynomial.sfmfactorystudio.client.blocks.BlockEditorScreen
                        .acceptLabels(msg.labels));
        ctx.get().setPacketHandled(true);
    }
}
