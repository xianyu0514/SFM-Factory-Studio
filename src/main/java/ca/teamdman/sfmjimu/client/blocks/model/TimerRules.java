package ca.teamdman.sfmjimu.client.blocks.model;

import ca.teamdman.sfm.common.config.SFMConfig;

import java.util.List;

/** Mirrors SFM's separate normal-transfer and energy-only timer limits. */
public final class TimerRules {
    private TimerRules() {
    }

    public static long normalMinimumTicks() {
        return SFMConfig.getOrDefault(SFMConfig.SERVER_CONFIG.timerTriggerMinimumIntervalInTicks);
    }

    public static long energyMinimumTicks() {
        return SFMConfig.getOrDefault(
                SFMConfig.SERVER_CONFIG.timerTriggerMinimumIntervalInTicksWhenOnlyForgeEnergyIO);
    }

    public static long minimumTicks(BProgram.TimerTrigger trigger) {
        return usesOnlyEnergyIO(trigger.body) ? energyMinimumTicks() : normalMinimumTicks();
    }

    public static long minimumCount(BProgram.TimerTrigger trigger) {
        long ticks = minimumTicks(trigger);
        return trigger.unit == BProgram.TimerTrigger.Unit.SECONDS
                ? Math.max(1, (ticks + 19) / 20)
                : ticks;
    }

    /** Same all-match semantics as SFM's TimerTrigger#usesOnlyForgeEnergyResourceIO. */
    public static boolean usesOnlyEnergyIO(List<BProgram.Statement> statements) {
        for (BProgram.Statement statement : statements) {
            if (statement instanceof BProgram.Statement.Input input) {
                if (!energyLimits(input.limits)) return false;
            } else if (statement instanceof BProgram.Statement.Output output) {
                if (!energyLimits(output.limits)) return false;
            } else if (statement instanceof BProgram.Statement.If iff) {
                for (BProgram.Branch branch : iff.branches) {
                    if (!usesOnlyEnergyIO(branch.body)) return false;
                }
                if (!usesOnlyEnergyIO(iff.elseBody)) return false;
            }
        }
        return true;
    }

    private static boolean energyLimits(List<BProgram.ResourceLimit> limits) {
        // No explicit resource means SFM's default item resource.
        if (limits.isEmpty()) return false;
        for (BProgram.ResourceLimit limit : limits) {
            if (limit.resources.isEmpty()) return false;
            for (BProgram.ResourceRef resource : limit.resources) {
                if (resource == null || !isEnergy(resource)) return false;
            }
        }
        return true;
    }

    private static boolean isEnergy(BProgram.ResourceRef resource) {
        if (!"sfm".equalsIgnoreCase(resource.typeNamespace)) return false;
        return "forge_energy".equalsIgnoreCase(resource.typeName)
                || "mekanism_energy".equalsIgnoreCase(resource.typeName);
    }
}
