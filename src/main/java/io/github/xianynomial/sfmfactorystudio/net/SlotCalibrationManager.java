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
 * 操作学习会话（服务端）。核心机制 = <b>逐刻被动关联</b>，完全只读：
 *
 * <p>界面开着时每 tick 采样一次"菜单槽内容"与"七朝向能力槽内容"。
 * 某界面格与某能力槽（某个朝向的某个索引）的内容在采样中持续一致、
 * 且观测到过至少一次同步变化（玩家放/取物品、机器加工都会产生）、
 * 且内容非空——即证实两者是同一底层存储，锚定"该视觉格（坐标）=
 * 该能力槽（朝向+索引）"。一旦内容分叉立即取消配对资格。
 *
 * <p>该机制对任何模组、任何槽位包装方式、任何点击应用时机都成立：
 * 它不猜测、不拦截，只观测真实数据流的长期一致性。
 *
 * <p>附加诊断：界面槽位持续变化而七个朝向能力面纹丝不动（连续 6 采样），
 * 判定"槽位未暴露"（典型如 Mekanism 需先在侧面配置中开放输入/输出）。
 *
 * <p>锚点以 <b>菜单类名 + 朝向 + 视觉格坐标</b> 为共享键（与方块坐标解耦）：
 * 多方块、同款机器多实例、多机绑定场景下学习成果互通。会话随界面关闭结束，
 * 上限 3 分钟；之后重新打开界面即开启新会话继续学习。
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

    private static final int MAX_SESSION_TICKS = 20 * 180;      // 会话上限 3 分钟
    private static final int ANCHOR_STREAK = 5;                 // 连续 5 次采样内容一致
    private static final int ANCHOR_JOINT_CHANGES = 1;          // 且观测到 ≥1 次同步变化
    private static final int NO_EXPOSURE_SAMPLES = 6;           // 连续 6 次采样判"未暴露"

    public static final int INFO_NO_EXPOSURE = 1;

    /** 单对（菜单格, 朝向, 能力槽）的关联状态。disqualified = 内容曾分叉。 */
    private static final class PairState {
        int streak = 0;
        int jointChanges = 0;
        boolean disqualified = false;
        boolean anchored = false;
    }

    private static final class Session {
        final BlockPos pos;
        final int containerId;
        final String menuClass;
        final MinecraftServer server;
        int ticksLeft = MAX_SESSION_TICKS;
        int noExposureStreak = 0;
        boolean noExposureSent = false;
        boolean sampled = false;
        long[] prevMenu;
        long[][] prevCap;
        // pairs[菜单槽索引] → {朝向 d → {能力槽 k → 状态}}
        final Map<Integer, Map<Integer, Map<Integer, PairState>>> pairStates = new HashMap<>();
        final List<int[]> sentAnchors = new ArrayList<>();   // {dir, x, y, capIndex}
        List<Integer> menuCs = new ArrayList<>();
        List<Integer> menuX = new ArrayList<>();
        List<Integer> menuY = new ArrayList<>();

        Session(BlockPos pos, int containerId, String menuClass, MinecraftServer server) {
            this.pos = pos;
            this.containerId = containerId;
            this.menuClass = menuClass == null ? "" : menuClass;
            this.server = server;
        }
    }

    private static final Map<UUID, Session> SESSIONS = new HashMap<>();

    public static void begin(ServerPlayer player, BlockPos pos, int containerId, String menuClass) {
        if (player == null || pos == null || containerId < 0 || player.getServer() == null) return;
        SESSIONS.put(player.getUUID(), new Session(pos, containerId,
                menuClass == null ? "" : menuClass, player.getServer()));
        SFMGui.LOGGER.info("[sfmjimu-calib] 会话开始: 玩家 {} 方块 {} 菜单 {} containerId {}",
                player.getGameProfile().getName(), pos, menuClass, containerId);
    }

    public static void forget(UUID playerId, String reason) {
        Session s = SESSIONS.remove(playerId);
        if (s != null) {
            SFMGui.LOGGER.info("[sfmjimu-calib] 会话结束({}): 方块 {} 菜单 {}", reason, s.pos, s.menuClass);
        }
    }

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
                continue;
            }
            if (player.containerMenu == null || player.containerMenu.containerId != s.containerId) {
                forget(id, "界面已关闭");
                continue;
            }
            sample(player, s);
        }
    }

    private static void sample(ServerPlayer player, Session s) {
        AbstractContainerMenu menu = player.containerMenu;
        Inventory playerInv = player.getInventory();

        int n = menu.slots.size();
        if (s.menuCs.size() != n) {
            // 菜单槽数量变化：重置配对状态与基线
            s.pairStates.clear();
            s.sampled = false;
            s.menuCs = new ArrayList<>();
            s.menuX = new ArrayList<>();
            s.menuY = new ArrayList<>();
            for (int i = 0; i < n; i++) {
                Slot slot = menu.slots.get(i);
                s.menuCs.add(slot.getContainerSlot());
                s.menuX.add(slot.x);
                s.menuY.add(slot.y);
            }
        }

        long[] menuNow = new long[n];
        for (int i = 0; i < n; i++) {
            Slot slot = menu.slots.get(i);
            menuNow[i] = slot.container == playerInv ? Long.MIN_VALUE : slotSig(safeGetItem(slot));
        }

        long[][] capNow = captureContents(player.serverLevel(), s.pos);

        if (s.sampled && s.prevMenu != null && s.prevMenu.length == n && s.prevCap != null) {
            boolean menuRealChange = false;
            boolean capChangedAny = false;
            for (int i = 0; i < n; i++) {
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
                    PacketDistributor.sendToPlayer(player,
                            new SlotCalibrationInfoPayload(s.pos, INFO_NO_EXPOSURE));
                    SFMGui.LOGGER.info("[sfmjimu-calib] 诊断: 界面槽位在变化但能力面无变化 → 判定槽位未暴露, pos {}", s.pos);
                }
            } else if (capChangedAny) {
                s.noExposureStreak = 0;
            }

            // 逐对关联：内容持续一致 → streak；同步变化 → jointChanges；分叉 → 取消资格
            for (int j = 0; j < n; j++) {
                if (menuNow[j] == Long.MIN_VALUE) continue;
                for (int d = 0; d < 7; d++) {
                    if (d >= capNow.length) continue;
                    for (int k = 0; k < capNow[d].length; k++) {
                        PairState ps = pairState(s, j, d, k);
                        if (ps.disqualified || ps.anchored) continue;
                        boolean capKnown = k < s.prevCap[d].length;
                        long capPrev = capKnown ? s.prevCap[d][k] : Long.MIN_VALUE;
                        long capCur = capNow[d][k];
                        long menuPrev = s.prevMenu[j];
                        long menuCur = menuNow[j];
                        if (capPrev != menuPrev || capCur != menuCur) {
                            ps.disqualified = true;   // 内容分叉 = 不是同一存储
                            continue;
                        }
                        ps.streak++;
                        boolean jointChange = menuPrev != menuCur
                                && capPrev != capCur
                                && menuCur != 0
                                && capCur != 0;
                        if (jointChange) ps.jointChanges++;
                        // 锚定：持续一致 + 观测过同步变化 + 内容非空
                        if (ps.streak >= ANCHOR_STREAK && ps.jointChanges >= ANCHOR_JOINT_CHANGES
                                && menuCur != 0 && capCur != 0) {
                            ps.anchored = true;
                            if (rememberAnchor(s, d, s.menuX.get(j), s.menuY.get(j), k)) {
                                PacketDistributor.sendToPlayer(player,
                                        new SlotAnchorPayload(s.pos, s.menuClass, d,
                                                s.menuCs.get(j), s.menuX.get(j), s.menuY.get(j), k));
                                SFMGui.LOGGER.info("[sfmjimu-calib] 被动关联锚定: 菜单 {} 朝向 {} 格 ({},{}) → 能力槽 {}",
                                        s.menuClass, d, s.menuX.get(j), s.menuY.get(j), k);
                            }
                        }
                    }
                }
            }
        }

        s.prevMenu = menuNow;
        s.prevCap = capNow;
        s.sampled = true;
    }

    private static PairState pairState(Session s, int j, int d, int k) {
        return s.pairStates
                .computeIfAbsent(j, x -> new HashMap<>())
                .computeIfAbsent(d, x -> new HashMap<>())
                .computeIfAbsent(k, x -> new PairState());
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
