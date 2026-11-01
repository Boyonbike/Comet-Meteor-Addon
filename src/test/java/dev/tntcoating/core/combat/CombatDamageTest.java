/*
 * Original Comet implementation by Boyonabike.
 * Independent: written from the pinned 26.2 artifact and Meteor build 35 behaviour; no third-party code.
 * SPDX-License-Identifier: CC0-1.0
 */

package dev.tntcoating.core.combat;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CombatDamageTest {
    private static final double EPS = 1.0e-9;

    @Test
    void rawExplosionDamageMatchesTheAuditedFormula() {
        // impact = (1 - 4/12) = 2/3; (impact^2 + impact) / 2 * 7 * 12 + 1.
        double expected = (2.0 / 3.0 * 2.0 / 3.0 + 2.0 / 3.0) / 2.0 * 7.0 * 12.0 + 1.0;
        assertEquals(expected, CombatDamage.rawExplosionDamage(4.0, 1.0, 12.0).value(), EPS);
        assertEquals(85.0, CombatDamage.rawExplosionDamage(0.0, 1.0, 12.0).value(), EPS);
        assertEquals(1.0, CombatDamage.rawExplosionDamage(12.0, 1.0, 12.0).value(), EPS);
        assertEquals(0.0, CombatDamage.rawExplosionDamage(12.0001, 1.0, 12.0).value(), EPS);
    }

    @Test
    void rawDamageIsNotTruncatedToInt() {
        // Meteor's audited DamageUtils casts this to int (47); the adapter must keep the fraction so a
        // self or friend upper bound is never understated by the truncation (audit C18).
        double raw = CombatDamage.rawExplosionDamage(4.0, 1.0, 12.0).value();
        assertEquals(47.66666666666667, raw, 1.0e-9);
        assertTrue(raw > 47.0, "a truncated int must never be used as an upper bound");
    }

    @Test
    void rawDamageValidatesItsInputs() {
        assertThrows(IllegalArgumentException.class,
            () -> CombatDamage.rawExplosionDamage(Double.NaN, 1.0, 12.0));
        assertThrows(IllegalArgumentException.class,
            () -> CombatDamage.rawExplosionDamage(-1.0, 1.0, 12.0));
        assertThrows(IllegalArgumentException.class,
            () -> CombatDamage.rawExplosionDamage(1.0, -0.1, 12.0));
        assertThrows(IllegalArgumentException.class,
            () -> CombatDamage.rawExplosionDamage(1.0, 1.1, 12.0));
        assertThrows(IllegalArgumentException.class,
            () -> CombatDamage.rawExplosionDamage(1.0, 1.0, 0.0));
    }

    @Test
    void difficultyScalingFollowsHurtServer() {
        assertEquals(5.0, CombatDamage.afterDifficulty(8.0, CombatDifficulty.EASY), EPS);
        assertEquals(8.0, CombatDamage.afterDifficulty(8.0, CombatDifficulty.NORMAL), EPS);
        assertEquals(12.0, CombatDamage.afterDifficulty(8.0, CombatDifficulty.HARD), EPS);
        assertEquals(0.0, CombatDamage.afterDifficulty(8.0, CombatDifficulty.PEACEFUL), EPS);
        // EASY is min(damage / 2 + 1, damage): no free damage at tiny values.
        assertEquals(1.0, CombatDamage.afterDifficulty(1.0, CombatDifficulty.EASY), EPS);
    }

    @Test
    void armourReductionFollowsCombatRules() {
        // f = 2 + 8/4 = 4; reduction = 20 - 10/4 = 17.5; 17.5/25 = 0.7.
        assertEquals(3.0, CombatDamage.afterArmour(10.0, 20.0, 8.0), EPS);
        // Armour without toughness: reduction = 10 - 10/2 = 5, so 10 * (1 - 5/25) = 8.
        assertEquals(8.0, CombatDamage.afterArmour(10.0, 10.0, 0.0), EPS);
        // The 20% floor: heavy damage relative to light armour is clamped at armour * 0.2.
        assertEquals(98.4, CombatDamage.afterArmour(100.0, 2.0, 0.0), EPS);
        // No armour.
        assertEquals(10.0, CombatDamage.afterArmour(10.0, 0.0, 0.0), EPS);
        // Never negative.
        assertTrue(CombatDamage.afterArmour(1.0, 20.0, 20.0) >= 0.0);
    }

    @Test
    void resistanceIsLinearAndFlooredAtZero() {
        assertEquals(8.0, CombatDamage.afterResistance(10.0, 0), EPS);
        assertEquals(6.0, CombatDamage.afterResistance(10.0, 1), EPS);
        assertEquals(0.0, CombatDamage.afterResistance(10.0, 4), EPS);
        assertEquals(0.0, CombatDamage.afterResistance(10.0, 9), EPS);
    }

    @Test
    void protectionReductionFollowsCombatRules() {
        assertEquals(6.8, CombatDamage.afterProtection(10.0, 8.0), EPS);
        assertEquals(2.0, CombatDamage.afterProtection(10.0, 20.0), EPS);
        // Protection points are clamped at 20, so 30 is not stronger than 20.
        assertEquals(2.0, CombatDamage.afterProtection(10.0, 30.0), EPS);
    }

    @Test
    void mitigationChainUsesVanillaOrderAndNeverSubtractsAbsorption() {
        MitigationState state = new MitigationState(CombatDifficulty.NORMAL, 20.0, 8.0, 16.0, 1,
            false, false, false);
        double expected = CombatDamage.afterProtection(
            CombatDamage.afterResistance(CombatDamage.afterArmour(47.66666666666667, 20.0, 8.0), 1),
            16.0);
        assertEquals(expected, CombatDamage.mitigated(new RawDamage(47.66666666666667), state)
            .beforeAbsorption(), EPS);
    }

    @Test
    void bypassFlagsSkipOnlyTheirOwnStage() {
        MitigationState bypassAll = new MitigationState(CombatDifficulty.NORMAL, 20.0, 8.0, 16.0, 1,
            true, true, true);
        assertEquals(10.0, CombatDamage.mitigated(new RawDamage(10.0), bypassAll).beforeAbsorption(), EPS);

        MitigationState bypassArmourOnly = new MitigationState(CombatDifficulty.NORMAL, 20.0, 8.0, 0.0, 0,
            true, false, false);
        assertEquals(8.0, CombatDamage.mitigated(new RawDamage(10.0), bypassArmourOnly).beforeAbsorption(),
            EPS);
    }

    @Test
    void weakestEnvelopeRemovesDoomedPiecesAndExpiringEffects() {
        MitigationState current = new MitigationState(CombatDifficulty.HARD, 20.0, 8.0, 16.0, 1,
            false, false, false);
        List<ArmorPiece> pieces = List.of(
            new ArmorPiece(10.0, 4.0, 8.0, false),
            new ArmorPiece(10.0, 4.0, 8.0, true));
        MitigationState envelope = CombatDamage.weakestEnvelope(current, pieces, true);
        assertEquals(10.0, envelope.armour(), EPS);
        assertEquals(4.0, envelope.toughness(), EPS);
        assertEquals(8.0, envelope.protectionPoints(), EPS);
        assertEquals(0, envelope.resistanceAmplifier());
        assertEquals(CombatDifficulty.HARD, envelope.difficulty());
        // The envelope is never stronger than the current state.
        assertTrue(CombatDamage.mitigated(new RawDamage(20.0), envelope).beforeAbsorption()
            >= CombatDamage.mitigated(new RawDamage(20.0), current).beforeAbsorption());
    }

    @Test
    void weakestEnvelopeKeepsEffectsThatCannotExpire() {
        MitigationState current = new MitigationState(CombatDifficulty.NORMAL, 8.0, 0.0, 0.0, 2,
            false, false, false);
        MitigationState envelope = CombatDamage.weakestEnvelope(current, List.of(), false);
        assertEquals(2, envelope.resistanceAmplifier());
    }

    @Test
    void upperBoundIsTheWeakestMitigationOfTheRawValue() {
        MitigationState weakest = new MitigationState(CombatDifficulty.NORMAL, 0.0, 0.0, 0.0, 0,
            false, true, true);
        assertEquals(47.66666666666667,
            CombatDamage.upperBound(new RawDamage(47.66666666666667), weakest).beforeAbsorption(), EPS);
    }

    @Test
    void targetEstimateKeepsTheThreeValuesOrderedAndUsesTheEnvelopeForOptimistic() {
        MitigationState current = new MitigationState(CombatDifficulty.NORMAL, 20.0, 8.0, 0.0, 0,
            false, false, false);
        MitigationState weakest = new MitigationState(CombatDifficulty.NORMAL, 0.0, 0.0, 0.0, 0,
            false, false, false);
        TargetEstimate estimate = CombatDamage.targetEstimate(
            new RawDamage(20.0), new RawDamage(30.0), new RawDamage(40.0), current, weakest);
        assertEquals(CombatDamage.mitigated(new RawDamage(20.0), current).beforeAbsorption(),
            estimate.guaranteedLower(), EPS);
        assertEquals(CombatDamage.mitigated(new RawDamage(30.0), current).beforeAbsorption(),
            estimate.expected(), EPS);
        assertEquals(CombatDamage.mitigated(new RawDamage(40.0), weakest).beforeAbsorption(),
            estimate.optimistic(), EPS);
        assertTrue(estimate.guaranteedLower() <= estimate.expected());
        assertTrue(estimate.expected() <= estimate.optimistic());
    }

    @Test
    void targetEstimateNeverLetsOptimisticFallBelowExpected() {
        MitigationState current = new MitigationState(CombatDifficulty.NORMAL, 20.0, 8.0, 0.0, 0,
            false, false, false);
        MitigationState weakest = new MitigationState(CombatDifficulty.NORMAL, 0.0, 0.0, 0.0, 0,
            false, false, false);
        // A raw optimistic value below the expected raw value cannot produce a smaller optimistic number.
        TargetEstimate estimate = CombatDamage.targetEstimate(
            new RawDamage(10.0), new RawDamage(50.0), new RawDamage(1.0), current, weakest);
        assertTrue(estimate.optimistic() >= estimate.expected());
    }
}
