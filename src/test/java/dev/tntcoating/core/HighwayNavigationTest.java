package dev.tntcoating.core;

import static org.junit.jupiter.api.Assertions.*;

import java.util.ArrayList;
import java.util.List;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import org.junit.jupiter.api.Test;

class HighwayNavigationTest {
    private static int tick;
    private static final Vec3 FORWARD = new Vec3(0, 0, 1);

    @Test
    void steersAlongThePlannedSlopeIncludingVerticalAlignment() {
        assertEquals(-45, HighwayNavigation.steeringPitch(Vec3.ZERO, new Vec3(0, 1, 1)), 1e-5);
        assertEquals(-89, HighwayNavigation.steeringPitch(Vec3.ZERO, new Vec3(0, 1, 0)), 1e-5);
        assertEquals(89, HighwayNavigation.steeringPitch(Vec3.ZERO, new Vec3(0, -1, 0)), 1e-5);
        assertEquals(0, HighwayNavigation.steeringPitch(Vec3.ZERO, new Vec3(1, 0, 0)), 1e-5);
    }

    @Test
    void keepsChosenSideThroughPositionJitterAndReplacementOffers() {
        HighwayNavigation navigation = new HighwayNavigation();
        assertTrue(navigation.commit(path(Vec3.ZERO, new Vec3(2, 0, 4), new Vec3(2, 0, 16))));
        for (double x : new double[] { .15, -.15, .1, -.1 }) {
            HighwayNavigation.Step step = update(navigation, new Vec3(x, 0, 1), false, probe(List.of()));
            assertTrue(step.followingPath());
            assertFalse(step.needsPath());
            assertTrue(step.target().x > 1, "Committed right route must survive lateral jitter");
            assertFalse(navigation.commit(path(Vec3.ZERO, new Vec3(-2, 0, 4), new Vec3(-2, 0, 16))));
        }
    }

    @Test
    void advancesLocallyWithoutBacktrackingAfterSmallSetback() {
        HighwayNavigation navigation = new HighwayNavigation();
        navigation.commit(path(Vec3.ZERO, new Vec3(0, 0, 10), new Vec3(0, 0, 30)));
        HighwayNavigation.Step start = update(navigation, Vec3.ZERO, false, probe(List.of()));
        assertTrue(start.target().z <= 8.01, "Must not aim at distant visible endpoint");
        HighwayNavigation.Step ahead = update(navigation, new Vec3(0, 0, 12), false, probe(List.of()));
        HighwayNavigation.Step jitter = update(navigation, new Vec3(0, 0, 11.8), false, probe(List.of()));
        assertTrue(ahead.target().z > start.target().z);
        assertTrue(jitter.target().z >= ahead.target().z, "Progress must never rewind");
        assertFalse(jitter.needsPath());
        assertTrue(update(navigation, new Vec3(0, 0, 30), false, probe(List.of())).needsPath());
    }

    @Test
    void requiresSustainedDirectClearanceBeforeLeavingDetour() {
        HighwayNavigation navigation = new HighwayNavigation();
        navigation.commit(path(Vec3.ZERO, new Vec3(2, 0, 6), new Vec3(2, 0, 20)));
        for (int i = 0; i < 4; i++) assertTrue(update(navigation, Vec3.ZERO, true, probe(List.of())).followingPath());
        assertTrue(update(navigation, Vec3.ZERO, false, probe(List.of())).followingPath());
        for (int i = 0; i < 5; i++) assertTrue(update(navigation, Vec3.ZERO, true, probe(List.of())).followingPath());
        HighwayNavigation.Step clear = update(navigation, Vec3.ZERO, true, probe(List.of()));
        assertFalse(clear.followingPath());
        assertFalse(clear.needsPath());
    }

    @Test
    void invalidatesObstructedSelectedSegmentAndAllowsReplacement() {
        HighwayNavigation navigation = new HighwayNavigation();
        navigation.commit(path(Vec3.ZERO, new Vec3(0, 0, 10), new Vec3(0, 0, 20)));
        HighwayNavigation.Step blocked = update(navigation, Vec3.ZERO, false,
            probe(List.of(new AABB(-1, -1, 3, 1, 3, 4))));
        assertTrue(blocked.needsPath());
        assertFalse(blocked.followingPath());
        assertEquals(0, blocked.speedLimit());
        assertTrue(navigation.commit(path(Vec3.ZERO, new Vec3(2, 0, 4), new Vec3(2, 0, 20))));
    }

