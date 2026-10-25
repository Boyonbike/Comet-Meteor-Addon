package dev.tntcoating.core;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class HighwayPortalCrossingTest {
    private AABB body(Vec3 p) { return new AABB(p.x-.3,p.y,p.z-.3,p.x+.3,p.y+1.8,p.z+.3); }

    @Test void crossesXPlanePerpendicularlyWithoutSidewaysOrVerticalDrift() {
        Vec3 start = new Vec3(5.2, 64, -2);
        var p = HighwayPortalCrossing.plan(start,body(start),new BlockPos(5,64,0),Direction.Axis.X,new Vec3(.8,0,1));
        assertEquals(new Vec3(0,0,1),p.direction());
        assertEquals(5.2,p.target().x);
        assertEquals(64,p.target().y);
        assertTrue(p.target().z > 1.3);
        assertFalse(p.finished(new Vec3(5.2,64,.8)));
        assertTrue(p.finished(p.target().add(0,0,.1)));
    }

    @Test void crossesZPlaneInReverseEvenWithMostlySidewaysApproach() {
        Vec3 start = new Vec3(3,64,5.2);
        var p = HighwayPortalCrossing.plan(start,body(start),new BlockPos(0,64,5),Direction.Axis.Z,new Vec3(-.1,0,1));
        assertEquals(new Vec3(-1,0,0),p.direction());
        assertTrue(p.target().x < -.3);
        assertEquals(5.2,p.target().z);
        assertFalse(p.finished(new Vec3(.1,64,5.2)));
        assertTrue(p.finished(p.target()));
    }

    @Test void alreadyInsideKeepsApproachDirectionAndRequiresExitOnFarSide() {
        Vec3 start = new Vec3(.5,64,.5);
        var p = HighwayPortalCrossing.plan(start,body(start),BlockPos.containing(start),Direction.Axis.X,new Vec3(1,0,-.1));
        assertEquals(new Vec3(0,0,-1),p.direction());
        assertFalse(p.finished(start));
        assertTrue(p.finished(p.target()));
    }
    @Test void overlappingNearEdgeKeepsMovingOutRatherThanTurningBackInside() {
        Vec3 start = new Vec3(2,64,-.2);
        var p = HighwayPortalCrossing.plan(start,body(start),new BlockPos(2,64,0),Direction.Axis.X,new Vec3(0,0,-1));
        assertEquals(new Vec3(0,0,-1),p.direction());
        assertTrue(p.target().z < start.z);
    }
}
