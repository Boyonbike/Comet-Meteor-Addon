/*
 * Original Comet implementation by Boyonabike.
 * Independent: written from the pinned 26.1.2 artifact and Meteor build 42 behaviour; no third-party code.
 * SPDX-License-Identifier: CC0-1.0
 */

package dev.tntcoating.core.combat.mc;

import dev.tntcoating.core.InventorySlotLoan;
import dev.tntcoating.core.InventoryWeaponAttack;
import java.util.List;
import java.util.Objects;

/**
 * Hotbar selection for a combat dispatch. It never borrows, never prepares a loan, never triggers a weapon
 * attack and never touches the offhand; it only moves the selected slot through {@link CombatPacketSink} and
 * restores it afterwards unless the user has taken selection over.
 *
 * <p>The adapter is deliberately Minecraft-free: {@link CombatClient} supplies the selected slot and the
 * current hotbar contents, so it is fully unit-testable and has no path that could send a packet itself.</p>
 */
public final class CombatInventoryAdapter {
    /** Other Comet owners that make the hotbar not ours to move. */
    public interface Ownership {
        boolean loanActive();

        boolean weaponAttackActive();

        static Ownership live() {
            return new Ownership() {
                @Override
                public boolean loanActive() {
                    return InventorySlotLoan.INSTANCE.isBorrowing();
                }

                @Override
                public boolean weaponAttackActive() {
                    return InventoryWeaponAttack.INSTANCE.isPending();
                }
            };
        }
    }

    /**
     * Outcome of one selection attempt. {@code attempted} is false when nothing needed to change;
     * {@code enqueued} is false when the packet could not be sent at all; {@code completed} mirrors vanilla's
     * send completion; {@code restored} is true only for the compensating restore.
     */
    public record SlotSwitch(boolean attempted, boolean enqueued, boolean completed, boolean restored,
                             String failure) {
        public SlotSwitch {
            if (restored && !attempted) {
                throw new IllegalArgumentException("a restore is always an attempt");
            }
            if (failure != null && failure.isBlank()) {
                throw new IllegalArgumentException("failure must be null or non-blank");
            }
        }

        public static SlotSwitch none() {
            return new SlotSwitch(false, false, false, false, null);
        }

        public static SlotSwitch failedAttempt() {
            return new SlotSwitch(true, false, false, false, null);
        }

        public boolean failed() {
            return attempted && !enqueued;
        }
    }

    /** Pickaxes in the order the planner prefers them; the first matching tier wins. */
    static final List<String> PICKAXE_TIERS = List.of(
        "minecraft:netherite_pickaxe",
        "minecraft:diamond_pickaxe",
        "minecraft:iron_pickaxe",
        "minecraft:stone_pickaxe",
        "minecraft:golden_pickaxe",
        "minecraft:wooden_pickaxe");

    private final CombatClient client;
    private final Ownership ownership;

    public CombatInventoryAdapter(CombatClient client, Ownership ownership) {
        this.client = Objects.requireNonNull(client, "client");
        this.ownership = Objects.requireNonNull(ownership, "ownership");
    }

    public static CombatInventoryAdapter production(CombatClient client) {
        return new CombatInventoryAdapter(client, Ownership.live());
    }

    public boolean screenOpen() {
        return client.screenOpen();
    }

    public int selectedSlot() {
        return client.selectedSlot();
    }

    /** True while a loan or weapon attack owns the hotbar, so combat must not switch it. */
    public boolean ownedElsewhere() {
        return ownership.loanActive() || ownership.weaponAttackActive();
    }

    public String hotbarItem(int slot) {
        return client.hotbarItem(slot);
    }

    public String offhandItem() {
        return client.offhandItem();
    }

    /**
     * Slot that can break {@code blockId}, or -1 when none does. Only obsidian and piston bases need a
     * pickaxe; any other block uses the current slot so the planner does not switch for nothing.
     */
    public int toolSlot(String blockId) {
        if (blockId == null || blockId.isEmpty()) return -1;
        if (!requiresPickaxe(blockId)) {
            int selected = client.selectedSlot();
            return selected >= 0 && selected <= 8 ? selected : -1;
        }
        for (String tier : PICKAXE_TIERS) {
            for (int slot = 0; slot <= 8; slot++) {
                if (tier.equals(client.hotbarItem(slot))) return slot;
            }
        }
        return -1;
    }

    /** Obsidian and piston bases are the only blocks the trap has to mine. */
    public static boolean requiresPickaxe(String blockId) {
        return "minecraft:obsidian".equals(blockId) || blockId.endsWith("piston");
    }

    /**
     * Select {@code slot} for a dispatch. Local selection is moved first so the client's idea and the
     * server's agree, then the packet is sent; a refused send rolls the local slot back.
     */
    public SlotSwitch switchTo(int slot, int previous, CombatPacketSink sink) {
        if (slot < 0 || slot > 8 || sink == null) {
            return new SlotSwitch(true, false, false, false, "invalid-slot");
        }
        int current = client.selectedSlot();
        if (current == slot) return SlotSwitch.none();
        client.selectHotbarSlot(slot);
        CombatPacketSink.SendResult result = sink.selectSlot(slot);
        if (result == null || !result.enqueued()) {
            client.selectHotbarSlot(previous >= 0 && previous <= 8 ? previous : current);
            return new SlotSwitch(true, false, false, false, "send-refused");
        }
        return new SlotSwitch(true, true, result.sendMethodCompleted(), false, null);
    }

    /**
     * Put the selection back after a dispatch only when it is still ours. A user selection change wins and
     * is reported instead of overwritten.
     */
    public SlotSwitch restore(int previous, int owned, CombatPacketSink sink) {
        if (previous < 0 || previous > 8 || sink == null) {
            return new SlotSwitch(true, false, false, false, "invalid-slot");
        }
        int current = client.selectedSlot();
        if (current == previous) return SlotSwitch.none();
        if (current != owned) {
            return new SlotSwitch(false, false, false, false, "user-changed-selection");
        }
        CombatPacketSink.SendResult result = sink.selectSlot(previous);
        if (result == null || !result.enqueued()) {
            return new SlotSwitch(true, false, false, false, "send-refused");
        }
        client.selectHotbarSlot(previous);
        return new SlotSwitch(true, true, result.sendMethodCompleted(), true, null);
    }
}
