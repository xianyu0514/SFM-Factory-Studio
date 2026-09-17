package io.github.xianynomial.sfmfactorystudio.client.blocks;
import io.github.xianynomial.sfmfactorystudio.client.Loc;
import io.github.xianynomial.sfmfactorystudio.client.blocks.model.CodeEditorLayout;
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
    private static final int BAR = 10;
    private static final int FOOTER = 13;
    private float renderScale = 1;
    private boolean wrap;
    private boolean horizontalBar, verticalBar;
    private Runnable statusAction;
    private int errorStart = -1, errorEnd = -1;
    private int widestLine;
    private List<CodeEditorLayout.Row> rows = List.of(new CodeEditorLayout.Row(0, 0, 0, false));
    private int lastWidth = -1, lastHeight = -1;
    private int scrollDrag; // 1: vertical, 2: horizontal
    private double dragOffset;
    private long lastClick;
    private int lastClickPosition = -1;
    private String search = "";
    private String status = "", shortcutHelp = "";
    private int statusColor = 0xFFBBCBE0;
    private double wheelRemainderX, wheelRemainderY;
    boolean containsPointer(double x, double y) { return visible && inside(x, y); }
    void cancelPointer() { dragging = false; scrollDrag = 0; }
    boolean pointerCaptured() { return visible && (dragging || scrollDrag != 0); }
    void setStatusAction(Runnable action) { statusAction = action; }
    void setStatus(String text, int color, String help) { status = text; statusColor = color; shortcutHelp = help; }

    private long lastTypedAt;
    private int lastTypedCursor = -1;
    private static final Loc POSITION = new Loc("gui.sfmfactorystudio.code_editor.position", "第 %s 行，第 %s 列 · 共 %s 行");
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
        super(x, y, width, height, Component.literal(new Loc("gui.sfmfactorystudio.code_editor.title", "SFML 代码编辑").getString()));
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
        if (Objects.equals(value, clean(next))) return;
        setValue(next, false, false);
        undo.clear();
        redo.clear();
    }
    void setRenderScale(float scale) { renderScale = scale; }
    boolean wrapped() { return wrap; }
    void toggleWrap() {
        wrap = !wrap;
        rebuildRows();
        ensureCursorVisible();
        clearSuggestions();
    }
    String selectedText() { return value.substring(Math.min(cursor, anchor), Math.max(cursor, anchor)); }
    String searchText() { return search; }
    int find(String query, boolean backwards) {
        search = query;
        int hit = CodeEditorLayout.find(value, query, cursor, anchor, backwards);
        if (hit >= 0) {
            anchor = hit;
            cursor = hit + query.length();
            clearSuggestions();
            ensureCursorVisible();
        }
        int count = 0;
        if (!query.isEmpty()) for (int p = 0; (p = value.indexOf(query, p)) >= 0; p += query.length()) count++;
        return count;
    }
    void goToLine(int line) {
        moveTo(lineStart(Math.max(0, line - 1)), false);
        clearSuggestions();
    }
    void resizeViewport() {
        if (lastWidth == width && lastHeight == height) return;
        int previousView = Math.max(LINE_H, lastHeight - PAD * 2 - BAR - FOOTER);
        int caretY = rowOf(cursor) * LINE_H;
        boolean followingCaret = lastHeight < 0 || caretY >= scrollY && caretY + LINE_H <= scrollY + previousView;
        lastWidth = width;
        lastHeight = height;
        rebuildRows();
        if (followingCaret) ensureCursorVisible();
        else {
            scrollY = Mth.clamp(scrollY, 0, maxScrollY());
            scrollX = Mth.clamp(scrollX, 0, maxScrollX());
        }
    }
    private void rebuildRows() {
        // Stable tracks do not alter text wrapping or pane geometry as content changes.
        rows = CodeEditorLayout.rows(value, contentWidth(), wrap,
                text -> font.plainSubstrByWidth(text, contentWidth() - 2).length());
        horizontalBar = !wrap && widestLine + 8 > contentWidth();
        verticalBar = rows.size() * LINE_H > viewHeight();
        if (!horizontalBar) scrollX = 0;
        if (!verticalBar) scrollY = 0;
    }
    int replace(String query, String replacement, boolean all) {
        var result = CodeEditorLayout.replace(value, query, clean(replacement), cursor, anchor, all,
                ca.teamdman.sfml.ast.Program.MAX_PROGRAM_LENGTH);
        if (result.count() <= 0) return result.count();
        if (!value.equals(result.source())) {
            remember();
            value = result.source();
            cursor = anchor = result.caret();
            redo.clear();
            changed();
        }
        search = query;
        return result.count();
    }
    void showError(int oneBasedLine, int column) {
        int line = Math.max(0, Math.min(lineStarts.size() - 1, oneBasedLine - 1));
        int start = lineStart(line);
        // ANTLR counts Unicode code points, the editor stores UTF-16 offsets.
        int count = value.codePointCount(start, lineEnd(line));
        moveTo(value.offsetByCodePoints(start, Math.max(0, Math.min(count, column))), false);
        errorStart = start;
        errorEnd = lineEnd(line);
        clearSuggestions();
    }
    private int rowOf(int offset) { return CodeEditorLayout.rowAt(rows, offset); }
    private int viewHeight() { return Math.max(LINE_H, height - PAD * 2 - BAR - FOOTER); }

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
        errorStart = errorEnd = -1;
        statusAction = null;
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
        lastTypedCursor = -1;
        State now = new State(value, cursor, anchor);
        if (undo.peek() == null || !undo.peek().equals(now)) undo.push(now);
        while (undo.size() > HISTORY_LIMIT) undo.removeLast();
    }

    private void changed() {
        errorStart = errorEnd = -1;
        statusAction = null;
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
        widestLine = 0;
        for (int i = 0; i < lineStarts.size(); i++)
            widestLine = Math.max(widestLine, font.width(value.substring(lineStart(i), lineEnd(i))));
        rebuildRows();
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
        return Math.max(8, width - GUTTER_W - PAD * 2 - BAR);
    }

    private int visibleLines() {
        return Math.max(1, viewHeight() / LINE_H);
    }

    private int maxScrollY() {
        return CodeEditorLayout.maxScroll(rows.size() * LINE_H, viewHeight());
    }
    private int maxScrollX() {
        return horizontalBar ? CodeEditorLayout.maxScroll(widestLine + 8, contentWidth()) : 0;
    }
    private void ensureCursorVisible() {
        int row = rowOf(cursor);
        int cx = font.width(value.substring(rows.get(row).start(), cursor));
        int cy = row * LINE_H;
        if (cy < scrollY) scrollY = cy;
        if (cy + LINE_H > scrollY + viewHeight()) scrollY = cy + LINE_H - viewHeight();
        if (cx < scrollX) scrollX = cx;
        if (cx + 8 > scrollX + contentWidth()) scrollX = cx + 8 - contentWidth();
        scrollY = Mth.clamp(scrollY, 0, maxScrollY());
        scrollX = Mth.clamp(scrollX, 0, maxScrollX());
        focusedAt = Util.getMillis();
    }

    private void replaceSelection(String inserted) { replaceSelection(inserted, false); }

    private void replaceSelection(String inserted, boolean typing) {
        String text = clean(inserted);
        int from = Math.min(cursor, anchor), to = Math.max(cursor, anchor);
        long now = Util.getMillis();
        boolean coalesce = typing && cursor == anchor && cursor == lastTypedCursor && now - lastTypedAt < 750;
        if (!coalesce) remember();
        value = value.substring(0, from) + text + value.substring(to);
        cursor = from + text.length();
        lastTypedAt = now;
        lastTypedCursor = typing ? cursor : -1;
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
            anchor = value.offsetByCodePoints(cursor, -1);
            replaceSelection("");
        } else if (direction > 0 && cursor < value.length()) {
            anchor = value.offsetByCodePoints(cursor, 1);
            replaceSelection("");
        }
    }

    private void moveTo(int target, boolean selecting) {
        lastTypedCursor = -1;
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
        int row = rowOf(cursor);
        if (preferredColumn < 0) preferredColumn = font.width(value.substring(rows.get(row).start(), cursor));
        var target = rows.get(Mth.clamp(row + delta, 0, rows.size() - 1));
        int column = font.plainSubstrByWidth(value.substring(target.start(), target.end()), preferredColumn).length();
        moveTo(target.start() + column, selecting);
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

    void undo() {
        if (undo.isEmpty()) return;
        redo.push(new State(value, cursor, anchor));
        restore(undo.pop());
    }

    void redo() {
        if (redo.isEmpty()) return;
        undo.push(new State(value, cursor, anchor));
        restore(redo.pop());
    }

    private void restore(State state) {
        lastTypedCursor = -1;
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
        if (!visible || !isFocused()) return false;
        suppressNextTypedCharacter = false;
        boolean ctrl = Screen.hasControlDown();
        boolean shift = Screen.hasShiftDown();
        if (ctrl || keyCode >= 256 && keyCode <= 269) lastTypedCursor = -1;
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
            if (!shift && !ctrl && cursor != anchor) {
                moveTo(keyCode == 263 ? Math.min(cursor, anchor) : Math.max(cursor, anchor), false);
                preferredColumn = -1;
                clearSuggestions();
                return true;
            }
            int target = keyCode == 263 ? (ctrl ? previousWord() : cursor > 0 ? value.offsetByCodePoints(cursor, -1) : 0) : (ctrl ? nextWord() : cursor < value.length() ? value.offsetByCodePoints(cursor, 1) : value.length());
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
        replaceSelection(Character.toString(codePoint), true);
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
        if (mouseX >= getX() + width - BAR && mouseY < getY() + PAD + viewHeight()) {
            clearSuggestions();
            dragOffset = mouseY - verticalThumbY();
            if (dragOffset >= 0 && dragOffset < verticalThumbSize() && maxScrollY() > 0) scrollDrag = 1;
            else scrollY = Mth.clamp(scrollY + (dragOffset < 0 ? -viewHeight() : viewHeight()), 0, maxScrollY());
            return true;
        }
        if (mouseY >= getY() + height - FOOTER - BAR && mouseY < getY() + height - FOOTER) {
            clearSuggestions();
            dragOffset = mouseX - horizontalThumbX();
            if (dragOffset >= 0 && dragOffset < horizontalThumbSize() && maxScrollX() > 0) scrollDrag = 2;
            else scrollX = Mth.clamp(scrollX + (dragOffset < 0 ? -contentWidth() : contentWidth()), 0, maxScrollX());
            return true;
        }
        if (mouseY >= getY() + height - FOOTER) {
            if (statusAction != null) statusAction.run();
            return true;
        }
        dragging = true;
        seek(mouseX, mouseY, Screen.hasShiftDown());
        long now = Util.getMillis();
        if (!Screen.hasShiftDown() && now - lastClick < 300 && cursor == lastClickPosition) {
            int start = cursor, end = cursor;
            while (start > 0 && wordCharacter(value.charAt(start - 1))) start--;
            while (end < value.length() && wordCharacter(value.charAt(end))) end++;
            anchor = start;
            cursor = end;
        }
        lastClick = now;
        lastClickPosition = cursor;
        return true;
    }

    @Override
    public boolean mouseDragged(double mouseX, double mouseY, int button, double dragX, double dragY) {
        if (button != 0) return false;
        if (scrollDrag != 0) { dragScroll(mouseX, mouseY); return true; }
        if (!dragging) return false;
        seek(mouseX, mouseY, true);
        return true;
    }

    @Override
    public boolean mouseReleased(double mouseX, double mouseY, int button) {
        boolean handled = dragging || scrollDrag != 0;
        dragging = false;
        scrollDrag = 0;
        return handled || button == 0 && inside(mouseX, mouseY);
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double scrollXDelta, double scrollYDelta) {
        if (!visible || !inside(mouseX, mouseY)) return false;
        double horizontal = Screen.hasShiftDown() && !wrap ? scrollYDelta : scrollXDelta;
        double vertical = Screen.hasShiftDown() && !wrap ? 0 : scrollYDelta;
        wheelRemainderX += horizontal * 24;
        wheelRemainderY += vertical * LINE_H * 3;
        int stepX = (int) wheelRemainderX, stepY = (int) wheelRemainderY;
        wheelRemainderX -= stepX;
        wheelRemainderY -= stepY;
        scrollX = Mth.clamp(scrollX - stepX, 0, maxScrollX());
        scrollY = Mth.clamp(scrollY - stepY, 0, maxScrollY());
        clearSuggestions();
        return true;
    }

    private boolean inside(double mx, double my) {
        return mx >= getX() && mx < getX() + width && my >= getY() && my < getY() + height;
    }

    private void seek(double mx, double my, boolean selecting) {
        int row = Mth.clamp((int) Math.floor((my - getY() - PAD + scrollY) / LINE_H), 0, rows.size() - 1);
        var r = rows.get(row);
        String text = value.substring(r.start(), r.end());
        int px = Math.max(0, (int) mx - (getX() + GUTTER_W + PAD) + scrollX);
        int col = font.plainSubstrByWidth(text, px).length();
        if (col < text.length()) {
            int before = font.width(text.substring(0, col));
            int glyph = font.width(text.substring(col, col + 1));
            if (px - before >= glyph / 2) col++;
        }
        moveTo(r.start() + col, selecting);
        clearSuggestions();
        preferredColumn = -1;
    }

    @Override
    public void setFocused(boolean focused) {
        super.setFocused(focused);
        if (focused) focusedAt = Util.getMillis();
        else { dragging = false; scrollDrag = 0; }
    }

    private static boolean wordCharacter(char c) {
        return Character.isLetterOrDigit(c) || "_:/#.*-".indexOf(c) >= 0;
    }
    private int verticalThumbSize() { return Math.min(viewHeight(), Math.max(12, viewHeight() * viewHeight() / Math.max(1, viewHeight() + maxScrollY()))); }
    private int verticalThumbY() { return getY() + PAD + (viewHeight() - verticalThumbSize()) * scrollY / Math.max(1, maxScrollY()); }
    private int horizontalThumbSize() { return Math.min(contentWidth(), Math.max(12, contentWidth() * contentWidth() / Math.max(1, contentWidth() + maxScrollX()))); }
    private int horizontalThumbX() { return getX() + GUTTER_W + PAD + (contentWidth() - horizontalThumbSize()) * scrollX / Math.max(1, maxScrollX()); }
    private void dragScroll(double mx, double my) {
        clearSuggestions();
        if (scrollDrag == 1) scrollY = Mth.clamp((int) Math.round((my - getY() - PAD - dragOffset)
                * maxScrollY() / Math.max(1, viewHeight() - verticalThumbSize())), 0, maxScrollY());
        else scrollX = Mth.clamp((int) Math.round((mx - getX() - GUTTER_W - PAD - dragOffset)
                * maxScrollX() / Math.max(1, contentWidth() - horizontalThumbSize())), 0, maxScrollX());
    }
    private void clip(GuiGraphics g, int x, int y, int right, int bottom) {
        g.enableScissor(Math.round(x * renderScale), Math.round(y * renderScale),
                Math.round(right * renderScale), Math.round(bottom * renderScale));
    }
    private Component rowComponent(CodeEditorLayout.Row row) {
        if (row.line() >= highlighted.size()) return Component.literal(value.substring(row.start(), row.end()));
        int from = row.start() - lineStart(row.line()), to = row.end() - lineStart(row.line());
        MutableComponent result = Component.empty();
        int[] offset = {0};
        highlighted.get(row.line()).visit((style, text) -> {
            int a = Math.max(0, from - offset[0]), b = Math.min(text.length(), to - offset[0]);
            if (b > a) result.append(Component.literal(text.substring(a, b)).setStyle(style));
            offset[0] += text.length();
            return java.util.Optional.empty();
        }, net.minecraft.network.chat.Style.EMPTY);
        return result;
    }
    @Override
    protected void renderWidget(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
        if (!visible) return;
        resizeViewport();
        if (highlightStale) {
            highlighted = SfmlHighlight.lines(value);
            highlightStale = false;
        }
        g.fill(getX(), getY(), getX() + width, getY() + height, 0xFF111827);
        g.fill(getX(), getY(), getX() + GUTTER_W, getY() + height - FOOTER, 0xFF182234);
        border(g, getX(), getY(), width, height, isFocused() ? 0xFF2F6FED : 0xFFB8C5D6);
        int first = Math.max(0, scrollY / LINE_H);
        int caretRow = rowOf(cursor);
        int last = Math.min(rows.size(), first + visibleLines() + 2);
        int selectionStart = Math.min(cursor, anchor), selectionEnd = Math.max(cursor, anchor);
        int textX = getX() + GUTTER_W + PAD - scrollX;
        clip(g, getX() + 1, getY() + PAD, getX() + GUTTER_W, getY() + PAD + viewHeight());
        for (int i = first; i < last; i++) {
            var row = rows.get(i);
            String number = row.continuation() ? ">" : Integer.toString(row.line() + 1);
            g.drawString(font, number, getX() + GUTTER_W - 5 - font.width(number),
                    getY() + PAD + i * LINE_H - scrollY, 0xFF8C9AAF, false);
        }
        g.disableScissor();
        clip(g, getX() + GUTTER_W + PAD, getY() + PAD,
                getX() + GUTTER_W + PAD + contentWidth(), getY() + PAD + viewHeight());
        for (int i = first; i < last; i++) {
            var row = rows.get(i);
            int y = getY() + PAD + i * LINE_H - scrollY;
            int start = row.start(), end = row.end();
            if (errorStart >= 0 && start >= errorStart && start <= errorEnd)
                g.fill(getX() + GUTTER_W + PAD, y - 1, getX() + GUTTER_W + PAD + contentWidth(), y + 9, 0x554D2020);
            if (i == caretRow) g.fill(getX() + GUTTER_W + PAD, y - 1, getX() + GUTTER_W + PAD + contentWidth(), y + 9, 0x222E5075);
            if (selectionEnd > start && selectionStart <= end) {
                int from = Math.max(start, selectionStart), to = Math.min(end, selectionEnd);
                if (to >= from) {
                    int x1 = textX + font.width(value.substring(start, from));
                    int x2 = textX + font.width(value.substring(start, to));
                    if (selectionEnd > end) x2 += 3;
                    g.fill(x1, y - 1, Math.max(x1 + 1, x2), y + 9, 0x883B82F6);
                }
            }
            g.drawString(font, rowComponent(row), textX, y, 0xFFE7EDF7, false);
        }
        if (isFocused() && (Util.getMillis() - focusedAt) / 500L % 2L == 0L) {
            int row = rowOf(cursor);
            int cx = textX + font.width(value.substring(rows.get(row).start(), cursor));
            int cy = getY() + PAD + row * LINE_H - scrollY;
            g.fill(cx, cy - 1, cx + 1, cy + 9, 0xFFF8FAFC);
        }
        g.disableScissor();
        int bottom = getY() + height - FOOTER;
        g.fill(getX() + width - BAR, getY() + PAD, getX() + width - 1, getY() + PAD + viewHeight(), 0xFF202D40);
        g.fill(getX() + GUTTER_W + PAD, bottom - BAR, getX() + GUTTER_W + PAD + contentWidth(), bottom - 1, 0xFF202D40);
        g.fill(getX() + width - BAR + 1, verticalThumbY(), getX() + width - 2, verticalThumbY() + verticalThumbSize(), verticalBar ? 0xFF9CBEF5 : 0xFF52627A);
        g.fill(horizontalThumbX(), bottom - BAR + 1, horizontalThumbX() + horizontalThumbSize(), bottom - 2, horizontalBar ? 0xFF9CBEF5 : 0xFF52627A);
        g.fill(getX() + 1, bottom, getX() + width - 1, getY() + height - 1, 0xFF182234);
        String position = POSITION.getString(lineOf(cursor) + 1, cursor - lineStart(lineOf(cursor)) + 1, lineStarts.size());
        int positionWidth = Math.min(font.width(position), Math.max(1, width / 2));
        int statusWidth = Math.max(1, width - positionWidth - 16);
        String compact = font.plainSubstrByWidth(status, Math.max(1, statusWidth - 6));
        if (font.width(status) > statusWidth) compact += "…";
        g.drawString(font, compact, getX() + 4, bottom + 2, statusColor == 0xFFD13438 ? 0xFFFF9B9B : 0xFFBBCBE0, false);
        g.drawString(font, font.plainSubstrByWidth(position, positionWidth),
                getX() + width - positionWidth - 4, bottom + 2, 0xFFBBCBE0, false);
        if (mouseY >= bottom && inside(mouseX, mouseY))
            g.renderTooltip(font, font.split(Component.literal(status + "\n" + position + "\n" + shortcutHelp),
                    Math.max(80, Math.min(280, width - 12))), mouseX, mouseY);
        renderSuggestions(g, mouseX, mouseY);
    }

    private record SuggestionBox(int x, int y, int width, int count, int first) {}
    private SuggestionBox suggestionBox() {
        int caretY = getY() + PAD + rowOf(cursor) * LINE_H - scrollY;
        int top = getY() + PAD, bottom = top + viewHeight();
        int below = Math.max(0, bottom - caretY - LINE_H - 3);
        int above = Math.max(0, caretY - top - 3);
        boolean down = below >= above;
        int count = Math.min(suggestions.size(), Math.max(0, ((down ? below : above) - 4) / 13));
        int h = count * 13 + 4;
        int w = Math.max(20, Math.min(220, width - 16));
        int caretX = getX() + GUTTER_W + PAD + font.width(value.substring(rows.get(rowOf(cursor)).start(), cursor)) - scrollX;
        int x = Mth.clamp(caretX, getX() + 4, getX() + width - w - 4);
        int y = down ? caretY + LINE_H + 3 : caretY - h - 3;
        return new SuggestionBox(x, y, w, count, Math.max(0, selectedSuggestion - count + 1));
    }
    private void renderSuggestions(GuiGraphics g, int mouseX, int mouseY) {
        if (!isFocused() || suggestions.isEmpty()) return;
        var box = suggestionBox();
        if (box.count() == 0) return;
        int x = box.x(), y = box.y(), w = box.width(), h = box.count() * 13 + 4;
        g.fill(x, y, x + w, y + h, 0xF7FFFFFF);
        border(g, x, y, w, h, 0xFF9FB0C5);
        for (int i = box.first(); i < box.first() + box.count(); i++) {
            int ry = y + 2 + (i - box.first()) * 13;
            if (i == selectedSuggestion) g.fill(x + 2, ry, x + w - 2, ry + 13, 0xFFDCE9FC);
            String label = font.plainSubstrByWidth(suggestions.get(i).getComponent().getString(), w - 10);
            g.drawString(font, label, x + 5, ry + 3, 0xFF1B2432, false);
        }
    }
    private int suggestionRowAt(double mouseX, double mouseY) {
        var box = suggestionBox();
        if (box.count() == 0 || mouseX < box.x() || mouseX >= box.x() + box.width()
                || mouseY < box.y() + 2 || mouseY >= box.y() + 2 + box.count() * 13) return -1;
        return box.first() + ((int) mouseY - box.y() - 2) / 13;
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
