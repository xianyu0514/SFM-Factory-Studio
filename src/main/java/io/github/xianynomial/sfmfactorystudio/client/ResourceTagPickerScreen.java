package io.github.xianynomial.sfmfactorystudio.client;

import io.github.xianynomial.sfmfactorystudio.client.blocks.model.BProgram;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.util.Mth;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.function.Consumer;

/** Pick an item, then choose one of the exact item/block tags SFM sees for it. */
public final class ResourceTagPickerScreen extends Screen {
    private enum Mode {ITEM, ITEM_TAGS, ALL_TAGS}
    private enum TagFilter {RECOMMENDED, ALL, COMMON, MINECRAFT, MOD}

    private static final int CELL = 22;
    private static final int TAG_ROW = 28;
    private static final int PREVIEW_CELL = 18;
    private static final int PREVIEW_COLS = 14;
    private static final int PREVIEW_ROWS = 10;
    private static final int PREVIEW_PAGE_SIZE = TagPreviewPaging.PAGE_SIZE;
    private static final int PREVIEW_W = PREVIEW_COLS * PREVIEW_CELL + 8;
    private static final int PREVIEW_H = 235;
    private static final int PANE_GAP = 8;
    private static final int FILTER_Y = 51;
    private static final int FILTER_H = 18;
    private static final String[] FILTER_LABELS = {"推荐", "全部", "通用", "原版", "模组"};
    private static final int[] FILTER_WIDTHS = {44, 44, 50, 50, 50};
    private static final int FILTER_GAP = 4;

    private final Screen previousScreen;
    private final Consumer<String> onPick;
    private final boolean startWithAllTags;
    private final List<ResourceIndex.Entry> filteredItems = new ArrayList<>();
    private final List<ResourceTagIndex.TagEntry> filteredTags = new ArrayList<>();

    private Mode mode;
    private ResourceIndex.Entry selectedItem;
    private EditBox searchBox;
    private int scroll;
    private TagFilter tagFilter = TagFilter.RECOMMENDED;
    private ResourceTagIndex.TagEntry previewTag;
    private int previewPage;
    private ResourceIndex.Entry hoveredResource;
    private ResourceTagIndex.TagEntry hoveredTag;

    public ResourceTagPickerScreen(Screen previousScreen, boolean allTags, Consumer<String> onPick) {
        super(Component.literal(allTags ? "搜索全部资源标签" : "通过物品选择资源标签"));
        this.previousScreen = previousScreen;
        this.startWithAllTags = allTags;
        this.mode = allTags ? Mode.ALL_TAGS : Mode.ITEM;
        this.onPick = onPick;
    }

    @Override
    protected void init() {
        String old = searchBox == null ? "" : searchBox.getValue();
        searchBox = new EditBox(this.font, this.width / 2 - 150, 27, 200, 17, Component.empty());
        searchBox.setMaxLength(128);
        searchBox.setBordered(false);
        searchBox.setTextColor(0xFF1B2432);
        searchBox.setTextShadow(false);
        searchBox.setHint(Component.literal(mode == Mode.ITEM ? "搜索物品名称或拼音…" : "搜索中文名称或原标签…"));
        searchBox.setValue(old);
        searchBox.setResponder(s -> applyFilter(true));
        addRenderableWidget(searchBox);
        setInitialFocus(searchBox);
        applyFilter(false);
    }

    private int columns() {
        return Math.max(4, Math.min(12, (width - 28) / CELL));
    }

    private int gridLeft() {
        return (width - columns() * CELL) / 2;
    }

    private int contentTop() {
        return mode == Mode.ITEM ? 57 : 76;
    }

    private int visibleItemRows() {
        return Math.max(1, (height - contentTop() - 40) / CELL);
    }

    private int tagWidth() {
        return Math.max(100, browserWidth() - PREVIEW_W - PANE_GAP);
    }

    private int tagLeft() {
        return (width - browserWidth()) / 2;
    }

    private int browserWidth() {
        return Math.min(720, Math.max(220, width - 16));
    }

    private int previewLeft() {
        return tagLeft() + tagWidth() + PANE_GAP;
    }

    private int visibleTagRows() {
        return Math.max(1, (height - contentTop() - 40) / TAG_ROW);
    }

