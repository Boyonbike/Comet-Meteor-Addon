package dev.tntcoating.core;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class PositionStabilityTest {
    @Test void groundedPlayerWithVanillaGravityVelocityIsStable() {
        assertTrue(PositionStability.stable(0, -0.0784, true));
    }
    @Test void airbornePlayerMustHaveSmallVerticalVelocity() {
        assertFalse(PositionStability.stable(0, -0.0784, false));
        assertTrue(PositionStability.stable(0, 0.01, false));
    }
    @Test void landingOrCorrectionDisplacementResetsStability() {
        assertFalse(PositionStability.stable(0.64, 0, true));
        assertFalse(PositionStability.stable(1, 0, true));
    }
    @Test void steadyWalkingAfterLandingDoesNotKeepCoatingPaused() {
        assertTrue(PositionStability.stable(0.09, -0.0784, true));
        assertTrue(PositionStability.stable(0.16, -0.0784, true));
    }
}
