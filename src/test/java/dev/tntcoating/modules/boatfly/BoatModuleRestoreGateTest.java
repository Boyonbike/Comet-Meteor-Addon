package dev.tntcoating.modules.boatfly;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class BoatModuleRestoreGateTest {
    @Test void worldLeaveBlocksRestoreEvenBeforeMinecraftClearsThePlayer() {
        var gate = new BoatModuleRestoreGate();
        assertTrue(gate.mayRestore(true, false));
        gate.worldLeft();
        assertFalse(gate.mayRestore(true, false), "GameLeft may still have a player, but Meteor already swept listeners");
        assertFalse(gate.mayRestore(false, false));
    }
    @Test void rejoinWaitsForAWorldAndDoesNotRestoreFlightWhileBoatControlsIt() {
        var gate = new BoatModuleRestoreGate();
        gate.worldLeft(); gate.worldJoined();
        assertFalse(gate.mayRestore(false, false));
        assertFalse(gate.mayRestore(true, true));
        assertTrue(gate.mayRestore(true, false));
    }
    @Test void anotherDisconnectBlocksPendingRestoreAgain() {
        var gate = new BoatModuleRestoreGate();
        gate.worldLeft(); gate.worldJoined(); gate.worldLeft();
        assertFalse(gate.mayRestore(true, false));
    }
    @Test void emptyQueueStillObservesRejoinIfBoatFlyIsDisabledWhileDisconnected() {
        var gate = new BoatModuleRestoreGate();
        assertFalse(gate.needsListener(false));
        gate.worldLeft();
        assertTrue(gate.needsListener(false), "The engine can be switched off before GameJoined");
        gate.worldJoined();
        assertFalse(gate.needsListener(false));
        assertTrue(gate.mayRestore(true, false));
        assertTrue(gate.needsListener(true));
    }
}
