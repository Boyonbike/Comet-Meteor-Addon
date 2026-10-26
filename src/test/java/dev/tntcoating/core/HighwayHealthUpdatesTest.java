package dev.tntcoating.core;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class HighwayHealthUpdatesTest {
    @Test
    void logsOutAtTheRemainingHealthThresholdAfterConnecting() {
        HighwayHealthUpdates updates = new HighwayHealthUpdates(new HighwayTravelSafety());
        updates.reset(20);

        updates.connectionStarted();
        updates.accept(20);
        updates.accept(8);

        assertTrue(updates.lowHealth(8));
    }

    @Test
    void healingBeforeTheNextTickPreventsAFullHealthLogout() {
        HighwayHealthUpdates updates = new HighwayHealthUpdates(new HighwayTravelSafety());
        updates.reset(20);

        updates.accept(6);
        updates.accept(20);

        assertFalse(updates.lowHealth(8));
    }

    @Test
    void repeatedHealedMobHitsDoNotExhaustATripDamageBudget() {
        HighwayTravelSafety safety = new HighwayTravelSafety();
        HighwayHealthUpdates updates = new HighwayHealthUpdates(safety);
        updates.reset(20);

        for (int hit = 0; hit < 10; hit++) {
            updates.accept(18);
            assertFalse(updates.lowHealth(8));
            updates.accept(20);
            assertFalse(updates.lowHealth(8));
        }
        assertEquals(20, safety.damageTaken());
    }

    @Test
    void aHealthDropAboveTheThresholdDoesNotLogOut() {
        HighwayHealthUpdates updates = new HighwayHealthUpdates(new HighwayTravelSafety());
        updates.reset(20);

        updates.accept(8.5);
        assertFalse(updates.lowHealth(8));
        updates.accept(8);
        assertTrue(updates.lowHealth(8));
    }

    @Test
    void preservesQueuedTripDamageWhenAConnectionStarts() {
        HighwayTravelSafety safety = new HighwayTravelSafety();
        HighwayHealthUpdates updates = new HighwayHealthUpdates(safety);
        updates.reset(20);

        updates.accept(16);
        updates.connectionStarted();
        updates.accept(10);
        updates.accept(6);

        assertTrue(updates.lowHealth(8));
        assertEquals(8, safety.damageTaken());
    }

    @Test
    void lowHealthOnAReconnectionStillTriggersTheThreshold() {
        HighwayHealthUpdates updates = new HighwayHealthUpdates(new HighwayTravelSafety());
        updates.reset(20);
        updates.accept(17);
        assertFalse(updates.lowHealth(8));

        updates.connectionStarted();
        updates.accept(5);
        assertTrue(updates.lowHealth(8));
    }

    @Test
    void marksOnlyTheFirstAuthoritativeSampleAsTheConnectionBaseline() {
        HighwayHealthUpdates updates = new HighwayHealthUpdates(new HighwayTravelSafety());
        updates.reset(20);

        updates.connectionStarted();
        updates.accept(10);
        updates.accept(2);

        assertTrue(updates.lowHealth(8));
    }

    @Test
    void repeatedPollingDoesNotCountOldDamageAgain() {
        HighwayHealthUpdates updates = new HighwayHealthUpdates(new HighwayTravelSafety());
        updates.reset(20);
        updates.accept(16);

        assertFalse(updates.lowHealth(8));
        assertFalse(updates.lowHealth(8));
        assertFalse(updates.lowHealth(8));

        updates.accept(15);
        assertFalse(updates.lowHealth(8));
        updates.accept(8);
        assertTrue(updates.lowHealth(8));
    }

    @Test
    void rechecksCurrentHealthWhenTheThresholdChangesWithoutNewSamples() {
        HighwayHealthUpdates updates = new HighwayHealthUpdates(new HighwayTravelSafety());
        updates.reset(20);
        updates.accept(6);

        assertFalse(updates.lowHealth(0));
        assertTrue(updates.lowHealth(8));
    }

    @Test
    void resettingDiscardsEndedTripSamplesAndThePendingConnectionBaseline() {
        HighwayHealthUpdates updates = new HighwayHealthUpdates(new HighwayTravelSafety());
        updates.reset(20);
        updates.accept(1);
        updates.connectionStarted();

        updates.reset(20);

        assertFalse(updates.lowHealth(8));
        updates.accept(8);
        assertTrue(updates.lowHealth(8));
    }

    @Test
    void clearingDiscardsQueuedSamplesAndThePendingConnectionBaseline() {
        HighwayHealthUpdates updates = new HighwayHealthUpdates(new HighwayTravelSafety());
        updates.reset(20);
        updates.accept(1);
        updates.connectionStarted();

        updates.clear();

        assertFalse(updates.lowHealth(8));
        updates.accept(8);
        assertTrue(updates.lowHealth(8));
    }

    @Test
    void resettingClearsLowHealthFromTheEndedTrip() {
        HighwayHealthUpdates updates = new HighwayHealthUpdates(new HighwayTravelSafety());
        updates.reset(20);
        updates.accept(8);
        assertTrue(updates.lowHealth(8));

        updates.reset(20);

        assertFalse(updates.lowHealth(8));
        updates.accept(16);
        assertFalse(updates.lowHealth(8));
    }

    @Test
    void aHealthyReconnectDoesNotReuseOldLowHealthForLogout() {
        HighwayTravelSafety safety = new HighwayTravelSafety();
        HighwayHealthUpdates updates = new HighwayHealthUpdates(safety);
        updates.reset(20);
        updates.accept(4);
        updates.connectionStarted();
        updates.accept(20);

        assertFalse(updates.lowHealth(8));
        assertEquals(16, safety.damageTaken());
    }

    @Test
    void disabledThresholdStillRetainsDamageTelemetry() {
        HighwayTravelSafety safety = new HighwayTravelSafety();
        HighwayHealthUpdates updates = new HighwayHealthUpdates(safety);
        updates.reset(20);
        updates.accept(2);

        assertFalse(updates.lowHealth(0));
        assertFalse(updates.lowHealth(-1));
        assertEquals(18, safety.damageTaken());
    }

    @Test
    void enablingWhileAlreadyLowDoesNotRequireANewPacket() {
        HighwayHealthUpdates updates = new HighwayHealthUpdates(new HighwayTravelSafety());
        updates.reset(7);

        assertTrue(updates.lowHealth(8));
    }

    @Test
    void invalidThresholdCannotConsumeQueuedHealthSamples() {
        HighwayHealthUpdates updates = new HighwayHealthUpdates(new HighwayTravelSafety());
        updates.reset(20);
        updates.accept(6);

        assertThrows(IllegalArgumentException.class, () -> updates.lowHealth(Double.NaN));
        assertThrows(IllegalArgumentException.class, () -> updates.lowHealth(Double.POSITIVE_INFINITY));
        assertTrue(updates.lowHealth(8));
    }
}
