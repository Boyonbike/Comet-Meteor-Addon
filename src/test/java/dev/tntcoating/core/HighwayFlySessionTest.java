package dev.tntcoating.core;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class HighwayFlySessionTest {
    private static final String NETHER = "minecraft:the_nether";

    @Test void initialHealthReceivedBeforeTheFlightStartsStillProtectsTheTrip() {
        HighwayFlySession session = new HighwayFlySession();
        HighwayHealthUpdates updates = new HighwayHealthUpdates(new HighwayTravelSafety());
        updates.reset(20);
        updates.connectionStarted();

        if (session.healthTrackingAllowed("6b6t.org")) updates.accept(6);
        session.start(NETHER, "6b6t.org");

        assertTrue(updates.lowHealth(8));
    }

    @Test void unrelatedServerHealthDoesNotPoisonARecoveringTrip() {
        HighwayFlySession session = new HighwayFlySession();
        HighwayHealthUpdates updates = new HighwayHealthUpdates(new HighwayTravelSafety());
        updates.reset(20);
        session.recover(NETHER, "6b6t.org");

        if (session.healthTrackingAllowed("other.org")) updates.accept(2);
        assertFalse(updates.lowHealth(8));

        if (session.healthTrackingAllowed("6B6T.ORG")) updates.accept(6);
        assertTrue(updates.lowHealth(8));
    }

    @Test void interruptedFlightKeepsRecoveryButSuspendsHomeAndPortalProtection() {
        HighwayFlySession session = new HighwayFlySession();
        session.start(NETHER, "6b6t.org");
        assertTrue(session.setHomeAllowed(true, NETHER, "6b6t.org"));
        assertTrue(session.portalProtectionAllowed(true));
        assertTrue(session.disconnected(true));
        assertTrue(session.recovering());
        assertFalse(session.flying());
        assertFalse(session.setHomeAllowed(true, NETHER, "6b6t.org"));
        assertFalse(session.portalProtectionAllowed(true));
    }

    @Test void manualOrSafetyStopCannotBeRearmedByDisconnect() {
        HighwayFlySession session = new HighwayFlySession();
        session.start(NETHER, "6b6t.org");
        assertTrue(session.flying());
        session.stop();
        assertFalse(session.disconnected(true));
        assertFalse(session.recovering());
    }

    @Test void disablingRecoveryEndsTheInterruptedTrip() {
        HighwayFlySession session = new HighwayFlySession();
        session.start(NETHER, "6b6t.org");
        assertTrue(session.flying());
        assertFalse(session.disconnected(false));
        assertFalse(session.flying());
        assertFalse(session.recovering());
    }

    @Test void homeCommandsStayOnTheFlightServerAndDimension() {
        HighwayFlySession session = new HighwayFlySession();
        session.start(NETHER, "6B6T.ORG");
        assertTrue(session.setHomeAllowed(true, NETHER, "6b6t.org"));
        assertFalse(session.setHomeAllowed(true, "minecraft:overworld", "6b6t.org"));
        assertFalse(session.setHomeAllowed(true, NETHER, "other.org"));
        assertFalse(session.setHomeAllowed(false, NETHER, "6b6t.org"));
        assertFalse(session.portalProtectionAllowed(false));
    }

    @Test void restoredRecoveryWaitsForItsOwnServer() {
        HighwayFlySession session = new HighwayFlySession();
        session.recover(NETHER, "6b6t.org");
        assertFalse(session.recoveryAllowed("other.org"));
        assertTrue(session.recoveryAllowed("6B6T.ORG"));
        assertFalse(session.portalProtectionAllowed(true));
        session.start(NETHER, "6b6t.org");
        assertTrue(session.flying());
        assertFalse(session.recovering());
        assertTrue(session.portalProtectionAllowed(true));
    }

    @Test void singleplayerRecoveryDoesNotApplyToMultiplayer() {
        HighwayFlySession session = new HighwayFlySession();
        session.recover(NETHER, null);
        assertTrue(session.recoveryAllowed(null));
        assertFalse(session.recoveryAllowed("6b6t.org"));
    }

    @Test void anotherDisconnectDuringRecoveryRetainsTheOriginalTrip() {
        HighwayFlySession session = new HighwayFlySession();
        session.start(NETHER, "6b6t.org");
        assertTrue(session.disconnected(true));
        assertTrue(session.disconnected(true));
        assertEquals(NETHER, session.dimension());
        assertEquals("6b6t.org", session.server());
        assertFalse(session.recoveryAllowed("other.org"));
    }

    @Test void unexpectedDropAllowsReconnectionOnlyToTheTripServer() {
        HighwayFlySession session = new HighwayFlySession();
        session.start(NETHER, "6b6t.org");
        assertFalse(session.reconnectAllowed(true, "6b6t.org"));
        session.disconnected(true);
        assertTrue(session.reconnectAllowed(true, "6B6T.ORG"));
        assertFalse(session.reconnectAllowed(true, "other.org"));
        assertFalse(session.reconnectAllowed(false, "6b6t.org"));
    }

    @Test void deliberateSafetyLogoutDoesNotReconnect() {
        HighwayFlySession session = new HighwayFlySession();
        session.start(NETHER, "6b6t.org");
        session.stop();
        session.disconnected(true);
        assertFalse(session.reconnectAllowed(true, "6b6t.org"));
    }

    @Test void singleplayerDoesNotTryToReconnectToTheLastMultiplayerServer() {
        HighwayFlySession session = new HighwayFlySession();
        session.start(NETHER, null);
        session.disconnected(true);
        assertFalse(session.reconnectAllowed(true, null));
        assertFalse(session.reconnectAllowed(true, "6b6t.org"));
    }
}
