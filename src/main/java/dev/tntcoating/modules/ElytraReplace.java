package dev.tntcoating.modules;

import dev.tntcoating.TntCoatingAddon;
import dev.tntcoating.core.ElytraReplacement;
import meteordevelopment.meteorclient.events.world.TickEvent;
import meteordevelopment.meteorclient.settings.IntSetting;
import meteordevelopment.meteorclient.settings.Setting;
import meteordevelopment.meteorclient.settings.SettingGroup;
import meteordevelopment.meteorclient.systems.modules.Module;
import meteordevelopment.meteorclient.utils.player.InvUtils;
import meteordevelopment.orbit.EventHandler;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

public final class ElytraReplace extends Module {
    /** Gives the server time to apply a swap before it is checked again, so a lagging swap isn't sent twice. */
    private static final int SWAP_TICKS = 10;
    private final SettingGroup general = settings.getDefaultGroup();
    private final Setting<Integer> durability = general.add(new IntSetting.Builder().name("durability").description("Swap the worn elytra once it has this much durability left or less. Keep it above Baritone's elytraMinimumDurability (default 5), or Baritone lands first.").defaultValue(10).range(1, 431).sliderRange(1, 100).build());
    private int wait;
    /** The no-spare warning was shown for the elytra currently worn. */
    private boolean warned;

    public ElytraReplace() { super(TntCoatingAddon.CATEGORY, "elytra-replace", "Swaps a nearly broken elytra for the one with the most durability in your inventory."); }

    @Override public String getInfoString() {
        if (mc.player == null) return null;
        int count = 0;
        for (int spare : spares(mc.player.getInventory())) if (spare > durability.get()) count++;
        return String.valueOf(count);
    }

    @Override public void onActivate() {
        wait = 0;
        warned = false;
    }

    @EventHandler private void onTick(TickEvent.Pre event) {
        if (mc.player == null) return;
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
        int[] spares = spares(mc.player.getInventory());
        int slot = ElytraReplacement.best(spares, durability.get());
        if (slot < 0) {
            if (!warned) warning("Your elytra has %d durability left and there's no better one in your inventory.", worn);
            warned = true;
            return;
        }
        // The click swap leaves the worn elytra in the spare's slot; the chest slot is never empty, so gliding carries on.
        InvUtils.move().from(slot).toArmor(2);
        wait = SWAP_TICKS;
        warned = false;
        info("Swapped your elytra (%d durability left) for one with %d.", worn, spares[slot]);
    }

    /** Durability at or below which the worn elytra is swapped out. */
    public int threshold() { return durability.get(); }

    /** Remaining durability of each hotbar and main inventory slot's elytra, -1 for a slot without one. */
    static int[] spares(Inventory inventory) {
        int[] spares = new int[Inventory.INVENTORY_SIZE];
        for (int i = 0; i < spares.length; i++) spares[i] = remaining(inventory.getItem(i));
        return spares;
    }

    /** Remaining durability of an elytra, otherwise -1. */
    static int remaining(ItemStack stack) {
        return stack.is(Items.ELYTRA) ? stack.getMaxDamage() - stack.getDamageValue() : -1;
    }
}
