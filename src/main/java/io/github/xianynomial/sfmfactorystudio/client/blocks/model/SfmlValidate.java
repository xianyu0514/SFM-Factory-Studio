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

    public static List<String> check(String sfml) {
        try {
            ProgramBuildResult result = new ProgramBuilder(sfml).useCache(false).build();
            return result.metadata().errors().stream()
                    .map(c -> Component.translatable(c.getKey(), c.getArgs()).getString())
                    .toList();
        } catch (Throwable t) {
            return List.of(String.valueOf(t.getMessage()));
        }
    }
}
