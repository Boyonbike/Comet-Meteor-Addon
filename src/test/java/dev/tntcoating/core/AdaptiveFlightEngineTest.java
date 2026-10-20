package dev.tntcoating.core;

import dev.tntcoating.core.AdaptiveFlightController.Motion;
import net.minecraft.world.phys.AABB;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/** The engine's own behaviour around the pipeline: setbacks, the starting-overlap hand-off and repeat moves. */
class AdaptiveFlightEngineTest {
    private static final double EPSILON = 1e-9;
    private static final AABB BODY = new AABB(.2, 200, .2, .8, 200.6, .8);
    private static final AdaptiveFlightEngine.Settings DEFAULTS = EngineFeelDriver.settings(FlightFeelScenarios.DEFAULTS);
    private static final FlightCorridor.Probe OPEN = new FlightCorridor.Probe() {
        @Override public boolean loaded(int x, int z) { return true; }
        @Override public boolean clear(AABB swept) { return true; }
    };

    @Test void aCruiseSetbackKeepsMomentumAndTheNextTickContinuesUnchanged() {
        AdaptiveFlightEngine corrected = new AdaptiveFlightEngine(DEFAULTS);
        AdaptiveFlightEngine untouched = new AdaptiveFlightEngine(DEFAULTS);
        for (int tick = 0; tick < 200; tick++) {
            if (tick == 100) {
                assertTrue(corrected.output().horizontalLength() > 2, "cruising at full speed");
                corrected.setback(false);
            }
            Motion a = fly(corrected, false), b = fly(untouched, false);
            assertEquals(b, a, "tick " + tick + ": a setback never slows, pauses or reshapes cruise");
        }
    }

    @Test void chainedBoostSetbacksOnlyRewindTheRampAndKeepMomentum() {
        AdaptiveFlightEngine engine = new AdaptiveFlightEngine(DEFAULTS);
        for (int tick = 0; tick < 400; tick++) fly(engine, true);
        double top = engine.horizontalBoost();
        assertTrue(top > 14.9, "boost at top speed: " + top);

        // A setback every third tick for ten seconds: each costs exactly one rewind, never more.
        double slowest = top;
        for (int tick = 0; tick < 200; tick++) {
            if (tick % 3 == 0) {
                Motion before = engine.output();
                double ramp = engine.horizontalBoost();
                engine.setback(false);
                double rewound = BoostRamp.rewind(ramp, DEFAULTS.boostRampStep(), DEFAULTS.boostTop(),
                    DEFAULTS.boostStart(), DEFAULTS.rewindTicks());
                assertEquals(rewound, engine.horizontalBoost(), EPSILON);
                Motion after = engine.output();
                assertEquals(Math.min(before.horizontalLength(), rewound), after.horizontalLength(), EPSILON,
                    "momentum is kept at the rewound speed");
                assertEquals(before.y(), after.y(), EPSILON, "and on the same heading");
                assertEquals(0, before.x() * after.z() - before.z() * after.x(), EPSILON);
            }
            fly(engine, true);
            slowest = Math.min(slowest, engine.horizontalBoost());
        }
        assertTrue(slowest >= DEFAULTS.boostStart() - EPSILON, "never below boost-start-speed: " + slowest);
        for (int tick = 0; tick < 600; tick++) fly(engine, true);
        assertEquals(top, engine.horizontalBoost(), .01, "and it climbs straight back once they stop");
    }

    @Test void theMoveAfterASetbackNeverAcceleratesAndTheRampHoldsBeforeClimbing() {
        AdaptiveFlightEngine engine = new AdaptiveFlightEngine(DEFAULTS);
        for (int tick = 0; tick < 30; tick++) fly(engine, true);
        double before = engine.output().horizontalLength();
        engine.setback(false);
        double rewound = engine.horizontalBoost();
        Motion first = fly(engine, true);
        assertEquals(rewound, engine.horizontalBoost(), EPSILON, "the ramp holds for boost-setback-hold");
        assertTrue(rewound < before - .5, "the rewind is a real slowdown: " + rewound * 20 + " from " + before * 20);
        // Braking down from the rejected speed instead (0.24.3) repeated it and pinned every boost start on 6b6t.
        assertEquals(rewound, first.horizontalLength(), EPSILON, "the first move after a setback flies the rewound speed");
        fly(engine, true);
        assertTrue(engine.horizontalBoost() > rewound, "then the ramp climbs again");
    }

