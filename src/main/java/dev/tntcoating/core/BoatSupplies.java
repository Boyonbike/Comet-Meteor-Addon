/* SPDX-License-Identifier: GPL-3.0-only */
package dev.tntcoating.core;

import java.util.function.IntFunction;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

/** Count usable carried eGaps once: hotbar/main inventory plus offhand, excluding armour and nested containers. */
public final class BoatSupplies {
    private BoatSupplies() {}
    public static int egaps(IntFunction<ItemStack> inventory, ItemStack offhand) {
        int count = 0;
        for (int slot = 0; slot < 36; slot++) count += count(inventory.apply(slot));
        return count + count(offhand);
    }
    public record Food(int units, boolean accessible) {}

    /** AutoEat can use offhand/hotbar directly; main inventory requires an empty hotbar slot. */
    public static Food food(IntFunction<ItemStack> inventory, ItemStack offhand,
                            java.util.List<net.minecraft.world.item.Item> blacklist, boolean inventorySafe) {
        int units = 0;
        boolean emptyHotbar = false, directFood = false, mainFood = false;
        for (int slot = 0; slot < 36; slot++) {
            ItemStack stack = inventory.apply(slot);
            if (slot < 9 && (stack == null || stack.isEmpty())) emptyHotbar = true;
            if (!allowed(stack, blacklist)) continue;
            units += stack.getCount();
            if (slot < 9) directFood = true; else mainFood = true;
        }
        if (allowed(offhand, blacklist)) { units += offhand.getCount(); directFood = true; }
        return new Food(units, inventorySafe && (directFood || mainFood && emptyHotbar));
    }

    /** At full hunger, ordinary food must not hide always-edible food from Meteor's offhand-first selector. */
    public static java.util.List<net.minecraft.world.item.Item> eatingBlacklist(
            java.util.List<net.minecraft.world.item.Item> original, boolean needsFood) {
        var result = new java.util.ArrayList<>(original);
        result.remove(Items.ENCHANTED_GOLDEN_APPLE);
        if (!needsFood) for (var item : net.minecraft.core.registries.BuiltInRegistries.ITEM) {
            var food = item.components().get(net.minecraft.core.component.DataComponents.FOOD);
            if (food != null && !food.canAlwaysEat() && !result.contains(item)) result.add(item);
        }
        return java.util.List.copyOf(result);
    }

    private static boolean allowed(ItemStack stack, java.util.List<net.minecraft.world.item.Item> blacklist) {
        return stack != null && !stack.isEmpty() && !blacklist.contains(stack.getItem())
            && stack.getItem().components().has(net.minecraft.core.component.DataComponents.FOOD)
            && stack.getItem().components().has(net.minecraft.core.component.DataComponents.CONSUMABLE)
            && stack.get(net.minecraft.core.component.DataComponents.FOOD) != null;
    }
    private static int count(ItemStack stack) {
        return stack != null && stack.is(Items.ENCHANTED_GOLDEN_APPLE) ? stack.getCount() : 0;
    }
}
