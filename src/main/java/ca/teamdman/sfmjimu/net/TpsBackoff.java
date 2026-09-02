package ca.teamdman.sfmjimu.net;

import ca.teamdman.sfm.common.blockentity.ManagerBlockEntity;
import net.neoforged.fml.loading.FMLPaths;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Properties;
import java.util.WeakHashMap;

/**
 * 服务端空转退避（AE2 SLEEP 的 SFM 等价物）：
 * 管理器连续多次触发都没搬任何东西时，定时触发器的有效间隔逐级拉长
 * （封顶 maxBackoff 倍），把空闲轮询的成本降到接近零；
 * 任何一次成功搬运或红石脉冲立即把倍率复位为 1——活动状态下
 * **每次触发搬运量与原版完全一致**，只有"全空闲系统的下次检测时机"变慢。
 * 红石脉冲触发器不受影响（只有 Interval 走倍率）。
 *
 * 配置：config/sfmjimu/tps.properties（enableIdleBackoff / maxIdleBackoff）。
 */
public final class TpsBackoff {
    private static final WeakHashMap<ManagerBlockEntity, Integer> EMPTY_STREAK = new WeakHashMap<>();
    private static volatile boolean enabled = true;
    private static volatile int maxBackoff = 8;
    private static boolean configLoaded = false;

    private TpsBackoff() {
    }

    private static void loadConfig() {
        if (configLoaded) return;
        configLoaded = true;
        try {
            Path dir = FMLPaths.CONFIGDIR.get().resolve("sfmjimu");
            Files.createDirectories(dir);
            Path file = dir.resolve("tps.properties");
            if (!Files.exists(file)) {
                Files.writeString(file, """
                        # 空转退避：管理器连续空转时定时触发间隔逐级拉长（搬运成功立即恢复）
                        enableIdleBackoff=true
                        # 最大退避倍数（8=空闲时最多每 8 倍间隔检测一次）
                        maxIdleBackoff=8
                        """);
            }
            Properties props = new Properties();
            props.load(Files.newInputStream(file));
            enabled = Boolean.parseBoolean(props.getProperty("enableIdleBackoff", "true"));
            maxBackoff = Math.max(1, Math.min(64, Integer.parseInt(props.getProperty("maxIdleBackoff", "8"))));
        } catch (IOException | RuntimeException ignored) {
            // 读不到配置按默认值跑
        }
    }

    /** 由 ManagerTickMixin 在每次程序运行结束后回调。 */
    public static void onProgramRan(ManagerBlockEntity manager, boolean didSomething) {
        if (didSomething) {
            EMPTY_STREAK.remove(manager);
        } else {
            EMPTY_STREAK.merge(manager, 1, Integer::sum);
        }
    }

    /** 当前有效倍率：空转 4 轮后从 1 起，每再空转一轮 ×1.5，封顶 maxBackoff。 */
    public static int multiplierFor(ManagerBlockEntity manager) {
        loadConfig();
        if (!enabled) return 1;
        Integer streak = EMPTY_STREAK.get(manager);
        if (streak == null || streak < 4) return 1;
        long mult = 1;
        for (int i = 4; i < streak; i++) {
            mult = (long) (mult * 1.5);
            if (mult >= maxBackoff) return maxBackoff;
        }
        return (int) Math.min(mult, maxBackoff);
    }

    /** 诊断用：当前空转轮数。 */
    public static int streakOf(ManagerBlockEntity manager) {
        Integer streak = EMPTY_STREAK.get(manager);
        return streak == null ? 0 : streak;
    }
}
