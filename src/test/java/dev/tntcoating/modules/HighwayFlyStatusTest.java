package dev.tntcoating.modules;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class HighwayFlyStatusTest {
    @Test
    void everyFlightStateHasAVisiblePhaseAndAction() {
        for (HighwayFly.State state : HighwayFly.State.values()) {
            assertFalse(HighwayFly.stateLabel(state).isBlank(), state.name());
            assertFalse(HighwayFly.actionForState(state).isBlank(), state.name());
        }
    }

    @Test
    void actionsNameRoutingManeuveringAndWaiting() {
        assertEquals("Routing around an obstacle", HighwayFly.actionForState(HighwayFly.State.DETOUR));
        assertTrue(HighwayFly.actionForState(HighwayFly.State.PATHING).contains("Maneuvering"));
        assertTrue(HighwayFly.actionForState(HighwayFly.State.LANDING).contains("Maneuvering"));
        assertTrue(HighwayFly.actionForState(HighwayFly.State.WAIT_CHUNKS).contains("Waiting"));
    }

    @Test
    void broadPhasesGroupAirborneStatesAsFlying() {
        assertEquals("Flying", HighwayFly.stateLabel(HighwayFly.State.CRUISE));
        assertEquals("Flying", HighwayFly.stateLabel(HighwayFly.State.DETOUR));
        assertEquals("Flying", HighwayFly.stateLabel(HighwayFly.State.PATHING));
        assertEquals("Waiting", HighwayFly.stateLabel(HighwayFly.State.WAIT_CHUNKS));
    }
}
