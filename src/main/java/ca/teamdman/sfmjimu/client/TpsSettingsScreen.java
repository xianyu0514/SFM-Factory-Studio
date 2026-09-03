package ca.teamdman.sfmjimu.client;

import ca.teamdman.sfmjimu.TpsConfig;
import ca.teamdman.sfmjimu.net.TpsBackoff;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.AbstractSliderButton;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

/**
 * 服务端 TPS 设置的中文配置界面（原版部件，无第三方依赖）。
 * 注册到 NeoForge 的 IConfigScreenFactory 后：模组列表底部「配置」按钮、
 * Catalogue / Configured 等界面模组都会打开它。保存即写回标准 TOML
 * 并同步 TpsBackoff，即时生效无需重启。
 */
public final class TpsSettingsScreen extends Screen {
    private static final int COL_LABEL = 0xFFFFFFFF;
    private static final int COL_DESC = 0xFFB0B0B0;
    private static final int COL_WARN = 0xFFE0A040;

    private final Screen parent;
    private boolean backoff;
    private int maxMult;
    private double budgetMs;

    public TpsSettingsScreen(Screen parent) {
        super(Component.literal("SFM 智造工坊 · 服务端 TPS 设置"));
        this.parent = parent;
        // 起始值 = 当前生效值（配置界面改完保存后再次打开能看到）
        this.backoff = TpsBackoff.isEnabled();
        this.maxMult = TpsBackoff.maxMultiplier();
        this.budgetMs = TpsBackoff.budgetMillis();
    }

    @Override
    protected void init() {
        int cx = this.width / 2;
        int bw = 240;
        int y = Math.max(56, this.height / 2 - 96);

        addRenderableWidget(Button.builder(toggleLabel(), b -> {
            backoff = !backoff;
            b.setMessage(toggleLabel());
        }).bounds(cx - bw / 2, y, bw, 20).build());
        y += 30;

        addRenderableWidget(new IntSlider(cx - bw / 2, y, bw, 1, 64, maxMult, v -> {
            maxMult = v;
            return "空转退避上限：" + v + " 倍（空闲后首件最多多等 " + (v - 1) + " 个周期）";
        }));
        y += 30;

        addRenderableWidget(new IntSlider(cx - bw / 2, y, bw, 0, 1000, (int) Math.round(budgetMs), v -> {
            budgetMs = v;
            return v == 0 ? "每刻全局预算：0 毫秒（关闭 · 默认）" : "每刻全局预算：" + v + " 毫秒";
        }));
        y += 46;

        addRenderableWidget(Button.builder(Component.literal("保存并关闭"), b -> save())
                .bounds(cx - 154, y, 100, 20).build());
        addRenderableWidget(Button.builder(Component.literal("恢复默认"), b -> {
            backoff = false;
            maxMult = 4;
            budgetMs = 0;
            rebuildWidgets();
        }).bounds(cx - 50, y, 100, 20).build());
        addRenderableWidget(Button.builder(Component.literal("取消"), b -> onClose())
                .bounds(cx + 54, y, 100, 20).build());
    }

    private Component toggleLabel() {
        return Component.literal(backoff
                ? "空转退避：开（空闲工厂省 TPS）"
                : "空转退避：关（默认 · 与原版行为完全一致）");
    }

    private void save() {
        try {
            TpsConfig.ENABLE_IDLE_BACKOFF.set(backoff);
            TpsConfig.MAX_IDLE_BACKOFF.set(maxMult);
            TpsConfig.TICK_BUDGET_MS.set(budgetMs);
            TpsConfig.SPEC.save();
        } catch (Throwable ignored) {
            // 配置写入失败也直接同步运行时值，下个启动再落盘
        }
        TpsBackoff.updateFromConfig(backoff, maxMult, budgetMs);
        onClose();
    }

    @Override
    public void renderBackground(GuiGraphics g, int mx, int my, float partialTick) {
        g.fillGradient(0, 0, this.width, this.height, 0xC0101010, 0xD0101010);
    }

    @Override
    public void render(GuiGraphics g, int mx, int my, float partialTick) {
        renderBackground(g, mx, my, partialTick);
        int cx = this.width / 2;
        int y = Math.max(56, this.height / 2 - 96);
        g.drawCenteredString(this.font, this.title, cx, y - 24, COL_LABEL);
        g.drawCenteredString(this.font, "连续空转的管理器把定时检测间隔逐级拉长，搬运成功立即恢复；每秒平均搬运量不变，红石触发器不受影响",
                cx, y + 22, COL_DESC);
        g.drawCenteredString(this.font, "仅在开启空转退避时生效；数值越大空闲时越省，首件延迟也越长",
                cx, y + 52, COL_DESC);
        g.drawCenteredString(this.font, "超出预算的触发本轮顺延（不搬运 = 损失吞吐！），仅高负载服保 TPS 时开启",
                cx, y + 82, COL_WARN);
        g.drawCenteredString(this.font, "默认全部关闭 = 与原版 SFM 行为完全一致（吞吐与首件延迟都不变）",
                cx, this.height - 44, COL_DESC);
        super.render(g, mx, my, partialTick);
    }

    @Override
    public void onClose() {
        if (this.minecraft != null) this.minecraft.setScreen(parent);
    }

    /** 整数刻度滑条：标签文案由取值回调即时生成。 */
    private static final class IntSlider extends AbstractSliderButton {
        private final int min, max;
        private final java.util.function.IntFunction<String> describe;

        IntSlider(int x, int y, int w, int min, int max, int initial,
                  java.util.function.IntFunction<String> describe) {
            super(x, y, w, 20, Component.empty(), (initial - min) / (double) (max - min));
            this.min = min;
            this.max = max;
            this.describe = describe;
            updateMessage();
        }

        private int current() {
            return min + (int) Math.round(this.value * (max - min));
        }

        @Override
        protected void updateMessage() {
            setMessage(Component.literal(describe.apply(current())));
        }

        @Override
        protected void applyValue() {
            updateMessage();
        }
    }
}
