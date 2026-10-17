package dev.tntcoating.core;

import net.minecraft.world.Container;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

import java.util.function.DoubleSupplier;

/** Cheap decisions first; never cache or postpone a damage prediction that could change the decision. */
public final class AutoTotemOptimization {
    private AutoTotemOptimization() {}

    public static boolean shouldHold(boolean strict, boolean flying, float health, int threshold,
                                     DoubleSupplier damage) {
        // Meteor's possibleHealthReductions returns a non-negative float.
        return strict || flying || health <= threshold || health - (float) damage.getAsDouble() <= threshold;
    }

    /** Resolve the actual player slot in the current menu, including containers with different slot layouts. */
    public static int offhandSwapSlot(AbstractContainerMenu menu, Container inventory, int source,
                                      ItemStack offhand) {
        if (source < 0 || source >= 36) return -1;
        for (int id = 0; id < menu.slots.size(); id++) {
            var slot = menu.slots.get(id);
            if (slot.container == inventory && slot.getContainerSlot() == source
                && slot.getItem().is(Items.TOTEM_OF_UNDYING) && slot.mayPlace(offhand)) return id;
        }
        return -1;
    }
}
