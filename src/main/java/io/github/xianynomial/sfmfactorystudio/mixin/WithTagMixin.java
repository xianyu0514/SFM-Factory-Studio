package io.github.xianynomial.sfmfactorystudio.mixin;

import ca.teamdman.sfm.common.resourcetype.ResourceType;
import ca.teamdman.sfml.ast.WithTag;
import io.github.xianynomial.sfmfactorystudio.net.NbtMatcherHook;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * NBT 伪标签支持：注入原版 SFM 的 WithTag.matchesStack 头部。`nbt:` 命名空间
 * 的标签串改走原生 NBT 匹配（1.20.1 物品数据在 ItemStack NBT 树上），其余标签
 * 行为不变。require = 0：SFM 未来版本若改动此方法，注入失败只告警不崩溃，
 * 能力自检随之失败、编辑器入口保持隐藏。
 */
@Mixin(value = WithTag.class, remap = false)
public abstract class WithTagMixin implements NbtMatcherHook.NbtAware {
    @Inject(method = "matchesStack", at = @At("HEAD"), cancellable = true, require = 0, remap = false)
    private void sfmfactorystudio$matchNbtPseudoTag(ResourceType<?, ?, ?> resourceType, Object stack,
                                                    CallbackInfoReturnable<Boolean> cir) {
        String matcher = ((WithTag) (Object) this).tagMatcher().toString();
        if (!matcher.startsWith("nbt:")) return;
        cir.setReturnValue(NbtMatcherHook.matchesComponent(matcher, stack));
    }
}
