package io.github.xianynomial.sfmfactorystudio.net;

import ca.teamdman.sfml.ast.Side;
import ca.teamdman.sfm.common.capability.SFMBlockCapabilityDiscovery;
import ca.teamdman.sfm.common.capability.SFMBlockCapabilityResult;
import ca.teamdman.sfm.common.registry.registration.SFMResourceTypes;
import ca.teamdman.sfm.common.resourcetype.ItemResourceType;
import io.github.xianynomial.sfmfactorystudio.SFMGui;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import net.neoforged.neoforge.network.PacketDistributor;
import net.neoforged.neoforge.network.handling.IPayloadContext;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * 服务端：用 SFM 本体自己的解析路径读取目标方块的能力槽
 * （{@link SFMBlockCapabilityDiscovery#discoverCapabilityFromLevel}——包含
 * SFM 线缆网络提供者，SFm 看到什么这里就查到什么），按语句的侧面限定解析方向。
 *
 * <p>回包 state 语义：
 * <ul>
 * <li>{@link #STATE_OK} —— 限定方向下解析到了能力面，items/counts 即其内容；</li>
 * <li>{@link #STATE_SIDE_FALLBACK} —— 限定方向全空，但其他朝向有：refDir 指明
 * 实际用于编号的朝向（SFM 默认面读不到的机器，写程序需要侧面限定）；</li>
 * <li>{@link #STATE_NO_CAPABILITY} —— 所有朝向都没有物品能力面；</li>
 * <li>{@link #STATE_UNREACHABLE} —— 方块实体不存在或距离过远。</li>
 * </ul>
 * 全程只读，不改任何状态。
 */
public final class SlotCapabilityRequestHandler {
    private SlotCapabilityRequestHandler() {
    }

    public static final int STATE_OK = 0;
    public static final int STATE_SIDE_FALLBACK = 1;
    public static final int STATE_NO_CAPABILITY = 2;
    public static final int STATE_UNREACHABLE = -1;

    /** 读取半径（格）。工厂里机器离玩家很远，放宽到 64。 */
    private static final double MAX_DISTANCE_SQR = 64 * 64;

    /** "each side" 的完整面序（与 SFML 解析 SideQualifier.ALL 一致，含 null 面）。 */
    private static final String EACH_SIDE = "top,bottom,north,south,east,west,null";

    public static void handle(SlotCapabilityRequestPayload msg, IPayloadContext ctx) {
        ctx.enqueueWork(() -> {
            if (!(ctx.player() instanceof ServerPlayer player)) {
                return;
            }
            if (!(player.level() instanceof Level level)) {
                send(player, msg.pos(), STATE_UNREACHABLE, "", -1, new int[7], List.of(), List.of());
                return;
            }
            BlockPos pos = msg.pos();
            if (player.distanceToSqr(pos.getX() + 0.5, pos.getY() + 0.5, pos.getZ() + 0.5) > MAX_DISTANCE_SQR) {
                send(player, pos, STATE_UNREACHABLE, "", -1, new int[7], List.of(), List.of());
                return;
            }
            ItemResourceType itemType = SFMResourceTypes.ITEM.get();

            // 七个朝向各自暴露的槽位数（[0]=无侧面，1..6=down,up,north,south,west,east）
            // ——模组机器（如 Mekanism）各朝向槽位不同，用于客户端的侧面限定引导
            int[] dirTotals = new int[7];
            dirTotals[0] = probeTotal(itemType, level, pos, null);
            Direction[] worldDirs = Direction.values();
            for (int i = 0; i < 6; i++) dirTotals[i + 1] = probeTotal(itemType, level, pos, worldDirs[i]);

            // 与 SFM 本体同一套侧面解析：Side.resolve 按方块朝向换算世界方向
            List<Direction> directions = resolveDirections(level, pos, msg.sides());
            if (directions == null) {
                send(player, pos, STATE_UNREACHABLE, "", -1, dirTotals, List.of(), List.of());
                return;
            }

            // ① 限定方向顺序内找第一个有内容的能力面（SFM 遍历顺序一致）
            for (Direction dir : directions) {
                SFMBlockCapabilityResult<?> result =
                        SFMBlockCapabilityDiscovery.discoverCapabilityFromLevel(level, itemType.capabilityKind(), pos, dir);
                if (result != null && result.isPresent()) {
                    var handler = (net.neoforged.neoforge.items.IItemHandler) result.unwrap();
                    send(player, pos, STATE_OK, dirName(dir), readTotal(itemType, handler),
                            dirTotals, readItems(itemType, handler), readCounts(itemType, handler));
                    return;
                }
            }

            // ② 限定方向全空：扫其余朝向，区分"机器真没槽"和"槽位在其他面"
            Direction other = firstPresentDirection(level, itemType, pos, directions);
            if (other == null) {
                send(player, pos, STATE_NO_CAPABILITY, "", 0, dirTotals, List.of(), List.of());
                return;
            }
            SFMBlockCapabilityResult<?> result =
                    SFMBlockCapabilityDiscovery.discoverCapabilityFromLevel(level, itemType.capabilityKind(), pos, other);
            if (result == null || !result.isPresent()) {
                send(player, pos, STATE_NO_CAPABILITY, "", 0, dirTotals, List.of(), List.of());
                return;
            }
            var handler = (net.neoforged.neoforge.items.IItemHandler) result.unwrap();
            send(player, pos, STATE_SIDE_FALLBACK, dirName(other), readTotal(itemType, handler),
                    dirTotals, readItems(itemType, handler), readCounts(itemType, handler));
        });
    }

    /** 侧面名列表 → 世界方向（null = SFM 的"无侧面"查询）。 */
    private static List<Direction> resolveDirections(Level level, BlockPos pos, String sides) {
        try {
            BlockState state = level.getBlockState(pos);
            List<Direction> out = new ArrayList<>();
            for (String name : sides.split(",")) {
                String trimmed = name.trim().toUpperCase(Locale.ROOT);
                if (trimmed.isEmpty()) continue;
                out.add(Side.valueOf(trimmed).resolve(state));
            }
            if (out.isEmpty()) out.add(null);
            return out;
        } catch (Throwable t) {
            SFMGui.LOGGER.warn("side qualifier resolve failed for {}: {}", sides, t.toString());
            List<Direction> out = new ArrayList<>();
            out.add(null);
            return out;
        }
    }

    /** 限定方向之外，第一个有物品能力面的朝向（用于精确报错与兜底编号）。 */
    private static Direction firstPresentDirection(Level level, ItemResourceType itemType,
                                                   BlockPos pos, List<Direction> exclude) {
        for (Direction dir : Direction.values()) {
            if (exclude.contains(dir)) continue;
            SFMBlockCapabilityResult<?> result =
                    SFMBlockCapabilityDiscovery.discoverCapabilityFromLevel(level, itemType.capabilityKind(), pos, dir);
            if (result != null && result.isPresent()) return dir;
        }
        return null;
    }

    /** 每槽内容签名；单个槽读取失败按空槽处理，不炸整次校准。 */
    private static List<String> readItems(ItemResourceType itemType, net.neoforged.neoforge.items.IItemHandler handler) {
        int n = readTotal(itemType, handler);
        List<String> items = new ArrayList<>(Math.max(0, n));
        for (int i = 0; i < n; i++) {
            try {
                ItemStack stack = (ItemStack) itemType.getStackInSlot(handler, i);
                items.add(stack == null || stack.isEmpty()
                        ? "" : itemType.getRegistryKeyForStack(stack).toString());
            } catch (Throwable t) {
                SFMGui.LOGGER.debug("capability slot {} read failed", i, t);
                items.add("");
            }
        }
        return items;
    }

    private static List<Integer> readCounts(ItemResourceType itemType, net.neoforged.neoforge.items.IItemHandler handler) {
        int n = readTotal(itemType, handler);
        List<Integer> counts = new ArrayList<>(Math.max(0, n));
        for (int i = 0; i < n; i++) {
            try {
                ItemStack stack = (ItemStack) itemType.getStackInSlot(handler, i);
                counts.add(stack == null ? 0 : stack.getCount());
            } catch (Throwable t) {
                counts.add(0);
            }
        }
        return counts;
    }

    private static int readTotal(ItemResourceType itemType, net.neoforged.neoforge.items.IItemHandler handler) {
        try {
            return Math.min(itemType.getSlots(handler), 4096);
        } catch (Throwable t) {
            return 0;
        }
    }

    private static String dirName(Direction dir) {
        return dir == null ? "null" : dir.getName();
    }

    /** 单朝向能力面槽数（-1 = 无能力面/读取失败）。只读。 */
    private static int probeTotal(ItemResourceType itemType, Level level, BlockPos pos, Direction dir) {
        try {
            SFMBlockCapabilityResult<?> r =
                    SFMBlockCapabilityDiscovery.discoverCapabilityFromLevel(level, itemType.capabilityKind(), pos, dir);
            if (r == null || !r.isPresent()) return -1;
            return Math.min(itemType.getSlots((net.neoforged.neoforge.items.IItemHandler) r.unwrap()), 4096);
        } catch (Throwable t) {
            return -1;
        }
    }

    private static void send(ServerPlayer player, BlockPos pos, int state, String refDir,
                             int total, int[] dirTotals, List<String> items, List<Integer> counts) {
        PacketDistributor.sendToPlayer(player,
                new SlotCapabilityPayload(pos, state, refDir, total, dirTotals, items, counts));
    }
}
