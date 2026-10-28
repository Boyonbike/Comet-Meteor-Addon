package dev.tntcoating.core;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import static dev.tntcoating.core.LobbyRecoveryDecisions.Arrival.*;

class LobbyRecoveryDecisionsTest {
    @Test void enabledStandaloneRecoveryStartsOnFreshJoinWithoutSavedFlight() {
        assertTrue(LobbyRecoveryDecisions.standaloneAllowed(true, false, false));
        assertTrue(LobbyRecoveryDecisions.standaloneAllowed(true, false, true));
    }
    @Test void disabledOrOnlyManagedLobbyDoesNotStartAnUnrelatedTrip() {
        assertFalse(LobbyRecoveryDecisions.standaloneAllowed(false, false, true));
        assertFalse(LobbyRecoveryDecisions.standaloneAllowed(false, true, false));
    }
    @Test void nativeManagedLobbyCanRecoverFromAKnownLobbySpawnWithoutInventingAnOrdinaryWorldRoute() {
        assertTrue(LobbyRecoveryDecisions.standaloneAllowed(false, true, true));
        assertFalse(LobbyRecoveryDecisions.standaloneAllowed(false, true, false));
    }
    @Test void nativeBoatAwaitingMountNeverInheritsStandaloneNetherTargetInOrdinaryWorld() {
        assertFalse(LobbyRecoveryDecisions.standaloneAllowed(true, true, false));
        assertTrue(LobbyRecoveryDecisions.standaloneAllowed(true, true, true));
    }
    @Test void standaloneCompletionIgnoresFlightResumeToggleAndDoesNotRestart() {
        assertEquals(COMPLETE_STANDALONE, LobbyRecoveryDecisions.arrived(true, false));
        assertEquals(COMPLETE_STANDALONE, LobbyRecoveryDecisions.arrived(true, true));
    }
    @Test void savedTripHonoursResumeToggle() {
        assertEquals(STOP_TRIP, LobbyRecoveryDecisions.arrived(false, false));
        assertEquals(RESUME_TRIP, LobbyRecoveryDecisions.arrived(false, true));
    }
    @Test void boatTripCanApproachLobbyPortalOnFootAfterServerDismount() {
        assertEquals(LobbyRecoveryDecisions.Movement.FOOT, LobbyRecoveryDecisions.movement(true, false, false));
        assertEquals(LobbyRecoveryDecisions.Movement.BOAT, LobbyRecoveryDecisions.movement(true, true, true));
        assertEquals(LobbyRecoveryDecisions.Movement.WAIT, LobbyRecoveryDecisions.movement(true, true, false));
    }
    @Test void standaloneAndHighwayRecoveryWalkWithoutRequiringABoat() {
        assertEquals(LobbyRecoveryDecisions.Movement.FOOT, LobbyRecoveryDecisions.movement(false, false, false));
        assertEquals(LobbyRecoveryDecisions.Movement.WAIT, LobbyRecoveryDecisions.movement(false, true, false));
    }
}
