package dev.tntcoating.core;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
class ModeGeometryTest {
    @Test void northSouthStripHasExactWidthAndNoDuplicates() {
        var cells = ModeGeometry.strip(10, 64, 20, 0, 1, 3);
        assertEquals(6, cells.size());
        assertEquals(6, cells.stream().distinct().count());
        assertTrue(cells.stream().allMatch(c -> c.y()==64 && c.x()>=9 && c.x()<=11 && c.z()>=20 && c.z()<=21));
        assertEquals(new ModeGeometry.Cell(10,64,20), cells.getFirst());
    }
    @Test void westwardStripRotatesAndSupportsEvenWidths() {
        var cells = ModeGeometry.strip(10, 64, 20, -1, .1, 4);
        assertEquals(8, cells.stream().distinct().count());
        assertTrue(cells.stream().allMatch(c -> c.x()==10 || c.x()==9));
        assertEquals(4, cells.stream().map(ModeGeometry.Cell::z).distinct().count());
    }
    @Test void widthsStayBounded() {
        assertEquals(2, ModeGeometry.strip(0,0,0,0,1,1).size());
        assertEquals(18, ModeGeometry.strip(0,0,0,1,0,9).size());
        assertThrows(IllegalArgumentException.class, () -> ModeGeometry.strip(0,0,0,1,0,0));
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
