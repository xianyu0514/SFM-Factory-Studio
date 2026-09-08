package io.github.xianynomial.sfmfactorystudio.client;

import net.minecraftforge.client.ConfigScreenHandler;
import net.minecraftforge.fml.ModContainer;

/**
 * 客户端扩展点注册（保持 SFMGui 主类零客户端依赖）。
 * 注册后：原版模组列表底部「配置」按钮、Catalogue / Configured 等界面
 * 模组都会打开我们的中文 TPS 设置页。
 * 与 1.21.1 版的 IConfigScreenFactory 扩展点同语义（Forge 侧为
 * ConfigScreenHandler.ConfigScreenFactory）。
 */
public final class SfmClientConfigRegistration {
    private SfmClientConfigRegistration() {
    }

    public static void register(ModContainer container) {
        container.registerExtensionPoint(ConfigScreenHandler.ConfigScreenFactory.class,
                () -> new ConfigScreenHandler.ConfigScreenFactory(
                        (minecraft, parent) -> new TpsSettingsScreen(parent)));
    }
}
