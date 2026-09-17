package io.github.xianynomial.sfmfactorystudio.client.blocks.model;

/** Same geometry for drawing, hit testing and dragging the horizontal divider. */
public final class CodePaneLayout {
    public static final int GAP = 6;
    private CodePaneLayout() {}
    public record Split(int canvasHeight, int codeTop, int codeHeight, int dividerY) {}
    public static Split split(int top, int bottom, double fraction) {
        int usable = Math.max(0, bottom - top - GAP);
        int minCanvas = Math.min(64, usable / 2);
        int minCode = Math.min(80, usable - minCanvas);
        if (!Double.isFinite(fraction)) fraction = 0.5;
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
