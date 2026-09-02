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
    private static volatile int maxBackoff = 4; // 吞吐中性（累积批量搬），仅影响空闲后首件延迟
    private static boolean configLoaded = false;

    private TpsBackoff() {
    }

    // ---- 每刻全局预算（多工厂保险丝）：SFM 上游只有耗时测量，没有预算机制 ----
    private static volatile double tickBudgetMs = 0; // 默认关闭：预算会丢轮次=动吞吐，玩家在意效率
    private static long accNanos = 0;
    private static long accGameTime = -1;
    private static final WeakHashMap<ManagerBlockEntity, Integer> STARVE = new WeakHashMap<>();

    /** 程序运行前：预算未超直接放行；超了看饥饿度（软公平，连续被跳的优先放行）。 */
    public static boolean tryAcquire(ManagerBlockEntity manager) {
        loadConfig();
        long budgetNanos = (long) (tickBudgetMs * 1_000_000);
        if (budgetNanos <= 0) return true; // 0=关闭
        long gameTime = manager.getLevel() != null ? manager.getLevel().getGameTime() : 0;
        if (gameTime != accGameTime) {
            accGameTime = gameTime;
            accNanos = 0;
        }
        if (accNanos < budgetNanos) return true;
        Integer starve = STARVE.get(manager);
        int s = starve == null ? 0 : starve;
        if (s >= 3 && accNanos < budgetNanos * 3 / 2) {
            STARVE.remove(manager); // 饥饿救济：放行但不再加重超支
            return true;
        }
        STARVE.merge(manager, 1, Integer::sum);
        return false;
    }

    /** 程序运行后：累计本刻耗时。 */
    public static void record(long nanos) {
        accNanos += nanos;
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
                        # 最大退避倍数（吞吐中性：物品累积后批量搬，每秒平均量不变；只影响空闲后首件延迟）
                        maxIdleBackoff=4
                        # 每刻全局预算（毫秒）：所有管理器单刻总耗时封顶，超出者丢本轮触发（=动吞吐！）
                        # 玩家在意吞吐请保持 0=关闭；只在高负载服务器想保 TPS 时手动开启
                        tickBudgetMs=0
                        """);
            }
            Properties props = new Properties();
            props.load(Files.newInputStream(file));
            enabled = Boolean.parseBoolean(props.getProperty("enableIdleBackoff", "true"));
            maxBackoff = Math.max(1, Math.min(64, Integer.parseInt(props.getProperty("maxIdleBackoff", "4"))));
            tickBudgetMs = Math.max(0, Double.parseDouble(props.getProperty("tickBudgetMs", "0")));
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
