package dev.tntcoating.core;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class HighwayTravelSafetyTest {
    @Test void reconnectRebasesHealthWithoutErasingEarlierTripDamage() {
        var safety = new HighwayTravelSafety();
        safety.reset(20);
        assertFalse(safety.damageLimitReached(16, 8));
        safety.rebaseHealth(10);
        assertFalse(safety.damageLimitReached(10, 8));
        assertFalse(safety.damageLimitReached(20, 8));
        assertTrue(safety.damageLimitReached(16, 8));
    }

    @Test void logsOutAtTheConfiguredCumulativeDamageLimit() {
        HighwayTravelSafety safety = new HighwayTravelSafety();
        safety.reset(20);

        assertFalse(safety.damageLimitReached(17, 8));
        assertFalse(safety.damageLimitReached(15, 8));
        assertTrue(safety.damageLimitReached(12, 8));
    }

    @Test void healingDoesNotEraseDamageOrHideTheNextHealthDrop() {
        HighwayTravelSafety safety = new HighwayTravelSafety();
        safety.reset(20);

        assertFalse(safety.damageLimitReached(16, 8));
        assertFalse(safety.damageLimitReached(20, 8));
        assertTrue(safety.damageLimitReached(16, 8));
    }

    @Test void unchangedHealthDoesNotCountTheSameDamageAgain() {
        HighwayTravelSafety safety = new HighwayTravelSafety();
        safety.reset(20);

        assertFalse(safety.damageLimitReached(15, 8));
        assertFalse(safety.damageLimitReached(15, 8));
        assertFalse(safety.damageLimitReached(15, 8));
    }

    @Test void resettingTheSessionClearsPreviousDamage() {
        HighwayTravelSafety safety = new HighwayTravelSafety();
        safety.reset(20);
        assertFalse(safety.damageLimitReached(13, 8));

        safety.reset(13);

        assertFalse(safety.damageLimitReached(12, 8));
        assertTrue(safety.damageLimitReached(5, 8));
    }

    @Test void aNonpositiveDamageLimitDisablesLogout() {
        HighwayTravelSafety safety = new HighwayTravelSafety();
        safety.reset(20);

        assertFalse(safety.damageLimitReached(10, 0));
        assertFalse(safety.damageLimitReached(0, -1));
    }

    @Test void invalidHealthCannotPoisonTheDamageBaseline() {
        HighwayTravelSafety safety = new HighwayTravelSafety();
        safety.reset(20);

        assertThrows(IllegalArgumentException.class, () -> safety.reset(Double.NaN));
        assertThrows(IllegalArgumentException.class,
            () -> safety.damageLimitReached(Double.POSITIVE_INFINITY, 8));
        assertThrows(IllegalArgumentException.class,
            () -> safety.damageLimitReached(10, Double.NaN));
        assertTrue(safety.damageLimitReached(12, 8));
    }

    @Test void stationaryTravelReachesTheTimeoutOnTheConfiguredTick() {
        HighwayTravelSafety safety = new HighwayTravelSafety();
        safety.resetProgress(-100);

        assertFalse(safety.noProgress(-100, 3));
        assertFalse(safety.noProgress(-100, 3));
        assertTrue(safety.noProgress(-100, 3));
        assertTrue(safety.noProgress(-100, 3));
    }

    @Test void aFullBlockOfForwardProgressRestartsTheTimeout() {
        HighwayTravelSafety safety = new HighwayTravelSafety();
        safety.resetProgress(-100);
        assertFalse(safety.noProgress(-100, 3));
        assertFalse(safety.noProgress(-100, 3));

        assertFalse(safety.noProgress(-99, 3));
        assertFalse(safety.noProgress(-99, 3));
        assertFalse(safety.noProgress(-99, 3));
        assertTrue(safety.noProgress(-99, 3));
    }

    @Test void tinyStepsAccumulateIntoRealForwardProgress() {
        HighwayTravelSafety safety = new HighwayTravelSafety();
        safety.resetProgress(0);

        assertFalse(safety.noProgress(.25, 4));
        assertFalse(safety.noProgress(.5, 4));
        assertFalse(safety.noProgress(.75, 4));
        assertFalse(safety.noProgress(1, 4));
        assertFalse(safety.noProgress(1.25, 4));
        assertFalse(safety.noProgress(1.5, 4));
        assertFalse(safety.noProgress(1.75, 4));
        assertFalse(safety.noProgress(2, 4));
    }

    @Test void backwardOscillationDoesNotResetTheTimeout() {
        HighwayTravelSafety safety = new HighwayTravelSafety();
        safety.resetProgress(0);

        assertFalse(safety.noProgress(-2, 4));
        assertFalse(safety.noProgress(0, 4));
        assertFalse(safety.noProgress(-2, 4));
        assertTrue(safety.noProgress(0, 4));
    }

    @Test void subBlockJitterDoesNotResetTheTimeout() {
        HighwayTravelSafety safety = new HighwayTravelSafety();
        safety.resetProgress(0);

        assertFalse(safety.noProgress(.9, 3));
        assertFalse(safety.noProgress(.1, 3));
        assertTrue(safety.noProgress(.9, 3));
    }

    @Test void resettingProgressStartsAFreshTimeoutAtTheNewLocation() {
        HighwayTravelSafety safety = new HighwayTravelSafety();
        safety.resetProgress(50);
        assertFalse(safety.noProgress(50, 2));

        safety.resetProgress(-500);

        assertFalse(safety.noProgress(-500, 2));
        assertTrue(safety.noProgress(-500, 2));
    }

    @Test void damageTakenReportsCumulativeDropsForTheDashboard() {
        HighwayTravelSafety safety = new HighwayTravelSafety();
        safety.reset(20);
        assertFalse(safety.damageLimitReached(16, 8));
        assertFalse(safety.damageLimitReached(20, 8));
        assertEquals(4, safety.damageTaken(), 1e-9);

        safety.rebaseHealth(10);
        assertFalse(safety.damageLimitReached(8, 8));
        assertEquals(6, safety.damageTaken(), 1e-9);
    }

    @Test void invalidProgressCannotPoisonTheTimeout() {
        HighwayTravelSafety safety = new HighwayTravelSafety();
        safety.resetProgress(0);

        assertThrows(IllegalArgumentException.class, () -> safety.resetProgress(Double.NaN));
        assertThrows(IllegalArgumentException.class,
            () -> safety.noProgress(Double.NEGATIVE_INFINITY, 2));
        assertThrows(IllegalArgumentException.class, () -> safety.noProgress(0, 0));
        assertFalse(safety.noProgress(0, 2));
        assertTrue(safety.noProgress(0, 2));
    }
}
