package io.github.xianynomial.sfmfactorystudio.test;

import io.github.xianynomial.sfmfactorystudio.client.blocks.model.EditorUiMath;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 编辑器 UI 几何回归锁（2026-09-08 交互失效修复批）。
 *
 * 背景：工具栏折行三目顺序写反（panelW<560?2:(panelW<380?3:1) 中 <380 分支
 * 永不可达），窄面板下声明行数(2) < 实际需要(4)，按钮被画进画布顶部并以
 * uiHits 吞掉该区域的点击——"积木拖不动、右键无反应"的直接机制。
 * 这组测试锁住三件事：
 * ① guessToolbarRows 的分支顺序（窄面板必须能给出 3 行）；
 * ② placeToolbar 的行数/坐标不变量（所有按钮落在声明行数的工具栏条内）；
 * ③ 虚拟分辨率换算（4K+scale9 ⇒ 640 虚拟宽，scale4 恒等）。
 */
public class EditorUiMathTest {

    // 与 BlockTheme/编辑器常量一致的布局契约值（防漂移，改动须同步审视）
    private static final int TOOLBAR_H = 28;
    private static final int GAP = 4;
    private static final int PANEL_X = 62, PANEL_Y = 16;   // 虚拟 480×270 + JEI 时的典型面板原点
    private static final int START_ROW_Y = PANEL_Y + 4;

    // 中文文案下的 9 颗固定按钮宽（保存/代码/撤销/重做/适配/分区/平衡/问题/关闭）
    private static final int[] ZH_9 = {86, 52, 48, 46, 44, 42, 48, 52, 46};
    private static final int TPL_W = 68;

    private static int[] withTpl(int[] base) {
        int[] out = new int[base.length + 1];
        System.arraycopy(base, 0, out, 0, base.length);
        out[base.length] = TPL_W;
        return out;
    }

    // ---------------------------------------------------------- 行数下限

    @Test
    void guessRows_branchOrder_fixed() {
        // 2026-09-08 前的 bug 版本 (panelW<560?2:(panelW<380?3:1)) 在这些值上
        // 分别返回 2/2/2/2 —— <380 分支永不可达。修后必须如此：
        assertEquals(3, EditorUiMath.guessToolbarRows(300));
        assertEquals(3, EditorUiMath.guessToolbarRows(355)); // 用户极端缩放场景（480 虚拟 + JEI 74%）
        assertEquals(3, EditorUiMath.guessToolbarRows(379));
        assertEquals(2, EditorUiMath.guessToolbarRows(380));
        assertEquals(2, EditorUiMath.guessToolbarRows(474)); // 640 虚拟 + JEI 74%
        assertEquals(2, EditorUiMath.guessToolbarRows(559));
        assertEquals(1, EditorUiMath.guessToolbarRows(560));
        assertEquals(1, EditorUiMath.guessToolbarRows(902)); // 4K + scale4 无 JEI
    }

    // ---------------------------------------------------------- 不变量扫描

    /** 核心不变量：所有按钮 y 都在 [startRowY, startRowY + 声明行数×rowH) 内。 */
    private void assertInsideDeclaredStrip(int panelW, int minBx, int[] widths) {
        int n = widths.length;
        int[] xs = new int[n], ys = new int[n];
        EditorUiMath.Placement p = EditorUiMath.placeToolbar(
                PANEL_X, panelW, minBx, START_ROW_Y, TOOLBAR_H, GAP, widths, xs, ys);
        int declared = Math.max(EditorUiMath.guessToolbarRows(panelW), p.rows());
        assertTrue(p.rows() >= 1, "至少一行");
        assertTrue(declared >= p.rows(), "声明行数 >= 实际占用行数（panelW=" + panelW + "）");
        for (int i = 0; i < n; i++) {
            assertTrue(ys[i] >= START_ROW_Y, "按钮 y 越过工具栏顶（panelW=" + panelW + " i=" + i + "）");
            assertTrue(ys[i] < START_ROW_Y + declared * TOOLBAR_H,
                    "按钮 y 越过声明工具栏条 ⇒ 会画进画布吞点击（panelW=" + panelW
                            + " i=" + i + " y=" + ys[i] + " 声明行数=" + declared + "）");
            assertTrue(xs[i] >= PANEL_X, "按钮左缘越出面板（panelW=" + panelW + " i=" + i + "）");
            assertTrue(xs[i] + widths[i] <= PANEL_X + panelW - 8,
                    "按钮右缘越出面板右缘（panelW=" + panelW + " i=" + i + "）");
        }
    }

    @Test
    void allButtonsInsideDeclaredStrip_sweep() {
        int[][] sets = {ZH_9, withTpl(ZH_9),
                {90, 60, 52, 52, 48, 48, 56, 60, 52, 72}}; // 英文更宽的文案
        for (int[] widths : sets) {
            for (int panelW = 150; panelW <= 1200; panelW += 7) {
                int minBx = PANEL_X + Math.min(panelW / 3, 200);
                assertInsideDeclaredStrip(panelW, minBx, widths);
            }
        }
    }

