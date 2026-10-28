package dev.tntcoating.core;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class BoatTravelControlTest {
    @Test void enablingBoatEngineStartsInManualPositioningWithoutHelpers() {
        var control = new BoatTravelControl();
        assertFalse(control.automatic());
        assertFalse(control.helpersRequested(true));
    }
    @Test void explicitStartRequiresDriverSeatThenEnablesTripHelpers() {
        var control = new BoatTravelControl();
        assertFalse(control.start(false));
        assertFalse(control.automatic());
        assertTrue(control.start(true));
        assertTrue(control.helpersRequested(true));
        assertFalse(control.helpersRequested(false));
    }
    @Test void pauseReturnsToPositioningAndCanStartAgain() {
        var control = new BoatTravelControl();
        control.start(true); control.pause();
        assertFalse(control.helpersRequested(true));
        assertTrue(control.start(true));
    }
    @Test void savedAutomaticTripCanWaitForBoatAfterReconnect() {
        var control = new BoatTravelControl();
        control.restore(true);
        assertTrue(control.automatic());
        control.restore(false);
        assertFalse(control.helpersRequested(true));
    }
    @Test void recoveryLoadedAfterActivationReplacesTheLobbyWorldIdentity() {
        var control = new BoatTravelControl();
        control.bindWorld("server:25565", "minecraft:overworld");
        control.bindWorld("server:25565", "minecraft:the_nether");
        assertFalse(control.inTripWorld("server:25565", "minecraft:overworld"));
        assertTrue(control.inTripWorld("SERVER:25565", "minecraft:the_nether"));
        assertFalse(control.inTripWorld("different-server", "minecraft:the_nether"));
    }
    @Test void missingWorldIdentityCannotAuthorizeAutomaticTravel() {
        var control = new BoatTravelControl();
        assertFalse(control.inTripWorld("server", "minecraft:the_nether"));
        String save = BoatTripIdentity.serverKey(null, java.nio.file.Path.of("saves/first"));
        control.bindWorld(save, "minecraft:the_nether");
        assertTrue(control.inTripWorld(save, "minecraft:the_nether"));
        control.bindWorld(null, null);
        assertFalse(control.inTripWorld(save, "minecraft:the_nether"));
    }
}
