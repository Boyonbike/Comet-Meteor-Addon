package dev.tntcoating.core;

/** Facing and climb decisions for steering Meteor's ElytraFly towards a point. */
public final class ElytraSteer {
    private ElytraSteer() {}

    public enum Climb { UP, DOWN, HOLD }

    /** Minecraft yaw, in degrees, that faces along (dx, dz). */
    public static float yawTo(double dx, double dz) {
        return (float) (Math.toDegrees(Math.atan2(dz, dx)) - 90);
    }

    /** Turns from current towards desired by at most maxStep degrees, the short way round. */
    public static float turn(float current, float desired, float maxStep) {
        float diff = wrap(desired - current);
        return current + Math.max(-maxStep, Math.min(maxStep, diff));
    }

    /** Climb or descend when more than band blocks off the target height (dy = target minus player). */
    public static Climb climb(double dy, double band) {
        if (dy > band) return Climb.UP;
        if (dy < -band) return Climb.DOWN;
        return Climb.HOLD;
    }

    /** The same angle in (-180, 180]. */
    static float wrap(float degrees) {
        float wrapped = degrees % 360;
        if (wrapped <= -180) wrapped += 360;
        else if (wrapped > 180) wrapped -= 360;
        return wrapped;
    }
}
