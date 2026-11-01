/*
 * Original Comet implementation by Boyonabike.
 * Independent: tests written against the project's own interfaces; no third-party code.
 * SPDX-License-Identifier: CC0-1.0
 */

package dev.tntcoating.core.combat;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;

/** Phase 4.2 pure motion-prediction semantics: one-sided bounds, confidence fallback, horizons. */
class MotionPredictionTest {
    private static final Vec CENTRE = new Vec(0.5, 64.0, 0.5);
    private static final Set<Cell> OVERLAYS = Set.of();
    private static final MitigationEnvelope ENVELOPE = new MitigationEnvelope(1L);

    private static MotionSample rising(double x, double y, double z) {
        return MotionSample.of(x, y, z, 0.0, 0.42, 0.0, false);
    }

    private static ExposureBound exposure(double self, Map<EntityKey, MitigatedDamage> friends) {
        return new ExposureBound(new MitigatedDamage(self), friends, ENVELOPE);
    }

    @Test
    void unknownMotionKeepsTheExactPhase3Baseline() {
        MotionFakeWorld world = new MotionFakeWorld();
        DirectTarget target = world.addTarget(MotionFakeWorld.TARGET_POSITION);
        TargetEstimate baseline = new TargetEstimate(4.0, 10.0, 12.0);

        TargetEstimate result = MotionPrediction.targetEstimate(world, target, CENTRE, OVERLAYS, baseline, 2);

        assertSame(baseline, result, "unknown motion must not construct a new estimate");
        assertEquals(1, world.motionCalls, "one sample lookup is made, and it reports unknown");
    }

    @Test
    void teleportOrSetbackAndNonPositiveHorizonsKeepTheBaseline() {
        MotionFakeWorld world = new MotionFakeWorld();
        DirectTarget target = world.addTarget(MotionFakeWorld.TARGET_POSITION);
        TargetEstimate baseline = new TargetEstimate(4.0, 10.0, 12.0);
        world.setMotion(target.entity(),
            MotionSample.of(2.5, 64.5, 0.5, 0.0, 0.42, 0.0, false).invalidated());

        assertSame(baseline,
            MotionPrediction.targetEstimate(world, target, CENTRE, OVERLAYS, baseline, 2));
        assertSame(baseline,
            MotionPrediction.targetEstimate(world, target, CENTRE, OVERLAYS, baseline, 0));
    }

    @Test
    void weakConfidenceNeverChangesTheExposureBounds() {
        MotionFakeWorld world = new MotionFakeWorld();
        EntityKey friend = world.addFriend(new Vec(0.5, 64.5, 3.0), 20.0);
        ExposureBound baseline = exposure(1.0, Map.of(friend, new MitigatedDamage(1.0)));
        world.damage.selfUpperAt = feet -> new MitigatedDamage(7.0);
        world.damage.friendUpperAt.put(friend, feet -> new MitigatedDamage(7.0));
        // A standing sample over a solid floor lands inside the horizon: WEAK, then LOST when grounded.
        world.setMotion(MotionFakeWorld.SELF, MotionSample.of(0.5, 64.5, 0.5, 0.0, 0.0, 0.0, true));
        world.setMotion(friend, MotionSample.of(0.5, 64.5, 3.0, 0.0, 0.0, 0.0, true));
        world.setCollisions(cell -> cell.y() == 64);

        ExposureBound result = MotionPrediction.exposure(world, CENTRE, OVERLAYS, baseline);

        assertEquals(1.0, result.selfUpper().beforeAbsorption(), 1e-9);
        assertEquals(1.0, result.friendUpper().get(friend).beforeAbsorption(), 1e-9);
        assertSame(ENVELOPE, result.envelope());
    }

    @Test
    void targetLowerUsesTheLeastExposedReachablePosition() {
        MotionFakeWorld world = new MotionFakeWorld();
        DirectTarget target = world.addTarget(MotionFakeWorld.TARGET_POSITION);
        world.damage.price = priced -> new TargetEstimate(
            8.0 - (priced.position().y() - 64.5) * 20.0,
            10.0 - (priced.position().y() - 64.5) * 20.0,
            12.0 - (priced.position().y() - 64.5) * 20.0);
        // Two ticks of a small upward float: the hull tops out at y = 64.68.
        world.setMotion(target.entity(), MotionSample.of(2.5, 64.5, 0.5, 0.0, 0.21, 0.0, false));
        TargetEstimate baseline = new TargetEstimate(8.0, 10.0, 12.0);

        TargetEstimate result = MotionPrediction.targetEstimate(world, target, CENTRE, OVERLAYS, baseline, 2);

        // A hull is not an exhaustive reachable set: an interior point can be less exposed than every
        // corner, so the only honest guaranteed lower bound is zero.
        assertEquals(0.0, result.guaranteedLower(), 1e-9,
            "no sampled corner can prove the least exposed reachable point");
        assertEquals(8.2, result.expected(), 1e-9, "expected is the mean over reachable positions");
        assertEquals(12.0, result.optimistic(), 1e-9);
        assertTrue(result.guaranteedLower() < baseline.guaranteedLower());
    }

