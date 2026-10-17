package dev.tntcoating.core;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class AreaSpiralPathTest {
    @Test void startsExactlyAtTheCenterAndPreviewDoesNotCountAsTravel() {
        var path = new AreaSpiralPath(-1_234.5, 678.25, 0, true, 256, 1_024);
        assertEquals(new AreaSpiralPath.Point(-1_234.5, 678.25), path.target(-1_234.5, 678.25, 0));
        assertNotEquals(path.center(), path.target(-1_234.5, 678.25, 48));
        assertEquals(0, path.radius());
        assertEquals(0, path.turns());
        assertFalse(path.finished());
    }

    @Test void approachesTheCenterBeforeBeginningTheSpiral() {
        var path = new AreaSpiralPath(-50, -100, -90, false, 256, 512);
        assertEquals(path.center(), path.target(200, 300, 48));
        assertEquals(path.center(), path.target(-50, -120, 48));
        assertEquals(0, path.radius());
        var departure = path.target(-50, -100, 12);
        assertTrue(departure.x() > -50);
        assertEquals(0, path.radius());
    }

    @Test void minecraftYawAndMapRotationChooseTheDepartureDirection() {
        var clockwise = new AreaSpiralPath(0, 0, 0, true, 256, 512).target(0, 0, 2);
        var counterclockwise = new AreaSpiralPath(0, 0, 0, false, 256, 512).target(0, 0, 2);
        var east = new AreaSpiralPath(0, 0, -90, true, 256, 512).target(0, 0, 2);
        assertTrue(clockwise.z() > 1.9 && clockwise.x() < 0);
        assertTrue(counterclockwise.z() > 1.9 && counterclockwise.x() > 0);
        assertTrue(east.x() > 1.9 && east.z() > 0);
        assertEquals(-clockwise.x(), counterclockwise.x(), 1e-10);
    }

    @Test void spacingIsTheRadialDistanceBetweenCompleteRevolutions() {
        for (int revolution = 1; revolution <= 4; revolution++) {
            var path = new AreaSpiralPath(-200, -300, 0, true, 128, revolution * 128);
            assertEquals(-200, path.endpoint().x(), 1e-8);
            assertEquals(-300 + revolution * 128, path.endpoint().z(), 1e-8);
        }
        var quarter = new AreaSpiralPath(-200, -300, 0, true, 128, 32);
        assertEquals(-232, quarter.endpoint().x(), 1e-8);
        assertEquals(-300, quarter.endpoint().z(), 1e-8);
        var half = new AreaSpiralPath(0, 0, 0, false, 128, 64);
        assertEquals(0, half.endpoint().x(), 1e-8);
        assertEquals(-64, half.endpoint().z(), 1e-8);
    }

    @Test void lookingAheadToTheEndCannotCompleteOrAdvanceAStationaryRoute() {
        var path = new AreaSpiralPath(0, 0, 0, true, 256, 512);
        for (int i = 0; i < 200; i++) assertEquals(path.endpoint(), path.target(0, 0, Double.MAX_VALUE));
        assertEquals(0, path.radius());
        assertFalse(path.finished());
    }

    @Test void crossingLaterRingsDoesNotSkipTheOrderedRoute() {
        var path = new AreaSpiralPath(0, 0, 0, true, 256, 1_024);
        path.target(0, 0, 24);
        path.target(path.endpoint().x(), path.endpoint().z(), 48);
        assertTrue(path.radius() < 64, "A straight outward flight must not skip complete revolutions");
        assertFalse(path.finished());
        double before = path.radius();
        for (int i = 0; i < 100; i++) path.target(0, 1_024, 10_000);
        assertEquals(before, path.radius());
    }

    @Test void aHighSpeedSweptPositionPassesSeveralRouteSegments() {
        var path = new AreaSpiralPath(0, 0, 0, true, 2_048, 512);
        path.target(0, 0, 24);
        double angle = .15;
        double radius = 2_048 * angle / (Math.PI * 2);
        path.target(-Math.sin(angle) * radius, Math.cos(angle) * radius, 24);
        assertTrue(path.radius() > 40, "Swept motion must pass route points missed between ticks");
        assertFalse(path.finished());
    }

    @Test void pausingDiscardsUnobservedMovementAndResumesTheSameRoute() {
        for (boolean clockwise : new boolean[] { true, false }) {
            for (boolean largeDetour : new boolean[] { false, true }) {
                var path = new AreaSpiralPath(0, 0, 0, clockwise, 2_048, 512);
                path.target(0, 0, 24);
                double direction = clockwise ? -1 : 1;
                double angle = .15;
                double radius = 2_048 * angle / (Math.PI * 2);
                double x = direction * Math.sin(angle) * radius;
                double z = Math.cos(angle) * radius;
                path.target(x, z, 24);
                var checkpoint = path.target(x, z, 0);
                double travelled = path.distanceTravelled();
                double progressRadius = path.radius();
                var center = path.center();
                var endpoint = path.endpoint();
                assertTrue(travelled > 0);
                assertNull(path.rejoinTarget());

                path.resetObservation();
                assertEquals(checkpoint, path.rejoinTarget());
                // Both forward drift and a far detour must return to the saved point without counting paused movement.
                angle = .45;
                radius = 2_048 * angle / (Math.PI * 2);
                x = largeDetour ? direction * 2_000 : direction * Math.sin(angle) * radius;
                z = largeDetour ? -1_500 : Math.cos(angle) * radius;
                assertEquals(checkpoint, path.target(x, z, 24));
                assertEquals(travelled, path.distanceTravelled());
                assertEquals(progressRadius, path.radius());
                assertEquals(center, path.center());
                assertEquals(endpoint, path.endpoint());
                assertFalse(path.finished());

                continueToEnd(path, 0, 2_048, 512, 3, 10, x, z);
                assertNull(path.rejoinTarget());
            }
        }
    }

    @Test void passingTheCenterWhilePausedDoesNotStartTheSpiral() {
        var path = new AreaSpiralPath(-50, -100, 0, true, 256, 512);
        assertEquals(path.center(), path.rejoinTarget());
        assertEquals(path.center(), path.target(-50, -200, 24));
        path.resetObservation();
        // The unobserved movement crosses the centre, but resuming must still approach it.
        assertEquals(path.center(), path.target(-50, 0, 24));
        assertEquals(0, path.distanceTravelled());
        assertEquals(0, path.radius());
        assertFalse(path.finished());
        assertEquals(path.center(), path.rejoinTarget());

        continueToEnd(path, 0, 256, 512, 3, 10, -50, 0);
        assertNull(path.rejoinTarget());
    }

    @Test void resumingWithinPilotStoppingDistanceKeepsProgressUntilThePlayerMoves() {
        var path = new AreaSpiralPath(0, 0, 0, true, 2_048, 512);
        path.target(0, 0, 24);
        double angle = .15;
        double radius = 2_048 * angle / (Math.PI * 2);
        double x = -Math.sin(angle) * radius;
        double z = Math.cos(angle) * radius;
        var preview = path.target(x, z, 24);
        var checkpoint = path.target(x, z, 0);
        double travelled = path.distanceTravelled();

        path.resetObservation();
        x = checkpoint.x() + 3;
        z = checkpoint.z();
        for (int i = 0; i < 20; i++) {
            assertEquals(preview, path.target(x, z, 24));
            assertEquals(travelled, path.distanceTravelled());
            assertNull(path.rejoinTarget());
        }
        continueToEnd(path, 0, 2_048, 512, 3, 10, x, z);
    }

    @Test void returningToTheSavedCheckpointDoesNotCountAsSpiralTravel() {
        var path = new AreaSpiralPath(0, 0, 0, true, 2_048, 512);
        path.target(0, 0, 24);
        double angle = .15;
        double radius = 2_048 * angle / (Math.PI * 2);
        double x = -Math.sin(angle) * radius;
        double z = Math.cos(angle) * radius;
        path.target(x, z, 24);
        var checkpoint = path.target(x, z, 0);
        double travelled = path.distanceTravelled();

        path.resetObservation();
        path.target(2_000, -1_500, 24);
        assertEquals(checkpoint, path.target(checkpoint.x() + 10, checkpoint.z(), 24));
        assertEquals(travelled, path.distanceTravelled());
        path.target(checkpoint.x(), checkpoint.z(), 24);
        assertNull(path.rejoinTarget());
        assertEquals(travelled, path.distanceTravelled());

        angle = .3;
        radius = 2_048 * angle / (Math.PI * 2);
        path.target(-Math.sin(angle) * radius, Math.cos(angle) * radius, 24);
        assertTrue(path.distanceTravelled() > travelled);
    }

    @Test void boundedTurnForwardFlightsCompleteInEitherDirection() {
        for (boolean clockwise : new boolean[] { true, false }) {
            for (double speed : new double[] { 1, 3, 8 }) {
                flyToEnd(-123.5, 987.25, -90, clockwise, 256, 512, speed, 10);
            }
        }
    }

    @Test void aRadiusSmallerThanTheSpacingEndsAtItsActualBoundary() {
        var path = flyToEnd(0, 0, 0, true, 256, 32, 1, 10);
        assertEquals(32, path.radius(), 1e-10);
        assertEquals(.125, path.turns(), 1e-10);
        assertEquals(32, Math.hypot(path.endpoint().x(), path.endpoint().z()), 1e-10);
    }

    @Test void aCustomCenterApproachWorksWhenThePilotStopsWithinFourBlocks() {
        for (double speed : new double[] { 1, 3 }) {
            flyToEnd(-500, -900, 0, true, 256, 512, speed, 10, -600, -1_000);
        }
    }

    @Test void aLargeAreaDoesNotRequirePrecomputingTheWholeRoute() {
        var path = new AreaSpiralPath(0, 0, 0, true, 1, 30_000_000);
        var target = path.target(0, 0, 32);
        assertTrue(Double.isFinite(target.x()) && Double.isFinite(target.z()));
        assertEquals(0, path.radius());
        assertFalse(path.finished());
    }

    @Test void validatesCoordinatesDimensionsAndObservations() {
        for (double invalid : new double[] { Double.NaN, Double.NEGATIVE_INFINITY, Double.POSITIVE_INFINITY }) {
            assertThrows(IllegalArgumentException.class, () -> new AreaSpiralPath(invalid, 0, 0, true, 256, 512));
            assertThrows(IllegalArgumentException.class, () -> new AreaSpiralPath(0, invalid, 0, true, 256, 512));
            assertThrows(IllegalArgumentException.class, () -> new AreaSpiralPath(0, 0, invalid, true, 256, 512));
            assertThrows(IllegalArgumentException.class, () -> new AreaSpiralPath(0, 0, 0, true, invalid, 512));
            assertThrows(IllegalArgumentException.class, () -> new AreaSpiralPath(0, 0, 0, true, 256, invalid));
        }
        for (double invalid : new double[] { 0, -1 }) {
            assertThrows(IllegalArgumentException.class, () -> new AreaSpiralPath(0, 0, 0, true, invalid, 512));
            assertThrows(IllegalArgumentException.class, () -> new AreaSpiralPath(0, 0, 0, true, 256, invalid));
        }
        var path = new AreaSpiralPath(0, 0, 0, true, 256, 512);
        assertThrows(IllegalArgumentException.class, () -> path.target(Double.NaN, 0, 24));
        assertThrows(IllegalArgumentException.class, () -> path.target(0, Double.POSITIVE_INFINITY, 24));
        assertThrows(IllegalArgumentException.class, () -> path.target(0, 0, Double.NaN));
        assertThrows(IllegalArgumentException.class, () -> path.target(0, 0, -1));
        assertThrows(IllegalArgumentException.class, () -> new AreaSpiralPath.Point(0, Double.NaN));
    }

    private static AreaSpiralPath flyToEnd(double centerX, double centerZ, double yaw,
                                           boolean clockwise, double spacing, double maxRadius,
                                           double speed, double maximumTurn) {
        return flyToEnd(centerX, centerZ, yaw, clockwise, spacing, maxRadius,
            speed, maximumTurn, centerX, centerZ);
    }

    private static AreaSpiralPath flyToEnd(double centerX, double centerZ, double yaw,
                                           boolean clockwise, double spacing, double maxRadius,
                                           double speed, double maximumTurn, double startX, double startZ) {
        var path = new AreaSpiralPath(centerX, centerZ, yaw, clockwise, spacing, maxRadius);
        return continueToEnd(path, yaw, spacing, maxRadius, speed, maximumTurn, startX, startZ);
    }

    private static AreaSpiralPath continueToEnd(AreaSpiralPath path, double yaw, double spacing, double maxRadius,
                                               double speed, double maximumTurn, double startX, double startZ) {
        double x = startX;
        double z = startZ;
        double lookAhead = Math.max(12, Math.min(Math.min(48, spacing / 8), speed * 6));
        for (int tick = 0; tick < 30_000 && !path.finished(); tick++) {
            var target = path.target(x, z, lookAhead);
            double dx = target.x() - x;
            double dz = target.z() - z;
            double desiredYaw = Math.toDegrees(Math.atan2(-dx, dz));
            double turn = ((desiredYaw - yaw + 540) % 360 + 360) % 360 - 180;
            yaw += Math.clamp(turn, -maximumTurn, maximumTurn);
            double distance = Math.hypot(dx, dz);
            double forward = distance <= 4 ? 0 : speed;
            x -= Math.sin(Math.toRadians(yaw)) * forward;
            z += Math.cos(Math.toRadians(yaw)) * forward;
        }
        assertTrue(path.finished(), "Forward flight failed: speed=" + speed
            + ", progress radius=" + path.radius() + ", position=" + x + "," + z);
        assertEquals(maxRadius, path.radius(), 1e-8);
        assertTrue(Math.hypot(x - path.endpoint().x(), z - path.endpoint().z()) <= 6 + speed);
        assertEquals(path.endpoint(), path.target(x, z, lookAhead));
        return path;
    }
}
