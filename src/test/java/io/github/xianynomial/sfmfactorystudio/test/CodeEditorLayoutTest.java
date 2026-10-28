package io.github.xianynomial.sfmfactorystudio.test;

import io.github.xianynomial.sfmfactorystudio.client.blocks.model.CodeEditorLayout;
import org.junit.jupiter.api.Test;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

class CodeEditorLayoutTest {
    @Test void shortWindowsReserveSpaceWithoutLeavingScreenBounds() {
        for (int w : new int[]{160, 320, 640, 1920}) for (int h : new int[]{60, 90, 150, 360, 1080}) {
            var frame = io.github.xianynomial.sfmfactorystudio.client.blocks.model.EditorUiMath.virtualFrame(w, h, 640, 240);
            assertTrue(frame.height() >= 240);
            assertTrue(frame.width() >= 640);
            assertEquals(w, frame.width() * frame.scale(), 1f);
            assertEquals(h, frame.height() * frame.scale(), 1f);
        }
    }
    @Test void emptyDocumentStillHasAnEditableRow() {
        var rows = CodeEditorLayout.rows("", 10, true, s -> Math.min(10, s.length()));
        assertEquals(List.of(new CodeEditorLayout.Row(0, 0, 0, false)), rows);
    }
    @Test void wrappingPreservesEverySourceCharacterAndRealLineNumber() {
        String source = "INPUT FROM warehouse\n\nOUTPUT TO target\n";
        var rows = CodeEditorLayout.rows(source, 5, true, s -> Math.min(5, s.length()));
        StringBuilder restored = new StringBuilder();
        int previousLine = 0;
        for (var row : rows) {
            if (row.line() != previousLine) restored.append('\n');
            restored.append(source, row.start(), row.end());
            previousLine = row.line();
        }
        assertEquals(source, restored.toString());
        assertEquals(3, rows.get(rows.size() - 1).line());
        assertEquals(source.length(), rows.get(rows.size() - 1).start());
        assertTrue(rows.stream().anyMatch(CodeEditorLayout.Row::continuation));
    }
    @Test void exactWrapBoundaryUsesNextDisplayRow() {
        var rows = CodeEditorLayout.rows("abcdef", 3, true, s -> Math.min(3, s.length()));
        assertEquals(0, CodeEditorLayout.rowAt(rows, 2));
        assertEquals(1, CodeEditorLayout.rowAt(rows, 3));
        assertEquals(1, CodeEditorLayout.rowAt(rows, 6));
    }
    @Test void noWrapAndTrailingNewlineKeepLogicalRows() {
        var rows = CodeEditorLayout.rows("abcdef\n", 1, false, s -> 1);
        assertEquals(List.of(new CodeEditorLayout.Row(0, 6, 0, false),
                new CodeEditorLayout.Row(7, 7, 1, false)), rows);
    }
    @Test void tinyViewportAlwaysMakesProgressWithoutSplittingUnicode() {
        String source = "A😀中文B";
        var rows = CodeEditorLayout.rows(source, 0, true, s -> 0);
        StringBuilder result = new StringBuilder();
        for (var row : rows) {
            String part = source.substring(row.start(), row.end());
            assertFalse(Character.isLowSurrogate(part.charAt(0)));
            assertFalse(Character.isHighSurrogate(part.charAt(part.length() - 1)));
            result.append(part);
        }
        assertEquals(source, result.toString());
    }
    @Test void allPositionsAreAccessibleAcrossViewportWidths() {
        String source = "a".repeat(800) + "\n\n" + "b".repeat(1200);
        for (int width : new int[]{1, 2, 7, 40, 120, 1000}) {
            var rows = CodeEditorLayout.rows(source, width, true, s -> Math.min(width, s.length()));
            for (int p = 0; p <= source.length(); p++) {
                var row = rows.get(CodeEditorLayout.rowAt(rows, p));
                assertTrue(row.start() <= p && p <= row.end(), "offset " + p + " width " + width);
            }
        }
    }
    @Test void scrollRangeReachesLastRowAndLongestLine() {
        for (int viewport : new int[]{1, 10, 37, 200, 10000}) {
            int extent = 1000 * 10 + 10;
            assertTrue(CodeEditorLayout.maxScroll(extent, viewport) + viewport >= extent);
            assertTrue(CodeEditorLayout.maxScroll(12345, viewport) + viewport >= 12345);
        }
        assertEquals(0, CodeEditorLayout.maxScroll(5, 100));
    }
    @Test void findWrapsBothWaysAndHonorsSelectionDirection() {
        String source = "INPUT a\nINPUT b\nINPUT c";
        assertEquals(8, CodeEditorLayout.find(source, "INPUT", 5, 0, false));
        assertEquals(8, CodeEditorLayout.find(source, "INPUT", 0, 5, false));
        assertEquals(0, CodeEditorLayout.find(source, "INPUT", 13, 8, true));
        assertEquals(16, CodeEditorLayout.find(source, "INPUT", 0, 0, true));
        assertEquals(0, CodeEditorLayout.find(source, "INPUT", source.length(), source.length(), false));
        assertEquals(-1, CodeEditorLayout.find(source, "", 0, 0, false));
        assertEquals(-1, CodeEditorLayout.find(source, "missing", 0, 0, false));
    }
}
