package dev.tntcoating.core;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class ElytraLandingTest {
    @Test void missingLandingSiteHoldsPositionAndAltitude() {
        // The module supplies a point above the current position when there is no checked dry landing site.
        var step = ElytraLanding.step(0, -10, 10);
        assertFalse(step.forward());
        assertFalse(step.descend());
        assertEquals(0, step.horizontalSpeed());
        assertEquals(0, step.fallMultiplier());
    }

    @Test void approachStaysLevelUntilAboveTheLandingFootprint() {
        var step = ElytraLanding.step(4, 100, 10);
        assertTrue(step.forward());
        assertFalse(step.descend());
        assertEquals(0, step.fallMultiplier());
        assertTrue(step.horizontalSpeed() > 0);
        assertTrue(step.horizontalSpeed() <= 0.4);
    }

    @Test void descendsOnlyWithinTheLandingFootprint() {
        var aligned = ElytraLanding.step(0.75, 3, 10);
        assertFalse(aligned.forward());
        assertTrue(aligned.descend());
        assertEquals(0.01, aligned.fallMultiplier());
        var outside = ElytraLanding.step(0.751, 3, 10);
        assertTrue(outside.forward());
        assertFalse(outside.descend());
    }

    @Test void finalTouchdownUsesNaturalDescentInsteadOfHoldingAboveGround() {
        var step = ElytraLanding.step(0, 2, 10);
        assertFalse(step.forward());
        assertFalse(step.descend());
        assertTrue(step.fallMultiplier() > 0);
        assertTrue(step.fallMultiplier() < 1);
    }

    @Test void approachConvergesEvenWhenInitiallyFacingAwayAtOneDegreePerTick() {
        for (float turnSpeed : new float[] { 1, 10, 45 }) {
            for (float initialYaw : new float[] { -90, 0, 90, 180 }) {
                double x = 20, z = 0;
                float yaw = initialYaw;
                int ticks = 0;
                while (Math.hypot(x, z) > 0.75 && ticks++ < 3_000) {
                    var step = ElytraLanding.step(Math.hypot(x, z), 30, turnSpeed);
                    assertFalse(step.descend());
                    yaw = ElytraSteer.turn(yaw, ElytraSteer.yawTo(-x, -z), turnSpeed);
                    x -= Math.sin(Math.toRadians(yaw)) * step.horizontalSpeed();
                    z += Math.cos(Math.toRadians(yaw)) * step.horizontalSpeed();
                }
                assertTrue(Math.hypot(x, z) <= 0.75, "Approach did not reach the site at turn speed " + turnSpeed + " from yaw " + initialYaw);
            }
        }
    }

    @Test void tinyTurnSpeedAndZeroDistanceRemainFinite() {
        var tiny = ElytraLanding.step(10, 30, 1e-9);
        assertTrue(Double.isFinite(tiny.horizontalSpeed()));
        assertTrue(tiny.horizontalSpeed() > 0);
        assertTrue(tiny.horizontalSpeed() < 1e-8);
        var zero = ElytraLanding.step(0, 0, 1e-9);
        assertFalse(zero.forward());
        assertEquals(0, zero.horizontalSpeed());
        assertTrue(Double.isFinite(zero.fallMultiplier()));
    }
}
