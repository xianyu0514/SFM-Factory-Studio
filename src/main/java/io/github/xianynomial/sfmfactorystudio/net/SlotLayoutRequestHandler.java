package io.github.xianynomial.sfmfactorystudio.net;

import io.github.xianynomial.sfmfactorystudio.SFMGui;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.MenuProvider;
import net.minecraft.world.SimpleMenuProvider;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.MenuType;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.neoforged.neoforge.network.PacketDistributor;
import net.neoforged.neoforge.network.handling.IPayloadContext;

import java.util.ArrayList;
import java.util.List;

/**
 * 服务端：读取目标方块容器的槽位布局（每个槽在 GUI 里的 x/y + 总槽数）并回包。
 * 构造 Menu 是纯数据操作（不发给玩家、不打开界面），30 秒结果缓存在
 * {@link SlotLayoutCache} 防止高频请求。
 *
 * MenuType → MenuProvider 的构造依赖方块实体的 Menus 注册表；拿不到
 * provider（多数无 GUI 机器）就回退只报总槽数，客户端走自适应网格。
 */
public final class SlotLayoutRequestHandler {
    private SlotLayoutRequestHandler() {
    }

    public static void handle(SlotLayoutRequestPayload msg, IPayloadContext ctx) {
        ctx.enqueueWork(() -> {
            if (!(ctx.player() instanceof ServerPlayer player)) return;
            BlockEntity be = player.level().getBlockEntity(msg.pos());
            if (be == null) {
                send(player, msg.pos(), -1, List.of());
                return;
            }

            // 1) 已有缓存直接回
            SlotLayoutCache.Entry cached = SlotLayoutCache.get(be.getBlockPos(), be.getBlockState());
            if (cached != null) {
                send(player, msg.pos(), cached.total(), cached.slots());
                return;
            }

            // 2) 尝试构造 Menu 读取槽位坐标
            int total = -1;
            List<int[]> coords = List.of();
            AbstractContainerMenu probe = probeMenu(be, player);
            if (probe != null) {
                total = probe.slots.size();
                // 只还原容器自身的槽（玩家背包槽通常在尾部且带 GUI 坐标，
                // 但它们的 container 大多不同，按 container 实例区分更稳）
                var container = probe.slots.isEmpty() ? null : probe.slots.get(0).container;
                List<int[]> list = new ArrayList<>();
                for (var slot : probe.slots) {
                    if (slot.container != container) continue; // 跳过玩家背包区
                    list.add(new int[]{slot.x, slot.y});
                }
                coords = list;
                total = Math.max(total, coords.size());
            } else {
                var cap = be.getLevel() == null ? null : be.getLevel().getCapability(
                        net.neoforged.neoforge.capabilities.Capabilities.ItemHandler.BLOCK,
                        be.getBlockPos(), null);
                if (cap != null) total = cap.getSlots();
            }

            SlotLayoutCache.put(be.getBlockPos(), be.getBlockState(), total, coords);
            send(player, msg.pos(), total, coords);
        });
    }

    /** 通过 MenuProvider 构造一次菜单（不打开）；失败返回 null。 */
    private static AbstractContainerMenu probeMenu(BlockEntity be, ServerPlayer player) {
        if (!(be instanceof MenuProvider provider)) return null;
        try {
            return provider.createMenu(0, player.getInventory(), player);
        } catch (Throwable t) {
            SFMGui.LOGGER.debug("slot layout probe failed at {}", be.getBlockPos(), t);
            return null;
        }
    }

    private static void send(ServerPlayer player, net.minecraft.core.BlockPos pos,
                             int total, List<int[]> coords) {
        PacketDistributor.sendToPlayer(player, new SlotLayoutPayload(pos, total, coords));
    }
}