    @Test
    void setbackPreventsImmediateReplanningAndSideReversal() {
        HighwayNavigation navigation = new HighwayNavigation();
        navigation.commit(path(Vec3.ZERO, new Vec3(2, 0, 4), new Vec3(2, 0, 20)));
        navigation.setback();
        HighwayNavigation.Step held = update(navigation, Vec3.ZERO, true, probe(List.of()));
        assertTrue(held.followingPath());
        assertTrue(held.target().x > 0);
        assertTrue(held.speedLimit() < 3);
        List<AABB> walls = List.of(new AABB(-3, -1, 2, 3, 3, 3));
        for (int i = 0; i < 8; i++) {
            assertFalse(update(navigation, Vec3.ZERO, false, probe(walls)).needsPath());
            assertFalse(navigation.commit(path(Vec3.ZERO, new Vec3(-2, 0, 4), new Vec3(-2, 0, 20))));
        }
        for (int i = 0; i < 3; i++) held = update(navigation, Vec3.ZERO, false, probe(walls));
        assertTrue(held.needsPath());
    }

    @Test
    void slowsBeforeSharpCornerComparedWithStraightCruise() {
        HighwayNavigation straight = new HighwayNavigation();
        straight.commit(path(Vec3.ZERO, new Vec3(0, 0, 4), new Vec3(0, 0, 20)));
        HighwayNavigation bend = new HighwayNavigation();
        bend.commit(path(Vec3.ZERO, new Vec3(0, 0, 4), new Vec3(12, 0, 4)));
        HighwayNavigation.Step cruise = update(straight, Vec3.ZERO, false, probe(List.of()));
        HighwayNavigation.Step turn = update(bend, Vec3.ZERO, false, probe(List.of()));
        assertEquals(3, cruise.speedLimit(), 1e-6);
        assertTrue(turn.speedLimit() < cruise.speedLimit() * .75);
        assertTrue(turn.speedLimit() > 0);
    }

    @Test
    void repeatedUpdatesInOneTickDoNotPrematurelyReleaseCommitment() {
        HighwayNavigation navigation = new HighwayNavigation();
        navigation.commit(path(Vec3.ZERO, new Vec3(2, 0, 6), new Vec3(2, 0, 20)));
        AABB body = new AABB(-.3, 0, -.3, .3, 1.8, .3);
        for (int i = 0; i < 20; i++) {
            assertTrue(navigation.update(42, Vec3.ZERO, body, FORWARD, true, 3,
                probe(List.of())).followingPath());
        }
    }

    @Test
    void retainsClearCornerApproachWhenEarlyShortcutWouldHitWall() {
        HighwayNavigation navigation = new HighwayNavigation();
        navigation.commit(path(Vec3.ZERO, new Vec3(0, 0, 4), new Vec3(4, 0, 4)));
        FlightCorridor.Probe wall = probe(List.of(new AABB(.4, -1, 0, 2, 3, 3.6)));

        HighwayNavigation.Step approach = update(navigation, new Vec3(0, 0, 3.4), false, wall);

        assertTrue(approach.followingPath(), "Clear committed legs must survive a blocked corner shortcut");
        assertFalse(approach.needsPath());
        assertEquals(new Vec3(0, 0, 4), approach.target());
        HighwayNavigation.Step crossed = update(navigation, new Vec3(0, 0, 4), false, wall);
        assertTrue(crossed.followingPath());
        assertEquals(new Vec3(4, 0, 4), crossed.target());
    }

    @Test
    void freezesCallerListAndResetRemovesOldCommitment() {
        HighwayNavigation navigation = new HighwayNavigation();
        ArrayList<Vec3> points = new ArrayList<>(List.of(Vec3.ZERO, new Vec3(2, 0, 10)));
        navigation.commit(new HighwayPathfinder.Path(points, HighwayPathfinder.Reason.FOUND, 1));
        points.clear();
        assertTrue(update(navigation, Vec3.ZERO, false, probe(List.of())).target().x > 0);
        navigation.reset();
        assertTrue(update(navigation, Vec3.ZERO, false, probe(List.of())).needsPath());
    }

    @Test
    void respectsTurnRateAndBrakesAsCornerApproachesWithoutStoppingFarAway() {
        HighwayNavigation navigation = new HighwayNavigation();
        navigation.commit(path(Vec3.ZERO, new Vec3(0, 0, 7), new Vec3(12, 0, 7)));
        HighwayNavigation.Step far = flightUpdate(navigation, Vec3.ZERO, 2, .02, probe(List.of()));
        HighwayNavigation.Step near = flightUpdate(navigation, new Vec3(0, 0, 6.5), 2, .02, probe(List.of()));
        assertTrue(far.speedLimit() > 1, "A distant bend must leave useful forward progress");
        assertTrue(near.speedLimit() < far.speedLimit());
        assertTrue(near.speedLimit() <= 2, "A half-block approach cannot carry cruise speed into a bend");
        HighwayNavigation fasterTurn = new HighwayNavigation();
        fasterTurn.commit(path(Vec3.ZERO, new Vec3(0, 0, 7), new Vec3(12, 0, 7)));
        assertTrue(flightUpdate(fasterTurn, new Vec3(0, 0, 6.5), 20, .02,
            probe(List.of())).speedLimit() > near.speedLimit());
    }

