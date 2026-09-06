package io.github.xianynomial.sfmfactorystudio.net;

import net.minecraft.core.BlockPos;
import net.minecraft.network.FriendlyByteBuf;

/**
 * 客户端 → 服务端（Forge 1.20.1 SimpleChannel）：请求管理器的标签绑定统计。
 * 只装客户端时服务端不回复（optional），编辑器按无数据降级。
 */
public class RequestLabelsPayload {
    public final BlockPos pos;

    public RequestLabelsPayload(BlockPos pos) {
        this.pos = pos;
    }

    public static void encode(RequestLabelsPayload msg, FriendlyByteBuf buf) {
        buf.writeBlockPos(msg.pos);
    }

    public static RequestLabelsPayload decode(FriendlyByteBuf buf) {
        return new RequestLabelsPayload(buf.readBlockPos());
    }
}
