package io.github.xianynomial.sfmfactorystudio.net;

import ca.teamdman.sfm.common.capability.SFMBlockCapabilityDiscovery;
import ca.teamdman.sfm.common.capability.SFMBlockCapabilityResult;
import ca.teamdman.sfm.common.registry.registration.SFMResourceTypes;
import ca.teamdman.sfm.common.resourcetype.ItemResourceType;
import io.github.xianynomial.sfmfactorystudio.SFMGui;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.ClickType;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.neoforged.neoforge.network.PacketDistributor;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 操作学习会话（服务端）。两个互补的观测通道，全部只读：
 *
 * <ul>
 * <li><b>点击差分</b>：ContainerClickMixin 在点击前后调用 before/afterClick，
 * 恰好一个能力槽变化即证实"被点视觉格 = 该能力槽"（对同步应用点击的容器即时生效）；</li>
 * <li><b>被动采样</b>：界面开着时每 10 刻采样一次"菜单槽内容"与"七朝向能力槽内容"，
 * 某能力槽与某菜单槽在同一个采样周期内发生了**完全相同的**前后变化（同物品同数量），
 * 即证实两者是同一底层存储——不依赖点击时机，机器自己运行也能学习。
 * 若菜单槽持续变化而七朝向能力面全无变化，判定"槽位未暴露"并提示玩家
 * 在机器的侧面配置中开放输入/输出（如 Mekanism 侧面配置）。</li>
 * </ul>
 *
 * <p>锚点以 <b>菜单类名</b> 为共享键（与方块坐标解耦）：多方块、同款机器多实例、
 * 多机绑定场景下学习成果互通。全程只读，不修改任何游戏状态。
 * 全链路日志前缀 {@code [sfmjimu-calib]}，任何环节失效都可在 latest.log 定位。
 */
public final class SlotCalibrationManager {
    private SlotCalibrationManager() {
    }

    public static final int STATE_OK = 0;
    public static final int STATE_SIDE_FALLBACK = 1;
    public static final int STATE_NO_CAPABILITY = 2;
    public static final int STATE_UNREACHABLE = -1;

    /** 读取半径（格）。工厂里机器离玩家很远，放宽到 64。 */
    private static final double MAX_DISTANCE_SQR = 64 * 64;

    private static final int SAMPLE_INTERVAL_TICKS = 10;
    private static final int MAX_SESSION_TICKS = 20 * 180;      // 会话上限 3 分钟
    private static final int NO_EXPOSURE_SAMPLES = 6;           // 连续 6 次采样判"未暴露"

    public static final int INFO_NO_EXPOSURE = 1;

    private static final class Session {
        final BlockPos pos;
        final int containerId;
        final String menuClass;
        final MinecraftServer server;
        int ticksLeft = MAX_SESSION_TICKS;
        int sampleCountdown = SAMPLE_INTERVAL_TICKS;
        int noExposureStreak = 0;
        boolean noExposureSent = false;
        long[][] prevCap;          // [dir][slot] = 签名
        long[] prevMenu;           // 菜单非玩家槽签名
        final List<int[]> sentAnchors = new ArrayList<>();   // {dir, containerSlot, capIndex}

        Session(BlockPos pos, int containerId, String menuClass, MinecraftServer server) {
            this.pos = pos;
            this.containerId = containerId;
            this.menuClass = menuClass == null ? "" : menuClass;
            this.server = server;
        }
    }

    private static final Map<UUID, Session> SESSIONS = new HashMap<>();
    private static final Map<UUID, long[][]> CLICK_SNAPSHOTS = new HashMap<>();

    public static void begin(ServerPlayer player, BlockPos pos, int containerId, String menuClass) {
        if (player == null || pos == null || containerId < 0 || player.getServer() == null) return;
        SESSIONS.put(player.getUUID(), new Session(pos, containerId,
                menuClass == null ? "" : menuClass, player.getServer()));
        CLICK_SNAPSHOTS.remove(player.getUUID());
        SFMGui.LOGGER.info("[sfmjimu-calib] 会话开始: 玩家 {} 方块 {} 菜单 {} containerId {}",
                player.getGameProfile().getName(), pos, menuClass, containerId);
    }

    public static void forget(UUID playerId, String reason) {
        Session s = SESSIONS.remove(playerId);
        CLICK_SNAPSHOTS.remove(playerId);
        if (s != null) {
            SFMGui.LOGGER.info("[sfmjimu-calib] 会话结束({}): 方块 {} 菜单 {}", reason, s.pos, s.menuClass);
        }
    }

