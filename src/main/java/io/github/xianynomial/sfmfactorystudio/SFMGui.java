package io.github.xianynomial.sfmfactorystudio;

import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.common.Mod;
import net.neoforged.fml.loading.FMLEnvironment;
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
        LOGGER.info("SFM Factory Studio (SFM 智造工坊) loaded");
        // 标准配置系统：Configured 等界面模组自动生成可视化编辑页，中文键见 lang 文件
        TpsConfig.register(modEventBus, modContainer);
        // 自带中文配置界面：原版模组列表「配置」按钮即可打开，无需第三方模组
        if (FMLEnvironment.dist.isClient()) {
            io.github.xianynomial.sfmfactorystudio.client.SfmClientConfigRegistration.register(modContainer);
        }
    }
}
