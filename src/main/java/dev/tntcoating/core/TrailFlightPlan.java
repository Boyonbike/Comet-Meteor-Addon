package dev.tntcoating.core;

/** Limits Baritone route changes without letting a straight trail run into an obsolete endpoint. */
public final class TrailFlightPlan {
    private static final long UPDATE_MILLIS = 3_000;
    private static final double LOOK_AHEAD = 500;
    private static final double REFRESH_DISTANCE = 200;
    private static final float HEADING_CHANGE = 10;
    private Goal last;
    private long sentAt;
    private float sentYaw;

    public record Goal(int x, int y, int z) {}

    /** Null means keep the existing route. The first call always supplies a route. */
    public Goal next(long now, double x, double z, int y, float yaw) {
        if (last != null) {
            if (now - sentAt < UPDATE_MILLIS) return null;
            float delta = Math.abs(ElytraSteer.turn(sentYaw, yaw, 180) - sentYaw);
            if (delta < HEADING_CHANGE && Math.hypot(last.x - x, last.z - z) > REFRESH_DISTANCE) return null;
        }
        double radians = Math.toRadians(yaw);
        last = new Goal((int) Math.floor(x - Math.sin(radians) * LOOK_AHEAD), y,
            (int) Math.floor(z + Math.cos(radians) * LOOK_AHEAD));
        sentAt = now;
        sentYaw = yaw;
        return last;
    }

    public void reset() { last = null; }
}
