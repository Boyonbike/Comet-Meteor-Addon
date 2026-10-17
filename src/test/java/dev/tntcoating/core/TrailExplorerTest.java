package dev.tntcoating.core;

import org.junit.jupiter.api.Test;

import static dev.tntcoating.core.TrailExplorer.State.*;
import static org.junit.jupiter.api.Assertions.*;

class TrailExplorerTest {
    /** Minecraft yaw: 0 south (+z), 90 west (-x), -90 east (+x), 180 north. */
    private static final float SOUTH = 0, WEST = 90, EAST = -90;

    @Test void searchTurnsTowardsUnflownOldChunksAhead() {
        var explorer = explorer(new TrailRouteMemory());
        // A band ahead and to the west (-x), with explicit new terrain elsewhere.
        paint(explorer, -400, 200, -100, 200, 48, 0, 0, 400);
        var decision = explorer.next(0, 0, 0, SOUTH);
        assertEquals(SEARCHING, decision.state());
        assertTrue(decision.yaw() > 20 && decision.yaw() < 135, "steer towards the old chunks: " + decision.yaw());
        assertFalse(decision.blocked());
    }

    @Test void aBandIsTakenAlongItsAxisWhateverTheCrossingAngle() {
        for (int crossing : new int[] {0, 30, 60, 90}) {
            var explorer = explorer(new TrailRouteMemory());
            explorer.setPreferOutbound(false);
            double radians = Math.toRadians(crossing);
            double dx = -Math.sin(radians), dz = Math.cos(radians);
            paint(explorer, -dx * 600, -dz * 600, dx * 600, dz * 600, 80, 0, 0, 300);
            var decision = explorer.next(0, 0, 0, SOUTH);
            assertEquals(FOLLOWING, decision.state(), "crossing " + crossing);
            double along = Math.abs(ElytraSteer.wrap(explorer.axis() - crossing));
            assertTrue(along <= 15 || along >= 165, "crossing " + crossing + " took axis " + explorer.axis());
        }
    }

    @Test void aBandIsTakenInTheDirectionLeadingAwayFromSpawn() {
        var explorer = explorer(new TrailRouteMemory());
        // A north-south band at x=5000: south (+z) and north are equally long; neither points at 0,0.
        // Facing east, outward is +x... so use a band radial to spawn: along x at z=0, far out.
        paint(explorer, 4_000, 0, 6_000, 0, 80, 5_000, 0, 300);
        var decision = explorer.next(0, 5_000, 0, SOUTH);
        assertEquals(FOLLOWING, decision.state());
        assertEquals(EAST, explorer.axis(), 15, "outward from 0,0 is +x");
    }

    @Test void aTrailThatOnlyLeadsBackTowardsSpawnIsNotTaken() {
        var explorer = explorer(new TrailRouteMemory());
        // The band's outer end is right here; its only continuation points at 0,0.
        paint(explorer, 3_000, 0, 4_900, 0, 80, 4_950, 0, 300);
        var decision = explorer.next(0, 4_950, 0, EAST);
        assertNotEquals(FOLLOWING, decision.state());
        var anyway = explorer(new TrailRouteMemory());
        anyway.setPreferOutbound(false);
        paint(anyway, 3_000, 0, 4_900, 0, 80, 4_950, 0, 300);
        assertEquals(FOLLOWING, anyway.next(0, 4_950, 0, EAST).state(), "with prefer-outbound off it is followed");
    }

    @Test void followingAimsAtTheMiddleOfAWideBandNotItsEdge() {
        var explorer = explorer(new TrailRouteMemory());
        explorer.setPreferOutbound(false);
        // A band along x from z=512 to z=688 (centre 600); start on its centre heading west.
        paint(explorer, 400, 600, -2_000, 600, 88, 0, 600, 300);
        explorer.next(0, 0, 600, WEST);
        // Now ride the southern edge: the command must turn back towards z=600 (north, a larger yaw).
        paint(explorer, 400, 600, -2_000, 600, 88, -100, 680, 300);
        var decision = explorer.next(1, -100, 680, WEST);
        assertEquals(FOLLOWING, decision.state());
        assertTrue(ElytraSteer.wrap(decision.yaw() - WEST) > 3, "turn back towards the centre: " + decision.yaw());
    }

