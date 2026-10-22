package dev.tntcoating.modules.autowasp;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class AutoWaspSwapDelayTest {
    @Test void pursuitWaitsAfterChestplateChangesToElytra() {
        AutoWaspSwapDelay delay = new AutoWaspSwapDelay();
        delay.observe(10, false, false);
        delay.observe(11, true, true);
        for (int tick = 11; tick < 11 + AutoWaspSwapDelay.SETTLE_TICKS; tick++) {
            delay.observe(tick, true, true);
            assertFalse(delay.ready(), "pursuit must wait at tick " + tick);
        }
        delay.observe(11 + AutoWaspSwapDelay.SETTLE_TICKS, true, true);
        assertTrue(delay.ready());
    }

    @Test void waitingForTheGlideDoesNotSpendTheDelay() {
        AutoWaspSwapDelay delay = new AutoWaspSwapDelay();
        delay.observe(1, false, false);
        delay.observe(2, true, false);
        delay.observe(100, true, false);
        assertFalse(delay.ready());
        delay.observe(101, true, true);
        delay.observe(105, true, true);
        assertFalse(delay.ready());
        delay.observe(106, true, true);
        assertTrue(delay.ready());
    }

    @Test void anExplicitSwapWorksWithoutAnEarlierEquipmentSampleAndRepeatsDoNotShortenIt() {
        AutoWaspSwapDelay delay = new AutoWaspSwapDelay();
        delay.swapped();
        delay.observe(20, true, true);
        for (int move = 0; move < 20; move++) delay.observe(20, true, true);
        assertFalse(delay.ready());
        delay.observe(25, true, true);
        assertTrue(delay.ready());
    }

    @Test void losingTheGlideWhileSettlingRequiresAStableNewGlide() {
        AutoWaspSwapDelay delay = new AutoWaspSwapDelay();
        delay.swapped();
        delay.observe(10, true, true);
        delay.observe(14, true, false);
        delay.observe(15, true, true);
        delay.observe(19, true, true);
        assertFalse(delay.ready());
        delay.observe(20, true, true);
        assertTrue(delay.ready());
    }

    @Test void existingFlightIsTakenOverAtOnce() {
        AutoWaspSwapDelay delay = new AutoWaspSwapDelay();
        delay.observe(50, true, true);
        assertTrue(delay.ready());
    }

    @Test void removingTheElytraCancelsAndTheNextEquipStartsItsOwnDelay() {
        AutoWaspSwapDelay delay = new AutoWaspSwapDelay();
        delay.observe(1, true, true);
        delay.swapped();
        delay.observe(2, true, true);
        delay.observe(3, false, false);
        assertTrue(delay.ready());
        delay.observe(4, true, true);
        assertFalse(delay.ready());
    }

    @Test void resetDoesNotCarryAnOldSwapIntoAnotherWorld() {
        AutoWaspSwapDelay delay = new AutoWaspSwapDelay();
        delay.swapped();
        delay.observe(1, true, true);
        delay.reset();
        delay.observe(2, true, true);
        assertTrue(delay.ready());
    }
}
