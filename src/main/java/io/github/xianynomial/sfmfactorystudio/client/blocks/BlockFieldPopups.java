package io.github.xianynomial.sfmfactorystudio.client.blocks;

import io.github.xianynomial.sfmfactorystudio.client.Loc;
import io.github.xianynomial.sfmfactorystudio.client.PinyinSearch;
import io.github.xianynomial.sfmfactorystudio.client.blocks.model.BProgram;
import io.github.xianynomial.sfmfactorystudio.client.ResourceIndex;
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
    // 弹窗通用文案（中文默认 + en_us 语言键）
    static final Loc P_MORE_BELOW = new Loc("gui.sfmfactorystudio.blocks.popup.more_below", "↓ 还有 %s 项 · 滚轮查看");
    static final Loc P_CANCEL = new Loc("gui.sfmfactorystudio.blocks.popup.cancel", "取消");
    static final Loc P_OK = new Loc("gui.sfmfactorystudio.blocks.popup.ok", "确定");
    static final Loc P_MIN_REQUIRED = new Loc("gui.sfmfactorystudio.blocks.popup.min_required", "%s：最少 %s");
    static final Loc P_KNOWN_LABELS = new Loc("gui.sfmfactorystudio.blocks.popup.known_labels", "可用方块标签 · %s 个");
    static final Loc P_BOUND_COUNT = new Loc("gui.sfmfactorystudio.blocks.popup.bound_count", "%s 个方块");
    static final Loc P_UNBOUND = new Loc("gui.sfmfactorystudio.blocks.popup.unbound", "未绑定");
    static final Loc P_NO_LABELS = new Loc("gui.sfmfactorystudio.blocks.popup.no_labels", "暂无标签，可在下方新建");

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
                g.drawString(font, P_MORE_BELOW.getString(left),
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
            // 英文文案比中文长很多：按标题/消息/按钮标签实测宽度自动加宽，
            // 上限 560 保证仍在编辑器面板内（调用点按 320 居中，向右加宽不出界）。
            Font font = Minecraft.getInstance().font;
            int count = Math.min(values.size(), labels.size());
            int maxLabel = 0;
            for (String label : labels) maxLabel = Math.max(maxLabel, font.width(label) + 14);
            int buttonsNeed = count > 0 ? maxLabel * count + 16 + 4 * (count - 1) : 0;
            this.w = Math.min(560, Math.max(Math.max(w,
                    font.width(title) + 16), Math.max(font.width(message) + 16, buttonsNeed)));
            this.y = y;
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
        private static final Loc BROWSE = new Loc("gui.sfmfactorystudio.blocks.popup.browse", "浏览...");

        public TextPopup(Screen host, int x, int y, int w, String initial, String hint,
                         Consumer<String> onDone, Runnable openPicker) {
            this(host, x, y, w, initial, hint, onDone, openPicker, null, null);
        }

        /** Text input with visible cancel/confirm actions for consequential edits. */
        public static TextPopup confirmed(Screen host, int x, int y, int w, String initial, String hint,
                                          Consumer<String> onDone, Runnable openPicker, String confirmLabel) {
            return new TextPopup(host, x, y, w, initial, hint, onDone, openPicker, confirmLabel);
        }

        /** 带侧边按钮（可自定义文字/橙色高亮）+ 实时预览的输入框。 */
        public static TextPopup withButton(Screen host, int x, int y, int w, String initial,
                                           Consumer<String> onDone, Runnable openButton,
                                           String confirmLabel, String buttonLabel,
                                           java.util.function.Function<String, String> livePreview) {
            TextPopup p = new TextPopup(host, x, y, w, initial, "", onDone, openButton, confirmLabel, livePreview);
            if (buttonLabel != null) p.pickerButtonLabel = buttonLabel;
            if ("beta".equals(buttonLabel)) p.pickerButtonOrange = true;
            return p;
        }

        private final java.util.function.Function<String, String> livePreview;
        private String pickerButtonLabel = BROWSE.getString();
        private boolean pickerButtonOrange = false;

        private TextPopup(Screen host, int x, int y, int w, String initial, String hint,
                          Consumer<String> onDone, Runnable openPicker, String confirmLabel) {
            this(host, x, y, w, initial, hint, onDone, openPicker, confirmLabel, null);
        }

        /** 带实时预览的文本输入：每帧把当前输入交给 preview 生成提示行。 */
        public TextPopup(Screen host, int x, int y, int w, String initial, String hint,
                         Consumer<String> onDone, Runnable openPicker, String confirmLabel,
                         java.util.function.Function<String, String> livePreview) {
            this.host = host;
            this.x = x;
            this.y = y;
            this.w = w;
            this.onDone = onDone;
            this.openPicker = openPicker;
            this.confirmLabel = confirmLabel;
            this.livePreview = livePreview;
            // 面板高度含预览行（有预览函数时 +14），预览画在面板内部不压游戏画面
            this.h = (confirmLabel == null ? 20 + (openPicker != null ? 22 : 0) : 44)
                    + (livePreview != null ? 14 : 0);
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
            if (livePreview != null) {
                String preview = livePreview.apply(box.getValue());
                if (preview != null && !preview.isEmpty()) {
                    int py2 = y + h - (confirmLabel != null ? 22 : 0) - 13;
                    g.fill(x + 3, py2 - 2, x + w - 3, py2 + 10, 0xFFF6F8FC); // 白底防重叠
                    g.drawString(font, preview, x + 5, py2, 0xFF5C6779, false);
                }
            }
            if (openPicker != null) {
                int bx = x + w - 54, by = y + 4;
                boolean hover = mx >= bx && mx < bx + 50 && my >= by && my < by + 16;
                int bBg = pickerButtonOrange ? (hover ? 0xFFFDE7C8 : 0xFFFBEDD5) : hover ? 0xFFE3ECFB : 0xFFF1F4F9;
                int bBd = pickerButtonOrange ? 0xFFD79A2B : 0xFFC9D2DF;
                g.fill(bx, by, bx + 50, by + 16, bBg);
                border(g, bx, by, 50, 16, bBd);
                g.drawCenteredString(font, pickerButtonLabel, bx + 25, by + 4,
                        pickerButtonOrange ? 0xFFB45309 : 0xFF1B2432);
            }
            if (confirmLabel != null) {
                int by = y + h - 20;
                drawAction(g, font, x + w - 104, by, 48, P_CANCEL.getString(), mx, my, false);
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
            g.drawString(font, P_MIN_REQUIRED.getString(minimumReason, minimum), x + 7, y + 26, infoColor, false);
            drawButton(g, font, x + 6, y + 42, 24, "−", mx, my, false);
            drawButton(g, font, x + 34, y + 42, 24, "＋", mx, my, false);
            drawButton(g, font, x + w - 94, y + 42, 42, P_CANCEL.getString(), mx, my, false);
            drawButton(g, font, x + w - 48, y + 42, 42, P_OK.getString(), mx, my, true);
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
        private static final Loc HINT = new Loc("gui.sfmfactorystudio.blocks.popup.label_hint", "搜索标签...");
        private static final Loc NEW = new Loc("gui.sfmfactorystudio.blocks.popup.new_label", "新建标签（还需用标签枪绑定）");

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
            search.setHint(Component.literal(HINT.getString()));
            search.setResponder(s -> refilter());
            newLabel = new EditBox(mc.font, x + 4, 0, w - 8, 14, Component.literal(""));
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
            g.drawString(font, P_KNOWN_LABELS.getString(known.size()), x + 6, y + 6, 0xFF1B2432, false);
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
                        ? P_BOUND_COUNT.getString(knownCounts.get(label)) : P_UNBOUND.getString();
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
                String empty = P_NO_LABELS.getString();
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
            // 完全手动管理焦点：直接判断点击落在哪个输入框，只聚焦那个、释放另一个。
            // 不依赖 EditBox.mouseClicked 的内部行为（1.21.1 中它不会自动释放别的框）。
            boolean clickedSearch = mx >= search.getX() && mx < search.getX() + search.getWidth()
                    && my >= search.getY() && my < search.getY() + search.getHeight();
            boolean clickedNewLabel = mx >= newLabel.getX() && mx < newLabel.getX() + newLabel.getWidth()
                    && my >= newLabel.getY() && my < newLabel.getY() + newLabel.getHeight();
            if (clickedNewLabel) {
                search.setFocused(false);
                newLabel.setFocused(true);
                newLabel.mouseClicked(mx, my, button); // 让它定位光标
            } else if (clickedSearch) {
                newLabel.setFocused(false);
                search.setFocused(true);
                search.mouseClicked(mx, my, button); // 让它定位光标
            } else {
                // 点在标签行或其他空白处：两个都取消焦点
                search.setFocused(false);
                newLabel.setFocused(false);
            }
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

    // ------------------------------------------------------------ card search
    /**
     * 「/ 搜索卡片」弹层：真输入框 + 实时过滤列表。此前借用 ChoicePopup 逐字符
     * 累积过滤——没有可见输入框，输入法组合串不走 charTyped，中文基本输不进去；
     * EditBox 提供光标/退格/粘贴，配合 PinyinSearch 让拼音直接命中中文标签。
     */
    public static class CardSearchPopup extends Popup {
        private record Entry(BProgram.Trigger trigger, String display) {
        }

        private final List<Entry> all = new ArrayList<>();
        private final List<Entry> filtered = new ArrayList<>();
        private final Consumer<BProgram.Trigger> onPick;
        private final EditBox box;
        private int sel = 0;
        private int scroll = 0;
        private int maxRows = 8;
        private static final int BOX_H = 26;
        private static final int ROW_H = 16;
        private static final int HINT_H = 12;
        private int visibleRows = 1;
        private boolean clipped;

        public CardSearchPopup(int x, int y, int w,
                               List<BProgram.Trigger> triggers, List<String> displays,
                               Consumer<BProgram.Trigger> onPick) {
            this.x = x;
            this.y = y;
            this.w = w;
            this.onPick = onPick;
            for (int i = 0; i < triggers.size(); i++) {
                all.add(new Entry(triggers.get(i), displays.get(i)));
            }
            box = new EditBox(Minecraft.getInstance().font, x + 8, y + 7, w - 16, 12,
                    Component.literal(new Loc("gui.sfmfactorystudio.ed.search_box_hint",
                            "搜索标签／摘要，支持拼音；Enter 定位，Esc 关闭").getString()));
            box.setMaxLength(80);
            box.setBordered(false);
            box.setTextColor(0xFF1B2432);
            box.setResponder(s -> refilter());
            box.setFocused(true);
            refilter();
        }

        /** 按当前输入过滤（含拼音），并按结果数与可用空间收敛弹层高度。 */
        private void refilter() {
            String q = box.getValue().trim();
            filtered.clear();
            for (Entry e : all) {
                if (q.isEmpty() || PinyinSearch.matches(e.display(), q)) filtered.add(e);
            }
            sel = Math.min(sel, Math.max(0, filtered.size() - 1));
            visibleRows = Math.max(1, Math.min(filtered.size(), maxRows));
            clipped = filtered.size() > visibleRows;
            h = BOX_H + visibleRows * ROW_H + (clipped ? HINT_H : 0) + 4;
            clampScroll();
        }

        private void clampScroll() {
            scroll = Math.max(0, Math.min(scroll, Math.max(0, filtered.size() - visibleRows)));
            if (sel < scroll) scroll = sel;
            if (sel >= scroll + visibleRows) scroll = sel - visibleRows + 1;
            scroll = Math.max(0, scroll);
        }

        @Override
        public void applyBounds(int minX, int maxX, int minY, int maxY) {
            maxRows = Math.max(1, (maxY - (y + BOX_H + 4)) / ROW_H);
            box.setWidth(w - 16);
            refilter();
        }

        private String fit(Font font, String text) {
            int room = w - 16;
            if (font.width(text) <= room) return text;
            return font.plainSubstrByWidth(text, Math.max(8, room - 8)) + "…";
        }

        @Override
        public void render(GuiGraphics g, Font font, int mx, int my) {
            panel(g, x, y, w, h);
            g.fill(x + 8, y + BOX_H - 3, x + w - 8, y + BOX_H - 2,
                    box.isFocused() ? 0xFF3E6FD8 : 0xFFD9DFEA);
            box.render(g, mx, my, 0);
            int ry0 = y + BOX_H;
            if (filtered.isEmpty()) {
                g.drawString(font, new Loc("gui.sfmfactorystudio.ed.search_empty", "没有匹配的卡片")
                        .getString(), x + 8, ry0 + 4, 0xFF8A94A6, false);
            } else {
                int rows = Math.min(filtered.size() - scroll, visibleRows);
                int listBottom = ry0 + visibleRows * ROW_H;
                for (int i = 0; i < rows; i++) {
                    int idx = scroll + i;
                    int ry = ry0 + i * ROW_H;
                    boolean hover = mx >= x && mx < x + w && my >= ry && my < ry + ROW_H;
                    row(g, font, x + 2, ry, w - 4, ROW_H, fit(font, filtered.get(idx).display()),
                            hover, idx == sel);
                }
                if (clipped) {
                    int left = Math.max(0, filtered.size() - scroll - visibleRows);
                    g.fill(x + 1, listBottom, x + w - 1, listBottom + HINT_H, 0xFFF6F8FC);
                    g.fill(x + 1, listBottom, x + w - 1, listBottom + 1, 0xFFE1E7F0);
                    g.drawString(font, new Loc("gui.sfmfactorystudio.blocks.popup.more_below", "↓ 还有 %s 项 · 滚轮查看")
                            .getString(left), x + 5, listBottom + 2, 0xFF6B7688, false);
                }
            }
        }

        private void pickCurrent() {
            if (filtered.isEmpty()) return;
            onPick.accept(filtered.get(sel).trigger());
            keepOpen = false;
        }

        private void moveSel(int d) {
            if (filtered.isEmpty()) return;
            sel = Math.max(0, Math.min(filtered.size() - 1, sel + d));
            clampScroll();
        }

        @Override
        public boolean mouseClicked(double mx, double my, int button) {
            if (!isOver(mx, my)) {
                keepOpen = false;
                return true; // 点外面只关弹层，不穿透到画布
            }
            if (box.mouseClicked(mx, my, button)) {
                box.setFocused(true);
                return true;
            }
            int ry0 = y + BOX_H;
            if (my >= ry0 && my < ry0 + visibleRows * ROW_H) {
                int idx = (int) ((my - ry0) / ROW_H) + scroll;
                if (idx >= 0 && idx < filtered.size()) {
                    sel = idx;
                    pickCurrent();
                    return true;
                }
            }
            return true;
        }

        @Override
        public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
            if (keyCode == 256) return false; // Esc：交给外层统一关闭
            if (keyCode == 257 || keyCode == 335) {
                pickCurrent();
                return true;
            }
            if (keyCode == 265) {
                moveSel(-1);
                return true;
            }
            if (keyCode == 264) {
                moveSel(1);
                return true;
            }
            return box.keyPressed(keyCode, scanCode, modifiers);
        }

        @Override
        public boolean charTyped(char ch, int modifiers) {
            return box.charTyped(ch, modifiers);
        }

        @Override
        public boolean mouseScrolled(double mx, double my, double scrollY) {
            if (!isOver(mx, my)) return false;
            scroll -= (int) scrollY;
            clampScroll();
            return true;
        }
    }
}
