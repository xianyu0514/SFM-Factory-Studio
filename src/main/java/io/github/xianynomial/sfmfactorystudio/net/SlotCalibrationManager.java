package io.github.xianynomial.sfmfactorystudio.net;

import ca.teamdman.sfm.common.capability.SFMBlockCapabilityDiscovery;
import ca.teamdman.sfm.common.capability.SFMBlockCapabilityResult;
import ca.teamdman.sfm.common.registry.registration.SFMResourceTypes;
import ca.teamdman.sfm.common.resourcetype.ItemResourceType;
import io.github.xianynomial.sfmfactorystudio.SFMGui;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.ClickType;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * 操作学习会话（服务端）：玩家打开容器界面时会话开始；此后每次容器点击
 * （由 {@link io.github.xianynomial.sfmfactorystudio.mixin.ContainerClickMixin}
 * 拦截），点击前后各做一次能力槽内容快照——**恰好一个槽位变化**即证实
 * "被点击的视觉格 = 该能力槽"，锚点发回客户端持久化。
 *
 * <p>这是完全模组无关的观测校准：不管模组怎么包装槽位，真实数据流不会骗人。
 * 只处理能产生单槽语义的点击（拿起/放下/快速移动/数字键交换）；
 * 拖动刷选（多槽分配）与创造克隆跳过。全程只读，不修改任何游戏状态。
 */
public final class SlotCalibrationManager {
    private SlotCalibrationManager() {
    }

    public record Session(BlockPos pos, int containerId) {
    }

    /** 每个玩家只保留最近打开的界面会话；快照仅在单次点击处理期间存在。 */
    private static final Map<UUID, Session> SESSIONS = new HashMap<>();
    private static final Map<UUID, long[][]> SNAPSHOTS = new HashMap<>();

    public static void begin(ServerPlayer player, BlockPos pos, int containerId) {
        if (player == null || pos == null || containerId < 0) return;
        SESSIONS.put(player.getUUID(), new Session(pos, containerId));
        SNAPSHOTS.remove(player.getUUID());
    }

    public static void forget(UUID playerId) {
        SESSIONS.remove(playerId);
        SNAPSHOTS.remove(playerId);
    }

    /** 点击前（HEAD）：登记会话并快照能力槽内容。 */
    public static void beforeClick(ServerPlayer player, net.minecraft.network.protocol.game.ServerboundContainerClickPacket packet) {
        UUID id = player.getUUID();
        SNAPSHOTS.remove(id);
        Session session = SESSIONS.get(id);
        if (session == null) return;
        if (session.containerId() != packet.getContainerId()) {
            SESSIONS.remove(id);   // 玩家已切到别的界面：旧会话作废
            return;
        }
        SNAPSHOTS.put(id, captureContents(player.serverLevel(), session.pos()));
    }

    /** 点击后（RETURN）：差分能力槽内容，单槽变化即回传锚点。 */
    public static void afterClick(ServerPlayer player, net.minecraft.network.protocol.game.ServerboundContainerClickPacket packet) {
        UUID id = player.getUUID();
        Session session = SESSIONS.get(id);
        long[][] before = SNAPSHOTS.remove(id);
        if (session == null || before == null) return;
        if (session.containerId() != packet.getContainerId()) return;

        // 只处理能产生"单槽变化"语义的点击；拖动（多槽分配）/克隆跳过
        ClickType type = packet.getClickType();
        if (type != ClickType.PICKUP && type != ClickType.QUICK_MOVE && type != ClickType.SWAP) return;

        AbstractContainerMenu menu = player.containerMenu;
        int slotNum = packet.getSlotNum();
        if (slotNum < 0 || slotNum >= menu.slots.size()) return;
        Slot slot = menu.getSlot(slotNum);
        if (slot.container == player.getInventory()) return;   // 玩家背包格与机器能力面无关

        long[][] after = captureContents(player.serverLevel(), session.pos());
        for (int d = 0; d < 7; d++) {
            int capIndex = singleChangedSlot(before[d], after[d]);
            if (capIndex >= 0) {
                net.neoforged.neoforge.network.PacketDistributor.sendToPlayer(player,
                        new SlotAnchorPayload(session.pos(), d,
                                slot.getContainerSlot(), slot.x, slot.y, capIndex));
            }
        }
    }

    /** 恰好一个槽位内容变化时返回其索引；零/多槽变化或结构变化返回 -1。 */
    private static int singleChangedSlot(long[] before, long[] after) {
        if (before.length != after.length || before.length == 0) return -1;
        int changed = -1;
        for (int i = 0; i < after.length; i++) {
            if (before[i] != after[i]) {
                if (changed >= 0) return -1;
                changed = i;
            }
        }
        return changed;
    }

    /** 七朝向能力槽内容签名（物品注册 id << 32 | 数量）。只读。 */
    private static long[][] captureContents(Level level, BlockPos pos) {
        ItemResourceType itemType = SFMResourceTypes.ITEM.get();
        long[][] out = new long[7][];
        out[0] = captureDir(itemType, level, pos, null);
        Direction[] dirs = Direction.values();
        for (int i = 0; i < 6; i++) out[i + 1] = captureDir(itemType, level, pos, dirs[i]);
        return out;
    }

    private static long[] captureDir(ItemResourceType itemType, Level level, BlockPos pos, Direction dir) {
        try {
            SFMBlockCapabilityResult<?> result = SFMBlockCapabilityDiscovery
                    .discoverCapabilityFromLevel(level, itemType.capabilityKind(), pos, dir);
            if (result == null || !result.isPresent()) return new long[0];
            var handler = (net.neoforged.neoforge.items.IItemHandler) result.unwrap();
            int n = Math.min(itemType.getSlots(handler), 4096);
            long[] sig = new long[n];
            for (int i = 0; i < n; i++) {
                ItemStack stack;
                try {
                    stack = (ItemStack) itemType.getStackInSlot(handler, i);
                } catch (Throwable t) {
                    stack = ItemStack.EMPTY;
                }
                int itemId = stack == null || stack.isEmpty()
                        ? -1 : BuiltInRegistries.ITEM.getId(stack.getItem());
                sig[i] = ((long) itemId << 32) | (stack == null ? 0 : stack.getCount() & 0xFFFFFFFFL);
            }
            return sig;
        } catch (Throwable t) {
            SFMGui.LOGGER.debug("calibration snapshot failed at {} dir {}", pos, dir, t);
            return new long[0];
        }
    }
}
