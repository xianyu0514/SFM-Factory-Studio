package io.github.xianynomial.sfmfactorystudio.client.blocks;

import ca.teamdman.sfm.client.ProgramTokenContextActions;
import ca.teamdman.sfm.client.registry.SFMKeyMappings;
import io.github.xianynomial.sfmfactorystudio.client.SfmlHighlight;
import ca.teamdman.sfml.intellisense.IntellisenseAction;
import ca.teamdman.sfml.intellisense.IntellisenseContext;
import ca.teamdman.sfml.manipulation.ManipulationResult;
import ca.teamdman.sfml.manipulation.ProgramStringManipulationUtils;
import net.minecraft.Util;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.narration.NarrationElementOutput;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.util.Mth;
import net.minecraft.util.StringUtil;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.function.Consumer;

/**
 * Small, non-wrapping SFML source editor used beside the block canvas.
 *
 * <p>Vanilla's {@code MultiLineEditBox} wraps long source lines and does not
 * expose its caret/selection, which makes SFM's indentation, comments and
 * intellisense impossible to reuse.  This widget keeps those pieces explicit
 * and deliberately implements the same useful editing commands as SFM's text
 * editor while fitting inside the block editor.</p>
 */
final class SfmlCodeEditor extends AbstractWidget {
    private static final int LINE_H = 10;
    private static final int GUTTER_W = 36;
    private static final int PAD = 4;
    private static final int HISTORY_LIMIT = 160;

    private final Font font;
    private final Consumer<String> valueListener;
    private String value = "";
    private int cursor;
    private int anchor;
    private int preferredColumn = -1;
    private int scrollY;
    private int scrollX;
    private boolean dragging;
    private long focusedAt = Util.getMillis();
    private List<Integer> lineStarts = List.of(0);
    private List<MutableComponent> highlighted = List.of(Component.empty());
    private int highlightDelay;
    // 窗口隐藏时跳过昂贵的 ANTLR 高亮（每次放积木的模型→代码同步都会走到这里）
    private boolean highlightStale = false;
    private final ArrayDeque<State> undo = new ArrayDeque<>();
    private final ArrayDeque<State> redo = new ArrayDeque<>();
    private List<IntellisenseAction> suggestions = List.of();
    private IntellisenseContext suggestionContext;
    private int selectedSuggestion;
    private boolean suppressNextTypedCharacter;

    SfmlCodeEditor(Font font, int x, int y, int width, int height, Consumer<String> valueListener) {
        super(x, y, width, height, Component.literal("SFML 代码编辑"));
        this.font = font;
        this.valueListener = valueListener;
        rebuildLines(true);
    }

    String value() {
        return value;
    }

    int cursorPosition() {
        return cursor;
    }

    int selectionCursorPosition() {
        return anchor;
    }

    void setValueFromModel(String next) {
        setValue(next, false, false);
        undo.clear();
        redo.clear();
    }

    void replaceFromAction(ManipulationResult result) {
        if (result == null) return;
        remember();
        value = clean(result.content());
        cursor = clamp(result.cursorPosition());
        anchor = clamp(result.selectionCursorPosition());
        preferredColumn = -1;
        redo.clear();
        changed();
    }

    void setSuggestions(List<IntellisenseAction> next, IntellisenseContext context) {
        suggestions = next == null ? List.of() : List.copyOf(next.subList(0, Math.min(8, next.size())));
        suggestionContext = context;
        selectedSuggestion = Mth.clamp(selectedSuggestion, 0, Math.max(0, suggestions.size() - 1));
    }

    void clearSuggestions() {
        suggestions = List.of();
        suggestionContext = null;
        selectedSuggestion = 0;
    }

    private void setValue(String next, boolean notify, boolean addHistory) {
        String cleaned = clean(next);
        if (Objects.equals(value, cleaned)) return;
        if (addHistory) remember();
        value = cleaned;
        cursor = Math.min(cursor, value.length());
        anchor = Math.min(anchor, value.length());
        preferredColumn = -1;
        redo.clear();
        rebuildLines(true);
        ensureCursorVisible();
        if (notify) valueListener.accept(value);
    }

    private static String clean(String source) {
        if (source == null) return "";
        return source.replace("\r\n", "\n").replace('\r', '\n');
    }

    private int clamp(int position) {
        return Mth.clamp(position, 0, value.length());
    }

    private void remember() {
        State now = new State(value, cursor, anchor);
        if (undo.peek() == null || !undo.peek().equals(now)) undo.push(now);
        while (undo.size() > HISTORY_LIMIT) undo.removeLast();
    }

