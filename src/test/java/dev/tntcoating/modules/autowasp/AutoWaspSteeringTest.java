package dev.tntcoating.modules.autowasp;

import net.minecraft.world.phys.Vec3;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class AutoWaspSteeringTest {
    private static final double EPSILON = 1e-9;

    @Test void arrivalSpeedLandsOnShortDistancesAndBrakesLongOnes() {
        assertEquals(0, AutoWaspSteering.arrivalSpeed(0, .3));
        assertEquals(.5, AutoWaspSteering.arrivalSpeed(.5, .3), EPSILON, "the last tick lands on the point");
        assertEquals(Math.sqrt(1.2), AutoWaspSteering.arrivalSpeed(2, .3), EPSILON);
        assertEquals(Math.sqrt(6), AutoWaspSteering.arrivalSpeed(10, .3), EPSILON);
        assertEquals(4, AutoWaspSteering.arrivalSpeed(4, 0), EPSILON, "no braking moves the whole way");
    }

    @Test void arrivingNoLongerStopsDeadFromFullSpeed() {
        double distance = 20;
        double speed = 2;
        double hardest = 0;
        for (int tick = 0; tick < 100 && distance > 0; tick++) {
            double next = Math.min(2, AutoWaspSteering.arrivalSpeed(distance, .3));
            assertTrue(next <= distance + EPSILON, "never overshoots");
            hardest = Math.max(hardest, speed - next);
            speed = next;
            distance -= next;
        }
        assertEquals(0, distance, EPSILON, "arrives exactly");
        assertTrue(hardest < .7, "the hardest one-tick slowdown was " + hardest + " b/t instead of a 2 b/t stop");
    }

    @Test void turnRateRisesWithSpeedToDoubleAtTheCap() {
        assertEquals(30, AutoWaspSteering.turnRate(30, 0, 2), EPSILON);
        assertEquals(45, AutoWaspSteering.turnRate(30, 1, 2), EPSILON);
        assertEquals(60, AutoWaspSteering.turnRate(30, 2, 2), EPSILON);
        assertEquals(60, AutoWaspSteering.turnRate(30, 9, 2), EPSILON);
        assertEquals(90, AutoWaspSteering.turnRate(80, 2, 2), EPSILON, "never beyond a right angle per tick");
        assertEquals(0, AutoWaspSteering.turnRate(0, 2, 2));
    }

    @Test void fastHeadingChangesSwingByTheRateAndKeepTheDesiredSpeed() {
        Vec3 east = new Vec3(2, 0, 0);
        Vec3 turned = AutoWaspSteering.limitTurn(east, new Vec3(0, .4, 2.5), 30, .3);
        assertEquals(Math.toRadians(30), Math.atan2(turned.z, turned.x), 1e-9);
        assertEquals(2.5, turned.horizontalDistance(), EPSILON);
        assertEquals(.4, turned.y, EPSILON, "vertical is left to the caps");

        Vec3 slight = new Vec3(2, 0, .5);
        assertEquals(0, AutoWaspSteering.limitTurn(east, slight, 30, .3).distanceTo(slight), 1e-9,
            "within the rate turns at once");
    }

    @Test void slowFlightAndAZeroRateTurnAtOnce() {
        Vec3 desired = new Vec3(0, 0, -2);
        assertEquals(desired, AutoWaspSteering.limitTurn(new Vec3(.5, 0, 0), desired, 30, .3));
        assertEquals(desired, AutoWaspSteering.limitTurn(new Vec3(2, 0, 0), desired, 0, .3));
    }

    @Test void aReversalBrakesAlongTheOldHeadingBeforeTurning() {
        Vec3 velocity = new Vec3(2, 0, 0);
        Vec3 back = new Vec3(-2, 0, 0);
        int ticks = 0;
        while (velocity.x > 0 && ticks < 20) {
            Vec3 next = AutoWaspSteering.limitTurn(velocity, back, 30, .3);
            if (next.x > 0) assertEquals(velocity.x - .3, next.x, EPSILON, "sheds the braking rate each tick");
            velocity = next;
            ticks++;
        }
        assertEquals(back, velocity, "turns once slow enough");
        assertTrue(ticks >= 5, "took " + ticks + " ticks instead of flipping 4 b/t at once");
    }

    @Test void capsKeepDirection() {
        Vec3 capped = AutoWaspSteering.capLength(new Vec3(30, 40, 0), 17);
        assertEquals(17, capped.length(), EPSILON);
        assertEquals(.75, capped.x / capped.y, EPSILON);
        Vec3 small = new Vec3(1, 1, 1);
        assertSame(small, AutoWaspSteering.capLength(small, 17));

        Vec3 horizontal = AutoWaspSteering.capHorizontal(new Vec3(3, 5, 4), 2);
        assertEquals(2, horizontal.horizontalDistance(), EPSILON);
        assertEquals(5, horizontal.y, EPSILON);
    }

    @Test void maneuveringNeedsTurningAndClimbingAtOnceAndOutlastsThem() {
        AutoWaspSteering.Maneuver maneuver = new AutoWaspSteering.Maneuver();
        Vec3 east = new Vec3(2, 0, 0);
        assertFalse(maneuver.update(east, new Vec3(2, 1, 0)), "climbing straight on");
        assertFalse(maneuver.update(east, new Vec3(2, 0, 1)), "turning level");
        assertTrue(maneuver.update(east, new Vec3(2, 1, 1)));
        for (int tick = 1; tick < AutoWaspSteering.MANEUVER_HOLD_TICKS; tick++) {
            assertTrue(maneuver.update(east, east), "held at tick " + tick);
        }
        assertFalse(maneuver.update(east, east));
    }
}
