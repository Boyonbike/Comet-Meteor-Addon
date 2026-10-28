package dev.tntcoating.core;

import java.util.List;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class HighwayFlightRouteTest {
    @Test void controllerActuallyTraversesTheRefinedGapWithoutContactOrRepeatedReplanning() {
        List<AABB> walls = List.of(new AABB(-3,-2,7.9,.8,4,8.1),new AABB(1.8,-2,7.9,3,4,8.1));
        FlightCorridor.Probe probe = new FlightCorridor.Probe() {
            public boolean loaded(int x,int z) { return true; }
            public boolean clear(AABB body) { return walls.stream().noneMatch(wall -> wall.intersects(body)); }
        };
        Vec3 position = Vec3.ZERO;
        Vec3 velocity = Vec3.ZERO;
        AABB original = new AABB(-.3,0,-.3,.3,.6,.3);
        var path = HighwayPathfinder.find(original,position,new Vec3(0,0,1),0,
            new HighwayPathfinder.Config(5,3,8,2,1,1,-10,10),probe);
        assertTrue(path.complete());
        var navigation = new HighwayNavigation();
        assertTrue(navigation.commit(path));
        var profile = HighwayFlightProfile.create(EngineFeelDriver.settings(FlightFeelScenarios.DEFAULTS),48,15);
        var flight = new BaritoneFlight(profile);
        var progress = new HighwayTravelSafety();
        progress.resetProgress(0);
        float yaw = 0;
        for (int tick=0;tick<400 && position.z<10;tick++) {
            AABB body = original.move(position);
            var guidance = navigation.update(tick,position,body,velocity,false,8,15,profile.braking(),probe);
            assertTrue(guidance.followingPath(), "The committed route must remain usable at tick "+tick+" position "+position);
            float desired = HighwayCourse.yaw(position,guidance.target());
            double difference = (desired-yaw+540)%360-180;
            yaw += (float)Math.clamp(difference,-15,15);
            float pitch = HighwayNavigation.steeringPitch(position,guidance.target());
            double speed = Math.min(8,guidance.speedLimit());
            var movement = flight.move(profile,new BaritoneFlight.Settings(speed,speed,.3,.3),tick,yaw,pitch,
                true,velocity.length(),velocity.length(),body,probe,()->Double.POSITIVE_INFINITY,null,m->m);
            assertNotNull(movement);
            velocity = new Vec3(movement.x(),movement.y(),movement.z());
            position = position.add(velocity);
            assertTrue(probe.clear(original.move(position)), "Movement must not hit the wall");
            assertFalse(progress.noProgress(position.z,100), "The route must not stall before crossing the wall");
        }
        assertTrue(position.z>=10,"The real controller must get beyond the gap, reached "+position);
    }
}
