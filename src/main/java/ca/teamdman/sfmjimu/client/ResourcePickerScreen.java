package ca.teamdman.sfmjimu.client;

import ca.teamdman.sfmjimu.client.blocks.model.BProgram;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.util.Mth;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.function.Consumer;

/**
 * Built-in fallback resource catalog used when JEI is not installed (with JEI,
 * players drag from JEI's own list instead). Light glass theme: searchable icon
 * grid. The resource category is chosen before this screen opens, so this page
 * never mixes category selection with concrete resources.
 */
public class ResourcePickerScreen extends Screen {
    private static final Loc SEARCH = new Loc("gui.sfmjimu.blocks.picker.search", "搜索...");
    private static final Loc CANCEL = new Loc("gui.sfmjimu.blocks.close", "关闭");

    private static final int CELL = 22;
    private static final int COLS = 11;

    private final Screen previousScreen;
    private final BProgram.ResourceKind resourceKind;
    private final Consumer<String> onPick;

    private final List<ResourceIndex.Entry> filtered = new ArrayList<>();
    private EditBox searchBox;
    private int scrollRow = 0;
    private ResourceIndex.Entry hoveredEntry;

    public ResourcePickerScreen(Screen previousScreen, BProgram.ResourceKind resourceKind, Consumer<String> onPick) {
        super(Component.literal("选择" + resourceKind.chineseName));
        this.previousScreen = previousScreen;
        this.resourceKind = resourceKind;
        this.onPick = onPick;
    }

    private int gridLeft() {
        return this.width / 2 - (COLS * CELL) / 2;
    }

    private int gridTop() {
        return 54;
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

        renderGrid(graphics, mx, my);
        drawCentered(graphics, filtered.size() + " 项", this.width / 2,
                this.height - 38, 0xFF6B7688);
        boolean closeHovered = mx >= this.width / 2 - 50 && mx < this.width / 2 + 50
                && my >= this.height - 24 && my < this.height - 6;
        graphics.fill(this.width / 2 - 50, this.height - 24, this.width / 2 + 50, this.height - 6,
                closeHovered ? 0xFFE2E8F2 : 0xFFFFFFFF);
        border(graphics, this.width / 2 - 50, this.height - 24, 100, 18, 0xFFD9DFEA);
        drawCentered(graphics, CANCEL.getString(), this.width / 2, this.height - 19, 0xFF1B2432);
        super.render(graphics, mx, my, partialTick);
        if (hoveredEntry != null) {
            graphics.renderTooltip(this.font, List.of(
                    Component.literal(hoveredEntry.displayName()),
                    Component.literal(hoveredEntry.sfmlId())), java.util.Optional.empty(), mx, my);
        }
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
            g.fill(cx + 1, cy + 1, cx + CELL - 1, cy + CELL - 1, over ? 0x502F6FED : 0x90606B7E);
            ResourceIndex.renderIcon(g, this.font, entry, cx + 3, cy + 3);
            if (over) hoveredEntry = entry;
        }
        // scrollbar
        int totalRows = (filtered.size() + COLS - 1) / COLS;
        if (totalRows > rows) {
            int barH = Math.max(16, rows * CELL * rows / totalRows);
            int barY = top + (rows * CELL - barH) * scrollRow / Math.max(1, totalRows - rows);
            g.fill(left + COLS * CELL + 3, barY, left + COLS * CELL + 5, barY + barH, 0xFFB9C4D2);
        }
    }

    @Override
    public boolean mouseClicked(double mx, double my, int button) {
        if (super.mouseClicked(mx, my, button)) return true;

        int left = gridLeft();
        int top = gridTop();
        int rows = visibleRows();
        if (mx >= left && mx < left + COLS * CELL && my >= top && my < top + rows * CELL) {
            int col = (int) ((mx - left) / CELL);
            int row = (int) ((my - top) / CELL);
            int index = scrollRow * COLS + row * COLS + col;
            if (index >= 0 && index < filtered.size()) {
                onPick.accept(filtered.get(index).sfmlId());
                Minecraft.getInstance().setScreen(previousScreen);
                return true;
            }
        }

        // close button
        if (mx >= this.width / 2 - 50 && mx < this.width / 2 + 50
                && my >= this.height - 24 && my < this.height - 6) {
            onClose();
            return true;
        }
        return super.mouseClicked(mx, my, button);
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

    // kept for parity with the rest of the codebase's Loc helper
    private record Loc(String key, String fallback) {
        public String getString() {
            return net.minecraft.client.resources.language.I18n.get(key);
        }
    }
}
