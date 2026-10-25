package dev.tntcoating.core;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import static dev.tntcoating.core.HighwayWebhookLifecycle.Disconnect.*;

class HighwayWebhookLifecycleTest {
    @Test void terminalAlertIsEmittedOnceAcrossFinishAndDeactivation() {
        var state = new HighwayWebhookLifecycle();
        assertTrue(state.terminal());
        assertFalse(state.terminal());
        state.reset();
        assertTrue(state.terminal());
    }

    @Test void liveTransferNeverBecomesLostConnectionAndDoesNotConsumeALaterFailure() {
        var state = new HighwayWebhookLifecycle();
        assertEquals(NONE,state.disconnected(true,false));
        assertEquals(NONE,state.disconnected(true,true));
        assertEquals(UNEXPECTED,state.disconnected(false,true));
        assertEquals(NONE,state.disconnected(false,true));
    }

    @Test void manualExitIsDistinctAndNewConnectionCanReportItsOwnFailure() {
        var state = new HighwayWebhookLifecycle();
        assertEquals(MANUAL,state.disconnected(false,false));
        assertEquals(NONE,state.disconnected(false,true));
        state.joined();
        assertEquals(UNEXPECTED,state.disconnected(false,true));
    }

    @Test void reconnectDoesNotClearAnAlreadyEmittedTripStop() {
        var state = new HighwayWebhookLifecycle();
        assertTrue(state.terminal());
        state.joined();
        assertFalse(state.terminal());
    }
}
