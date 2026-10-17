package dev.tntcoating.core;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class TrailHeadingAvoidanceTest {
    @Test void anUnusedAttemptAllowsEveryHeading() {
        var avoidance = new TrailHeadingAvoidance();
        for (int yaw = -360; yaw <= 360; yaw++) assertFalse(avoidance.blocked(0, 0, yaw));
    }

    @Test void rejectsTheWholeTwentyDegreeSectorIncludingItsBoundaries() {
        var avoidance = new TrailHeadingAvoidance();
        avoidance.reject(0, 0, 60);
        assertTrue(avoidance.blocked(0, 0, 40));
        assertTrue(avoidance.blocked(0, 0, 60));
        assertTrue(avoidance.blocked(0, 0, 80));
        assertFalse(avoidance.blocked(0, 0, 39.9f));
        assertFalse(avoidance.blocked(0, 0, 80.1f));
        assertFalse(avoidance.blocked(0, 0, -60));
    }

    @Test void sectorsWrapCorrectlyAtBothMinecraftYawBoundaries() {
        var avoidance = new TrailHeadingAvoidance();
        avoidance.reject(0, 0, 179);
        assertTrue(avoidance.blocked(0, 0, -180));
        assertTrue(avoidance.blocked(0, 0, -161));
        assertTrue(avoidance.blocked(0, 0, 159));
        assertFalse(avoidance.blocked(0, 0, -160));
        avoidance.clear();
        avoidance.reject(0, 0, -179);
        assertTrue(avoidance.blocked(0, 0, 161));
        assertTrue(avoidance.blocked(0, 0, -159));
        assertFalse(avoidance.blocked(0, 0, 160));
    }

    @Test void sectorsWrapAroundZeroAndNormalizeLargeEquivalentYaws() {
        var avoidance = new TrailHeadingAvoidance();
        avoidance.reject(0, 0, 720);
        assertTrue(avoidance.blocked(0, 0, -20));
        assertTrue(avoidance.blocked(0, 0, 380));
        assertTrue(avoidance.blocked(0, 0, -720));
        assertFalse(avoidance.blocked(0, 0, 21));
        assertFalse(avoidance.blocked(0, 0, -21));
    }

    @Test void multipleFailuresPreserveBothDisjointAndOverlappingSectors() {
        var avoidance = new TrailHeadingAvoidance();
        avoidance.reject(0, 0, 0);
        avoidance.reject(0, 0, 15);
        avoidance.reject(0, 0, 120);
        assertTrue(avoidance.blocked(0, 0, -20));
        assertTrue(avoidance.blocked(0, 0, 35));
        assertTrue(avoidance.blocked(0, 0, 100));
        assertTrue(avoidance.blocked(0, 0, 140));
        assertFalse(avoidance.blocked(0, 0, 60));
    }

    @Test void repeatedNoisyRejectionsCannotEvictAnOlderBadHeading() {
        var avoidance = new TrailHeadingAvoidance();
        avoidance.reject(0, 0, 120);
        for (int i = 0; i < 10_000; i++) avoidance.reject(0, 0, i % 20);
        assertTrue(avoidance.blocked(0, 0, 120));
        assertFalse(avoidance.blocked(0, 0, -100));
    }

    @Test void movementExpiresTheAttemptAt128BlocksFromTheFirstRejection() {
        var avoidance = new TrailHeadingAvoidance();
        avoidance.reject(10, 20, 0);
        assertTrue(avoidance.blocked(137.9, 20, 0));
        avoidance.reject(130, 20, 90); // Another failure must not move the attempt origin.
        assertFalse(avoidance.blocked(138, 20, 0));
        assertFalse(avoidance.blocked(138, 20, 90));
        assertFalse(avoidance.blocked(10, 20, 0), "returning starts a new local attempt");
    }

    @Test void aRejectionAfterLeavingTheAttemptStartsAFreshScope() {
        var avoidance = new TrailHeadingAvoidance();
        avoidance.reject(0, 0, 0);
        avoidance.reject(128, 0, 90);
        assertFalse(avoidance.blocked(128, 0, 0));
        assertTrue(avoidance.blocked(128, 0, 90));
    }

    @Test void clearImmediatelyReleasesAllHeadings() {
        var avoidance = new TrailHeadingAvoidance();
        for (int yaw = 0; yaw < 360; yaw += 20) avoidance.reject(0, 0, yaw);
        for (int yaw = 0; yaw < 360; yaw++) assertTrue(avoidance.blocked(0, 0, yaw));
        avoidance.clear();
        for (int yaw = 0; yaw < 360; yaw++) assertFalse(avoidance.blocked(0, 0, yaw));
    }

    @Test void invalidPositionsAndYawsAreRejected() {
        var avoidance = new TrailHeadingAvoidance();
        assertThrows(IllegalArgumentException.class, () -> avoidance.reject(Double.NaN, 0, 0));
        assertThrows(IllegalArgumentException.class, () -> avoidance.reject(0, 0, Float.POSITIVE_INFINITY));
        assertThrows(IllegalArgumentException.class, () -> avoidance.blocked(0, Double.NEGATIVE_INFINITY, 0));
        assertThrows(IllegalArgumentException.class, () -> avoidance.blocked(0, 0, Float.NaN));
    }
}
