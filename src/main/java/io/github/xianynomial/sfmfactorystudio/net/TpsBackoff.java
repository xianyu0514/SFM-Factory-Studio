package io.github.xianynomial.sfmfactorystudio.net;

import ca.teamdman.sfm.common.blockentity.ManagerBlockEntity;

import java.util.WeakHashMap;

/**
 * 服务端空转退避（AE2 SLEEP 的 SFM 等价物）：
 * 管理器连续多次触发都没搬任何东西时，定时触发器的有效间隔逐级拉长
 * （封顶 maxBackoff 倍），把空闲轮询的成本降到接近零；
 * 任何一次成功搬运或红石脉冲立即把倍率复位为 1——活动状态下
 * **每次触发搬运量与原版完全一致**，只有"全空闲系统的下次检测时机"变慢。
 * 红石脉冲触发器不受影响（只有 Interval 走倍率）。
 *
 * **默认关闭**（enableIdleBackoff=false）：开启后空闲工厂的首件检测最多
 * 推迟 (maxBackoff-1) 个周期，与原版存在可感知差异；默认状态下行为与
 * 原版 SFM 完全一致，仅在需要空闲 TPS 的服务器手动开启。
 *
 * 配置走 NeoForge 标准 TOML（见 {@link io.github.xianynomial.sfmfactorystudio.TpsConfig}），
 * Configured 等配置界面可直接可视化编辑，保存/重载即时生效。
 */
public final class TpsBackoff {
    private static final WeakHashMap<ManagerBlockEntity, Integer> EMPTY_STREAK = new WeakHashMap<>();
    private static volatile boolean enabled = false; // 默认与原版完全一致（含首件延迟）
    private static volatile int maxBackoff = 4; // 吞吐中性（累积批量搬），仅影响空闲后首件延迟
    private static volatile double tickBudgetMs = 0; // 默认关闭：预算会丢轮次=动吞吐，玩家在意效率
    private static long accNanos = 0;
    private static long accGameTime = -1;
    private static final WeakHashMap<ManagerBlockEntity, Integer> STARVE = new WeakHashMap<>();

    /** 由 TpsConfig 在配置加载/重载时同步（热路径只读 volatile，零查表成本）。 */
    public static void updateFromConfig(boolean enableIdleBackoff, int maxIdleBackoff, double budgetMs) {
        enabled = enableIdleBackoff;
        maxBackoff = Math.max(1, Math.min(64, maxIdleBackoff));
        tickBudgetMs = Math.max(0, budgetMs);
    }

    /** 当前生效值（配置界面的起始值）。 */
    public static boolean isEnabled() {
        return enabled;
    }

    public static int maxMultiplier() {
        return maxBackoff;
    }

    public static double budgetMillis() {
        return tickBudgetMs;
    }

    /** 程序运行前：预算未超直接放行；超了看饥饿度（软公平，连续被跳的优先放行）。 */
    public static boolean tryAcquire(ManagerBlockEntity manager) {
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

    /** 由 ManagerTickMixin 在每次程序运行结束后回调。 */
    public static void onProgramRan(ManagerBlockEntity manager, boolean didSomething) {
        if (!enabled) return; // 关闭时连空转计数都不记，运行路径与原版零差异
        if (didSomething) {
            EMPTY_STREAK.remove(manager);
        } else {
            EMPTY_STREAK.merge(manager, 1, Integer::sum);
        }
    }

    /** 当前有效倍率：空转 4 轮后从 1 起，每再空转一轮 ×1.5，封顶 maxBackoff。 */
    public static int multiplierFor(ManagerBlockEntity manager) {
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
