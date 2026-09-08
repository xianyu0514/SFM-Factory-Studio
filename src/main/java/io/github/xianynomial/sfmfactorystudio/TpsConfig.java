package io.github.xianynomial.sfmfactorystudio;

import io.github.xianynomial.sfmfactorystudio.net.TpsBackoff;
import net.minecraftforge.common.ForgeConfigSpec;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.ModLoadingContext;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.config.ModConfig;
import net.minecraftforge.fml.event.config.ModConfigEvent;

/**
 * 服务端 TPS 选项走 Forge 标准配置（config/sfmfactorystudio-common.toml），
 * 与 1.21.1 NeoForge 版（ModConfigSpec）逐键等价。
 * 加载/重载事件把值同步进 {@link TpsBackoff} 的 volatile 静态量，
 * Mixin 热路径零查表成本，改配置即时生效无需重启。
 */
public final class TpsConfig {
    private static final ForgeConfigSpec.Builder BUILDER = new ForgeConfigSpec.Builder();

    public static final ForgeConfigSpec.BooleanValue ENABLE_IDLE_BACKOFF = BUILDER
            .comment("""
                    空转退避：管理器连续空转时把定时触发间隔逐级拉长（封顶 maxIdleBackoff 倍），搬运成功立即恢复。\
                    默认 false = 与原版 SFM 完全一致（含空闲后首件延迟）。\
                    高负载服务器想省空闲 TPS 时再开启；代价 = 空闲工厂的首件检测最多多等 (maxIdleBackoff-1) 个周期。\
                    每秒平均搬运量不变（物品累积后批量搬）。红石脉冲触发器不受影响。""")
            .translation("sfmfactorystudio.configuration.enableIdleBackoff")
            .define("enableIdleBackoff", false);

    public static final ForgeConfigSpec.IntValue MAX_IDLE_BACKOFF = BUILDER
            .comment("空转退避的最大倍数（仅 enableIdleBackoff = true 时生效）。1 = 关闭拉长。")
            .translation("sfmfactorystudio.configuration.maxIdleBackoff")
            .defineInRange("maxIdleBackoff", 4, 1, 64);

    public static final ForgeConfigSpec.DoubleValue TICK_BUDGET_MS = BUILDER
            .comment("""
                    每刻全局预算（毫秒）：所有管理器单刻总耗时封顶，超出者本轮顺延（含软公平饥饿救济）。\
                    注意：被顺延的轮次不搬运 = 动吞吐！保持 0 = 关闭（默认）；\
                    只在高负载服务器 TPS 垂死、宁可牺牲吞吐也要保住服务器时手动开启。""")
            .translation("sfmfactorystudio.configuration.tickBudgetMs")
            .defineInRange("tickBudgetMs", 0.0, 0.0, 1000.0);

    public static final ForgeConfigSpec SPEC = BUILDER.build();

    private TpsConfig() {
    }

    /** Forge 惯例：主类构造里登记配置文件（ForgeConfigSpec 注册即生效）。 */
    public static void register() {
        ModLoadingContext.get().registerConfig(ModConfig.Type.COMMON, SPEC);
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

    /** mod 事件总线：配置加载/重载后同步运行时值（改配置即时生效）。 */
    @Mod.EventBusSubscriber(modid = SFMGui.MOD_ID, bus = Mod.EventBusSubscriber.Bus.MOD)
    private static final class ConfigEvents {
        @SubscribeEvent
        public static void onLoad(ModConfigEvent.Loading event) {
            apply();
        }

        @SubscribeEvent
        public static void onReload(ModConfigEvent.Reloading event) {
            apply();
        }
    }
}
