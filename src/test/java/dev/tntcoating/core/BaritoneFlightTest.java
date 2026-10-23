package dev.tntcoating.core;

import dev.tntcoating.core.AdaptiveFlightController.Motion;
import net.minecraft.world.phys.AABB;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class BaritoneFlightTest {
    private static final double EPSILON = 1e-9;
    private static final AABB BODY = new AABB(.2, 100, .2, .8, 100.6, .8);
    private static final FlightCorridor.Probe OPEN = probe(null, Integer.MAX_VALUE);
    private static final AdaptiveFlightEngine.Settings PROFILE = EngineFeelDriver.settings(FlightFeelScenarios.DEFAULTS);
    private static final BaritoneFlight.Settings CONFIG = new BaritoneFlight.Settings(300, 60, .3, .5);

    @Test void automaticLevelFlightMatchesAdaptiveBoostEveryTickIncludingSetbackHold() {
        BaritoneFlight flight = new BaritoneFlight(PROFILE);
        AdaptiveFlightEngine adaptive = new AdaptiveFlightEngine(PROFILE);
        for (int tick = 0; tick < 120; tick++) {
            if (tick == 50) {
                flight.setback(false);
                adaptive.setback(false);
            }
            adaptive.startTick(PROFILE);
            assertTrue(adaptive.begin(true, true, 1, 1, BODY, OPEN));
            adaptive.step(new AdaptiveFlightEngine.Input(1, 0, 0, false, false, 0,
                BODY, OPEN, () -> Double.POSITIVE_INFINITY, null, movement -> movement));
            Motion actual = move(flight, CONFIG, tick, 0, 0, BODY, OPEN);
            assertEquals(adaptive.output(), actual, "tick " + tick);
        }
    }

    @Test void configuredCapsStopTheRampWithoutChangingItsInitialCurve() {
        BaritoneFlight flight = new BaritoneFlight(PROFILE);
        BaritoneFlight.Settings cap = new BaritoneFlight.Settings(80, 60, .3, .5);
        assertEquals(2.999, move(flight, cap, 0, 0, 0, BODY, OPEN).horizontalLength(), EPSILON);
        assertEquals(3.2390160010667377, move(flight, cap, 1, 0, 0, BODY, OPEN).horizontalLength(), EPSILON);
        Motion actual = Motion.ZERO;
        for (int tick = 2; tick < 100; tick++) actual = move(flight, cap, tick, 0, 0, BODY, OPEN);
        assertEquals(4, actual.horizontalLength(), EPSILON);
    }

    @Test void localRampStepsControlHorizontalAndVerticalAcceleration() {
        BaritoneFlight slower = new BaritoneFlight(PROFILE);
        BaritoneFlight faster = new BaritoneFlight(PROFILE);
        BaritoneFlight.Settings slow = new BaritoneFlight.Settings(300, 60, .1, .1);
        BaritoneFlight.Settings fast = new BaritoneFlight.Settings(300, 60, .8, .8);
        move(slower, slow, 0, 0, -40, BODY, OPEN);
        move(faster, fast, 0, 0, -40, BODY, OPEN);
        Motion a = move(slower, slow, 1, 0, -40, BODY, OPEN);
        Motion b = move(faster, fast, 1, 0, -40, BODY, OPEN);
        assertTrue(b.horizontalLength() > a.horizontalLength());
        assertTrue(b.y() > a.y());
    }

    @Test void capturedYawAndPitchSteerBothClimbsAndDives() {
        for (float pitch : new float[] {-20, 20}) {
            BaritoneFlight flight = new BaritoneFlight(PROFILE);
            Motion actual = move(flight, CONFIG, 0, -90, pitch, BODY, OPEN);
            assertTrue(actual.x() > 0);
            assertEquals(0, actual.z(), EPSILON);
            assertEquals(-Math.signum(pitch), Math.signum(actual.y()), EPSILON);
        }
    }

    @Test void shallowPitchUsesASlopeCapInsteadOfFullVerticalBoost() {
        BaritoneFlight flight = new BaritoneFlight(PROFILE);
        Motion actual = move(flight, CONFIG, 0, 0, -1, BODY, OPEN);
        assertEquals(.05234773971972455, actual.y(), EPSILON);
        assertTrue(actual.y() < .06, "one degree must not engage the full climb start speed");
    }

    @Test void tinyPitchNoiseAndZeroVerticalCapKeepFlightLevel() {
        for (float pitch : new float[] {0, -.01f, .01f}) {
            BaritoneFlight flight = new BaritoneFlight(PROFILE);
            assertEquals(0, move(flight, CONFIG, 0, 0, pitch, BODY, OPEN).y(), EPSILON);
        }
        BaritoneFlight flight = new BaritoneFlight(PROFILE);
        assertTrue(move(flight, CONFIG, 0, 0, -70, BODY, OPEN).y() > 0);
        Motion level = move(flight, new BaritoneFlight.Settings(300, 0, .3, .5), 1, 0, -70, BODY, OPEN);
        assertEquals(0, level.y(), EPSILON);
        assertTrue(level.horizontalLength() > 0, "disabling vertical flight preserves horizontal flight");
    }

    @Test void zeroHorizontalCapImmediatelyStopsExistingHorizontalMomentum() {
        BaritoneFlight flight = new BaritoneFlight(PROFILE);
        move(flight, CONFIG, 0, 0, 0, BODY, OPEN);
        Motion stopped = move(flight, new BaritoneFlight.Settings(0, 60, .3, .5), 1, 0, 0, BODY, OPEN);
        assertEquals(0, stopped.horizontalLength(), EPSILON);
    }

    @Test void repeatedMoveInOneTickOnlyRechecksTerrainAndDoesNotAccelerateTwice() {
        BaritoneFlight flight = new BaritoneFlight(PROFILE);
        Motion first = move(flight, CONFIG, 10, 0, 0, BODY, OPEN);
        assertEquals(first, move(flight, CONFIG, 10, 90, -60, BODY, OPEN));
        AABB wall = new AABB(-5, 90, 2, 5, 120, 3);
        Motion clipped = move(flight, CONFIG, 10, 0, 0, BODY, probe(wall, Integer.MAX_VALUE));
        assertTrue(clipped.length() < first.length());
        assertFalse(BODY.move(clipped.x(), clipped.y(), clipped.z()).intersects(wall));
    }

    @Test void pitchingUpAfterAStopCanClimbOverTheObstacleThatStoppedLevelFlight() {
        AABB ledge = new AABB(-5, 90, 1.14, 5, 100.01, 1.24);
        FlightCorridor.Probe terrain = probe(ledge, Integer.MAX_VALUE);
        BaritoneFlight flight = new BaritoneFlight(PROFILE);
        assertEquals(Motion.ZERO, move(flight, CONFIG, 0, 0, 0, BODY, terrain));
        AABB body = BODY;
        for (int tick = 1; tick <= 5; tick++) {
            Motion climbing = move(flight, CONFIG, tick, 0, -70, body, terrain);
            assertNotNull(climbing);
            assertTrue(climbing.y() > 0, "the available ramp must still allow a climb after a terrain stop");
            body = body.move(climbing.x(), climbing.y(), climbing.z());
            assertFalse(body.intersects(ledge), "tick " + tick);
        }
        assertTrue(body.minY > ledge.maxY);
        assertTrue(body.minZ > ledge.maxZ, "the route resumes beyond the ledge");
    }

    @Test void verticalHeadingsKeepFiniteSignedMotionWithinTheConfiguredVerticalCap() {
        for (float pitch : new float[] {-90, 90}) {
            BaritoneFlight flight = new BaritoneFlight(PROFILE);
            BaritoneFlight.Settings slowClimb = new BaritoneFlight.Settings(300, 20, .3, .5);
            for (int tick = 0; tick < 10; tick++) {
                Motion actual = move(flight, slowClimb, tick, 0, pitch, BODY, OPEN);
                assertTrue(Double.isFinite(actual.length()));
                assertEquals(-Math.signum(pitch), Math.signum(actual.y()), EPSILON);
                assertTrue(Math.abs(actual.y()) <= 1 + EPSILON);
                assertEquals(0, actual.horizontalLength(), EPSILON, "a vertical route must not fly forward");
            }
            BaritoneFlight stopped = new BaritoneFlight(PROFILE);
            Motion zero = move(stopped, new BaritoneFlight.Settings(0, 60, .3, .5), 0, 0, pitch, BODY, OPEN);
            assertEquals(Motion.ZERO, zero);
        }
    }

    @Test void steepRoutesReduceHorizontalSpeedToPreserveTheRequestedSlopeAtTheVerticalCap() {
        for (float pitch : new float[] {-70, 70}) {
            BaritoneFlight flight = new BaritoneFlight(PROFILE);
            // A cap above the shared profile's vertical curve does not make that curve faster.
            BaritoneFlight.Settings config = new BaritoneFlight.Settings(300, 1000, .3, .5);
            Motion settled = Motion.ZERO;
            for (int tick = 0; tick < 200; tick++) settled = move(flight, config, tick, 0, pitch, BODY, OPEN);
            assertEquals(3, Math.abs(settled.y()), EPSILON);
            assertEquals(1.091910702798607, settled.horizontalLength(), EPSILON);
            assertEquals(2.7474774194546216, Math.abs(settled.y()) / settled.horizontalLength(), EPSILON);
        }
    }

    @Test void wallAndUnloadedChunkGuardsKeepMovementOutOfUnknownTerrain() {
        for (FlightCorridor.Probe terrain : new FlightCorridor.Probe[] {
            probe(new AABB(-5, 90, 35, 5, 120, 36), Integer.MAX_VALUE), probe(null, 2)
        }) {
            BaritoneFlight flight = new BaritoneFlight(PROFILE);
            AABB body = BODY;
            for (int tick = 0; tick < 100; tick++) {
                Motion actual = move(flight, CONFIG, tick, 0, 0, body, terrain);
                assertNotNull(actual);
                body = body.move(actual.x(), actual.y(), actual.z());
                assertTrue(body.maxZ < 35, "tick " + tick + " reached " + body.maxZ);
                assertTrue(body.maxZ < 32 || terrain.loaded(0, 2), "unloaded chunk edge");
            }
        }
    }

    @Test void overlappingTerrainYieldsAndResumingStartsFromTheBoostFloor() {
        BaritoneFlight flight = new BaritoneFlight(PROFILE);
        move(flight, CONFIG, 0, 0, 0, BODY, OPEN);
        FlightCorridor.Probe occupied = probe(BODY.inflate(1), Integer.MAX_VALUE);
        assertNull(move(flight, CONFIG, 1, 0, 0, BODY, occupied));
        assertEquals(Motion.ZERO, flight.output());
        assertEquals(2.999, move(flight, CONFIG, 2, 0, 0, BODY, OPEN).horizontalLength(), EPSILON);
    }

    @Test void teleportAndResetRestartTheRampRatherThanKeepingOldMomentum() {
        BaritoneFlight flight = new BaritoneFlight(PROFILE);
        for (int tick = 0; tick < 30; tick++) move(flight, CONFIG, tick, 0, 0, BODY, OPEN);
        flight.setback(true);
        assertEquals(Motion.ZERO, flight.output());
        assertEquals(2.999, move(flight, CONFIG, 30, 0, 0, BODY, OPEN).horizontalLength(), EPSILON);
        flight.reset();
        assertEquals(2.999, move(flight, CONFIG, 30, 0, 0, BODY, OPEN).horizontalLength(), EPSILON);
    }

    @Test void combinedFlightNeverExceedsTheSinglePacketMovementBudget() {
        AdaptiveFlightEngine.Settings profile = profile(24, ServerMoveLimit.SPLIT, 1, 1);
        BaritoneFlight flight = new BaritoneFlight(profile);
        BaritoneFlight.Settings high = new BaritoneFlight.Settings(1000, 1000, .3, .5);
        for (int tick = 0; tick < 400; tick++) {
            Motion actual = flight.move(profile, high, tick, 0, -10, true, 1, 1, BODY, OPEN,
                () -> Double.POSITIVE_INFINITY, null, movement -> movement);
            assertTrue(actual.length() <= ServerMoveLimit.SINGLE + EPSILON);
        }
    }

    @Test void splitMoveProfileUsesTheSinglePacketCurveTop() {
        AdaptiveFlightEngine.Settings profile = profile(24, ServerMoveLimit.SPLIT, 1, 1);
        BaritoneFlight flight = new BaritoneFlight(profile);
        BaritoneFlight.Settings high = new BaritoneFlight.Settings(1000, 1000, .3, .5);
        flight.move(profile, high, 0, 0, 0, true, 1, 1, BODY, OPEN,
            () -> Double.POSITIVE_INFINITY, null, movement -> movement);
        Motion next = flight.move(profile, high, 1, 0, 0, true, 1, 1, BODY, OPEN,
            () -> Double.POSITIVE_INFINITY, null, movement -> movement);
        assertEquals(3.2460764705882355, next.horizontalLength(), EPSILON);
    }

    @Test void meteorStockSpeedsCannotDisableTheIndependentFlightAxes() {
        AdaptiveFlightEngine.Settings profile = profile(PROFILE.boostTop(), PROFILE.boostCeiling(), 0, 0);
        BaritoneFlight flight = new BaritoneFlight(profile);
        Motion actual = flight.move(profile, CONFIG, 0, 0, -70, true, 1, 1, BODY, OPEN,
            () -> Double.POSITIVE_INFINITY, null, movement -> movement);
        assertEquals(1.091910702798607, actual.horizontalLength(), EPSILON);
        assertEquals(1.5, actual.y(), EPSILON);
    }

    @Test void anElytraSwapDelayUsesAdaptiveAccelerationBeforeAutomaticBoostResumes() {
        BaritoneFlight flight = new BaritoneFlight(PROFILE);
        Motion delayed = flight.move(PROFILE, CONFIG, 0, 0, 0, false, 1, 1, BODY, OPEN,
            () -> Double.POSITIVE_INFINITY, null, movement -> movement);
        assertEquals(.15, delayed.horizontalLength(), EPSILON);
        assertEquals(2.999, move(flight, CONFIG, 1, 0, 0, BODY, OPEN).horizontalLength(), EPSILON);
    }

    @Test void changingPitchDirectionKeepsTheAdaptiveVerticalReversalBrake() {
        BaritoneFlight flight = new BaritoneFlight(PROFILE);
        assertEquals(1.5, move(flight, CONFIG, 0, 0, -70, BODY, OPEN).y(), EPSILON);
        assertEquals(1, move(flight, CONFIG, 1, 0, 70, BODY, OPEN).y(), EPSILON);
        assertEquals(.5, move(flight, CONFIG, 2, 0, 70, BODY, OPEN).y(), EPSILON);
        assertTrue(move(flight, CONFIG, 3, 0, 70, BODY, OPEN).y() < 0);
    }

    @Test void levelingTheRequestedHeadingBrakesVerticalMomentumOverSeveralTicks() {
        BaritoneFlight flight = new BaritoneFlight(PROFILE);
        assertEquals(1.5, move(flight, CONFIG, 0, 0, -70, BODY, OPEN).y(), EPSILON);
        assertEquals(1.25, move(flight, CONFIG, 1, 0, 0, BODY, OPEN).y(), EPSILON);
        assertTrue(move(flight, CONFIG, 2, 0, .01f, BODY, OPEN).y() > 0);
        Motion stopped = Motion.ZERO;
        for (int tick = 3; tick < 30; tick++) stopped = move(flight, CONFIG, tick, 0, 0, BODY, OPEN);
        assertEquals(0, stopped.y(), EPSILON);
    }

    @Test void aSetbackBetweenMovesImmediatelyAppliesTheRewoundRampOnce() {
        BaritoneFlight flight = new BaritoneFlight(PROFILE);
        Motion before = Motion.ZERO;
        for (int tick = 0; tick <= 40; tick++) before = move(flight, CONFIG, tick, 0, 0, BODY, OPEN);
        flight.setback(false);
        Motion corrected = move(flight, CONFIG, 40, 0, 0, BODY, OPEN);
        assertTrue(corrected.horizontalLength() < before.horizontalLength(), "same-tick move must use the rewind");
        assertTrue(corrected.horizontalLength() > PROFILE.boostStart(), "setbacks retain ramp progress");
        assertEquals(corrected, move(flight, CONFIG, 40, 0, 0, BODY, OPEN));
        assertTrue(move(flight, CONFIG, 41, 0, 0, BODY, OPEN).horizontalLength() > corrected.horizontalLength());
    }

    @Test void aTeleportBetweenMovesRestartsTheRampInTheSameTick() {
        BaritoneFlight flight = new BaritoneFlight(PROFILE);
        for (int tick = 0; tick <= 30; tick++) move(flight, CONFIG, tick, 0, 0, BODY, OPEN);
        flight.setback(true);
        Motion restarted = move(flight, CONFIG, 30, 0, 0, BODY, OPEN);
        assertEquals(2.999, restarted.horizontalLength(), EPSILON);
        assertEquals(restarted, move(flight, CONFIG, 30, 0, 0, BODY, OPEN));
        assertEquals(3.2390160010667377, move(flight, CONFIG, 31, 0, 0, BODY, OPEN).horizontalLength(), EPSILON);
    }

    @Test void descendingPitchUsesTheAdaptiveSoftLandingFloorScan() {
        BaritoneFlight flight = new BaritoneFlight(PROFILE);
        Motion landing = flight.move(PROFILE, CONFIG, 0, 0, 70, true, 1, 1, BODY, OPEN,
            () -> 0, null, movement -> movement);
        assertEquals(-AdaptiveFlightStep.LANDING_SPEED, landing.y(), EPSILON);
    }

    @Test void approachCapsDestinationSpeedWithoutReshapingTheBoostRamp() {
        assertEquals(300, BaritoneFlight.approach(CONFIG, 100).speedBps(), EPSILON);
        assertEquals(60, BaritoneFlight.approach(CONFIG, 100).verticalSpeedBps(), EPSILON);
        BaritoneFlight.Settings near = BaritoneFlight.approach(CONFIG, 10);
        assertEquals(40, near.speedBps(), EPSILON);
        assertEquals(4, BaritoneFlight.approach(CONFIG, .25).speedBps(), EPSILON);
        assertEquals(40, near.verticalSpeedBps(), EPSILON);
        assertEquals(4, BaritoneFlight.approach(CONFIG, .25).verticalSpeedBps(), EPSILON);
        assertEquals(CONFIG.rampStep(), near.rampStep());
        assertEquals(CONFIG.verticalRampStep(), near.verticalRampStep());
        BaritoneFlight.Settings slower = BaritoneFlight.approach(new BaritoneFlight.Settings(2, 1, .1, .2), 10);
        assertEquals(2, slower.speedBps());
        assertEquals(1, slower.verticalSpeedBps());

        BaritoneFlight flight = new BaritoneFlight(PROFILE);
        for (int tick = 0; tick < 200; tick++) move(flight, CONFIG, tick, 0, 0, BODY, OPEN);
        assertTrue(flight.output().horizontalLength() > 14);
        Motion capped = move(flight, BaritoneFlight.approach(CONFIG, .25), 200, 0, 0, BODY, OPEN);
        assertEquals(.2, capped.horizontalLength(), EPSILON, "arrival must not keep the old boost momentum");
    }

    @Test void approachImmediatelySlowsAnExistingVerticalDescentNearTheAirWaypoint() {
        BaritoneFlight flight = new BaritoneFlight(PROFILE);
        for (int tick = 0; tick < 200; tick++) move(flight, CONFIG, tick, 0, 90, BODY, OPEN);
        assertTrue(flight.output().y() < -2.9);
        Motion arriving = move(flight, BaritoneFlight.approach(CONFIG, .25), 200, 0, 90, BODY, OPEN);
        assertEquals(-.2, arriving.y(), EPSILON);
        assertEquals(0, arriving.horizontalLength(), EPSILON);
    }

    @Test void landingDescendsAtTheColumnCenterEvenWhenBothCruiseAxesAreDisabled() {
        BaritoneFlight flight = new BaritoneFlight(PROFILE);
        BaritoneFlight.Settings disabled = new BaritoneFlight.Settings(0, 0, .3, .5);
        for (int tick = 0; tick < 5; tick++) {
            Motion landing = land(flight, disabled, tick, 0, 0, BODY, OPEN);
            assertEquals(0, landing.horizontalLength(), EPSILON);
            assertEquals(-.2, landing.y(), EPSILON);
        }
    }

    @Test void landingBoundsFarAndFinalColumnAlignmentAndHonorsLowerPositiveCaps() {
        BaritoneFlight flight = new BaritoneFlight(PROFILE);
        Motion far = land(flight, CONFIG, 0, 100, 0, BODY, OPEN);
        assertEquals(.2, far.x(), EPSILON);
        assertEquals(0, far.z(), EPSILON);
        Motion near = land(flight, CONFIG, 1, .03, 0, BODY, OPEN);
        assertEquals(.03, near.horizontalLength(), EPSILON);
        assertEquals(-.2, near.y(), EPSILON);

        BaritoneFlight slow = new BaritoneFlight(PROFILE);
        Motion limited = land(slow, new BaritoneFlight.Settings(1, 2, .3, .5), 0, 100, 0, BODY, OPEN);
        assertEquals(.05, limited.horizontalLength(), EPSILON);
        assertEquals(-.1, limited.y(), EPSILON);
    }

    @Test void enteringLandingImmediatelyReplacesCruiseMomentumWithTheLandingCaps() {
        BaritoneFlight flight = new BaritoneFlight(PROFILE);
        for (int tick = 0; tick < 200; tick++) move(flight, CONFIG, tick, 0, -1, BODY, OPEN);
        assertTrue(flight.output().horizontalLength() > 14);
        assertTrue(flight.output().y() > 0);
        Motion landing = land(flight, CONFIG, 200, 0, 0, BODY, OPEN);
        assertEquals(0, landing.horizontalLength(), EPSILON);
        assertEquals(-.2, landing.y(), EPSILON);
    }

    @Test void landingTouchesTheFloorThroughRealCollisionInsteadOfHoveringAboveIt() {
        BaritoneFlight flight = new BaritoneFlight(PROFILE);
        AABB[] body = { BODY.move(0, -97, 0) };
        FlightCorridor.Probe floor = new FlightCorridor.Probe() {
            @Override public boolean loaded(int x, int z) { return true; }
            @Override public boolean clear(AABB swept) { return swept.minY >= 0; }
        };
        for (int tick = 0; tick < 30; tick++) {
            Motion landing = flight.land(PROFILE, CONFIG, tick, 0, 0, true, .1, .1, body[0], floor,
                () -> Math.max(0, body[0].minY), null,
                movement -> new Motion(movement.x(), Math.max(movement.y(), -body[0].minY), movement.z()));
            assertNotNull(landing);
            assertTrue(landing.y() >= -.2 - EPSILON);
            body[0] = body[0].move(landing.x(), landing.y(), landing.z());
            assertTrue(body[0].minY >= -EPSILON);
        }
        assertEquals(0, body[0].minY, EPSILON);
        assertEquals(0, flight.output().length(), EPSILON);
    }

    @Test void landingKeepsWallCollisionsAndUnloadedChunkGuards() {
        BaritoneFlight flight = new BaritoneFlight(PROFILE);
        AABB[] body = { BODY };
        AABB wall = new AABB(-5, 0, 1, 5, 120, 2);
        FlightCorridor.Probe blocked = probe(wall, Integer.MAX_VALUE);
        for (int tick = 0; tick < 5; tick++) {
            Motion landing = flight.land(PROFILE, CONFIG, tick, 0, 10, true, .1, .1, body[0], blocked,
                () -> Double.POSITIVE_INFINITY, null,
                movement -> new Motion(movement.x(), movement.y(), Math.min(movement.z(), 1 - body[0].maxZ)));
            body[0] = body[0].move(landing.x(), landing.y(), landing.z());
            assertFalse(body[0].intersects(wall));
        }
        assertEquals(1, body[0].maxZ, EPSILON);

        BaritoneFlight chunks = new BaritoneFlight(PROFILE);
        AABB edge = BODY.move(0, 0, 15);
        for (int tick = 0; tick < 5; tick++) {
            Motion landing = land(chunks, CONFIG, tick, 0, 10, edge, probe(null, 1));
            assertNotNull(landing);
            edge = edge.move(landing.x(), landing.y(), landing.z());
            assertTrue(edge.maxZ <= 16);
        }
    }

    @Test void landingRepeatsAndCorrectionsReuseTheSameBoundedDescent() {
        BaritoneFlight flight = new BaritoneFlight(PROFILE);
        Motion first = land(flight, CONFIG, 4, 0, 0, BODY, OPEN);
        assertEquals(first, land(flight, CONFIG, 4, 0, 0, BODY, OPEN));
        flight.setback(false);
        assertEquals(first, land(flight, CONFIG, 4, 0, 0, BODY, OPEN));
        flight.setback(true);
        assertEquals(first, land(flight, CONFIG, 4, 0, 0, BODY, OPEN));
    }

    private static Motion land(BaritoneFlight flight, BaritoneFlight.Settings config, int tick,
                               double offsetX, double offsetZ, AABB body, FlightCorridor.Probe probe) {
        return flight.land(PROFILE, config, tick, offsetX, offsetZ, true, .1, .1, body, probe,
            () -> Double.POSITIVE_INFINITY, null, movement -> movement);
    }

    private static AdaptiveFlightEngine.Settings profile(double top, double ceiling, double stockH, double stockV) {
        var p = PROFILE;
        return new AdaptiveFlightEngine.Settings(p.acceleration(), p.braking(), p.turnRate(),
            p.cruise(), p.sprint(), p.verticalMaximum(), p.verticalCruise(), p.verticalSprint(),
            p.verticalAcceleration(), p.verticalRampStep(), p.verticalReversalTicks(), p.unlimitedBoost(),
            top, p.boostStart(), p.boostRampStep(), p.boostVerticalStart(), p.rewindTicks(), p.setbackHoldTicks(),
            ceiling, p.maneuverMode(), p.maneuverCeiling(), p.lookahead(), p.margin(), p.safeManeuvering(),
            p.closeObjectSpeed(), stockH, stockV, p.fallMultiplier());
    }

    private static Motion move(BaritoneFlight flight, BaritoneFlight.Settings config, int tick,
                               float yaw, float pitch, AABB body, FlightCorridor.Probe probe) {
        return flight.move(PROFILE, config, tick, yaw, pitch, true, 1, 1, body, probe,
            () -> Double.POSITIVE_INFINITY, null, movement -> movement);
    }

    private static FlightCorridor.Probe probe(AABB obstacle, int unloadedChunkZ) {
        return new FlightCorridor.Probe() {
            @Override public boolean loaded(int x, int z) { return z < unloadedChunkZ; }
            @Override public boolean clear(AABB swept) { return obstacle == null || !swept.intersects(obstacle); }
        };
    }
}
