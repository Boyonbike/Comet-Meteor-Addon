package dev.tntcoating.modules.boatfly;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class BoatTickGateTest {
    @Test
    void suppressesDuplicatePlayerTickButAllowsSessionReplacement() {
        BoatTickGate gate = new BoatTickGate();
        Object firstPlayer = new Object();
        assertTrue(gate.tick(firstPlayer, 10));
        assertFalse(gate.tick(firstPlayer, 10));
        assertTrue(gate.tick(firstPlayer, 11));
        assertTrue(gate.tick(new Object(), 11));
        gate.reset();
        assertTrue(gate.tick(firstPlayer, 11));
    }

    @Test
    void usesPacketIdentityAndClearsOwnershipOnReset() {
        BoatTickGate gate = new BoatTickGate();
        Object first = new String("same contents");
        Object second = new String("same contents");
        assertTrue(gate.vehicle(first));
        assertFalse(gate.vehicle(first));
        assertTrue(gate.vehicle(second));
        gate.reset();
        assertTrue(gate.vehicle(second));
    }
}
