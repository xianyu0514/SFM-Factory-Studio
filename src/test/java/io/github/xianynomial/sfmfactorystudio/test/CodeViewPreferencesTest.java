package io.github.xianynomial.sfmfactorystudio.test;

import com.google.gson.Gson;
import io.github.xianynomial.sfmfactorystudio.client.blocks.model.CodePaneLayout;
import io.github.xianynomial.sfmfactorystudio.client.blocks.model.CodeViewPreferences;
import org.junit.jupiter.api.Test;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

class CodeViewPreferencesTest {
    @Test void firstOpenShowsSixtyFortySplitWithoutRequestingFocus() {
        var prefs = CodeViewPreferences.read(null);
        assertTrue(prefs.expanded());
        assertFalse(prefs.focused());
        assertEquals(.4, prefs.fraction());
        var split = CodePaneLayout.split(0, 506, prefs.fraction());
        assertEquals(300, split.canvasHeight());
        assertEquals(200, split.codeHeight());
    }
    @Test void collapsedAndFocusedChoicesSurviveRealJsonRoundTrip() {
        for (boolean expanded : new boolean[]{false, true}) for (boolean focused : new boolean[]{false, true}) {
            var prefs = new CodeViewPreferences(.37, focused, true, expanded);
            var gson = new Gson();
            assertEquals(prefs, CodeViewPreferences.read(gson.fromJson(gson.toJson(prefs.write()), List.class)));
        }
    }
    @Test void oldThreeFieldSettingsKeepRatioWrapAndFocus() {
        assertEquals(new CodeViewPreferences(.65, true, true, true),
                CodeViewPreferences.read(List.of(List.of(.65, 1, 1))));
    }
    @Test void compactWindowsKeepBlocksUnlessPlayerExplicitlyChoosesCode() {
        boolean fits = CodePaneLayout.canSplit(270, 220);
        assertFalse(fits);
        assertFalse(CodeViewPreferences.defaults().showCode(fits));
        assertTrue(new CodeViewPreferences(.4, true, false, true).showCode(fits));
        assertFalse(new CodeViewPreferences(.4, true, false, false).showCode(true));
        assertTrue(CodeViewPreferences.defaults().showCode(CodePaneLayout.canSplit(400, 400)));
    }
    @Test void compactFallbackDoesNotOverwritePreferredExpandedStateOrRatio() {
        var prefs = new CodeViewPreferences(.42, false, true, true);
        assertFalse(prefs.showCode(false));
        var restored = CodeViewPreferences.read(prefs.write());
        assertTrue(restored.showCode(true));
        assertEquals(.42, restored.fraction());
    }
    @Test void damagedSettingsFallBackSafely() {
        assertEquals(CodeViewPreferences.defaults(), CodeViewPreferences.read(List.of("bad")));
        assertEquals(.4, CodeViewPreferences.read(List.of(List.of(Double.NaN, 0, 0))).fraction());
        assertEquals(1, CodeViewPreferences.read(List.of(List.of(90, 0, 0))).fraction());
    }
}
