package ca.teamdman.sfmjimu.net;

import ca.teamdman.sfml.ast.TagMatcher;
import ca.teamdman.sfml.ast.WithTag;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;
import net.neoforged.neoforge.fluids.FluidStack;

import java.util.List;

/**
 * NBT 伪标签（#nbt:ns/path）的匹配与可用性自检。
 *
 * 匹配逻辑由 {@code WithTagMixin} 在运行时注入原版 SFM 的
 * {@code WithTag.matchesStack} 头部：`nbt:` 命名空间的伪标签按
 * "组件键存在且非默认"匹配物品/流体（1.21 组件 = 旧 NBT）。
 *
 * 自检：Mixin 同时让 WithTag 实现 {@link NbtAware} 标记接口——只有注入
 * 真正生效的实例才 instanceof 成功；SFM 更新导致注入失败时（require=0
 * 只告警不崩），能力声明不会发出，编辑器入口保持隐藏，绝不误开。
 */
public final class NbtMatcherHook {
    private NbtMatcherHook() {
    }

    /** 由 Mixin 挂到 WithTag 上的标记接口（注入成功的运行时证据）。 */
    public interface NbtAware {
    }

    /**
     * 组件"非默认"判定（1.21 组件映射是 补丁+默认值 双层，get() 会透传默认值——
     * 钻石剑默认就带空附魔栏，所以不能用 get()!=null 判断）：
     * 物品 = 实际值 ≠ 物品默认值；流体 = 组件补丁里显式设置。
     */
    public static boolean hasNonDefault(Object stack, net.minecraft.core.component.DataComponentType<?> type) {
        if (stack instanceof ItemStack itemStack) {
            Object value = itemStack.getComponents().get(type);
            return value != null && !java.util.Objects.equals(
                    value, itemStack.getItem().components().get(type));
        }
        if (stack instanceof FluidStack fs) {
            for (var e : fs.getComponentsPatch().entrySet()) {
                if (e.getKey() == type) return e.getValue().isPresent();
            }
        }
        return false;
    }

    /** 组件 id（ns:path）→ 该物品/流体是否携带此非默认组件。 */
    public static boolean matchesComponent(String componentId, Object stack) {
        if (stack == null) return false;
        ResourceLocation key = ResourceLocation.tryParse(componentId);
        if (key == null) return false;
        var type = BuiltInRegistries.DATA_COMPONENT_TYPE.get(key);
        if (type == null) return false;
        return hasNonDefault(stack, type);
    }

    /** 服务器是否真正具备 NBT 区分能力（Mixin 已生效）。 */
    public static boolean isAvailable() {
        try {
            // 编译期 WithTag 与 NbtAware 无关（接口由 Mixin 在运行时挂上），
            // 经 Object 转型做纯运行时检查
            return (Object) new WithTag(TagMatcher.fromPath(List.of("sfmjimu_probe")))
                    instanceof NbtAware;
        } catch (Throwable t) {
            return false;
        }
    }
}
