package dev.tntcoating.core;

import dev.tntcoating.core.AdaptiveFlightStep.Blend;
import dev.tntcoating.core.AdaptiveFlightStep.Controls;

/** Shorthand Controls for regression tests; production builds the full record. */
final class TestControls {
    /** Vertical flight up to the engine maximum at the controller's own acceleration and braking, without the boost curve. */
    static final Blend BLEND = new Blend(64, 100, 0);

    private TestControls() {}

    static Controls of(boolean sprintBoost, double maximumFraction, int verticalInput, boolean releaseSafeCollisions) {
        return of(sprintBoost, maximumFraction, verticalInput, releaseSafeCollisions, null);
    }

    static Controls of(boolean sprintBoost, double maximumFraction, int verticalInput, boolean releaseSafeCollisions,
                       ServerGlide.Context glide) {
        return of(sprintBoost, maximumFraction, maximumFraction, verticalInput, releaseSafeCollisions, glide, BLEND);
    }

    static Controls of(boolean sprintBoost, double maximumFraction, double verticalFraction, int verticalInput,
                       boolean releaseSafeCollisions, ServerGlide.Context glide, Blend blend) {
        return new Controls(sprintBoost, maximumFraction, verticalFraction, verticalInput, releaseSafeCollisions,
            glide, AdaptiveFlightStep.SAFE_MANEUVER_SPEED, blend, null);
    }
}
