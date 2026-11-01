/*
 * Original Comet implementation by Boyonabike.
 * Independent: tests written against the project's own interfaces; no third-party code.
 * SPDX-License-Identifier: CC0-1.0
 */

package dev.tntcoating.core.combat;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;

/** Phase 4.7 table coverage, deterministic percentiles and safe recording for {@link CombatMetrics}. */
final class CombatMetricsTest {
    @Test
    void everyPhase47CounterExistsAndIsSeparate() {
        assertEquals(15, CombatMetrics.COUNTERS.size());
        assertEquals(Set.of(
            "estimated_target_damage",
            "place_attempts", "place_observed",
            "attack_attempts", "explosion_observed",
            "target_damage_observed", "totem_pops_observed", "unknown_outcome",
            "risk_debt", "suspensions", "defence_actions",
            "queue_age_ms", "observe_to_apply_ms", "apply_to_send_ms", "combat_tick_ms"),
            Set.copyOf(CombatMetrics.COUNTERS));
        assertTrue(CombatMetrics.COUNTERS.containsAll(CombatMetrics.LATENCY_COUNTERS));
        assertEquals(4, CombatMetrics.LATENCY_COUNTERS.size());
        for (String counter : CombatMetrics.COUNTERS) {
            assertFalse(counter.isBlank(), "counter names are stable table names");
        }

        CombatMetrics metrics = new CombatMetrics();
        metrics.recordPlaceAttempt();
        metrics.recordPlaceObserved();
        metrics.recordAttackAttempt();
        metrics.recordExplosionObserved();
        metrics.recordTargetDamageObserved(5.0);
        metrics.recordTotemPopObserved();
        metrics.recordUnknownOutcome();
        metrics.recordEstimatedTargetDamage(7.0);

        assertEquals(1, metrics.placeAttempts());
        assertEquals(1, metrics.placeObserved());
        assertEquals(1, metrics.attackAttempts());
        assertEquals(1, metrics.explosionObserved());
        assertEquals(5.0, metrics.targetDamageObserved());
        assertEquals(1, metrics.totemPopsObserved());
        assertEquals(1, metrics.unknownOutcome());
        assertEquals(7.0, metrics.estimatedTargetDamage());
        assertEquals(0.0, metrics.riskDebtIntegral(), "recording one counter never moves another");
        assertEquals(0, metrics.totalSuspensions());
        assertEquals(0, metrics.totalDefenceActions());
    }

    @Test
    void emptyWindowReportsNoSamples() {
        CombatMetrics metrics = new CombatMetrics();
        for (String counter : CombatMetrics.LATENCY_COUNTERS) {
            CombatMetrics.Percentiles percentiles = metrics.percentiles(counter);
            assertEquals(0, percentiles.samples(), counter);
            assertEquals(0.0, percentiles.p50());
            assertEquals(0.0, percentiles.p95());
            assertEquals(0.0, percentiles.p99());
        }
    }

    @Test
    void oneSampleMakesEveryPercentileThatSample() {
        CombatMetrics metrics = new CombatMetrics();
        metrics.recordCombatTickMs(4.0);
        CombatMetrics.Percentiles percentiles = metrics.percentiles(CombatMetrics.COMBAT_TICK_MS);
        assertEquals(1, percentiles.samples());
        assertEquals(4.0, percentiles.p50());
        assertEquals(4.0, percentiles.p95());
        assertEquals(4.0, percentiles.p99());
    }

    @Test
    void nearestRankPercentilesAreDeterministic() {
        CombatMetrics metrics = new CombatMetrics();
        for (int i = 1; i <= 100; i++) metrics.recordQueueAgeMs(i);
        CombatMetrics.Percentiles percentiles = metrics.percentiles(CombatMetrics.QUEUE_AGE_MS);
        assertEquals(100, percentiles.samples());
        assertEquals(50.0, percentiles.p50());
        assertEquals(95.0, percentiles.p95());
        assertEquals(99.0, percentiles.p99());
    }

    @Test
    void windowKeepsTheNewestSamplesAndStaysBounded() {
        CombatMetrics metrics = new CombatMetrics();
        int total = CombatMetrics.LATENCY_WINDOW + 88;
        for (int i = 0; i < total; i++) metrics.recordObserveToApplyMs(i);
        CombatMetrics.Percentiles percentiles = metrics.percentiles(CombatMetrics.OBSERVE_TO_APPLY_MS);
        assertEquals(CombatMetrics.LATENCY_WINDOW, percentiles.samples());
        // The window now holds 88..599; nearest rank over 512 values: index ceil(p * 512) - 1.
        assertEquals(88.0 + 255, percentiles.p50());
        assertEquals(88.0 + 486, percentiles.p95());
        assertEquals(88.0 + 506, percentiles.p99());
    }

