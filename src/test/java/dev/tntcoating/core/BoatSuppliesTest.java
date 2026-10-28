package dev.tntcoating.core;

import java.util.Arrays;
import net.minecraft.SharedConstants;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class BoatSuppliesTest {
    @BeforeAll static void bootstrap() { SharedConstants.tryDetectVersion(); Bootstrap.bootStrap();
        var lookup = net.minecraft.data.registries.VanillaRegistries.createLookup();
        net.minecraft.core.registries.BuiltInRegistries.DATA_COMPONENT_INITIALIZERS.build(lookup).forEach(pending -> pending.apply());
    }
    @Test void countsItemsAcrossHotbarMainAndOffhandExactlyOnce() {
        ItemStack[] inventory = new ItemStack[41]; Arrays.fill(inventory, ItemStack.EMPTY);
        inventory[0] = new ItemStack(Items.ENCHANTED_GOLDEN_APPLE, 64);
        inventory[9] = new ItemStack(Items.ENCHANTED_GOLDEN_APPLE, 32);
        inventory[35] = new ItemStack(Items.ENCHANTED_GOLDEN_APPLE, 16);
        inventory[40] = new ItemStack(Items.ENCHANTED_GOLDEN_APPLE, 17);
        assertEquals(129, BoatSupplies.egaps(slot -> inventory[slot], inventory[40]));
    }
    @Test void ordinaryApplesArmourSlotsAndEmptyInventoryDoNotCount() {
        ItemStack[] inventory = new ItemStack[41]; Arrays.fill(inventory, ItemStack.EMPTY);
        assertEquals(0, BoatSupplies.egaps(slot -> inventory[slot], ItemStack.EMPTY));
        inventory[0] = new ItemStack(Items.GOLDEN_APPLE, 64);
        inventory[36] = new ItemStack(Items.ENCHANTED_GOLDEN_APPLE, 64);
        assertEquals(0, BoatSupplies.egaps(slot -> inventory[slot], new ItemStack(Items.APPLE, 64)));
    }
    @Test void mainInventoryFoodNeedsAnEmptyHotbarAndSafeInventoryAccess() {
        ItemStack[] inventory = new ItemStack[36]; Arrays.fill(inventory, new ItemStack(Items.STONE));
        inventory[9] = new ItemStack(Items.ENCHANTED_GOLDEN_APPLE, 64);
        assertFalse(BoatSupplies.food(slot -> inventory[slot], ItemStack.EMPTY, java.util.List.of(), true).accessible());
        inventory[0] = ItemStack.EMPTY;
        assertTrue(BoatSupplies.food(slot -> inventory[slot], ItemStack.EMPTY, java.util.List.of(), true).accessible());
        assertFalse(BoatSupplies.food(slot -> inventory[slot], ItemStack.EMPTY, java.util.List.of(), false).accessible());
    }
    @Test void offhandHotbarAndBlacklistMatchAutoEatSelection() {
        ItemStack[] inventory = new ItemStack[36]; Arrays.fill(inventory, new ItemStack(Items.STONE));
        var egap = new ItemStack(Items.ENCHANTED_GOLDEN_APPLE, 16);
        assertTrue(BoatSupplies.food(slot -> inventory[slot], egap, java.util.List.of(), true).accessible());
        assertFalse(BoatSupplies.food(slot -> inventory[slot], egap, java.util.List.of(Items.ENCHANTED_GOLDEN_APPLE), true).accessible());
        inventory[4] = egap;
        var food = BoatSupplies.food(slot -> inventory[slot], ItemStack.EMPTY, java.util.List.of(), true);
        assertTrue(food.accessible()); assertEquals(16, food.units());
    }
    @Test void fullHungerSkipsOrdinaryOffhandFoodButKeepsEgapsUsable() {
        var userBlacklist = java.util.List.of(Items.ROTTEN_FLESH, Items.ENCHANTED_GOLDEN_APPLE);
        var full = BoatSupplies.eatingBlacklist(userBlacklist, false);
        assertTrue(full.contains(Items.COOKED_BEEF));
        assertFalse(full.contains(Items.ENCHANTED_GOLDEN_APPLE));
        assertTrue(full.contains(Items.ROTTEN_FLESH));
        assertEquals(java.util.List.of(Items.ROTTEN_FLESH, Items.ENCHANTED_GOLDEN_APPLE), userBlacklist);
        var hungry = BoatSupplies.eatingBlacklist(userBlacklist, true);
        assertFalse(hungry.contains(Items.COOKED_BEEF));
        assertFalse(hungry.contains(Items.ENCHANTED_GOLDEN_APPLE));
        assertTrue(hungry.contains(Items.ROTTEN_FLESH));
    }

}
