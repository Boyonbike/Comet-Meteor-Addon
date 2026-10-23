package dev.tntcoating.modules.autowasp;

import net.minecraft.world.phys.Vec3;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class AutoWaspSpeedTest {
    private static final double EPSILON = 1e-9;

    @Test void theDefaultTopSurvivesTwoPacketsInOneServerTick() {
        assertTrue(AutoWaspSpeed.acceptedWhenBunched(AutoWaspSpeed.BUNCHED_SAFE), "240 b/s is never set back for distance");
        assertTrue(AutoWaspSpeed.acceptedWhenBunched(12.24));
        assertFalse(AutoWaspSpeed.acceptedWhenBunched(12.3), "245 b/s and up fails when packets bunch");
        assertFalse(AutoWaspSpeed.acceptedWhenBunched(AutoWaspSpeed.HORIZONTAL_TOP),
            "VolytraFly's 300 b/s top is set back whenever two packets arrive together");
        assertTrue(AutoWaspSpeed.HORIZONTAL_TOP * AutoWaspSpeed.HORIZONTAL_TOP <= 300,
            "but one packet a tick still carries it");
    }

    @Test void capsKeepDirection() {
        Vec3 capped = AutoWaspSpeed.capLength(new Vec3(30, 40, 0), 12);
        assertEquals(12, capped.length(), EPSILON);
        assertEquals(.75, capped.x / capped.y, EPSILON);
        Vec3 small = new Vec3(1, 1, 1);
        assertSame(small, AutoWaspSpeed.capLength(small, 12));

        Vec3 horizontal = AutoWaspSpeed.capHorizontal(new Vec3(3, 5, 4), 2);
        assertEquals(2, horizontal.horizontalDistance(), EPSILON);
        assertEquals(5, horizontal.y, EPSILON);
        assertEquals(Vec3.ZERO, AutoWaspSpeed.capLength(new Vec3(1, 0, 0), Double.NaN));
    }

    @Test void theThreeDLimitAppliesWhileClimbingOrDescendingAndBriefly() {
        AutoWaspSpeed.Limit3d limit = new AutoWaspSpeed.Limit3d();
        assertFalse(limit.update(0), "level flight keeps the horizontal limit");
        assertFalse(limit.update(.04), "drifting under 1 b/s is not a climb");
        assertTrue(limit.update(.5));
        assertTrue(limit.update(-1.2));
        for (int tick = 1; tick < AutoWaspSpeed.LIMIT_3D_HOLD_TICKS; tick++) {
            assertTrue(limit.update(0), "held at tick " + tick + ", so a bobbing target cannot make it flicker");
        }
        assertFalse(limit.update(0));
        limit.update(1);
        limit.reset();
        assertFalse(limit.active());
    }

    @Test void theThreeDLimitHoldsBothRampsAndBoostClimbsBackFromIt() {
        double cap = 180 / AutoWaspSpeed.TICKS_PER_SECOND;
        AutoWaspRamp horizontal = new AutoWaspRamp(new AutoWaspRamp.Settings(AutoWaspSpeed.HORIZONTAL_START,
            AutoWaspSpeed.HORIZONTAL_TOP, .3, 15, 1, AutoWaspSpeed.BUNCHED_SAFE));
        AutoWaspRamp vertical = new AutoWaspRamp(new AutoWaspRamp.Settings(AutoWaspSpeed.VERTICAL_START,
            AutoWaspSpeed.VERTICAL_TOP, AutoWaspSpeed.VERTICAL_STEP, 15, 1, AutoWaspSpeed.VERTICAL_TOP));
        horizontal.engage(12);
        vertical.engage(3);
        AutoWaspSpeed.holdRampsTo(horizontal, vertical, cap);
        assertEquals(cap, Math.hypot(horizontal.speed(), vertical.speed()), EPSILON, "240 + 60 b/s held to 180 b/s");
        assertEquals(3 * cap / Math.hypot(12, 3), vertical.speed(), EPSILON, "vertical keeps its share");
        assertTrue(horizontal.speed() < 12 && horizontal.speed() > 8);

        double held = horizontal.speed();
        horizontal.advance();
        assertTrue(horizontal.speed() > held && horizontal.speed() - held < .3,
            "once lifted, boost speeds up from the cap instead of snapping back to 240 b/s");
    }

    @Test void rampsWithinTheThreeDLimitAreLeftAlone() {
        AutoWaspRamp horizontal = new AutoWaspRamp(new AutoWaspRamp.Settings(3, 15, .3, 15, 1, 15));
        AutoWaspRamp vertical = new AutoWaspRamp(new AutoWaspRamp.Settings(1.5, 3, .5, 15, 1, 3));
        horizontal.engage(5);
        vertical.engage(2);
        AutoWaspSpeed.holdRampsTo(horizontal, vertical, 9);
        assertEquals(5, horizontal.speed(), EPSILON);
        assertEquals(2, vertical.speed(), EPSILON);
        vertical.disengage();
        horizontal.engage(12);
        AutoWaspSpeed.holdRampsTo(horizontal, vertical, 9);
        assertEquals(9, horizontal.speed(), EPSILON, "a level burst inside the hold is held to the 3D limit too");
    }
}
