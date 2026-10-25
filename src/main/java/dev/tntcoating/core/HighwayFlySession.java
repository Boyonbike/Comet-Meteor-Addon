package dev.tntcoating.core;

/** Coordinates flight-only helpers with reconnect recovery and explicit stops. */
public final class HighwayFlySession {
    private enum Phase { STOPPED, FLYING, RECOVERING }
    private Phase phase = Phase.STOPPED;
    private String dimension;
    private String server;

    public void start(String dimension, String server) {
        this.dimension = java.util.Objects.requireNonNull(dimension);
        this.server = server;
        phase = Phase.FLYING;
    }

    public void recover(String dimension, String server) {
        this.dimension = java.util.Objects.requireNonNull(dimension);
        this.server = server;
        phase = Phase.RECOVERING;
    }

    public void stop() { phase = Phase.STOPPED; }

    public boolean disconnected(boolean recoveryEnabled) {
        if (recoveryEnabled && phase != Phase.STOPPED) {
            phase = Phase.RECOVERING;
            return true;
        }
        stop();
        return false;
    }

    public boolean flying() { return phase == Phase.FLYING; }
    public boolean recovering() { return phase == Phase.RECOVERING; }
    public String dimension() { return dimension; }
    public String server() { return server; }

    public boolean recoveryAllowed(String currentServer) {
        return recovering() && sameServer(currentServer);
    }

    public boolean reconnectAllowed(boolean enabled, String reconnectServer) {
        return enabled && server != null && recoveryAllowed(reconnectServer);
    }

    public boolean setHomeAllowed(boolean enabled, String currentDimension, String currentServer) {
        return enabled && flying() && dimension.equals(currentDimension) && sameServer(currentServer);
    }

    public boolean portalProtectionAllowed(boolean enabled) { return enabled && flying(); }

    private boolean sameServer(String currentServer) {
        return server == null ? currentServer == null
            : currentServer != null && server.equalsIgnoreCase(currentServer);
    }
}
