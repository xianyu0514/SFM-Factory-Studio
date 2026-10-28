package io.github.xianynomial.sfmfactorystudio;

import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.common.Mod;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * SFM Factory Studio — a Chinese visual programming companion for Super Factory
 * Manager with synchronized block and source editing.
 * <p>
 * The addon never modifies SFM. It hooks into SFM's manager screen via a client
 * event and reuses SFM's public APIs (program string, packets, DSL parser) to
 * read and save programs.
 */
@Mod(SFMGui.MOD_ID)
public class SFMGui {
    public static final String MOD_ID = "sfmfactorystudio";
    public static final Logger LOGGER = LoggerFactory.getLogger("SFMGui");

    public SFMGui(IEventBus modEventBus, ModContainer modContainer) {
        // 构建标识：改 UI 后更新这里，用户看日志就能确认跑的是不是最新包
        LOGGER.info("SFM Factory Studio loaded (mod id {}, build note-center-2)", MOD_ID);
        // 迁移自检：三个 mixin 都是 require=0，注入失败不报错也不崩，只会静默退回原版行为。
        // 加 -Dsfmfactorystudio.verifyMixins=true 启动时主动触发三个目标类的加载，
        // 日志里应出现三条 "Mixing <Xxx>Mixin from sfmfactorystudio.mixins.json into <目标类>"。
        // 平时不执行（系统属性未开），零副作用。
        if (Boolean.getBoolean("sfmfactorystudio.verifyMixins")) {
            verifyMixins();
        }
        // 标准配置系统：Configured 等界面模组自动生成可视化编辑页，中文键见 lang 文件
        TpsConfig.register(modEventBus, modContainer);
        // 自带中文配置界面：原版模组列表「配置」按钮即可打开，无需第三方模组
        // 26.1 起 FMLEnvironment.dist 字段被移除（只剩 getDist()），这里改成不依赖
        // FML 内部形态的判端：Minecraft 类只在物理客户端存在。
        if (isPhysicalClient()) {
            io.github.xianynomial.sfmfactorystudio.client.SfmClientConfigRegistration.register(modContainer);
        }
    }

    /** 主动加载三个 mixin 目标类，让 Mixin 在类加载时应用并打出日志。 */
    private static void verifyMixins() {
        String[] targets = {
                "ca.teamdman.sfml.ast.Interval",                      // IntervalMixin
                "ca.teamdman.sfml.ast.WithTag",                       // WithTagMixin
                "ca.teamdman.sfm.common.blockentity.ManagerBlockEntity" // ManagerTickMixin
        };
        for (String t : targets) {
            try {
                // initialize=false：只触发类加载（mixin 在这一步生效），不跑静态初始化
                Class.forName(t, false, SFMGui.class.getClassLoader());
                LOGGER.info("[mixin-verify] loaded {}", t);
            } catch (Throwable e) {
                LOGGER.error("[mixin-verify] failed to load {}", t, e);
            }
        }
    }

    /** 物理客户端判定：客户端专属类能否加载（不引用 FMLEnvironment，跨 FML 版本通用）。 */
    public static boolean isPhysicalClient() {
        try {
            Class.forName("net.minecraft.client.Minecraft", false, SFMGui.class.getClassLoader());
            return true;
        } catch (ClassNotFoundException | NoClassDefFoundError e) {
            return false;
        }
    }
}
