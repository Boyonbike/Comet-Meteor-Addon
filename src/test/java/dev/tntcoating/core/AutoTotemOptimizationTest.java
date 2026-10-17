package dev.tntcoating.core;

import net.minecraft.SharedConstants;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.data.registries.VanillaRegistries;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class AutoTotemOptimizationTest {
    @BeforeAll static void bootstrapMinecraft() {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
        BuiltInRegistries.DATA_COMPONENT_INITIALIZERS.build(VanillaRegistries.createLookup())
            .forEach(components -> components.apply());
    }

    @Test void strictFlightAndAlreadyLowHealthNeverNeedDamagePrediction() {
        assertTrue(AutoTotemOptimization.shouldHold(true, false, 20, 10, () -> fail("Strict scan")));
        assertTrue(AutoTotemOptimization.shouldHold(false, true, 20, 10, () -> fail("Flight scan")));
        assertTrue(AutoTotemOptimization.shouldHold(false, false, 10, 10, () -> fail("Low health scan")));
        assertTrue(AutoTotemOptimization.shouldHold(false, false, 1, 0, () -> 1));
    }

    @Test void smartModeRechecksThreatsOnEveryDecisionAndIncludesAbsorption() {
        double[] damage = {0};
        int[] calls = {0};
        var predictor = (java.util.function.DoubleSupplier) () -> { calls[0]++; return damage[0]; };
        assertFalse(AutoTotemOptimization.shouldHold(false, false, 24, 10, predictor));
        damage[0] = 14;
        assertTrue(AutoTotemOptimization.shouldHold(false, false, 24, 10, predictor));
        damage[0] = 13.5;
        assertFalse(AutoTotemOptimization.shouldHold(false, false, 24, 10, predictor));
        assertEquals(3, calls[0]);
    }

    @Test void optimizedDecisionsMatchMeteorAcrossHealthDamageAndThresholdBoundaries() {
        for (boolean strict : new boolean[] {false, true}) {
            for (boolean flying : new boolean[] {false, true}) {
                for (float health : new float[] {0, 1, 9.99f, 10, 10.01f, 20, 36, 40}) {
                    for (float damage : new float[] {0, 0.01f, 5, 10, 20, 100}) {
                        for (int threshold : new int[] {0, 10, 36}) {
                            boolean original = strict || health - damage <= threshold || flying;
                            assertEquals(original, AutoTotemOptimization.shouldHold(strict, flying, health,
                                threshold, () -> damage));
                        }
                    }
                }
            }
        }
    }

    @Test void resolvesPlayerInventorySlotWithoutConfusingStorageWithTheSameIndex() {
        var inventory = new SimpleContainer(41);
        var chest = new SimpleContainer(27);
        inventory.setItem(9, new ItemStack(Items.TOTEM_OF_UNDYING));
        chest.setItem(9, new ItemStack(Items.TOTEM_OF_UNDYING));
        var menu = new TestMenu();
        for (int i = 0; i < 27; i++) menu.slots.add(new Slot(chest, i, 0, 0));
        for (int i = 9; i < 36; i++) menu.slots.add(new Slot(inventory, i, 0, 0));
        for (int i = 0; i < 9; i++) menu.slots.add(new Slot(inventory, i, 0, 0));
        assertEquals(27, AutoTotemOptimization.offhandSwapSlot(menu, inventory, 9, ItemStack.EMPTY));
        // Occupied cursor is unrelated to the SWAP operation and is never touched by the planner.
        var cursor = new ItemStack(Items.DIAMOND, 12);
        menu.setCarried(cursor);
        assertEquals(27, AutoTotemOptimization.offhandSwapSlot(menu, inventory, 9, new ItemStack(Items.SHIELD)));
        assertSame(cursor, menu.getCarried());
    }

    @Test void resolvesHotbarAndInventoryMenuLayouts() {
        var inventory = new SimpleContainer(41);
        inventory.setItem(0, new ItemStack(Items.TOTEM_OF_UNDYING));
        inventory.setItem(35, new ItemStack(Items.TOTEM_OF_UNDYING));
        var menu = new TestMenu();
        var crafting = new SimpleContainer(5);
        for (int i = 0; i < 5; i++) menu.slots.add(new Slot(crafting, i, 0, 0));
        for (int i = 39; i >= 36; i--) menu.slots.add(new Slot(inventory, i, 0, 0));
        for (int i = 9; i < 36; i++) menu.slots.add(new Slot(inventory, i, 0, 0));
        for (int i = 0; i < 9; i++) menu.slots.add(new Slot(inventory, i, 0, 0));
        menu.slots.add(new Slot(inventory, 40, 0, 0));
        assertEquals(36, AutoTotemOptimization.offhandSwapSlot(menu, inventory, 0, ItemStack.EMPTY));
        assertEquals(35, AutoTotemOptimization.offhandSwapSlot(menu, inventory, 35, ItemStack.EMPTY));
        inventory.setItem(0, ItemStack.EMPTY);
        assertEquals(-1, AutoTotemOptimization.offhandSwapSlot(menu, inventory, 0, ItemStack.EMPTY));
    }

    @Test void missingArmorOffhandAndRestrictedSlotsUseTheFallback() {
        var inventory = new SimpleContainer(41);
        var menu = new TestMenu();
        inventory.setItem(9, new ItemStack(Items.TOTEM_OF_UNDYING));
        menu.slots.add(new Slot(inventory, 9, 0, 0) {
            @Override public boolean mayPlace(ItemStack stack) { return false; }
        });
        for (int source : new int[] {-1, 0, 9, 36, 40, 99}) {
            assertEquals(-1, AutoTotemOptimization.offhandSwapSlot(menu, inventory, source,
                new ItemStack(Items.SHIELD)));
        }
    }

    private static final class TestMenu extends AbstractContainerMenu {
        TestMenu() { super(null, 0); }
        @Override public ItemStack quickMoveStack(Player player, int slot) { return ItemStack.EMPTY; }
        @Override public boolean stillValid(Player player) { return true; }
    }
}