    // ---- 通道一：点击差分 ----

    /** 点击前（HEAD）：登记会话并快照能力槽内容。 */
    public static void beforeClick(ServerPlayer player, net.minecraft.network.protocol.game.ServerboundContainerClickPacket packet) {
        UUID id = player.getUUID();
        CLICK_SNAPSHOTS.remove(id);
        Session session = SESSIONS.get(id);
        if (session == null) return;
        if (session.containerId != packet.getContainerId()) {
            forget(id, "切到其他界面");
            return;
        }
        CLICK_SNAPSHOTS.put(id, captureContents(player.serverLevel(), session.pos));
    }

    /** 点击后（RETURN）：差分能力槽内容，单槽变化即回传锚点。 */
    public static void afterClick(ServerPlayer player, net.minecraft.network.protocol.game.ServerboundContainerClickPacket packet) {
        UUID id = player.getUUID();
        Session session = SESSIONS.get(id);
        long[][] before = CLICK_SNAPSHOTS.remove(id);
        if (session == null || before == null) return;
        if (session.containerId != packet.getContainerId()) return;

        // 只处理能产生"单槽变化"语义的点击；拖动（多槽分配）/克隆跳过
        ClickType type = packet.getClickType();
        if (type != ClickType.PICKUP && type != ClickType.QUICK_MOVE && type != ClickType.SWAP) return;

        AbstractContainerMenu menu = player.containerMenu;
        int slotNum = packet.getSlotNum();
        if (slotNum < 0 || slotNum >= menu.slots.size()) return;
        Slot slot = menu.getSlot(slotNum);
        if (slot.container == player.getInventory()) return;   // 玩家背包格与机器能力面无关

        long[][] after = captureContents(player.serverLevel(), session.pos);
        for (int d = 0; d < 7; d++) {
            int capIndex = singleChangedSlot(before[d], after[d]);
            if (capIndex >= 0 && rememberAnchor(session, d, slot.getContainerSlot(), capIndex)) {
                PacketDistributor.sendToPlayer(player,
                        new SlotAnchorPayload(session.pos, session.menuClass, d,
                                slot.getContainerSlot(), slot.x, slot.y, capIndex));
                SFMGui.LOGGER.info("[sfmjimu-calib] 点击差分锚定: 菜单 {} 朝向 {} 容器槽 {} → 能力槽 {}",
                        session.menuClass, d, slot.getContainerSlot(), capIndex);
            }
        }
    }

    // ---- 通道二：被动采样 ----

    /** 服务端每 tick 调用（ServerTickEvent.END）。 */
    public static void tick(MinecraftServer server) {
        if (SESSIONS.isEmpty()) return;
        for (Map.Entry<UUID, Session> entry : new ArrayList<>(SESSIONS.entrySet())) {
            UUID id = entry.getKey();
            Session s = entry.getValue();
            if (--s.ticksLeft <= 0) {
                forget(id, "超时");
                continue;
            }
            ServerPlayer player = server.getPlayerList().getPlayer(id);
            if (player == null) {
                SESSIONS.remove(id);
                CLICK_SNAPSHOTS.remove(id);
                continue;
            }
            if (player.containerMenu == null || player.containerMenu.containerId != s.containerId) {
                forget(id, "界面已关闭");
                continue;
            }
            if (--s.sampleCountdown > 0) continue;
            s.sampleCountdown = SAMPLE_INTERVAL_TICKS;
            sample(player, s);
        }
    }

