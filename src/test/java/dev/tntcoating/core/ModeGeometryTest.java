package dev.tntcoating.core;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
class ModeGeometryTest {
    @Test void pathWidthsStayBounded() {
        assertThrows(IllegalArgumentException.class, () -> ModeGeometry.path(0,0,0,1,0,0));
        assertThrows(IllegalArgumentException.class, () -> ModeGeometry.path(0,0,0,1,0,10));
        assertEquals(9, ModeGeometry.path(.5,0,.5,1,0,9).stream().map(ModeGeometry.Cell::z).distinct().count());
    }
    @Test void pathPreparesForwardCentreBeforeWidening() {
        var path = ModeGeometry.path(.5, 64, .5, 0, 1, 5);
        assertEquals(new ModeGeometry.Cell(0,64,0), path.get(0));
        assertEquals(new ModeGeometry.Cell(0,64,1), path.get(1));
        assertEquals(new ModeGeometry.Cell(0,64,2), path.get(2));
        assertEquals(path.size(), path.stream().distinct().count());
    }
    @Test void diagonalPathFollowsBothAxes() {
        var path = ModeGeometry.path(.8, 64, .2, 1, 1, 1);
        assertTrue(path.contains(new ModeGeometry.Cell(1,64,0)));
        assertTrue(path.contains(new ModeGeometry.Cell(1,64,1)));
        assertTrue(path.contains(new ModeGeometry.Cell(2,64,1)));
    }
    @Test void pathHandlesNegativeCoordinatesAndDirection() {
        var path = ModeGeometry.path(-.2, 64, -.2, -1, 0, 1);
        assertEquals(new ModeGeometry.Cell(-1,64,-1), path.getFirst());
        assertTrue(path.contains(new ModeGeometry.Cell(-3,64,-1)));
    }
    @Test void facingControlsBackfillInAllCardinalDirections() {
        assertTrue(ModeGeometry.behindFacing(0,-2,0));
        assertTrue(ModeGeometry.behindFacing(2,0,90));
        assertTrue(ModeGeometry.behindFacing(0,2,180));
        assertTrue(ModeGeometry.behindFacing(-2,0,-90));
        assertFalse(ModeGeometry.behindFacing(0,2,0));
        assertFalse(ModeGeometry.behindFacing(2,0,0));
    }
    @Test void turningReversesEligibilityWithoutMoving() {
        assertTrue(ModeGeometry.behindFacing(0,-3,0));
        assertFalse(ModeGeometry.behindFacing(0,-3,180));
        assertTrue(ModeGeometry.behindFacing(2,-2,45));
        assertFalse(ModeGeometry.behindFacing(-2,2,45));
    }
    @Test void facingPreservesRearClearanceAndYawWrapping() {
        assertFalse(ModeGeometry.behindFacing(0,-1,0));
        assertTrue(ModeGeometry.behindFacing(0,-2,360));
        assertTrue(ModeGeometry.behindFacing(0,-2,-360));
    }
    @Test void backfillLeavesForwardAndNearbyCellsAlone() {
        assertTrue(ModeGeometry.behind(0,-2,0,1));
        assertFalse(ModeGeometry.behind(0,-1,0,1));
        assertFalse(ModeGeometry.behind(0,2,0,1));
        assertTrue(ModeGeometry.behind(2,0,-1,0));
    }
}
