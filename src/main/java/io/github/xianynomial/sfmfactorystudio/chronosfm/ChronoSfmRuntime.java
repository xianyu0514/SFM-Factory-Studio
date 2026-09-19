package io.github.xianynomial.sfmfactorystudio.chronosfm;

import ca.teamdman.sfm.common.blockentity.ManagerBlockEntity;
import ca.teamdman.sfml.ast.Program;
import dev.xianyu.chronosfm.compiler.ChronoSfmCompiler;
import dev.xianyu.chronosfm.runtime.TriggerProbe;

import java.util.Collections;
import java.util.Map;
import java.util.WeakHashMap;

/**
 * Server-thread fast gate for SFM manager ticks.
 *
 * It only suppresses Program.tick when all known triggers are provably inactive.
 * Unsupported triggers are compiled as LEGACY and therefore always force the
 * original SFM execution path.
 */
public final class ChronoSfmRuntime {
    private static final SfmProgramAdapter ADAPTER = new SfmProgramAdapter();
    private static final ChronoSfmCompiler COMPILER = new ChronoSfmCompiler();
    private static final Map<Program, TriggerProbe> PROBES =
            Collections.synchronizedMap(new WeakHashMap<>());

    private ChronoSfmRuntime() {
    }

    public static boolean shouldExecute(Program program, ManagerBlockEntity manager) {
        if (program == null || manager == null || manager.getLevel() == null) return true;

        try {
            TriggerProbe probe = PROBES.computeIfAbsent(
                    program,
                    p -> new TriggerProbe(COMPILER.compile(ADAPTER.adapt(p)))
            );

            var result = probe.probe(
                    manager.getTick(),
                    manager.getLevel().getGameTime(),
                    manager.getUnprocessedRedstonePulseCount()
            );
            return !result.maySkipFullContext();
        } catch (Throwable ignored) {
            // Optimization failure must never change SFM semantics.
            return true;
        }
    }

    public static void invalidate(Program program) {
        if (program != null) PROBES.remove(program);
    }

    static int cachedProgramCountForTesting() {
        return PROBES.size();
    }
}
