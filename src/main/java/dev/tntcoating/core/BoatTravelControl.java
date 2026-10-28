/* SPDX-License-Identifier: GPL-3.0-only */
package dev.tntcoating.core;

/** Manual flight remains available while explicit automatic trip ownership is paused. */
public final class BoatTravelControl {
    private boolean automatic;
    private String server, dimension;

    /** Bind a new trip, or replace its identity with an authoritative recovery marker. */
    public void bindWorld(String server, String dimension) { this.server = server; this.dimension = dimension; }
    public boolean inTripWorld(String currentServer, String currentDimension) {
        return server != null && dimension != null && server.equalsIgnoreCase(currentServer)
            && dimension.equals(currentDimension);
    }
    public String server() { return server; }
    public String dimension() { return dimension; }
    public boolean automatic() { return automatic; }
    public boolean helpersRequested(boolean moduleActive) { return moduleActive && automatic; }
    public boolean start(boolean driver) {
        if (!driver) return false;
        automatic = true;
        return true;
    }
    public void pause() { automatic = false; }
    public void restore(boolean savedAutomatic) { automatic = savedAutomatic; }
}
