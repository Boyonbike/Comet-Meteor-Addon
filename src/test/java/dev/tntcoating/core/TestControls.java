package dev.tntcoating.core;

import dev.tntcoating.core.AdaptiveFlightStep.Controls;

/** Shorthand Controls for regression tests; production builds the full record. */
final class TestControls {
    private TestControls() {}

    static Controls of(boolean sprintBoost, double maximumFraction, int verticalInput, boolean releaseSafeCollisions) {
        return of(sprintBoost, maximumFraction, verticalInput, releaseSafeCollisions, null);
    }

    static Controls of(boolean sprintBoost, double maximumFraction, int verticalInput, boolean releaseSafeCollisions,
                       ServerGlide.Context glide) {
        return of(sprintBoost, maximumFraction, verticalInput, releaseSafeCollisions, glide, null);
    }

    static Controls of(boolean sprintBoost, double maximumFraction, int verticalInput, boolean releaseSafeCollisions,
                       ServerGlide.Context glide, AdaptiveFlightMemory.Limits limits) {
        return of(sprintBoost, maximumFraction, maximumFraction, verticalInput, releaseSafeCollisions, glide, limits);
    }

    static Controls of(boolean sprintBoost, double maximumFraction, double verticalFraction, int verticalInput,
                       boolean releaseSafeCollisions, ServerGlide.Context glide, AdaptiveFlightMemory.Limits limits) {
        return new Controls(sprintBoost, maximumFraction, verticalFraction, verticalInput, releaseSafeCollisions,
            glide, limits, AdaptiveFlightStep.SAFE_MANEUVER_SPEED);
    }
}