    @Test void aSetbackDuringTheManeuverCapStillFliesTheRewoundSpeed() {
        AdaptiveFlightEngine engine = new AdaptiveFlightEngine(DEFAULTS);
        for (int tick = 0; tick < 1000; tick++) fly(engine, DEFAULTS, true, false, 0);
        float yaw = 0;
        // A sharp turn lowers the ramp to the cap while momentum is still braking down to it.
        fly(engine, DEFAULTS, true, false, yaw += 6);
        assertTrue(engine.output().horizontalLength() > engine.horizontalBoost() + .5, "still braking to the cap");
        engine.setback(false);
        double rewound = engine.horizontalBoost();
        Motion first = fly(engine, DEFAULTS, true, false, yaw);
        assertEquals(rewound, first.horizontalLength(), EPSILON);
    }

    @Test void aHardServerLimitSettlesBoostJustBelowItWithoutAnyLearnedCap() {
        double limit = 12; // 240 b/s, below the 300 b/s ramp maximum
        AdaptiveFlightEngine engine = new AdaptiveFlightEngine(DEFAULTS);
        int pendingAt = -1;
        double low = Double.POSITIVE_INFINITY, high = 0;
        for (int tick = 0; tick < 1200; tick++) {
            // The server rejects any move over the limit; its correction arrives three ticks later.
            if (pendingAt == tick) { engine.setback(false); pendingAt = -1; }
            Motion motion = fly(engine, true);
            if (pendingAt < 0 && motion.length() > limit) pendingAt = tick + 3;
            if (tick >= 600) {
                low = Math.min(low, motion.length());
                high = Math.max(high, motion.length());
            }
        }
        assertTrue(low > limit * .9, "rewinds hold boost close under the limit: " + low);
        assertTrue(high < limit + .5, "and only briefly over it: " + high);
    }

    @Test void aTeleportFarAwayStopsTheFlightAndReleasesTheRamps() {
        AdaptiveFlightEngine engine = new AdaptiveFlightEngine(DEFAULTS);
        for (int tick = 0; tick < 100; tick++) fly(engine, true);
        engine.setback(true);
        assertEquals(Motion.ZERO, engine.output());
        assertEquals(0, engine.horizontalBoost(), EPSILON);
        assertFalse(engine.boostActive());
    }

    @Test void aBodyAlreadyInsideTerrainHandsOverToStockFlight() {
        AdaptiveFlightEngine engine = new AdaptiveFlightEngine(DEFAULTS);
        for (int tick = 0; tick < 50; tick++) fly(engine, true);
        FlightCorridor.Probe solid = new FlightCorridor.Probe() {
            @Override public boolean loaded(int x, int z) { return true; }
            @Override public boolean clear(AABB swept) { return false; }
        };
        engine.startTick(DEFAULTS);
        assertFalse(engine.begin(true, true, 1.2, 1.2, BODY, solid));
        assertEquals(Motion.ZERO, engine.output());
        assertFalse(engine.boostActive());
    }

    @Test void aRepeatMoveInOneTickOnlyShortensTowardsWhatIsClear() {
        AdaptiveFlightEngine engine = new AdaptiveFlightEngine(DEFAULTS);
        for (int tick = 0; tick < 100; tick++) fly(engine, false);
        Motion output = engine.output();
        AABB wall = BODY.move(1.5, 0, 0).expandTowards(0, 0, 50);
        FlightCorridor.Probe walled = new FlightCorridor.Probe() {
            @Override public boolean loaded(int x, int z) { return true; }
            @Override public boolean clear(AABB swept) { return !swept.intersects(wall); }
        };
        assertTrue(engine.begin(false, true, 1.2, 1.2, BODY, OPEN));
        engine.repeat(BODY, OPEN);
        assertEquals(output, engine.output(), "clear terrain keeps the tick's movement");
        engine.repeat(BODY.move(0, 0, 0), walled);
        assertTrue(engine.output().length() <= output.length() + EPSILON);
    }

