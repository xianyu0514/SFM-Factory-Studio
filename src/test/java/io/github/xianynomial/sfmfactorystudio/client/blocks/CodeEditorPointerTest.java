package io.github.xianynomial.sfmfactorystudio.client.blocks;
import net.minecraft.client.gui.Font;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/** Exercise the real editor input and resize handlers without a graphics context. */
class CodeEditorPointerTest {
    private SfmlCodeEditor editor() {
        Font font = new Font(id -> null, false) {
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
    private int offset(SfmlCodeEditor editor) throws Exception {
        var field = SfmlCodeEditor.class.getDeclaredField("scrollY");
        field.setAccessible(true);
        return field.getInt(editor);
    }
    @Test void scrollbarCapturesPointerThroughDraggingOutsideAndRelease() throws Exception {
        var editor = editor();
        assertTrue(editor.mouseClicked(198, 6, 0));
        assertTrue(editor.pointerCaptured());
        assertEquals(0, offset(editor), "grabbing the thumb must not move it");
        assertTrue(editor.mouseDragged(400, 75, 0, 202, 69));
        assertTrue(offset(editor) > 0);
        assertTrue(editor.mouseReleased(400, 500, 0));
        assertFalse(editor.pointerCaptured());
    }
    @Test void resizingScrolledDocumentDoesNotJumpBackToOffscreenCaret() throws Exception {
        var editor = editor();
        editor.mouseClicked(198, 6, 0);
        editor.mouseDragged(198, 45, 0, 0, 39);
        editor.mouseReleased(198, 45, 0);
        int before = offset(editor);
        assertTrue(before > 0);
        editor.setHeight(140);
        editor.resizeViewport();
        assertEquals(before, offset(editor));
        assertEquals(0, editor.cursorPosition());
    }
    @Test void dividerCanCancelPreviouslyCapturedTextOrScrollbarInput() {
        var editor = editor();
        editor.mouseClicked(198, 6, 0);
        assertTrue(editor.pointerCaptured());
        editor.cancelPointer();
        assertFalse(editor.pointerCaptured());
        assertFalse(editor.mouseDragged(198, 45, 0, 0, 39));
    }
    @Test void horizontalThumbReachesLongLineEndAndWrappingResetsHorizontalScroll() throws Exception {
        var editor = editor();
        String source = "warehouse_".repeat(100);
        editor.setValueFromModel(source);
        assertTrue(editor.mouseClicked(42, 80, 0));
        assertTrue(editor.pointerCaptured());
        editor.mouseDragged(400, 80, 0, 358, 0);
        editor.mouseReleased(400, 80, 0);
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
