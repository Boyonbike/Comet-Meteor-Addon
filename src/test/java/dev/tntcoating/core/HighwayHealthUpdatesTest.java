package dev.tntcoating.core;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class HighwayHealthUpdatesTest {
    @Test
    void retainsDamageReceivedAfterTheInitialConnectionSampleBeforeTheNextTick() {
        HighwayHealthUpdates updates = new HighwayHealthUpdates(new HighwayTravelSafety());
        updates.reset(20);

        updates.connectionStarted();
        updates.accept(20);
        updates.accept(12);

        assertTrue(updates.exceeded(8));
    }

    @Test
    void countsDamageEvenWhenHealingArrivesInTheSameBurst() {
        HighwayHealthUpdates updates = new HighwayHealthUpdates(new HighwayTravelSafety());
        updates.reset(20);

        updates.accept(12);
        updates.accept(20);

        assertTrue(updates.exceeded(8));
    }

    @Test
    void preservesQueuedTripDamageWhenAConnectionStarts() {
        HighwayHealthUpdates updates = new HighwayHealthUpdates(new HighwayTravelSafety());
        updates.reset(20);

        updates.accept(16);
        updates.connectionStarted();
        updates.accept(10);
        updates.accept(6);

        assertTrue(updates.exceeded(8));
    }

    @Test
    void rebasesLowerInitialHealthWithoutErasingEarlierTripDamage() {
        HighwayHealthUpdates updates = new HighwayHealthUpdates(new HighwayTravelSafety());
        updates.reset(20);
        updates.accept(17);
        assertFalse(updates.exceeded(8));

        updates.connectionStarted();
        updates.accept(10);
        assertFalse(updates.exceeded(8));

        updates.accept(5);
        assertTrue(updates.exceeded(8));
    }

    @Test
    void marksOnlyTheFirstAuthoritativeSampleAsTheConnectionBaseline() {
        HighwayHealthUpdates updates = new HighwayHealthUpdates(new HighwayTravelSafety());
        updates.reset(20);

        updates.connectionStarted();
        updates.accept(10);
        updates.accept(2);

        assertTrue(updates.exceeded(8));
    }

    @Test
    void repeatedPollingDoesNotCountOldDamageAgain() {
        HighwayHealthUpdates updates = new HighwayHealthUpdates(new HighwayTravelSafety());
        updates.reset(20);
        updates.accept(16);

        assertFalse(updates.exceeded(8));
        assertFalse(updates.exceeded(8));
        assertFalse(updates.exceeded(8));

        updates.accept(15);
        assertFalse(updates.exceeded(8));
        updates.accept(12);
        assertTrue(updates.exceeded(8));
    }

    @Test
    void rechecksAccumulatedDamageWhenTheLimitChangesWithoutNewSamples() {
        HighwayHealthUpdates updates = new HighwayHealthUpdates(new HighwayTravelSafety());
        updates.reset(20);
        updates.accept(12);

        assertFalse(updates.exceeded(0));
        assertTrue(updates.exceeded(8));
    }

    @Test
    void resettingDiscardsEndedTripSamplesAndThePendingConnectionBaseline() {
        HighwayHealthUpdates updates = new HighwayHealthUpdates(new HighwayTravelSafety());
        updates.reset(20);
        updates.accept(1);
        updates.connectionStarted();

        updates.reset(20);

        assertFalse(updates.exceeded(8));
        updates.accept(12);
        assertTrue(updates.exceeded(8));
    }

    @Test
    void clearingDiscardsQueuedSamplesAndThePendingConnectionBaseline() {
        HighwayHealthUpdates updates = new HighwayHealthUpdates(new HighwayTravelSafety());
        updates.reset(20);
        updates.accept(1);
        updates.connectionStarted();

        updates.clear();

        assertFalse(updates.exceeded(8));
        updates.accept(12);
        assertTrue(updates.exceeded(8));
    }

    @Test
    void resettingClearsDamageAlreadyCountedOnTheEndedTrip() {
        HighwayHealthUpdates updates = new HighwayHealthUpdates(new HighwayTravelSafety());
        updates.reset(20);
        updates.accept(12);
        assertTrue(updates.exceeded(8));

        updates.reset(20);

        assertFalse(updates.exceeded(8));
        updates.accept(16);
        assertFalse(updates.exceeded(8));
    }
}