    @Test void aDenseSidePatchCannotPullAStraightTrailAway() {
        var explorer = explorer(new TrailRouteMemory());
        explorer.setPreferOutbound(false);
        paint(explorer, 0, -200, 0, 1_000, 48, 0, 0, 300);
        explorer.next(0, 0, 0, SOUTH);
        // A broad old patch south-west of the line, closer than most of the trail.
        for (int cx = -14; cx <= -4; cx++) for (int cz = 3; cz <= 9; cz++) explorer.observe(cx, cz, true);
        for (int tick = 1; tick <= 40; tick++) explorer.next(tick, 0, tick * 1.5, SOUTH);
        assertEquals(SOUTH, explorer.axis(), 6);
    }

    @Test void explicitNewTerrainAcrossTheBandEndsTheTrailWithALead() {
        var memory = new TrailRouteMemory();
        var explorer = explorer(memory);
        explorer.setPreferOutbound(false);
        paint(explorer, 0, -100, 0, 400, 48, 0, 0, 600);
        TrailExplorer.Decision decision = null;
        TrailExplorer.Lead lead = null;
        for (int tick = 0; tick <= 400 && lead == null; tick++) {
            double z = tick * 1.5;
            memory.record(0, z);
            decision = explorer.next(tick * 50L, 0, z, SOUTH);
            lead = decision.lead();
        }
        assertNotNull(lead, "the trail meets explicit new terrain at z=400");
        assertEquals(TrailExplorer.LeadKind.TRAIL_END, lead.kind());
        // The band, 48 blocks wide either side, ends at z=448.
        assertTrue(lead.z() > 250 && lead.z() < 448, "reported near the end: " + lead.z());
        assertTrue(lead.trailBlocks() >= 192);
        assertEquals(ESCAPING, decision.state());
        assertFalse(decision.blocked());
    }

    @Test void unknownTerrainAheadIsAGapThatKeepsTheBearing() {
        var explorer = explorer(new TrailRouteMemory());
        explorer.setPreferOutbound(false);
        paint(explorer, 0, -100, 0, 200, 48, 0, 0, 150);
        explorer.next(0, 0, 0, SOUTH);
        // Beyond z=150 nothing is known: no frontier, no trail.
        for (int tick = 1; tick <= 200; tick++) {
            var decision = explorer.next(tick, 0, 150 + tick, SOUTH);
            assertNotEquals(ESCAPING, decision.state(), "an unknown gap is not an end, tick " + tick);
            assertNull(decision.lead());
        }
        assertEquals(SEEKING, explorer.next(201, 0, 351, SOUTH).state());
    }

    @Test void isolatedNewChunksAheadCannotEndATrail() {
        var explorer = explorer(new TrailRouteMemory());
        explorer.setPreferOutbound(false);
        paint(explorer, 0, -100, 0, 1_000, 48, 0, 0, 300);
        explorer.next(0, 0, 0, SOUTH);
        explorer.observe(0, 4, false);
        explorer.observe(-1, 7, false);
        var decision = explorer.next(1, 0, 1, SOUTH);
        assertEquals(FOLLOWING, decision.state());
        assertNull(decision.lead());
    }

    @Test void crossingABandBrieflyIsNotReportedAsATrailEnd() {
        var memory = new TrailRouteMemory();
        var explorer = explorer(memory);
        explorer.setPreferOutbound(false);
        // A short stub: 120 blocks of trail, then explicit new terrain.
        paint(explorer, 0, -40, 0, 120, 48, 0, 0, 400);
        for (int tick = 0; tick <= 200; tick++) {
            memory.record(0, tick * 1.5);
            assertNull(explorer.next(tick, 0, tick * 1.5, SOUTH).lead(), "tick " + tick);
        }
    }

    @Test void oldChunksAlreadySeenFromEarlierFlightsDoNotAttractASearch() {
        var memory = new TrailRouteMemory();
        // An earlier session flew straight along the band 300 blocks to the west.
        memory.record(300, -500);
        memory.record(300, 1_500);
        var restored = TrailRouteMemory.restore(memory.snapshot().keys());
        var explorer = explorer(restored);
        paint(explorer, 300, -500, 300, 1_500, 60, 0, 0, 400);
        var decision = explorer.next(0, 0, 0, SOUTH);
        assertEquals(SEARCHING, decision.state());
        assertEquals(SOUTH, decision.yaw(), 1e-3, "the flown band is not a lead");
    }

