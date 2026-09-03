package ca.teamdman.sfmjimu.client;

import net.neoforged.fml.ModContainer;
import net.neoforged.neoforge.client.gui.IConfigScreenFactory;

/**
 * 客户端扩展点注册（保持 SFMGui 主类零客户端依赖）。
 * 注册后：原版模组列表底部「配置」按钮、Catalogue / Configured 等界面
 * 模组都会打开我们的中文 TPS 设置页。
 */
public final class SfmClientConfigRegistration {
    private SfmClientConfigRegistration() {
    }

    public static void register(ModContainer container) {
        container.registerExtensionPoint(IConfigScreenFactory.class,
                (modContainer, parentScreen) -> new TpsSettingsScreen(parentScreen));
    }
}
