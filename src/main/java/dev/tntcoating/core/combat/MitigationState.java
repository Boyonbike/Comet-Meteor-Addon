/*
 * Original Comet implementation by Boyonabike.
 * Independent: written from the pinned 26.2 artifact and Meteor build 35 behaviour; no third-party code.
 * SPDX-License-Identifier: CC0-1.0
 */

package dev.tntcoating.core.combat;

import java.util.Objects;

/**
 * The inputs the pinned 26.2 mitigation path reads for one entity at one instant: difficulty, the armour
 * and toughness totals (already floored to whole points, as vanilla does), the explosion protection points
 * from enchantments, the Resistance amplifier, and the damage-source bypass flags.
 *
 * <p>This is a snapshot, not a bound. {@link CombatDamage#weakestEnvelope} derives the weakest state the
 * entity could be in at the latest arrival; no model may widen a self or friend upper bound by lowering
 * mitigation except through that envelope.</p>
 */
public record MitigationState(CombatDifficulty difficulty, double armour, double toughness,
                              double protectionPoints, int resistanceAmplifier,
                              boolean bypassesArmour, boolean bypassesResistance,
                              boolean bypassesEnchantments) {
    public MitigationState {
        Objects.requireNonNull(difficulty, "difficulty");
        if (!Double.isFinite(armour) || armour < 0.0) {
            throw new IllegalArgumentException("armour must be finite and non-negative: " + armour);
        }
        if (!Double.isFinite(toughness) || toughness < 0.0) {
            throw new IllegalArgumentException("toughness must be finite and non-negative: " + toughness);
        }
        if (!Double.isFinite(protectionPoints) || protectionPoints < 0.0) {
            throw new IllegalArgumentException("protectionPoints must be finite and non-negative: "
                + protectionPoints);
        }
        if (resistanceAmplifier < 0) {
            throw new IllegalArgumentException("resistanceAmplifier must be non-negative: "
                + resistanceAmplifier);
        }
    }
}