    @Test void aTrailRunningIntoAFlownRouteEndsWithoutALead() {
        var earlier = new TrailRouteMemory();
        // An earlier session flew the western part of this band.
        earlier.record(-1_000, 600);
        earlier.record(-300, 600);
        var memory = TrailRouteMemory.restore(earlier.snapshot().keys());
        var explorer = explorer(memory);
        explorer.setPreferOutbound(false);
        double x = 600, z = 600;
        float yaw = WEST;
        int alongFlownPart = 0;
        for (int tick = 0; tick < 1_200; tick++) {
            paint(explorer, 1_000, 600, -1_000, 600, 64, x, z, 200);
            memory.record(x, z);
            var decision = explorer.next(tick * 50L, x, z, yaw);
            assertNull(decision.lead(), "joining a flown route is not a trail end, tick " + tick);
            assertFalse(decision.blocked());
            yaw = ElytraSteer.turn(yaw, decision.yaw(), 10);
            x -= Math.sin(Math.toRadians(yaw)) * 1.5;
            z += Math.cos(Math.toRadians(yaw)) * 1.5;
            if (x < -300 && Math.abs(z - 600) < 96) alongFlownPart++;
        }
        assertTrue(alongFlownPart < 60, "at most a brief crossing of the flown part, not a repeat: " + alongFlownPart);
    }

    @Test void whenEveryHeadingRepeatsHistoryItTakesTheLeastRepetitiveOneInsteadOfLanding() {
        var memory = new TrailRouteMemory();
        // A tight star of earlier flights through the start: every direction runs alongside one.
        for (int angle = 0; angle < 180; angle += 20) {
            double radians = Math.toRadians(angle);
            memory.record(-Math.cos(radians) * 500, -Math.sin(radians) * 500);
            memory.record(Math.cos(radians) * 500, Math.sin(radians) * 500);
            memory.breakContinuity();
        }
        var explorer = explorer(memory);
        var decision = explorer.next(0, 0, 0, SOUTH);
        assertEquals(ESCAPING, decision.state());
        assertFalse(decision.blocked(), "a forced exit, not a landing");
        // A forced exit is held without rechecking, so it is not immediately re-forced.
        for (int tick = 1; tick < 100; tick++) {
            double radians = Math.toRadians(decision.yaw());
            decision = explorer.next(tick, -Math.sin(radians) * tick, Math.cos(radians) * tick, decision.yaw());
            assertFalse(decision.blocked(), "tick " + tick);
        }
    }

    @Test void fullMemoryCannotCertifyAnyHeadingSoTheDecisionIsBlocked() {
        var memory = new TrailRouteMemory(1);
        memory.record(10_000, 10_000);
        assertTrue(memory.isFull());
        var decision = explorer(memory).next(0, 0, 0, SOUTH);
        assertTrue(decision.blocked());
    }

    @Test void theSpiralCirclesSpawnInTheSenseNearestTheStartingHeading() {
        for (float start : new float[] {WEST, EAST}) {
            var explorer = explorer(new TrailRouteMemory());
            explorer.setPattern(TrailExplorer.Pattern.SPIRAL, 768);
            // At 3000 south of spawn (+z), tangents point east or west.
            var decision = explorer.next(0, 0, 3_000, start);
            assertEquals(SEARCHING, decision.state());
            assertTrue(Math.abs(ElytraSteer.wrap(decision.yaw() - start)) < 20,
                "start " + start + " gave " + decision.yaw());
        }
    }

    @Test void afterAnExcursionTheSpiralHeadsBackToItsCircleDiagonally() {
        var explorer = explorer(new TrailRouteMemory());
        explorer.setPattern(TrailExplorer.Pattern.SPIRAL, 768);
        explorer.next(0, 0, 3_000, WEST);
        // Two thousand blocks further out on the same bearing, the search turns inward as well as around.
        var decision = explorer.next(1, 0, 5_000, WEST);
        double radians = Math.toRadians(decision.yaw());
        double inward = Math.cos(radians) * -1; // +z is outward here, so a negative z component is inward
        double around = Math.sin(radians);      // west (-x) is the circle's sense
        assertTrue(inward > 0.4 && around > 0.4, "diagonal return: yaw " + decision.yaw());
    }

