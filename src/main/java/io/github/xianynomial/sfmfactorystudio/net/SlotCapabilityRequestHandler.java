package io.github.xianynomial.sfmfactorystudio.net;

import io.github.xianynomial.sfmfactorystudio.SFMGui;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import net.minecraftforge.network.NetworkEvent;
import net.minecraftforge.network.PacketDistributor;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Supplier;

/**
 * 服务端：只读目标方块的能力面（IItemHandler），回报每个能力槽的内容签名
 * （物品 id + 数量），供客户端把界面格子校准成 SFM 实际寻址的能力槽索引。
 *
 * <p>与旧探针不同：不构造菜单、不读 GUI 坐标、零缓存（能力面读取本身廉价，
 * 且内容新鲜度直接决定签名匹配质量）。total=-1 = 方块实体不存在/超出距离；
 * total=0 = 没有物品能力面（两者都足以证明服务端装了本模组）。
 */
public final class SlotCapabilityRequestHandler {
    private SlotCapabilityRequestHandler() {
    }

    public static void handle(SlotCapabilityRequestPayload msg, Supplier<NetworkEvent.Context> ctx) {
        ctx.get().enqueueWork(() -> {
            ServerPlayer player = ctx.get().getSender();
            if (player == null) return;
            BlockPos pos = msg.pos;
            // 防滥用：只回报玩家附近的方块（8 格），与容器交互距离同量级
            if (player.distanceToSqr(pos.getX() + 0.5, pos.getY() + 0.5, pos.getZ() + 0.5) > 64) {
                send(player, pos, -1, List.of(), List.of());
                return;
            }
            var be = player.level().getBlockEntity(pos);
            var cap = be == null ? null
                    : be.getCapability(
                            net.minecraftforge.common.capabilities.ForgeCapabilities.ITEM_HANDLER, null)
                    .orElse(null);
            if (cap == null) {
                // SFM 与本模组查询口径一致（默认朝向 = null）；没有能力面 = 不可寻址
                send(player, pos, 0, List.of(), List.of());
                return;
            }
            var handler = cap;
            int n = Math.min(handler.getSlots(), 4096);
            List<String> items = new ArrayList<>(n);
            List<Integer> counts = new ArrayList<>(n);
            for (int i = 0; i < n; i++) {
                ItemStack stack;
                try {
                    stack = handler.getStackInSlot(i);
                } catch (Throwable t) {
                    // 个别模组能力面对异常索引抛错：按空槽处理，不让单槽炸掉整次校准
                    SFMGui.LOGGER.debug("capability slot {} read failed at {}", i, pos, t);
                    stack = ItemStack.EMPTY;
                }
                items.add(stack == null || stack.isEmpty()
                        ? "" : BuiltInRegistries.ITEM.getKey(stack.getItem()).toString());
                counts.add(stack == null ? 0 : stack.getCount());
            }
            send(player, pos, n, items, counts);
        });
        ctx.get().setPacketHandled(true);
    }

    private static void send(ServerPlayer player, BlockPos pos, int total,
                             List<String> items, List<Integer> counts) {
        SFMGuiNetwork.CHANNEL.send(
                net.minecraftforge.network.PacketDistributor.PLAYER.with(() -> player),
                new SlotCapabilityPayload(pos, total, items, counts));
    }
}
