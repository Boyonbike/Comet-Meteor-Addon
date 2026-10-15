package dev.tntcoating.core;

import net.minecraft.world.phys.AABB;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class PlayerClearanceTest {
    private final AABB player = new AABB(.2, 65, .2, .8, 66.8, .8);

    @Test void floorTouchingThePlayersSolesDoesNotObstructTheirBody() {
        assertFalse(player.inflate(.08, 0, .08).intersects(new AABB(0, 64, 0, 1, 65, 1)));
    }
    @Test void predictedWalkingSpaceAllowsASupportingFloorAhead() {
        assertFalse(player.inflate(.08, 0, .08).move(1, 0, 0).intersects(new AABB(1, 64, 0, 2, 65, 1)));
    }
    @Test void predictedWalkingSpaceRejectsFeetAndHeadBlocksAhead() {
        AABB next = player.inflate(.08, 0, .08).move(1, 0, 0);
        assertTrue(next.intersects(new AABB(1, 65, 0, 2, 66, 1)));
        assertTrue(next.intersects(new AABB(1, 66, 0, 2, 67, 1)));
    }
    @Test void underfootPlacementRequiresRealClearanceDuringAJumP() {
        AABB underfoot = new AABB(0, 64, 0, 1, 65, 1);
        assertTrue(player.move(0, -.4, 0).intersects(underfoot));
        assertFalse(player.move(0, .03, 0).intersects(underfoot));
    }
}
