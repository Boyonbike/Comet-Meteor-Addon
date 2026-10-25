package dev.tntcoating.core;

import java.util.concurrent.atomic.AtomicInteger;
import net.minecraft.core.BlockPos;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class HighwayRecoveryTargetsTest {
    @Test void walkingSelectsTheActualStandingLevelInsteadOfCruiseAltitude() {
        BlockPos target = HighwayRecoveryTargets.standingTarget(new BlockPos(20,120,0),64,
            p -> p.equals(new BlockPos(20,64,0))).orElseThrow();
        assertEquals(new BlockPos(20,64,0), target);
    }
    @Test void absentWalkTargetHasABoundedSearch() {
        AtomicInteger calls=new AtomicInteger();
        assertTrue(HighwayRecoveryTargets.standingTarget(new BlockPos(20,120,0),64,
            p -> { calls.incrementAndGet(); return false; }).isEmpty());
        assertTrue(calls.get()<=225);
    }
    @Test void destinationPortalHasASafeExitWithoutFlightStartingInsideIt() {
        var body=new AABB(-.3,64,-.3,.3,65.8,.3);
        var exit=HighwayRecoveryTargets.portalExit(body,new Vec3(0,64,0),new Vec3(0,0,1),
            new HighwayRecoveryTargets.ExitProbe() {
                public boolean clear(AABB b) {return b.minZ>=-.301;}
                public boolean supported(AABB b) {return true;}
                public boolean portal(AABB b) {return b.minZ<1;}
            }).orElseThrow();
        assertTrue(exit.z>=1.3);
    }
    @Test void portalExitRefusesToWalkOffAnUnsupportedEdge() {
        assertTrue(HighwayRecoveryTargets.portalExit(new AABB(-.3,64,-.3,.3,65.8,.3),new Vec3(0,64,0),
            new Vec3(0,0,1),new HighwayRecoveryTargets.ExitProbe() {
                public boolean clear(AABB b) {return true;}
                public boolean supported(AABB b) {return false;}
                public boolean portal(AABB b) {return false;}
            }).isEmpty());
    }
    @Test void portalExitChecksSupportBeforeTheFirstFullBlockOfTravel() {
        assertTrue(HighwayRecoveryTargets.portalExit(new AABB(-.3,64,-.3,.3,65.8,.3),new Vec3(0,64,0),
            new Vec3(0,0,1),new HighwayRecoveryTargets.ExitProbe() {
                public boolean clear(AABB b) {return b.minZ>=-.301;}
                public boolean supported(AABB b) {return b.getCenter().z<=.2 || b.getCenter().z>=.9;}
                public boolean portal(AABB b) {return b.minZ<1;}
            }).isEmpty());
    }
    @Test void terrainQueriesAreReusedButInvalidatedByTickWorldOrServerUpdate() {
        HighwayProbeCache<Integer> cache = new HighwayProbeCache<>(4);
        AtomicInteger calls=new AtomicInteger(); Object world=new Object();
        assertEquals(1,cache.get(world,1,0,7,calls::incrementAndGet));
        assertEquals(1,cache.get(world,1,0,7,calls::incrementAndGet));
        assertEquals(2,cache.get(world,2,0,7,calls::incrementAndGet));
        assertEquals(3,cache.get(world,2,1,7,calls::incrementAndGet));
        assertEquals(4,cache.get(new Object(),2,1,7,calls::incrementAndGet));
    }
    @Test void stoppedTripsDropCachedTerrainAndCapacityNeverGrowsWithTravel() {
        HighwayProbeCache<Integer> cache = new HighwayProbeCache<>(1);
        AtomicInteger calls=new AtomicInteger(); Object world=new Object();
        assertEquals(1,cache.get(world,1,0,1,calls::incrementAndGet));
        assertEquals(2,cache.get(world,1,0,2,calls::incrementAndGet));
        assertEquals(3,cache.get(world,1,0,2,calls::incrementAndGet));
        assertEquals(1,cache.get(world,1,0,1,calls::incrementAndGet));
        cache.clear();
        assertEquals(4,cache.get(world,1,0,1,calls::incrementAndGet));
    }
}
