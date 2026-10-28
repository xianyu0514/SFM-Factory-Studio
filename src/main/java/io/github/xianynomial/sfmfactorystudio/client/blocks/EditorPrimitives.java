package io.github.xianynomial.sfmfactorystudio.client.blocks;

import net.minecraft.client.gui.GuiGraphicsExtractor;

/**
 * 编辑器的无状态绘制原语。
 *
 * <p>从 {@code BlockEditorScreen} 抽出的原因：这几个方法只依赖 {@code g} 与入参，
 * 不碰任何实例状态，留在 9000 行的屏幕类里既难复用、也难单独验证。
 * 屏幕类通过 {@code import static ...EditorPrimitives.*} 继续直接调用，调用点无需改动。
 *
 * <p><b>性能约束（改之前先算）</b>：{@code g.fill} 每次都是一次绘制提交，不是免费的。
 * 曾把 {@link #rounded} 改成按圆方程逐行绘制真圆角（每圆角约 16 次 fill），
 * 一帧几十个圆角 = 数百次提交，帧率直接崩。这里保持两次矩形叠加的近似方案。
 * 加任何逐行/逐点效果前，先算「元素数 × 每次调用数 = 每帧提交数」。
 */
public final class EditorPrimitives {

    private EditorPrimitives() {
    }

    /**
     * 圆角矩形（快速版）。
     *
     * <p>两次矩形叠加做出的近似圆角：一次竖向内缩、一次横向内缩，四个角自然缺一小块。
     * 视觉上足够，绘制次数固定为 2。半径无效或尺寸过小时退化为单个实心矩形。
     */
    public static void rounded(GuiGraphicsExtractor g, int x, int y, int w, int h, int r, int color) {
        if (r <= 0 || w < r * 2 || h < r * 2) {
            g.fill(x, y, x + w, y + h, color);
            return;
        }
        g.fill(x + 1, y, x + w - 1, y + h, color);
        g.fill(x, y + 1, x + w, y + h - 1, color);
    }

    /** 1px 描边，固定 4 次 fill（上/下/左/右各一条）。 */
    public static void border(GuiGraphicsExtractor g, int x, int y, int w, int h, int color) {
        g.fill(x + 1, y, x + w - 1, y + 1, color);
        g.fill(x + 1, y + h - 1, x + w - 1, y + h, color);
        g.fill(x, y + 1, x + 1, y + h - 1, color);
        g.fill(x + w - 1, y + 1, x + w, y + h - 1, color);
    }

    /** 虚线描边：拖动卡片时标出落位。段长 5px、间隔 5px。 */
    public static void dashedBorder(GuiGraphicsExtractor g, int x, int y, int w, int h, int color) {
        int x2 = x + w, y2 = y + h;
        int dash = 5;
        for (int i = x; i < x2; i += dash * 2) {
            int e = Math.min(i + dash, x2);
            g.fill(i, y, e, y + 1, color);
            g.fill(i, y2 - 1, e, y2, color);
        }
        for (int i = y; i < y2; i += dash * 2) {
            int e = Math.min(i + dash, y2);
            g.fill(x, i, x + 1, e, color);
            g.fill(x2 - 1, i, x2, e, color);
        }
    }

    /** 按 ratio（0~255）线性混合两个 ARGB 颜色，结果不透明。 */
    public static int mix(int a, int b, int ratio) {
        int ar = (a >> 16) & 0xFF, ag = (a >> 8) & 0xFF, ab = a & 0xFF;
        int br = (b >> 16) & 0xFF, bg = (b >> 8) & 0xFF, bb = b & 0xFF;
        int r = (ar * (255 - ratio) + br * ratio) / 255;
        int gg = (ag * (255 - ratio) + bg * ratio) / 255;
        int bl = (ab * (255 - ratio) + bb * ratio) / 255;
        return 0xFF000000 | (r << 16) | (gg << 8) | bl;
    }
}
