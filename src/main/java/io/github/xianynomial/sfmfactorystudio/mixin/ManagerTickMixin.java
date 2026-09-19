package io.github.xianynomial.sfmfactorystudio.mixin;

import ca.teamdman.sfm.common.blockentity.ManagerBlockEntity;
import ca.teamdman.sfml.ast.Program;
import io.github.xianynomial.sfmfactorystudio.chronosfm.ChronoSfmRuntime;
import io.github.xianynomial.sfmfactorystudio.net.TpsBackoff;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/**
 * SFM manager hot-path wrapper.
 *
 * ChronoSFM first removes provably inactive ProgramContext construction without
 * changing trigger frequency. Existing optional backoff/budget behaviour remains
 * available but is not part of the ChronoSFM correctness path.
 */
@Mixin(value = ManagerBlockEntity.class)
public abstract class ManagerTickMixin {
    @Redirect(
            method = "serverTick",
            at = @At(
                    value = "INVOKE",
                    target = "Lca/teamdman/sfml/ast/Program;tick(Lca/teamdman/sfm/common/blockentity/ManagerBlockEntity;)Z"
            ),
            require = 0
    )
    private static boolean sfmfactorystudio$chronoTick(Program program, ManagerBlockEntity manager) {
        // Safe #602-style fast gate: no context/network/label setup if every
        // trigger is mathematically known to be inactive this tick.
        if (!ChronoSfmRuntime.shouldExecute(program, manager)) {
            manager.clearRedstonePulseQueue();
            return false;
        }

        // Legacy optional protection mode. Defaults to disabled.
        if (!TpsBackoff.tryAcquire(manager)) {
            return false;
        }

        long start = System.nanoTime();
        boolean didSomething = program.tick(manager);
        TpsBackoff.record(System.nanoTime() - start);
        TpsBackoff.onProgramRan(manager, didSomething);
        return didSomething;
    }
}
