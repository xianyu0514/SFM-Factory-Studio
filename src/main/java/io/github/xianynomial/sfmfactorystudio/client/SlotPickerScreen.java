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
        this(parent, total, coords, initial, onResult, false);
    }

    private SlotPickerScreen(Screen parent, int total, List<int[]> coords, List<Integer> initial,
                             Consumer<List<Integer>> onResult, boolean unavailableMode) {
        super(Component.literal("选择槽位（beta）"));
        this.parent = parent;
        this.total = total;
        this.unavailableMode = unavailableMode;
        if (coords.isEmpty()) buildGridCoords(total);
        else this.coords.addAll(coords);
        this.selected.addAll(initial);
        this.onResult = onResult;
        this.ready = true;
    }

    /** 服务端未回应（未装附属）：短暂提示后自动关闭。 */
    public static SlotPickerScreen unavailable() {
        return new SlotPickerScreen(null, -1, List.of(), List.of(), null, true);
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

    private void relayoutGrid() {
        int rows = Math.max(1, (total + COLS - 1) / COLS);
        gridW = COLS * CELL;
        gridH = rows * CELL;
        gridX = (width - gridW) / 2;
        gridY = Math.max(60, height / 2 - gridH / 2);
    }

    @Override
    protected void init() {
        relayoutGrid();
    }

    private int slotAt(double mx, double my) {
        for (int i = 0; i < coords.size(); i++) {
            int x = gridX + coords.get(i)[0];
            int y = gridY + coords.get(i)[1];
            if (mx >= x && mx < x + CELL && my >= y && my < y + CELL) {
                return i < total ? i : -1;
            }
        }
        return -1;
    }

    @Override
    public boolean mouseClicked(double mx, double my, int button) {
        if (button == 0) {
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
        g.drawCenteredString(this.font, "选择槽位（beta）", width / 2, 24, 0xFFFFFFFF);
        g.drawCenteredString(this.font, "单击选中 · 拖动刷选 · Shift+点击选范围", width / 2, 38, 0xFF909090);

        relayoutGrid();
        // 背景
        g.fill(gridX - 4, gridY - 4, gridX + gridW + 4, gridY + gridH + 4, 0xFF2A2A2E);
        for (int i = 0; i < total; i++) {
            int x = gridX + coords.get(i)[0];
            int y = gridY + coords.get(i)[1];
            boolean sel = selected.contains(i);
            g.fill(x + 1, y + 1, x + CELL - 1, y + CELL - 1, sel ? 0xFF3A6FD8 : 0xFF1B1B1E);
            border(g, x, y, CELL, CELL, sel ? 0xFF7FA8FF : 0xFF55555C);
            g.drawCenteredString(this.font, String.valueOf(i), x + CELL / 2, y + CELL / 2 - 4,
                    sel ? 0xFFEAF2FF : 0xFF8A8A92);
        }

        // 底部：结果 + 按钮
        String result = compress(selected);
        g.drawCenteredString(this.font, result.isEmpty() ? "未选择（=全部槽位）" : "slots " + result,
                width / 2, gridY + gridH + 12, selected.isEmpty() ? 0xFF909090 : 0xFF7FA8FF);
        int by = gridY + gridH + 26;
        button(g, width / 2 - 110, by, 70, "确认", 0xFF2FA84F, () -> {
            onResult.accept(new ArrayList<>(selected));
            onClose();
        }, over(width / 2 - 110, by, 70, mx, my));
        button(g, width / 2 - 35, by, 70, "清空", 0xFF5B6472, () -> selected.clear(), over(width / 2 - 35, by, 70, mx, my));
        button(g, width / 2 + 40, by, 70, "关闭", 0xFF5B6472, this::onClose, over(width / 2 + 40, by, 70, mx, my));
    }

    private void button(GuiGraphics g, int x, int y, int w, String label, int color, Runnable onClick, boolean hover) {
        g.fill(x, y, x + w, y + 18, hover ? mix(color, 0xFFFFFFFF, 40) : color);
        g.drawCenteredString(this.font, label, x + w / 2, y + 5, 0xFFFFFFFF);
        if (hover) pendingAction = onClick;
    }

    private Runnable pendingAction;

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
        Minecraft.getInstance().setScreen(parent);
    }
}
