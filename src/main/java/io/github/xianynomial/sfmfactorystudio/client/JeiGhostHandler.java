package io.github.xianynomial.sfmfactorystudio.client;

import io.github.xianynomial.sfmfactorystudio.client.blocks.BlockEditorScreen;
import io.github.xianynomial.sfmfactorystudio.client.blocks.JeiGhostDrops;
import mezz.jei.api.gui.handlers.IGhostIngredientHandler;
import mezz.jei.api.ingredients.ITypedIngredient;
import net.minecraft.client.renderer.Rect2i;

import java.util.ArrayList;
import java.util.List;

/**
 * Bridges JEI's ghost-ingredient drag onto the block editor: every resource
 * field pill registers a drop zone, and dropping an ingredient on it fills the
 * field with the matching SFML resource id (item, fluid, chemical…).
 * Only classloaded when JEI is installed.
 */
public class JeiGhostHandler implements IGhostIngredientHandler<BlockEditorScreen> {
    @Override
    public <I> List<Target<I>> getTargetsTyped(BlockEditorScreen gui, ITypedIngredient<I> ingredient, boolean doStart) {
        List<Target<I>> out = new ArrayList<>();
        for (JeiGhostDrops.Zone zone : JeiGhostDrops.zones()) {
            out.add(new Target<>() {
                @Override
                public Rect2i getArea() {
                    return zone.area();
                }

                @Override
                public void accept(I ing) {
                    String id = JeiGhostDrops.convert(ing, zone.current());
                    if (id != null) {
                        zone.setter().accept(id);
                    }
                }
            });
        }
        return out;
    }

    @Override
    public void onComplete() {
        // nothing to clean up; zones refresh every frame
    }
}
