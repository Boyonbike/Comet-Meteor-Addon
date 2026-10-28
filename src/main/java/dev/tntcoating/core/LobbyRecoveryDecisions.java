/* SPDX-License-Identifier: GPL-3.0-only */
package dev.tntcoating.core;

/** Shared join/arrival decisions: standalone recovery must not invent a destination for a native boat trip. */
public final class LobbyRecoveryDecisions {
    public enum Movement { FOOT, BOAT, WAIT }
    public static Movement movement(boolean boatTrip, boolean passenger, boolean boatReady) {
        if (!passenger) return Movement.FOOT;
        return boatTrip && boatReady ? Movement.BOAT : Movement.WAIT;
    }
    public enum Arrival { COMPLETE_STANDALONE, STOP_TRIP, RESUME_TRIP }
    private LobbyRecoveryDecisions() {}
    public static boolean standaloneAllowed(boolean independentlyEnabled, boolean nativeBoatActive, boolean knownLobby) {
        return nativeBoatActive ? knownLobby : independentlyEnabled;
    }
    public static Arrival arrived(boolean standalone, boolean resumeFlight) {
        if (standalone) return Arrival.COMPLETE_STANDALONE;
        return resumeFlight ? Arrival.RESUME_TRIP : Arrival.STOP_TRIP;
    }
}
