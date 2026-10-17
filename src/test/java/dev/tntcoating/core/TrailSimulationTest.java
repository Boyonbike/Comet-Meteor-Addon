package dev.tntcoating.core;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Whole flights through simulated worlds, using Trail Hunter's navigator with view-distance chunk loading, a
 * send-rate cap, cache sampling and a turn-limited player. These pin down the behaviour the unit tests cannot:
 * that the bot actually reaches the bases at trail ends.
 */
class TrailSimulationTest {
    @Test void aTrailCrossedAtAnyAngleIsFollowedToTheBaseAtItsEnd() {
        for (int angle : new int[] {0, 30, 60, 90}) {
            var world = TrailSimReport.crossing(angle);
            var sim = new TrailSim(world, TrailSim.Mode.OLD_112, 8, 1.5, 8, 8, 0);
            sim.run(20 * 60 * 3);
            assertFalse(sim.stoppedBlocked, "angle " + angle);
            assertEquals(1, sim.basesFound.size(), "one of the two trail ends, angle " + angle);
            var end = sim.leads.stream().filter(lead -> lead.kind() == TrailExplorer.LeadKind.TRAIL_END).findFirst();
            assertTrue(end.isPresent(), "angle " + angle);
            assertTrue(world.bases.stream().anyMatch(base -> Math.hypot(base[0] - end.get().x(), base[1] - end.get().z()) < 200),
                "the trail-end lead is at the base, angle " + angle);
        }
    }

    @Test void aWideTrailIsFollowedAlongItsCentreNotItsEdge() {
        // A 176-block band along x at z=600, wider than one side's view, crossed from the south.
        var sim = new TrailSim(TrailSimReport.crossing(90), TrailSim.Mode.OLD_112, 8, 1.5, 8, 8, 0);
        double offset = 0;
        int samples = 0;
        for (int tick = 0; tick < 20 * 60 * 2; tick++) {
            sim.step();
            if (tick > 900 && sim.navigator.decision().state() == TrailExplorer.State.FOLLOWING) {
                offset += Math.abs(sim.z - 600);
                samples++;
            }
        }
        assertTrue(samples > 500);
        assertTrue(offset / samples < 24, "mean distance from the centre: " + offset / samples);
    }

    @Test void aBentTrailIsFollowedThroughTheBendToItsBase() {
        double radians = Math.toRadians(40);
        double endX = -Math.sin(radians) * 2_000, endZ = 1_500 + Math.cos(radians) * 2_000;
        var world = new TrailSim.World().trail(80, 0, -200, 0, 1_500, endX, endZ).base(endX, endZ, 100);
        var sim = new TrailSim(world, TrailSim.Mode.OLD_112, 8, 1.5, 8, 8, 0);
        sim.run(20 * 60 * 3);
        assertEquals(1, sim.basesFound.size());
        assertFalse(sim.stoppedBlocked);
    }

    @Test void huntingRealisticWorldsFindsBasesWithoutLanding() {
        int bases = 0;
        for (long seed = 1; seed <= 3; seed++) {
            var world = TrailSim.realistic(seed);
            double angle = seed * 0.77;
            double x = Math.cos(angle) * 3_000, z = Math.sin(angle) * 3_000;
            var sim = new TrailSim(world, TrailSim.Mode.OLD_112, 8, 1.5, x, z, ElytraSteer.wrap(ElytraSteer.yawTo(x, z) + 60));
            sim.navigator.setPattern(TrailExplorer.Pattern.SPIRAL, 768);
            sim.run(20 * 60 * 10);
            assertFalse(sim.stoppedBlocked, "seed " + seed);
            assertTrue(sim.ticksOverOld > sim.tick / 3, "most of the flight over old chunks, seed " + seed);
            bases += sim.basesFound.size();
        }
        assertTrue(bases >= 9, "bases found in 30 simulated minutes: " + bases);
    }

    @Test void noiseAloneNeverStartsAFollow() {
        var world = new TrailSim.World().noise(0.004, 0);
        var sim = new TrailSim(world, TrailSim.Mode.OLD_112, 8, 1.5, 3_000, 0, 0);
        sim.run(20 * 60 * 3);
        assertFalse(sim.stateTicks.containsKey(TrailExplorer.State.FOLLOWING), "isolated old chunks are not a trail");
        assertTrue(sim.leads.isEmpty());
    }
}
