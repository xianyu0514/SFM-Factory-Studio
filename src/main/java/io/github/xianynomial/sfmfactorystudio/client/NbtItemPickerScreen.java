package io.github.xianynomial.sfmfactorystudio.client;

import io.github.xianynomial.sfmfactorystudio.client.blocks.ComponentNames;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.ItemStack;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.function.Consumer;

/**
 * NBT 组件可视化选择器：背包物品 / 全部物品 双页签，图标网格 + 拼音搜索；
 * 选中物品后列出其全部非默认数据组件（中文名 + 值预览），点选回调完整目标
 * （组件id[/选择器]，冒号写点）。输入分发 super 优先。
 */
public class NbtItemPickerScreen extends Screen {
    private static final int SLOT = 20, COLS = 12, GRID_W = COLS * SLOT;
    private static final int PANEL_W = GRID_W + 24, ROW_H = 18;
    // 行距大幅拉开，各层绝不重叠
    private static final int PANEL_TOP = 40;
    private static final int TAB_Y = 56;      // 页签（面板顶+16）
    private static final int SEARCH_Y = 84;   // 搜索框（页签+28）
    private static final int CONTENT_Y = 116; // 内容区（搜索+32）

    /** 可见行数跟着窗口高度走，大屏多显示、小屏也不至于挤没。 */
    private int maxRows() {
        int byHeight = (this.height - CONTENT_Y - 60) / Math.max(ROW_H, SLOT);
        return Math.max(6, Math.min(16, byHeight));
    }

    private final Screen parent;
    private final Consumer<String> onPick;
    private final List<ItemStack> inventory = new ArrayList<>();
    private static List<ItemStack> allItemsCache = null;
    private final List<ItemStack> allItems;
    private List<ItemStack> shownItems = new ArrayList<>();

    private record PickRow(String display, String target) {}
    private final List<PickRow> pickRows = new ArrayList<>();
    private boolean componentPage = false;
    private boolean allTab = false;
    private ItemStack selected = ItemStack.EMPTY;
    private EditBox search;
    private String query = "";
    private int itemScroll = 0, compScroll = 0;

    public NbtItemPickerScreen(Screen parent, Consumer<String> onPick) {
        super(Component.literal("选择 NBT 组件"));
        this.parent = parent;
        this.onPick = onPick;
        var player = Minecraft.getInstance().player;
        if (player != null) {
            var inv = player.getInventory();
            for (int i = 0; i < inv.getContainerSize(); i++) {
                ItemStack s = inv.getItem(i);
                if (!s.isEmpty()) inventory.add(s.copy());
            }
        }
        if (allItemsCache == null) {
            allItemsCache = new ArrayList<>();
            for (var item : BuiltInRegistries.ITEM) allItemsCache.add(new ItemStack(item));
        }
        allItems = allItemsCache;
    }

    @Override
    protected void init() {
        int px = panelX();
        search = new EditBox(this.font, px + 8, SEARCH_Y, PANEL_W - 16, 14, Component.literal(""));
        search.setBordered(false); // 去掉 EditBox 黑色边框——它的自绘背景会盖住我们的面板和页签
        search.setTextColor(0xFF1B2432);
        search.setHint(Component.literal("搜索物品名称或拼音…"));
        search.setResponder(q -> {
            query = q.trim().toLowerCase(Locale.ROOT);
            itemScroll = 0;
            compScroll = 0;
            refilter();
        });
        addRenderableWidget(search);
        setInitialFocus(search);
        refilter();
    }

    private int panelX() { return width / 2 - PANEL_W / 2; }

    private void refilter() {
        List<ItemStack> source = allTab ? allItems : inventory;
        shownItems = new ArrayList<>();
        if (query.isEmpty()) { shownItems.addAll(source); return; }
        for (ItemStack s : source) {
            String name = s.getHoverName().getString();
            if (name.toLowerCase(Locale.ROOT).contains(query)
                    || PinyinSearch.matchesNormalized(name, query)) shownItems.add(s);
        }
    }

