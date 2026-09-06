package io.github.xianynomial.sfmfactorystudio.client;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.TreeSet;
import java.util.function.Consumer;

/**
 * 槽位可视化选择器（beta）：按容器真实布局渲染槽位网格，点选/刷选后
 * 回写槽位文本。三层降级：
 * ① 布局快照（服务端 Menu 坐标）——像素级还原；
 * ② 仅总槽数——每行 9 格自适应网格；
 * ③ 服务端未装/请求失败——提示后自动关闭（入口此时应已隐藏）。
 *
 * 交互：单击选中/取消；按住拖动刷选；Shift+点击范围选中；「清空」；
 * 「确认」把 TreeSet 槽号压缩成 1,3-9 形式回写。
 */
public final class SlotPickerScreen extends Screen {
    private static final int CELL = 20;
    private static final int COLS = 9;

    private final Screen parent;
    private final Consumer<List<Integer>> onResult;
    private final TreeSet<Integer> selected = new TreeSet<>();
    private final List<int[]> coords = new ArrayList<>();   // {x, y} 像素布局（②为网格生成）
    private int total;
    private boolean ready;
    private boolean failed;
    private int brushFrom = -1;                             // 刷选起点槽号
    private int gridW, gridH, gridX, gridY;

    private boolean unavailableMode = false;

    public SlotPickerScreen(Screen parent, int total, List<int[]> coords, List<Integer> initial,
                            Consumer<List<Integer>> onResult) {
        this(parent, total, coords, initial, onResult, false, null);
    }

    public SlotPickerScreen(Screen parent, int total, List<int[]> coords, List<Integer> initial,
                            Consumer<List<Integer>> onResult, net.minecraft.core.BlockPos containerPos) {
        this(parent, total, coords, initial, onResult, false, containerPos);
    }

    private SlotPickerScreen(Screen parent, int total, List<int[]> coords, List<Integer> initial,
                             Consumer<List<Integer>> onResult, boolean unavailableMode,
                             net.minecraft.core.BlockPos containerPos) {
        super(Component.literal("选择槽位（beta）"));
        this.parent = parent;
        this.total = total;
        this.unavailableMode = unavailableMode;
        // 真实 GUI 布局：玩家右键打开过该容器界面时，捕获缓存里存着像素级
        // 坐标（按方块坐标键，含异形布局）——与原版界面完全一致
        if (containerPos != null) {
            ClientGuiLayoutCache.Layout captured = ClientGuiLayoutCache.get(containerPos);
            if (captured != null && !captured.slots().isEmpty()) {
                // 捕获条目 = [容器槽索引, x, y]；按索引展开成坐标数组
                int maxIdx = 0;
                for (int[] e : captured.slots()) maxIdx = Math.max(maxIdx, e[0]);
                int need = Math.max(total, maxIdx + 1);
                List<int[]> positioned = new ArrayList<>();
                for (int i = 0; i < need; i++) positioned.add(new int[]{-1, -1}); // 无坐标占位
                for (int[] e : captured.slots()) positioned.set(e[0], new int[]{e[1], e[2]});
                coords = positioned;
                total = need;
            }
        }
        if (coords.isEmpty()) buildGridCoords(total);
        else this.coords.addAll(coords);
        this.selected.addAll(initial);
        this.onResult = onResult;
        if (this.coords.isEmpty() && this.total > 0) buildGridCoords(this.total);
        this.ready = this.total > 0;
    }

    /** 服务端未回应（未装附属）：短暂提示后自动关闭。 */
    public static SlotPickerScreen unavailable() {
        return new SlotPickerScreen(null, 0, List.of(), List.of(), null, true, null);
    }

    public static void showUnavailable(Screen parent) {
        Minecraft mc = Minecraft.getInstance();
        mc.setScreen(new Screen(Component.literal("槽位可视化不可用")) {
            @Override
            public void render(GuiGraphics g, int mx, int my, float pt) {
                this.renderBackground(g, mx, my, pt);
                g.drawCenteredString(this.font, "服务端未安装槽位可视化支持", this.width / 2, this.height / 2 - 8, 0xFFFFFFFF);
                g.drawCenteredString(this.font, "可直接在输入框中输入槽位数字", this.width / 2, this.height / 2 + 8, 0xFFB0B0B0);
            }

            @Override
            public void tick() {
                onClose();
            }
        });
    }

    private void buildGridCoords(int count) {
        coords.clear();
        int rows = Math.max(1, (count + COLS - 1) / COLS);
        for (int i = 0; i < count; i++) {
            coords.add(new int[]{(i % COLS) * CELL, (i / COLS) * CELL});
        }
        gridW = COLS * CELL;
        gridH = rows * CELL;
    }

