package dev.tntcoating.core;

/** Known server spawn points. A small tolerance covers spawn rounding and initial movement. */
public enum LobbyLocation {
    NONE(null, 0, 0, 0),
    CRACKED_LOBBY("minecraft:the_end", 1000.5, 100, 1000.5),
    LOBBY("minecraft:overworld", 247.5, 62, 228.5),
    WAITING_AREA("minecraft:the_end", 29.5, 19.06, 18.5);

    private final String dimension;
    private final double x, y, z;

    LobbyLocation(String dimension, double x, double y, double z) {
        this.dimension = dimension;
        this.x = x;
        this.y = y;
        this.z = z;
    }

    /** Known spawns override the fallback account setting; returning to the route needs no login. */
    public boolean requiresLogin(boolean crackedAccount, boolean routeDimension) {
        return this == CRACKED_LOBBY || this == NONE && crackedAccount && !routeDimension;
    }

    public static LobbyLocation detect(String dimension, double x, double y, double z) {
        for (LobbyLocation location : values()) {
            if (location != NONE && location.dimension.equals(dimension)
                && Math.abs(location.y - y) <= 1
                && Math.abs(location.x - x) <= 3
                && Math.abs(location.z - z) <= 3) return location;
        }
        return NONE;
    }
}
