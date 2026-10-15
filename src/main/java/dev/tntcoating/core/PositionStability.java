package dev.tntcoating.core;

public final class PositionStability {
    private PositionStability() {}

    public static boolean stable(double squaredDisplacement, double verticalVelocity, boolean grounded) {
        // Vanilla can retain a small negative gravity velocity while on the ground.
        // Normal walking/sprinting after landing should not require the user to stop.
        return grounded ? squaredDisplacement < 0.2025
            : squaredDisplacement < 0.0064 && Math.abs(verticalVelocity) < 0.04;
    }
}
