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

    @Test void reachesTargetThroughTheFlightControllerAtHighwaySpeed() {
        var profile = HighwayFlightProfile.create(EngineFeelDriver.settings(FlightFeelScenarios.DEFAULTS), 48, 15);
        var flight = new BaritoneFlight(profile, 0);
        var settings = new BaritoneFlight.Settings(300, 20, .3, .5);
        var open = new FlightCorridor.Probe() {
            public boolean loaded(int x, int z) { return true; }
            public boolean clear(net.minecraft.world.phys.AABB body) { return true; }
        };
        for (double startY : new double[] {110, 130}) {
            flight.reset();
            double y = startY;
            for (int tick = 0; tick < 4000; tick++) {
                var previous = flight.output();
                float pitch = AltitudeHold.pitch(120, y, previous, CONFIG);
                var body = new net.minecraft.world.phys.AABB(-.3, y, -.3, .3, y + .6, .3);
                var movement = flight.move(profile, settings, tick, 0, pitch, true,
                    1, 1, body, open, () -> Double.POSITIVE_INFINITY, null, m -> m);
                assertNotNull(movement);
                y += movement.y();
                if (tick >= 3000) assertEquals(120, y, .5, "Must keep holding the target");
            }
            assertEquals(120, y, .5, "Must settle at target from Y " + startY);
        }
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