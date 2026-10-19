package dev.tntcoating.modules;

import dev.tntcoating.TntCoatingAddon;
import dev.tntcoating.core.ElytraReplacement;
import meteordevelopment.meteorclient.events.world.TickEvent;
import meteordevelopment.meteorclient.settings.IntSetting;
import meteordevelopment.meteorclient.settings.Setting;
import meteordevelopment.meteorclient.settings.SettingGroup;
import meteordevelopment.meteorclient.systems.modules.Module;
import meteordevelopment.meteorclient.utils.player.InvUtils;
import meteordevelopment.meteorclient.utils.player.SlotUtils;
import meteordevelopment.orbit.EventHandler;
import net.minecraft.core.component.DataComponents;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.inventory.ContainerInput;
import net.minecraft.world.item.ItemStack;

public final class ElytraReplace extends Module {
    /** Gives the server time to apply a swap before it is checked again, so a lagging swap isn't sent twice. */
    private static final int SWAP_TICKS = 10;
    /** The chest armor slot in the player's own inventory menu. */
    private static final int CHEST_MENU_SLOT = 6;
    private final SettingGroup general = settings.getDefaultGroup();
    private final Setting<Integer> durability = general.add(new IntSetting.Builder().name("durability").description("Swap the worn elytra once it has this much durability left or less. Keep it above Baritone's elytraMinimumDurability (default 5), or Baritone lands first.").defaultValue(10).range(1, 431).sliderRange(1, 100).build());
    private int wait;
    /** The no-spare warning was shown for the elytra currently worn. */
    private boolean warned;
    private String info;

    public ElytraReplace() { super(TntCoatingAddon.CATEGORY, "elytra-replace", "Swaps a nearly broken elytra for the one with the most durability in your inventory."); }

    // Refreshed once per tick: the module list asks for this every frame.
    @Override public String getInfoString() { return info; }

    @Override public void onActivate() {
        wait = 0;
        warned = false;
        info = null;
    }

    @EventHandler private void onTick(TickEvent.Pre event) {
        if (mc.player == null) {
            info = null;
            return;
        }
        int[] spares = spares(mc.player.getInventory());
        int usable = 0;
        for (int spare : spares) if (spare > durability.get()) usable++;
        info = String.valueOf(usable);
        if (wait > 0) {
            wait--;
            return;
        }
        int worn = remaining(mc.player.getItemBySlot(EquipmentSlot.CHEST));
        if (!ElytraReplacement.due(worn, durability.get())) {
            warned = false;
            return;
        }
        // The armor slots are only in the player's own inventory menu, and the swap puts the old elytra back with the cursor.
        if (mc.player.containerMenu != mc.player.inventoryMenu || !mc.player.containerMenu.getCarried().isEmpty()) return;
        int slot = ElytraReplacement.best(spares, durability.get());
        if (slot < 0) {
            if (!warned) warning("Your elytra has %d durability left and there's no better one in your inventory.", worn);
            warned = true;
            return;
        }
        // Either way the worn elytra ends up in the spare's slot and the chest slot is never empty, so gliding carries on.
        if (SlotUtils.isHotbar(slot)) {
            // One SWAP click, as pressing that hotbar number over the chest slot does; nothing is left on the cursor.
            mc.gameMode.handleContainerInput(mc.player.inventoryMenu.containerId, CHEST_MENU_SLOT, slot, ContainerInput.SWAP, mc.player);
        } else {
            InvUtils.move().from(slot).toArmor(2);
        }
        wait = SWAP_TICKS;
        warned = false;
        info("Swapped your elytra (%d durability left) for one with %d.", worn, spares[slot]);
    }

    /** Durability at or below which the worn elytra is swapped out. */
    public int threshold() { return durability.get(); }

    /** Remaining durability of each hotbar and main inventory slot's glider, -1 for a slot without one. */
    static int[] spares(Inventory inventory) {
        int[] spares = new int[Inventory.INVENTORY_SIZE];
        for (int i = 0; i < spares.length; i++) spares[i] = remaining(inventory.getItem(i));
        return spares;
    }

    /** Whether this item lets its wearer glide: the vanilla elytra, or any item with the glider component. */
    public static boolean isGlider(ItemStack stack) {
        return !stack.isEmpty() && stack.has(DataComponents.GLIDER);
    }

    /** Remaining durability of a glider, otherwise -1. A glider that cannot be damaged never wears out. */
    static int remaining(ItemStack stack) {
        if (!isGlider(stack)) return -1;
        return stack.isDamageableItem() ? stack.getMaxDamage() - stack.getDamageValue() : Integer.MAX_VALUE;
    }
}
