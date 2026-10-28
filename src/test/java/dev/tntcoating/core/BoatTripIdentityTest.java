package dev.tntcoating.core;

import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class BoatTripIdentityTest {
    @Test void sameDimensionInDifferentSavesCannotResumeTheOldTrip() {
        var first = BoatTripIdentity.serverKey(null, Path.of("saves/first"));
        var second = BoatTripIdentity.serverKey(null, Path.of("saves/second"));
        assertNotEquals(first, second);
        var marker = new LobbyRecoveryStore.Recovery("minecraft:overworld", first, null, null, 0f, LobbyRecoveryStore.NATIVE_BOAT);
        assertTrue(BoatRecoveryPolicy.canResume(marker, first, true, true));
        assertFalse(BoatRecoveryPolicy.canResume(marker, second, true, true));
        var control = new BoatTravelControl(); control.bindWorld(first, "minecraft:overworld");
        assertFalse(control.inTripWorld(second, "minecraft:overworld"));
        assertTrue(control.inTripWorld(first, "minecraft:overworld"));
    }
    @Test void normalizedSavePathsRemainStableAndUnknownWorldsFailClosed() {
        assertEquals(BoatTripIdentity.serverKey(null, Path.of("saves/first")),
            BoatTripIdentity.serverKey(null, Path.of("saves/second/../first")));
        assertNull(BoatTripIdentity.serverKey(null, null));
        assertEquals("Example.org:25565", BoatTripIdentity.serverKey("Example.org:25565", null));
        assertTrue(BoatTripIdentity.serverKey(null, Path.of("saves/first")).length() < 256);
    }
    @Test void savedRecoverySurvivesReloadButCannotMoveToAnotherSave(
            @org.junit.jupiter.api.io.TempDir Path directory) throws Exception {
        String first = BoatTripIdentity.serverKey(null, directory.resolve("first-save"));
        String second = BoatTripIdentity.serverKey(null, directory.resolve("second-save"));
        Path markerFile = directory.resolve("boat-recovery.json");
        var store = new LobbyRecoveryStore(markerFile);
        store.saveBoat("minecraft:the_nether", first, -90, LobbyRecoveryStore.NATIVE_BOAT);
        var reloaded = new LobbyRecoveryStore(markerFile).load().orElseThrow();
        assertTrue(BoatRecoveryPolicy.canResume(reloaded, first, true, true));
        assertFalse(BoatRecoveryPolicy.canResume(reloaded, second, true, true));
        assertTrue(BoatRecoveryPolicy.saveActive(reloaded, first, "minecraft:the_nether", -90, true, true));
        assertFalse(BoatRecoveryPolicy.saveActive(reloaded, second, "minecraft:the_nether", -90, true, true));
    }

}