    private float viewScale = 1.0f;

    private void relayoutGrid() {
        // 计算布局包围盒（真实 GUI 坐标可能远超 9 列网格；-1 占位跳过）
        int maxX = 0, maxY = 0;
        for (int[] c : coords) {
            if (c[0] < 0) continue;
            maxX = Math.max(maxX, c[0] + CELL);
            maxY = Math.max(maxY, c[1] + CELL);
        }
        int rawW = Math.max(maxX, COLS * CELL);
        int rawH = Math.max(maxY, CELL);
        // 缩放适配：布局超出屏宽/屏高（留出标题和按钮空间）时整体缩小
        float availW = width - 40, availH = height - 150;
        viewScale = Math.min(1.0f, Math.min(availW / rawW, availH / rawH));
        gridW = Math.round(rawW * viewScale);
        gridH = Math.round(rawH * viewScale);
        gridX = (width - gridW) / 2;
        gridY = Math.max(56, height / 2 - gridH / 2);
    }

    @Override
    protected void init() {
        relayoutGrid();
    }

    private int slotAt(double mx, double my) {
        int sz = Math.max(8, Math.round(CELL * viewScale));
        for (int i = 0; i < Math.min(coords.size(), total); i++) {
            int[] c = coords.get(i);
            if (c[0] < 0) continue;
            int x = gridX + Math.round(c[0] * viewScale);
            int y = gridY + Math.round(c[1] * viewScale);
            if (mx >= x && mx < x + sz && my >= y && my < y + sz) {
                return i;
            }
        }
        return -1;
    }

    /** 底部三个按钮：[矩形 x,y,w] + 动作，render 与 mouseClicked 共用同一数据。 */
    private final List<int[]> buttonRects = new ArrayList<>();
    private final List<Runnable> buttonActions = new ArrayList<>();

    public boolean mouseClicked(double mx, double my, int button) {
        if (button == 0) {
            // 按钮优先（渲染层与命中层共用 buttonRects，永不漂移）
            for (int i = 0; i < buttonRects.size(); i++) {
                int[] b = buttonRects.get(i);
                if (mx >= b[0] && mx < b[0] + b[2] && my >= b[1] && my < b[1] + 18) {
                    buttonActions.get(i).run();
                    return true;
                }
            }
            int slot = slotAt(mx, my);
            if (slot >= 0) {
                if (hasShiftDown() && !selected.isEmpty()) {
                    int from = selected.last();
                    for (int i = Math.min(from, slot); i <= Math.max(from, slot); i++) selected.add(i);
                } else {
                    if (selected.contains(slot)) selected.remove(slot);
                    else selected.add(slot);
                    brushFrom = slot;
                }
                return true;
            }
        }
        return super.mouseClicked(mx, my, button);
    }

    @Override
    public boolean mouseDragged(double mx, double my, int button, double dx, double dy) {
        if (button == 0 && brushFrom >= 0) {
            int slot = slotAt(mx, my);
            if (slot >= 0) {
                for (int i = Math.min(brushFrom, slot); i <= Math.max(brushFrom, slot); i++) selected.add(i);
            }
            return true;
        }
        return super.mouseDragged(mx, my, button, dx, dy);
    }

    @Override
    public boolean mouseReleased(double mx, double my, int button) {
        brushFrom = -1;
        return super.mouseReleased(mx, my, button);
    }

