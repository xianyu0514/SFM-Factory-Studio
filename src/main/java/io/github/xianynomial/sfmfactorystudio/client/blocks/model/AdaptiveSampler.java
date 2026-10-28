package io.github.xianynomial.sfmfactorystudio.client.blocks.model;

/**
 * 自适应采样间隔：连续安静（无新锚点、无内容变化）时逐级翻倍，任何活动立即回到
 * 基准间隔。学习语义零变化——所有锚定阈值都按"采样次数"计，翻倍只拉长安静期的
 * 等待时间，界面一有动静就恢复全速采样。
 */
public final class AdaptiveSampler {
    private final int baseTicks, maxTicks;
    private int quiet;

    public AdaptiveSampler(int baseTicks, int maxTicks) {
        this.baseTicks = Math.max(1, baseTicks);
        this.maxTicks = Math.max(this.baseTicks, maxTicks);
    }

    /** 当前采样间隔（刻）：基准、2×、4×…封顶 max。 */
    public int intervalTicks() {
        // 必须用 long 移位：baseTicks >= 2 时 1<<29 / 1<<30 会溢出成负数，
        // 而 Math.min(maxTicks, 负数) 仍返回负数。调用方（SlotCalibrationManager）
        // 随后做 `% 负数`：sampleAcc 从 1 递增永不等于 0 ⇒ 采样静默停摆。
        // 触发条件只有"界面静止约 58 秒"（29 次安静采样 × 40 tick），正是
        // "开着 AE2 界面发呆一会儿，槽位编号忽然学不到"的成因。2026-10-28 修。
        long shifted = (long) baseTicks << Math.min(quiet, 30);
        return (int) Math.min(maxTicks, shifted);
    }

    public void onQuiet() {
        quiet = Math.min(quiet + 1, 30);
    }

    public void onActivity() {
        quiet = 0;
    }

    public int quietSamples() {
        return quiet;
    }
}
