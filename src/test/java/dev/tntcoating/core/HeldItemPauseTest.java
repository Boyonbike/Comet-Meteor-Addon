package dev.tntcoating.core;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class HeldItemPauseTest {
    private static final long SECOND = 1_000_000_000L;
    private static final long TIMEOUT = 5 * SECOND;
    private final HeldItemPause pause = new HeldItemPause();

    @Test void resumesAtExactlyFiveSecondsAndStaysResumedWhileHeld() {
        assertTrue(pause.paused(true, 0, TIMEOUT));
        assertTrue(pause.paused(true, TIMEOUT - 1, TIMEOUT));
        assertFalse(pause.paused(true, TIMEOUT, TIMEOUT));
        assertFalse(pause.paused(true, 6 * SECOND, TIMEOUT));
        assertFalse(pause.paused(true, 60 * SECOND, TIMEOUT));
    }

    @Test void repeatedChecksInOneTickDoNotRestartOrRearmTheTimer() {
        for (int check = 0; check < 3; check++) {
            assertTrue(pause.paused(true, SECOND, TIMEOUT));
        }
        assertTrue(pause.paused(true, 4 * SECOND, TIMEOUT));
        for (int check = 0; check < 3; check++) {
            assertFalse(pause.paused(true, 6 * SECOND, TIMEOUT));
        }
    }

    @Test void repeatedUsesExtendThePauseFromTheLatestUse() {
        assertTrue(pause.paused(true, 0, TIMEOUT));
        pause.itemUsed(true, true, 4 * SECOND);
        assertTrue(pause.paused(true, 5 * SECOND, TIMEOUT));
        pause.itemUsed(true, true, 8 * SECOND);
        assertTrue(pause.paused(true, 9 * SECOND, TIMEOUT));
        assertTrue(pause.paused(true, 13 * SECOND - 1, TIMEOUT));
        assertFalse(pause.paused(true, 13 * SECOND, TIMEOUT));
        assertFalse(pause.paused(true, 60 * SECOND, TIMEOUT));
    }

    @Test void usingAnItemRearmsAnExpiredContinuousHold() {
        assertTrue(pause.paused(true, 0, TIMEOUT));
        assertFalse(pause.paused(true, TIMEOUT, TIMEOUT));
        pause.itemUsed(true, true, 8 * SECOND);
        assertTrue(pause.paused(true, 8 * SECOND, TIMEOUT));
        assertTrue(pause.paused(true, 13 * SECOND - 1, TIMEOUT));
        assertFalse(pause.paused(true, 13 * SECOND, TIMEOUT));
    }

    @Test void disabledRestartPreservesTheDeadlineAndDoesNotRearmExpiry() {
        assertTrue(pause.paused(true, 0, TIMEOUT));
        pause.itemUsed(false, true, 4 * SECOND);
        assertTrue(pause.paused(true, TIMEOUT - 1, TIMEOUT));
        assertFalse(pause.paused(true, TIMEOUT, TIMEOUT));
        pause.itemUsed(false, true, 6 * SECOND);
        assertFalse(pause.paused(true, 6 * SECOND, TIMEOUT));
    }

    @Test void unrelatedItemUsePreservesTheDeadlineAndDoesNotRearmExpiry() {
        assertTrue(pause.paused(true, 0, TIMEOUT));
        pause.itemUsed(true, false, 4 * SECOND);
        assertTrue(pause.paused(true, TIMEOUT - 1, TIMEOUT));
        assertFalse(pause.paused(true, TIMEOUT, TIMEOUT));
        pause.itemUsed(true, false, 6 * SECOND);
        assertFalse(pause.paused(true, 6 * SECOND, TIMEOUT));
    }

    @Test void useBeforeTheFirstHeldCheckStartsTheTimerAtTheUseTime() {
        pause.itemUsed(true, true, SECOND);
        assertTrue(pause.paused(true, 4 * SECOND, TIMEOUT));
        assertTrue(pause.paused(true, 6 * SECOND - 1, TIMEOUT));
        assertFalse(pause.paused(true, 6 * SECOND, TIMEOUT));
    }

    @Test void releaseAndResetClearTheTimerStartedByUse() {
        pause.itemUsed(true, true, 0);
        assertFalse(pause.paused(false, 4 * SECOND, TIMEOUT));
        assertTrue(pause.paused(true, 4 * SECOND, TIMEOUT));
        assertTrue(pause.paused(true, 5 * SECOND, TIMEOUT));
        assertFalse(pause.paused(true, 9 * SECOND, TIMEOUT));
        pause.itemUsed(true, true, 10 * SECOND);
        pause.reset();
        assertTrue(pause.paused(true, 14 * SECOND, TIMEOUT));
        assertTrue(pause.paused(true, 15 * SECOND, TIMEOUT));
        assertFalse(pause.paused(true, 19 * SECOND, TIMEOUT));
    }

    @Test void zeroTimeoutRemainsIndefiniteAfterUse() {
        assertTrue(pause.paused(true, 0, 0));
        pause.itemUsed(true, true, 4 * SECOND);
        assertTrue(pause.paused(true, 60 * SECOND, 0));
        assertFalse(pause.paused(false, 60 * SECOND, 0));
    }

    @Test void releasingAndReselectingStartsAFreshPauseEvenAtTheSameTime() {
        assertTrue(pause.paused(true, 0, TIMEOUT));
        assertFalse(pause.paused(true, TIMEOUT, TIMEOUT));
        assertFalse(pause.paused(false, TIMEOUT, TIMEOUT));
        assertTrue(pause.paused(true, TIMEOUT, TIMEOUT));
        assertTrue(pause.paused(true, 2 * TIMEOUT - 1, TIMEOUT));
        assertFalse(pause.paused(true, 2 * TIMEOUT, TIMEOUT));
    }

    @Test void releaseBeforeExpiryImmediatelyClearsThePreviousHold() {
        assertTrue(pause.paused(true, 0, TIMEOUT));
        assertFalse(pause.paused(false, 4 * SECOND, TIMEOUT));
        assertTrue(pause.paused(true, 4 * SECOND, TIMEOUT));
        assertTrue(pause.paused(true, 5 * SECOND, TIMEOUT));
        assertFalse(pause.paused(true, 9 * SECOND, TIMEOUT));
    }

    @Test void zeroTimeoutKeepsPausingUntilTheItemIsReleased() {
        assertTrue(pause.paused(true, 0, 0));
        assertTrue(pause.paused(true, 60 * SECOND, 0));
        assertFalse(pause.paused(false, 60 * SECOND, 0));
        assertTrue(pause.paused(true, 60 * SECOND, TIMEOUT));
    }

    @Test void shorteningTimeoutUsesTheOriginalHoldStart() {
        assertTrue(pause.paused(true, SECOND, 10 * SECOND));
        assertTrue(pause.paused(true, 4 * SECOND, 10 * SECOND));
        assertFalse(pause.paused(true, 4 * SECOND, 2 * SECOND));
    }

    @Test void lengtheningTimeoutBeforeExpiryUsesTheOriginalHoldStart() {
        assertTrue(pause.paused(true, SECOND, TIMEOUT));
        assertTrue(pause.paused(true, 4 * SECOND, 10 * SECOND));
        assertTrue(pause.paused(true, 10 * SECOND, 10 * SECOND));
        assertFalse(pause.paused(true, 11 * SECOND, 10 * SECOND));
    }

    @Test void changingAnIndefinitePauseToATimeoutKeepsTheOriginalHoldStart() {
        assertTrue(pause.paused(true, SECOND, 0));
        assertFalse(pause.paused(true, 6 * SECOND, TIMEOUT));
    }

    @Test void settingChangesDoNotRearmAnExpiredContinuousHold() {
        assertTrue(pause.paused(true, 0, TIMEOUT));
        assertFalse(pause.paused(true, TIMEOUT, TIMEOUT));
        assertFalse(pause.paused(true, TIMEOUT, 10 * SECOND));
        assertFalse(pause.paused(true, TIMEOUT, 0));
    }

    @Test void resetClearsActiveAndExpiredHolds() {
        assertTrue(pause.paused(true, 0, TIMEOUT));
        pause.reset();
        assertTrue(pause.paused(true, 4 * SECOND, TIMEOUT));
        assertTrue(pause.paused(true, 5 * SECOND, TIMEOUT));
        assertFalse(pause.paused(true, 9 * SECOND, TIMEOUT));
        pause.reset();
        assertTrue(pause.paused(true, 9 * SECOND, TIMEOUT));
    }

    @Test void negativeNanoTimeValuesAndCrossingZeroAreValid() {
        assertTrue(pause.paused(true, -3 * SECOND, TIMEOUT));
        assertTrue(pause.paused(true, 2 * SECOND - 1, TIMEOUT));
        assertFalse(pause.paused(true, 2 * SECOND, TIMEOUT));
    }

    @Test void nanoTimeWraparoundPreservesElapsedDuration() {
        long start = Long.MAX_VALUE - 2 * SECOND;
        assertTrue(pause.paused(true, start, TIMEOUT));
        assertTrue(pause.paused(true, start + TIMEOUT - 1, TIMEOUT));
        assertFalse(pause.paused(true, start + TIMEOUT, TIMEOUT));
    }
}
