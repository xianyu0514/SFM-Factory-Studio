package io.github.xianynomial.sfmfactorystudio.client;

import io.github.xianynomial.sfmfactorystudio.client.Loc;

import io.github.xianynomial.sfmfactorystudio.client.blocks.model.BProgram;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.util.Mth;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.function.Consumer;

/**
 * Built-in fallback resource catalog used when JEI is not installed (with JEI,
 * players drag from JEI's own list instead). Light glass theme: searchable icon
 * grid. The resource category is chosen before this screen opens, so this page
 * never mixes category selection with concrete resources.
 *
 * 两种模式：
 * 单选（legacy）——点击物品立即返回；
 * 多选（multiPick != null）——右上角 ＋ 角标勾选/Shift+点击/按住拖动框选，
 * 绿色描边标记已选，底部「完成」一次性提交整组（语义=「和」备选链，SFML or 连接）。
 * 无修饰键单击仍是"选这一个立即完成"的最短路径。
 */
public class ResourcePickerScreen extends Screen {
    private static final Loc SEARCH = new Loc("gui.sfmfactorystudio.blocks.picker.search", "搜索...");
    private static final Loc TITLE = new Loc("gui.sfmfactorystudio.blocks.picker.title", "选择%s");
    private static final Loc COUNT = new Loc("gui.sfmfactorystudio.blocks.picker.count", "%s 项");
    private static final Loc CANCEL = new Loc("gui.sfmfactorystudio.blocks.close", "关闭");
    private static final Loc DONE = new Loc("gui.sfmfactorystudio.blocks.picker.done", "完成");
    private static final Loc CLEAR = new Loc("gui.sfmfactorystudio.blocks.picker.clear", "清空");
    private static final Loc MULTI_HINT = new Loc("gui.sfmfactorystudio.blocks.picker.multi_hint", "＋勾选 / Shift+点 / 拖框选，完成后点「完成」");

    private static final int CELL = 22;
    private static final int COLS = 11;
    /** 已选项的绿色描边（与单选蓝区分：绿=攒着一组，蓝=即选即走）。 */
    private static final int GREEN = 0xFF0C8F58;

    private final Screen previousScreen;
    private final BProgram.ResourceKind resourceKind;
    private final Consumer<String> onPick;
    @Nullable
    private final Consumer<List<String>> multiPick;
    private final LinkedHashSet<String> multiSelected = new LinkedHashSet<>();

    private final List<ResourceIndex.Entry> filtered = new ArrayList<>();
    private EditBox searchBox;
    private int scrollRow = 0;
    private ResourceIndex.Entry hoveredEntry;
    // 多选拖动状态：pressIndex = 按下的格子（-1 无）；bandDragging = 已进入框选
    private int pressIndex = -1;
    private int bandAnchor = -1;
    private boolean bandDragging = false;
    private int bandCursor = -1;

    public ResourcePickerScreen(Screen previousScreen, BProgram.ResourceKind resourceKind, Consumer<String> onPick) {
        this(previousScreen, resourceKind, onPick, null);
    }

    /** 多选模式：完成时回调整组 id（保序）。 */
    public ResourcePickerScreen(Screen previousScreen, BProgram.ResourceKind resourceKind,
                                Consumer<String> onPick, @Nullable Consumer<List<String>> multiPick) {
        super(Component.literal(TITLE.getString(resourceKind.chineseName())));
        this.previousScreen = previousScreen;
        this.resourceKind = resourceKind;
        this.onPick = onPick;
        this.multiPick = multiPick;
    }

    private boolean multi() {
        return multiPick != null;
    }

    private int gridLeft() {
        return this.width / 2 - (COLS * CELL) / 2;
    }

    private int gridTop() {
        return multi() ? 66 : 54;
    }

    private int visibleRows() {
        return Math.max(1, (this.height - gridTop() - 44) / CELL);
    }

    @Override
    protected void init() {
        searchBox = new EditBox(this.font, this.width / 2 - 110, 26, 220, 16, Component.empty());
        searchBox.setMaxLength(128);
        searchBox.setBordered(false);
        searchBox.setTextColor(0xFF1B2432);
        searchBox.setTextShadow(false);
        searchBox.setHint(Component.literal(SEARCH.getString()));
        searchBox.setResponder(s -> applyFilter());
        this.addRenderableWidget(searchBox);
        this.setInitialFocus(searchBox);
        applyFilter();
    }

