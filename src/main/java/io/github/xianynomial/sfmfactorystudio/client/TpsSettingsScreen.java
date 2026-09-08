package io.github.xianynomial.sfmfactorystudio.client;

import io.github.xianynomial.sfmfactorystudio.TpsConfig;
import io.github.xianynomial.sfmfactorystudio.net.TpsBackoff;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.AbstractSliderButton;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

/**
 * 服务端 TPS 设置的配置界面（原版部件，无第三方依赖）。
 * 注册到 Forge 的 ConfigScreenHandler 扩展点后：模组列表底部「配置」按钮、
 * Catalogue / Configured 等界面模组都会打开它。保存即写回标准 TOML
 * 并同步 TpsBackoff，即时生效无需重启。
 * 与 1.21.1 版逐行等价（仅 renderBackground 签名随 1.20.1 原版 API 调整）。
 */
public final class TpsSettingsScreen extends Screen {
    private static final Loc TITLE = new Loc("gui.sfmfactorystudio.tps.title", "SFM 智造工坊 · 服务端 TPS 设置");
    private static final Loc BACKOFF_ON = new Loc("gui.sfmfactorystudio.tps.backoff_on", "空转退避：开（空闲工厂省 TPS）");
    private static final Loc BACKOFF_OFF = new Loc("gui.sfmfactorystudio.tps.backoff_off", "空转退避：关（默认 · 与原版行为完全一致）");
    private static final Loc BACKOFF_MAX = new Loc("gui.sfmfactorystudio.tps.backoff_max", "空转退避上限：%s 倍（空闲后首件最多多等 %s 个周期）");
    private static final Loc BUDGET_OFF = new Loc("gui.sfmfactorystudio.tps.budget_off", "每刻全局预算：0 毫秒（关闭 · 默认）");
    private static final Loc BUDGET = new Loc("gui.sfmfactorystudio.tps.budget", "每刻全局预算：%s 毫秒");
    private static final Loc SAVE = new Loc("gui.sfmfactorystudio.tps.save", "保存并关闭");
    private static final Loc RESET = new Loc("gui.sfmfactorystudio.tps.reset", "恢复默认");
    private static final Loc CANCEL = new Loc("gui.sfmfactorystudio.blocks.popup.cancel", "取消");
    private static final Loc DESC_BACKOFF_1 = new Loc("gui.sfmfactorystudio.tps.desc_backoff_1",
            "连续空转的管理器把定时检测间隔逐级拉长，搬运成功立即恢复；每秒平均搬运量不变，红石触发器不受影响");
    private static final Loc DESC_BACKOFF_2 = new Loc("gui.sfmfactorystudio.tps.desc_backoff_2",
            "仅在开启空转退避时生效；数值越大空闲时越省，首件延迟也越长");
    private static final Loc DESC_BUDGET = new Loc("gui.sfmfactorystudio.tps.desc_budget",
            "超出预算的触发本轮顺延（不搬运 = 损失吞吐！），仅高负载服保 TPS 时开启");
    private static final Loc DESC_DEFAULT = new Loc("gui.sfmfactorystudio.tps.desc_default",
            "默认全部关闭 = 与原版 SFM 行为完全一致（吞吐与首件延迟都不变）");

    private static final int COL_LABEL = 0xFFFFFFFF;
    private static final int COL_DESC = 0xFFB0B0B0;
    private static final int COL_WARN = 0xFFE0A040;

    private final Screen parent;
    private boolean backoff;
    private int maxMult;
    private double budgetMs;

    public TpsSettingsScreen(Screen parent) {
        super(Component.literal(TITLE.getString()));
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
            return BACKOFF_MAX.getString(v, v - 1);
        }));
        y += 30;

        addRenderableWidget(new IntSlider(cx - bw / 2, y, bw, 0, 1000, (int) Math.round(budgetMs), v -> {
            budgetMs = v;
            return v == 0 ? BUDGET_OFF.getString() : BUDGET.getString(v);
        }));
        y += 46;

        addRenderableWidget(Button.builder(Component.literal(SAVE.getString()), b -> save())
                .bounds(cx - 154, y, 100, 20).build());
        addRenderableWidget(Button.builder(Component.literal(RESET.getString()), b -> {
            backoff = false;
            maxMult = 4;
            budgetMs = 0;
            rebuildWidgets();
        }).bounds(cx - 50, y, 100, 20).build());
        addRenderableWidget(Button.builder(Component.literal(CANCEL.getString()), b -> onClose())
                .bounds(cx + 54, y, 100, 20).build());
    }

    private Component toggleLabel() {
        return Component.literal(backoff ? BACKOFF_ON.getString() : BACKOFF_OFF.getString());
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
    public void render(GuiGraphics g, int mx, int my, float partialTick) {
        // 1.20.1：Screen.render 不自动调 renderBackground，由本方法显式调用
        renderBackground(g);
        int cx = this.width / 2;
        int y = Math.max(56, this.height / 2 - 96);
        g.drawCenteredString(this.font, this.title, cx, y - 24, COL_LABEL);
        g.drawCenteredString(this.font, DESC_BACKOFF_1.getString(), cx, y + 22, COL_DESC);
        g.drawCenteredString(this.font, DESC_BACKOFF_2.getString(), cx, y + 52, COL_DESC);
        g.drawCenteredString(this.font, DESC_BUDGET.getString(), cx, y + 82, COL_WARN);
        g.drawCenteredString(this.font, DESC_DEFAULT.getString(), cx, this.height - 44, COL_DESC);
        super.render(g, mx, my, partialTick);
    }

    @Override
    public void renderBackground(GuiGraphics g) {
        g.fillGradient(0, 0, this.width, this.height, 0xC0101010, 0xD0101010);
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
