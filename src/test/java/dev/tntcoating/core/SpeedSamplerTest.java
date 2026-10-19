package dev.tntcoating.core;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class SpeedSamplerTest {
    private static final long TICK = 50_000_000L;

    @Test void measuresBlocksPerSecondFromWallClockTime() {
        var sampler = new SpeedSampler();
        for (int tick = 0; tick <= 10; tick++) sampler.sample(tick * .5, 64, 0, false, tick * TICK, 1);
        assertEquals(10, sampler.speed(), 1e-9);
    }

    @Test void timerSpeedIsReportedAsRealSpeed() {
        // Timer 2x: the same distance per tick, but ticks arrive twice as often.
        var sampler = new SpeedSampler();
        for (int tick = 0; tick <= 10; tick++) sampler.sample(tick * .5, 64, 0, false, tick * TICK / 2, 1);
        assertEquals(20, sampler.speed(), 1e-9);
    }

    @Test void teleportsAndPausesDoNotSpikeTheReading() {
        var sampler = new SpeedSampler();
        sampler.sample(0, 64, 0, false, 0, 1);
        sampler.sample(.5, 64, 0, false, TICK, 1);
        assertEquals(10, sampler.speed(), 1e-9);
        sampler.sample(5_000, 64, 0, false, 2 * TICK, 1);
        assertEquals(10, sampler.speed(), 1e-9, "a /home teleport is not movement");
        sampler.sample(5_000.5, 64, 0, false, 3 * TICK, 1);
        assertEquals(10, sampler.speed(), 1e-9, "sampling restarts from the new position");
        sampler.sample(5_010, 64, 0, false, 3 * TICK + 2_000_000_000L, 1);
        assertEquals(10, sampler.speed(), 1e-9, "a two-second pause is not averaged in");
    }

    @Test void verticalMovementIsOptionalAndSmoothingBlendsSamples() {
        var vertical = new SpeedSampler();
        vertical.sample(0, 0, 0, true, 0, 1);
        vertical.sample(0, 1, 0, true, TICK, 1);
        assertEquals(20, vertical.speed(), 1e-9);
        var horizontal = new SpeedSampler();
        horizontal.sample(0, 0, 0, false, 0, 1);
        horizontal.sample(0, 1, 0, false, TICK, 1);
        assertEquals(0, horizontal.speed(), 1e-9);

        var smoothed = new SpeedSampler();
        smoothed.sample(0, 0, 0, false, 0, .25);
        smoothed.sample(1, 0, 0, false, TICK, .25);
        assertEquals(5, smoothed.speed(), 1e-9);
    }

    @Test void repeatedInstantsAndInvalidPositionsAreIgnoredSafely() {
        var sampler = new SpeedSampler();
        sampler.sample(0, 0, 0, false, TICK, 1);
        sampler.sample(1, 0, 0, false, TICK, 1);
        assertEquals(0, sampler.speed());
        sampler.sample(Double.NaN, 0, 0, false, 2 * TICK, 1);
        assertEquals(0, sampler.speed());
        sampler.sample(0, 0, 0, false, 3 * TICK, 1);
        sampler.sample(1, 0, 0, false, 4 * TICK, 1);
        assertEquals(20, sampler.speed(), 1e-9);
    }
}