    private void applyFilter() {
        String raw = searchBox.getValue().trim().toLowerCase(Locale.ROOT);
        filtered.clear();
        for (ResourceIndex.Entry entry : ResourceIndex.forKind(resourceKind)) {
            if (raw.isEmpty()
                    || entry.searchText().contains(raw)
                    || PinyinSearch.matchesNormalized(entry.displayName(), raw)) {
                filtered.add(entry);
            }
        }
        scrollRow = 0;
    }

    @Override
    public void renderBackground(GuiGraphics graphics, int mx, int my, float partialTick) {
        // opaque light backdrop: no vanilla blur, no world show-through
    }

    @Override
    public void render(GuiGraphics graphics, int mx, int my, float partialTick) {
        hoveredEntry = null;
        graphics.fill(0, 0, this.width, this.height, 0xFFF2F4F8);
        drawCentered(graphics, this.title.getString(), this.width / 2, 10, 0xFF1B2432);
        graphics.fill(this.width / 2 - 114, 23, this.width / 2 + 114, 45, 0xFFFFFFFF);
        border(graphics, this.width / 2 - 114, 23, 228, 22, 0xFFD9DFEA);
        if (multi()) {
            drawCentered(graphics, MULTI_HINT.getString(), this.width / 2, 52, 0xFF6B7688);
        }

        renderGrid(graphics, mx, my);
        int bottom = this.height - 24;
        if (multi()) {
            drawCentered(graphics, COUNT.getString(filtered.size()) + " · " + DONE.getString()
                    + "(" + multiSelected.size() + ")", this.width / 2, this.height - 38, 0xFF6B7688);
            button(graphics, mx, my, this.width / 2 - 118, bottom, 70, CLEAR.getString(), 0xFF5B6472, this::clearMulti);
            button(graphics, mx, my, this.width / 2 - 42, bottom, 84,
                    DONE.getString() + "(" + multiSelected.size() + ")", GREEN, this::commitMulti);
            button(graphics, mx, my, this.width / 2 + 48, bottom, 70, CANCEL.getString(), 0xFF1B2432, this::onClose);
        } else {
            drawCentered(graphics, COUNT.getString(filtered.size()), this.width / 2,
                    this.height - 38, 0xFF6B7688);
            button(graphics, mx, my, this.width / 2 - 50, bottom, 100, CANCEL.getString(), 0xFF1B2432, this::onClose);
        }
        super.render(graphics, mx, my, partialTick);
        if (hoveredEntry != null) {
            graphics.renderTooltip(this.font, List.of(
                    Component.literal(hoveredEntry.displayName()),
                    Component.literal(hoveredEntry.sfmlId())), java.util.Optional.empty(), mx, my);
        }
    }

    private void button(GuiGraphics g, int mx, int my, int x, int y, int w, String label, int color, Runnable action) {
        boolean hover = mx >= x && mx < x + w && my >= y && my < y + 18;
        g.fill(x, y, x + w, y + 18, hover ? 0xFFE2E8F2 : 0xFFFFFFFF);
        border(g, x, y, w, 18, 0xFFD9DFEA);
        drawCentered(g, label, x + w / 2, y + 5, color);
    }

    private void clearMulti() {
        multiSelected.clear();
    }

    private void commitMulti() {
        if (multiPick == null) return;
        if (multiSelected.isEmpty()) {
            onClose();
            return;
        }
        multiPick.accept(new ArrayList<>(multiSelected));
        Minecraft.getInstance().setScreen(previousScreen);
    }

    /** 框选拖动时画半透明选区矩形（格对齐）。 */
    private void renderBand(GuiGraphics g, int left, int top) {
        if (!bandDragging || bandAnchor < 0 || bandCursor < 0) return;
        int[] a = cellRect(bandAnchor, left, top);
        int[] b = cellRect(bandCursor, left, top);
        if (a == null || b == null) return;
        int x1 = Math.min(a[0], b[0]), y1 = Math.min(a[1], b[1]);
        int x2 = Math.max(a[0], b[0]) + CELL, y2 = Math.max(a[1], b[1]) + CELL;
        g.fill(x1, y1, x2, y2, 0x300C8F58);
        border(g, x1, y1, x2 - x1, y2 - y1, GREEN);
    }

