package io.github.xianynomial.sfmfactorystudio.client.blocks.model;

/**
 * 编辑器 UI 的纯几何数学（MC-free，可单测）：虚拟分辨率换算与工具栏折行。
 * 这里锁住 2026-09-08 交互失效修复批的不变量——
 * 声明行数 >= 实际占用行数 ⇒ 按钮永远不会溢出工具栏条进入画布区吞点击。
 */
public final class EditorUiMath {
    private EditorUiMath() {}

    /** 虚拟分辨率换算结果。 */
    public record Frame(float scale, int width, int height) {}

    /**
     * GUI 逻辑尺寸不足 minVirtualW 时等比缩小整个编辑器绘制。
     * scale = min(1, max(0.25, guiW / minVirtualW))；尺寸按 scale 反推取整。
     */
    public static Frame virtualFrame(int guiW, int guiH, int minVirtualW) {
        float scale = Math.min(1.0f, Math.max(0.25f, guiW / (float) minVirtualW));
        return new Frame(scale, Math.round(guiW / scale), Math.round(guiH / scale));
    }

    /**
     * 工具栏声明行数的按宽猜测下限。窄面板需要更多行。
     * 注意 <380 必须先于 <560 判断，否则 3 行分支永不可达
     * （2026-09-08 曾因三目顺序写反导致按钮溢出进画布）。
     */
    public static int guessToolbarRows(int panelW) {
        return panelW < 380 ? 3 : (panelW < 560 ? 2 : 1);
    }

    /** 折行布局结果：实际占用行数 + 第一行按钮组左缘（状态字相对它右对齐让位）。 */
    public record Placement(int rows, int row1Left) {}

    /**
     * 按钮右→左放置，放不下折到下一行（按需多行，无上限）。
     * widths 按绘制顺序给出；xs/ys 输出每颗按钮左上角（与 widths 同序，须预分配）。
     * 首颗贴右缘（panelX+panelW-8）无间隙，其余间隙 gap；折行后从右缘重新起排。
     * 调用方须把返回的 rows 回写为"实际占用行数"，与 {@link #guessToolbarRows}
     * 取 max 作为声明行数——这保证任何按钮的 y 都落在声明的工具栏条内。
     */
    public static Placement placeToolbar(int panelX, int panelW, int minBx, int startRowY,
                                         int rowH, int gap, int[] widths, int[] xs, int[] ys) {
        int rowY = startRowY;
        int curX = panelX + panelW - 8;
        int row1Left = curX;
        for (int i = 0; i < widths.length; i++) {
            int w = widths[i];
            curX -= (i == 0 ? 0 : gap) + w;
            if (curX < minBx) {
                rowY += rowH;
                curX = panelX + panelW - 8 - w;
            }
            if (rowY == startRowY) row1Left = curX;
            xs[i] = curX;
            ys[i] = rowY;
        }
        return new Placement(((rowY - startRowY) / rowH) + 1, row1Left);
    }
}
