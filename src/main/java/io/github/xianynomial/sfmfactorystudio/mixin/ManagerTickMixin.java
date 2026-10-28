package io.github.xianynomial.sfmfactorystudio.mixin;

import ca.teamdman.sfm.common.blockentity.ManagerBlockEntity;
import io.github.xianynomial.sfmfactorystudio.net.TpsBackoff;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/**
 * 记录每次程序运行是否真的搬了东西（空转轮数），供 IntervalMixin 的
 * 退避倍率使用。Redirect 只做包装：原调用照常执行，返回值原样透传。
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
    private static boolean sfmfactorystudio$trackDidSomething(ca.teamdman.sfml.ast.Program program,
                                                     ManagerBlockEntity manager) {
        // 每刻全局预算（多工厂保险丝）：超预算且不满足饥饿救济时本轮顺延。
        // 放行的运行与原版逐字节一致；被顺延只是损失这一轮触发时机。
        if (!io.github.xianynomial.sfmfactorystudio.net.TpsBackoff.tryAcquire(manager)) {
            return false;
        }
        long start = System.nanoTime();
        boolean didSomething = program.tick(manager);
        io.github.xianynomial.sfmfactorystudio.net.TpsBackoff.record(System.nanoTime() - start);
        io.github.xianynomial.sfmfactorystudio.net.TpsBackoff.onProgramRan(manager, didSomething);
        return didSomething;
    }
}