    private void openComponents(ItemStack stack) {
        selected = stack;
        pickRows.clear();
        componentPage = true;
        compScroll = 0;
        BuiltInRegistries.DATA_COMPONENT_TYPE.forEach(type -> {
            if (io.github.xianynomial.sfmfactorystudio.net.NbtMatcherHook.hasNonDefault(stack, type)) {
                String id = String.valueOf(BuiltInRegistries.DATA_COMPONENT_TYPE.getKey(type));
                Object value = stack.getComponents().get(type);
                if (value instanceof net.minecraft.world.item.enchantment.ItemEnchantments ench) {
                    for (var e : ench.entrySet()) {
                        var k = e.getKey().unwrapKey();
                        if (k.isEmpty()) continue;
                        var fullLoc = k.get().location();
                        // minecraft 命名空间→纯路径；其他→ns__path（双下划线编码命名空间）
                        String target = fullLoc.getNamespace().equals("minecraft")
                                ? fullLoc.getPath()
                                : fullLoc.getNamespace() + "__" + fullLoc.getPath();
                        String name = Component.translatable(
                                "enchantment." + fullLoc.getNamespace() + "." + fullLoc.getPath()).getString();
                        pickRows.add(new PickRow(
                                ComponentNames.display(id) + " · " + name + " " + e.getValue(),
                                id + "/" + target));
                    }
                } else if (value instanceof net.minecraft.world.item.alchemy.PotionContents pc) {
                    pc.potion().ifPresent(h -> {
                        var k = BuiltInRegistries.POTION.getKey(h.value());
                        if (k != null) {
                            String target = k.getNamespace().equals("minecraft")
                                    ? k.getPath()
                                    : k.getNamespace() + "__" + k.getPath();
                            pickRows.add(new PickRow(
                                    ComponentNames.display(id) + " · " + k.getPath(),
                                    id + "/" + target));
                        }
                    });
                } else if (value instanceof net.minecraft.world.item.component.CustomData data) {
                    var tag = data.copyTag();
                    for (String key : tag.getAllKeys()) {
                        if (pickRows.size() > 30) break;
                        String leaf;
                        var t = tag.get(key);
                        if (t instanceof net.minecraft.nbt.NumericTag n) leaf = " = " + n.getAsLong();
                        else if (t instanceof net.minecraft.nbt.StringTag st) leaf = " = " + st.getAsString();
                        else leaf = "";
                        // 点号编码为 __（下划线保持原样，不再歧义）
                        pickRows.add(new PickRow(
                                ComponentNames.display(id) + " · " + key + leaf,
                                id + "/" + key.replace(".", "__")));
                    }
                } else if (value instanceof Component text) {
                    String raw = text.getString();
                    // 名称可编码（纯字母数字）→ 精确匹配该名称；含中文/符号 → 存在性
                    String encoded = io.github.xianynomial.sfmfactorystudio.net.NbtMatcherHook.encodeNameMatcher(raw);
                    String target = encoded == null ? id : id + "/" + encoded;
                    String t = raw.length() > 18 ? raw.substring(0, 18) + "…" : raw;
                    String mark = encoded == null ? "" : "（按此名称）";
                    pickRows.add(new PickRow(ComponentNames.display(id) + " · " + t + mark, target));
                } else {
                    String pv = ComponentNames.preview(type, stack);
                    pickRows.add(new PickRow(
                            pv.isEmpty() ? ComponentNames.display(id)
                                    : ComponentNames.display(id) + " · " + pv,
                            id));
                }
            }
        });
    }

    private static void rounded(GuiGraphics g, int x, int y, int w, int h, int r, int color) {
        if (r <= 0 || w < r * 2 || h < r * 2) { g.fill(x, y, x + w, y + h, color); return; }
        g.fill(x + 1, y, x + w - 1, y + h, color);
        g.fill(x, y + 1, x + w, y + h - 1, color);
    }