    @Test
    void invalidSamplesAreIgnoredInsteadOfCorruptingData() {
        CombatMetrics metrics = new CombatMetrics();
        metrics.recordEstimatedTargetDamage(-1.0);
        metrics.recordEstimatedTargetDamage(Double.NaN);
        metrics.recordEstimatedTargetDamage(Double.POSITIVE_INFINITY);
        metrics.recordTargetDamageObserved(-0.5);
        metrics.recordRiskDebt(-2.0);
        metrics.recordRiskDebt(Double.NaN);
        metrics.recordQueueAgeMs(-1.0);
        metrics.recordQueueAgeMs(Double.NaN);
        metrics.recordCombatTickMs(Double.POSITIVE_INFINITY);
        metrics.recordRetirement(null);
        metrics.recordRetirement("  ");
        metrics.recordSuspension(null);
        metrics.recordDefenceAction(null);

        assertEquals(0.0, metrics.estimatedTargetDamage());
        assertEquals(0.0, metrics.targetDamageObserved());
        assertEquals(0.0, metrics.riskDebtIntegral());
        assertEquals(0, metrics.riskDebtSamples());
        assertEquals(0, metrics.percentiles(CombatMetrics.QUEUE_AGE_MS).samples());
        assertEquals(0, metrics.percentiles(CombatMetrics.COMBAT_TICK_MS).samples());
        assertTrue(metrics.retirementsByRule().isEmpty());
        assertEquals(0, metrics.totalSuspensions());
        assertEquals(0, metrics.totalDefenceActions());
    }

    @Test
    void riskDebtIntegralCountsOutstandingTimeSeparatelyFromRetirements() {
        CombatMetrics metrics = new CombatMetrics();
        metrics.recordRiskDebt(3.0);
        metrics.recordRiskDebt(5.0);
        metrics.recordRetirement("rule_2");
        metrics.recordRetirement("rule_1");
        metrics.recordRetirement("rule_1");

        assertEquals(8.0, metrics.riskDebtIntegral());
        assertEquals(2, metrics.riskDebtSamples());
        assertEquals(4.0, metrics.meanRiskDebt());
        assertEquals(Map.of("rule_1", 2L, "rule_2", 1L), metrics.retirementsByRule());
        assertEquals(2, metrics.retirements("rule_1"));
        assertEquals(0, metrics.retirements("missing"));
    }

    @Test
    void suspensionsAndDefenceActionsStayBucketedByCauseAndType() {
        CombatMetrics metrics = new CombatMetrics();
        metrics.recordSuspension(SuspensionCause.QUEUE_OVERFLOW);
        metrics.recordSuspension(SuspensionCause.QUEUE_OVERFLOW);
        metrics.recordSuspension(SuspensionCause.DEATH);
        metrics.recordDefenceAction("break_crystal");
        metrics.recordDefenceAction("reequip_totem");
        metrics.recordDefenceAction("break_crystal");

        assertEquals(3, metrics.totalSuspensions());
        assertEquals(2, metrics.suspensions(SuspensionCause.QUEUE_OVERFLOW));
        assertEquals(1, metrics.suspensions(SuspensionCause.DEATH));
        assertEquals(0, metrics.suspensions(SuspensionCause.SETBACK));
        assertEquals(Map.of(SuspensionCause.QUEUE_OVERFLOW, 2L, SuspensionCause.DEATH, 1L),
            metrics.suspensionsByCause());
        assertEquals(3, metrics.totalDefenceActions());
        assertEquals(Map.of("break_crystal", 2L, "reequip_totem", 1L),
            metrics.defenceActionsByType());
    }

    @Test
    void percentilesRejectUnknownCountersInsteadOfGuessing() {
        CombatMetrics metrics = new CombatMetrics();
        assertThrows(IllegalArgumentException.class, () -> metrics.percentiles("place_attempts"));
        assertThrows(IllegalArgumentException.class, () -> metrics.percentiles("estimated_target_damage"));
        assertThrows(NullPointerException.class, () -> metrics.percentiles(null));
    }

    @Test
    void resetClearsEveryCounterAndWindow() {
        CombatMetrics metrics = new CombatMetrics();
        metrics.recordEstimatedTargetDamage(4.0);
        metrics.recordPlaceAttempt();
        metrics.recordPlaceObserved();
        metrics.recordAttackAttempt();
        metrics.recordExplosionObserved();
        metrics.recordTargetDamageObserved(4.0);
        metrics.recordTotemPopObserved();
        metrics.recordUnknownOutcome();
        metrics.recordRiskDebt(2.0);
        metrics.recordRetirement("rule_1");
        metrics.recordSuspension(SuspensionCause.SETBACK);
        metrics.recordDefenceAction("break_crystal");
        metrics.recordQueueAgeMs(1.0);
        metrics.recordObserveToApplyMs(2.0);
        metrics.recordApplyToSendMs(3.0);
        metrics.recordCombatTickMs(4.0);

        metrics.reset();

        assertEquals(0.0, metrics.estimatedTargetDamage());
        assertEquals(0, metrics.placeAttempts());
        assertEquals(0, metrics.placeObserved());
        assertEquals(0, metrics.attackAttempts());
        assertEquals(0, metrics.explosionObserved());
        assertEquals(0.0, metrics.targetDamageObserved());
        assertEquals(0, metrics.totemPopsObserved());
        assertEquals(0, metrics.unknownOutcome());
        assertEquals(0.0, metrics.riskDebtIntegral());
        assertEquals(0, metrics.riskDebtSamples());
        assertEquals(0.0, metrics.meanRiskDebt());
        assertTrue(metrics.retirementsByRule().isEmpty());
        assertTrue(metrics.suspensionsByCause().isEmpty());
        assertEquals(0, metrics.totalSuspensions());
        assertTrue(metrics.defenceActionsByType().isEmpty());
        for (String counter : CombatMetrics.LATENCY_COUNTERS) {
            assertEquals(0, metrics.percentiles(counter).samples(), counter);
        }
    }
}
