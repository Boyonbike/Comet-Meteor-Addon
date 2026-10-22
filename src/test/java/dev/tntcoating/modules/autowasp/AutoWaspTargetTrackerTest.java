package dev.tntcoating.modules.autowasp;

import net.minecraft.world.phys.Vec3;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class AutoWaspTargetTrackerTest {
    private static final double EPSILON = 1e-6;

    @Test void velocityComesFromPositionsAndSettlesOnSteadyMovement() {
        AutoWaspTargetTracker tracker = new AutoWaspTargetTracker();
        Vec3 step = new Vec3(1.5, -.1, .5);
        tracker.sample(7, Vec3.ZERO);
        assertEquals(Vec3.ZERO, tracker.velocity(), "one sample is no movement yet");
        for (int tick = 1; tick <= 30; tick++) tracker.sample(7, step.scale(tick));
        assertEquals(0, tracker.velocity().distanceTo(step), EPSILON);
    }

    @Test void anotherTargetOrATeleportStartsAgain() {
        AutoWaspTargetTracker tracker = new AutoWaspTargetTracker();
        for (int tick = 0; tick < 10; tick++) tracker.sample(1, new Vec3(tick, 0, 0));
        tracker.sample(2, new Vec3(500, 0, 0));
        assertEquals(Vec3.ZERO, tracker.velocity());
        tracker.sample(2, new Vec3(501, 0, 0));
        tracker.sample(2, new Vec3(900, 0, 0));
        assertEquals(Vec3.ZERO, tracker.velocity());
        tracker.sample(2, new Vec3(Double.NaN, 0, 0));
        assertEquals(Vec3.ZERO, tracker.velocity());
    }

    @Test void leadCoversPingAndInterpolationButIsCapped() {
        assertEquals(3, AutoWaspTargetTracker.leadTicks(0), EPSILON);
        assertEquals(5.6, AutoWaspTargetTracker.leadTicks(130), EPSILON);
        assertEquals(AutoWaspTargetTracker.MAX_LEAD_TICKS, AutoWaspTargetTracker.leadTicks(2000), EPSILON);
        assertEquals(3, AutoWaspTargetTracker.leadTicks(-5), EPSILON);
    }

    @Test void predictionLeadsByTheSmoothedVelocity() {
        AutoWaspTargetTracker tracker = new AutoWaspTargetTracker();
        for (int tick = 0; tick <= 30; tick++) tracker.sample(3, new Vec3(0, 64, tick));
        Vec3 shown = new Vec3(0, 64, 30);
        assertEquals(0, tracker.predict(shown, 100).distanceTo(new Vec3(0, 64, 35)), 1e-4);
    }
}