    @Test
    void targetLowerNeverRisesWithoutEvidence() {
        MotionFakeWorld world = new MotionFakeWorld();
        DirectTarget target = world.addTarget(MotionFakeWorld.TARGET_POSITION);
        // Every predicted position claims more damage: a lower bound must still not be raised by prediction.
        world.damage.price = priced -> new TargetEstimate(50.0, 60.0, 70.0);
        world.setMotion(target.entity(), rising(2.5, 64.5, 0.5));
        TargetEstimate baseline = new TargetEstimate(8.0, 10.0, 12.0);

        TargetEstimate result = MotionPrediction.targetEstimate(world, target, CENTRE, OVERLAYS, baseline, 2);

        assertEquals(0.0, result.guaranteedLower(), 1e-9);
        assertTrue(result.guaranteedLower() <= baseline.guaranteedLower());
    }

    @Test
    void selfUpperUsesTheMostExposedReachablePositionIncludingVerticalMovement() {
        MotionFakeWorld world = new MotionFakeWorld();
        EntityKey friend = world.addFriend(new Vec(0.5, 64.5, 3.0), 20.0);
        ExposureBound baseline = exposure(1.0, Map.of(friend, new MitigatedDamage(1.0)));
        world.damage.selfUpperAt = feet -> new MitigatedDamage(feet.y() > 64.6 ? 7.0 : 1.0);
        world.setMotion(MotionFakeWorld.SELF, rising(0.5, 64.5, 0.5));

        ExposureBound result = MotionPrediction.exposure(world, new Vec(0.5, 65.0, 0.5), OVERLAYS,
            baseline);

        assertEquals(7.0, result.selfUpper().beforeAbsorption(), 1e-9,
            "the vertical escape reaches the more exposed hull point");
        assertEquals(1.0, result.friendUpper().get(friend).beforeAbsorption(), 1e-9);
    }

    @Test
    void selfAndFriendUppersNeverFallBelowTheBaseline() {
        MotionFakeWorld world = new MotionFakeWorld();
        EntityKey friend = world.addFriend(new Vec(0.5, 64.5, 3.0), 20.0);
        ExposureBound baseline = exposure(1.0, Map.of(friend, new MitigatedDamage(1.0)));
        world.damage.selfUpperAt = feet -> new MitigatedDamage(0.1);
        world.damage.friendUpperAt.put(friend, feet -> new MitigatedDamage(0.2));
        world.setMotion(MotionFakeWorld.SELF, rising(0.5, 64.5, 0.5));
        world.setMotion(friend, rising(0.5, 64.5, 3.0));

        ExposureBound result = MotionPrediction.exposure(world, CENTRE, OVERLAYS, baseline);

        assertEquals(1.0, result.selfUpper().beforeAbsorption(), 1e-9);
        assertEquals(1.0, result.friendUpper().get(friend).beforeAbsorption(), 1e-9);
    }

    @Test
    void friendUpperUsesTheMostExposedReachablePosition() {
        MotionFakeWorld world = new MotionFakeWorld();
        EntityKey friend = world.addFriend(new Vec(0.5, 64.5, 3.0), 20.0);
        ExposureBound baseline = exposure(1.0, Map.of(friend, new MitigatedDamage(1.0)));
        world.damage.friendUpperAt.put(friend, feet -> new MitigatedDamage(feet.y() > 64.6 ? 5.0 : 1.0));
        world.setMotion(friend, rising(0.5, 64.5, 3.0));

        ExposureBound result = MotionPrediction.exposure(world, new Vec(0.5, 65.0, 0.5), OVERLAYS,
            baseline);

        assertEquals(5.0, result.friendUpper().get(friend).beforeAbsorption(), 1e-9);
    }

    @Test
    void targetHorizonComesFromTheCallSiteAndSelfHorizonStaysOneTick() {
        MotionFakeWorld world = new MotionFakeWorld();
        DirectTarget target = world.addTarget(MotionFakeWorld.TARGET_POSITION);
        world.damage.price = priced -> new TargetEstimate(
            10.0 - (priced.position().y() - 64.5),
            10.0 - (priced.position().y() - 64.5),
            10.0 - (priced.position().y() - 64.5));
        world.setMotion(target.entity(), MotionSample.of(2.5, 64.5, 0.5, 0.0, 1.0, 0.0, false));
        TargetEstimate baseline = new TargetEstimate(10.0, 10.0, 10.0);

        TargetEstimate two = MotionPrediction.targetEstimate(world, target, CENTRE, OVERLAYS, baseline, 2);
        TargetEstimate three = MotionPrediction.targetEstimate(world, target, CENTRE, OVERLAYS, baseline, 3);

        assertNotEquals(two.expected(), three.expected(), "the horizon parameter controls the hull");
        assertEquals(9.12, two.expected(), 1e-9);
        assertEquals(8.74, three.expected(), 1e-9);

        List<Double> selfFeet = new ArrayList<>();
        world.damage.selfUpperAt = feet -> {
            selfFeet.add(feet.y());
            return new MitigatedDamage(1.0);
        };
        world.setMotion(MotionFakeWorld.SELF, rising(0.5, 64.5, 0.5));
        MotionPrediction.exposure(world, CENTRE, OVERLAYS, exposure(1.0, Map.of()));

        assertFalse(selfFeet.isEmpty());
        for (double feet : selfFeet) {
            assertTrue(feet <= 64.84 + 1e-9, "self prediction is fixed at one tick: " + feet);
        }
    }

