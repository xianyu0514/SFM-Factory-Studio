package ca.teamdman.sfmjimu.mixin;

import ca.teamdman.sfm.common.blockentity.ManagerBlockEntity;
import ca.teamdman.sfmjimu.net.TpsBackoff;
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
    private static boolean sfmjimu$trackDidSomething(ca.teamdman.sfml.ast.Program program,
                                                     ManagerBlockEntity manager) {
        boolean didSomething = program.tick(manager);
        TpsBackoff.onProgramRan(manager, didSomething);
        return didSomething;
    }
}