    private void changed() {
        rebuildLines(false);
        ensureCursorVisible();
        clearSuggestions();
        valueListener.accept(value);
    }

    private void rebuildLines(boolean highlightNow) {
        ArrayList<Integer> starts = new ArrayList<>();
        starts.add(0);
        for (int i = 0; i < value.length(); i++) {
            if (value.charAt(i) == '\n') starts.add(i + 1);
        }
        lineStarts = List.copyOf(starts);
        if (highlightNow && visible) {
            highlighted = SfmlHighlight.lines(value);
            highlightDelay = 0;
            highlightStale = false;
        } else {
            ArrayList<MutableComponent> plain = new ArrayList<>(lineStarts.size());
            for (int line = 0; line < lineStarts.size(); line++) {
                plain.add(Component.literal(value.substring(lineStart(line), lineEnd(line))));
            }
            highlighted = List.copyOf(plain);
            highlightDelay = 3;
            if (highlightNow) highlightStale = true;
        }
    }

    private int lineOf(int position) {
        int p = clamp(position);
        int lo = 0, hi = lineStarts.size() - 1;
        while (lo <= hi) {
            int mid = (lo + hi) >>> 1;
            if (lineStarts.get(mid) <= p) lo = mid + 1;
            else hi = mid - 1;
        }
        return Math.max(0, hi);
    }

    private int lineStart(int line) {
        return lineStarts.get(Mth.clamp(line, 0, lineStarts.size() - 1));
    }

    private int lineEnd(int line) {
        int l = Mth.clamp(line, 0, lineStarts.size() - 1);
        return l + 1 < lineStarts.size() ? lineStarts.get(l + 1) - 1 : value.length();
    }

    private int contentWidth() {
        return Math.max(8, width - GUTTER_W - PAD * 2 - 7);
    }

    private int visibleLines() {
        return Math.max(1, (height - PAD * 2) / LINE_H);
    }

    private int maxScrollY() {
        return Math.max(0, lineStarts.size() * LINE_H - (height - PAD * 2));
    }

    private int maxScrollX() {
        int widest = 0;
        for (int line = 0; line < lineStarts.size(); line++) {
            widest = Math.max(widest, font.width(value.substring(lineStart(line), lineEnd(line))));
        }
        return Math.max(0, widest - contentWidth() + 12);
    }

    private void ensureCursorVisible() {
        int line = lineOf(cursor);
        int cx = font.width(value.substring(lineStart(line), cursor));
        int viewH = height - PAD * 2;
        int cy = line * LINE_H;
        if (cy < scrollY) scrollY = cy;
        if (cy + LINE_H > scrollY + viewH) scrollY = cy + LINE_H - viewH;
        if (cx < scrollX) scrollX = cx;
        if (cx + 8 > scrollX + contentWidth()) scrollX = cx + 8 - contentWidth();
        scrollY = Mth.clamp(scrollY, 0, maxScrollY());
        scrollX = Mth.clamp(scrollX, 0, maxScrollX());
    }

    private void replaceSelection(String inserted) {
        String text = StringUtil.filterText(inserted == null ? "" : inserted, true);
        int from = Math.min(cursor, anchor), to = Math.max(cursor, anchor);
        remember();
        value = value.substring(0, from) + text + value.substring(to);
        cursor = from + text.length();
        anchor = cursor;
        preferredColumn = -1;
        redo.clear();
        changed();
    }

    private void deleteRelative(int direction) {
        if (cursor != anchor) {
            replaceSelection("");
            return;
        }
        if (direction < 0 && cursor > 0) {
            anchor = cursor - 1;
            replaceSelection("");
        } else if (direction > 0 && cursor < value.length()) {
            anchor = cursor + 1;
            replaceSelection("");
        }
    }

    private void moveTo(int target, boolean selecting) {
        cursor = clamp(target);
        if (!selecting) anchor = cursor;
        ensureCursorVisible();
    }

    private int previousWord() {
        int p = cursor;
        while (p > 0 && Character.isWhitespace(value.charAt(p - 1))) p--;
        while (p > 0 && !Character.isWhitespace(value.charAt(p - 1))) p--;
        return p;
    }

    private int nextWord() {
        int p = cursor;
        while (p < value.length() && !Character.isWhitespace(value.charAt(p))) p++;
        while (p < value.length() && Character.isWhitespace(value.charAt(p))) p++;
        return p;
    }

