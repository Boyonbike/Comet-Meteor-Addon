package dev.tntcoating.core;

import meteordevelopment.meteorclient.events.game.GameLeftEvent;
import meteordevelopment.meteorclient.events.world.TickEvent;
import meteordevelopment.meteorclient.systems.modules.Modules;
import meteordevelopment.meteorclient.systems.modules.combat.AutoWeapon;
import meteordevelopment.meteorclient.systems.modules.player.AutoTool;
import meteordevelopment.orbit.EventHandler;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.ContainerInput;
import net.minecraft.world.item.ItemStack;

import static meteordevelopment.meteorclient.MeteorClient.mc;

/** Temporarily exchanges a main-inventory item with a hotbar slot and later reverses the exchange. */
public final class InventorySlotLoan {
    private static final int WEAPON_DISPLAY_TICKS = 4;

    public enum Owner { AutoTool, AutoWeapon }

    public static final InventorySlotLoan INSTANCE = new InventorySlotLoan();

    private Owner owner;
    private int source = -1;
    private int hotbar = -1;
    private ItemStack borrowed = ItemStack.EMPTY;
    private ItemStack displaced = ItemStack.EMPTY;
    private boolean restoreWeapon;
    private int weaponDisplayTicks;

    private InventorySlotLoan() {}

    /** Restore any earlier exchange before a module examines the inventory for its next action. */
    public boolean prepare(Owner requestedBy) {
        return owner == null || restore();
    }

    /**
     * Move an inventory item into the selected hotbar slot. A previous loan is restored first.
     * The normal container SWAP operation preserves the cursor and performs both moves atomically.
     */
    public boolean borrow(Owner requestedBy, int inventorySlot) {
        if (inventorySlot < 9 || inventorySlot >= 36 || mc.player == null || mc.gameMode == null) return false;

        if (owner != null && !restore()) return false;

        int selected = mc.player.getInventory().getSelectedSlot();
        AbstractContainerMenu menu = mc.player.containerMenu;
        int menuSlot = playerSlot(menu, inventorySlot);
        if (menuSlot < 0 || !menu.getCarried().isEmpty()) return false;

        var slot = menu.getSlot(menuSlot);
        ItemStack sourceStack = slot.getItem();
        ItemStack selectedStack = mc.player.getInventory().getItem(selected);
        if (sourceStack.isEmpty() || !slot.mayPickup(mc.player) || !slot.mayPlace(selectedStack)) return false;

        owner = requestedBy;
        source = inventorySlot;
        hotbar = selected;
        borrowed = sourceStack.copy();
        displaced = selectedStack.copy();
        mc.gameMode.handleContainerInput(menu.containerId, menuSlot, selected, ContainerInput.SWAP, mc.player);
        return true;
    }

    public void restoreAfterAttack() {
        if (owner == Owner.AutoWeapon) {
            restoreWeapon = true;
            // Keep the borrowed weapon selected long enough for the hand renderer and server inventory
            // processing to observe it. A new attack still restores/re-scores immediately in prepare().
            weaponDisplayTicks = WEAPON_DISPLAY_TICKS;
        }
    }

    public void restore(Owner requestedBy) {
        if (owner == requestedBy) restore();
    }

    /** Returns false only when the player inventory is temporarily unavailable in the current menu. */
    private boolean restore() {
        if (owner == null) return true;
        if (mc.player == null || mc.gameMode == null) {
            clear();
            return true;
        }

        AbstractContainerMenu menu = mc.player.containerMenu;
        int menuSlot = playerSlot(menu, source);
        if (menuSlot < 0 || !menu.getCarried().isEmpty()) return false;

        ItemStack atSource = menu.getSlot(menuSlot).getItem();
        ItemStack atHotbar = mc.player.getInventory().getItem(hotbar);
        boolean sourceUnchanged = ItemStack.matches(displaced, atSource);
        boolean borrowedStillPresent = atHotbar.isEmpty() || ItemStack.isSameItem(borrowed, atHotbar);

        // If the player or another module rearranged either endpoint, abandoning the loan is safer
        // than swapping unrelated items. Normal durability changes and a broken tool are accepted.
        if (!sourceUnchanged || !borrowedStillPresent) {
            clear();
            return true;
        }

        var slot = menu.getSlot(menuSlot);
        if (!slot.mayPickup(mc.player) || !slot.mayPlace(atHotbar)) return false;
        mc.gameMode.handleContainerInput(menu.containerId, menuSlot, hotbar, ContainerInput.SWAP, mc.player);
        clear();
        return true;
    }

    private static int playerSlot(AbstractContainerMenu menu, int inventorySlot) {
        if (mc.player == null) return -1;
        var inventory = mc.player.getInventory();
        for (int id = 0; id < menu.slots.size(); id++) {
            var slot = menu.slots.get(id);
            if (slot.container == inventory && slot.getContainerSlot() == inventorySlot) return id;
        }
        return -1;
    }

    @EventHandler
    private void onTick(TickEvent.Post event) {
        if (owner == Owner.AutoWeapon && !Modules.get().isActive(AutoWeapon.class)) restore();
        else if (owner == Owner.AutoWeapon && restoreWeapon && weaponDisplayTicks-- <= 0) restore();
        else if (owner == Owner.AutoTool
            && (!Modules.get().isActive(AutoTool.class) || mc.options == null || !mc.options.keyAttack.isDown())) restore();
    }

    @EventHandler
    private void onGameLeft(GameLeftEvent event) {
        clear();
    }

    private void clear() {
        owner = null;
        source = -1;
        hotbar = -1;
        borrowed = ItemStack.EMPTY;
        displaced = ItemStack.EMPTY;
        restoreWeapon = false;
        weaponDisplayTicks = 0;
    }
}
