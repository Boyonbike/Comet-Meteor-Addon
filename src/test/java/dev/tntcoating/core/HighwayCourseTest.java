package dev.tntcoating.core;

import net.minecraft.world.phys.Vec3;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class HighwayCourseTest {
    private static final double EPS = 1e-9;

    @Test void approachAndProgressIncludeLateralDistanceAfterOvershootingTheRoutePlane() {
        HighwayCourse course = new HighwayCourse(new Vec3(0,120,0),new Vec3(0,120,1000),120,32);
        assertEquals(Math.hypot(1000,10),course.distanceToDestination(new Vec3(1000,120,1010)),EPS);
        assertTrue(course.distanceToDestination(new Vec3(900,120,1010))
            < course.distanceToDestination(new Vec3(1000,120,1010)));
    }

    @Test void courseNormalizesTheHorizontalDirectionAndLength() {
        HighwayCourse course = new HighwayCourse(new Vec3(10, 70, -5), new Vec3(10, 70, 95), 120, 32);
        assertEquals(100, course.length(), EPS);
        assertEquals(0, course.direction().x, EPS);
        assertEquals(1, course.direction().z, EPS);
        assertEquals(-1, course.perpendicular().x, EPS);
        assertEquals(0, course.perpendicular().z, EPS);
        assertEquals(120, course.targetY(), EPS);
    }

    @Test void remainingIsLongitudinalAndArrivalUsesHorizontalDistance() {
        HighwayCourse course = new HighwayCourse(new Vec3(0, 70, 0), new Vec3(100, 70, 0), 120, 10);
        assertEquals(100, course.remaining(Vec3.ZERO), EPS);
        assertTrue(course.arrived(new Vec3(95, 64, 3)));
        assertFalse(course.arrived(new Vec3(85, 64, 3)));
        assertEquals(0, course.remaining(new Vec3(130, 64, 0)), EPS);
    }

    @Test void doesNotArriveFarBesideOrBeyondTheDestination() {
        HighwayCourse course = new HighwayCourse(new Vec3(0, 120, 0), new Vec3(0, 120, 1000), 120, 32);
        assertEquals(10, course.remaining(new Vec3(1000, 120, 990)), EPS);
        assertFalse(course.arrived(new Vec3(1000, 120, 990)));
        assertFalse(course.arrived(new Vec3(0, 120, 1040)));
        assertTrue(course.arrived(new Vec3(0, 200, 1032)));
    }

    @Test void aimPointLooksAheadAndPullsGentlyBackToTheLine() {
        HighwayCourse course = new HighwayCourse(new Vec3(0, 70, 0), new Vec3(0, 70, 100), 120, 10);
        Vec3 aim = course.aimPoint(new Vec3(2, 64, 10), 20, .5);
        assertEquals(-1.0, aim.x, 1e-6);
        assertEquals(120, aim.y, EPS);
        assertEquals(30, aim.z, EPS);
    }

    @Test void aimPointClampsAtTheDestination() {
        HighwayCourse course = new HighwayCourse(new Vec3(0, 70, 0), new Vec3(0, 70, 100), 120, 10);
        Vec3 aim = course.aimPoint(new Vec3(0, 64, 95), 100, 0);
        assertEquals(100, aim.z, EPS);
    }

    @Test void waypointCombinesTheAimPointAndYaw() {
        HighwayCourse course = new HighwayCourse(new Vec3(0, 70, 0), new Vec3(0, 70, 100), 120, 10);
        HighwayCourse.Waypoint waypoint = course.waypoint(new Vec3(0, 64, 10), 20, 0);
        assertEquals(30, waypoint.aim().z, EPS);
        assertEquals(0, waypoint.yaw(), EPS);
    }

    @Test void yawUsesMinecraftConventions() {
        assertEquals(0, HighwayCourse.yaw(Vec3.ZERO, new Vec3(0, 0, 1)), EPS);
        assertEquals(-90, HighwayCourse.yaw(Vec3.ZERO, new Vec3(1, 0, 0)), EPS);
        assertEquals(90, HighwayCourse.yaw(Vec3.ZERO, new Vec3(-1, 0, 0)), EPS);
        assertEquals(180, Math.abs(HighwayCourse.yaw(Vec3.ZERO, new Vec3(0, 0, -1))), EPS);
        assertEquals(0, HighwayCourse.yaw(Vec3.ZERO, Vec3.ZERO), EPS);
    }

    @Test void rejectsDegenerateOrInvalidCourses() {
        assertThrows(IllegalArgumentException.class,
            () -> new HighwayCourse(Vec3.ZERO, new Vec3(0, 0, 0), 120, 10));
        assertThrows(IllegalArgumentException.class,
            () -> new HighwayCourse(new Vec3(Double.NaN, 0, 0), new Vec3(1, 0, 0), 120, 10));
        assertThrows(IllegalArgumentException.class,
            () -> new HighwayCourse(Vec3.ZERO, new Vec3(1, 0, 0), 120, -1));
        assertThrows(IllegalArgumentException.class,
            () -> new HighwayCourse(Vec3.ZERO, new Vec3(1, 0, 0), Double.POSITIVE_INFINITY, 10));
    }
}
