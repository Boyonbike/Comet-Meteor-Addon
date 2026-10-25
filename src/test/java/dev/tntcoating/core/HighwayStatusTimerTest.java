package dev.tntcoating.core;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class HighwayStatusTimerTest {
    private static final long THIRTY_MINUTES = 1_800_000_000_000L;

    @Test
    void doesNotReportStatusBeforeATripStarts() {
        HighwayStatusTimer timer = new HighwayStatusTimer();

        assertFalse(timer.due(THIRTY_MINUTES));
    }

    @Test
    void waitsThirtyMinutesBeforeTheFirstStatus() {
        HighwayStatusTimer timer = new HighwayStatusTimer();
        timer.reset(100);

        assertFalse(timer.due(100));
        assertFalse(timer.due(THIRTY_MINUTES + 99));
        assertTrue(timer.due(THIRTY_MINUTES + 100));
    }

    @Test
    void waitsAnotherThirtyMinutesBetweenStatuses() {
        HighwayStatusTimer timer = new HighwayStatusTimer();
        timer.reset(0);
        assertTrue(timer.due(THIRTY_MINUTES));

        assertFalse(timer.due(THIRTY_MINUTES));
        assertFalse(timer.due(2 * THIRTY_MINUTES - 1));
        assertTrue(timer.due(2 * THIRTY_MINUTES));
    }

    @Test
    void sendsOneStatusAfterALongGapWithoutABurst() {
        HighwayStatusTimer timer = new HighwayStatusTimer();
        timer.reset(0);
        long afterLongGap = 4 * THIRTY_MINUTES + 100;

        assertTrue(timer.due(afterLongGap));
        assertFalse(timer.due(afterLongGap));
        assertFalse(timer.due(afterLongGap + THIRTY_MINUTES - 1));
        assertTrue(timer.due(afterLongGap + THIRTY_MINUTES));
    }

    @Test
    void startsANewCadenceWhenTheTripResets() {
        HighwayStatusTimer timer = new HighwayStatusTimer();
        timer.reset(0);
        timer.reset(THIRTY_MINUTES - 1);

        assertFalse(timer.due(THIRTY_MINUTES));
        assertTrue(timer.due(2 * THIRTY_MINUTES - 1));
    }

    @Test
    void clearingTheTripDisablesStatusUntilTheNextReset() {
        HighwayStatusTimer timer = new HighwayStatusTimer();
        timer.reset(0);
        timer.clear();

        assertFalse(timer.due(THIRTY_MINUTES));
        assertFalse(timer.due(10 * THIRTY_MINUTES));

        timer.reset(10 * THIRTY_MINUTES);
        assertFalse(timer.due(10 * THIRTY_MINUTES));
        assertTrue(timer.due(11 * THIRTY_MINUTES));
    }

    @Test
    void supportsNegativeNanoTimeOrigins() {
        HighwayStatusTimer timer = new HighwayStatusTimer();
        timer.reset(-THIRTY_MINUTES);

        assertFalse(timer.due(-1));
        assertTrue(timer.due(0));
    }

    @Test
    void preservesTheIntervalWhenNanoTimeWraps() {
        HighwayStatusTimer timer = new HighwayStatusTimer();
        long start = Long.MAX_VALUE - THIRTY_MINUTES / 2;
        timer.reset(start);

        assertFalse(timer.due(start + THIRTY_MINUTES - 1));
        assertTrue(timer.due(start + THIRTY_MINUTES));
    }

    @Test
    void configurableCadenceWaitsForTheChosenInterval() {
        for (long interval : new long[] {60_000_000_000L, 86_400_000_000_000L}) {
            HighwayStatusTimer timer = new HighwayStatusTimer();
            timer.reset(100);

            assertFalse(timer.due(interval + 99, interval));
            assertTrue(timer.due(interval + 100, interval));
            assertFalse(timer.due(2 * interval + 99, interval));
            assertTrue(timer.due(2 * interval + 100, interval));
        }
    }

    @Test
    void configurableCadenceDoesNotBurstAfterDelayedPolling() {
        HighwayStatusTimer timer = new HighwayStatusTimer();
        timer.reset(0);

        assertTrue(timer.due(10_000, 100));
        assertFalse(timer.due(10_000, 100));
        assertFalse(timer.due(10_099, 100));
        assertTrue(timer.due(10_100, 100));
    }

    @Test
    void resettingWhileChangingTheIntervalWaitsForTheNewFullPeriod() {
        HighwayStatusTimer timer = new HighwayStatusTimer();
        timer.reset(0);
        assertFalse(timer.due(99, 100));
        timer.reset(99);

        assertFalse(timer.due(108, 10));
        assertTrue(timer.due(109, 10));
    }

    @Test
    void configurableCadenceRemainsInactiveUntilReset() {
        HighwayStatusTimer timer = new HighwayStatusTimer();
        assertFalse(timer.due(100, 10));
        timer.reset(100);
        timer.clear();

        assertFalse(timer.due(1_000, 10));
    }

    @Test
    void configurableCadenceSupportsNegativeOriginsAndClockWraparound() {
        for (long start : new long[] {-100, Long.MAX_VALUE - 5}) {
            HighwayStatusTimer timer = new HighwayStatusTimer();
            timer.reset(start);

            assertFalse(timer.due(start + 9, 10));
            assertTrue(timer.due(start + 10, 10));
        }
    }

    @Test
    void rejectsNonPositiveIntervalsWithoutConsumingTheTimer() {
        HighwayStatusTimer timer = new HighwayStatusTimer();
        timer.reset(0);

        assertThrows(IllegalArgumentException.class, () -> timer.due(100, 0));
        assertThrows(IllegalArgumentException.class, () -> timer.due(100, -1));
        assertTrue(timer.due(100, 100));
    }
}
