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
    /**
     * Auto Tool keeps its loan while Attack is held or a block is still being broken, and for this many ticks
     * without either. Automated mining (Baritone, other modules) never holds Attack, and the short gap between
     * consecutive blocks must not swap the tool away and back.
     */
    static final int MINING_IDLE_TICKS = 10;

    public enum Owner { AutoTool, AutoWeapon }

    public static final InventorySlotLoan INSTANCE = new InventorySlotLoan();

    private Owner owner;
    private int source = -1;
    private int hotbar = -1;
    private ItemStack borrowed = ItemStack.EMPTY;
    private ItemStack displaced = ItemStack.EMPTY;
    private boolean restoreWeapon;
    private int weaponDisplayTicks;
    private int miningIdleTicks;

    private InventorySlotLoan() {}

    /** Restore any earlier exchange before a module examines the inventory for its next action. */
    public boolean prepare() {
        if (InventoryWeaponAttack.INSTANCE.isPending()) return false;
        return owner == null || restore();
    }

    /**
     * Move an inventory item into the selected hotbar slot. A previous loan is restored first.
     * The normal container SWAP operation preserves the cursor and performs both moves atomically.
     */
    public boolean borrow(Owner requestedBy, int inventorySlot) {
        return borrow(requestedBy, inventorySlot, () -> {});
    }

    /** Publish an attack's acknowledgement listener before the swap packet can reach a local server. */
    public boolean borrow(Owner requestedBy, int inventorySlot, Runnable beforeSwap) {
        if (InventoryWeaponAttack.INSTANCE.isPending()) return false;
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
        miningIdleTicks = 0;
        beforeSwap.run();
        mc.gameMode.handleContainerInput(menu.containerId, menuSlot, selected, ContainerInput.SWAP, mc.player);
        return true;
    }

    public void restoreAfterAttack() {
        if (owner == Owner.AutoWeapon) {
            restoreWeapon = true;
            // Start the display/restore grace period only after the deferred attack has run.
            weaponDisplayTicks = WEAPON_DISPLAY_TICKS;
        }
    }

    public void restore(Owner requestedBy) {
        if (owner == requestedBy) restore();
    }

    /** A replaced player/world must never restore an old exchange into its new inventory. */
    public void discard(Owner requestedBy) {
        if (owner == requestedBy) clear();
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
        else if (owner == Owner.AutoTool) {
            if (!Modules.get().isActive(AutoTool.class) || mc.options == null) restore();
            else if (mc.options.keyAttack.isDown() || mc.gameMode != null && mc.gameMode.isDestroying()) miningIdleTicks = 0;
            else if (++miningIdleTicks >= MINING_IDLE_TICKS) restore();
        }
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
        miningIdleTicks = 0;
    }
}
