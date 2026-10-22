package dev.tntcoating.modules.autowasp;

import dev.tntcoating.modules.autowasp.AutoWaspTakeoff.Step;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class AutoWaspTakeoffTest {
    // Vertical speed over a vanilla jump: up at 0.42 less gravity each tick, then down.
    private static final double[] JUMP = {0.3332, 0.2481, 0.1645, 0.0819, 0.0011, -0.0784, -0.1552};

    @Test void jumpsThenOpensNearTheTop() {
        AutoWaspTakeoff takeoff = new AutoWaspTakeoff();
        assertEquals(Step.JUMP, takeoff.next(0, true, false, 0, false));
        assertEquals(Step.WAIT, takeoff.next(1, false, false, JUMP[0], false));
        assertEquals(Step.WAIT, takeoff.next(2, false, false, JUMP[1], false));
        assertEquals(Step.WAIT, takeoff.next(3, false, false, JUMP[2], false));
        assertEquals(Step.OPEN, takeoff.next(4, false, false, JUMP[3], false));
        assertEquals(Step.WAIT, takeoff.next(5, false, true, JUMP[4], false));
    }

    @Test void aRefusedOpenIsRetriedOnlyAfterThePause() {
        AutoWaspTakeoff takeoff = new AutoWaspTakeoff();
        assertEquals(Step.OPEN, takeoff.next(100, false, false, -.3, false));
        // The client glides until the server's refusal arrives a round trip later.
        for (long tick = 101; tick < 104; tick++) assertEquals(Step.WAIT, takeoff.next(tick, false, true, -.3, false));
        for (long tick = 104; tick < 100 + AutoWaspTakeoff.REOPEN_TICKS; tick++) {
            assertEquals(Step.WAIT, takeoff.next(tick, false, false, -.5, false), "tick " + tick);
        }
        assertEquals(Step.OPEN, takeoff.next(100 + AutoWaspTakeoff.REOPEN_TICKS, false, false, -.5, false));
    }

    @Test void aGlideEndingLongAfterItsOpenReopensAtOnce() {
        AutoWaspTakeoff takeoff = new AutoWaspTakeoff();
        assertEquals(Step.OPEN, takeoff.next(0, false, false, -.3, false));
        assertEquals(Step.OPEN, takeoff.next(500, false, false, -.6, false));
    }

    @Test void liquidRetriesSoonerAndDoesNotWaitForTheTopOfAJump() {
        AutoWaspTakeoff takeoff = new AutoWaspTakeoff();
        assertEquals(Step.OPEN, takeoff.next(0, false, false, .2, true));
        assertEquals(Step.WAIT, takeoff.next(1, false, false, .2, true));
        assertEquals(Step.OPEN, takeoff.next(AutoWaspTakeoff.LIQUID_REOPEN_TICKS, false, false, .2, true));
    }

    @Test void groundAlwaysJumpsAndResetAllowsAnImmediateOpen() {
        AutoWaspTakeoff takeoff = new AutoWaspTakeoff();
        assertEquals(Step.OPEN, takeoff.next(10, false, false, -.2, false));
        assertEquals(Step.JUMP, takeoff.next(11, true, false, 0, false));
        takeoff.reset();
        assertEquals(Step.OPEN, takeoff.next(12, false, false, -.2, false));
    }
}
