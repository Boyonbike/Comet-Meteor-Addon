package dev.tntcoating.core;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class AltitudeHoldTest {
    private static final AltitudeHold.Config CONFIG = new AltitudeHold.Config(.05, .1, 20, 20, .25, 30);
    private static final double EPS = 1e-6;

    @Test void climbsWhenBelowTarget() {
        float pitch = AltitudeHold.pitch(120, 100, 0, 100, CONFIG);
        assertTrue(pitch < 0, "negative pitch climbs");
        assertEquals(-.57, pitch, .1);
    }

    @Test void descendsWhenAboveTarget() {
        float pitch = AltitudeHold.pitch(120, 140, 0, 100, CONFIG);
        assertTrue(pitch > 0, "positive pitch descends");
    }

    @Test void deadbandAndVerticalDampingSettleThePitch() {
        assertEquals(0, AltitudeHold.pitch(120, 120, 0, 100, CONFIG), EPS);
        assertEquals(0, AltitudeHold.pitch(120, 120.2, .1, 100, CONFIG), EPS);
    }

    @Test void capsTheRequestedVerticalSpeed() {
        float pitch = AltitudeHold.pitch(500, 0, 0, 20, CONFIG);
        assertEquals(-30, pitch, .1);
    }

    @Test void lowHorizontalSpeedDoesNotCreateAnExtremePitch() {
        float pitch = AltitudeHold.pitch(120, 119, 0, 0, CONFIG);
        assertTrue(pitch > -90 && pitch < 0);
        assertEquals(-.143, pitch, .1);
    }

    @Test void rejectsUnsafeConfiguration() {
        assertThrows(IllegalArgumentException.class, () -> new AltitudeHold.Config(0, 0, 1, 1, 0, 30));
        assertThrows(IllegalArgumentException.class, () -> new AltitudeHold.Config(1, -1, 1, 1, 0, 30));
        assertThrows(IllegalArgumentException.class, () -> new AltitudeHold.Config(1, 0, -1, 1, 0, 30));
        assertThrows(IllegalArgumentException.class, () -> new AltitudeHold.Config(1, 0, 1, 1, 0, 91));
    }

    @Test void rejectsNonFiniteInputs() {
        assertThrows(IllegalArgumentException.class, () -> AltitudeHold.pitch(Double.NaN, 0, 0, 1, CONFIG));
        assertThrows(IllegalArgumentException.class, () -> AltitudeHold.pitch(1, 0, Double.NaN, 1, CONFIG));
        assertThrows(IllegalArgumentException.class, () -> AltitudeHold.pitch(1, 0, 0, Double.NaN, CONFIG));
        assertThrows(IllegalArgumentException.class, () -> AltitudeHold.pitch(1, 0, 0, 1, null));
    }
}