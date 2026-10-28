package dev.tntcoating.core;

import dev.tntcoating.core.AdaptiveFlightController.Motion;
import net.minecraft.world.phys.AABB;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class HighwayFlightProfileTest {
    @Test void highwayOwnsTerrainAndSteeringWhileKeepingTheSharedRamp() {
        var inherited = EngineFeelDriver.settings(FlightFeelScenarios.DEFAULTS);
        var highway = HighwayFlightProfile.create(inherited, 48, 12);
        assertEquals(48, highway.lookahead());
        assertEquals(12, highway.turnRate());
        assertEquals(.03, highway.margin());
        assertFalse(highway.safeManeuvering());
        assertFalse(highway.maneuverMode());
        assertEquals(inherited.boostRampStep(), highway.boostRampStep());
        assertEquals(inherited.braking(), highway.braking());
    }

    @Test void closeGapRemainsTraversableWithoutInheritedStandoffOrCollisionRelease() {
        var inherited = EngineFeelDriver.settings(FlightFeelScenarios.DEFAULTS);
        var profile = HighwayFlightProfile.create(inherited, 48, 15);
        AABB body = new AABB(-.3, 0, -.3, .3, .6, .3);
        AABB wall = new AABB(-3, -1, .5, 3, 2, .6);
        FlightCorridor.Probe probe = new FlightCorridor.Probe() {
            public boolean loaded(int x, int z) { return true; }
            public boolean clear(AABB box) { return !box.intersects(wall); }
        };
        BaritoneFlight flight = new BaritoneFlight(profile);
        var config = new BaritoneFlight.Settings(8, 8, .3, .3);
        Motion movement = flight.move(profile, config, 1, 0, 0, true, 0, 0, body, probe,
            () -> Double.POSITIVE_INFINITY, null, motion -> motion);
        assertNotNull(movement);
        assertTrue(movement.z() > 0, "A small physical clearance should allow a controlled approach");
        assertFalse(body.move(movement.x(), movement.y(), movement.z()).intersects(wall));
    }
}
