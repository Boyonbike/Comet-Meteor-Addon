package dev.tntcoating.core;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class ElytraBoostDelayTest {
    @Test void heldSprintWaitsAfterChestplateChangesToElytra() {
        ElytraBoostDelay delay = new ElytraBoostDelay();
        delay.observe(10, false, false);
        delay.observe(11, true, true);
        for (int tick = 11; tick < 11 + ElytraBoostDelay.SETTLE_TICKS; tick++) {
            delay.observe(tick, true, true);
            assertFalse(delay.ready(), "held Sprint must wait at tick " + tick);
        }
        delay.observe(11 + ElytraBoostDelay.SETTLE_TICKS, true, true);
        assertTrue(delay.ready());
    }

    @Test void waitingForTheGlideDoesNotSpendTheDelay() {
        ElytraBoostDelay delay = new ElytraBoostDelay();
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

    @Test void anExplicitSameTickSwapWorksWithoutAnEarlierEquipmentSample() {
        ElytraBoostDelay delay = new ElytraBoostDelay();
        delay.swapped();
        delay.observe(20, true, true);
        assertFalse(delay.ready());
        for (int move = 0; move < 20; move++) delay.observe(20, true, true);
        assertFalse(delay.ready(), "tick and movement callbacks cannot consume the delay twice");
        delay.observe(25, true, true);
        assertTrue(delay.ready());
    }

    @Test void losingTheGlideWhileSettlingRequiresAStableNewGlide() {
        ElytraBoostDelay delay = new ElytraBoostDelay();
        delay.swapped();
        delay.observe(10, true, true);
        delay.observe(14, true, false);
        delay.observe(15, true, true);
        delay.observe(19, true, true);
        assertFalse(delay.ready());
        delay.observe(20, true, true);
        assertTrue(delay.ready());
    }

    @Test void existingFlightAndOrdinarySprintDoNotGetADelay() {
        ElytraBoostDelay delay = new ElytraBoostDelay();
        delay.observe(50, true, true);
        assertTrue(delay.ready());
        delay.observe(51, true, true);
        assertTrue(delay.ready());
    }

    @Test void anotherSwapRestartsTheDelayAndRemovingElytraCancelsIt() {
        ElytraBoostDelay delay = new ElytraBoostDelay();
        delay.observe(1, true, true);
        delay.swapped();
        delay.observe(2, true, true);
        delay.swapped();
        delay.observe(6, true, true);
        delay.observe(7, true, true);
        assertFalse(delay.ready());
        delay.observe(8, false, false);
        assertTrue(delay.ready());
        delay.observe(9, true, true);
        assertFalse(delay.ready(), "the next equip must start its own delay");
    }

    @Test void resetDoesNotCarryAnOldSwapIntoAnotherWorld() {
        ElytraBoostDelay delay = new ElytraBoostDelay();
        delay.swapped();
        delay.observe(1, true, true);
        delay.reset();
        delay.observe(2, true, true);
        assertTrue(delay.ready());
    }
}