    private void applyFilter(boolean resetScroll) {
        if (searchBox == null) return;
        String query = searchBox.getValue().trim().toLowerCase(Locale.ROOT);
        filteredItems.clear();
        filteredTags.clear();
        if (mode == Mode.ITEM) {
            for (ResourceIndex.Entry entry : ResourceIndex.forKind(BProgram.ResourceKind.ITEM)) {
                if (query.isEmpty() || entry.searchText().contains(query)
                        || PinyinSearch.matchesNormalized(entry.displayName(), query)) {
                    filteredItems.add(entry);
                }
            }
        } else {
            List<ResourceTagIndex.TagEntry> source = mode == Mode.ALL_TAGS
                    ? ResourceTagIndex.all()
                    : selectedItem == null ? List.of() : ResourceTagIndex.forItem(selectedItem.sfmlId());
            for (ResourceTagIndex.TagEntry entry : source) {
                if (matchesTagFilter(entry)
                        && (query.isEmpty() || entry.searchText().contains(query)
                        || PinyinSearch.matchesNormalized(entry.displayName(), query))) {
                    filteredTags.add(entry);
                }
            }
            filteredTags.sort(Comparator.comparingInt(ResourceTagPickerScreen::recommendationScore)
                    .thenComparing(ResourceTagIndex.TagEntry::displayName)
                    .thenComparing(entry -> entry.id().toString()));
            if (previewTag == null || !filteredTags.contains(previewTag)) {
                setPreviewTag(filteredTags.isEmpty() ? null : filteredTags.get(0));
            } else {
                previewPage = Mth.clamp(previewPage, 0, maxPreviewPage());
            }
        }
        if (resetScroll) scroll = 0;
    }

    private void setPreviewTag(ResourceTagIndex.TagEntry entry) {
        if (previewTag == entry) return;
        previewTag = entry;
        previewPage = 0;
    }

    private int maxPreviewPage() {
        int total = previewTag == null ? 0 : previewTag.memberIds().size();
        return TagPreviewPaging.pageCount(total) - 1;
    }

    private boolean matchesTagFilter(ResourceTagIndex.TagEntry entry) {
        String namespace = entry.id().getNamespace();
        return switch (tagFilter) {
            case ALL -> true;
            case COMMON -> namespace.equals("c") || namespace.equals("forge");
            case MINECRAFT -> namespace.equals("minecraft");
            case MOD -> !namespace.equals("c") && !namespace.equals("forge") && !namespace.equals("minecraft");
            case RECOMMENDED -> TagDisplayNames.isResourceCategory(entry.id())
                    || (!namespace.equals("minecraft") && !namespace.equals("c")
                    && !namespace.equals("forge") && entry.resourceCount() <= 64);
        };
    }

    /** Category tags first, then understandable functional tags, with very broad groups last. */
    static int recommendationScore(ResourceTagIndex.TagEntry entry) {
        String namespace = entry.id().getNamespace();
        int score;
        if (TagDisplayNames.isResourceCategory(entry.id()) && entry.id().getPath().contains("/")) score = 0;
        else if (TagDisplayNames.isResourceCategory(entry.id())) score = 10;
        else if (namespace.equals("c") || namespace.equals("forge")) score = 18;
        else if (TagDisplayNames.hasFriendlyName(entry.id())) score = 24;
        else if (entry.resourceCount() <= 16) score = 28;
        else if (namespace.equals("minecraft")) score = 34;
        else score = 44;
        if (entry.resourceCount() > 10_000) score += 18;
        else if (entry.resourceCount() > 2_000) score += 12;
        else if (entry.resourceCount() > 256) score += 6;
        return score;
    }

    @Override
    public void renderBackground(GuiGraphics graphics, int mx, int my, float partialTick) {
        // Opaque editor page; never invoke the vanilla blur pass.
    }

