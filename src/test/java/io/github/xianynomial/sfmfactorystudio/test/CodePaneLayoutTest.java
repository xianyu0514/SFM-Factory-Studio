package io.github.xianynomial.sfmfactorystudio.test;
import io.github.xianynomial.sfmfactorystudio.client.blocks.model.CodePaneLayout;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
class CodePaneLayoutTest {
    @Test void grabbingDividerDoesNotMoveEitherPane() {
        for (int top : new int[]{0, 34, 126}) for (int h : new int[]{200, 260, 600})
            for (double fraction : new double[]{0, .2, .5, .8, 1}) for (double offset : new double[]{-2.5, 0, 2.5}) {
                var before = CodePaneLayout.split(top, top + h, fraction);
                double next = CodePaneLayout.dragFraction(top, top + h, before.dividerY() + offset, offset);
                assertEquals(before, CodePaneLayout.split(top, top + h, next));
            }
    }
    @Test void dividerTracksMouseDistanceExactlyAwayFromLimits() {
        var before = CodePaneLayout.split(40, 640, .5);
        double next = CodePaneLayout.dragFraction(40, 640, before.dividerY() + 37 + 2, 2);
        var after = CodePaneLayout.split(40, 640, next);
        assertEquals(before.dividerY() + 37, after.dividerY());
        assertEquals(before.codeHeight() - 37, after.codeHeight());
    }
    @Test void bothPanesStayInsideTheirSharedBoundsAtDragLimits() {
        for (double y : new double[]{-10000, 0, 100, 200, 10000}) {
            var split = CodePaneLayout.split(20, 260, CodePaneLayout.dragFraction(20, 260, y, 2));
            assertTrue(split.canvasHeight() >= 64);
            assertTrue(split.codeHeight() >= 80);
            assertEquals(260, split.codeTop() + split.codeHeight());
            assertEquals(CodePaneLayout.GAP, split.codeTop() - 20 - split.canvasHeight());
        }
    }
}
