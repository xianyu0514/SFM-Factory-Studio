package io.github.xianynomial.sfmfactorystudio.client.blocks;
import net.minecraft.client.gui.Font;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/** Exercise the real editor input and resize handlers without a graphics context. */
class CodeEditorPointerTest {
    private SfmlCodeEditor editor() {
        Font font = new Font(new Font.Provider() {
            @Override public net.minecraft.client.gui.GlyphSource glyphs(
                    net.minecraft.network.chat.FontDescription d) { return null; }
            @Override public net.minecraft.client.gui.font.glyphs.EffectGlyph effect() { return null; }
        }) {
            @Override public int width(String text) { return text.length() * 6; }
            @Override public String plainSubstrByWidth(String text, int width) {
                return text.substring(0, Math.min(text.length(), Math.max(0, width / 6)));
            }
        };
        var editor = new SfmlCodeEditor(font, 0, 0, 200, 100, text -> {});
        editor.setValueFromModel("INPUT FROM warehouse\n".repeat(100));
        editor.resizeViewport();
        return editor;
    }
    /** 26.1 的鼠标事件是 record：MouseButtonEvent(x, y, MouseButtonInfo(button, modifiers))。 */
    private static net.minecraft.client.input.MouseButtonEvent mb(double x, double y, int button) {
        return new net.minecraft.client.input.MouseButtonEvent(
                x, y, new net.minecraft.client.input.MouseButtonInfo(button, 0));
    }

    private int offset(SfmlCodeEditor editor) throws Exception {
        var field = SfmlCodeEditor.class.getDeclaredField("scrollY");
        field.setAccessible(true);
        return field.getInt(editor);
    }
    @Test void scrollbarCapturesPointerThroughDraggingOutsideAndRelease() throws Exception {
        var editor = editor();
        assertTrue(editor.mouseClicked(mb(198, 6, 0), false));
        assertTrue(editor.pointerCaptured());
        assertEquals(0, offset(editor), "grabbing the thumb must not move it");
        assertTrue(editor.mouseDragged(mb(400, 75, 0), 202, 69));
        assertTrue(offset(editor) > 0);
        assertTrue(editor.mouseReleased(mb(400, 500, 0)));
        assertFalse(editor.pointerCaptured());
    }
    @Test void resizingScrolledDocumentDoesNotJumpBackToOffscreenCaret() throws Exception {
        var editor = editor();
        editor.mouseClicked(mb(198, 6, 0), false);
        editor.mouseDragged(mb(198, 45, 0), 0, 39);
        editor.mouseReleased(mb(198, 45, 0));
        int before = offset(editor);
        assertTrue(before > 0);
        editor.setHeight(140);
        editor.resizeViewport();
        assertEquals(before, offset(editor));
        assertEquals(0, editor.cursorPosition());
    }
    @Test void dividerCanCancelPreviouslyCapturedTextOrScrollbarInput() {
        var editor = editor();
        editor.mouseClicked(mb(198, 6, 0), false);
        assertTrue(editor.pointerCaptured());
        editor.cancelPointer();
        assertFalse(editor.pointerCaptured());
        assertFalse(editor.mouseDragged(mb(198, 45, 0), 0, 39));
    }
    @Test void horizontalThumbReachesLongLineEndAndWrappingResetsHorizontalScroll() throws Exception {
        var editor = editor();
        String source = "warehouse_".repeat(100);
        editor.setValueFromModel(source);
        assertTrue(editor.mouseClicked(mb(42, 80, 0), false));
        assertTrue(editor.pointerCaptured());
        editor.mouseDragged(mb(400, 80, 0), 358, 0);
        editor.mouseReleased(mb(400, 80, 0));
        var scroll = SfmlCodeEditor.class.getDeclaredField("scrollX");
        scroll.setAccessible(true);
        assertEquals(source.length() * 6 + 8 - 146, scroll.getInt(editor));
        editor.toggleWrap();
        assertEquals(0, scroll.getInt(editor));
        assertEquals(source, editor.value());
    }
    @Test void replaceAllRemainsOneUndoStepAcrossResizeAndWrapChanges() {
        var editor = editor();
        String before = editor.value();
        assertEquals(100, editor.replace("warehouse", "storage", true));
        String after = editor.value();
        editor.setWidth(140);
        editor.resizeViewport();
        editor.toggleWrap();
        editor.undo();
        assertEquals(before, editor.value());
        editor.redo();
        assertEquals(after, editor.value());
    }
}
