package dev.tntcoating.core;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class TrailLineTest {
    private static final float EAST = -90;

    @Test void fitsStraightTrailsAtObliqueAnglesAndAcrossYawWrap() {
        for (float yaw : new float[] {-179, -135, -90, -31, 0, 47, 93, 179}) {
            TrailLine line = new TrailLine();
            double dx = -Math.sin(Math.toRadians(yaw));
            double dz = Math.cos(Math.toRadians(yaw));
            for (int i = 0; i < 16; i++) line.observePoint(100 + dx * i * 16, -200 + dz * i * 16);
            var result = line.estimate(100, -200, yaw);
            assertAngle(yaw, result.axisYaw(), 0.001);
            assertAngle(yaw, result.yaw(), 0.001);
            assertTrue(result.confidence() > 0.9);
            assertTrue((result.targetX() - 100) * dx + (result.targetZ() + 200) * dz > 90);
        }
    }

    @Test void forwardSpatialCoverageOutweighsOrderAndNumberOfChunkArrivals() {
        TrailLine line = eastLine();
        for (int i = 0; i < 64; i++) line.observePoint(16 + i % 4 * 16, 80 + i / 4 * 16);
        var estimate = line.estimate(48, 8, EAST);
        assertAngle(EAST, estimate.axisYaw(), 0.001);
        assertAngle(EAST, estimate.yaw(), 0.001);
    }

    @Test void keepsStraightBranchEvenWhenTheForkContainsMoreChunks() {
        TrailLine line = eastLine();
        for (int i = 1; i <= 70; i++) line.observePoint(48 + i * 3, 8 + i * 3);
        var estimate = line.estimate(48, 8, EAST);
        assertAngle(EAST, estimate.axisYaw(), 0.001);
        assertAngle(EAST, estimate.yaw(), 0.001);
        assertTrue(estimate.confidence() > 0.5);
    }

    @Test void aParallelTrailOneHundredBlocksAwayCannotPullTheLineSideways() {
        TrailLine line = eastLine();
        for (int i = 0; i < 100; i++) line.observePoint(8 + i * 16, 108);
        var estimate = line.estimate(32, 8, EAST);
        assertAngle(EAST, estimate.axisYaw(), 0.001);
        assertAngle(EAST, estimate.yaw(), 0.001);
        assertEquals(8, estimate.targetZ(), 1e-5);
    }

    @Test void lateRenderLoadsBehindCannotReverseTheFit() {
        TrailLine line = eastLine();
        for (int i = 0; i < 96; i++) line.observePoint(-16 - i * 16, 100 + i % 7 * 16);
        var estimate = line.estimate(96, 8, EAST);
        assertAngle(EAST, estimate.axisYaw(), 0.001);
        assertTrue(estimate.targetX() > 96);
    }

    @Test void turningTheCameraCannotReverseAnEstablishedDirection() {
        TrailLine line = eastLine();
        var estimate = line.estimate(32, 8, 90);
        assertAngle(EAST, estimate.axisYaw(), 0.001);
        assertTrue(estimate.targetX() > 32);
    }

    @Test void lateralCorrectionPointsBackTowardLineAndIsBounded() {
        TrailLine line = eastLine();
        var north = line.estimate(64, -92, EAST);
        assertTrue(north.targetZ() > -92);
        assertAngle(EAST + 12, north.yaw(), 0.001);
        var south = line.estimate(64, 108, EAST);
        assertTrue(south.targetZ() < 108);
        assertAngle(EAST - 12, south.yaw(), 0.001);
    }

    @Test void aGapKeepsCommittedDirectionWhenThereIsNoNewEvidence() {
        TrailLine line = eastLine();
        var estimate = line.estimate(1_000, 8, 90);
        assertAngle(EAST, estimate.yaw(), 0.001);
        assertEquals(0, estimate.confidence(), 1e-8);
    }

    @Test void oneSidedNoiseDoesNotRepeatedlyChangeDirectionAtTheSamePosition() {
        TrailLine line = eastLine();
        for (int batch = 0; batch < 10; batch++) {
            for (int i = 0; i < 20; i++) line.observePoint(16 + i * 16, 60 + batch * 16 + i);
            assertAngle(EAST, line.estimate(0, 8, EAST).axisYaw(), 0.001);
        }
    }

    @Test void confirmsAShallowBendAfterForwardProgressWhenStraightSupportEnds() {
        TrailLine line = eastLine();
        double radians = Math.toRadians(10);
        for (int i = 0; i < 16; i++)
            line.observePoint(400 + Math.cos(radians) * i * 16, 48 + Math.sin(radians) * i * 16);
        var estimate = line.estimate(400, 48, EAST);
        assertTrue(estimate.axisYaw() > EAST + 5);
        assertTrue(estimate.axisYaw() <= EAST + 12.001);
        assertTrue(estimate.targetX() > 400);
    }

    @Test void tooLittleEvidenceHoldsDirectionInsteadOfChasingASingleChunk() {
        TrailLine line = new TrailLine();
        line.observe(0, 4);
        var estimate = line.estimate(0, 0, EAST);
        assertAngle(EAST, estimate.yaw(), 0.001);
        assertEquals(0, estimate.confidence(), 1e-8);
    }

    @Test void resetAllowsAnUnrelatedDirectionAndDropsOldEvidence() {
        TrailLine line = eastLine();
        line.reset();
        var estimate = line.estimate(0, 0, 90);
        assertAngle(90, estimate.yaw(), 0.001);
        assertEquals(0, estimate.supportingChunks());
    }

    @Test void duplicateChunksCannotManufactureConfidence() {
        TrailLine line = new TrailLine();
        for (int i = 0; i < 128; i++) line.observe(1, 0);
        var estimate = line.estimate(0, 8, EAST);
        assertEquals(1, estimate.supportingChunks());
        assertEquals(0, estimate.confidence(), 1e-8);
    }

    @Test void validatesInputsAndConfiguration() {
        assertThrows(IllegalArgumentException.class, () -> new TrailLine.Config(1, 24, 96, 12, 64, 12));
        assertThrows(IllegalArgumentException.class, () -> new TrailLine.Config(128, 0, 96, 12, 64, 12));
        assertThrows(IllegalArgumentException.class, () -> new TrailLine.Config(128, 24, 96, 90, 64, 12));
        TrailLine line = new TrailLine();
        assertThrows(IllegalArgumentException.class, () -> line.observePoint(Double.NaN, 0));
        assertThrows(IllegalArgumentException.class, () -> line.estimate(0, 0, Float.NaN));
    }

    private static TrailLine eastLine() {
        TrailLine line = new TrailLine();
        for (int i = 0; i < 20; i++) line.observe(i, 0);
        var estimate = line.estimate(0, 8, EAST);
        assertAngle(EAST, estimate.axisYaw(), 0.001);
        return line;
    }

    private static void assertAngle(double expected, double actual, double tolerance) {
        assertEquals(0, Math.IEEEremainder(actual - expected, 360), tolerance,
            "expected heading " + expected + ", got " + actual);
    }
}
