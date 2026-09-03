package ca.teamdman.sfmjimu.client.blocks;

import ca.teamdman.sfmjimu.client.Loc;
import ca.teamdman.sfmjimu.client.PinyinSearch;
import ca.teamdman.sfmjimu.client.ResourceIndex;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;

/**
 * In-screen popup panels used to edit block fields (choices, numbers, labels,
 * resources, conditions). Rendered as overlays inside the editor screen — no
 * nested Screen stack — so the canvas stays visible while editing.
 */
abstract class Popup {
    public int x, y, w, h;
    public boolean keepOpen = true;

    public abstract void render(GuiGraphics g, Font font, int mx, int my);

    /** @return true when the click was consumed. */
    public abstract boolean mouseClicked(double mx, double my, int button);

    public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
        return false;
    }

    public boolean charTyped(char ch, int modifiers) {
        return false;
    }

    public boolean mouseScrolled(double mx, double my, double scrollY) {
        return false;
    }

    public boolean isOver(double mx, double my) {
        return mx >= x && mx < x + w && my >= y && my < y + h;
    }

    /**
     * 落位后按面板边界重算尺寸。坐标先被夹进面板，这里才能按剩余空间把
     * 列表展开到"能显示多少就显示多少"。默认实现什么都不做（定高弹层）。
     *
     * @param maxY 面板内可用的下边界（已减去边距），不是 y+h 的上限
     */
    public void applyBounds(int minX, int maxX, int minY, int maxY) {
    }

    // shared drawing helpers -------------------------------------------------

    protected static void panel(GuiGraphics g, int x, int y, int w, int h) {
        // soft shadow + white card + light border (high-tech light theme)
        g.fill(x + 2, y + 3, x + w + 2, y + h + 3, 0x2A20334A);
        g.fill(x + 1, y, x + w - 1, y + h, 0xFFFFFFFF);
        g.fill(x, y + 1, x + w, y + h - 1, 0xFFFFFFFF);
        border(g, x, y, w, h, 0xFFD9DFEA);
    }

    protected static void border(GuiGraphics g, int x, int y, int w, int h, int color) {
        g.fill(x, y, x + w, y + 1, color);
        g.fill(x, y + h - 1, x + w, y + h, color);
        g.fill(x, y, x + 1, y + h, color);
        g.fill(x + w - 1, y, x + w, y + h, color);
    }

    protected static void row(GuiGraphics g, Font font, int x, int y, int w, int h,
                              String text, boolean hover, boolean selected) {
        int bg = selected ? 0xFFE3ECFB : hover ? 0xFFF1F4F9 : 0xFFFFFFFF;
        g.fill(x, y, x + w, y + h, bg);
        g.drawString(font, text, x + 5, y + (h - 8) / 2, selected ? 0xFF1B4FA0 : 0xFF1B2432, false);
    }

    // ------------------------------------------------------------ choice

    /** A simple dropdown list of (value, display) options. */
    public static class ChoicePopup extends Popup {
        private final List<String> values;
        private final List<String> labels;
        private final Consumer<String> onSelect;
        private final String current;
        private int scroll = 0;
        private final int rowH = 14;
        private int visibleRows;
        private boolean clipped;
        private static final int HINT_H = 12;

        public ChoicePopup(int x, int y, int w, List<String> values, List<String> labels,
                           String current, Consumer<String> onSelect) {
            this.x = x;
            this.y = y;
            this.w = w;
            this.values = values;
            this.labels = labels;
            this.current = current;
            this.onSelect = onSelect;
            // 落位后由 applyBounds() 按剩余空间展开；这里只给一个安全初值
            this.visibleRows = Math.max(1, Math.min(values.size(), 8));
            this.h = visibleRows * rowH + 4;
        }

        /**
         * 一屏内能放几条就显示几条：高度按面板剩余空间算，宽度按最长条目
         * 自适应，长条目省略而不是画到面板外面。装不下时才保留滚轮。
         */
        @Override
        public void applyBounds(int minX, int maxX, int minY, int maxY) {
            int avail = Math.max(rowH, maxY - y - 6);
            clipped = values.size() * rowH + 4 > avail;
            if (clipped) avail -= HINT_H; // 装不下时留一条底栏说明还有几项
            visibleRows = Math.max(1, Math.min(values.size(), avail / rowH));
            this.h = visibleRows * rowH + 4 + (clipped ? HINT_H : 0);
            Font font = Minecraft.getInstance().font;
            int want = w;
            for (int i = 0; i < labels.size(); i++) {
                want = Math.max(want, font.width(labels.get(i)) + 24);
            }
            this.w = Math.max(80, Math.min(want, maxX - minX));
        }

        private String fit(Font font, String text) {
            int room = w - 12;
            if (font.width(text) <= room) return text;
            return font.plainSubstrByWidth(text, Math.max(8, room - 6)) + "…";
        }

        @Override
        public void render(GuiGraphics g, Font font, int mx, int my) {
            panel(g, x, y, w, h);
            int listBottom = y + h - 2 - (clipped ? HINT_H : 0);
            int rows = Math.min(values.size() - scroll, visibleRows);
            for (int i = 0; i < rows; i++) {
                int idx = scroll + i;
                int ry = y + 2 + i * rowH;
                if (ry + rowH > listBottom) break;
                boolean hover = mx >= x && mx < x + w && my >= ry && my < ry + rowH;
                // 当前生效的那一项高亮，打开下拉就知道自己选的是哪个
                boolean sel = values.get(idx).equals(current) || labels.get(idx).equals(current);
                row(g, font, x + 2, ry, w - 4, rowH, fit(font, labels.get(idx)), hover, sel);
            }
            if (clipped) {
                int left = Math.max(0, values.size() - scroll - visibleRows);
                g.fill(x + 1, listBottom, x + w - 1, listBottom + HINT_H, 0xFFF6F8FC);
                g.fill(x + 1, listBottom, x + w - 1, listBottom + 1, 0xFFE1E7F0);
                g.drawString(font, "↓ 还有 " + left + " 项 · 滚轮查看",
                        x + 5, listBottom + 2, 0xFF6B7688, false);
            }
        }

        @Override
        public boolean mouseClicked(double mx, double my, int button) {
            if (!isOver(mx, my)) {
                keepOpen = false;
                return true; // close without clicking through into the canvas
            }
            // 底栏不算条目：点到它只是关掉提示，不能选中看不见的那一项
            if (clipped && my >= y + h - 2 - HINT_H) return true;
            int i = (int) ((my - y - 2) / rowH) + scroll;
            if (i >= scroll && i < scroll + visibleRows && i < values.size()) {
                onSelect.accept(values.get(i));
                keepOpen = false;
                return true;
            }
            return true;
        }

        @Override
        public boolean mouseScrolled(double mx, double my, double scrollY) {
            if (!isOver(mx, my)) return false;
            int max = Math.max(0, values.size() - visibleRows);
            scroll = Math.max(0, Math.min(max, scroll - (int) Math.signum(scrollY)));
            return true;
        }
    }

    // ------------------------------------------------------------ decision

    /**
     * A small modal decision card used for choices that must be explained,
     * such as leaving with unsaved work or restoring a local draft.  Unlike a
     * dropdown, clicks outside are swallowed so they can never accidentally
     * activate an editor control underneath the question.
     */
    public static class DecisionPopup extends Popup {
        private final String title;
        private final String message;
        private final List<String> values;
        private final List<String> labels;
        private final Consumer<String> onSelect;

        public DecisionPopup(int x, int y, int w, String title, String message,
                             List<String> values, List<String> labels,
                             Consumer<String> onSelect) {
            this.x = x;
            this.y = y;
            this.w = w;
            this.h = 68;
            this.title = title;
            this.message = message;
            this.values = values;
            this.labels = labels;
            this.onSelect = onSelect;
        }

        @Override
        public void render(GuiGraphics g, Font font, int mx, int my) {
            panel(g, x, y, w, h);
            g.drawString(font, title, x + 8, y + 8, 0xFF1B2432, false);
            g.drawString(font, message, x + 8, y + 22, 0xFF5C6779, false);

            int count = Math.min(values.size(), labels.size());
            if (count == 0) return;
            int gap = 4;
            int buttonW = (w - 16 - gap * (count - 1)) / count;
            int by = y + h - 24;
            for (int i = 0; i < count; i++) {
                int bx = x + 8 + i * (buttonW + gap);
                int bw = i == count - 1 ? x + w - 8 - bx : buttonW;
                boolean hover = mx >= bx && mx < bx + bw && my >= by && my < by + 17;
                int bg = hover ? 0xFFE3ECFB : 0xFFF1F4F9;
                if (values.get(i).contains("discard") || values.get(i).contains("delete")) {
                    bg = hover ? 0xFFFBE1E1 : 0xFFFFF1F1;
                } else if (values.get(i).contains("save") || values.get(i).contains("restore")) {
                    bg = hover ? 0xFFD9F3E8 : 0xFFECF9F3;
                }
                g.fill(bx, by, bx + bw, by + 17, bg);
                border(g, bx, by, bw, 17, 0xFFC9D2DF);
                g.drawCenteredString(font, labels.get(i), bx + bw / 2, by + 5, 0xFF1B2432);
            }
        }

        @Override
        public boolean mouseClicked(double mx, double my, int button) {
            // This popup is modal: an outside click is consumed but does not
            // choose anything or close it.
            if (button != 0 || !isOver(mx, my)) return true;
            int count = Math.min(values.size(), labels.size());
            if (count == 0) return true;
            int gap = 4;
            int buttonW = (w - 16 - gap * (count - 1)) / count;
            int by = y + h - 24;
            if (my < by || my >= by + 17) return true;
            for (int i = 0; i < count; i++) {
                int bx = x + 8 + i * (buttonW + gap);
                int bw = i == count - 1 ? x + w - 8 - bx : buttonW;
                if (mx >= bx && mx < bx + bw) {
                    onSelect.accept(values.get(i));
                    keepOpen = false;
                    return true;
                }
            }
            return true;
        }
    }

    // ------------------------------------------------------------ text

    /** Single-line text entry with an optional browse button (opens the resource picker). */
    public static class TextPopup extends Popup {
        private final EditBox box;
        private final Consumer<String> onDone;
        private final Screen host;
        private final Runnable openPicker;
        private final String confirmLabel;
        private static final Loc BROWSE = new Loc("gui.sfmjimu.blocks.popup.browse", "浏览...");

        public TextPopup(Screen host, int x, int y, int w, String initial, String hint,
                         Consumer<String> onDone, Runnable openPicker) {
            this(host, x, y, w, initial, hint, onDone, openPicker, null);
        }

        /** Text input with visible cancel/confirm actions for consequential edits. */
        public static TextPopup confirmed(Screen host, int x, int y, int w, String initial, String hint,
                                          Consumer<String> onDone, Runnable openPicker, String confirmLabel) {
            return new TextPopup(host, x, y, w, initial, hint, onDone, openPicker, confirmLabel);
        }

        private TextPopup(Screen host, int x, int y, int w, String initial, String hint,
                          Consumer<String> onDone, Runnable openPicker, String confirmLabel) {
            this.host = host;
            this.x = x;
            this.y = y;
            this.w = w;
            this.onDone = onDone;
            this.openPicker = openPicker;
            this.confirmLabel = confirmLabel;
            this.h = confirmLabel == null ? 20 + (openPicker != null ? 22 : 0) : 44;
            var mc = Minecraft.getInstance();
            int boxW = w - 8 - (openPicker != null ? 50 : 0);
            box = new EditBox(mc.font, x + 4, y + 4, boxW, 16, Component.literal(hint));
            box.setMaxLength(256);
            box.setValue(initial == null ? "" : initial);
            box.setHint(Component.literal(hint));
            box.setFocused(true);
            // Most editor fields replace an existing value. Selecting it on
            // open makes typing "40" replace "20" instead of producing "2040".
            box.setCursorPosition(box.getValue().length());
            box.setHighlightPos(0);
            box.setResponder(s -> {});
        }

        @Override
        public void render(GuiGraphics g, Font font, int mx, int my) {
            panel(g, x, y, w, h);
            box.render(g, mx, my, 0);
            if (openPicker != null) {
                int bx = x + w - 54, by = y + 4;
                boolean hover = mx >= bx && mx < bx + 50 && my >= by && my < by + 16;
                g.fill(bx, by, bx + 50, by + 16, hover ? 0xFFE3ECFB : 0xFFF1F4F9);
                border(g, bx, by, 50, 16, 0xFFC9D2DF);
                g.drawCenteredString(font, BROWSE.getString(), bx + 25, by + 4, 0xFF1B2432);
            }
            if (confirmLabel != null) {
                int by = y + h - 20;
                drawAction(g, font, x + w - 104, by, 48, "取消", mx, my, false);
                drawAction(g, font, x + w - 52, by, 48, confirmLabel, mx, my, true);
            }
        }

        private static void drawAction(GuiGraphics g, Font font, int x, int y, int w,
                                       String label, int mx, int my, boolean primary) {
            boolean hover = mx >= x && mx < x + w && my >= y && my < y + 16;
            int bg = primary
                    ? (hover ? 0xFFD0EEE0 : 0xFFE8F7F0)
                    : (hover ? 0xFFE3ECFB : 0xFFF1F4F9);
            g.fill(x, y, x + w, y + 16, bg);
            border(g, x, y, w, 16, 0xFFC9D2DF);
            g.drawCenteredString(font, label, x + w / 2, y + 4, 0xFF1B2432);
        }

        @Override
        public boolean mouseClicked(double mx, double my, int button) {
            if (confirmLabel != null) {
                if (button != 0) return true;
                int by = y + h - 20;
                if (my >= by && my < by + 16) {
                    if (mx >= x + w - 104 && mx < x + w - 56) {
                        keepOpen = false;
                        return true;
                    }
                    if (mx >= x + w - 52 && mx < x + w - 4) {
                        finish();
                        return true;
                    }
                }
                // Consequential forms require an explicit decision. An outside
                // click neither saves nor leaks through to the editor below.
                if (!isOver(mx, my)) return true;
            }
            if (openPicker != null) {
                int bx = x + w - 54, by = y + 4;
                if (mx >= bx && mx < bx + 50 && my >= by && my < by + 16) {
                    keepOpen = false;
                    openPicker.run();
                    return true;
                }
            }
            if (!isOver(mx, my)) {
                finish();
                return true; // consumed: click outside commits & closes
            }
            box.mouseClicked(mx, my, button);
            return true;
        }

        @Override
        public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
            if (keyCode == 257 || keyCode == 335) { // enter
                finish();
                return true;
            }
            return box.keyPressed(keyCode, scanCode, modifiers);
        }

        @Override
        public boolean charTyped(char ch, int modifiers) {
            return box.charTyped(ch, modifiers);
        }

        private void finish() {
            keepOpen = false;
            onDone.accept(box.getValue().trim());
        }
    }

    // ------------------------------------------------------------ number

    /** Explicit numeric editor with step buttons and a visible server minimum. */
    public static class NumberPopup extends Popup {
        private final EditBox box;
        private final Consumer<Long> onDone;
        private final long minimum;
        private final String minimumReason;

        public NumberPopup(int x, int y, int w, long value, long minimum,
                           String minimumReason, Consumer<Long> onDone) {
            this.x = x;
            this.y = y;
            this.w = Math.max(180, w);
            this.h = 62;
            this.minimum = minimum;
            this.minimumReason = minimumReason;
            this.onDone = onDone;
            box = new EditBox(Minecraft.getInstance().font, x + 6, y + 6, this.w - 12, 16, Component.empty());
            box.setMaxLength(18);
            box.setFilter(s -> s.matches("[0-9]*"));
            box.setValue(Long.toString(value));
            box.setFocused(true);
            box.setCursorPosition(box.getValue().length());
            box.setHighlightPos(0);
        }

        @Override
        public void render(GuiGraphics g, Font font, int mx, int my) {
            panel(g, x, y, w, h);
            box.setX(x + 6);
            box.setY(y + 6);
            box.setWidth(w - 12);
            box.render(g, mx, my, 0);
            long value = value();
            int infoColor = value < minimum ? 0xFFC22B21 : 0xFF5C6779;
            g.drawString(font, minimumReason + "：最少 " + minimum, x + 7, y + 26, infoColor, false);
            drawButton(g, font, x + 6, y + 42, 24, "−", mx, my, false);
            drawButton(g, font, x + 34, y + 42, 24, "＋", mx, my, false);
            drawButton(g, font, x + w - 94, y + 42, 42, "取消", mx, my, false);
            drawButton(g, font, x + w - 48, y + 42, 42, "确定", mx, my, true);
        }

        private static void drawButton(GuiGraphics g, Font font, int x, int y, int w,
                                       String label, int mx, int my, boolean primary) {
            boolean hover = mx >= x && mx < x + w && my >= y && my < y + 16;
            int bg = primary
                    ? (hover ? 0xFFD0EEE0 : 0xFFE8F7F0)
                    : (hover ? 0xFFE3ECFB : 0xFFF1F4F9);
            g.fill(x, y, x + w, y + 16, bg);
            border(g, x, y, w, 16, 0xFFC9D2DF);
            g.drawCenteredString(font, label, x + w / 2, y + 4, 0xFF1B2432);
        }

        @Override
        public boolean mouseClicked(double mx, double my, int button) {
            if (button != 0 || !isOver(mx, my)) return true;
            if (my >= y + 42 && my < y + 58) {
                if (mx >= x + 6 && mx < x + 30) {
                    box.setValue(Long.toString(Math.max(0, value() - 1)));
                    return true;
                }
                if (mx >= x + 34 && mx < x + 58) {
                    long current = value();
                    box.setValue(Long.toString(current == Long.MAX_VALUE ? current : current + 1));
                    return true;
                }
                if (mx >= x + w - 94 && mx < x + w - 52) {
                    keepOpen = false;
                    return true;
                }
                if (mx >= x + w - 48 && mx < x + w - 6) {
                    finish();
                    return true;
                }
            }
            box.mouseClicked(mx, my, button);
            return true;
        }

        @Override
        public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
            if (keyCode == 257 || keyCode == 335) {
                finish();
                return true;
            }
            return box.keyPressed(keyCode, scanCode, modifiers);
        }

        @Override
        public boolean charTyped(char ch, int modifiers) {
            return box.charTyped(ch, modifiers);
        }

        private long value() {
            try {
                return Long.parseLong(box.getValue());
            } catch (NumberFormatException ignored) {
                return 0;
            }
        }

        private void finish() {
            if (value() < minimum) return;
            keepOpen = false;
            onDone.accept(value());
        }
    }

    // ------------------------------------------------------------ labels

    /** Multi-select label picker: known labels + free-text add. */
    public static class LabelPopup extends Popup {
        private final EditBox search;
        private final EditBox newLabel;
        private final Set<String> selected;
        private final List<String> known;
        private final Map<String, Integer> knownCounts;
        private final List<String> filtered = new ArrayList<>();
        private final Consumer<List<String>> onDone;
        /**
         * False on servers without the addon: binding counts are unknown (not
         * zero!), so the count column is hidden instead of showing misleading
         * "未绑定" badges for every label.
         */
        private final boolean showCounts;
        private int scroll = 0;
        private final int rowH = 16;
        /** 可见行数：落位后按面板剩余空间放大，装不下 entire 列表时才滚轮。 */
        private int visibleRows = 6;
        private static final Loc HINT = new Loc("gui.sfmjimu.blocks.popup.label_hint", "搜索标签...");
        private static final Loc NEW = new Loc("gui.sfmjimu.blocks.popup.new_label", "新建标签（还需用标签枪绑定）");

        public LabelPopup(int x, int y, int w, List<String> knownLabels,
                          Map<String, Integer> knownCounts, List<String> current,
                          Consumer<List<String>> onDone) {
            this(x, y, w, knownLabels, knownCounts, current, onDone, true);
        }

        public LabelPopup(int x, int y, int w, List<String> knownLabels,
                          Map<String, Integer> knownCounts, List<String> current,
                          Consumer<List<String>> onDone, boolean showCounts) {
            this.x = x;
            this.y = y;
            this.w = w;
            this.known = knownLabels;
            this.knownCounts = knownCounts;
            this.selected = new LinkedHashSet<>(current);
            this.onDone = onDone;
            this.showCounts = showCounts;
            var mc = Minecraft.getInstance();
            search = new EditBox(mc.font, x + 4, y + 18, w - 8, 14, Component.literal(""));
            search.setValue("");
            search.setTextShadow(false);
            search.setHint(Component.literal(HINT.getString()));
            search.setResponder(s -> refilter());
            newLabel = new EditBox(mc.font, x + 4, 0, w - 8, 14, Component.literal(""));
            newLabel.setTextShadow(false);
            newLabel.setHint(Component.literal(NEW.getString()));
            this.h = 36 + visibleRows * rowH + 22 + 4;
            refilter();
            search.setFocused(true); // 打开即聚焦：不依赖用户先点一下搜索框
        }

        /** 顶部搜索框 + 底部新建行的固定占用，列表高度之外都要留出来。 */
        private static final int CHROME_H = 36 + 22 + 4;

        /** 标签列表同样撑到一屏放得下为止，最少 3 行免得面板塌成一条。 */
        @Override
        public void applyBounds(int minX, int maxX, int minY, int maxY) {
            int avail = Math.max(rowH, maxY - y - CHROME_H - 6);
            int want = Math.max(3, Math.min(Math.max(1, filtered.size()), avail / rowH));
            visibleRows = want;
            this.h = CHROME_H + visibleRows * rowH;
        }

        private void refilter() {
            filtered.clear();
            String q = search.getValue().trim().toLowerCase(Locale.ROOT);
            for (String l : known) {
                if (q.isEmpty() || PinyinSearch.matchesNormalized(l, q)) {
                    filtered.add(l);
                }
            }
            for (String l : selected) {
                if (!filtered.contains(l)) filtered.add(l);
            }
        }

        /** Refresh suggestions while this popup is open when the server reply arrives. */
        public void replaceKnownLabels(List<String> labels, Map<String, Integer> counts) {
            known.clear();
            knownCounts.clear();
            for (String label : labels) {
                if (label != null && !label.isBlank() && !known.contains(label)) {
                    known.add(label);
                    knownCounts.put(label, Math.max(0, counts.getOrDefault(label, 0)));
                }
            }
            for (String label : selected) {
                if (!known.contains(label)) known.add(label);
                knownCounts.putIfAbsent(label, 0);
            }
            refilter();
            scroll = Math.min(scroll, Math.max(0, filtered.size() - visibleRows));
            search.setFocused(true);
        }

        private int listH() {
            return Math.min(visibleRows, Math.max(1, filtered.size())) * rowH;
        }

        @Override
        public void render(GuiGraphics g, Font font, int mx, int my) {
            h = 36 + listH() + 22 + 4;
            search.setX(x + 4);
            search.setY(y + 18);
            search.setWidth(w - 8);
            // position the new-label row dynamically
            newLabel.setX(x + 4);
            newLabel.setY(y + 36 + listH() + 4);
            newLabel.setWidth(w - 8);
            panel(g, x, y, w, h);
            g.drawString(font, "可用方块标签 · " + known.size() + " 个", x + 6, y + 6, 0xFF1B2432, false);
            search.render(g, mx, my, 0);
            int rows = Math.min(visibleRows, filtered.size() - scroll);
            for (int i = 0; i < rows; i++) {
                String label = filtered.get(scroll + i);
                int ry = y + 35 + i * rowH;
                boolean hover = mx >= x + 2 && mx < x + w - 2 && my >= ry && my < ry + rowH;
                boolean sel = selected.contains(label);
                int bg = sel ? 0xFFE3ECFB : hover ? 0xFFF1F4F9 : 0xFFFFFFFF;
                g.fill(x + 2, ry, x + w - 2, ry + rowH, bg);
                String count = !showCounts ? null : knownCounts.getOrDefault(label, 0) > 0
                        ? knownCounts.get(label) + " 个方块" : "未绑定";
                int countColor = knownCounts.getOrDefault(label, 0) > 0 ? 0xFF18794E : 0xFFB54708;
                int countW = count != null ? font.width(count) : 0;
                String prefix = sel ? "✓ " : "○ ";
                String shown = font.plainSubstrByWidth(prefix + label, Math.max(20, w - countW - 22));
                g.drawString(font, shown, x + 7, ry + 4, sel ? 0xFF1B4FA0 : 0xFF1B2432, false);
                if (count != null) {
                    g.drawString(font, count, x + w - countW - 7, ry + 4, countColor, false);
                }
            }
            if (filtered.isEmpty()) {
                String empty = "暂无标签，可在下方新建";
                g.drawString(font, empty, x + w / 2 - font.width(empty) / 2,
                        y + 40, 0xFF6B7688, false);
            }
            newLabel.render(g, mx, my, 0);
        }

        @Override
        public boolean mouseClicked(double mx, double my, int button) {
            if (!isOver(mx, my)) {
                finish();
                return true;
            }
            search.mouseClicked(mx, my, button);
            newLabel.mouseClicked(mx, my, button);
            int rows = Math.min(visibleRows, filtered.size() - scroll);
            for (int i = 0; i < rows; i++) {
                int ry = y + 35 + i * rowH;
                if (my >= ry && my < ry + rowH && mx >= x + 2 && mx < x + w - 2) {
                    String label = filtered.get(scroll + i);
                    if (selected.contains(label)) selected.remove(label);
                    else selected.add(label);
                    return true;
                }
            }
            return true;
        }

        @Override
        public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
            if (keyCode == 257 || keyCode == 335) {
                String text = newLabel.getValue().trim();
                if (!text.isEmpty()) {
                    selected.add(text);
                    newLabel.setValue("");
                    if (!known.contains(text)) known.add(text);
                    refilter();
                    return true;
                }
                finish();
                return true;
            }
            if (search.isFocused()) return search.keyPressed(keyCode, scanCode, modifiers);
            if (newLabel.isFocused()) return newLabel.keyPressed(keyCode, scanCode, modifiers);
            return false;
        }

        @Override
        public boolean charTyped(char ch, int modifiers) {
            if (search.isFocused()) return search.charTyped(ch, modifiers);
            if (newLabel.isFocused()) return newLabel.charTyped(ch, modifiers);
            return false;
        }

        @Override
        public boolean mouseScrolled(double mx, double my, double scrollY) {
            if (!isOver(mx, my)) return false;
            int max = Math.max(0, filtered.size() - visibleRows);
            scroll = Math.max(0, Math.min(max, scroll - (int) Math.signum(scrollY)));
            return true;
        }

        private void finish() {
            keepOpen = false;
            onDone.accept(new ArrayList<>(selected));
        }
    }

}
