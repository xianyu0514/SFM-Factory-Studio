package ca.teamdman.sfmjimu.client.blocks;

import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
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
            boolean rightDrag = ca.teamdman.sfmjimu.client.SFMGuiClientEvents.lastPressButton == 1;
            if ("fluid".equals(expected) || rightDrag) {
                String fluid = fluidOf(stack);
                if (fluid != null) return fluid;
            }
            if ("chemical".equals(expected) || "gas".equals(expected) || "slurry".equals(expected)
                    || "pigment".equals(expected) || "infusion".equals(expected)
                    || (rightDrag && expected == null)) {
                String prefix = expected != null ? expected : "chemical";
                String chem = chemicalOf(stack, prefix);
                if (chem != null) return chem;
            }
            return BuiltInRegistries.ITEM.getKey(stack.getItem()).toString();
        }
        if (ingredient instanceof Fluid fluid) {
            return fluidId(fluid);
        }
        // JEI hands Mekanism chemicals to us as ChemicalStack; it carries the
        // registry name via getTypeRegistryName() (not getRegistryName()).
        if (MEKANISM_API && ingredient instanceof mekanism.api.chemical.ChemicalStack cs) {
            if (!cs.isEmpty()) {
                ResourceLocation rl = cs.getTypeRegistryName();
                if (rl != null) {
                    String prefix = chemicalFamilyPrefix(expected);
                    return prefix + ":" + rl.getNamespace() + ":" + rl.getPath();
                }
            }
        }
        // chemicals and other registry-backed types: reflect the registry name,
        // then let SFM's resource-type registry supply the type prefix
        try {
            Object rl = ingredient.getClass().getMethod("getRegistryName").invoke(ingredient);
            if (rl instanceof ResourceLocation loc) {
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

    private static final boolean MEKANISM_API =
            classExists("mekanism.api.chemical.ChemicalStack");

    private static boolean classExists(String className) {
        try {
            Class.forName(className, false, JeiGhostDrops.class.getClassLoader());
            return true;
        } catch (ClassNotFoundException e) {
            return false;
        }
    }

    /** The SFM type prefix for a chemical dragged onto a slot of the given kind. */
    private static String chemicalFamilyPrefix(@org.jetbrains.annotations.Nullable String expected) {
        if ("gas".equals(expected) || "slurry".equals(expected) || "pigment".equals(expected)
                || "infusion".equals(expected) || "chemical".equals(expected)) {
            return expected;
        }
        return "chemical"; // SFM's all-chemicals registry covers every subtype
    }

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

    /**
     * Chemical contained in an item (Mekanism cans, gauges…); null when none.
     * Fully reflective and guarded: no-ops without Mekanism or on API changes.
     */
    /**
     * Chemical contained in an item (Mekanism cans, tanks, gauges…); null when
     * none. Reflective against the real class layout:
     * {@code mekanism.common.capabilities.Capabilities#CHEMICAL} is a
     * MultiTypeCapability whose {@code item()} yields the ItemCapability token.
     */
    @SuppressWarnings("unchecked")
    private static String chemicalOf(ItemStack stack, String prefix) {
        try {
            Class<?> capsClass = Class.forName("mekanism.common.capabilities.Capabilities");
            Object multi = capsClass.getField("CHEMICAL").get(null);
            Object itemCap = multi.getClass().getMethod("item").invoke(multi);
            if (!(itemCap instanceof net.neoforged.neoforge.capabilities.ItemCapability<?, ?> token)) {
                return null;
            }
            Object handler = stack.getCapability(
                    (net.neoforged.neoforge.capabilities.ItemCapability<Object, Void>) token);
            if (handler == null) return null;
            Object chemStack = handler.getClass()
                    .getMethod("getChemicalInTank", int.class)
                    .invoke(handler, 0);
            if (chemStack == null) return null;
            if ((boolean) chemStack.getClass().getMethod("isEmpty").invoke(chemStack)) return null;
            Object rl = chemStack.getClass().getMethod("getTypeRegistryName").invoke(chemStack);
            if (rl instanceof ResourceLocation loc) {
                return prefix + ":" + loc.getNamespace() + ":" + loc.getPath();
            }
        } catch (Throwable ignored) {
            // Mekanism absent or layout changed — item id stays
        }
        return null;
    }

    /** Fluid contained in an item (water bucket, tank items…); null when none. */
    private static String fluidOf(ItemStack stack) {
        if (stack.is(Items.WATER_BUCKET)) return "fluid:minecraft:water";
        if (stack.is(Items.LAVA_BUCKET)) return "fluid:minecraft:lava";
        if (stack.is(Items.MILK_BUCKET)) return "fluid:minecraft:milk";
        try {
            IFluidHandlerItem handler = stack.getCapability(net.neoforged.neoforge.capabilities.Capabilities.FluidHandler.ITEM);
            if (handler != null) {
                FluidStack fs = handler.getFluidInTank(0);
                if (!fs.isEmpty()) return fluidId(fs.getFluid());
            }
        } catch (Throwable ignored) {
            // capability not present on this item
        }
        return null;
    }

    private static String fluidId(Fluid fluid) {
        ResourceLocation key = BuiltInRegistries.FLUID.getKey(fluid);
        if (key.getPath().startsWith("flowing_")) {
            ResourceLocation still = ResourceLocation.fromNamespaceAndPath(
                    key.getNamespace(), key.getPath().substring("flowing_".length()));
            Fluid s = BuiltInRegistries.FLUID.get(still);
            if (s != Fluids.EMPTY) {
                key = BuiltInRegistries.FLUID.getKey(s);
            } else {
                key = ResourceLocation.fromNamespaceAndPath(key.getNamespace(), key.getPath().substring("flowing_".length()));
            }
        }
        return "fluid:" + key.getNamespace() + ":" + key.getPath();
    }

    /** Finds the SFM resource-type path ("chemical", "energy"…) owning this key. */
    private static String sfmPrefixFor(ResourceLocation id) {
        try {
            var registry = ca.teamdman.sfm.common.registry.registration.SFMResourceTypes.registry();
            for (var entry : registry.entries()) {
                var type = entry.getValue();
                if (type instanceof ca.teamdman.sfm.common.resourcetype.RegistryBackedResourceType<?, ?, ?> backed) {
                    for (ResourceLocation key : backed.getRegistryKeys()) {
                        if (key.equals(id)) {
                            return entry.getKey().location().getPath();
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
