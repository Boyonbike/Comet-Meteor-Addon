/*
 * Original Comet implementation by Boyonabike.
 * Independent: tests written against the project's own interfaces; no third-party code.
 * SPDX-License-Identifier: CC0-1.0
 */

package dev.tntcoating.core.combat;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;

/** Phase 4.2 planner integration: the tactic only changes bounds, never Phase 3 acceptance by surprise. */
class MotionPredictionPlannerTest {
    private static DirectSettings settings(boolean motion, int ticks) {
        DirectSettings base = DirectSettings.conservative(MotionFakeWorld.SAFETY);
        return new DirectSettings(base.targetRange(), base.placeRange(), base.attackRange(),
            base.wallsRange(), base.safety(), base.ownOnly(), base.minAgeTicks(), base.maxAttempts(),
            base.faceplaceEnabled(), base.faceplaceHealth(), base.faceplaceArmourFraction(),
            base.twoAirProfile(), base.supportEnabled(), base.switchDelayTicks(), base.rotationsOn(),
            base.pauseOnEating(), base.pauseOnDrinking(), base.pauseOnMining(), base.pauseOnScreen(),
            base.pauseOnBaritone(), base.healthFloor(), base.pauseOnLag(), base.minTps(),
            base.staleFeedbackNanos(), motion, ticks);
    }

    private static DirectPlanState state() {
        return new DirectPlanState(2L, 7L, 100, null, Map.of(), 0);
    }

    private static Optional<DirectPlan> plan(DirectSettings settings, MotionFakeWorld world) {
        return new DirectPlanner(settings).plan(world, state());
    }

    private static MotionSample floatUp() {
        return MotionSample.of(2.5, 64.5, 0.5, 0.0, 0.21, 0.0, false);
    }

    @Test
    void offKeepsThePhase3BaselineAndNeverReadsMotion() {
        MotionFakeWorld world = new MotionFakeWorld();
        DirectTarget target = world.addTarget(MotionFakeWorld.TARGET_POSITION);
        world.damage.price = priced -> new TargetEstimate(4.0, 10.0, 12.0);
        world.setMotion(target.entity(), floatUp());

        DirectPlan plan = plan(settings(false, 2), world).orElseThrow();

        assertEquals(new TargetEstimate(4.0, 10.0, 12.0), plan.context().estimate());
        assertEquals(0, world.motionCalls, "the off tactic must not consult the motion model");
    }

    @Test
    void onPricesTheTargetAtTheLeastExposedReachablePosition() {
        MotionFakeWorld world = new MotionFakeWorld();
        DirectTarget target = world.addTarget(MotionFakeWorld.TARGET_POSITION);
        world.damage.price = priced -> new TargetEstimate(
            8.0 - (priced.position().y() - 64.5) * 20.0,
            10.0 - (priced.position().y() - 64.5) * 20.0,
            12.0 - (priced.position().y() - 64.5) * 20.0);
        world.setMotion(target.entity(), floatUp());

        DirectPlan plan = plan(settings(true, 2), world).orElseThrow();

        assertEquals(0.0, plan.context().estimate().guaranteedLower(), 1e-9,
            "a hull cannot prove the least exposed point, so the guarantee is zero");
        assertEquals(8.2, plan.context().estimate().expected(), 1e-9);
    }

    @Test
    void onUnknownMotionFallsBackToTheExactBaseline() {
        MotionFakeWorld world = new MotionFakeWorld();
        world.addTarget(MotionFakeWorld.TARGET_POSITION);
        world.damage.price = priced -> new TargetEstimate(4.0, 10.0, 12.0);

        DirectPlan plan = plan(settings(true, 2), world).orElseThrow();

        assertEquals(new TargetEstimate(4.0, 10.0, 12.0), plan.context().estimate());
    }

    @Test
    void onUpwardPredictionNeverRaisesTheTargetLowerBound() {
        MotionFakeWorld world = new MotionFakeWorld();
        DirectTarget target = world.addTarget(MotionFakeWorld.TARGET_POSITION);
        world.damage.price = priced -> priced.position().y() > 64.55
            ? new TargetEstimate(50.0, 60.0, 70.0)
            : new TargetEstimate(3.0, 10.0, 12.0);
        world.setMotion(target.entity(), floatUp());

        DirectPlan plan = plan(settings(true, 2), world).orElseThrow();

        assertEquals(0.0, plan.context().estimate().guaranteedLower(), 1e-9,
            "a more exposed predicted position must not create a guaranteed lower bound");
        assertFalse(plan.context().targetOverride());
    }

    @Test
    void unknownPriorHurtStaysAtZero() {
        MotionFakeWorld world = new MotionFakeWorld();
        DirectTarget target = world.addTarget(MotionFakeWorld.TARGET_POSITION,
            new EffectiveHealth(0.0, 0.0, Capability.UNKNOWN, 0L));
        world.damage.price = priced -> new TargetEstimate(4.0, 10.0, 12.0);
        world.setMotion(target.entity(), floatUp());

        DirectPlan plan = plan(settings(true, 2), world).orElseThrow();

        assertEquals(0.0, plan.context().targetHealth().value(), 1e-9,
            "hurt tracking is not part of Phase 4.2; unknown prior damage remains zero");
        assertEquals(Capability.UNKNOWN, plan.context().targetHealth().cap());
        assertEquals(0.0, target.health().value(), 1e-9, "planning must not mutate the target health");
    }