    private void moveVertical(int delta, boolean selecting) {
        int line = lineOf(cursor);
        if (preferredColumn < 0) preferredColumn = cursor - lineStart(line);
        int targetLine = Mth.clamp(line + delta, 0, lineStarts.size() - 1);
        moveTo(Math.min(lineStart(targetLine) + preferredColumn, lineEnd(targetLine)), selecting);
    }

    private String autoIndent() {
        int line = lineOf(cursor);
        String before = value.substring(lineStart(line), cursor);
        int n = 0;
        while (n < before.length() && (before.charAt(n) == ' ' || before.charAt(n) == '\t')) n++;
        String indent = before.substring(0, n).replace("\t", "    ");
        String upper = before.stripTrailing().toUpperCase(java.util.Locale.ROOT);
        if (upper.endsWith(" DO") || upper.endsWith(" THEN") || upper.equals("DO") || upper.equals("THEN")
                || upper.startsWith("IF ") || upper.startsWith("ELSE IF ") || upper.equals("ELSE")) {
            indent += "    ";
        }
        return "\n" + indent;
    }

    private void undo() {
        if (undo.isEmpty()) return;
        redo.push(new State(value, cursor, anchor));
        restore(undo.pop());
    }

    private void redo() {
        if (redo.isEmpty()) return;
        undo.push(new State(value, cursor, anchor));
        restore(redo.pop());
    }

    private void restore(State state) {
        value = state.value;
        cursor = clamp(state.cursor);
        anchor = clamp(state.anchor);
        preferredColumn = -1;
        changed();
    }

    private boolean acceptSuggestion() {
        if (suggestions.isEmpty() || suggestionContext == null) return false;
        replaceFromAction(suggestions.get(selectedSuggestion).perform(suggestionContext));
        clearSuggestions();
        return true;
    }

    void tick() {
        if (highlightDelay > 0 && --highlightDelay == 0) {
            if (visible) {
                highlighted = SfmlHighlight.lines(value);
                highlightStale = false;
            }
            // 不可见时留给 highlightStale，render 发现可见再重建
        }
    }

    @Override
    public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
        if (!isFocused()) return false;
        boolean ctrl = Screen.hasControlDown();
        boolean shift = Screen.hasShiftDown();

        if (keyCode == 256 && !suggestions.isEmpty()) {
            clearSuggestions();
            return true;
        }

