package ca.teamdman.sfmjimu.mixin;

import ca.teamdman.sfm.common.resourcetype.ResourceType;
import ca.teamdman.sfml.ast.WithTag;
import ca.teamdman.sfmjimu.net.NbtMatcherHook;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * NBT 伪标签支持：注入原版 SFM 的 WithTag.matchesStack 头部。`nbt:` 命名空间
 * 的标签串改走组件存在性匹配，其余标签行为不变。require = 0：SFM 未来版本若
 * 改动此方法，注入失败只告警不崩溃，能力自检随之失败、编辑器入口保持隐藏。
 */
@Mixin(value = WithTag.class)
public abstract class WithTagMixin implements NbtMatcherHook.NbtAware {
    @Inject(method = "matchesStack", at = @At("HEAD"), cancellable = true, require = 0)
    private void sfmjimu$matchNbtPseudoTag(ResourceType<?, ?, ?> resourceType, Object stack,
                                            CallbackInfoReturnable<Boolean> cir) {
        String matcher = ((WithTag) (Object) this).tagMatcher().toString();
        if (!matcher.startsWith("nbt:")) return;
        cir.setReturnValue(NbtMatcherHook.matchesComponent(
                matcher.substring(4).replace('/', ':'), stack));
    }
}
