package dev.tntcoating.core;

/** Movement decisions for approaching a checked landing site and descending onto it. */
public final class ElytraLanding {
    private ElytraLanding() {}

    public record Step(boolean forward, boolean descend, double horizontalSpeed, double fallMultiplier) {}

    /**
     * @param horizontalDistance distance to the centre of the landing site
     * @param dy player height minus target height; a target above the player requests holding altitude
     * @param turnSpeed maximum yaw change per tick, in degrees
     */
    public static Step step(double horizontalDistance, double dy, double turnSpeed) {
        boolean forward = horizontalDistance > 0.75;
        boolean descend = !forward && dy > 2;
        // Hold altitude during the approach. A gentle natural fall finishes the last few blocks.
        double fallMultiplier = forward || dy < 0 ? 0 : descend ? 0.01 : 0.8;
        // The turning radius must stay smaller than the distance remaining, even at a low turn speed.
        double speed = Math.min(0.4, horizontalDistance * Math.max(0, Math.sin(Math.toRadians(turnSpeed))) * 0.5);
        return new Step(forward, descend, speed, fallMultiplier);
    }
}
