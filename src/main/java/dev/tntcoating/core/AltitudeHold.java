package dev.tntcoating.core;

/** Converts a target altitude into the pitch expected by the shared flight pipeline. */
public final class AltitudeHold {
    public record Config(double gain, double damping, double maxClimbBps, double maxDescendBps,
                         double deadband, double maxPitchDegrees) {
        public Config {
            requireFinite(gain, "Altitude gain");
            requireFinite(damping, "Altitude damping");
            requireFinite(maxClimbBps, "Maximum climb speed");
            requireFinite(maxDescendBps, "Maximum descent speed");
            requireFinite(deadband, "Altitude deadband");
            requireFinite(maxPitchDegrees, "Maximum altitude pitch");
            if (gain <= 0) throw new IllegalArgumentException("Altitude gain must be positive.");
            if (damping < 0) throw new IllegalArgumentException("Altitude damping cannot be negative.");
            if (maxClimbBps < 0 || maxDescendBps < 0) {
                throw new IllegalArgumentException("Altitude speed caps cannot be negative.");
            }
            if (deadband < 0) throw new IllegalArgumentException("Altitude deadband cannot be negative.");
            if (maxPitchDegrees <= 0 || maxPitchDegrees > 90) {
                throw new IllegalArgumentException("Altitude pitch must be in (0, 90].");
            }
        }
    }

    private AltitudeHold() {}

    /**
     * @param verticalBps positive when climbing, like the player's delta movement Y
     * @param horizontalBps current horizontal speed, used to turn the requested climb into a path angle
     * @return Minecraft pitch in degrees; negative climbs and positive descends
     */
    public static float pitch(double targetY, double y, double verticalBps, double horizontalBps, Config config) {
        requireFinite(targetY, "Target altitude");
        requireFinite(y, "Current altitude");
        requireFinite(verticalBps, "Vertical speed");
        requireFinite(horizontalBps, "Horizontal speed");
        if (config == null) throw new IllegalArgumentException("Altitude configuration is required.");

        double error = targetY - y;
        if (Math.abs(error) <= config.deadband() && Math.abs(verticalBps) <= config.deadband()) return 0;

        double desired = clamp(error * config.gain() - verticalBps * config.damping(),
            -config.maxDescendBps(), config.maxClimbBps());
        if (Math.abs(desired) < 1e-9) return 0;

        double horizontal = Math.max(Math.abs(horizontalBps), 20);
        double pitch = -Math.toDegrees(Math.atan2(desired, horizontal));
        return (float) clamp(pitch, -config.maxPitchDegrees(), config.maxPitchDegrees());
    }

    private static void requireFinite(double value, String name) {
        if (!Double.isFinite(value)) throw new IllegalArgumentException(name + " must be finite.");
    }

    private static double clamp(double value, double min, double max) {
        return Math.max(min, Math.min(max, value));
    }
}