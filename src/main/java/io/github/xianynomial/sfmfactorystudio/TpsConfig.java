package io.github.xianynomial.sfmfactorystudio;

import io.github.xianynomial.sfmfactorystudio.net.TpsBackoff;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.config.ModConfig;
import net.neoforged.neoforge.common.ModConfigSpec;
import net.neoforged.fml.event.config.ModConfigEvent;

/**
 * 服务端 TPS 选项走 NeoForge 标准配置（config/sfmfactorystudio-common.toml），
 * 让 Configured 等配置界面模组自动生成可视化编辑页；语言键见 assets lang。
 * 加载/重载事件把值同步进 {@link TpsBackoff} 的 volatile 静态量，
 * Mixin 热路径零查表成本，改配置即时生效无需重启。
 */
public final class TpsConfig {
    private static final ModConfigSpec.Builder BUILDER = new ModConfigSpec.Builder();

    public static final ModConfigSpec.BooleanValue ENABLE_IDLE_BACKOFF = BUILDER
            .comment("""
                    空转退避：管理器连续空转时把定时触发间隔逐级拉长（封顶 maxIdleBackoff 倍），搬运成功立即恢复。\
                    默认 false = 与原版 SFM 完全一致（含空闲后首件延迟）。\
                    高负载服务器想省空闲 TPS 时再开启；代价 = 空闲工厂的首件检测最多多等 (maxIdleBackoff-1) 个周期。\
                    每秒平均搬运量不变（物品累积后批量搬）。红石脉冲触发器不受影响。""")
            .translation("sfmfactorystudio.configuration.enableIdleBackoff")
            .define("enableIdleBackoff", false);

    public static final ModConfigSpec.IntValue MAX_IDLE_BACKOFF = BUILDER
            .comment("空转退避的最大倍数（仅 enableIdleBackoff = true 时生效）。1 = 关闭拉长。")
            .translation("sfmfactorystudio.configuration.maxIdleBackoff")
            .defineInRange("maxIdleBackoff", 4, 1, 64);

    public static final ModConfigSpec.DoubleValue TICK_BUDGET_MS = BUILDER
            .comment("""
                    每刻全局预算（毫秒）：所有管理器单刻总耗时封顶，超出者本轮顺延（含软公平饥饿救济）。\
                    注意：被顺延的轮次不搬运 = 动吞吐！保持 0 = 关闭（默认）；\
                    只在高负载服务器 TPS 垂死、宁可牺牲吞吐也要保住服务器时手动开启。""")
            .translation("sfmfactorystudio.configuration.tickBudgetMs")
            .defineInRange("tickBudgetMs", 0.0, 0.0, 1000.0);

    public static final ModConfigSpec SPEC = BUILDER.build();

    private TpsConfig() {
    }

    public static void register(IEventBus modEventBus, ModContainer container) {
        container.registerConfig(ModConfig.Type.COMMON, SPEC);
        modEventBus.addListener((ModConfigEvent.Loading e) -> apply());
        modEventBus.addListener((ModConfigEvent.Reloading e) -> apply());
    }

    private static void apply() {
        try {
            TpsBackoff.updateFromConfig(
                    ENABLE_IDLE_BACKOFF.get(),
                    MAX_IDLE_BACKOFF.get(),
                    TICK_BUDGET_MS.get());
        } catch (Throwable t) {
            // 配置未就绪按默认值跑（默认=全关，与原版一致）
            SFMGui.LOGGER.warn("TPS 配置读取失败，按默认值（全关）运行: {}", t.toString());
        }
    }
}
