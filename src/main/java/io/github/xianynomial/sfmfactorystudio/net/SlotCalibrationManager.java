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
import net.minecraftforge.network.PacketDistributor;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 操作学习会话（服务端）。核心通道 = <b>点击验证的轨迹匹配</b>，完全只读：
 *
 * <ul>
 * <li>玩家点击某个菜单格（ContainerClickMixin 在 HEAD 拦截）：记录该格的
 * 内容轨迹起点（点击前签名）与七朝向能力槽内容快照；</li>
 * <li>之后每 tick 跟踪：当该格内容发生变化（放入/取出都算）时，寻找经历了
 * <b>完全相同前后变化</b> 的唯一能力槽——找到即证实"这个视觉格 = 该能力槽"。
 * 内容变化必须与点击相关，机器后台加工（无点击关联）不会产生锚点；
 * 模组延迟应用点击（如 Mekanism）也天然兼容（最长跟踪 3 秒）。</li>
 * <li>若七个朝向的能力面在持续界面活动下始终纹丝不动，判定"槽位未暴露"
 * （典型如 Mekanism 需先在侧面配置中开放输入/输出）。</li>
 * </ul>
 *
 * <p>锚点以 <b>菜单类名 + 朝向 + 视觉格坐标</b> 为键（与方块坐标解耦）：
 * 多方块、同款机器多实例、多机绑定场景下学习成果互通。
 * 全链路日志前缀 {@code [sfmjimu-calib]}。
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

    private static final int TRACK_TICKS = 60;                  // 单次点击最长跟踪 3 秒
    private static final int MAX_SESSION_TICKS = 20 * 180;      // 会话上限 3 分钟
    private static final int NO_EXPOSURE_SAMPLES = 6;           // 连续 6 次采样判"未暴露"
    private static final int MAX_PENDING = 8;                   // 每玩家待验证点击上限

    public static final int INFO_NO_EXPOSURE = 1;

    private static final class Session {
        final BlockPos pos;
        final int containerId;
        final String menuClass;
        final MinecraftServer server;
        int ticksLeft = MAX_SESSION_TICKS;
        int sampleCountdown = 10;
        int noExposureStreak = 0;
        boolean noExposureSent = false;
        long[][] prevCap;                       // 未暴露诊断用
        long[] prevMenu;                        // 未暴露诊断用
        final List<int[]> sentAnchors = new ArrayList<>();   // {dir, x, y, capIndex}
        final List<PendingClick> pending = new ArrayList<>();

        Session(BlockPos pos, int containerId, String menuClass, MinecraftServer server) {
            this.pos = pos;
            this.containerId = containerId;
            this.menuClass = menuClass == null ? "" : menuClass;
            this.server = server;
        }
    }

    /** 一次待验证的点击：视觉格（菜单内槽号/坐标）+ 内容轨迹起点 + 能力面快照。 */
    private static final class PendingClick {
        final int slotNum;
        final int containerSlot;
        final int x;
        final int y;
        final long menuBefore;
        final long[][] capBefore;
        int ticksLeft = TRACK_TICKS;

        PendingClick(int slotNum, int containerSlot, int x, int y,
                     long menuBefore, long[][] capBefore) {
            this.slotNum = slotNum;
            this.containerSlot = containerSlot;
            this.x = x;
            this.y = y;
            this.menuBefore = menuBefore;
            this.capBefore = capBefore;
        }
    }

    private static final Map<UUID, Session> SESSIONS = new HashMap<>();
    private static final Map<UUID, List<PendingClick>> PENDING = new HashMap<>();

    public static void begin(ServerPlayer player, BlockPos pos, int containerId, String menuClass) {
        if (player == null || pos == null || containerId < 0 || player.getServer() == null) return;
        SESSIONS.put(player.getUUID(), new Session(pos, containerId,
                menuClass == null ? "" : menuClass, player.getServer()));
        PENDING.remove(player.getUUID());
        SFMGui.LOGGER.info("[sfmjimu-calib] 会话开始: 玩家 {} 方块 {} 菜单 {} containerId {}",
                player.getGameProfile().getName(), pos, menuClass, containerId);
    }

    public static void forget(UUID playerId, String reason) {
        Session s = SESSIONS.remove(playerId);
        PENDING.remove(playerId);
        if (s != null) {
            SFMGui.LOGGER.info("[sfmjimu-calib] 会话结束({}): 方块 {} 菜单 {}", reason, s.pos, s.menuClass);
        }
    }

    /** 点击前（HEAD）：记录视觉格与内容轨迹起点 + 能力面快照。 */
    public static void beforeClick(ServerPlayer player, net.minecraft.network.protocol.game.ServerboundContainerClickPacket packet) {
        UUID id = player.getUUID();
        Session session = SESSIONS.get(id);
        if (session == null) {
            PENDING.remove(id);
            return;
        }
        if (session.containerId != packet.getContainerId()) {
            forget(id, "切到其他界面");
            return;
        }
        // 只处理能产生"单格变化"语义的点击；拖动（多槽分配）/克隆跳过
        ClickType type = packet.getClickType();
        if (type != ClickType.PICKUP && type != ClickType.QUICK_MOVE && type != ClickType.SWAP) return;

        AbstractContainerMenu menu = player.containerMenu;
        int slotNum = packet.getSlotNum();
        if (slotNum < 0 || slotNum >= menu.slots.size()) return;
        Slot slot = menu.slots.get(slotNum);
        if (slot.container == player.getInventory()) return;   // 玩家背包格与机器能力面无关

        List<PendingClick> pending = PENDING.computeIfAbsent(id, k -> new ArrayList<>());
        if (pending.size() >= MAX_PENDING) pending.remove(0);   // 队列上限：丢最旧
        pending.add(new PendingClick(slotNum, slot.getContainerSlot(), slot.x, slot.y,
                slotSig(safeGetItem(slot)), captureContents(player.serverLevel(), session.pos)));
    }

    /** 服务端每 tick 调用（ServerTickEvent.END）：轨迹跟踪 + 未暴露诊断。 */
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
                PENDING.remove(id);
                continue;
            }
            if (player.containerMenu == null || player.containerMenu.containerId != s.containerId) {
                forget(id, "界面已关闭");
                continue;
            }

            trackPending(player, s);
            diagnoseExposure(player, s);
        }
    }

    /** 点击验证的轨迹匹配：被点格子的变化 → 唯一同轨迹能力槽。 */
    private static void trackPending(ServerPlayer player, Session s) {
        UUID id = player.getUUID();
        List<PendingClick> pending = PENDING.get(id);
        if (pending == null || pending.isEmpty()) return;
        AbstractContainerMenu menu = player.containerMenu;
        long[][] capNowArr = captureContents(player.serverLevel(), s.pos);
        Iterator<PendingClick> it = pending.iterator();
        while (it.hasNext()) {
            PendingClick pc = it.next();
            if (--pc.ticksLeft <= 0) {
                it.remove();   // 跟踪超时（变化与应用始终未观测到）
                continue;
            }
            if (pc.slotNum >= menu.slots.size()) {
                it.remove();
                continue;
            }
            Slot slot = menu.slots.get(pc.slotNum);
            long menuNow = slotSig(safeGetItem(slot));
            if (menuNow == pc.menuBefore) continue;   // 内容还没变（含延迟应用/放回原样）

            // 内容变化了：在七个朝向中寻找经历完全相同轨迹的能力槽
            // 判定 = 唯一（任一朝向出现 ≥2 个同轨迹槽即视为歧义放弃）
            int foundDir = -1, foundCap = -1, totalMatches = 0;
            for (int d = 0; d < 7 && totalMatches <= 1; d++) {
                if (d >= pc.capBefore.length || d >= capNowArr.length) continue;
                int dirMatches = 0, lastK = -1;
                for (int k = 0; k < pc.capBefore[d].length && k < capNowArr[d].length; k++) {
                    if (pc.capBefore[d][k] == pc.menuBefore && capNowArr[d][k] == menuNow) {
                        dirMatches++;
                        lastK = k;
                    }
                }
                if (dirMatches == 1) {
                    totalMatches++;
                    foundDir = d;
                    foundCap = lastK;
                } else if (dirMatches > 1) {
                    totalMatches = 2;
                }
            }
            if (totalMatches == 1) {
                it.remove();
                if (rememberAnchor(s, foundDir, pc.x, pc.y, foundCap)) {
                    SFMGuiNetwork.CHANNEL.send(
                            PacketDistributor.PLAYER.with(() -> player),
                            new SlotAnchorPayload(s.pos, s.menuClass, foundDir,
                                    pc.containerSlot, pc.x, pc.y, foundCap));
                    SFMGui.LOGGER.info("[sfmjimu-calib] 轨迹锚定: 菜单 {} 朝向 {} 格 ({},{}) → 能力槽 {}",
                            s.menuClass, foundDir, pc.x, pc.y, foundCap);
                }
            } else if (totalMatches > 1) {
                it.remove();   // 多个能力槽同轨迹 = 歧义（如同类槽内容相同），放弃本次
                SFMGui.LOGGER.debug("[sfmjimu-calib] 轨迹歧义（{} 个同轨迹能力槽），放弃", totalMatches);
            }
            // totalMatches == 0：可能尚未应用（延迟），继续等至超时
        }
    }

    /** 未暴露诊断：界面槽位在真实变化而七个朝向能力面纹丝不动。 */
    private static void diagnoseExposure(ServerPlayer player, Session s) {
        if (--s.sampleCountdown > 0) return;
        s.sampleCountdown = 10;
        AbstractContainerMenu menu = player.containerMenu;
        Inventory playerInv = player.getInventory();
        long[] menuNow = new long[menu.slots.size()];
        boolean anyNonPlayer = false;
        for (int i = 0; i < menu.slots.size(); i++) {
            Slot slot = menu.slots.get(i);
            if (slot.container == playerInv) {
                menuNow[i] = Long.MIN_VALUE;
                continue;
            }
            anyNonPlayer = true;
            menuNow[i] = slotSig(safeGetItem(slot));
        }
        if (!anyNonPlayer) return;

        long[][] capNow = captureContents(player.serverLevel(), s.pos);
        if (s.prevMenu != null && s.prevCap != null && s.prevMenu.length == menuNow.length) {
            boolean menuRealChange = false;
            boolean capChangedAny = false;
            for (int i = 0; i < menuNow.length; i++) {
                if (menuNow[i] == Long.MIN_VALUE) continue;
                if (s.prevMenu[i] != menuNow[i]
                        && (!isEmptySig(s.prevMenu[i]) || !isEmptySig(menuNow[i]))) {
                    menuRealChange = true;
                }
            }
            for (int d = 0; d < 7 && !capChangedAny; d++) {
                if (s.prevCap[d].length != capNow[d].length) {
                    capChangedAny = true;
                    continue;
                }
                for (int k = 0; k < capNow[d].length; k++) {
                    if (s.prevCap[d][k] != capNow[d][k]) {
                        capChangedAny = true;
                        break;
                    }
                }
            }
            if (menuRealChange && !capChangedAny) {
                s.noExposureStreak++;
                if (s.noExposureStreak >= NO_EXPOSURE_SAMPLES && !s.noExposureSent) {
                    s.noExposureSent = true;
                    SFMGuiNetwork.CHANNEL.send(
                            PacketDistributor.PLAYER.with(() -> player),
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

    private static ItemStack safeGetItem(Slot slot) {
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

    /** 锚点去重键 = (朝向, 视觉格 x, y)：同一格重复学习同值不重发，异值发更新。 */
    private static boolean rememberAnchor(Session s, int dir, int x, int y, int capIndex) {
        for (int[] a : s.sentAnchors) {
            if (a[0] == dir && a[1] == x && a[2] == y) {
                boolean changed = a[3] != capIndex;
                a[3] = capIndex;
                return changed;
            }
        }
        s.sentAnchors.add(new int[]{dir, x, y, capIndex});
        return true;
    }

    // ---- 快照 ----

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
            var handler = (net.minecraftforge.items.IItemHandler) result.unwrap();
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