    @Test void straightSearchHoldsTheStartingHeading() {
        var explorer = explorer(new TrailRouteMemory());
        for (int tick = 0; tick < 50; tick++) assertEquals(37, explorer.next(tick, 3_000 + tick, 0, 37).yaw(), 1e-3);
    }

    @Test void neutralClassificationForgetsAndFreshReplacesOld() {
        var explorer = explorer(new TrailRouteMemory());
        paint(explorer, -400, 200, -100, 200, 48, 0, 0, 400);
        float toward = explorer.next(0, 0, 0, SOUTH).yaw();
        assertTrue(toward > 20);
        var other = explorer(new TrailRouteMemory());
        paint(other, -400, 200, -100, 200, 48, 0, 0, 400);
        for (int cx = -30; cx <= 0; cx++) for (int cz = 0; cz <= 30; cz++) other.observe(cx, cz, false, false);
        assertEquals(SOUTH, other.next(0, 0, 0, SOUTH).yaw(), 1e-3, "forgotten chunks are unknown, not trail");
    }

    @Test void validatesInputsTimeAndConfiguration() {
        var explorer = explorer(new TrailRouteMemory());
        assertThrows(IllegalArgumentException.class, () -> explorer.next(0, Double.NaN, 0, 0));
        assertThrows(IllegalArgumentException.class, () -> explorer.next(0, 0, 0, Float.POSITIVE_INFINITY));
        explorer.next(10, 0, 0, 0);
        assertThrows(IllegalArgumentException.class, () -> explorer.next(9, 0, 0, 0));
        assertThrows(IllegalArgumentException.class, () -> new TrailExplorer.Config(63, 256, 96));
        assertThrows(IllegalArgumentException.class, () -> new TrailExplorer.Config(256, 513, 96));
        assertThrows(IllegalArgumentException.class, () -> new TrailExplorer.Config(256, 256, 63));
        assertThrows(IllegalArgumentException.class, () -> explorer.setPattern(TrailExplorer.Pattern.SPIRAL, 64));
    }

    /** The same route access as Trail Hunter's navigator. */
    static TrailExplorer explorer(TrailRouteMemory memory) {
        return new TrailExplorer(new TrailExplorer.RouteAccess() {
            @Override public boolean wouldRevisit(double x, double z, float yaw, double lookAhead) {
                double radians = Math.toRadians(yaw);
                return memory.wouldRetrace(x, z, x - Math.sin(radians) * lookAhead, z + Math.cos(radians) * lookAhead);
            }

            @Override public int overlap(double x, double z, float yaw, double lookAhead) {
                double radians = Math.toRadians(yaw);
                return memory.parallelOverlap(x, z, x - Math.sin(radians) * lookAhead, z + Math.cos(radians) * lookAhead);
            }

            @Override public boolean flownNear(int chunkX, int chunkZ, int radiusChunks) {
                return memory.isNearEarlierFlight(chunkX, chunkZ, radiusChunks);
            }
        });
    }

    /** Classifies every chunk within radius of (cx, cz): trail within halfWidth of the segment, else new terrain. */
    static void paint(TrailExplorer explorer, double x1, double z1, double x2, double z2, double halfWidth,
                      double cx, double cz, double radius) {
        var segment = new TrailSim.World.Segment(x1, z1, x2, z2, halfWidth);
        for (int x = (int) Math.floor((cx - radius) / 16); x <= (int) Math.floor((cx + radius) / 16); x++)
            for (int z = (int) Math.floor((cz - radius) / 16); z <= (int) Math.floor((cz + radius) / 16); z++) {
                double px = x * 16 + 8, pz = z * 16 + 8;
                if (Math.hypot(px - cx, pz - cz) > radius) continue;
                explorer.observe(x, z, segment.distance(px, pz) <= halfWidth);
            }
    }
}
