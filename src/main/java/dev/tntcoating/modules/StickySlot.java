package dev.tntcoating.modules;

import dev.tntcoating.mixin.GameModeInvoker;
import java.util.function.BooleanSupplier;

import static meteordevelopment.meteorclient.MeteorClient.mc;

/**
 * Sticky auto-switch for Block Coat and Scaffold: hold the block slot while placing, then return to the slot it
 * switched away from after a second idle, unless the player has picked another slot meanwhile.
 */
public final class StickySlot {
    static final int HOLD_TICKS = 20;

    private int restore = -1, slot = -1, used;

    /** Selects this hotbar slot for a placement, remembering where to return. */
    void hold(int slot, int tick) {
        var inventory = mc.player.getInventory();
        int selected = inventory.getSelectedSlot();
        switched(selected, slot, tick);
        if (selected != slot) {
            inventory.setSelectedSlot(slot);
            ((GameModeInvoker) mc.gameMode).comet$ensureHasSentCarriedItem();
        }
    }

    /** Remembers a placement switch from {@code selected} to {@code slot}, whoever performs the switch itself. */
    public void switched(int selected, int slot, int tick) {
        // A slot the player picks while the block is held becomes the one to return to.
        if (selected != slot && (restore < 0 || selected != this.slot)) restore = selected;
        this.slot = slot;
        used = tick;
    }

    /** True while holding a switched slot that has not been used for a placement within the hold time. */
    public boolean idle(int tick) {
        return restore >= 0 && tick - used > HOLD_TICKS;
    }

    public boolean holding() { return restore >= 0; }

    /** Returns to the original slot when the player still holds the block slot and switching is allowed now. */
    public void release(BooleanSupplier switchAllowed) {
        if (restore >= 0 && mc.player != null && mc.gameMode != null && switchAllowed.getAsBoolean()
            && mc.player.getInventory().getSelectedSlot() == slot) {
            mc.player.getInventory().setSelectedSlot(restore);
            ((GameModeInvoker) mc.gameMode).comet$ensureHasSentCarriedItem();
        }
        restore = slot = -1;
    }
}
