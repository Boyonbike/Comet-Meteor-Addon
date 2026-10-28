/* SPDX-License-Identifier: GPL-3.0-only */
package dev.tntcoating.core;

/** Saved module flags cannot authorize a fresh unattended trip. */
public final class BoatRecoveryPolicy {
    private BoatRecoveryPolicy() {}
    public static boolean canResume(LobbyRecoveryStore.Recovery marker, String server, boolean connected, boolean recoveryEnabled) {
        return recoveryEnabled && nativeMarker(marker) && (!connected || marker.isForServer(server));
    }
    public static boolean saveActive(LobbyRecoveryStore.Recovery marker, String server, String dimension,
                                     double heading, boolean automatic, boolean recoveryEnabled) {
        return automatic && canResume(marker, server, true, recoveryEnabled)
            && marker.dimension().equals(dimension) && Math.abs(Math.IEEEremainder(marker.boatYaw() - heading, 360)) < .001;
    }
    public static boolean controllerAuthorized(boolean active, boolean configured, boolean automatic, boolean loading) {
        return active && configured && (automatic || loading);
    }
    /** Native ownership survives temporary seat loss until the controller explicitly ends its grace period. */
    public static boolean keepTrip(boolean nativeController, boolean automatic, boolean driving) {
        return driving || nativeController && automatic;
    }
    private static boolean nativeMarker(LobbyRecoveryStore.Recovery marker) {
        return marker != null && marker.server() != null && LobbyRecoveryStore.NATIVE_BOAT.equals(marker.boatModule()) && marker.boatYaw() != null
            && Float.isFinite(marker.boatYaw()) && marker.dimension() != null && !marker.dimension().isBlank();
    }
}
