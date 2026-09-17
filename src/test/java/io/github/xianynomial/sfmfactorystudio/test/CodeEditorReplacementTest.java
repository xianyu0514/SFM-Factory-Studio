package io.github.xianynomial.sfmfactorystudio.test;

import io.github.xianynomial.sfmfactorystudio.client.blocks.model.CodeEditorLayout;
import io.github.xianynomial.sfmfactorystudio.client.blocks.model.SfmlValidate;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class CodeEditorReplacementTest {
    @Test void literalReplacementDoesNotInterpretRegexOrReplacementSyntax() {
        var r = CodeEditorLayout.replace("a.* a.*", "a.*", "$1\\x", 0, 0, true, 1000);
        assertEquals("$1\\x $1\\x", r.source());
        assertEquals(2, r.count());
    }
    @Test void replacementDoesNotSearchInsertedTextAgain() {
        var r = CodeEditorLayout.replace("aa aa", "aa", "aaaa", 0, 0, true, 1000);
        assertEquals("aaaa aaaa", r.source());
        assertEquals(2, r.count());
    }
    @Test void selectedMatchIsReplacedBeforeTheNextMatch() {
        var r = CodeEditorLayout.replace("old old", "old", "new", 3, 0, false, 1000);
        assertEquals("new old", r.source());
        assertEquals(3, r.caret());
    }
    @Test void nextReplacementWrapsAtEndOfDocument() {
        var r = CodeEditorLayout.replace("old old", "old", "new", 7, 7, false, 1000);
        assertEquals("new old", r.source());
        assertEquals(1, r.count());
    }
    @Test void whitespaceDeletionAndUnicodeArePreserved() {
        assertEquals("中文\n", CodeEditorLayout.replace(" 中文 \n", " ", "", 0, 0, true, 1000).source());
        assertEquals("a😀b", CodeEditorLayout.replace("a中b", "中", "😀", 0, 0, false, 1000).source());
    }
    @Test void invalidOrOversizedReplacementLeavesSourceUnchanged() {
        var empty = CodeEditorLayout.replace("abc", "", "x", 0, 0, true, 10);
        assertEquals("abc", empty.source());
        assertEquals(0, empty.count());
        var large = CodeEditorLayout.replace("aaa", "a", "123456", 0, 0, true, 10);
        assertEquals("aaa", large.source());
        assertEquals(-1, large.count());
    }
    @Test void positionsOnlyComeFromRecognizedCompilerLocationSyntax() {
        assertEquals(new CodeEditorLayout.ErrorPosition(12, 0), CodeEditorLayout.errorPosition("[line 12:0 missing END]"));
        assertNull(CodeEditorLayout.errorPosition("Unknown label warehouse42"));
        assertNull(CodeEditorLayout.errorPosition("line 99999999999999999999:0"));
        assertNull(CodeEditorLayout.errorPosition("line 0:0"));
    }
    @Test void realCompilerSyntaxErrorsKeepNavigableLocations() {
        var errors = SfmlValidate.diagnose("EVERY 20 TICKS DO\nINPUT FROM\nEND");
        assertFalse(errors.isEmpty());
        assertTrue(errors.stream().anyMatch(e -> e.position() != null && e.position().line() >= 2), errors.toString());
    }
}
