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
        return Math.min(maxTicks, baseTicks << Math.min(quiet, 30));
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
