/*
 * Original Comet implementation by Boyonabike.
 * Independent: written from the pinned 26.2 artifact and Meteor build 35 behaviour; no third-party code.
 * SPDX-License-Identifier: CC0-1.0
 */

package dev.tntcoating.core.combat;

import java.util.List;
import java.util.Objects;

/**
 * The pure explosion-damage arithmetic: raw explosion damage, the audited mitigation chain, the mitigation
 * envelope and the assembly of a three-value target estimate.
 *
 * <p>Every number here is unrounded. Meteor's {@code DamageUtils} casts raw explosion damage to {@code int};
 * the adapter never uses that truncated value as an upper bound (audit C18).</p>
 */
public final class CombatDamage {
    /** Hard-coded vanilla divider: armour points are worth at most 20 of the 25-point scale. */
    public static final double ARMOUR_PROTECTION_DIVIDER = 25.0;
    public static final double MAX_ARMOUR_PROTECTION = 20.0;
    public static final double MIN_ARMOUR_RATIO = 0.2;
    public static final double BASE_ARMOUR_TOUGHNESS = 2.0;
    /** A vanilla end crystal explodes with radius 6, and the damage path uses twice the radius. */
    public static final double CRYSTAL_EXPLOSION_POWER = 12.0;

    private static final double EXPLOSION_BASE_DAMAGE = 1.0;
    private static final double EXPLOSION_DAMAGE_SCALE = 7.0;
    private static final double RESISTANCE_PER_LEVEL = 0.2;
    private static final double MAX_PROTECTION_POINTS = 20.0;

    private CombatDamage() {
    }

    /**
     * Vanilla {@code ExplosionDamageCalculator#getEntityDamageAmount} for an explosion whose
     * {@code radius * 2} is {@code power} (12 for an end crystal), without the {@code int} truncation.
     *
     * <p>{@code distance} is the feet-to-centre distance vanilla uses. An entity outside the power radius
     * receives nothing, matching the audited Meteor guard.</p>
     */
    public static RawDamage rawExplosionDamage(double distance, double seenPercent, double power) {
        if (!Double.isFinite(distance) || distance < 0.0) {
            throw new IllegalArgumentException("distance must be finite and non-negative: " + distance);
        }
        if (!Double.isFinite(seenPercent) || seenPercent < 0.0 || seenPercent > 1.0) {
            throw new IllegalArgumentException("seenPercent must be in [0, 1]: " + seenPercent);
        }
        if (!Double.isFinite(power) || power <= 0.0) {
            throw new IllegalArgumentException("power must be finite and positive: " + power);
        }
        if (distance > power) return new RawDamage(0.0);
        double impact = (1.0 - distance / power) * seenPercent;
        double damage = (impact * impact + impact) / 2.0 * EXPLOSION_DAMAGE_SCALE * power
            + EXPLOSION_BASE_DAMAGE;
        return new RawDamage(damage);
    }

    /** Vanilla {@code Player#hurtServer} difficulty scaling for a damage type that scales. */
    public static double afterDifficulty(double damage, CombatDifficulty difficulty) {
        Objects.requireNonNull(difficulty, "difficulty");
        requireDamage(damage);
        return switch (difficulty) {
            case PEACEFUL -> 0.0;
            case EASY -> Math.min(damage / 2.0 + 1.0, damage);
            case NORMAL -> damage;
            case HARD -> damage * 1.5;
        };
    }

    /** Vanilla {@code CombatRules#getDamageAfterAbsorb}, unrounded. */
    public static double afterArmour(double damage, double armour, double toughness) {
        requireDamage(damage);
        ArmorPiece.requireNonNegativeFinite(armour, "armour");
        ArmorPiece.requireNonNegativeFinite(toughness, "toughness");
        double reduction = Math.max(armour * MIN_ARMOUR_RATIO,
            Math.min(MAX_ARMOUR_PROTECTION, armour - damage / (BASE_ARMOUR_TOUGHNESS + toughness / 4.0)));
        return damage * (1.0 - reduction / ARMOUR_PROTECTION_DIVIDER);
    }