        if (!suggestions.isEmpty() && (keyCode == 265 || keyCode == 264)) {
            selectedSuggestion = Math.floorMod(selectedSuggestion + (keyCode == 265 ? -1 : 1), suggestions.size());
            return true;
        }
        if (SFMKeyMappings.TEXT_EDITOR_ACCEPT_INTELLISENSE_KEY.get().matches(keyCode, scanCode)
                && acceptSuggestion()) {
            // GLFW still emits the matching character event after keyPressed;
            // without this guard accepting the default '\\' shortcut would
            // append a stray backslash to the completed SFML.
            suppressNextTypedCharacter = true;
            return true;
        }
        if (ctrl && keyCode == 32) {
            ProgramTokenContextActions.getContextAction(value, cursor).ifPresent(Runnable::run);
            return true;
        }
        if (ctrl && keyCode == 65) {
            anchor = 0;
            cursor = value.length();
            ensureCursorVisible();
            return true;
        }
        if (ctrl && keyCode == 67) {
            Minecraft.getInstance().keyboardHandler.setClipboard(value.substring(Math.min(cursor, anchor), Math.max(cursor, anchor)));
            return true;
        }
        if (ctrl && keyCode == 88) {
            if (cursor != anchor) {
                Minecraft.getInstance().keyboardHandler.setClipboard(value.substring(Math.min(cursor, anchor), Math.max(cursor, anchor)));
                replaceSelection("");
            }
            return true;
        }
        if (ctrl && keyCode == 86) {
            replaceSelection(Minecraft.getInstance().keyboardHandler.getClipboard());
            return true;
        }
        if (ctrl && keyCode == 90) {
            if (shift) redo(); else undo();
            return true;
        }
        if (ctrl && keyCode == 89) {
            redo();
            return true;
        }
        if (ctrl && keyCode == 47) {
            replaceFromAction(ProgramStringManipulationUtils.toggleComments(value, cursor, anchor));
            return true;
        }
        if (keyCode == 258) {
            replaceFromAction(shift
                    ? ProgramStringManipulationUtils.deindent(value, cursor, anchor)
                    : ProgramStringManipulationUtils.indent(value, cursor, anchor));
            return true;
        }
        if (keyCode == 257 || keyCode == 335) {
            replaceSelection(autoIndent());
            return true;
        }
        if (keyCode == 259) {
            if (ctrl && cursor == anchor) {
                anchor = previousWord();
                replaceSelection("");
            } else deleteRelative(-1);
            return true;
        }
        if (keyCode == 261) {
            if (ctrl && cursor == anchor) {
                anchor = nextWord();
                replaceSelection("");
            } else deleteRelative(1);
            return true;
        }
        if (keyCode == 263 || keyCode == 262) {
            int target = keyCode == 263 ? (ctrl ? previousWord() : cursor - 1) : (ctrl ? nextWord() : cursor + 1);
            moveTo(target, shift);
            preferredColumn = -1;
            return true;
        }
        if (keyCode == 265 || keyCode == 264) {
            moveVertical(keyCode == 265 ? -1 : 1, shift);
            return true;
        }
        if (keyCode == 268 || keyCode == 269) {
            int line = lineOf(cursor);
            moveTo(keyCode == 268 ? (ctrl ? 0 : lineStart(line)) : (ctrl ? value.length() : lineEnd(line)), shift);
            preferredColumn = -1;
            return true;
        }
        if (keyCode == 266 || keyCode == 267) {
            moveVertical((keyCode == 266 ? -1 : 1) * Math.max(1, visibleLines() - 1), shift);
            return true;
        }
        return false;
    }

    @Override
    public boolean charTyped(char codePoint, int modifiers) {
        if (suppressNextTypedCharacter) {
            suppressNextTypedCharacter = false;
            return true;
        }
        if (!visible || !isFocused() || !StringUtil.isAllowedChatCharacter(codePoint)) return false;
        replaceSelection(Character.toString(codePoint));
        return true;
    }

    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        if (!visible || button != 0 || !inside(mouseX, mouseY)) return false;
        setFocused(true);
        if (!suggestions.isEmpty() && suggestionContext != null) {
            int row = suggestionRowAt(mouseX, mouseY);
            if (row >= 0) {
                selectedSuggestion = row;
                acceptSuggestion();
                return true;
            }
        }
        dragging = true;
        seek(mouseX, mouseY, Screen.hasShiftDown());
        return true;
    }

    @Override
    public boolean mouseDragged(double mouseX, double mouseY, int button, double dragX, double dragY) {
        if (!dragging || button != 0) return false;
        seek(mouseX, mouseY, true);
        return true;
    }

    @Override
    public boolean mouseReleased(double mouseX, double mouseY, int button) {
        dragging = false;
        return button == 0 && inside(mouseX, mouseY);
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double scrollXDelta, double scrollYDelta) {
        if (!visible || !inside(mouseX, mouseY)) return false;
        if (Screen.hasShiftDown()) scrollX = Mth.clamp(scrollX - (int) Math.round(scrollYDelta * 24), 0, maxScrollX());
        else scrollY = Mth.clamp(scrollY - (int) Math.round(scrollYDelta * LINE_H * 3), 0, maxScrollY());
        return true;
    }

    private boolean inside(double mx, double my) {
        return mx >= getX() && mx < getX() + width && my >= getY() && my < getY() + height;
    }

    private void seek(double mx, double my, boolean selecting) {
        int line = Mth.clamp((int) ((my - getY() - PAD + scrollY) / LINE_H), 0, lineStarts.size() - 1);
        String text = value.substring(lineStart(line), lineEnd(line));
        int px = Math.max(0, (int) mx - (getX() + GUTTER_W + PAD) + scrollX);
        int col = font.plainSubstrByWidth(text, px).length();
        if (col < text.length()) {
            int before = font.width(text.substring(0, col));
            int glyph = font.width(text.substring(col, col + 1));
            if (px - before >= glyph / 2) col++;
        }
        moveTo(lineStart(line) + col, selecting);
        preferredColumn = -1;
    }

    @Override
    public void setFocused(boolean focused) {
        super.setFocused(focused);
        if (focused) focusedAt = Util.getMillis();
        else dragging = false;
    }

    @Override
    protected void renderWidget(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
        if (highlightStale && visible) {
            highlighted = SfmlHighlight.lines(value);
            highlightStale = false;
        }
        if (!visible) return;
        // SFM's highlighter uses bright token colours, so an opaque dark
        // surface gives every token crisp contrast without text shadows.
        g.fill(getX(), getY(), getX() + width, getY() + height, 0xFF111827);
        g.fill(getX(), getY(), getX() + GUTTER_W, getY() + height, 0xFF182234);
        border(g, getX(), getY(), width, height, isFocused() ? 0xFF2F6FED : 0xFFB8C5D6);

        int firstLine = Math.max(0, scrollY / LINE_H);
        int lastLine = Math.min(lineStarts.size(), firstLine + visibleLines() + 2);
        int selectionStart = Math.min(cursor, anchor), selectionEnd = Math.max(cursor, anchor);
        int textX = getX() + GUTTER_W + PAD - scrollX;

        g.enableScissor(getX() + 1, getY() + 1, getX() + width - 1, getY() + height - 1);
        for (int line = firstLine; line < lastLine; line++) {
            int y = getY() + PAD + line * LINE_H - scrollY;
            String number = Integer.toString(line + 1);
            g.drawString(font, number, getX() + GUTTER_W - 5 - font.width(number), y, 0xFF8C9AAF, false);

            int start = lineStart(line), end = lineEnd(line);
            if (selectionEnd > start && selectionStart < end + (line + 1 < lineStarts.size() ? 1 : 0)) {
                int from = Math.max(start, selectionStart), to = Math.min(end, selectionEnd);
                int x1 = textX + font.width(value.substring(start, from));
                int x2 = to >= end && selectionEnd > end ? getX() + width - 8
                        : textX + font.width(value.substring(start, to));
                if (x2 <= x1) x2 = x1 + 2;
                g.fill(x1, y - 1, x2, y + 9, 0x663B82F6);
            }

            Component rendered = line < highlighted.size() ? highlighted.get(line)
                    : Component.literal(value.substring(start, end));
            g.drawString(font, rendered, textX, y, 0xFFE7EDF7, false);
        }

        boolean cursorVisible = isFocused() && ((Util.getMillis() - focusedAt) / 300L % 2L == 0L);
        if (cursorVisible) {
            int line = lineOf(cursor);
            int cx = textX + font.width(value.substring(lineStart(line), cursor));
            int cy = getY() + PAD + line * LINE_H - scrollY;
            g.fill(cx, cy - 1, cx + 1, cy + 9, 0xFF152238);
        }
        g.disableScissor();

        if (maxScrollY() > 0) {
            int barH = Math.max(12, height * height / Math.max(height, lineStarts.size() * LINE_H + PAD * 2));
            int barY = getY() + (height - barH) * scrollY / Math.max(1, maxScrollY());
            g.fill(getX() + width - 4, barY, getX() + width - 2, barY + barH, 0x993B82F6);
        }
        renderSuggestions(g, mouseX, mouseY);
    }

    private void renderSuggestions(GuiGraphics g, int mouseX, int mouseY) {
        if (!isFocused() || suggestions.isEmpty()) return;
        int rowH = 13;
        int w = Math.min(220, Math.max(110, width / 2));
        int h = suggestions.size() * rowH + 4;
        int x = getX() + width - w - 8;
        int y = getY() + height - h - 5;
        g.fill(x, y, x + w, y + h, 0xF7FFFFFF);
        border(g, x, y, w, h, 0xFF9FB0C5);
        for (int i = 0; i < suggestions.size(); i++) {
            int ry = y + 2 + i * rowH;
            if (i == selectedSuggestion) g.fill(x + 2, ry, x + w - 2, ry + rowH, 0xFFDCE9FC);
            String label = font.plainSubstrByWidth(suggestions.get(i).getComponent().getString(), w - 10);
            g.drawString(font, label, x + 5, ry + 3, 0xFF1B2432, false);
        }
    }

    private int suggestionRowAt(double mouseX, double mouseY) {
        int rowH = 13;
        int w = Math.min(220, Math.max(110, width / 2));
        int h = suggestions.size() * rowH + 4;
        int x = getX() + width - w - 8;
        int y = getY() + height - h - 5;
        if (mouseX < x || mouseX >= x + w || mouseY < y + 2 || mouseY >= y + h - 2) return -1;
        int row = ((int) mouseY - y - 2) / rowH;
        return row >= 0 && row < suggestions.size() ? row : -1;
    }

    @Override
    protected void updateWidgetNarration(NarrationElementOutput output) {
        defaultButtonNarrationText(output);
    }

    private static void border(GuiGraphics g, int x, int y, int w, int h, int color) {
        g.fill(x, y, x + w, y + 1, color);
        g.fill(x, y + h - 1, x + w, y + h, color);
        g.fill(x, y, x + 1, y + h, color);
        g.fill(x + w - 1, y, x + w, y + h, color);
    }

    private record State(String value, int cursor, int anchor) {
    }
}
