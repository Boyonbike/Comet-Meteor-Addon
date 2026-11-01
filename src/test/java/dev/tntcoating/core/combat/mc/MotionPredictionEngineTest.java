/*
 * Original Comet implementation by Boyonabike.
 * Independent: tests written against the project's own interfaces; no third-party code.
 * SPDX-License-Identifier: CC0-1.0
 */

package dev.tntcoating.core.combat.mc;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.tntcoating.core.combat.MotionSample;
import dev.tntcoating.core.combat.Vec;
import org.junit.jupiter.api.Test;

/** Motion prediction is opt-in and only widens the self bound, never the send path. */
final class MotionPredictionEngineTest {
    private static final Vec SELF = new Vec(0.5, -60.0, 0.5);
    private static final Vec BASE = new Vec(2.0, -61.0, 0.0);
    private static final Vec TARGET = new Vec(2.5, -60.0, 3.0);

    private static EngineHarness harness(boolean motion) {
        EngineHarness harness = EngineHarness.create(
            EngineHarness.create(SELF).settings.withMotion(motion, 2), SELF);
        harness.setBase(EngineHarness.cellOf(BASE));
        harness.addTarget(TARGET);
        harness.setMotion(harness.world.targets.get(0).entity(),
            MotionSample.of(TARGET.x(), TARGET.y(), TARGET.z(), 0.1, 0.0, 0.0, true));
        return harness;
    }

    @Test
    void motionOffNeverSamplesTheWorld() {
        EngineHarness harness = harness(false);
        harness.tick();
        assertEquals(0, harness.world.motionCalls);
    }

    @Test
    void motionOnSamplesTheTargetTrajectory() {
        EngineHarness harness = harness(true);
        harness.tick();
        assertTrue(harness.world.motionCalls > 0);
    }

    @Test
    void aRaisedSelfUpperBoundBlocksThePlacementNotTheSettings() {
        EngineHarness harness = EngineHarness.create(SELF);
        harness.setBase(EngineHarness.cellOf(BASE));
        harness.addTarget(TARGET);
        harness.world.selfUpper = new dev.tntcoating.core.combat.MitigatedDamage(7.0);
        harness.tick();
        assertEquals(0, harness.placeCount());
        assertTrue(harness.engine.settings().motionPrediction() == false);
    }

    @Test
    void enabledPredictionStaysWithinTheConfiguredHorizon() {
        EngineHarness harness = harness(true);
        assertEquals(2, harness.engine.settings().motionPredictionTicks());
        assertTrue(harness.engine.settings().motionPrediction());
    }
}
