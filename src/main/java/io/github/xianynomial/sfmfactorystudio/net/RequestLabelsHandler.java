package io.github.xianynomial.sfmfactorystudio.net;

import ca.teamdman.sfm.common.blockentity.ManagerBlockEntity;
import ca.teamdman.sfm.common.label.LabelPositionHolder;
import io.github.xianynomial.sfmfactorystudio.SFMGui;
import io.github.xianynomial.sfmfactorystudio.net.SFMGuiNetwork;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.MenuProvider;
import net.minecraft.world.SimpleMenuProvider;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.MenuType;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraftforge.network.NetworkEvent;
import net.minecraftforge.network.PacketDistributor;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.function.Supplier;

/**
 * 服务端：收集管理器磁盘的标签绑定统计（标签名 → 绑定方块数，程序引用但
 * 未绑定的计 0）并回包。回包为空也有意义：清掉客户端可能残留的其它管理器
 * 标签，避免过期建议。
 */
public final class RequestLabelsHandler {
    private RequestLabelsHandler() {
    }

    public static void handle(RequestLabelsPayload msg, Supplier<NetworkEvent.Context> ctxSup) {
        NetworkEvent.Context ctx = ctxSup.get();
        ctx.enqueueWork(() -> {
            ServerPlayer player = ctx.getSender();
            BlockEntity be = player.level().getBlockEntity(msg.pos);
            if (!(be instanceof ManagerBlockEntity manager)) return;
            ItemStack disk = manager.getDisk();
            if (disk == null || disk.isEmpty()) {
                send(player, java.util.List.of());
                return;
            }

            LinkedHashMap<String, Integer> labels = new LinkedHashMap<>();
            try {
                LabelPositionHolder.from(disk).labels().forEach(
                        (name, positions) -> labels.put(name, positions.size()));
                ca.teamdman.sfml.ast.Program program = manager.getProgram();
                if (program != null) {
                    for (String name : program.referencedLabels()) labels.putIfAbsent(name, 0);
                }
            } catch (Throwable t) {
                SFMGui.LOGGER.warn("Failed to collect labels for block editor", t);
            }
            var info = labels.entrySet().stream()
                    .sorted(java.util.Comparator
                            .comparingInt((java.util.Map.Entry<String, Integer> entry) -> entry.getValue() > 0 ? 0 : 1)
                            .thenComparing(java.util.Map.Entry::getKey))
                    .map(entry -> new UpdateLabelsPayload.LabelInfo(entry.getKey(), entry.getValue()))
                    .toList();
            send(player, info);
        });
        ctx.setPacketHandled(true);
    }

    private static void send(ServerPlayer player, List<UpdateLabelsPayload.LabelInfo> info) {
        try {
            SFMGuiNetwork.CHANNEL.send(
                    PacketDistributor.PLAYER.with(() -> player),
                    new UpdateLabelsPayload(info));
        } catch (Throwable ignored) {
        }
    }
}