    @Override
    public void render(GuiGraphics g, int mx, int my, float partialTick) {
        hoveredResource = null;
        hoveredTag = null;
        g.fill(0, 0, width, height, 0xFFF2F4F8);
        String heading = switch (mode) {
            case ITEM -> "先选择一个物品";
            case ITEM_TAGS -> selectedItem == null ? "选择资源标签" : selectedItem.displayName() + " 所属的资源标签";
            case ALL_TAGS -> "搜索全部资源标签";
        };
        drawCentered(g, font.plainSubstrByWidth(heading, width - 24), width / 2, 9, 0xFF1B2432);

        // Search field card and the global-search shortcut.
        g.fill(width / 2 - 154, 24, width / 2 + 54, 47, 0xFFFFFFFF);
        border(g, width / 2 - 154, 24, 208, 23, 0xFFD9DFEA);
        if (mode == Mode.ITEM) {
            drawButton(g, width / 2 + 60, 25, 100, 20, "搜索全部特征", mx, my);
        } else {
            renderTagFilters(g, mx, my);
        }

        if (mode == Mode.ITEM) renderItems(g, mx, my);
        else renderTags(g, mx, my);

        if (mode != Mode.ITEM && !startWithAllTags) {
            drawButton(g, width / 2 - 108, height - 27, 100, 20, "返回选物品", mx, my);
            drawButton(g, width / 2 + 8, height - 27, 100, 20, "关闭", mx, my);
        } else {
            drawButton(g, width / 2 - 50, height - 27, 100, 20, "关闭", mx, my);
        }
        super.render(g, mx, my, partialTick);
        renderHoveredTooltip(g, mx, my);
    }

    private void renderItems(GuiGraphics g, int mx, int my) {
        int cols = columns(), left = gridLeft(), top = contentTop(), rows = visibleItemRows();
        g.fill(left - 3, top - 3, left + cols * CELL + 3, top + rows * CELL + 3, 0xFFFFFFFF);
        border(g, left - 3, top - 3, cols * CELL + 6, rows * CELL + 6, 0xFFD9DFEA);
        for (int i = 0; i < rows * cols; i++) {
            int index = scroll * cols + i;
            if (index >= filteredItems.size()) break;
            ResourceIndex.Entry entry = filteredItems.get(index);
            int x = left + (i % cols) * CELL, y = top + (i / cols) * CELL;
            boolean over = mx >= x && mx < x + CELL && my >= y && my < y + CELL;
            g.fill(x + 1, y + 1, x + CELL - 1, y + CELL - 1, over ? 0x502F6FED : 0x90606B7E);
            ResourceIndex.renderIcon(g, font, entry, x + 3, y + 3);
            if (over) hoveredResource = entry;
        }
        drawCentered(g, filteredItems.size() + " 个物品", width / 2, height - 40, 0xFF6B7688);
    }

    private void renderTags(GuiGraphics g, int mx, int my) {
        int left = tagLeft(), top = contentTop(), rows = visibleTagRows(), w = tagWidth();
        g.fill(left - 2, top - 2, left + w + 2, top + rows * TAG_ROW, 0xFFFFFFFF);
        border(g, left - 2, top - 2, w + 4, rows * TAG_ROW + 2, 0xFFD9DFEA);
        for (int i = 0; i < rows; i++) {
            int index = scroll + i;
            if (index >= filteredTags.size()) break;
            ResourceTagIndex.TagEntry entry = filteredTags.get(index);
            int y = top + i * TAG_ROW;
            boolean over = mx >= left && mx < left + w && my >= y && my < y + TAG_ROW - 2;
            int sourceColor = sourceColor(entry);
            boolean active = entry == previewTag;
            g.fill(left, y, left + w, y + TAG_ROW - 2,
                    active ? 0xFFDCE9FC : over ? 0xFFEDF3FC : 0xFFFFFFFF);
            g.fill(left, y + 1, left + 3, y + TAG_ROW - 3, sourceColor);

            String source = font.plainSubstrByWidth(TagDisplayNames.sourceName(entry.id()), 42);
            int sourceW = Math.min(52, font.width(source) + 8);
            g.fill(left + 7, y + 7, left + 7 + sourceW, y + 20, sourceTint(entry));
            g.drawString(font, source, left + 11, y + 9, sourceColor, false);

            String count = shortCount(entry.resourceCount());
            int countW = font.width(count);
            int titleX = left + 12 + sourceW;
            int titleW = Math.max(12, w - (titleX - left) - countW - 12);
            String display = font.plainSubstrByWidth(entry.displayName(), titleW);
            g.drawString(font, display, titleX, y + 9, 0xFF1B2432, false);
            g.drawString(font, count, left + w - countW - 7, y + 9,
                    scopeColor(entry.resourceCount()), false);
            if (over) {
                hoveredTag = entry;
                setPreviewTag(entry);
            }
        }
        if (filteredTags.isEmpty()) {
            String empty = tagFilter == TagFilter.RECOMMENDED
                    ? "没有推荐项；点“全部”可查看所有语法有效的特征"
                    : mode == Mode.ITEM_TAGS ? "这个物品没有可用于SFM的资源标签" : "没有匹配的资源标签";
            drawCentered(g, font.plainSubstrByWidth(empty, w - 12), left + w / 2, top + 20, 0xFF6B7688);
        }
        renderMemberPreview(g, mx, my);
        String summary = filteredTags.size() + " 个特征 · 点击左侧特征即可选用";
        drawCentered(g, summary, width / 2, height - 40, 0xFF6B7688);
    }