    @Test
    void lethalOverrideCannotFireFromARaisedLowerBound() {
        MotionFakeWorld world = new MotionFakeWorld();
        DirectTarget target = world.addTarget(MotionFakeWorld.TARGET_POSITION,
            new EffectiveHealth(5.0, 0.0, Capability.KNOWN, 0L));
        // Prediction claims a much more exposed position; a lower bound must never be raised by it.
        world.damage.price = priced -> new TargetEstimate(50.0, 60.0, 70.0);
        world.setMotion(target.entity(), rising(2.5, 64.5, 0.5));
        TargetEstimate baseline = new TargetEstimate(3.0, 10.0, 12.0);

        TargetEstimate result = MotionPrediction.targetEstimate(world, target, CENTRE, OVERLAYS, baseline, 2);
        assertEquals(0.0, result.guaranteedLower(), 1e-9);

        CombatDamagePolicy policy = new CombatDamagePolicy(
            new EffectiveHealth(20.0, 0.0, Capability.KNOWN, 0L),
            new EffectiveHealth(5.0, 0.0, Capability.KNOWN, 0L));
        assertFalse(policy.allows(result, exposure(1.0, Map.of()), MotionFakeWorld.SAFETY, true, false),
            "the override needs guaranteedLower >= known target health, which prediction did not raise");
    }

    @Test
    void hullCornersCannotProveTheLeastExposedInteriorPoint() {
        MotionFakeWorld world = new MotionFakeWorld();
        DirectTarget target = world.addTarget(MotionFakeWorld.TARGET_POSITION);
        // Every sampled corner is fully exposed; an interior reachable point is nearly covered.
        world.damage.price = priced -> {
            double y = priced.position().y();
            boolean interior = y > 64.6 && y < 65.0;
            return interior
                ? new TargetEstimate(2.0, 3.0, 4.0)
                : new TargetEstimate(50.0, 60.0, 70.0);
        };
        world.setMotion(target.entity(), MotionSample.of(2.5, 64.5, 0.5, 0.0, 0.42, 0.0, false));
        TargetEstimate baseline = new TargetEstimate(8.0, 10.0, 12.0);

        TargetEstimate result = MotionPrediction.targetEstimate(world, target, CENTRE, OVERLAYS, baseline, 2);

        assertEquals(0.0, result.guaranteedLower(), 1e-9,
            "a corner minimum would have claimed a guarantee the interior safe point disproves");
    }

    @Test
    void selfUpperWorstUsesTheInteriorHullPointClosestToTheCrystal() {
        MotionFakeWorld world = new MotionFakeWorld();
        ExposureBound baseline = exposure(1.0, Map.of());
        // Corners price low; only the interior point closest to the crystal prices high.
        world.damage.selfUpperAt = feet -> {
            boolean interior = feet.y() > 64.6 && feet.y() < 64.8;
            return interior ? new MitigatedDamage(7.0) : new MitigatedDamage(0.1);
        };
        world.setMotion(MotionFakeWorld.SELF, rising(0.5, 64.5, 0.5));

        ExposureBound result = MotionPrediction.exposure(world, new Vec(0.5, 64.7, 0.5), OVERLAYS,
            baseline);

        assertEquals(7.0, result.selfUpper().beforeAbsorption(), 1e-9,
            "the whole-hull bound must use the hull point closest to the crystal, not corner maxima");
    }

    @Test
    void friendUpperWorstUsesTheInteriorHullPointClosestToTheCrystal() {
        MotionFakeWorld world = new MotionFakeWorld();
        EntityKey friend = world.addFriend(new Vec(0.5, 64.5, 3.0), 20.0);
        ExposureBound baseline = exposure(1.0, Map.of(friend, new MitigatedDamage(1.0)));
        world.damage.friendUpperAt.put(friend, feet -> {
            boolean interior = feet.y() > 64.6 && feet.y() < 64.8;
            return interior ? new MitigatedDamage(5.0) : new MitigatedDamage(0.1);
        });
        world.setMotion(friend, rising(0.5, 64.5, 3.0));

        ExposureBound result = MotionPrediction.exposure(world, new Vec(0.5, 64.7, 3.0), OVERLAYS,
            baseline);

        assertEquals(5.0, result.friendUpper().get(friend).beforeAbsorption(), 1e-9,
            "the whole-hull friend bound must not miss the interior worst point");
    }
}
