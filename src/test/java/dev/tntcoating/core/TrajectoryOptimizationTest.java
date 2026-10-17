package dev.tntcoating.core;

import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

class TrajectoryOptimizationTest {
    @Test void unchangedPredictionsAreReusableOnlyInTheExactWorldTickAndCollisionRevision() {
        var prediction = new TrajectoryPrediction<String>();
        Object world = new Object();
        prediction.update(world, 10, 20, "same input", 500, false);
        assertTrue(prediction.matches(world, 10, 20, new String("same input"), 0));
        assertFalse(prediction.matches(new Object(), 10, 20, "same input", 500));
        assertFalse(prediction.matches(world, 11, 20, "same input", 500));
        assertFalse(prediction.matches(world, 10, 21, "same input", 500));
        assertFalse(prediction.matches(world, 10, 20, "new aim/item/motion", 500));
    }

    @Test void clearReleasesScopeAndPreventsReuseAcrossDisableReconnectOrWorldSwitch() {
        var prediction = new TrajectoryPrediction<String>();
        Object world = new Object();
        assertFalse(prediction.matches(world, 1, 1, "held", 500));
        prediction.update(world, 1, 1, "held", 500, false);
        prediction.clear();
        assertFalse(prediction.matches(world, 1, 1, "held", 500));
        prediction.update(world, 1, 1, "held", 500, false);
        assertTrue(prediction.matches(world, 1, 1, "held", 500));
    }

    @Test void truncatedPredictionsRefreshWhenMoreWorkBecomesAvailable() {
        var prediction = new TrajectoryPrediction<String>();
        Object world = new Object();
        prediction.update(world, 1, 2, "held", 100, true);
        assertTrue(prediction.matches(world, 1, 2, "held", 100));
        assertTrue(prediction.matches(world, 1, 2, "held", 20));
        assertFalse(prediction.matches(world, 1, 2, "held", 101));
        prediction.update(world, 1, 2, "held", 200, false);
        assertTrue(prediction.matches(world, 1, 2, "held", 2000));
    }

    @Test void anUnlimitedSimulationCannotExceedTheTotalFrameBudget() {
        var budget = new TrajectoryFrameBudget();
        budget.beginFrame(8000);
        budget.beginCandidate(8000, 1);
        budget.beginPath();
        int steps = 0;
        while (budget.tryStep()) steps++;
        assertEquals(8000, steps);
        assertEquals(0, budget.remaining());
        assertTrue(budget.limited());
        for (int i = 0; i < 100; i++) assertFalse(budget.tryStep());
    }

    @Test void localUnlimitedPathsReserveWorkForOtherEntities() {
        var budget = new TrajectoryFrameBudget();
        budget.beginFrame(8000);
        assertEquals(4000, budget.allowance(true, 5));
        budget.beginCandidate(budget.allowance(true, 5), 1);
        budget.beginPath();
        while (budget.tryStep()) {}
        assertEquals(4000, budget.remaining());
        assertEquals(1000, budget.allowance(false, 4));
    }

    @Test void multipleMultishotPathsShareAnAllowanceAndCannotStarveTheOtherArrows() {
        var budget = new TrajectoryFrameBudget();
        budget.beginFrame(8000);
        budget.beginCandidate(1500, 3);
        for (int path = 0; path < 3; path++) {
            budget.beginPath();
            int steps = 0;
            while (budget.tryStep()) steps++;
            assertEquals(500, steps);
            budget.endPath();
        }
        assertEquals(6500, budget.remaining());
        assertTrue(budget.limited());
    }

    @Test void shortPathsReturnUnusedWorkToTheRemainingMultishotArrows() {
        var budget = new TrajectoryFrameBudget();
        budget.beginFrame(1500);
        budget.beginCandidate(1500, 3);
        budget.beginPath();
        assertTrue(budget.tryStep());
        budget.endPath();
        budget.beginPath();
        int second = 0;
        while (budget.tryStep()) second++;
        budget.endPath();
        budget.beginPath();
        int third = 0;
        while (budget.tryStep()) third++;
        assertEquals(749, second);
        assertEquals(750, third);
        assertEquals(0, budget.remaining());
    }

    @Test void aNewCandidateCannotResetTheGlobalFrameBudget() {
        var budget = new TrajectoryFrameBudget();
        budget.beginFrame(10);
        for (int candidate = 0; candidate < 3; candidate++) {
            budget.beginCandidate(7, 1);
            budget.beginPath();
            while (budget.tryStep()) {}
            budget.endPath();
        }
        assertEquals(0, budget.remaining());
        budget.beginCandidate(1000, 1);
        budget.beginPath();
        assertFalse(budget.tryStep());
    }

    @Test void remoteWorkRotatesAcrossEveryCandidateAndResetsOnLifecycleClear() {
        var budget = new TrajectoryFrameBudget();
        Set<Integer> first = new HashSet<>();
        for (int i = 0; i < 19; i++) first.add(budget.remoteStart(19));
        assertEquals(19, first.size());
        assertEquals(0, budget.remoteStart(19));
        budget.clear();
        assertEquals(0, budget.remoteStart(19));
        assertEquals(0, budget.remaining());
        assertEquals(0, budget.remoteStart(0));
    }

    @Test void everyFreshFrameRestoresExactlyItsConfiguredBudget() {
        var budget = new TrajectoryFrameBudget();
        for (int frame = 0; frame < 5; frame++) {
            budget.beginFrame(128);
            budget.beginCandidate(1000, 1);
            budget.beginPath();
            int count = 0;
            while (budget.tryStep()) count++;
            assertEquals(128, count);
        }
    }
}
