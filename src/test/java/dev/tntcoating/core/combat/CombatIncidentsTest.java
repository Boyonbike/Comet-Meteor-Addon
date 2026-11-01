/*
 * Original Comet implementation by Boyonabike.
 * Independent: written from the pinned 26.2 artifact and Meteor build 35 behaviour; no third-party code.
 * SPDX-License-Identifier: CC0-1.0
 */

package dev.tntcoating.core.combat;

import org.junit.jupiter.api.Test;

import java.util.Set;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CombatIncidentsTest {
    @Test
    void damageBelowOrAtThePredictedBoundIsNotAnIncident() {
        assertFalse(CombatIncidents.selfDamageExceedsBound(20.0, 15.0, new Bound(5.0, 1)));
        assertFalse(CombatIncidents.selfDamageExceedsBound(20.0, 15.1, new Bound(5.0, 2)));
        assertFalse(CombatIncidents.selfDamageExceedsBound(20.0, 20.0, new Bound(0.0, 0)));
    }

    @Test
    void nonFiniteHealthOrMissingBoundIsNeverAnIncident() {
        assertFalse(CombatIncidents.selfDamageExceedsBound(Double.NaN, 10.0, new Bound(1.0, 1)));
        assertFalse(CombatIncidents.selfDamageExceedsBound(20.0, Double.POSITIVE_INFINITY, new Bound(1.0, 1)));
        assertFalse(CombatIncidents.selfDamageExceedsBound(20.0, 10.0, null));
    }

    @Test
    void damageAboveThePredictedBoundIsAnIncident() {
        assertTrue(CombatIncidents.selfDamageExceedsBound(20.0, 10.0, new Bound(5.0, 2)));
        assertTrue(CombatIncidents.selfDamageExceedsBound(20.0, 14.999, new Bound(5.0, 2)));
    }

    @Test
    void selfDamageLabelCarriesBothValuesAndComponents() {
        String label = CombatIncidents.selfDamageLabel(7.5, new Bound(4.25, 3));
        assertTrue(label.startsWith(CombatIncidents.PREFIX));
        assertTrue(label.contains("self-damage"));
        assertTrue(label.contains("damage=7.500"));
        assertTrue(label.contains("predicted_upper_bound=4.250"));
        assertTrue(label.contains("components=3"));
    }

    @Test
    void selfDeathLabelNamesModuleState() {
        assertEquals("incident: self-death module=enabled", CombatIncidents.selfDeathLabel(true));
        assertEquals("incident: self-death module=disabled", CombatIncidents.selfDeathLabel(false));
    }

    @Test
    void suspensionLabelListsAddedRemovedAndCurrentCauses() {
        String added = CombatIncidents.suspensionLabel(Set.of(SuspensionCause.QUEUE_OVERFLOW),
            Set.of(SuspensionCause.QUEUE_OVERFLOW, SuspensionCause.SETBACK));
        assertTrue(added.contains("suspended=true"));
        assertTrue(added.contains("added=[SETBACK]"));
        assertTrue(added.contains("removed=[]"));
        assertTrue(added.contains("causes=[QUEUE_OVERFLOW, SETBACK]"));

        String removed = CombatIncidents.suspensionLabel(
            Set.of(SuspensionCause.QUEUE_OVERFLOW, SuspensionCause.SETBACK), Set.of());
        assertTrue(removed.contains("suspended=false"));
        assertTrue(removed.contains("added=[]"));
        assertTrue(removed.contains("removed=[QUEUE_OVERFLOW, SETBACK]"));
        assertTrue(removed.contains("causes=[]"));
    }

    @Test
    void debtWatchReportsOnceAfterThirtySecondsAndResetsOnRecovery() {
        CombatIncidents.DebtWatch watch = new CombatIncidents.DebtWatch();
        long start = 1_000L;
        long thirty = CombatIncidents.DEBT_STUCK_NANOS;

        assertFalse(watch.update(true, start));
        assertTrue(watch.inEpisode());
        assertFalse(watch.update(true, start + thirty - 1));
        assertFalse(watch.reported());
        assertTrue(watch.update(true, start + thirty));
        assertTrue(watch.reported());
        assertEquals(thirty, watch.elapsedNanos(start + thirty));
        assertFalse(watch.update(true, start + 2 * thirty));

        assertFalse(watch.update(false, start + 2 * thirty + 1));
        assertFalse(watch.inEpisode());
        assertFalse(watch.reported());

        long second = start + 3 * thirty;
        assertFalse(watch.update(true, second));
        assertTrue(watch.update(true, second + thirty));
    }

    @Test
    void debtStuckLabelCarriesSecondsCountAndBound() {
        String label = CombatIncidents.debtStuckLabel(new Debt(2, 4.25),
            TimeUnit.SECONDS.toNanos(42));
        assertTrue(label.startsWith(CombatIncidents.PREFIX));
        assertTrue(label.contains("risk-debt-stuck"));
        assertTrue(label.contains("seconds=42.000"));
        assertTrue(label.contains("count=2"));
        assertTrue(label.contains("bound=4.250"));
    }

    @Test
    void manualMarkerIsFlattenedAndBounded() {
        assertEquals("incident: manual boom here", CombatIncidents.manualMarker("boom\nhere"));
        String longMarker = CombatIncidents.manualMarker("x".repeat(400));
        assertEquals("incident: manual " + "x".repeat(160), longMarker);
        assertEquals("incident: manual", CombatIncidents.manualMarker("   "));
    }

    @Test
    void ensurePrefixNeverDoublesTheIncidentPrefix() {
        assertEquals("incident: manual", CombatIncidents.ensurePrefix(""));
        assertEquals("incident: manual", CombatIncidents.ensurePrefix(null));
        assertEquals("incident: something", CombatIncidents.ensurePrefix("something"));
        assertEquals("incident: something", CombatIncidents.ensurePrefix("incident: something"));
    }

    @Test
    void dispatchInvariantAndManualLabelsUseTheIncidentPrefix() {
        String label = CombatIncidents.dispatchInvariantLabel("FAILED", "PLAN_MISMATCH");
        assertTrue(label.startsWith(CombatIncidents.PREFIX));
        assertTrue(label.contains("status=FAILED"));
        assertTrue(label.contains("reason=PLAN_MISMATCH"));
        assertTrue(CombatIncidents.manualMarker("oops").startsWith(CombatIncidents.PREFIX));
    }
}
