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
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.network.protocol.common.ClientboundDisconnectPacket;
import net.minecraft.world.entity.EquipmentSlot;

public final class ElytraLog extends Module {
    private final SettingGroup general = settings.getDefaultGroup();
    private final Setting<Integer> durability = general.add(new IntSetting.Builder().name("durability").description("Log out once the worn elytra has this much durability left or less and there's no spare. What's left is what you glide down with after logging back in, about one point a second. Keep it above Baritone's elytraMinimumDurability (default 5), or Baritone lands first.").defaultValue(20).range(1, 431).sliderRange(1, 100).build());

    public ElytraLog() { super(TntCoatingAddon.CATEGORY, "elytra-log", "Logs you out mid-flight when your elytra is nearly broken and there's no spare in your inventory."); }

    @EventHandler private void onTick(TickEvent.Pre event) {
        if (mc.player == null || !flying()) return;
        int worn = ElytraReplace.remaining(mc.player.getItemBySlot(EquipmentSlot.CHEST));
        if (ElytraReplacement.stranded(worn, ElytraReplace.spares(mc.player.getInventory()), durability.get(), spareThreshold())) logOut(worn);
    }

    /** A spare only saves the flight if something puts it on: elytra-replace when it's on, otherwise you. */
    private int spareThreshold() {
        ElytraReplace replace = Modules.get().get(ElytraReplace.class);
        return replace.isActive() ? replace.threshold() : durability.get();
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
}
