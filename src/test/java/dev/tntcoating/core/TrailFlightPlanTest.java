package dev.tntcoating.core;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class TrailFlightPlanTest {
    @Test void sendsAheadAndThrottlesTurns() {
        var plan = new TrailFlightPlan();
        assertEquals(new TrailFlightPlan.Goal(500, 80, 0), plan.next(0, 0, 0, 80, -90));
        assertNull(plan.next(2_999, 0, 0, 80, 0));
        assertEquals(new TrailFlightPlan.Goal(0, 80, 500), plan.next(3_000, 0, 0, 80, 0));
    }

    @Test void smallTurnsDoNotRestartPathButStraightFlightExtendsBeforeEndpoint() {
        var plan = new TrailFlightPlan();
        plan.next(0, 0, 0, 80, -90);
        assertNull(plan.next(3_000, 10, 0, 80, -85));
        assertEquals(new TrailFlightPlan.Goal(810, 80, 0), plan.next(4_000, 310, 0, 80, -90));
    }

    @Test void comparesHeadingAcrossWrapAndResetStartsFresh() {
        var plan = new TrailFlightPlan();
        plan.next(0, 0, 0, 80, 179);
        assertNull(plan.next(3_000, 0, 0, 80, -179));
        plan.reset();
        assertNotNull(plan.next(3_001, 0, 0, 80, 179));
    }
}
