package dev.tntcoating.core;

import meteordevelopment.meteorclient.MeteorClient;
import meteordevelopment.meteorclient.events.game.GameLeftEvent;
import meteordevelopment.meteorclient.events.packets.PacketEvent;
import meteordevelopment.orbit.EventHandler;
import net.minecraft.core.component.DataComponents;
import net.minecraft.network.Connection;
import net.minecraft.network.protocol.game.ClientboundSetHeldSlotPacket;
import net.minecraft.network.protocol.game.ServerboundSetCarriedItemPacket;

import static meteordevelopment.meteorclient.MeteorClient.mc;

/** Tracks actual slot packets for Speedmine without filtering other modules' traffic. */
public final class SpeedmineInventory {
    private static final SpeedmineInventory INSTANCE = new SpeedmineInventory();
    private volatile Connection connection;
    private volatile int serverSlot = -1;

    public static final class Priority {
        public static final int NORMAL = 0;
        public static final int EATING = 10;
        public static final int EXTERNAL = 20;
        private Priority() {}
    }

    private SpeedmineInventory() {
        MeteorClient.EVENT_BUS.subscribe(this);
    }

    public static SpeedmineInventory getInstance() { return INSTANCE; }

    public int getServerSlot() {
        if (mc.player == null || mc.getConnection() == null) return -1;
        return connection == mc.getConnection().getConnection() && serverSlot >= 0
            ? serverSlot : mc.player.getInventory().getSelectedSlot();
    }

    public void setSlotForced(int slot) {
        if (slot < 0 || slot >= 9 || mc.getConnection() == null) return;
        mc.getConnection().send(new ServerboundSetCarriedItemPacket(slot));
    }

    public boolean isEating() {
        return mc.player != null && mc.player.isUsingItem() && mc.player.getUseItem().has(DataComponents.FOOD);
    }

    public int getCurrentPriority() {
        if (InventoryWeaponAttack.INSTANCE.isPending()
            || InventorySlotLoan.INSTANCE.isBorrowing(InventorySlotLoan.Owner.AutoWeapon)) return Priority.EXTERNAL;
        return isEating() ? Priority.EATING : Priority.NORMAL;
    }

    @EventHandler
    private void onSent(PacketEvent.Sent event) {
        if (event.packet instanceof ServerboundSetCarriedItemPacket packet) {
            connection = event.connection;
            serverSlot = packet.getSlot();
        }
    }

    @EventHandler
    private void onReceive(PacketEvent.Receive event) {
        if (!event.isCancelled() && event.packet instanceof ClientboundSetHeldSlotPacket packet) {
            Connection receivedFrom = event.connection;
            mc.execute(() -> {
                if (mc.getConnection() == null || mc.getConnection().getConnection() != receivedFrom) return;
                connection = receivedFrom;
                serverSlot = packet.slot();
            });
        }
    }

    @EventHandler
    private void onLeave(GameLeftEvent event) {
        connection = null;
        serverSlot = -1;
    }
}
