/* SPDX-License-Identifier: GPL-3.0-only */
package dev.tntcoating.modules.boatfly;

/** GameLeft still has a player on some builds; callbacks must not resubscribe modules during teardown. */
public final class BoatModuleRestoreGate {
    private boolean leavingWorld;
    public void worldLeft() { leavingWorld = true; }
    public void worldJoined() { leavingWorld = false; }
    public boolean needsListener(boolean pendingModules) { return leavingWorld || pendingModules; }
    public boolean mayRestore(boolean worldReady, boolean controllingBoat) {
        return !leavingWorld && worldReady && !controllingBoat;
    }
}
