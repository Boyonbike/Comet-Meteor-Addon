/*
 * Original Comet implementation by Boyonabike.
 * Independent: written from the pinned 26.2 artifact and Meteor build 35 behaviour; no third-party code.
 * SPDX-License-Identifier: CC0-1.0
 */

package dev.tntcoating.core.combat.mc;

import dev.tntcoating.core.InventorySlotLoan;
import dev.tntcoating.core.InventoryWeaponAttack;
import java.util.Objects;

/**
 * Hotbar-only inventory boundary for combat dispatch. It never moves the offhand item, never requests a
 * mining action and never calls {@link InventorySlotLoan#prepare()}: that method is not a query (it can
 * send a container SWAP and clear a loan), so any active loan is treated as "owned elsewhere" instead.
 *
 * <p>Restore is deliberately narrow: the previously selected slot is restored only when the module still
 * owns the selection (the user has not changed it in the meantime). A failed supporting packet is reported,
 * never retried, and never rolls back a causative packet.
 */
public final class CombatInventoryAdapter {
    /** Queries only; production reads the loan and weapon-attack singletons. */
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

    /** Outcome of one supporting slot packet. */
    public record SlotSwitch(boolean attempted, boolean enqueued, boolean completed, boolean restored,
                             String failure) {
        public static SlotSwitch none() {
            return new SlotSwitch(false, false, false, false, null);
        }

        public boolean failed() {
            return attempted && !enqueued;
        }
    }

    private final CombatClient client;
    private final Ownership ownership;

    public CombatInventoryAdapter(CombatClient client, Ownership ownership) {
        this.client = Objects.requireNonNull(client, "client");
        this.ownership = Objects.requireNonNull(ownership, "ownership");
    }

    /** Production adapter bound to the account-wide loan and weapon-attack ownership. */
    public static CombatInventoryAdapter production(CombatClient client) {
        return new CombatInventoryAdapter(client, Ownership.live());
    }

    public boolean screenOpen() {
        return client.screenOpen();
    }

    public int selectedSlot() {
        return client.selectedSlot();
    }

    /** Another subsystem owns an inventory/attack slot and combat must not interleave. */
    public boolean ownedElsewhere() {
        return ownership.loanActive() || ownership.weaponAttackActive();
    }

    public String hotbarItem(int slot) {
        return client.hotbarItem(slot);
    }

    public String offhandItem() {
        return client.offhandItem();
    }

    /** Pickaxes in descending tier order; only these can mine the trap's piston and obsidian. */
    private static final String[] PICKAXE_TIERS = {
        "minecraft:netherite_pickaxe",
        "minecraft:diamond_pickaxe",
        "minecraft:iron_pickaxe",
        "minecraft:stone_pickaxe",
        "minecraft:golden_pickaxe",
        "minecraft:wooden_pickaxe"
    };

    /**
     * The hotbar slot the module would use to break {@code blockId}, or {@code -1} when no safe tool is
     * available. Only slots 0-8 are considered; the offhand is never a tool source. Blocks that do not
     * require a pickaxe (for example the trap's redstone torch) use the current slot, which may hold an
     * empty hand.
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

    private static boolean requiresPickaxe(String blockId) {
        return "minecraft:obsidian".equals(blockId) || blockId.endsWith("piston");
    }

    /**
     * Moves the client selection to {@code slot} and sends the matching hotbar packet. The client-side
     * selection is rolled back locally when the packet is refused; no retry is attempted.
     */
    public SlotSwitch switchTo(int slot, int previousSlot, CombatPacketSink sink) {
        if (slot < 0 || slot > 8 || sink == null) {
            return new SlotSwitch(true, false, false, false, "invalid-slot");
        }
        int current = client.selectedSlot();
        if (current == slot) return SlotSwitch.none();
        client.selectHotbarSlot(slot);
        CombatPacketSink.SendResult sent = sink.selectSlot(slot);
        if (!sent.enqueued()) {
            client.selectHotbarSlot(previousSlot >= 0 ? previousSlot : current);
            return new SlotSwitch(true, false, false, false, "send-refused");
        }
        return new SlotSwitch(true, true, sent.sendMethodCompleted(), false, null);
    }

    /**
     * Restores {@code previousSlot} only while the module still owns the selection. When the user changed
     * the selected slot after dispatch the restore is abandoned silently: manual priority wins.
     */
    public SlotSwitch restore(int previousSlot, int ownedSlot, CombatPacketSink sink) {
        if (previousSlot < 0 || previousSlot > 8 || sink == null || ownedSlot < 0) {
            return SlotSwitch.none();
        }
        if (client.selectedSlot() != ownedSlot) {
            return new SlotSwitch(false, false, false, false, "user-changed-selection");
        }
        CombatPacketSink.SendResult sent = sink.selectSlot(previousSlot);
        if (!sent.enqueued()) {
            return new SlotSwitch(true, false, false, false, "send-refused");
        }
        client.selectHotbarSlot(previousSlot);
        return new SlotSwitch(true, true, sent.sendMethodCompleted(), true, null);
    }
}