    private static void sample(ServerPlayer player, Session s) {
        AbstractContainerMenu menu = player.containerMenu;

        // 菜单槽签名（非玩家槽；玩家背包格不参与配对）
        int n = menu.slots.size();
        long[] menuNow = new long[n];
        int[] menuCs = new int[n];
        int[] menuX = new int[n];
        int[] menuY = new int[n];
        Inventory playerInv = player.getInventory();
        for (int i = 0; i < n; i++) {
            Slot slot = menu.slots.get(i);
            menuCs[i] = slot.getContainerSlot();
            menuX[i] = slot.x;
            menuY[i] = slot.y;
            menuNow[i] = slot.container == playerInv ? Long.MIN_VALUE : slotSig(safeGet(menu, i));
        }

        long[][] capNow = captureContents(player.serverLevel(), s.pos);

        if (s.prevMenu != null && s.prevCap != null && s.prevMenu.length == n) {
            boolean menuRealChange = false;
            boolean capChangedAny = false;
            for (int i = 0; i < n; i++) {
                if (menuNow[i] == Long.MIN_VALUE) continue;
                if (s.prevMenu[i] != menuNow[i]
                        && (!isEmptySig(s.prevMenu[i]) || !isEmptySig(menuNow[i]))) {
                    menuRealChange = true;
                }
            }
            for (int d = 0; d < 7; d++) {
                List<Integer> changedCap = new ArrayList<>();
                if (s.prevCap[d].length == capNow[d].length) {
                    for (int k = 0; k < capNow[d].length; k++) {
                        if (s.prevCap[d][k] != capNow[d][k]) changedCap.add(k);
                    }
                }
                if (!changedCap.isEmpty()) capChangedAny = true;

                // 单能力槽变化：找与它前后内容完全一致的菜单格（同一底层存储的证据）
                if (changedCap.size() == 1) {
                    int k = changedCap.get(0);
                    int partner = -1;
                    boolean ambiguous = false;
                    for (int i = 0; i < n; i++) {
                        if (menuNow[i] == Long.MIN_VALUE) continue;
                        if (s.prevMenu[i] == s.prevCap[d][k] && menuNow[i] == capNow[d][k]
                                && s.prevMenu[i] != menuNow[i]) {
                            if (partner >= 0) {
                                ambiguous = true;
                                break;   // 多个同变候选 = 歧义，本周期放弃
                            }
                            partner = i;
                        }
                    }
                    if (!ambiguous && partner >= 0 && rememberAnchor(s, d, menuCs[partner], k)) {
                        PacketDistributor.sendToPlayer(player,
                                new SlotAnchorPayload(s.pos, s.menuClass, d, menuCs[partner],
                                        menuX[partner], menuY[partner], k));
                        SFMGui.LOGGER.info("[sfmjimu-calib] 采样锚定: 菜单 {} 朝向 {} 容器槽 {} → 能力槽 {}",
                                s.menuClass, d, menuCs[partner], k);
                    }
                }
            }
            // "槽位未暴露"判定：界面内容真有变化，而七朝向能力面全程纹丝不动
            if (menuRealChange && !capChangedAny) {
                s.noExposureStreak++;
                if (s.noExposureStreak >= NO_EXPOSURE_SAMPLES && !s.noExposureSent) {
                    s.noExposureSent = true;
                    PacketDistributor.sendToPlayer(player,
                            new SlotCalibrationInfoPayload(s.pos, INFO_NO_EXPOSURE));
                    SFMGui.LOGGER.info("[sfmjimu-calib] 诊断: 界面槽位在变化但能力面无变化 → 判定槽位未暴露, pos {}", s.pos);
                }
            } else if (capChangedAny) {
                s.noExposureStreak = 0;
            }
        }

        s.prevMenu = menuNow;
        s.prevCap = capNow;
    }

    private static ItemStack safeGet(AbstractContainerMenu menu, int i) {
        Slot slot = menu.slots.get(i);
        try {
            return slot.getItem();
        } catch (Throwable t) {
            return ItemStack.EMPTY;
        }
    }

    private static long slotSig(ItemStack stack) {
        if (stack == null || stack.isEmpty()) return 0L;
        int itemId;
        try {
            itemId = BuiltInRegistries.ITEM.getId(stack.getItem());
        } catch (Throwable t) {
            itemId = -1;
        }
        return ((long) itemId << 32) | (stack.getCount() & 0xFFFFFFFFL);
    }

    private static boolean isEmptySig(long sig) {
        return sig == 0L;
    }

    private static boolean rememberAnchor(Session s, int dir, int containerSlot, int capIndex) {
        for (int[] a : s.sentAnchors) {
            if (a[0] == dir && a[1] == containerSlot) {
                boolean changed = a[2] != capIndex;
                a[2] = capIndex;
                return changed;
            }
        }
        s.sentAnchors.add(new int[]{dir, containerSlot, capIndex});
        return true;
    }

    // ---- 快照 ----

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
                sig[i] = slotSig(stack);
            }
            return sig;
        } catch (Throwable t) {
            SFMGui.LOGGER.debug("calibration snapshot failed at {} dir {}", pos, dir, t);
            return new long[0];
        }
    }
}