    @Override
    public void render(GuiGraphics g, int mx, int my, float partialTick) {
        this.renderBackground(g, mx, my, partialTick);
        if (!ready) {
            g.drawCenteredString(this.font, "还没有这个容器的布局记录", width / 2, height / 2 - 20, 0xFFFFFFFF);
            g.drawCenteredString(this.font, "① 返回游戏，右键打开一次该容器的界面", width / 2, height / 2 - 2, 0xFFE0E0E0);
            g.drawCenteredString(this.font, "② 再回到这里，就会还原成和原版一样的布局", width / 2, height / 2 + 14, 0xFFE0E0E0);
            g.drawCenteredString(this.font, "也可以直接关闭后输入槽位数字", width / 2, height / 2 + 30, 0xFF909090);
            return;
        }
        g.drawCenteredString(this.font, "选择槽位（beta）", width / 2, 24, 0xFFFFFFFF);
        g.drawCenteredString(this.font, "单击选中 · 拖动刷选 · Shift+点击选范围", width / 2, 38, 0xFF909090);

        relayoutGrid();
        // 背景
        g.fill(gridX - 4, gridY - 4, gridX + gridW + 4, gridY + gridH + 4, 0xFF2A2A2E);
        // 坐标数可能少于 total（快照缺失/退化网格）：按实际有的坐标渲染
        int drawable = Math.min(total, coords.size());
        for (int i = 0; i < drawable; i++) {
            int[] c = coords.get(i);
            if (c[0] < 0) continue; // 无坐标的槽（快照未覆盖）不渲染
            int x = gridX + Math.round(c[0] * viewScale);
            int y = gridY + Math.round(c[1] * viewScale);
            int cw = Math.max(8, Math.round(CELL * viewScale));
            int ch = cw;
            boolean sel = selected.contains(i);
            g.fill(x + 1, y + 1, x + cw - 1, y + ch - 1, sel ? 0xFF3A6FD8 : 0xFF1B1B1E);
            border(g, x, y, cw, ch, sel ? 0xFF7FA8FF : 0xFF55555C);
            if (cw >= 14) {
                g.drawCenteredString(this.font, String.valueOf(i), x + cw / 2, y + ch / 2 - 4,
                        sel ? 0xFFEAF2FF : 0xFF8A8A92);
            }
        }

        // 底部：结果 + 按钮
        String result = compress(selected);
        g.drawCenteredString(this.font, result.isEmpty() ? "未选择（=全部槽位）" : "slots " + result,
                width / 2, gridY + gridH + 12, selected.isEmpty() ? 0xFF909090 : 0xFF7FA8FF);
        int by = gridY + gridH + 26;
        buttonRects.clear();
        buttonActions.clear();
        addButton(g, width / 2 - 110, by, 70, "确认", 0xFF2FA84F, () -> {
            if (onResult != null) onResult.accept(new ArrayList<>(selected));
            onClose();
        }, mx, my);
        addButton(g, width / 2 - 35, by, 70, "清空", 0xFF5B6472, () -> selected.clear(), mx, my);
        addButton(g, width / 2 + 40, by, 70, "关闭", 0xFF5B6472, this::onClose, mx, my);
    }

    /** 画按钮并注册命中矩形（render 与 mouseClicked 共用，保证可点）。 */
    private void addButton(GuiGraphics g, int x, int y, int w, String label, int color, Runnable onClick, double mx, double my) {
        boolean hover = mx >= x && mx < x + w && my >= y && my < y + 18;
        g.fill(x, y, x + w, y + 18, hover ? mix(color, 0xFFFFFFFF, 40) : color);
        g.drawCenteredString(this.font, label, x + w / 2, y + 5, 0xFFFFFFFF);
        buttonRects.add(new int[]{x, y, w});
        buttonActions.add(onClick);
    }

    private static boolean over(int x, int y, int w, double mx, double my) {
        return mx >= x && mx < x + w && my >= y && my < y + 18;
    }

    private static int mix(int a, int b, int ratio) {
        int r = (a >> 16 & 255) * (255 - ratio) / 255 + (b >> 16 & 255) * ratio / 255;
        int gg = (a >> 8 & 255) * (255 - ratio) / 255 + (b >> 8 & 255) * ratio / 255;
        int bl = (a & 255) * (255 - ratio) / 255 + (b & 255) * ratio / 255;
        return 0xFF000000 | r << 16 | gg << 8 | bl;
    }

    private static void border(GuiGraphics g, int x, int y, int w, int h, int color) {
        g.fill(x, y, x + w, y + 1, color);
        g.fill(x, y + h - 1, x + w, y + h, color);
        g.fill(x, y, x + 1, y + h, color);
        g.fill(x + w - 1, y, x + w, y + h, color);
    }

    /** {1,3,4,5,9} → "1,3-5,9" */
    public static String compress(TreeSet<Integer> set) {
        StringBuilder sb = new StringBuilder();
        Integer prev = null, start = null;
        for (Integer v : set) {
            if (prev != null && v == prev + 1) {
                prev = v;
                continue;
            }
            flush(sb, start, prev);
            start = v;
            prev = v;
        }
        flush(sb, start, prev);
        return sb.toString();
    }

    private static void flush(StringBuilder sb, Integer start, Integer end) {
        if (start == null) return;
        if (sb.length() > 0) sb.append(',');
        if (start.equals(end)) sb.append(start);
        else sb.append(start).append('-').append(end);
    }

    @Override
    public void onClose() {
        Minecraft mc = Minecraft.getInstance();
        if (parent != null) mc.setScreen(parent);
        else mc.setScreen(null);
    }
}
