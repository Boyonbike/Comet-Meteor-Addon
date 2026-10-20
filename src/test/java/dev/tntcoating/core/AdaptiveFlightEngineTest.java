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

    @Test void climbingWhileBoostingNeverTakesTotalSpeedPastTheChosenMaximum() {
        AdaptiveFlightEngine.Settings capped = boosting(10, 10); // a 200 b/s maximum, the 6b6t-safe speed
        AdaptiveFlightEngine engine = new AdaptiveFlightEngine(capped);
        for (int tick = 0; tick < 600; tick++) {
            Motion motion = fly(engine, capped, true, tick > 300);
            assertTrue(motion.length() <= 10 + EPSILON, "tick " + tick + ": " + motion.length() * 20 + " b/s");
            if (tick > 320) assertTrue(motion.y() > 2, "still climbing, by trading some horizontal speed");
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

    /** The defaults with another boost top speed and total boost ceiling. */
    private static AdaptiveFlightEngine.Settings boosting(double top, double ceiling) {
        AdaptiveFlightEngine.Settings d = DEFAULTS;
        return new AdaptiveFlightEngine.Settings(d.acceleration(), d.braking(), d.turnRate(), d.cruise(), d.sprint(),
            d.verticalMaximum(), d.verticalCruise(), d.verticalSprint(), d.verticalAcceleration(), d.verticalRampStep(),
            d.unlimitedBoost(), top, d.boostStart(), d.boostRampStep(), d.boostVerticalStart(), d.rewindTicks(),
            d.setbackHoldTicks(), ceiling, d.lookahead(), d.margin(), d.safeManeuvering(), d.closeObjectSpeed(),
            d.stockHorizontalSpeed(), d.stockVerticalSpeed(), d.fallMultiplier());
    }

    /** One client tick of W held (and Sprint when boosting), facing +Z in open air. */
    private static Motion fly(AdaptiveFlightEngine engine, boolean sprint) {
        return fly(engine, DEFAULTS, sprint, false);
    }

    private static Motion fly(AdaptiveFlightEngine engine, AdaptiveFlightEngine.Settings settings,
                              boolean sprint, boolean jump) {
        engine.startTick(settings);
        assertTrue(engine.begin(sprint, true, 1.2, 1.2, BODY, OPEN));
        engine.step(new AdaptiveFlightEngine.Input(1, 0, 0, jump, false, -.5, BODY, OPEN,
            () -> Double.POSITIVE_INFINITY, null, movement -> movement));
        return engine.output();
    }
}
