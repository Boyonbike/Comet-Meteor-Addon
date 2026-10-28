package dev.tntcoating.core;

/** Highway navigation owns its avoidance settings; acceleration and ramps remain shared. */
public final class HighwayFlightProfile {
    private HighwayFlightProfile() {}
    public static AdaptiveFlightEngine.Settings create(AdaptiveFlightEngine.Settings profile, int lookahead, double turnRate) {
        java.util.Objects.requireNonNull(profile, "profile");
        if (lookahead < 1 || !Double.isFinite(turnRate) || turnRate <= 0) {
            throw new IllegalArgumentException("Highway lookahead and turn rate must be positive.");
        }
        return new AdaptiveFlightEngine.Settings(profile.acceleration(), profile.braking(), turnRate,
            profile.cruise(), profile.sprint(), profile.verticalMaximum(), profile.verticalCruise(), profile.verticalSprint(),
            profile.verticalAcceleration(), profile.verticalRampStep(), profile.verticalReversalTicks(),
            profile.unlimitedBoost(), profile.boostTop(), profile.boostStart(), profile.boostRampStep(),
            profile.boostVerticalStart(), profile.rewindTicks(), profile.setbackHoldTicks(), profile.boostCeiling(),
            false, profile.maneuverCeiling(), Math.min(64, lookahead), .03, false, .2,
            profile.stockHorizontalSpeed(), profile.stockVerticalSpeed(), profile.fallMultiplier());
    }
}
