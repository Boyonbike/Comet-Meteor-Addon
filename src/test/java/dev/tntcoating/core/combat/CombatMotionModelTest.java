/*
 * Original Comet implementation by Boyonabike.
 * Independent: written from the pinned 26.2 artifact and Meteor build 35 behaviour; no third-party code.
 * SPDX-License-Identifier: CC0-1.0
 */

package dev.tntcoating.core.combat;

import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CombatMotionModelTest {
    private static final double EPS = 1.0e-9;
    private static final CombatMotionModel MODEL = new CombatMotionModel();

    private static CollisionView view(Set<Cell> solid) {
        return solid::contains;
    }

    private static Set<Cell> cells(Cell... cells) {
        return new HashSet<>(Set.of(cells));
    }

    @Test
    void zeroTicksIsStationarityAtTheSample() {
        PredictedBox predicted = MODEL.predict(MotionSample.of(0.5, 10.0, 0.5, 0.0, 0.0, 0.0, false),
            0, CollisionView.open());
        assertEquals(Confidence.STRONG, predicted.confidence());
        assertEquals(0.5, predicted.hull().min().x(), EPS);
        assertEquals(10.0, predicted.hull().max().y(), EPS);
    }

    @Test
    void freeFallIsThreeDimensionalAndContainsThePath() {
        PredictedBox one = MODEL.predict(MotionSample.of(0.5, 10.0, 0.5, 0.0, 0.0, 0.0, false),
            1, CollisionView.open());
        assertEquals(9.92, one.hull().min().y(), EPS);
        assertEquals(10.0, one.hull().max().y(), EPS);
        assertEquals(Confidence.STRONG, one.confidence());
        assertTrue(one.contains(new Vec(0.5, 10.0, 0.5)));
        assertTrue(one.contains(new Vec(0.5, 9.92, 0.5)));

        PredictedBox two = MODEL.predict(MotionSample.of(0.5, 10.0, 0.5, 0.0, 0.0, 0.0, false),
            2, CollisionView.open());
        assertEquals(10.0 - 0.08 - 0.16, two.hull().min().y(), EPS);
    }

    @Test
    void horizontalMotionExpandsTheHullInXAndZ() {
        PredictedBox predicted = MODEL.predict(
            MotionSample.of(0.5, 10.0, 0.5, 0.5, 0.0, -0.25, false), 2, CollisionView.open());
        assertEquals(1.5, predicted.hull().max().x(), EPS);
        assertEquals(0.0, predicted.hull().min().z(), EPS);
        assertEquals(0.5, predicted.hull().min().x(), EPS);
    }

    @Test
    void aWallStopsTheAxisAndLowersConfidence() {
        // Player occupies block cells x=0 while at x=0.5; a solid cell x=1 blocks +x motion.
        CollisionView wall = view(cells(new Cell(1, 10, 0), new Cell(1, 11, 0)));
        PredictedBox predicted = MODEL.predict(
            MotionSample.of(0.5, 10.0, 0.5, 1.0, 0.0, 0.0, false), 1, wall);
        assertEquals(0.5, predicted.hull().max().x(), EPS);
        assertEquals(Confidence.WEAK, predicted.confidence());
    }

    @Test
    void slidingMovesAlongTheUnblockedAxis() {
        CollisionView wall = view(cells(new Cell(1, 10, 0), new Cell(1, 11, 0)));
        PredictedBox predicted = MODEL.predict(
            MotionSample.of(0.5, 10.0, 0.5, 1.0, 0.0, 0.5, false), 2, wall);
        assertEquals(0.5, predicted.hull().max().x(), EPS);
        assertEquals(1.5, predicted.hull().max().z(), EPS);
    }

    @Test
    void landingOnTheFloorStopsVerticalMotionAndLowersConfidence() {
        Set<Cell> solid = new HashSet<>();
        for (int x = -1; x <= 1; x++) {
            for (int z = -1; z <= 1; z++) solid.add(new Cell(x, 0, z));
        }
        PredictedBox predicted = MODEL.predict(
            MotionSample.of(0.5, 1.0, 0.5, 0.0, 0.0, 0.0, false), 2, view(solid));
        assertEquals(1.0, predicted.hull().min().y(), EPS);
        assertEquals(1.0, predicted.hull().max().y(), EPS);
        assertEquals(Confidence.LOST, predicted.confidence(),
            "two collisions plus grounding exceed the weak threshold");
    }

    @Test
    void upwardEscapeLeavesTheHole() {
        Set<Cell> solid = new HashSet<>();
        for (int x = -1; x <= 1; x++) {
            for (int z = -1; z <= 1; z++) solid.add(new Cell(x, 0, z));
        }
        PredictedBox predicted = MODEL.predict(
            MotionSample.of(0.5, 1.0, 0.5, 0.0, 0.42, 0.0, true), 2, view(solid));
        assertTrue(predicted.hull().max().y() > 1.5, "the model must predict vertical escape");
        assertEquals(Confidence.STRONG, predicted.confidence());
    }

    @Test
    void directionReversalLowersConfidenceEvenWithoutACollision() {
        // Upward knockback with no obstacle: the model must predict the apex and the fall, but the exact
        // tick of the reversal depends on the knockback and tick phase, so confidence cannot stay strong.
        PredictedBox predicted = MODEL.predict(
            MotionSample.of(0.5, 10.0, 0.5, 0.0, 0.1, 0.0, false), 4, CollisionView.open());
        assertEquals(Confidence.WEAK, predicted.confidence());
        assertTrue(predicted.hull().max().y() > 10.0, "the rise must be predicted");
        assertTrue(predicted.hull().min().y() < predicted.hull().max().y(),
            "the fall after the reversal must be predicted too");
    }

    @Test
    void aCeilingStopsTheEscape() {
        Set<Cell> solid = new HashSet<>();
        for (int x = -1; x <= 1; x++) {
            for (int z = -1; z <= 1; z++) solid.add(new Cell(x, 2, z));
        }
        PredictedBox predicted = MODEL.predict(
            MotionSample.of(0.5, 1.0, 0.5, 0.0, 0.42, 0.0, false), 4, view(solid));
        assertTrue(predicted.hull().max().y() <= 2.0);
    }

    @Test
    void aHoleWithWallsLosesConfidence() {
        Set<Cell> solid = new HashSet<>();
        for (int x = -1; x <= 1; x++) {
            for (int z = -1; z <= 1; z++) {
                solid.add(new Cell(x, 0, z));
                if (x == 1 || z == 1) {
                    solid.add(new Cell(x, 1, z));
                    solid.add(new Cell(x, 2, z));
                }
            }
        }
        PredictedBox predicted = MODEL.predict(
            MotionSample.of(0.5, 1.0, 0.5, 1.0, 0.0, 1.0, false), 2, view(solid));
        assertEquals(Confidence.LOST, predicted.confidence());
    }

    @Test
    void beyondTheCapConfidenceIsLost() {
        PredictedBox predicted = MODEL.predict(MotionSample.of(0.5, 10.0, 0.5, 1.0, 1.0, 1.0, false),
            CombatMotionModel.MAX_TICKS + 1, CollisionView.open());
        assertEquals(Confidence.LOST, predicted.confidence());
        assertTrue(predicted.contains(new Vec(0.5, 10.0, 0.5)));
        assertFalse(predicted.contains(new Vec(1.5, 10.0, 0.5)));
    }

    @Test
    void teleportOrSetbackInvalidatesThePrediction() {
        PredictedBox predicted = MODEL.predict(
            MotionSample.of(0.5, 10.0, 0.5, 1.0, 1.0, 1.0, false).invalidated(), 2,
            CollisionView.open());
        assertEquals(Confidence.LOST, predicted.confidence());
    }

    @Test
    void negativeTicksAreRejected() {
        assertThrows(IllegalArgumentException.class, () -> MODEL.predict(
            MotionSample.of(0.5, 10.0, 0.5, 0.0, 0.0, 0.0, false), -1, CollisionView.open()));
    }
}
