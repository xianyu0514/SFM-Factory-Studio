package io.github.xianynomial.sfmfactorystudio.net;

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

    public static void handle(RequestLabelsPayload msg, IPayloadContext ctx) {
        ctx.enqueueWork(() -> {
            if (!(ctx.player() instanceof ServerPlayer player)) return;
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