    @Test void overdriveBoostsTo340AndAClimbSharesTheServerLimitInsteadOfExceedingIt() {
        AdaptiveFlightEngine.Settings overdrive = boosting(ServerMoveLimit.SINGLE, ServerMoveLimit.SINGLE);
        AdaptiveFlightEngine engine = new AdaptiveFlightEngine(overdrive);
        for (int tick = 0; tick < 1000; tick++) fly(engine, overdrive, true, false);
        assertTrue(engine.output().horizontalLength() > 16.9, "340 b/s: " + engine.output().horizontalLength() * 20);

        for (int tick = 0; tick < 100; tick++) {
            Motion motion = fly(engine, overdrive, true, true);
            assertTrue(motion.length() <= ServerMoveLimit.SINGLE + EPSILON, "tick " + tick + ": " + motion.length());
            if (tick > 10) assertTrue(motion.y() > 2, "still climbing at boost speed");
        }
    }

    @Test void climbingWhileBoostingNeverTakesTotalSpeedPastTheChosenMaximum() {
        AdaptiveFlightEngine.Settings capped = boosting(10, 10); // a 200 b/s maximum, the 6b6t-safe speed
        AdaptiveFlightEngine engine = new AdaptiveFlightEngine(capped);
        for (int tick = 0; tick < 600; tick++) {
            Motion motion = fly(engine, capped, true, tick > 300);
            assertTrue(motion.length() <= 10 + EPSILON, "tick " + tick + ": " + motion.length() * 20 + " b/s");
            if (tick > 320) assertTrue(motion.y() > 2, "still climbing, by trading some horizontal speed");
        }
    }

    @Test void climbingWhileBoostingBrakesToTheManeuverCapAndKeepsTheClimb() {
        AdaptiveFlightEngine engine = new AdaptiveFlightEngine(DEFAULTS);
        for (int tick = 0; tick < 1000; tick++) fly(engine, DEFAULTS, true, false, 0);
        assertTrue(engine.output().horizontalLength() > 14.8, "straight and level: full 300 b/s");
        double previous = engine.output().length();
        for (int tick = 0; tick < 100; tick++) {
            Motion motion = fly(engine, DEFAULTS, true, true, 0);
            assertTrue(previous - motion.length() < 2.5, "brakes over a few ticks instead of snapping: tick " + tick);
            if (tick >= 5) {
                assertTrue(motion.length() <= DEFAULTS.maneuverCeiling() + EPSILON, "tick " + tick + ": " + motion.length() * 20);
                assertTrue(engine.maneuverCapped());
            }
            previous = motion.length();
        }
        assertEquals(3, engine.output().y(), 1e-6, "the climb keeps its full 60 b/s");
        assertEquals(Math.sqrt(100 - 9), engine.output().horizontalLength(), 1e-6, "horizontal pays for it");
    }

    @Test void aSharpTurnCapsBoostAndStraightLevelFlightRampsBackGradually() {
        AdaptiveFlightEngine engine = new AdaptiveFlightEngine(DEFAULTS);
        for (int tick = 0; tick < 1000; tick++) fly(engine, DEFAULTS, true, false, 0);
        float yaw = 0;
        for (int tick = 0; tick < 20; tick++) fly(engine, DEFAULTS, true, false, yaw += 6);
        assertTrue(engine.output().length() <= DEFAULTS.maneuverCeiling() + EPSILON, "turning: " + engine.output().length() * 20);

        double previous = engine.output().length();
        for (int tick = 0; tick < AdaptiveFlightEngine.MANEUVER_HOLD_TICKS; tick++) {
            fly(engine, DEFAULTS, true, false, yaw);
            assertEquals(previous, engine.output().length(), 1e-6, "held for half a second after the turn");
        }
        for (int tick = 0; tick < 40; tick++) {
            double speed = fly(engine, DEFAULTS, true, false, yaw).length();
            assertTrue(speed >= previous - EPSILON && speed - previous < .2, "climbs back along the ramp: " + speed);
            previous = speed;
        }
        assertTrue(previous > DEFAULTS.maneuverCeiling(), "back above 200 b/s once straight");
    }

