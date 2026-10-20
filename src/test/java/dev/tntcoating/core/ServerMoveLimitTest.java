package dev.tntcoating.core;

import net.minecraft.world.phys.Vec3;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class ServerMoveLimitTest {
    private static final double EPSILON = 1e-9;

    @Test void aFastTickIsReportedHalfwayFirst() {
        Vec3 last = new Vec3(100, 80, 100);
        Vec3 current = new Vec3(100, 80, 115);
        Vec3 half = ServerMoveLimit.halfStep(last, current, 15);
        assertNotNull(half);
        assertEquals(107.5, half.z, EPSILON);
    }

    @Test void slowTicksNeedOnlyOnePacket() {
        assertNull(ServerMoveLimit.halfStep(new Vec3(0, 80, 0), new Vec3(0, 80, 9.9), 9.9),
            "at or below 200 b/s one packet is within Paper's limit even when packets bunch");
    }

    @Test void noHalfwayPointIsInventedAcrossAServerCorrection() {
        // After a setback, vanilla's last reported position is still from before the correction: here 12 blocks
        // behind the corrected spot, which moved only 3 blocks this tick. Its midpoint was never a real position.
        assertNull(ServerMoveLimit.halfStep(new Vec3(0, 80, 0), new Vec3(0, 80, 15), 3));
    }

    @Test void splitMovesRaiseTheLimitWithinPapersBunchedAllowance() {
        assertEquals(17, ServerMoveLimit.of(false), EPSILON);
        assertTrue(ServerMoveLimit.of(true) <= 20, "two bunched ticks of split moves may cover 20 blocks each");
        assertEquals(10, ServerMoveLimit.SPLIT_ABOVE, EPSILON, "Paper's per-packet limit when packets bunch");
    }
}
