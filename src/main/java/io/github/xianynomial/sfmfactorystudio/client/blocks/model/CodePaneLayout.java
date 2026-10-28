package io.github.xianynomial.sfmfactorystudio.client.blocks.model;

/** Same geometry for drawing, hit testing and dragging the horizontal divider. */
public final class CodePaneLayout {
    /**
     * 画布与代码面板之间的间隙（也是拖动条所在的高度）。
     * 从 6 收到 4：视觉上更紧凑，拖动区域仍然够点（4px ≈ 8 物理像素@scale2）。
     * 所有分屏高度、拖动换算都由它推导，改这一个值即可全局一致。
     */
    public static final int GAP = 4;
    private CodePaneLayout() {}
    public static boolean canSplit(int width, int height) { return width >= 280 && height >= 260; }
    public record Split(int canvasHeight, int codeTop, int codeHeight, int dividerY) {}
    public static Split split(int top, int bottom, double fraction) {
        int usable = Math.max(0, bottom - top - GAP);
        int minCanvas = Math.min(64, usable / 2);
        int minCode = Math.min(80, usable - minCanvas);
        if (!Double.isFinite(fraction)) fraction = CodeViewPreferences.DEFAULT_FRACTION;
        int code = Math.max(minCode, Math.min(usable - minCanvas, (int) Math.round(usable * fraction)));
        int canvas = usable - code;
        return new Split(canvas, top + canvas + GAP, code, top + canvas + GAP / 2);
    }
    public static double dragFraction(int top, int bottom, double pointerY, double grabOffset) {
        int usable = Math.max(1, bottom - top - GAP);
        double desiredCanvas = pointerY - grabOffset - top - GAP / 2.0;
        return Math.max(0, Math.min(1, (usable - desiredCanvas) / usable));
    }
}