    private int @Nullable [] cellRect(int index, int left, int top) {
        int i = index - scrollRow * COLS;
        if (i < 0) return null;
        int cx = left + (i % COLS) * CELL;
        int cy = top + (i / COLS) * CELL;
        return new int[]{cx, cy};
    }

    private void renderGrid(GuiGraphics g, int mx, int my) {
        int left = gridLeft();
        int top = gridTop();
        int rows = visibleRows();
        g.fill(left - 3, top - 3, left + COLS * CELL + 3, top + rows * CELL + 3, 0xFFFFFFFF);
        border(g, left - 3, top - 3, COLS * CELL + 6, rows * CELL + 6, 0xFFD9DFEA);
        for (int i = 0; i < rows * COLS; i++) {
            int index = scrollRow * COLS + i;
            if (index >= filtered.size()) break;
            ResourceIndex.Entry entry = filtered.get(index);
            int cx = left + (i % COLS) * CELL;
            int cy = top + (i / COLS) * CELL;
            boolean over = mx >= cx && mx < cx + CELL && my >= cy && my < cy + CELL;
            boolean picked = multiSelected.contains(entry.sfmlId());
            g.fill(cx + 1, cy + 1, cx + CELL - 1, cy + CELL - 1, over ? 0x502F6FED : 0x90606B7E);
            ResourceIndex.renderIcon(g, this.font, entry, cx + 3, cy + 3);
            if (picked) {
                // 绿色描边 + 右下角小圆点双标记（描边在拖动中也醒目）
                border(g, cx, cy, CELL, CELL, GREEN);
                border(g, cx + 1, cy + 1, CELL - 2, CELL - 2, GREEN);
                g.fill(cx + CELL - 6, cy + CELL - 6, cx + CELL - 3, cy + CELL - 3, GREEN);
            }
            if (multi()) {
                // 右上角 ＋ 角标（手绘两笔，8×8 内）；已选项画 − 表示可取消
                int bx = cx + CELL - 10, by = cy + 2;
                boolean plusHover = mx >= bx && mx < bx + 8 && my >= by && my < by + 8;
                int pc = picked ? GREEN : (plusHover ? 0xFF2F6FED : 0xFF9AA6B8);
                g.fill(bx + 3, by + 1, bx + 5, by + 7, pc);
                if (!picked) g.fill(bx + 1, by + 3, bx + 7, by + 5, pc);
            }
            if (over) hoveredEntry = entry;
        }
        renderBand(g, left, top);
        // scrollbar
        int totalRows = (filtered.size() + COLS - 1) / COLS;
        if (totalRows > rows) {
            int barH = Math.max(16, rows * CELL * rows / totalRows);
            int barY = top + (rows * CELL - barH) * scrollRow / Math.max(1, totalRows - rows);
            g.fill(left + COLS * CELL + 3, barY, left + COLS * CELL + 5, barY + barH, 0xFFB9C4D2);
        }
    }

    private int cellIndexAt(double mx, double my) {
        int left = gridLeft();
        int top = gridTop();
        int rows = visibleRows();
        if (mx < left || mx >= left + COLS * CELL || my < top || my >= top + rows * CELL) return -1;
        int col = (int) ((mx - left) / CELL);
        int row = (int) ((my - top) / CELL);
        int index = scrollRow * COLS + row * COLS + col;
        return index >= 0 && index < filtered.size() ? index : -1;
    }

    private void toggleMulti(ResourceIndex.Entry entry) {
        if (!multiSelected.remove(entry.sfmlId())) multiSelected.add(entry.sfmlId());
    }