    /** Vanilla Resistance, one linear 20% factor per level, floored at zero damage. */
    public static double afterResistance(double damage, int amplifier) {
        requireDamage(damage);
        if (amplifier < 0) {
            throw new IllegalArgumentException("amplifier must be non-negative: " + amplifier);
        }
        return Math.max(0.0, damage * (1.0 - RESISTANCE_PER_LEVEL * (amplifier + 1)));
    }

    /** Vanilla {@code CombatRules#getDamageAfterMagicAbsorb} for protection points. */
    public static double afterProtection(double damage, double protectionPoints) {
        requireDamage(damage);
        ArmorPiece.requireNonNegativeFinite(protectionPoints, "protectionPoints");
        return damage * (1.0 - Math.min(protectionPoints, MAX_PROTECTION_POINTS) / ARMOUR_PROTECTION_DIVIDER);
    }

    /** The full audited mitigation chain, in vanilla order, before absorption. */
    public static MitigatedDamage mitigated(RawDamage raw, MitigationState state) {
        Objects.requireNonNull(raw, "raw");
        Objects.requireNonNull(state, "state");
        requireDamage(raw.value());
        double damage = afterDifficulty(raw.value(), state.difficulty());
        if (!state.bypassesArmour()) {
            damage = afterArmour(damage, state.armour(), state.toughness());
        }
        if (!state.bypassesResistance()) {
            damage = afterResistance(damage, state.resistanceAmplifier());
        }
        if (!state.bypassesEnchantments()) {
            damage = afterProtection(damage, state.protectionPoints());
        }
        return new MitigatedDamage(Math.max(0.0, damage));
    }

    /**
     * The weakest mitigation the entity could have at the latest arrival: armour pieces flagged as possibly
     * breaking are removed, and expiring effects are dropped. The envelope is only ever weaker than the
     * current state, so a self or friend upper bound computed from it never narrows.
     */
    public static MitigationState weakestEnvelope(MitigationState current, List<ArmorPiece> pieces,
                                                  boolean effectsMayExpire) {
        Objects.requireNonNull(current, "current");
        Objects.requireNonNull(pieces, "pieces");
        double armour = 0.0;
        double toughness = 0.0;
        double protection = 0.0;
        for (ArmorPiece piece : pieces) {
            Objects.requireNonNull(piece, "piece");
            if (piece.couldBreakBeforeArrival()) continue;
            armour += piece.armour();
            toughness += piece.toughness();
            protection += piece.protectionPoints();
        }
        int resistance = effectsMayExpire ? 0 : current.resistanceAmplifier();
        return new MitigationState(current.difficulty(), armour, toughness, protection, resistance,
            current.bypassesArmour(), current.bypassesResistance(), current.bypassesEnchantments());
    }

    /**
     * Assemble the three target values. The guaranteed lower bound uses the strongest (current) mitigation
     * and the target-lower-bound exposure; expected uses faithful exposure and current mitigation;
     * optimistic is diagnostic only and uses the weakest envelope.
     */
    public static TargetEstimate targetEstimate(RawDamage guaranteedLowerRaw, RawDamage expectedRaw,
                                                RawDamage optimisticRaw, MitigationState current,
                                                MitigationState weakest) {
        Objects.requireNonNull(guaranteedLowerRaw, "guaranteedLowerRaw");
        Objects.requireNonNull(expectedRaw, "expectedRaw");
        Objects.requireNonNull(optimisticRaw, "optimisticRaw");
        Objects.requireNonNull(current, "current");
        Objects.requireNonNull(weakest, "weakest");
        double guaranteedLower = mitigated(guaranteedLowerRaw, current).beforeAbsorption();
        double expected = mitigated(expectedRaw, current).beforeAbsorption();
        double optimistic = mitigated(optimisticRaw, weakest).beforeAbsorption();
        return new TargetEstimate(guaranteedLower, expected, Math.max(optimistic, expected));
    }

    /** Upper bound on the damage this entity can take from one explosion at this exposure. */
    public static MitigatedDamage upperBound(RawDamage raw, MitigationState weakest) {
        return mitigated(raw, weakest);
    }

    private static void requireDamage(double damage) {
        if (!Double.isFinite(damage) || damage < 0.0) {
            throw new IllegalArgumentException("damage must be finite and non-negative: " + damage);
        }
    }
}
