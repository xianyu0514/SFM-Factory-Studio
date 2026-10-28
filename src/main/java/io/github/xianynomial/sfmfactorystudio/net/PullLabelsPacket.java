package io.github.xianynomial.sfmfactorystudio.net;

import ca.teamdman.sfm.common.blockentity.ManagerBlockEntity;
import ca.teamdman.sfm.common.item.LabelGunItem;
import ca.teamdman.sfm.common.label.LabelPositionHolder;
import io.github.xianynomial.sfmfactorystudio.SFMGui;
import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraftforge.network.NetworkEvent;

import java.util.LinkedHashSet;
import java.util.function.Supplier;

/**
 * Serverbound message (Forge 1.20.1 {@code SimpleChannel}): request that the SFM
 * manager at {@code pos} copy the labels stored on its inserted disk onto the
 * label gun in the requesting player's inventory.
 * <p>
 * The channel is optional — servers without this addon simply never answer, and
 * the editor treats the button as best-effort.
 */
public record PullLabelsPacket(BlockPos pos) {
    /**
     * 允许的最大距离（平方，单位=格）。{@code pos} 完全来自客户端，而本 handler
     * 除了读磁盘还会把标签<b>写进玩家自己的标签枪</b>——不设限就等于允许从任意
     * 距离"复制"别人的标签配置，绕过原版 4~6 格交互距离。
     * 64 格与 1.21.1 / 26.1.2 的同名常量同一标准。
     */
    private static final double MAX_DISTANCE_SQR = 64 * 64;

    /**
     * 每玩家最小重入间隔（毫秒）。本 handler 在主线程对磁盘程序做一次
     * {@code ProgramBuilder(...).useCache(false)} 编译（强制不吃缓存），是明确的
     * CPU 开销；循环发包即可让主线程持续编译。按钮是人工点击，250ms 对正常使用
     * 零影响，但能挡住这类滥用。
     */
    private static final long MIN_INTERVAL_MS = 250;
    /** 限流表容量上限：超了整体清空，只丢限流状态、不影响任何功能。 */
    private static final int MAX_TRACKED_PLAYERS = 512;
    private static final java.util.Map<java.util.UUID, Long> LAST_CALL_MS =
            new java.util.concurrent.ConcurrentHashMap<>();

    /** @return true 表示本次调用应被丢弃（距上次太近）。 */
    private static boolean throttled(java.util.UUID id) {
        long now = System.currentTimeMillis();
        Long previous = LAST_CALL_MS.get(id);
        if (previous != null && now - previous < MIN_INTERVAL_MS) return true;
        if (LAST_CALL_MS.size() > MAX_TRACKED_PLAYERS) LAST_CALL_MS.clear();
        LAST_CALL_MS.put(id, now);
        return false;
    }

    public static void encode(PullLabelsPacket msg, FriendlyByteBuf buf) {
        buf.writeBlockPos(msg.pos());
    }

    public static PullLabelsPacket decode(FriendlyByteBuf buf) {
        return new PullLabelsPacket(buf.readBlockPos());
    }

    public static void handle(PullLabelsPacket msg, Supplier<NetworkEvent.Context> ctx) {
        ctx.get().enqueueWork(() -> {
            ServerPlayer player = ctx.get().getSender();
            if (player == null) {
                return;
            }
            if (player.distanceToSqr(
                    msg.pos().getX() + 0.5, msg.pos().getY() + 0.5, msg.pos().getZ() + 0.5) > MAX_DISTANCE_SQR) {
                // 静默丢弃：越权请求不该得到任何可区分的回包
                return;
            }
            if (throttled(player.getUUID())) return;
            BlockEntity be = player.level().getBlockEntity(msg.pos());
            if (!(be instanceof ManagerBlockEntity manager)) {
                feedback(player, "gui.sfmfactorystudio.pull_labels.no_manager", ChatFormatting.RED);
                return;
            }
            ItemStack disk = manager.getDisk();
            if (disk == null || disk.isEmpty()) {
                feedback(player, "gui.sfmfactorystudio.pull_labels.no_disk", ChatFormatting.RED);
                return;
            }
            // Collect the label NAMES referenced by the program on the disk, plus any
            // names already bound on the disk's holder. We push names (not positions)
            // so the player can then bind them to blocks with the label gun.
            LinkedHashSet<String> names = collectLabelNames(disk);
            if (names == null) {
                // Non-blank program that failed to compile.
                feedback(player, "gui.sfmfactorystudio.pull_labels.compile_failed", ChatFormatting.RED);
                return;
            }
            SFMGui.LOGGER.debug("PullLabels: {} label names from disk: {}", names.size(), names);
            if (names.isEmpty()) {
                feedback(player, "gui.sfmfactorystudio.pull_labels.empty", ChatFormatting.YELLOW);
                return;
            }
            ItemStack gun = findLabelGun(player);
            if (gun == null) {
                feedback(player, "gui.sfmfactorystudio.pull_labels.no_gun", ChatFormatting.RED);
                return;
            }
            LabelPositionHolder gunLabels = LabelPositionHolder.from(gun);
            for (String name : names) {
                gunLabels.addReferencedLabel(name);
            }
            gunLabels.save(gun);
            feedback(player, "gui.sfmfactorystudio.pull_labels.success", ChatFormatting.GREEN, names.size());
        });
        ctx.get().setPacketHandled(true);
    }

    /**
     * Collects the label names to push onto the gun from the compiled program's
     * {@code referencedLabels()}, unioned with any names already bound on the
     * disk's own holder. Returns {@code null} when the disk has a non-blank
     * program that fails to compile, so the caller can tell the player to fix it
     * first rather than silently pushing nothing.
     */
    private static LinkedHashSet<String> collectLabelNames(ItemStack disk) {
        LinkedHashSet<String> names = new LinkedHashSet<>();
        // Names already bound on the disk's own holder (if any).
        try {
            LabelPositionHolder.from(disk).labels().keySet().forEach(names::add);
        } catch (Exception ignored) {
        }
        String program = "";
        try {
            program = ca.teamdman.sfm.common.item.DiskItem.getProgramString(disk);
        } catch (Exception ignored) {
        }
        if (program == null || program.isBlank()) {
            return names;
        }
        try {
            var result = new ca.teamdman.sfml.program_builder.ProgramBuilder(program)
                    .useCache(false).build();
            if (result.program() != null) {
                names.addAll(result.program().referencedLabels());
                return names;
            }
        } catch (Exception e) {
            SFMGui.LOGGER.debug("PullLabels: program compile threw", e);
        }
        // Non-blank program that failed to compile: signal the caller.
        return null;
    }

    private static ItemStack findLabelGun(ServerPlayer player) {
        var inv = player.getInventory();
        // prefer the currently held item, else first gun in inventory
        ItemStack main = player.getMainHandItem();
        if (main.getItem() instanceof LabelGunItem) {
            return main;
        }
        ItemStack off = player.getOffhandItem();
        if (off.getItem() instanceof LabelGunItem) {
            return off;
        }
        for (int i = 0; i < inv.getContainerSize(); i++) {
            ItemStack s = inv.getItem(i);
            if (s.getItem() instanceof LabelGunItem) {
                return s;
            }
        }
        return null;
    }

    private static void feedback(ServerPlayer player, String key, ChatFormatting color, Object... args) {
        player.displayClientMessage(Component.translatable(key, args).withStyle(color), false);
        SFMGui.LOGGER.debug("PullLabels: {} for {}", key, player.getGameProfile().getName());
    }
}