    @Test
    void brokenScenario_nowFoldsInsteadOfBleeding() {
        // 用户实测场景：480 虚拟宽 + JEI ⇒ panelW≈355。修复前声明 2 行、按钮
        // 需要 ~4 行，第 3/4 行画进画布顶部。修复后必须折行并如实报告行数：
        int panelW = 355;
        int n = 10;
        int[] widths = withTpl(ZH_9);
        int[] xs = new int[n], ys = new int[n];
        EditorUiMath.Placement p = EditorUiMath.placeToolbar(
                PANEL_X, panelW, PANEL_X + 96, START_ROW_Y, TOOLBAR_H, GAP, widths, xs, ys);
        assertTrue(p.rows() >= 3, "窄面板必须如实折到 3+ 行，实际=" + p.rows());
        int declared = Math.max(EditorUiMath.guessToolbarRows(panelW), p.rows());
        for (int i = 0; i < n; i++) {
            assertTrue(ys[i] < START_ROW_Y + declared * TOOLBAR_H,
                    "修复失效：按钮回到了画布里（i=" + i + "）");
        }
    }

    // ---------------------------------------------------------- 放置细节

    @Test
    void widePanel_singleRow_leftToRightOrder() {
        int panelW = 902;
        int[] widths = withTpl(ZH_9);
        int n = widths.length;
        int[] xs = new int[n], ys = new int[n];
        EditorUiMath.Placement p = EditorUiMath.placeToolbar(
                PANEL_X, panelW, PANEL_X + 200, START_ROW_Y, TOOLBAR_H, GAP, widths, xs, ys);
        assertEquals(1, p.rows());
        for (int i = 0; i < n; i++) assertEquals(START_ROW_Y, ys[i]);
        // 右→左：x 严格递减；row1Left = 最左颗
        for (int i = 1; i < n; i++) assertTrue(xs[i] < xs[i - 1]);
        assertEquals(xs[n - 1], p.row1Left());
        // 首颗贴右缘（panelX+panelW-8）无间隙；其余颗间距恰为 GAP
        assertEquals(PANEL_X + panelW - 8 - widths[0], xs[0]);
        for (int i = 1; i < n; i++) assertEquals(xs[i - 1] - GAP - widths[i], xs[i]);
    }

    @Test
    void foldedRow_restartsAtRightEdge_row1LeftFreezes() {
        // 构造必然折行：一排超宽按钮
        int[] widths = {120, 120, 120, 120, 120, 120};
        int panelW = 420;
        int minBx = PANEL_X + 96;
        int n = widths.length;
        int[] xs = new int[n], ys = new int[n];
        EditorUiMath.Placement p = EditorUiMath.placeToolbar(
                PANEL_X, panelW, minBx, START_ROW_Y, TOOLBAR_H, GAP, widths, xs, ys);
        assertTrue(p.rows() > 1);
        // 每行第一颗右缘都贴 panelX+panelW-8
        boolean firstOfRow = true;
        int expectedRow1Left = -1;
        for (int i = 0; i < n; i++) {
            if (i > 0 && ys[i] != ys[i - 1]) firstOfRow = true;
            if (firstOfRow) {
                assertEquals(PANEL_X + panelW - 8 - widths[i], xs[i], "行首颗应从右缘起排 i=" + i);
                firstOfRow = false;
            }
            // row1Left 语义 = 第一行最后放置（最左）颗；折行后不再更新
            if (ys[i] == START_ROW_Y) expectedRow1Left = xs[i];
        }
        assertEquals(expectedRow1Left, p.row1Left(), "row1Left 必须只由第一行决定");
    }

    // ---------------------------------------------------------- 虚拟分辨率

    @Test
    void virtualScale_identityAtNormalResolutions() {
        // 4K + scale4 ⇒ GUI 960×540，edScale 恒等（不改变任何现有体验）
        EditorUiMath.Frame f = EditorUiMath.virtualFrame(960, 540, 640);
        assertEquals(1.0f, f.scale(), 1e-6f);
        assertEquals(960, f.width());
        assertEquals(540, f.height());
    }

    @Test
    void virtualScale_extremeZoomGets640VirtualWidth() {
        // 4K + scale9 ⇒ GUI 427×240。修复前下限 480 ⇒ 画布 ~203px 宽积木行点不中；
        // 修复后虚拟宽 640 ⇒ 面板 ~474、画布 ~300px。
        EditorUiMath.Frame f = EditorUiMath.virtualFrame(427, 240, 640);
        assertEquals(427f / 640f, f.scale(), 1e-6f);
        assertEquals(640, f.width());
        assertEquals(360, f.height());
    }

    @Test
    void virtualScale_clampsToQuarterFloor() {
        // 下限 0.25 保持原公式语义（宽 <160 的窗口会到地板，虚拟宽不再增大）
        EditorUiMath.Frame f = EditorUiMath.virtualFrame(100, 100, 640);
        assertEquals(0.25f, f.scale(), 1e-6f);
        assertEquals(400, f.width());
        assertEquals(400, f.height());
    }
}