    @Override
    public void render(GuiGraphics g, int mx, int my, float partialTick) {
        g.fill(0, 0, width, height, 0x90000000);
        int px = panelX();
        int rows = Math.min(maxRows(), (shownItems.size() + COLS - 1) / COLS);
        int panelH = componentPage
                ? CONTENT_Y - PANEL_TOP + pickRows.size() * ROW_H + 16
                : CONTENT_Y - PANEL_TOP + rows * SLOT + 16;
        g.fill(px + 3, PANEL_TOP + 3, px + PANEL_W + 3, PANEL_TOP + panelH + 3, 0x30203A5A);
        g.fill(px, PANEL_TOP, px + PANEL_W, PANEL_TOP + panelH, 0xFFF6F8FC);
        g.fill(px, PANEL_TOP, px + PANEL_W, PANEL_TOP + 1, 0xFFC9D4E2);
        g.fill(px, PANEL_TOP + panelH - 1, px + PANEL_W, PANEL_TOP + panelH, 0xFFC9D4E2);
        g.fill(px, PANEL_TOP, px + 1, PANEL_TOP + panelH, 0xFFC9D4E2);
        g.fill(px + PANEL_W - 1, PANEL_TOP, px + PANEL_W, PANEL_TOP + panelH, 0xFFC9D4E2);

        if (componentPage) {
            g.drawString(font, selected.getHoverName().getString() + " 的 NBT（" + pickRows.size() + " 项）",
                    px + 10, PANEL_TOP + 6, 0xFF1B2432, false);
            g.drawString(font, "← 返回选物品", px + 10, TAB_Y, 0xFF2F6FED, false);
            g.fill(px + 8, TAB_Y + 16, px + PANEL_W - 8, TAB_Y + 17, 0xFFE0E5EC); // 分隔线
            int visible = pickRows.size();
            compScroll = Math.min(compScroll, Math.max(0, pickRows.size() - visible));
            for (int i = 0; i < visible; i++) {
                PickRow row = pickRows.get(compScroll + i);
                int ry = CONTENT_Y + i * ROW_H;
                boolean hover = mx >= px + 4 && mx < px + PANEL_W - 4 && my >= ry && my < ry + ROW_H;
                g.fill(px + 4, ry, px + PANEL_W - 4, ry + ROW_H, hover ? 0xFFF1F4F9 : 0xFFFFFFFF);
                g.fill(px + 8, ry + 6, px + 11, ry + 10, 0xFF8B5CF6);
                g.drawString(font, font.plainSubstrByWidth(row.display(), PANEL_W - 32),
                        px + 15, ry + 4, 0xFF1B2432, false);
            }
            if (pickRows.isEmpty()) {
                String empty = "这件物品没有 NBT 数据（无非默认组件），换一件试试";
                g.drawString(font, empty, px + PANEL_W / 2 - font.width(empty) / 2,
                        CONTENT_Y + 5, 0xFFB45309, false);
            }
        } else {
            g.drawString(font, "选择一件物品，查看它的 NBT", px + 10, PANEL_TOP + 6, 0xFF1B2432, false);
            String[] tabs = {"背包物品（" + inventory.size() + "）", "全部物品（" + allItems.size() + "）"};
            int tx = px + 10;
            for (int i = 0; i < 2; i++) {
                boolean active = (i == 1) == allTab;
                int tw = font.width(tabs[i]) + 14;
                g.fill(tx, TAB_Y - 2, tx + tw, TAB_Y + 12, active ? 0xFFDDE8FB : 0xFFEDF2F8);
                g.drawString(font, tabs[i], tx + 7, TAB_Y + 1, active ? 0xFF1B4FA0 : 0xFF5C6779, false);
                tx += tw + 4;
            }
            int gridRows = (shownItems.size() + COLS - 1) / COLS;
            int visibleRows = Math.min(maxRows(), gridRows);
            itemScroll = Math.min(itemScroll, Math.max(0, gridRows - visibleRows));
            for (int r = 0; r < visibleRows; r++) for (int c = 0; c < COLS; c++) {
                int idx = (r + itemScroll) * COLS + c;
                if (idx >= shownItems.size()) break;
                ItemStack s = shownItems.get(idx);
                int sx = px + 12 + c * SLOT, sy = CONTENT_Y + r * SLOT;
                boolean hover = mx >= sx - 1 && mx < sx + 19 && my >= sy - 1 && my < sy + 19;
                if (hover) g.fill(sx - 1, sy - 1, sx + 19, sy + 19, 0xFFE0EBFB);
                g.renderItem(s, sx, sy);
                if (!allTab) g.renderItemDecorations(font, s, sx, sy);
            }
            if (shownItems.isEmpty()) {
                String empty = inventory.isEmpty() ? "背包是空的，先把要区分的物品拿在身上；或切到「全部物品」"
                        : allTab ? "没有匹配的物品" : "背包里没有匹配的物品";
                g.drawString(font, empty, px + PANEL_W / 2 - font.width(empty) / 2,
                        CONTENT_Y + 6, 0xFFB45309, false);
            }
        }
        // 搜索框底板（EditBox 无边框后需要自己画底色+边框才能看清位置）
        if (search != null) {
            rounded(g, search.getX() - 4, search.getY() - 3, search.getWidth() + 8, search.getHeight() + 6, 4, 0xFFFFFFFF);
            g.fill(search.getX() - 4, search.getY() - 3, search.getX() + search.getWidth() + 4, search.getY() - 2, 0xFFC9D4E2);
            g.fill(search.getX() - 4, search.getY() + search.getHeight() + 2, search.getX() + search.getWidth() + 4, search.getY() + search.getHeight() + 3, 0xFFC9D4E2);
            g.fill(search.getX() - 4, search.getY() - 3, search.getX() - 3, search.getY() + search.getHeight() + 3, 0xFFC9D4E2);
            g.fill(search.getX() + search.getWidth() + 3, search.getY() - 3, search.getX() + search.getWidth() + 4, search.getY() + search.getHeight() + 3, 0xFFC9D4E2);
        }
        super.render(g, mx, my, partialTick);
    }

