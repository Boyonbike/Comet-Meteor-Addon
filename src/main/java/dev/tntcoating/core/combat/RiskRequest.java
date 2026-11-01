/*
 * Original Comet implementation by Boyonabike.
 * Independent: written from the pinned 26.2 artifact and Meteor build 35 behaviour; no third-party code.
 * SPDX-License-Identifier: CC0-1.0
 */

package dev.tntcoating.core.combat;

import java.util.Objects;

/**
 * Everything the scheduler needs to reserve one dispatch atomically: the exposure bound, its arrival
 * interval, the safety state it was priced against, the bound crystal (when one exists), the packet cost,
 * the slot/rotation/mining flags and, optionally, the plan's real {@link Footprint}.
 *
 * <p>A risk without both a bound crystal and a bound position can never retire by rule 1; it remains debt
 * until rule 5 or an explicitly enabled statistical path clears it.
 */
public record RiskRequest(ExposureBound upper, ServerTickInterval arrival, SafetyState safety,
                          EntityKey boundCrystal, Vec boundPosition, int packetCost, int tick,
                          boolean needsSlot, boolean needsRotation, boolean needsMiningSlot,
                          Footprint footprint) {
    public RiskRequest {
        Objects.requireNonNull(upper, "upper");
        Objects.requireNonNull(arrival, "arrival");
        Objects.requireNonNull(safety, "safety");
    }

    /**
     * Convenience preserving the original crystal-bearing call without a footprint. The scheduler falls
     * back to a footprint built from the intent's locks.
     */
    public RiskRequest(ExposureBound upper, ServerTickInterval arrival, SafetyState safety,
                       EntityKey boundCrystal, Vec boundPosition, int packetCost, int tick,
                       boolean needsSlot, boolean needsRotation, boolean needsMiningSlot) {
        this(upper, arrival, safety, boundCrystal, boundPosition, packetCost, tick, needsSlot,
            needsRotation, needsMiningSlot, null);
    }

    /** Convenience for risks that name no bound crystal; such risks can never retire by rule 1. */
    public RiskRequest(ExposureBound upper, ServerTickInterval arrival, SafetyState safety,
                       int packetCost, int tick, boolean needsSlot, boolean needsRotation,
                       boolean needsMiningSlot) {
        this(upper, arrival, safety, null, null, packetCost, tick, needsSlot, needsRotation,
            needsMiningSlot, null);
    }
}