    @Test
    void tightClearanceLowersSpeedEvenOnStraightRoute() {
        HighwayNavigation open = new HighwayNavigation();
        HighwayNavigation narrow = new HighwayNavigation();
        open.commit(path(Vec3.ZERO, new Vec3(0, 0, 20)));
        narrow.commit(path(Vec3.ZERO, new Vec3(0, 0, 20)));
        FlightCorridor.Probe walls = probe(List.of(new AABB(.5, -1, -1, 2, 3, 20),
            new AABB(-2, -1, -1, -.5, 3, 20)));
        assertTrue(flightUpdate(narrow, Vec3.ZERO, 6, .02, walls).speedLimit()
            < flightUpdate(open, Vec3.ZERO, 6, .02, probe(List.of())).speedLimit());
    }

    @Test
    void floorContactDoesNotMasqueradeAsTightSteeringClearance() {
        HighwayNavigation open = new HighwayNavigation();
        HighwayNavigation aboveFloor = new HighwayNavigation();
        open.commit(path(Vec3.ZERO, new Vec3(0, 0, 20)));
        aboveFloor.commit(path(Vec3.ZERO, new Vec3(0, 0, 20)));
        double openLimit = flightUpdate(open, Vec3.ZERO, 6, .02, probe(List.of())).speedLimit();
        double floorLimit = flightUpdate(aboveFloor, Vec3.ZERO, 6, .02,
            probe(List.of(new AABB(-10, -1, -10, 10, 0, 30)))).speedLimit();
        assertEquals(openLimit, floorLimit, 1e-6);
    }

    @Test
    void weakBrakingCannotExceedTheVerifiedStoppingHorizon() {
        HighwayNavigation weak = new HighwayNavigation();
        HighwayNavigation strong = new HighwayNavigation();
        weak.commit(path(Vec3.ZERO, new Vec3(0, 0, 30)));
        strong.commit(path(Vec3.ZERO, new Vec3(0, 0, 30)));
        double weakLimit = flightUpdate(weak, Vec3.ZERO, 6, .005, probe(List.of())).speedLimit();
        double strongLimit = flightUpdate(strong, Vec3.ZERO, 6, .1, probe(List.of())).speedLimit();
        assertTrue(weakLimit < strongLimit);
        // At 12 blocks/sec, .6 blocks/tick needs 36 blocks to stop with .005 braking.
        assertTrue(weakLimit < 6, "An eight-block verified horizon cannot permit that stopping distance");
    }

    @Test
    void consumesOnlyCollisionFailuresAndClearsThemOnReset() {
        HighwayNavigation navigation = new HighwayNavigation();
        Vec3 waypoint = new Vec3(0, 0, 10);
        navigation.commit(path(Vec3.ZERO, waypoint));
        update(navigation, Vec3.ZERO, false, probe(List.of(new AABB(-1, -1, 3, 1, 3, 4))));
        assertEquals(waypoint, navigation.takeFailedWaypoint());
        assertNull(navigation.takeFailedWaypoint());
        navigation.commit(path(Vec3.ZERO, waypoint));
        FlightCorridor.Probe unloaded = new FlightCorridor.Probe() {
            @Override public boolean loaded(int x, int z) { return false; }
            @Override public boolean clear(AABB box) { fail("Unloaded chunks must not be probed"); return false; }
        };
        update(navigation, Vec3.ZERO, false, unloaded);
        assertNull(navigation.takeFailedWaypoint());
        navigation.reset();
        assertNull(navigation.nextWaypoint());
    }

    private static HighwayNavigation.Step flightUpdate(HighwayNavigation navigation, Vec3 position,
                                                        double turnRate, double braking, FlightCorridor.Probe probe) {
        return navigation.update(++tick, position, new AABB(-.3, 0, -.3, .3, 1.8, .3).move(position),
            FORWARD, false, 12, turnRate, braking, probe);
    }

    private static HighwayNavigation.Step update(HighwayNavigation navigation, Vec3 position,
                                                boolean directClear, FlightCorridor.Probe probe) {
        return navigation.update(++tick, position, new AABB(-.3, 0, -.3, .3, 1.8, .3).move(position),
            FORWARD, directClear, 3, probe);
    }

    private static HighwayPathfinder.Path path(Vec3... points) {
        return new HighwayPathfinder.Path(List.of(points), HighwayPathfinder.Reason.FOUND, points.length - 1);
    }

    private static FlightCorridor.Probe probe(List<AABB> walls) {
        return new FlightCorridor.Probe() {
            @Override public boolean loaded(int chunkX, int chunkZ) { return true; }
            @Override public boolean clear(AABB box) {
                return walls.stream().noneMatch(wall -> wall.intersects(box));
            }
        };
    }
}
