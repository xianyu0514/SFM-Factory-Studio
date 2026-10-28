package io.github.xianynomial.sfmfactorystudio.client.blocks.model;

import ca.teamdman.sfml.program_builder.ProgramBuildResult;
import ca.teamdman.sfml.program_builder.ProgramBuilder;
import net.minecraft.network.chat.Component;

import java.util.List;

/**
 * Runs generated SFML through SFM's own compiler so the save button can refuse
 * to write a broken program. Returns the list of compile errors (empty = OK).
 */
public final class SfmlValidate {
    private SfmlValidate() {
    }

    public record Diagnostic(String message, CodeEditorLayout.ErrorPosition position) {}
    public static List<String> check(String sfml) {
        return diagnose(sfml).stream().map(Diagnostic::message).toList();
    }
    public static List<Diagnostic> diagnose(String sfml) {
        try {
            ProgramBuildResult result = new ProgramBuilder(sfml).useCache(false).build();
            return result.metadata().errors().stream().map(c -> {
                String message = Component.translatable(c.getKey(), c.getArgs()).getString();
                // Read compiler arguments before localization; translated UI text
                // must not determine source positions.
                var position = CodeEditorLayout.errorPosition(java.util.Arrays.deepToString(c.getArgs()));
                return new Diagnostic(message, position);
            }).toList();
        } catch (Throwable t) {
            return List.of(new Diagnostic(String.valueOf(t.getMessage()), null));
        }
    }
}