    private void renderMemberPreview(GuiGraphics g, int mx, int my) {
        int x = previewLeft(), y = contentTop(), w = PREVIEW_W;
        g.fill(x, y, x + w, y + PREVIEW_H, 0xFFFFFFFF);
        border(g, x, y, w, PREVIEW_H, 0xFFD0D8E5);
        String title = previewTag == null ? "标签包含的物品" : previewTag.displayName();
        drawCentered(g, font.plainSubstrByWidth(title, w - 12), x + w / 2, y + 6, 0xFF1B2432);

        int total = previewTag == null ? 0 : previewTag.memberIds().size();
        int start = TagPreviewPaging.start(previewPage, total);
        int end = TagPreviewPaging.end(previewPage, total);
        String range = total == 0 ? "暂无物品" : (start + 1) + "–" + end + " / 共 " + total + " 种";
        drawCentered(g, range, x + w / 2, y + 17, 0xFF6B7688);

        int gridX = x + 4, gridY = y + 30;
        for (int i = 0; i < PREVIEW_PAGE_SIZE; i++) {
            int cx = gridX + (i % PREVIEW_COLS) * PREVIEW_CELL;
            int cy = gridY + (i / PREVIEW_COLS) * PREVIEW_CELL;
            boolean over = inside(mx, my, cx, cy, PREVIEW_CELL, PREVIEW_CELL);
            g.fill(cx, cy, cx + PREVIEW_CELL - 1, cy + PREVIEW_CELL - 1,
                    over ? 0xFFDCE9FC : 0xFFF1F3F7);
            border(g, cx, cy, PREVIEW_CELL - 1, PREVIEW_CELL - 1, 0xFFD7DEE9);
            int memberIndex = start + i;
            if (previewTag == null || memberIndex >= end) continue;
            ResourceIndex.Entry entry = ResourceIndex.lookup(previewTag.memberIds().get(memberIndex));
            if (entry == null) continue;
            ResourceIndex.renderIcon(g, font, entry, cx + 1, cy + 1);
            if (over) hoveredResource = entry;
        }

        int by = previewButtonsY();
        drawCompactButton(g, x + 6, by, 50, 18, "上一页", previewPage > 0, mx, my);
        drawCentered(g, (previewPage + 1) + " / " + (maxPreviewPage() + 1), x + w / 2, by + 5, 0xFF5C6779);
        drawCompactButton(g, x + w - 56, by, 50, 18, "下一页", previewPage < maxPreviewPage(), mx, my);
    }

    private int previewButtonsY() {
        return contentTop() + 30 + PREVIEW_ROWS * PREVIEW_CELL + 4;
    }

    private void renderHoveredTooltip(GuiGraphics g, int mx, int my) {
        if (hoveredResource != null) {
            List<Component> lines = new ArrayList<>();
            lines.add(Component.literal(hoveredResource.displayName()));
            lines.add(Component.literal(hoveredResource.sfmlId()));
            if (mode == Mode.ITEM) {
                lines.add(Component.literal(ResourceTagIndex.forItem(hoveredResource.sfmlId()).size()
                        + " 个可用资源标签"));
            } else {
                lines.add(Component.literal("属于当前资源标签"));
            }
            g.renderTooltip(font, lines, Optional.empty(), mx, my);
            return;
        }
        if (hoveredTag != null) {
            List<Component> tip = new ArrayList<>();
            tip.add(Component.literal(hoveredTag.displayName()));
            tip.add(Component.literal("来源：" + TagDisplayNames.sourceName(hoveredTag.id())));
            tip.add(Component.literal(scopeText(hoveredTag.resourceCount())));
            tip.add(Component.literal("保存时编译为 #" + hoveredTag.id()));
            if (hoveredTag.resourceCount() > 2_000) {
                tip.add(Component.literal("范围很广，请确认不会搬运多余物品"));
            }
            g.renderTooltip(font, tip, Optional.empty(), mx, my);
        }
    }

