package io.github.xianynomial.sfmfactorystudio.client.blocks;

import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.material.Fluid;
import net.minecraft.world.level.material.Fluids;
import net.minecraft.client.renderer.Rect2i;
import net.minecraft.world.level.material.FluidState;
import net.neoforged.neoforge.fluids.FluidStack;
import net.neoforged.neoforge.fluids.capability.IFluidHandlerItem;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

/**
 * Drop zones for JEI ghost-ingredient drags, plus the ingredient→SFML-id
 * conversion. Zones are re-registered every render frame by the block editor
 * (one per resource field), so they always match what is on screen.
 *
 * Conversion rules:
 * - ItemStack → item id; with a right-click drag a fluid container (water
 *   bucket, any tank item…) contributes its fluid instead.
 * - Fluid → fluid id.
 * - Anything else (Mekanism chemicals…) → resolved through its
 *   {@code getRegistryName()} and SFM's own resource-type registry, which
 *   supplies the {@code chemical:} / {@code mekanism:…} style prefix.
 */
public final class JeiGhostDrops {
    public record Zone(Rect2i area, String current, Consumer<String> setter) {
    }

    private static final List<Zone> ZONES = new ArrayList<>();

    /** Mouse button of the most recent press; 1 = right click (fluid intent). */
    public static int lastMouseButton = 0;

    private JeiGhostDrops() {
    }

    public static void beginFrame() {
        ZONES.clear();
    }

    public static void add(Rect2i area, String current, Consumer<String> setter) {
        ZONES.add(new Zone(area, current, setter));
    }

    public static List<Zone> zones() {
        return ZONES;
    }

    /**
     * Converts a dropped JEI ingredient into an SFML resource id, respecting the
     * target slot's current type: a container item (water bucket, chemical can…)
     * dropped on a fluid slot yields the fluid, on a chemical slot the chemical;
     * right-button drags always prefer the contained resource.
     */
    public static String convert(Object ingredient, String current) {
        String expected = expectedType(current);
        if (ingredient instanceof ItemStack stack) {
            if (stack.isEmpty()) return null;
            boolean rightDrag = io.github.xianynomial.sfmfactorystudio.client.SFMGuiClientEvents.lastPressButton == 1;
            if ("fluid".equals(expected) || rightDrag) {
                String fluid = fluidOf(stack);
                if (fluid != null) return fluid;
            }
            return BuiltInRegistries.ITEM.getKey(stack.getItem()).toString();
        }
        if (ingredient instanceof Fluid fluid) {
            return fluidId(fluid);
        }
        // chemicals and other registry-backed types: reflect the registry name,
        // then let SFM's resource-type registry supply the type prefix
        try {
            Object rl = ingredient.getClass().getMethod("getRegistryName").invoke(ingredient);
            if (rl instanceof Identifier loc) {
                String prefix = sfmPrefixFor(loc);
                String id = loc.getNamespace() + ":" + loc.getPath();
                return prefix != null ? prefix + ":" + id : id;
            }
        } catch (ReflectiveOperationException | NoClassDefFoundError ignored) {
            // not a registry-backed ingredient
        }
        return null;
    }

    private static final java.util.Set<String> KNOWN_KINDS = java.util.Set.of(
            "item", "fluid", "chemical", "gas", "slurry", "pigment", "infusion", "forge_energy");

    /** The resource kind the target slot currently holds; null when unknown. */
    static String expectedType(String current) {
        if (current == null || current.isBlank()) return null;
        String s = current.trim().toLowerCase(java.util.Locale.ROOT);
        if (s.equals("*")) return null;
        int colon = s.indexOf(':');
        if (colon > 0) {
            String k = s.substring(0, colon);
            if (KNOWN_KINDS.contains(k)) return k;
        }
        return "item"; // bare registry id (or unknown prefix) = an item
    }

    /** Fluid contained in an item (water bucket, tank items…); null when none. */
    private static String fluidOf(ItemStack stack) {
        if (stack.is(Items.WATER_BUCKET)) return "fluid:minecraft:water";
        if (stack.is(Items.LAVA_BUCKET)) return "fluid:minecraft:lava";
        if (stack.is(Items.MILK_BUCKET)) return "fluid:minecraft:milk";
        try {
            // 26.1：物品能力查询改成 ItemCapability.getCapability(stack, context)，
            // 拿回来的不再是 IFluidHandler 而是 ResourceHandler<FluidResource>。
            // 有些 handler 要求非空 context，所以先试 null，不行再用 ItemAccess 兜一次。
            var fluidCap = net.neoforged.neoforge.capabilities.Capabilities.Fluid.ITEM;
            Object cap = fluidCap.getCapability(stack, null);
            if (cap == null) {
                var player = net.minecraft.client.Minecraft.getInstance().player;
                if (player != null) {
                    cap = fluidCap.getCapability(stack,
                            net.neoforged.neoforge.transfer.access.ItemAccess
                                    .forInfiniteMaterials(player, stack));
                }
            }
            if (cap instanceof net.neoforged.neoforge.transfer.ResourceHandler rh && rh.size() > 0) {
                if (rh.getAmountAsInt(0) > 0 && rh.getResource(0)
                        instanceof net.neoforged.neoforge.transfer.fluid.FluidResource fr) {
                    Fluid f = fr.getFluid();
                    if (f != null) return fluidId(f);
                }
            }
        } catch (Throwable ignored) {
            // capability not present on this item
        }
        return null;
    }

    private static String fluidId(Fluid fluid) {
        Identifier key = BuiltInRegistries.FLUID.getKey(fluid);
        if (key.getPath().startsWith("flowing_")) {
            Identifier still = Identifier.fromNamespaceAndPath(
                    key.getNamespace(), key.getPath().substring("flowing_".length()));
            Fluid s = BuiltInRegistries.FLUID.getOptional(still).orElse(null);
            if (s != Fluids.EMPTY) {
                key = BuiltInRegistries.FLUID.getKey(s);
            } else {
                key = Identifier.fromNamespaceAndPath(key.getNamespace(), key.getPath().substring("flowing_".length()));
            }
        }
        return "fluid:" + key.getNamespace() + ":" + key.getPath();
    }

    /** Finds the SFM resource-type path ("chemical", "energy"…) owning this key. */
    private static String sfmPrefixFor(Identifier id) {
        try {
            var registry = ca.teamdman.sfm.common.registry.registration.SFMResourceTypes.registry();
            for (var entry : registry.entries()) {
                var type = entry.getValue();
                if (type instanceof ca.teamdman.sfm.common.resourcetype.RegistryBackedResourceType<?, ?, ?> backed) {
                    for (Identifier key : backed.getRegistryKeys()) {
                        if (key.equals(id)) {
                            return entry.getKey().identifier().getPath();
                        }
                    }
                }
            }
        } catch (Throwable ignored) {
            // SFM registries unavailable
        }
        return null;
    }
}
