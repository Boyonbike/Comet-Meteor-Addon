package dev.tntcoating.core;
import dev.tntcoating.core.ElytraTakeoff.Step;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
class ElytraTakeoffTest {
    // Vertical speed over a vanilla jump: up at 0.42 less gravity each tick, then down.
    private static final double[] JUMP = {0.3332, 0.2481, 0.1645, 0.0819, 0.0011, -0.0784, -0.1552};

    @Test void jumpsThenOpensNearTheTop() {
        ElytraTakeoff t = new ElytraTakeoff();
        assertEquals(Step.JUMP, t.next(true, false, 0, true));
        assertEquals(Step.WAIT, t.next(false, false, JUMP[0], true));
        assertEquals(Step.WAIT, t.next(false, false, JUMP[1], true));
        assertEquals(Step.WAIT, t.next(false, false, JUMP[2], true));
        assertEquals(Step.OPEN, t.next(false, false, JUMP[3], true));
        assertEquals(Step.WAIT, t.next(false, true, JUMP[4], true));
    }
    @Test void opensOncePerJump() {
        ElytraTakeoff t = new ElytraTakeoff();
        t.next(true, false, 0, true);
        assertEquals(Step.OPEN, t.next(false, false, JUMP[4], true));
        assertEquals(Step.WAIT, t.next(false, false, JUMP[5], true));
        assertEquals(Step.WAIT, t.next(false, false, JUMP[6], true));
    }
    @Test void opensWhenAlreadyFalling() {
        ElytraTakeoff t = new ElytraTakeoff();
        assertEquals(Step.OPEN, t.next(false, false, -0.5, true));
    }
    @Test void waitsOnTheGroundBeforeJumpingAgain() {
        ElytraTakeoff t = new ElytraTakeoff();
        t.next(true, false, 0, true);
        t.next(false, false, JUMP[4], true);
        for (int i = 1; i < ElytraTakeoff.RETRY_TICKS; i++) assertEquals(Step.WAIT, t.next(true, false, 0, true));
        assertEquals(Step.JUMP, t.next(true, false, 0, true));
    }
    @Test void landingAfterAFlightIsNotATakeoff() {
        ElytraTakeoff t = new ElytraTakeoff();
        t.reset(true);
        assertEquals(Step.WAIT, t.next(true, false, 0, true));
    }
    @Test void givesUpAfterMaxJumps() {
        ElytraTakeoff t = new ElytraTakeoff();
        for (int jump = 0; jump < ElytraTakeoff.MAX_JUMPS; jump++) {
            assertEquals(Step.JUMP, t.next(true, false, 0, true));
            t.next(false, false, JUMP[0], true);
            for (int i = 1; i < ElytraTakeoff.RETRY_TICKS; i++) t.next(true, false, 0, true);
        }
        assertEquals(Step.GAVE_UP, t.next(true, false, 0, true));
    }
    @Test void coveredDoesNotJumpOrCountAJump() {
        ElytraTakeoff t = new ElytraTakeoff();
        for (int tick = 0; tick <= ElytraTakeoff.MAX_JUMPS; tick++) assertEquals(Step.COVERED, t.next(true, false, 0, false));
        assertEquals(Step.JUMP, t.next(true, false, 0, true));
    }
}
