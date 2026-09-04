package io.github.xianynomial.sfmfactorystudio.client.blocks.model;

import java.util.List;

/**
 * 程序执行成本的静态估算——让玩家"看见"自己程序的 TPS 代价。
 *
 * <p>成本模型（与 SFM 4.34 执行路径对齐的粗略近似）：
 * 每次触发 ≈ Σ(每个绑定的标签方块数 × 扫描系数)；每秒成本 = 每次触发 ÷ 间隔秒。
 * 扫描系数：无资源种类限制（input *）= 全部槽位试探，最贵（4）；
 * 指定了具体资源 = 可提前跳过不匹配槽位（1）；纯能量 IO 走独立轻路径（0.25）。
 *
 * <p>等级：绿（低）/ 黄（建议优化）/ 红（高负载元凶）。阈值按"单卡每秒
 * 等效槽位试探数"划分：≤64 绿、≤512 黄、>512 红。
 *
 * <p>标签方块数是估算值：未绑定/离线时按已知计数的 8 折保守估计，
 * 完全未知时按 4 个计（多不坑少）。
 */
public final class ProgramCost {
    private ProgramCost() {
    }

    /** 单卡成本快照。 */
    public record Cost(int score, String detail) {
        public enum Level {LOW, MEDIUM, HIGH}

        public Level level() {
            if (score <= 64) return Level.LOW;
            if (score <= 512) return Level.MEDIUM;
            return Level.HIGH;
        }
    }

    /** 标签 → 已知绑定方块数；未知标签用 fallback。可为 null（全部按 fallback 计）。 */
    public interface LabelSizeLookup {
        int sizeOf(String label);
    }

    public static Cost of(BProgram.Trigger trigger, LabelSizeLookup lookup) {
        if (trigger instanceof BProgram.PulseTrigger) {
            // 红石触发只在信号沿执行一次，静息成本为零——不给黄红牌
            return new Cost(0, "红石脉冲触发：只在收到信号时执行一次");
        }
        BProgram.TimerTrigger tt = (BProgram.TimerTrigger) trigger;
        long intervalTicks = Math.max(TimerRules.minimumCount(tt), tt.count);

        double perRun = 0;
        int blocks = 0;
        boolean wildcard = false;
        boolean energy = false;
        for (BProgram.Statement s : tt.body) {
            List<BProgram.ResourceLimit> limitGroups;
            List<String> labels;
            if (s instanceof BProgram.Statement.Input in) {
                limitGroups = in.limits;
                labels = in.access.labels;
            } else if (s instanceof BProgram.Statement.Output out) {
                limitGroups = out.limits;
                labels = out.access.labels;
            } else {
                continue;
            }
            int labelBlocks = 0;
            for (String label : labels) {
                labelBlocks += lookup == null ? 4 : Math.max(1, lookup.sizeOf(label));
            }
            blocks += labelBlocks;
            for (BProgram.ResourceLimit rl : limitGroups) {
                boolean noKind = rl.resources.isEmpty()
                        || rl.resources.stream().anyMatch(r -> r != null && r.isWildcard());
                boolean onlyEnergy = !rl.resources.isEmpty() && rl.resources.stream()
                        .allMatch(r -> r != null && "forge_energy".equals(r.typeName));
                if (onlyEnergy) {
                    perRun += labelBlocks * 0.25;
                    energy = true;
                } else if (noKind) {
                    perRun += labelBlocks * 4.0;
                    wildcard = true;
                } else {
                    perRun += labelBlocks * 1.0;
                }
            }
        }
        double perSecond = perRun * 20.0 / intervalTicks;
        int score = (int) Math.min(Integer.MAX_VALUE / 2, perSecond);

        StringBuilder detail = new StringBuilder();
        detail.append("绑定约 ").append(blocks).append(" 个方块 × 每 ")
                .append(intervalTicks).append(" 刻（")
                .append(String.format(java.util.Locale.ROOT, "%.1f", intervalTicks / 20.0)).append("秒）×")
                .append(wildcard ? " 全部槽位扫描" : energy ? " 纯能量轻路径" : " 定向资源");
        detail.append(" ≈ 每秒 ").append(score).append(" 次等效试探");
        if (wildcard && intervalTicks < 20) {
            detail.append("（高频+全扫：建议加资源标签或拉长间隔）");
        }
        return new Cost(score, detail.toString());
    }
}