    @Override
    public boolean mouseClicked(double mx, double my, int button) {
        if (super.mouseClicked(mx, my, button)) return true;
        if (button != 0) return true;
        int px = panelX();
        if (componentPage) {
            if (my >= TAB_Y - 3 && my < TAB_Y + 14 && mx >= px + 8 && mx < px + 100) {
                componentPage = false; return true;
            }
            int visible = pickRows.size();
            for (int i = 0; i < visible; i++) {
                int ry = CONTENT_Y + i * ROW_H;
                if (my >= ry && my < ry + ROW_H && mx >= px + 4 && mx < px + PANEL_W - 4) {
                    String target = pickRows.get(compScroll + i).target();
                    Minecraft.getInstance().setScreen(parent);
                    onPick.accept(target);
                    return true;
                }
            }
            return true;
        }
        String[] tabs = {"背包物品", "全部物品"};
        int tx = px + 10;
        for (int i = 0; i < 2; i++) {
            int tw = font.width(tabs[i]) + 14;
            if (my >= TAB_Y - 2 && my < TAB_Y + 12 && mx >= tx && mx < tx + tw) {
                boolean wantAll = i == 1;
                if (wantAll != allTab) { allTab = wantAll; itemScroll = 0; refilter(); }
                return true;
            }
            tx += tw + 4;
        }
        int gridRows = (shownItems.size() + COLS - 1) / COLS;
        int visibleRows = Math.min(maxRows(), gridRows);
        for (int r = 0; r < visibleRows; r++) for (int c = 0; c < COLS; c++) {
            int idx = (r + itemScroll) * COLS + c;
            if (idx >= shownItems.size()) break;
            int sx = px + 12 + c * SLOT, sy = CONTENT_Y + r * SLOT;
            if (mx >= sx - 1 && mx < sx + 19 && my >= sy - 1 && my < sy + 19) {
                openComponents(shownItems.get(idx)); return true;
            }
        }
        return true;
    }

    @Override
    public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
        if (super.keyPressed(keyCode, scanCode, modifiers)) return true;
        if (keyCode == 256) { onClose(); return true; }
        return true;
    }

    @Override
    public boolean mouseScrolled(double mx, double my, double scrollX, double scrollY) {
        if (componentPage) compScroll = Math.max(0, compScroll - (int) Math.signum(scrollY));
        else itemScroll = Math.max(0, itemScroll - (int) Math.signum(scrollY));
        return true;
    }

    @Override
    public void onClose() { Minecraft.getInstance().setScreen(parent); }

    @Override
    public void renderBackground(GuiGraphics g, int mx, int my, float partialTick) {}
}
