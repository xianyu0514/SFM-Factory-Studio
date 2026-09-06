package io.github.xianynomial.sfmfactorystudio.net;

import net.minecraft.core.BlockPos;
import net.minecraft.network.FriendlyByteBuf;

/**
 * 客户端 → 服务端：请求某个方块位置的容器槽位布局（槽位可视化 beta）。
 * 只装客户端时服务端不回复，客户端按钮保持隐藏。
 */
public class SlotLayoutRequestPayload {
    public final BlockPos pos;

    public SlotLayoutRequestPayload(BlockPos pos) {
        this.pos = pos;
    }

    public static void encode(SlotLayoutRequestPayload msg, FriendlyByteBuf buf) {
        buf.writeLong(msg.pos.asLong());
    }

    public static SlotLayoutRequestPayload decode(FriendlyByteBuf buf) {
        return new SlotLayoutRequestPayload(BlockPos.of(buf.readLong()));
    }
}