    @Test
    void horizonComesFromTheSettingsNotAGlobalMaximum() {
        MotionFakeWorld world = new MotionFakeWorld();
        DirectTarget target = world.addTarget(MotionFakeWorld.TARGET_POSITION);
        world.damage.price = priced -> priced.position().y() > 66.5
            ? new TargetEstimate(2.0, 10.0, 12.0)
            : new TargetEstimate(2.0, 2.0, 3.0);
        world.setMotion(target.entity(), MotionSample.of(2.5, 64.5, 0.5, 0.0, 1.0, 0.0, false));

        assertTrue(plan(settings(true, 2), world).isEmpty(),
            "two ticks of motion stay below the minimum target damage");
        DirectPlan threeTicks = plan(settings(true, 3), world).orElseThrow();
        assertEquals(0.0, threeTicks.context().estimate().guaranteedLower(), 1e-9);
        assertEquals(6.0, threeTicks.context().estimate().expected(), 1e-9);
    }

    @Test
    void lethalOverrideCannotFireFromARaisedLower() {
        MotionFakeWorld world = new MotionFakeWorld();
        DirectTarget target = world.addTarget(MotionFakeWorld.TARGET_POSITION,
            new EffectiveHealth(5.0, 0.0, Capability.KNOWN, 0L));
        world.damage.price = priced -> priced.position().y() > 64.55
            ? new TargetEstimate(50.0, 60.0, 70.0)
            : new TargetEstimate(3.0, 10.0, 12.0);
        world.setMotion(target.entity(), floatUp());

        DirectPlan plan = plan(settings(true, 2), world).orElseThrow(
            () -> new AssertionError("the normal minimum-damage path still passes on expected damage"));
        TargetEstimate estimate = plan.context().estimate();
        assertEquals(0.0, estimate.guaranteedLower(), 1e-9);
        CombatDamagePolicy policy = new CombatDamagePolicy(
            new EffectiveHealth(20.0, 0.0, Capability.KNOWN, 0L), target.health());
        assertFalse(policy.allows(estimate, plan.context().exposure(), MotionFakeWorld.SAFETY, true, false),
            "a lethal override cannot become eligible from prediction");
    }

    @Test
    void weakMotionLowersOpportunityConfidenceButKeepsTheSafetyBounds() {
        MotionFakeWorld world = new MotionFakeWorld();
        DirectTarget strong = world.addTarget(new Vec(2.5, 64.5, 0.5));
        DirectTarget weak = world.addTarget(new Vec(2.5, 64.5, 1.5));
        world.damage.price = priced -> new TargetEstimate(4.0, 10.0, 12.0);
        world.setMotion(strong.entity(), floatUp());
        // Two blocked horizontal steps: the model reports WEAK, so no bound may move, only the ranking.
        world.setCollisions(cell -> cell.x() >= 3);
        world.setMotion(weak.entity(), MotionSample.of(2.5, 64.5, 1.5, 0.6, 0.0, 0.0, false));

        List<EntityKey> shortlist = new DirectPlanner(settings(true, 2)).selectTargets(world, state());
        assertEquals(2, shortlist.size());
        assertEquals(strong.entity(), shortlist.get(0),
            "the weak-motion target is ranked lower even with the same expected damage");
        assertEquals(weak.entity(), shortlist.get(1));

        DirectPlan strongPlan = plan(settings(true, 2), world).orElseThrow();
        assertEquals(strong.entity(), strongPlan.target());
        assertEquals(1.0, strongPlan.context().confidence(), 1e-9);
        assertEquals(0.0, strongPlan.context().estimate().guaranteedLower(), 1e-9);

        MotionFakeWorld weakOnly = new MotionFakeWorld();
        DirectTarget weakTarget = weakOnly.addTarget(new Vec(2.5, 64.5, 1.5));
        weakOnly.damage.price = priced -> new TargetEstimate(4.0, 10.0, 12.0);
        weakOnly.setCollisions(cell -> cell.x() >= 3);
        weakOnly.setMotion(weakTarget.entity(),
            MotionSample.of(2.5, 64.5, 1.5, 0.6, 0.0, 0.0, false));

        DirectPlan weakPlan = plan(settings(true, 2), weakOnly).orElseThrow();
        assertEquals(0.5, weakPlan.context().confidence(), 1e-9);
        assertEquals(new TargetEstimate(4.0, 10.0, 12.0), weakPlan.context().estimate(),
            "weak motion must not move the guaranteed lower or expected bounds");
        assertEquals(1.0, weakPlan.context().exposure().selfUpper().beforeAbsorption(), 1e-9);
    }
}
