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
    private static final double MANEUVER_CAP = 10; // 200 b/s
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
                assertEquals(before, engine.output(), "momentum is kept");
                assertEquals(BoostRamp.rewind(ramp, DEFAULTS.boostRampStep(), DEFAULTS.boostTop(),
                    DEFAULTS.boostStart(), DEFAULTS.rewindTicks()), engine.horizontalBoost(), EPSILON);
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
        assertTrue(first.horizontalLength() < before, "the first move after a setback slows instead of accelerating");
        fly(engine, true);
        assertTrue(engine.horizontalBoost() > rewound, "then the ramp climbs again");
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

    @Test void aBoostMaximumAboveTheServerLimitIsHeldToItInThreeDimensions() {
        AdaptiveFlightEngine.Settings tooFast = boosting(24, ServerMoveLimit.SINGLE);
        AdaptiveFlightEngine engine = new AdaptiveFlightEngine(tooFast);
        for (int tick = 0; tick < 1000; tick++) {
            Motion motion = fly(engine, tooFast, true, tick > 500);
            assertTrue(motion.length() <= ServerMoveLimit.SINGLE + EPSILON, "tick " + tick + ": " + motion.length());
        }
    }

    @Test void aBoostCapStopsTheUnchangedCurveInsteadOfTaperingTowardsIt() {
        double cap = 10; // 200 b/s
        AdaptiveFlightEngine.Settings capped = boosting(DEFAULTS.boostTop(), cap, ServerMoveLimit.SINGLE);
        AdaptiveFlightEngine free = new AdaptiveFlightEngine(DEFAULTS), limited = new AdaptiveFlightEngine(capped);
        int reached = -1;
        for (int tick = 0; tick < 400; tick++) {
            fly(free, true);
            Motion motion = fly(limited, capped, true, false);
            assertEquals(Math.min(cap, free.horizontalBoost()), limited.horizontalBoost(), EPSILON,
                "tick " + tick + ": the same acceleration curve, stopped at the cap");
            assertTrue(motion.horizontalLength() <= cap + EPSILON, "tick " + tick + ": " + motion.horizontalLength());
            if (reached < 0 && limited.horizontalBoost() >= cap - EPSILON) reached = tick;
        }
        assertTrue(reached > 0, "reaches the cap");
        assertEquals(cap, limited.output().horizontalLength(), EPSILON, "and holds exactly the cap");
        assertEquals(cap, limited.horizontalBoostCeiling(), EPSILON);
    }

    @Test void aBoostSetbackAtTheCapRewindsAlongTheOriginalCurve() {
        double cap = 10;
        AdaptiveFlightEngine.Settings capped = boosting(DEFAULTS.boostTop(), cap, ServerMoveLimit.SINGLE);
        AdaptiveFlightEngine engine = new AdaptiveFlightEngine(capped);
        for (int tick = 0; tick < 200; tick++) fly(engine, capped, true, false);
        engine.setback(false);
        assertEquals(BoostRamp.rewind(cap, DEFAULTS.boostRampStep(), DEFAULTS.boostTop(), DEFAULTS.boostStart(),
            DEFAULTS.rewindTicks()), engine.horizontalBoost(), EPSILON);
    }

    @Test void cruiseSettlesOnItsCapAndNeverPassesIt() {
        AdaptiveFlightEngine.Settings d = DEFAULTS;
        double cap = 1; // 20 b/s
        AdaptiveFlightEngine.Settings slow = new AdaptiveFlightEngine.Settings(d.acceleration(), d.braking(),
            d.turnRate(), cap, d.sprint(), d.verticalMaximum(), d.verticalCruise(), d.verticalSprint(),
            d.verticalAcceleration(), d.verticalRampStep(), d.verticalReversalTicks(), d.unlimitedBoost(), d.boostTop(), d.boostStart(),
            d.boostRampStep(), d.boostVerticalStart(), d.rewindTicks(), d.setbackHoldTicks(), d.boostCeiling(),
            d.maneuverMode(), d.maneuverCeiling(), d.lookahead(), d.margin(), d.safeManeuvering(), d.closeObjectSpeed(), d.stockHorizontalSpeed(),
            d.stockVerticalSpeed(), d.fallMultiplier());
        AdaptiveFlightEngine engine = new AdaptiveFlightEngine(slow);
        for (int tick = 0; tick < 200; tick++) {
            Motion motion = fly(engine, slow, false, false);
            assertTrue(motion.horizontalLength() <= cap + EPSILON, "tick " + tick + ": " + motion.horizontalLength());
        }
        assertEquals(cap, engine.output().horizontalLength(), 1e-9);
    }

    @Test void verticalBoostStopsAtItsCap() {
        AdaptiveFlightEngine.Settings d = DEFAULTS;
        double cap = 2; // 40 b/s
        AdaptiveFlightEngine.Settings capped = new AdaptiveFlightEngine.Settings(d.acceleration(), d.braking(),
            d.turnRate(), d.cruise(), d.sprint(), d.verticalMaximum(), d.verticalCruise(), cap,
            d.verticalAcceleration(), d.verticalRampStep(), d.verticalReversalTicks(), d.unlimitedBoost(), d.boostTop(), d.boostStart(),
            d.boostRampStep(), d.boostVerticalStart(), d.rewindTicks(), d.setbackHoldTicks(), d.boostCeiling(),
            d.maneuverMode(), d.maneuverCeiling(), d.lookahead(), d.margin(), d.safeManeuvering(), d.closeObjectSpeed(), d.stockHorizontalSpeed(),
            d.stockVerticalSpeed(), d.fallMultiplier());
        AdaptiveFlightEngine engine = new AdaptiveFlightEngine(capped);
        for (int tick = 0; tick < 200; tick++) {
            Motion motion = fly(engine, capped, true, true);
            assertTrue(motion.y() <= cap + EPSILON, "tick " + tick + ": " + motion.y());
        }
        assertEquals(cap, engine.verticalBoost(), EPSILON);
        assertEquals(cap, engine.output().y(), EPSILON);
    }

    @Test void maneuveringHoldsTotalSpeedToTheCapWhileClimbingAndTurning() {
        AdaptiveFlightEngine engine = new AdaptiveFlightEngine(DEFAULTS);
        for (int tick = 0; tick < 400; tick++) fly(engine, true);
        assertTrue(engine.output().length() > 14.9, "boosting at 300 b/s");
        for (int tick = 0; tick < 60; tick++) {
            Motion motion = fly(engine, DEFAULTS, true, true, (tick + 1) * 5f);
            assertTrue(engine.maneuverMode(), "tick " + tick);
            assertTrue(motion.length() <= MANEUVER_CAP + EPSILON, "tick " + tick + ": " + motion.length() * 20);
            assertTrue(Math.hypot(engine.horizontalBoost(), engine.verticalBoost()) <= MANEUVER_CAP + EPSILON,
                "the ramps sit at their share of the cap");
        }
    }

    @Test void climbingWithoutTurningIsNotManeuvering() {
        AdaptiveFlightEngine engine = new AdaptiveFlightEngine(DEFAULTS);
        for (int tick = 0; tick < 400; tick++) fly(engine, true);
        for (int tick = 0; tick < 60; tick++) {
            fly(engine, DEFAULTS, true, true, 0);
            assertFalse(engine.maneuverMode(), "tick " + tick);
        }
        assertTrue(engine.output().length() > MANEUVER_CAP + 1, "boost keeps its speed: " + engine.output().length());
    }

    @Test void afterManeuveringBoostSpeedsUpAgainFromTheCapInsteadOfSnapping() {
        AdaptiveFlightEngine engine = new AdaptiveFlightEngine(DEFAULTS);
        for (int tick = 0; tick < 400; tick++) fly(engine, true);
        float yaw = 0;
        for (int tick = 0; tick < 40; tick++) fly(engine, DEFAULTS, true, true, yaw += 5);
        int held = 0;
        while (engine.maneuverMode() || held == 0) {
            fly(engine, DEFAULTS, true, true, yaw);
            if (engine.maneuverMode()) held++;
            assertTrue(held < 20, "maneuver mode ends shortly after turning stops");
        }
        assertTrue(held >= 8, "but outlasts the turn briefly: " + held);
        double previous = engine.horizontalBoost();
        assertTrue(previous <= MANEUVER_CAP + EPSILON);
        for (int tick = 0; tick < 200; tick++) {
            fly(engine, DEFAULTS, true, false, yaw);
            double ramp = engine.horizontalBoost();
            assertTrue(ramp - previous <= DEFAULTS.boostRampStep() + EPSILON, "tick " + tick + ": climbs along the curve");
            previous = ramp;
        }
        assertTrue(previous > 14, "back near top speed: " + previous);
    }

    @Test void maneuverModeOffLeavesFlightUnchanged() {
        AdaptiveFlightEngine.Settings d = DEFAULTS;
        AdaptiveFlightEngine.Settings off = new AdaptiveFlightEngine.Settings(d.acceleration(), d.braking(),
            d.turnRate(), d.cruise(), d.sprint(), d.verticalMaximum(), d.verticalCruise(), d.verticalSprint(),
            d.verticalAcceleration(), d.verticalRampStep(), d.verticalReversalTicks(), d.unlimitedBoost(), d.boostTop(), d.boostStart(),
            d.boostRampStep(), d.boostVerticalStart(), d.rewindTicks(), d.setbackHoldTicks(), d.boostCeiling(),
            false, d.maneuverCeiling(), d.lookahead(), d.margin(), d.safeManeuvering(), d.closeObjectSpeed(),
            d.stockHorizontalSpeed(), d.stockVerticalSpeed(), d.fallMultiplier());
        AdaptiveFlightEngine engine = new AdaptiveFlightEngine(off);
        for (int tick = 0; tick < 400; tick++) fly(engine, off, true, false, 0);
        for (int tick = 0; tick < 60; tick++) {
            fly(engine, off, true, true, (tick + 1) * 5f);
            assertFalse(engine.maneuverMode());
        }
        assertTrue(engine.output().length() > MANEUVER_CAP + 1, "uncapped: " + engine.output().length());
    }

    /** The defaults with another boost top speed (Sprint capped there too) and total boost ceiling. */
    private static AdaptiveFlightEngine.Settings boosting(double top, double ceiling) {
        return boosting(top, top, ceiling);
    }

    @Test void switchingFromJumpToSneakSlowsTheClimbEvenlyOverTheReversalTicks() {
        for (int ticks : new int[] {0, 2, 5}) {
            AdaptiveFlightEngine.Settings settings = reversing(ticks);
            AdaptiveFlightEngine engine = new AdaptiveFlightEngine(settings);
            for (int tick = 0; tick < 60; tick++) vertical(engine, settings, false, 1);
            double climb = engine.output().y();
            assertTrue(climb > .5, "climbs at cruise speed");

            for (int tick = 1; tick <= ticks; tick++) {
                assertEquals(climb * (ticks + 1 - tick) / (ticks + 1), vertical(engine, settings, false, -1).y(), EPSILON,
                    ticks + " reversal ticks, tick " + tick);
            }
            assertEquals(-.5, vertical(engine, settings, false, -1).y(), EPSILON,
                "then descends from a standstill along the vertical ramp");
        }
    }

    @Test void aBoostedReversalHoldsTheNewDirectionsBoostAtItsStartSpeedUntilTheClimbIsGone() {
        AdaptiveFlightEngine engine = new AdaptiveFlightEngine(DEFAULTS);
        for (int tick = 0; tick < 100; tick++) vertical(engine, DEFAULTS, true, 1);
        double climb = engine.output().y();
        assertTrue(climb > 2.5, "climbs near the 60 b/s vertical boost top: " + climb * 20);

        for (int tick = 1; tick <= 2; tick++) {
            assertEquals(climb * (3 - tick) / 3, vertical(engine, DEFAULTS, true, -1).y(), EPSILON);
            assertEquals(DEFAULTS.boostVerticalStart(), engine.verticalBoost(), EPSILON, "descent boost waits");
        }
        assertEquals(-DEFAULTS.boostVerticalStart(), vertical(engine, DEFAULTS, true, -1).y(), EPSILON,
            "turns around at the boost start speed");
        assertTrue(vertical(engine, DEFAULTS, true, -1).y() < -DEFAULTS.boostVerticalStart(), "then ramps up");
    }

    /** The defaults with another number of vertical reversal ticks. */
    private static AdaptiveFlightEngine.Settings reversing(int ticks) {
        AdaptiveFlightEngine.Settings d = DEFAULTS;
        return new AdaptiveFlightEngine.Settings(d.acceleration(), d.braking(), d.turnRate(), d.cruise(), d.sprint(),
            d.verticalMaximum(), d.verticalCruise(), d.verticalSprint(), d.verticalAcceleration(), d.verticalRampStep(),
            ticks, d.unlimitedBoost(), d.boostTop(), d.boostStart(), d.boostRampStep(), d.boostVerticalStart(),
            d.rewindTicks(), d.setbackHoldTicks(), d.boostCeiling(), d.maneuverMode(), d.maneuverCeiling(),
            d.lookahead(), d.margin(), d.safeManeuvering(), d.closeObjectSpeed(),
            d.stockHorizontalSpeed(), d.stockVerticalSpeed(), d.fallMultiplier());
    }

    /** One client tick of W held facing +Z in open air, with Jump (1) or Sneak (-1). */
    private static Motion vertical(AdaptiveFlightEngine engine, AdaptiveFlightEngine.Settings settings,
                                   boolean sprint, int direction) {
        engine.startTick(settings);
        assertTrue(engine.begin(sprint, true, 1.2, 1.2, BODY, OPEN));
        engine.step(new AdaptiveFlightEngine.Input(1, 0, 0, direction > 0, direction < 0, -.5, BODY, OPEN,
            () -> Double.POSITIVE_INFINITY, null, movement -> movement));
        return engine.output();
    }

    /** The defaults with another boost curve top, horizontal Sprint cap and total boost ceiling. */
    private static AdaptiveFlightEngine.Settings boosting(double top, double cap, double ceiling) {
        AdaptiveFlightEngine.Settings d = DEFAULTS;
        return new AdaptiveFlightEngine.Settings(d.acceleration(), d.braking(), d.turnRate(), d.cruise(), cap,
            d.verticalMaximum(), d.verticalCruise(), d.verticalSprint(), d.verticalAcceleration(), d.verticalRampStep(),
            d.verticalReversalTicks(), d.unlimitedBoost(), top, d.boostStart(), d.boostRampStep(), d.boostVerticalStart(), d.rewindTicks(),
            d.setbackHoldTicks(), ceiling, d.maneuverMode(), d.maneuverCeiling(), d.lookahead(), d.margin(), d.safeManeuvering(), d.closeObjectSpeed(),
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
}