    private void renderTagFilters(GuiGraphics g, int mx, int my) {
        int x = filterBarLeft();
        for (int i = 0; i < FILTER_LABELS.length; i++) {
            int w = FILTER_WIDTHS[i];
            boolean active = tagFilter.ordinal() == i;
            boolean hover = inside(mx, my, x, FILTER_Y, w, FILTER_H);
            int fill = active ? 0xFF2F6FED : hover ? 0xFFE3ECFB : 0xFFFFFFFF;
            int text = active ? 0xFFFFFFFF : 0xFF344054;
            g.fill(x, FILTER_Y, x + w, FILTER_Y + FILTER_H, fill);
            border(g, x, FILTER_Y, w, FILTER_H, active ? 0xFF2F6FED : 0xFFD0D8E5);
            drawCentered(g, FILTER_LABELS[i], x + w / 2, FILTER_Y + 5, text);
            x += w + FILTER_GAP;
        }
    }

    private int filterBarLeft() {
        int total = FILTER_GAP * (FILTER_WIDTHS.length - 1);
        for (int w : FILTER_WIDTHS) total += w;
        return (width - total) / 2;
    }

    private static int sourceColor(ResourceTagIndex.TagEntry entry) {
        return switch (entry.id().getNamespace()) {
            case "c", "forge" -> 0xFF18794E;
            case "minecraft" -> 0xFF2F6FED;
            default -> 0xFF7A4CC2;
        };
    }

    private static int sourceTint(ResourceTagIndex.TagEntry entry) {
        return switch (entry.id().getNamespace()) {
            case "c", "forge" -> 0xFFE8F5EE;
            case "minecraft" -> 0xFFEAF1FF;
            default -> 0xFFF2ECFB;
        };
    }

    static String scopeText(int count) {
        String countText = shortCount(count);
        if (count <= 8) return "精确 · " + countText;
        if (count <= 128) return "较精确 · " + countText;
        if (count <= 2_000) return "较广 · " + countText;
        return "很广 · " + countText;
    }

    private static String shortCount(int count) {
        if (count >= 10_000) {
            double wan = count / 10_000.0;
            return (wan >= 10 ? String.valueOf((int) wan) : String.format(Locale.ROOT, "%.1f", wan)) + "万种";
        }
        return count + "种";
    }

    private static int scopeColor(int count) {
        if (count <= 8) return 0xFF18794E;
        if (count <= 128) return 0xFF2F6FED;
        if (count <= 2_000) return 0xFF8A5A00;
        return 0xFFB54708;
    }

    @Override
    public boolean mouseClicked(double mx, double my, int button) {
        if (super.mouseClicked(mx, my, button)) return true;
        if (button != 0) return true;
        if (mode == Mode.ITEM && inside(mx, my, width / 2 + 60, 25, 100, 20)) {
            mode = Mode.ALL_TAGS;
            tagFilter = TagFilter.RECOMMENDED;
            searchBox.setValue("");
            searchBox.setHint(Component.literal("搜索中文名称或原标签…"));
            applyFilter(true);
            return true;
        }

        if (mode != Mode.ITEM) {
            int x = filterBarLeft();
            for (int i = 0; i < FILTER_LABELS.length; i++) {
                int w = FILTER_WIDTHS[i];
                if (inside(mx, my, x, FILTER_Y, w, FILTER_H)) {
                    tagFilter = TagFilter.values()[i];
                    applyFilter(true);
                    return true;
                }
                x += w + FILTER_GAP;
            }

            int previewX = previewLeft(), previewY = previewButtonsY();
            if (inside(mx, my, previewX + 6, previewY, 50, 18)) {
                if (previewPage > 0) previewPage--;
                return true;
            }
            if (inside(mx, my, previewX + PREVIEW_W - 56, previewY, 50, 18)) {
                if (previewPage < maxPreviewPage()) previewPage++;
                return true;
            }
            if (inside(mx, my, previewX, contentTop(), PREVIEW_W, PREVIEW_H)) return true;
        }

        if (mode == Mode.ITEM) {
            int cols = columns(), left = gridLeft(), top = contentTop(), rows = visibleItemRows();
            if (inside(mx, my, left, top, cols * CELL, rows * CELL)) {
                int col = (int) ((mx - left) / CELL), row = (int) ((my - top) / CELL);
                int index = scroll * cols + row * cols + col;
                if (index >= 0 && index < filteredItems.size()) {
                    selectedItem = filteredItems.get(index);
                    mode = Mode.ITEM_TAGS;
                    tagFilter = TagFilter.RECOMMENDED;
                    searchBox.setValue("");
                    searchBox.setHint(Component.literal("搜索这个物品的资源标签…"));
                    applyFilter(true);
                }
                return true;
            }
        } else {
            int left = tagLeft(), top = contentTop(), rows = visibleTagRows();
            if (inside(mx, my, left, top, tagWidth(), rows * TAG_ROW)) {
                int index = scroll + (int) ((my - top) / TAG_ROW);
                if (index >= 0 && index < filteredTags.size()) {
                    onPick.accept(filteredTags.get(index).id().toString());
                    Minecraft.getInstance().setScreen(previousScreen);
                }
                return true;
            }
        }

        if (mode != Mode.ITEM && !startWithAllTags
                && inside(mx, my, width / 2 - 108, height - 27, 100, 20)) {
            mode = Mode.ITEM;
            selectedItem = null;
            searchBox.setValue("");
            searchBox.setHint(Component.literal("搜索物品名称或拼音…"));
            applyFilter(true);
            return true;
        }
        boolean close = mode != Mode.ITEM && !startWithAllTags
                ? inside(mx, my, width / 2 + 8, height - 27, 100, 20)
                : inside(mx, my, width / 2 - 50, height - 27, 100, 20);
        if (close) onClose();
        return true;
    }

