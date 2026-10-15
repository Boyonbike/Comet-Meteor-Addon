package dev.tntcoating.core;
import dev.tntcoating.core.ElytraSteer.Climb;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
class ElytraSteerTest {
    // Minecraft yaw: 0 faces +Z (south), 90 faces -X (west), 180 faces -Z, -90 faces +X.
    @Test void yawMatchesMinecraftCompass() {
        assertEquals(0, ElytraSteer.wrap(ElytraSteer.yawTo(0, 10)), 1e-4);
        assertEquals(90, ElytraSteer.wrap(ElytraSteer.yawTo(-10, 0)), 1e-4);
        assertEquals(180, Math.abs(ElytraSteer.wrap(ElytraSteer.yawTo(0, -10))), 1e-4);
        assertEquals(-90, ElytraSteer.wrap(ElytraSteer.yawTo(10, 0)), 1e-4);
    }
    @Test void turnsAtMostTheStep() {
        assertEquals(10, ElytraSteer.turn(0, 90, 10), 1e-4);
        assertEquals(-10, ElytraSteer.turn(0, -90, 10), 1e-4);
        assertEquals(45, ElytraSteer.turn(40, 45, 10), 1e-4);
    }
    @Test void turnsTheShortWayRound() {
        // From 170 to -170 is 20 degrees clockwise through 180, not 340 the other way.
        assertEquals(180, ElytraSteer.turn(170, -170, 10), 1e-4);
        // Yaw keeps growing past 360 in Minecraft; the step still follows the short way.
        assertEquals(725, ElytraSteer.turn(720, 10, 5), 1e-4);
    }
    @Test void climbsOnlyOutsideTheBand() {
        assertEquals(Climb.UP, ElytraSteer.climb(2.5, 2));
        assertEquals(Climb.HOLD, ElytraSteer.climb(2, 2));
        assertEquals(Climb.HOLD, ElytraSteer.climb(-2, 2));
        assertEquals(Climb.DOWN, ElytraSteer.climb(-2.5, 2));
    }
}
