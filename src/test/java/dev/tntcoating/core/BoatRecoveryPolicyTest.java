package dev.tntcoating.core;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class BoatRecoveryPolicyTest {
    private final LobbyRecoveryStore.Recovery nativeTrip = new LobbyRecoveryStore.Recovery(
        "minecraft:the_nether", "server", null, null, -90f, LobbyRecoveryStore.NATIVE_BOAT);
    @Test void profilesRequireANativeMarkerAndRecoveryEnabled() {
        assertFalse(BoatRecoveryPolicy.canResume(null, "server", true, true));
        assertFalse(BoatRecoveryPolicy.canResume(nativeTrip, "server", true, false));
        assertFalse(BoatRecoveryPolicy.canResume(new LobbyRecoveryStore.Recovery("minecraft:the_nether", "server"), "server", true, true));
        assertFalse(BoatRecoveryPolicy.canResume(new LobbyRecoveryStore.Recovery("minecraft:the_nether", "server", null, null, -90f), "server", true, true));
        assertTrue(BoatRecoveryPolicy.canResume(nativeTrip, "SERVER", true, true));
    }
    @Test void offlineLoadMayWaitButConnectedLoadCannotSwitchServers() {
        assertTrue(BoatRecoveryPolicy.canResume(nativeTrip, null, false, true));
        assertFalse(BoatRecoveryPolicy.canResume(nativeTrip, "other", true, true));
        assertFalse(BoatRecoveryPolicy.canResume(nativeTrip, null, true, true));
        var singleplayer = new LobbyRecoveryStore.Recovery("minecraft:the_nether", null, null, null, 0f, LobbyRecoveryStore.NATIVE_BOAT);
        assertFalse(BoatRecoveryPolicy.canResume(singleplayer, null, true, true),
            "A legacy marker without a save identity cannot authorize automatic travel");
    }
    @Test void savingRequiresAnAutomaticTripAndMatchingDurableIdentity() {
        assertTrue(BoatRecoveryPolicy.saveActive(nativeTrip, "server", "minecraft:the_nether", -90, true, true));
        assertFalse(BoatRecoveryPolicy.saveActive(nativeTrip, "server", "minecraft:the_nether", -90, false, true));
        assertFalse(BoatRecoveryPolicy.saveActive(nativeTrip, "server", "minecraft:the_nether", -90, true, false));
        assertFalse(BoatRecoveryPolicy.saveActive(nativeTrip, "server", "minecraft:overworld", -90, true, true));
        assertFalse(BoatRecoveryPolicy.saveActive(nativeTrip, "other", "minecraft:the_nether", -90, true, true));
        assertFalse(BoatRecoveryPolicy.saveActive(nativeTrip, "server", "minecraft:the_nether", 0, true, true));
    }
    @Test void nativeRecoveryAlwaysRequiresAnActiveSafetyOwner() {
        assertFalse(BoatRecoveryPolicy.controllerAuthorized(false, true, true, true));
        assertFalse(BoatRecoveryPolicy.controllerAuthorized(true, false, true, true));
        assertFalse(BoatRecoveryPolicy.controllerAuthorized(true, true, false, false));
        assertTrue(BoatRecoveryPolicy.controllerAuthorized(true, true, true, false));
        assertTrue(BoatRecoveryPolicy.controllerAuthorized(true, true, false, true));
    }
    @Test void equivalentNorthHeadingsRemainRestartableWhileRecovering() {
        var north = new LobbyRecoveryStore.Recovery("minecraft:the_nether", "server", null, null, 180f, LobbyRecoveryStore.NATIVE_BOAT);
        assertTrue(BoatRecoveryPolicy.saveActive(north, "server", "minecraft:the_nether", -180, true, true));
    }
    @Test void dismountGraceRetainsNativeTripButNotAnUnownedExternalTrip() {
        assertTrue(BoatRecoveryPolicy.keepTrip(true, true, false));
        assertTrue(BoatRecoveryPolicy.keepTrip(true, true, true));
        assertFalse(BoatRecoveryPolicy.keepTrip(true, false, false));
        assertFalse(BoatRecoveryPolicy.keepTrip(false, false, false));
        assertTrue(BoatRecoveryPolicy.keepTrip(false, false, true));
        var session = new HighwayFlySession(); session.start(nativeTrip.dimension(), nativeTrip.server());
        if (!BoatRecoveryPolicy.keepTrip(true, true, false)) session.stop();
        assertTrue(session.disconnected(true));
        assertTrue(session.recoveryAllowed("server"));
    }

}