    @Override
    public boolean mouseScrolled(double mx, double my, double scrollX, double scrollY) {
        if (mode != Mode.ITEM && inside(mx, my, previewLeft(), contentTop(), PREVIEW_W, PREVIEW_H)) {
            previewPage = Mth.clamp(previewPage - (int) Math.signum(scrollY), 0, maxPreviewPage());
            return true;
        }
        int totalRows;
        int visible;
        if (mode == Mode.ITEM) {
            totalRows = (filteredItems.size() + columns() - 1) / columns();
            visible = visibleItemRows();
        } else {
            totalRows = filteredTags.size();
            visible = visibleTagRows();
        }
        scroll = Mth.clamp(scroll - (int) Math.signum(scrollY), 0, Math.max(0, totalRows - visible));
        return true;
    }

    @Override
    public void onClose() {
        Minecraft.getInstance().setScreen(previousScreen);
    }

    private static boolean inside(double mx, double my, int x, int y, int w, int h) {
        return mx >= x && mx < x + w && my >= y && my < y + h;
    }

    private void drawButton(GuiGraphics g, int x, int y, int w, int h, String label, int mx, int my) {
        boolean hover = inside(mx, my, x, y, w, h);
        g.fill(x, y, x + w, y + h, hover ? 0xFFE3ECFB : 0xFFFFFFFF);
        border(g, x, y, w, h, hover ? 0xFF8FB4E8 : 0xFFD9DFEA);
        drawCentered(g, label, x + w / 2, y + 6, 0xFF1B2432);
    }

    private void drawCompactButton(GuiGraphics g, int x, int y, int w, int h, String label,
                                   boolean enabled, int mx, int my) {
        boolean hover = enabled && inside(mx, my, x, y, w, h);
        g.fill(x, y, x + w, y + h, enabled ? hover ? 0xFFE3ECFB : 0xFFFFFFFF : 0xFFF1F3F7);
        border(g, x, y, w, h, enabled ? 0xFFC7D2E0 : 0xFFE0E4EA);
        drawCentered(g, label, x + w / 2, y + 5, enabled ? 0xFF344054 : 0xFFA0A8B4);
    }

    /** Minecraft's centered helper always adds a shadow; this UI uses crisp one-pass text. */
    private void drawCentered(GuiGraphics g, String text, int centerX, int y, int color) {
        g.drawString(font, text, centerX - font.width(text) / 2, y, color, false);
    }

    private static void border(GuiGraphics g, int x, int y, int w, int h, int color) {
        g.fill(x, y, x + w, y + 1, color);
        g.fill(x, y + h - 1, x + w, y + h, color);
        g.fill(x, y, x + 1, y + h, color);
        g.fill(x + w - 1, y, x + w, y + h, color);
    }
}
