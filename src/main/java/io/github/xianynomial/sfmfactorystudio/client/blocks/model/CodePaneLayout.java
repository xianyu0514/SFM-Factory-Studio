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
    /**
     * 是否允许"积木 + 代码"分屏。
     *
     * <p>高度门槛从 260 降到 200：260 是按"代码面板至少 26 行"定的，
     * 但 1080p / GUI 缩放 2 的实际可用高只有约 244，导致分屏永远不成立、
     * 用户只能进"只看代码"模式 —— 而那个模式里画布和代码不同屏，
     * 双向联动在视觉上不可能生效（踩了很久才发现）。
     * 200 高约 20 行代码，够用且能让窄窗口也享受联动。
     */
    /**
     * 是否允许"积木 + 代码"分屏。
     *
     * <p>原门槛 280x260 是按"代码面板至少 26 行"定的，但 1080p + GUI 缩放 2 的
     * 实际可用高只有约 244，于是分屏永远不成立、只能进"只看代码"模式，
     * 而那个模式里画布和代码不同屏 —— 双向联动在视觉上不可能发生。
     *
     * <p>下调到 250x230：既保留"紧凑窗口默认只显示积木"的设计意图
     * （见 CodeViewPreferencesTest.compactWindowsKeepBlocks...，270x220 仍判 false），
     * 又让 1080p/缩放 2 这种常见配置能真正分屏。
     */
    public static boolean canSplit(int width, int height) { return width >= 250 && height >= 230; }
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
