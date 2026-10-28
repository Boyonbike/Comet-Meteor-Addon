package dev.tntcoating.core;

import java.util.List;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class BoatLavaAvoidanceTest {
    private final AABB boat = new AABB(0, 70, 0, 1, 70.5625, 1);
    private final AABB rider = new AABB(.2, 70, .2, .8, 71.8, .8);
    private final Vec3 step = new Vec3(0, 0, .24);
    private final AABB overheadLava = new AABB(0, 71, 1, 1, 72, 2);

    @Test void protectsTheRiderBeforeEnteringOverheadLava() {
        assertTrue(BoatLavaAvoidance.blocked(boat, List.of(rider), step,
            BoatLavaAvoidance.Mode.BoatAndRiders, overheadLava::intersects));
    }
    @Test void boatOnlyModeAllowsIntentionalRiderContact() {
        assertFalse(BoatLavaAvoidance.blocked(boat, List.of(rider), step,
            BoatLavaAvoidance.Mode.BoatOnly, overheadLava::intersects));
    }
    @Test void bothModesStillProtectTheBoat() {
        var lava = new AABB(0, 70, 1, 1, 71, 2);
        for (var mode : BoatLavaAvoidance.Mode.values())
            assertTrue(BoatLavaAvoidance.blocked(boat, List.of(rider), step, mode, lava::intersects));
    }
    @Test void checksEveryRiderAndAllowsAClearRoute() {
        for (var mode : BoatLavaAvoidance.Mode.values())
            assertFalse(BoatLavaAvoidance.blocked(boat, List.of(rider), step, mode, box -> false));
        assertTrue(BoatLavaAvoidance.blocked(boat, List.of(boat, rider), step,
            BoatLavaAvoidance.Mode.BoatAndRiders, overheadLava::intersects));
    }
}
