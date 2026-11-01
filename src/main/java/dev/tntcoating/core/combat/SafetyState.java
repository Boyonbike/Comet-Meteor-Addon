/*
 * Original Comet implementation by Boyonabike.
 * Independent: written from the pinned 26.2 artifact and Meteor build 35 behaviour; no third-party code.
 * SPDX-License-Identifier: CC0-1.0
 */

package dev.tntcoating.core.combat;

import java.util.Map;
import java.util.Objects;

/**
 * The safety-relevant state used to price one reservation: world connection, action generation, the
 * player's own identity and effective health, per-friend effective health, and the thresholds.
 *
 * <p>Absorption is already folded into each {@link EffectiveHealth}; the budget never subtracts it from an
 * upper bound. Friends are independent: there is no shared friend pool.
 */
public record SafetyState(ConnectionKey conn, ActionGeneration gen, EntityKey self,
                          EffectiveHealth selfHealth, Map<EntityKey, EffectiveHealth> friends,
                          Safety safety) {
    public SafetyState {
        Objects.requireNonNull(conn, "conn");
        Objects.requireNonNull(gen, "gen");
        Objects.requireNonNull(self, "self");
        Objects.requireNonNull(selfHealth, "selfHealth");
        Objects.requireNonNull(safety, "safety");
        friends = friends == null ? Map.of() : Map.copyOf(friends);
    }

    public SafetyState(ConnectionKey conn, ActionGeneration gen, EntityKey self,
                       EffectiveHealth selfHealth, Safety safety) {
        this(conn, gen, self, selfHealth, Map.of(), safety);
    }
}
