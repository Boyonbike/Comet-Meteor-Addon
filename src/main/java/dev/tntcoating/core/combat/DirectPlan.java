/*
 * Original Comet implementation by Boyonabike.
 * Independent: written from the pinned 26.2 artifact and Meteor build 35 behaviour; no third-party code.
 * SPDX-License-Identifier: CC0-1.0
 */

package dev.tntcoating.core.combat;

/**
 * The single verified action a planning pass chose. Placement plans carry the crystal cell and, for the
 * support variant, {@link Action#PLACE_SUPPORT} with the support cell in {@code cell}. Break plans carry
 * the crystal entity and the cell it occupies.
 */
public record DirectPlan(
    Action action,
    EntityKey target,
    Cell cell,
    EntityKey entity,
    Intent intent,
    DirectContext context
) {
    public DirectPlan {
        if (action == null) throw new IllegalArgumentException("action");
        if (intent == null) throw new IllegalArgumentException("intent");
        if (context == null) throw new IllegalArgumentException("context");
    }

    public boolean isPlacement() {
        return action == Action.PLACE_CRYSTAL || action == Action.PLACE_SUPPORT
            || action == Action.PLACE_BLOCKER;
    }

    public boolean isBreak() {
        return action == Action.ATTACK_CRYSTAL;
    }

    /** Phase 4.6: a defensive obsidian placement in the piston push path. */
    public boolean isBlocker() {
        return action == Action.PLACE_BLOCKER;
    }

    /**
     * Packet/action cost of this plan. A crystal placement costs one action; the support variant costs
     * two because the support block and the later crystal are separate player actions. Phase 4.4 models
     * the cost here and tests it; the current engine separately enforces one placement per tick and one
     * pending support, so the chain always spans at least two ticks. The value is for later selection/UI
     * accounting and is not read by the scheduler as a packet count.
     */
    public int actionCost() {
        return action == Action.PLACE_SUPPORT ? 2 : 1;
    }
}
