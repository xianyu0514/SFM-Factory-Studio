package io.github.xianynomial.sfmfactorystudio.mixin;

import ca.teamdman.sfml.ast.Interval;
import ca.teamdman.sfm.common.program.ProgramContext;
import io.github.xianynomial.sfmfactorystudio.net.TpsBackoff;
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
 *
 * <p>remap=false：目标是 SFM 自己的类（生产环境不混淆，Forge 的 mixin 注解
 * 处理器也无须为其查 MC 映射），与 {@link WithTagMixin} 同一模式。
 */
@Mixin(value = Interval.class, remap = false)
public abstract class IntervalMixin {
    @Inject(method = "shouldTick", at = @At("HEAD"), cancellable = true, require = 0, remap = false)
    private void sfmfactorystudio$idleBackoff(ProgramContext context, CallbackInfoReturnable<Boolean> cir) {
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
