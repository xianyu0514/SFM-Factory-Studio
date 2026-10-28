package io.github.xianynomial.sfmfactorystudio.net;

import io.github.xianynomial.sfmfactorystudio.client.Loc;

import ca.teamdman.sfm.common.blockentity.ManagerBlockEntity;
import ca.teamdman.sfm.common.label.LabelPositionHolder;
import ca.teamdman.sfml.ast.Program;
import io.github.xianynomial.sfmfactorystudio.SFMGui;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.neoforged.neoforge.network.handling.IPayloadContext;
import net.neoforged.neoforge.network.PacketDistributor;

import java.util.LinkedHashMap;

/**
 * Server-side: collect label names from the manager's disk (bound positions plus
 * names referenced by its compiled program) and push them to the client.
 */
public final class RequestLabelsHandler {
    private RequestLabelsHandler() {
    }

    /**
     * 允许的最大距离（平方，单位=格）。{@code msg.pos()} 完全来自客户端：
     * 不设限的话，任意坐标都能让服务端去读已加载区块里任意管理器磁盘上的标签
     * （越权读取），而且是无成本的高频触发。
     *
     * <p>为什么是 64：正常路径下玩家必须先右键打开管理器界面（原版交互距离只有
     * 4~6 格），余量约十倍，正常使用碰不到。与
     * {@link SlotCalibrationManager} / {@link SlotCapabilityRequestHandler}
     * 的 {@code MAX_DISTANCE_SQR} 保持同一标准（三处语义相同，改一处要三处同改）。
     */
    private static final double MAX_DISTANCE_SQR = 64 * 64;

    public static void handle(RequestLabelsPayload msg, IPayloadContext ctx) {
        ctx.enqueueWork(() -> {
            if (!(ctx.player() instanceof ServerPlayer player)) return;
            if (player.distanceToSqr(
                    msg.pos().getX() + 0.5, msg.pos().getY() + 0.5, msg.pos().getZ() + 0.5) > MAX_DISTANCE_SQR) {
                // 静默丢弃：越权请求不该得到任何可区分的回包
                return;
            }
            BlockEntity be = player.level().getBlockEntity(msg.pos());
            if (!(be instanceof ManagerBlockEntity manager)) return;
            ItemStack disk = manager.getDisk();
            if (disk == null || disk.isEmpty()) {
                PacketDistributor.sendToPlayer(player, new UpdateLabelsPayload(java.util.List.of()));
                return;
            }

            LinkedHashMap<String, Integer> labels = new LinkedHashMap<>();
            try {
                LabelPositionHolder.from(disk).labels().forEach(
                        (name, positions) -> labels.put(name, positions.size()));
                Program program = manager.getProgram();
                if (program != null) {
                    for (String name : program.referencedLabels()) labels.putIfAbsent(name, 0);
                }
            } catch (Throwable t) {
                SFMGui.LOGGER.warn("Failed to collect labels for block editor", t);
            }
            // Empty is also meaningful: it clears labels cached from a different
            // manager instead of leaving stale suggestions in the editor.
            var info = labels.entrySet().stream()
                    .sorted(java.util.Comparator
                            .comparingInt((java.util.Map.Entry<String, Integer> entry) -> entry.getValue() > 0 ? 0 : 1)
                            .thenComparing(java.util.Map.Entry::getKey))
                    .map(entry -> new UpdateLabelsPayload.LabelInfo(entry.getKey(), entry.getValue()))
                    .toList();
            PacketDistributor.sendToPlayer(player, new UpdateLabelsPayload(info));
        });
    }
}
