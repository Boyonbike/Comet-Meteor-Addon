package dev.tntcoating.modules;

import dev.tntcoating.TntCoatingAddon;
import dev.tntcoating.core.ElytraReplacement;
import meteordevelopment.meteorclient.events.world.TickEvent;
import meteordevelopment.meteorclient.settings.IntSetting;
import meteordevelopment.meteorclient.settings.Setting;
import meteordevelopment.meteorclient.settings.SettingGroup;
import meteordevelopment.meteorclient.systems.modules.Module;
import meteordevelopment.meteorclient.systems.modules.Modules;
import meteordevelopment.meteorclient.systems.modules.misc.AutoReconnect;
import meteordevelopment.meteorclient.systems.modules.movement.elytrafly.ElytraFlightModes;
import meteordevelopment.meteorclient.systems.modules.movement.elytrafly.ElytraFly;
import meteordevelopment.orbit.EventHandler;
import net.minecraft.core.component.DataComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.network.protocol.common.ClientboundDisconnectPacket;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.ItemStack;

public final class ElytraLog extends Module {
    private final SettingGroup general = settings.getDefaultGroup();
    private final Setting<Integer> durability = general.add(new IntSetting.Builder().name("durability").description("Log out once the worn elytra has this much durability left or less and there's no spare. What's left is what you glide down with after logging back in, about one point a second. Keep it above Baritone's elytraMinimumDurability (default 5), or Baritone lands first.").defaultValue(20).range(1, 431).sliderRange(1, 100).build());
    private final Setting<Integer> warningMargin = general.add(new IntSetting.Builder().name("warning-margin").description("Warn in chat once the worn elytra is within this much durability of logging you out with no spare. Zero disables the warning.").defaultValue(10).range(0, 200).sliderRange(0, 50).build());
    /** The early warning was shown for the elytra currently worn; cleared once it is no longer close. */
    private boolean warned;

    public ElytraLog() { super(TntCoatingAddon.CATEGORY, "elytra-log", "Logs you out mid-flight when your elytra is nearly broken and there's no spare in your inventory."); }

    @Override public void onActivate() { warned = false; }

    @EventHandler private void onTick(TickEvent.Pre event) {
        if (mc.player == null || !flying()) return;
        int worn = remaining(mc.player.getItemBySlot(EquipmentSlot.CHEST));
        int[] spares = spares(mc.player.getInventory());
        // A spare only saves the flight if you can put it on before reaching the threshold yourself.
        if (ElytraReplacement.stranded(worn, spares, durability.get(), durability.get())) {
            logOut(worn);
            return;
        }
        boolean close = warningMargin.get() > 0
            && ElytraReplacement.stranded(worn, spares, durability.get() + warningMargin.get(), durability.get());
        if (close && !warned) warning("Your elytra has %d durability left and there's no spare; Elytra Log logs you out at %d.", worn, durability.get());
        warned = close;
    }

    /** ElytraFly's Packet mode flies without the client counting the player as gliding. */
    private boolean flying() {
        if (mc.player.isFallFlying()) return true;
        ElytraFly elytraFly = Modules.get().get(ElytraFly.class);
        return elytraFly.isActive() && elytraFly.flightMode.get() == ElytraFlightModes.Packet && !mc.player.onGround();
    }

    private void logOut(int worn) {
        MutableComponent reason = Component.literal("[Elytra Log] Your elytra had " + worn + " durability left and there was no spare.");
        // Reconnecting straight away would put you back in the air with the same elytra.
        AutoReconnect autoReconnect = Modules.get().get(AutoReconnect.class);
        if (autoReconnect.isActive()) {
            autoReconnect.toggle();
            reason.append(Component.literal("\n\nAutoReconnect was turned off.").withColor(0xFF808080));
        }
        // Turned off so it doesn't log you out again the moment you glide down after rejoining.
        toggle();
        mc.player.connection.handleDisconnect(new ClientboundDisconnectPacket(reason));
    }

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