    @Override
    public boolean mouseClicked(double mx, double my, int button) {
        if (super.mouseClicked(mx, my, button)) return true;
        if (button != 0) return true;

        int index = cellIndexAt(mx, my);
        if (index >= 0) {
            ResourceIndex.Entry entry = filtered.get(index);
            int cx = gridLeft() + ((index - scrollRow * COLS) % COLS) * CELL;
            int cy = gridTop() + ((index - scrollRow * COLS) / COLS) * CELL;
            // 右上角 ＋/− 角标：勾选切换，留在本页
            if (multi() && mx >= cx + CELL - 10 && mx < cx + CELL - 2 && my >= cy + 2 && my < cy + 10) {
                toggleMulti(entry);
                return true;
            }
            if (multi()) {
                if (hasShiftDown()) {
                    toggleMulti(entry);
                    return true;
                }
                // 普通按下：先记录，松手时若无拖动才当"选这一个立即完成"
                //（给按住拖动框选让路）
                pressIndex = index;
                bandAnchor = index;
                bandDragging = false;
                bandCursor = index;
                return true;
            }
            onPick.accept(entry.sfmlId());
            Minecraft.getInstance().setScreen(previousScreen);
            return true;
        }
        pressIndex = -1;
        bandDragging = false;

        // bottom buttons (multi mode)
        if (multi()) {
            int bottom = this.height - 24;
            if (mx >= this.width / 2 - 118 && mx < this.width / 2 - 48 && my >= bottom && my < bottom + 18) {
                clearMulti();
                return true;
            }
            if (mx >= this.width / 2 - 42 && mx < this.width / 2 + 42 && my >= bottom && my < bottom + 18) {
                commitMulti();
                return true;
            }
            if (mx >= this.width / 2 + 48 && mx < this.width / 2 + 118 && my >= bottom && my < bottom + 18) {
                onClose();
                return true;
            }
        } else if (mx >= this.width / 2 - 50 && mx < this.width / 2 + 50
                && my >= this.height - 24 && my < this.height - 6) {
            onClose();
            return true;
        }
        return super.mouseClicked(mx, my, button);
    }

    @Override
    public boolean mouseDragged(double mx, double my, int button, double dx, double dy) {
        if (multi() && pressIndex >= 0 && button == 0) {
            int cur = cellIndexAt(mx, my);
            if (cur < 0) return true;
            if (!bandDragging && cur != pressIndex) bandDragging = true;
            bandCursor = cur;
            if (bandDragging) {
                // 矩形框选：锚点格与当前格之间的整片区域全部加入。
                // 拖动中滚轮会改 scrollRow，相对行/列可能为负——用 floorMod 保证
                // 行列号不出错（负数取模会把列算成负、选错一片）
                int ac = Math.floorMod(bandAnchor - scrollRow * COLS, COLS), ar = Math.floorDiv(bandAnchor - scrollRow * COLS, COLS);
                int cc = Math.floorMod(cur - scrollRow * COLS, COLS), cr = Math.floorDiv(cur - scrollRow * COLS, COLS);
                int c1 = Math.min(ac, cc), c2 = Math.max(ac, cc);
                int r1 = Math.min(ar, cr), r2 = Math.max(ar, cr);
                for (int r = Math.max(0, r1); r <= r2; r++) {
                    for (int c = c1; c <= c2; c++) {
                        int idx = scrollRow * COLS + r * COLS + c;
                        if (idx >= 0 && idx < filtered.size()) multiSelected.add(filtered.get(idx).sfmlId());
                    }
                }
            }
            return true;
        }
        return super.mouseDragged(mx, my, button, dx, dy);
    }

    @Override
    public boolean mouseReleased(double mx, double my, int button) {
        if (multi() && pressIndex >= 0 && button == 0) {
            if (!bandDragging) {
                // 没拖动：按"选这一个立即完成"处理（多选回调收到单元素列表）
                ResourceIndex.Entry entry = filtered.get(pressIndex);
                multiPick.accept(List.of(entry.sfmlId()));
                Minecraft.getInstance().setScreen(previousScreen);
            }
            pressIndex = -1;
            bandAnchor = -1;
            bandDragging = false;
            bandCursor = -1;
            return true;
        }
        return super.mouseReleased(mx, my, button);
    }

    @Override
    public boolean mouseScrolled(double mx, double my, double scrollX, double scrollY) {
        int totalRows = (filtered.size() + COLS - 1) / COLS;
        int maxScroll = Math.max(0, totalRows - visibleRows());
        scrollRow = Mth.clamp(scrollRow - (int) Math.signum(scrollY), 0, maxScroll);
        return true;
    }

    @Override
    public void onClose() {
        Minecraft.getInstance().setScreen(previousScreen);
    }

    private static void border(GuiGraphics g, int x, int y, int w, int h, int color) {
        g.fill(x, y, x + w, y + 1, color);
        g.fill(x, y + h - 1, x + w, y + h, color);
        g.fill(x, y, x + 1, y + h, color);
        g.fill(x + w - 1, y, x + w, y + h, color);
    }

    private void drawCentered(GuiGraphics g, String text, int centerX, int y, int color) {
        g.drawString(font, text, centerX - font.width(text) / 2, y, color, false);
    }
}