    @Test void gentleSteeringAndAnUnsetCapLeaveBoostAlone() {
        AdaptiveFlightEngine engine = new AdaptiveFlightEngine(DEFAULTS);
        float yaw = 0;
        for (int tick = 0; tick < 1000; tick++) fly(engine, DEFAULTS, true, false, yaw += 1);
        assertTrue(engine.output().horizontalLength() > 14.8, "a 1 degree per tick curve is not a maneuver");

        AdaptiveFlightEngine.Settings uncapped = withManeuverCeiling(Double.POSITIVE_INFINITY);
        AdaptiveFlightEngine free = new AdaptiveFlightEngine(uncapped);
        for (int tick = 0; tick < 1000; tick++) fly(free, uncapped, true, tick > 900, 0);
        assertTrue(free.output().horizontalLength() > 14, "with the cap off, climbing keeps boost speed");
    }

    @Test void aBoostMaximumAboveTheServerLimitIsHeldToItInThreeDimensions() {
        AdaptiveFlightEngine.Settings tooFast = boosting(24, ServerMoveLimit.SINGLE);
        AdaptiveFlightEngine engine = new AdaptiveFlightEngine(tooFast);
        for (int tick = 0; tick < 1000; tick++) {
            Motion motion = fly(engine, tooFast, true, tick > 500);
            assertTrue(motion.length() <= ServerMoveLimit.SINGLE + EPSILON, "tick " + tick + ": " + motion.length());
        }
    }

    /** The defaults with another boost top speed and total boost ceiling. */
    private static AdaptiveFlightEngine.Settings boosting(double top, double ceiling) {
        AdaptiveFlightEngine.Settings d = DEFAULTS;
        return new AdaptiveFlightEngine.Settings(d.acceleration(), d.braking(), d.turnRate(), d.cruise(), d.sprint(),
            d.verticalMaximum(), d.verticalCruise(), d.verticalSprint(), d.verticalAcceleration(), d.verticalRampStep(),
            d.unlimitedBoost(), top, d.boostStart(), d.boostRampStep(), d.boostVerticalStart(), d.rewindTicks(),
            d.setbackHoldTicks(), ceiling, d.maneuverCeiling(), d.lookahead(), d.margin(), d.safeManeuvering(), d.closeObjectSpeed(),
            d.stockHorizontalSpeed(), d.stockVerticalSpeed(), d.fallMultiplier());
    }

    /** One client tick of W held (and Sprint when boosting), facing +Z in open air. */
    private static Motion fly(AdaptiveFlightEngine engine, boolean sprint) {
        return fly(engine, DEFAULTS, sprint, false);
    }

    private static Motion fly(AdaptiveFlightEngine engine, AdaptiveFlightEngine.Settings settings,
                              boolean sprint, boolean jump) {
        return fly(engine, settings, sprint, jump, 0);
    }

    private static Motion fly(AdaptiveFlightEngine engine, AdaptiveFlightEngine.Settings settings,
                              boolean sprint, boolean jump, float yaw) {
        engine.startTick(settings);
        assertTrue(engine.begin(sprint, true, 1.2, 1.2, BODY, OPEN));
        engine.step(new AdaptiveFlightEngine.Input(1, 0, yaw, jump, false, -.5, BODY, OPEN,
            () -> Double.POSITIVE_INFINITY, null, movement -> movement));
        return engine.output();
    }

    /** The defaults with another manoeuvre cap. */
    private static AdaptiveFlightEngine.Settings withManeuverCeiling(double ceiling) {
        AdaptiveFlightEngine.Settings d = DEFAULTS;
        return new AdaptiveFlightEngine.Settings(d.acceleration(), d.braking(), d.turnRate(), d.cruise(), d.sprint(),
            d.verticalMaximum(), d.verticalCruise(), d.verticalSprint(), d.verticalAcceleration(), d.verticalRampStep(),
            d.unlimitedBoost(), d.boostTop(), d.boostStart(), d.boostRampStep(), d.boostVerticalStart(), d.rewindTicks(),
            d.setbackHoldTicks(), d.boostCeiling(), ceiling, d.lookahead(), d.margin(), d.safeManeuvering(),
            d.closeObjectSpeed(), d.stockHorizontalSpeed(), d.stockVerticalSpeed(), d.fallMultiplier());
    }
}
