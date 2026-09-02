package ca.teamdman.sfmjimu.mixin;

import ca.teamdman.sfml.ast.Interval;
import ca.teamdman.sfm.common.program.ProgramContext;
import ca.teamdman.sfmjimu.net.TpsBackoff;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.Objects;

/**
 * 空转退避：连续空转的管理器把定时触发间隔按倍率拉长。
 * 只改"何时触发"，不改"触发时做什么"——单次触发的搬运逻辑与原版完全一致；
 * 任何成功搬运都会让倍率立即复位（见 TpsBackoff）。require=0：上游改动注入
 * 失败时只退回原版行为，不崩服。
 */
@Mixin(value = Interval.class)
public abstract class IntervalMixin {
    @Inject(method = "shouldTick", at = @At("HEAD"), cancellable = true, require = 0)
    private void sfmjimu$idleBackoff(ProgramContext context, CallbackInfoReturnable<Boolean> cir) {
        Interval self = (Interval) (Object) this;
        int multiplier = TpsBackoff.multiplierFor(context.getManager());
        if (multiplier <= 1) return;
        long ticks = Math.min(72_000L, (long) self.ticks() * multiplier);
        cir.setReturnValue(switch (self.alignment()) {
            case LOCAL -> context.getManager().getTick() % ticks == self.offset();
            case GLOBAL -> Objects.requireNonNull(context.getManager().getLevel()).getGameTime() % ticks
                    == self.offset();
        });
    }
}
