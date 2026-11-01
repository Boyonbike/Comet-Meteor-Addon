/*
 * Original Comet implementation by Boyonabike.
 * Independent: tests written against the project's own interfaces; no third-party code.
 * SPDX-License-Identifier: CC0-1.0
 */

package dev.tntcoating.core.combat.mc;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.tntcoating.core.combat.Cell;
import dev.tntcoating.core.combat.CombatDamagePolicy;
import dev.tntcoating.core.combat.DirectSettings;
import dev.tntcoating.core.combat.DirectTarget;
import dev.tntcoating.core.combat.EntityKey;
import dev.tntcoating.core.combat.MitigatedDamage;
import dev.tntcoating.core.combat.MotionSample;
import dev.tntcoating.core.combat.TargetEstimate;
import dev.tntcoating.core.combat.Vec;
import dev.tntcoating.core.combat.mc.CombatTestFakes.FakeSink;
import org.junit.jupiter.api.Test;

/**
 * Phase 4.2 end-to-end behaviour on the fake world/connection harness: the tactic is opt-in, only ever
 * moves bounds in the safe direction, and never introduces a second send path.
 */
class MotionPredictionEngineTest {
    private static final Vec SELF = new Vec(0.5, -60.0, 0.5);
    private static final Cell BASE = new Cell(2, -61, 0);
    private static final Vec CRYSTAL_POSITION = new Vec(2.5, -60.0, 0.5);
    private static final Vec TARGET = new Vec(2.5, -60.0, 3.0);

    private static DirectSettings settings(boolean motion, int ticks) {
        DirectSettings base = DirectSettings.conservative(CombatDamagePolicy.conservativeSafety());
        return new DirectSettings(base.targetRange(), base.placeRange(), base.attackRange(),
            base.wallsRange(), base.safety(), base.ownOnly(), base.minAgeTicks(), base.maxAttempts(),
            base.faceplaceEnabled(), base.faceplaceHealth(), base.faceplaceArmourFraction(),
            base.twoAirProfile(), base.supportEnabled(), base.switchDelayTicks(), base.rotationsOn(),
            base.pauseOnEating(), base.pauseOnDrinking(), base.pauseOnMining(), base.pauseOnScreen(),
            base.pauseOnBaritone(), base.healthFloor(), base.pauseOnLag(), base.minTps(),
            base.staleFeedbackNanos(), motion, ticks);
    }

    private static EngineHarness harness(boolean motion, int ticks) {
        EngineHarness harness = EngineHarness.create(settings(motion, ticks), SELF);
        harness.setBase(BASE);
        harness.addTarget(TARGET);
        return harness;
    }

    private static void raisedSelfBound(EngineHarness harness) {
        // The whole-hull worst bound is priced at the hull point closest to the crystal; any value above
        // max-self-damage must reject the placement regardless of which point the model samples.
        harness.setSelfUpperAt(feet -> new MitigatedDamage(7.0));
    }

    private static MotionSample floatUp(Vec feet) {
        return MotionSample.of(feet.x(), feet.y(), feet.z(), 0.0, 0.42, 0.0, false);
    }

    @Test
    void offKeepsPhase3AndNeverSamplesMotion() {
        EngineHarness harness = harness(false, 2);

        harness.tick();

        assertEquals(1, harness.placeCount());
        assertEquals(0, harness.motionCalls(), "an off tactic must not consult the motion model");
    }

    @Test
    void onWithoutUsableMotionIsThePhase3Baseline() {
        EngineHarness off = harness(false, 2);
        EngineHarness on = harness(true, 2);

        off.tick(3);
        on.tick(3);

        assertEquals(1, off.placeCount());
        assertEquals(off.placeCount(), on.placeCount());
        assertEquals(off.sink.attempts, on.sink.attempts);
        assertTrue(on.motionCalls() > 0, "the on tactic does ask for samples");
    }

    @Test
    void raisedSelfUpperOnlyBlocksDispatch() {
        EngineHarness harness = harness(true, 2);
        raisedSelfBound(harness);
        harness.setMotion(harness.self(), floatUp(SELF));

        harness.tick();

        assertEquals(0, harness.placeCount(), "the whole-hull self bound exceeds max-self-damage");
        assertEquals(0, harness.sink.attempts, "nothing is sent");

        EngineHarness control = harness(false, 2);
        control.setSelfUpperAt(feet -> new MitigatedDamage(7.0));
        control.tick();
        assertEquals(1, control.placeCount(), "with motion prediction off the self bound stays at the baseline");
    }

    @Test
    void lowConfidenceSelfPredictionKeepsTheBaselineAndPlaces() {
        EngineHarness harness = harness(true, 2);
        raisedSelfBound(harness);
        harness.setMotion(harness.self(), MotionSample.of(SELF.x(), SELF.y(), SELF.z(), 0.0, 0.0, 0.0, true));
        harness.setCollision(cell -> cell.y() <= -61);

        harness.tick();

        assertEquals(1, harness.placeCount(), "LOST confidence falls back to the Phase 3 self upper bound");
    }

    @Test
    void raisedFriendUpperOnlyBlocksDispatch() {
        EngineHarness harness = harness(true, 2);
        EntityKey friend = harness.addFriend(new Vec(0.5, -60.0, 3.0), 20.0);
        harness.setFriendUpper(friend, 1.0);
        harness.setFriendUpperAt(friend, feet -> new MitigatedDamage(7.0));
        harness.setMotion(friend, floatUp(new Vec(0.5, -60.0, 3.0)));

        harness.tick();
        assertEquals(0, harness.placeCount(), "the most exposed friend position exceeds max-friend-damage");

        EngineHarness control = harness(false, 2);
        EntityKey controlFriend = control.addFriend(new Vec(0.5, -60.0, 3.0), 20.0);
        control.setFriendUpper(controlFriend, 1.0);
        control.setMotion(controlFriend, floatUp(new Vec(0.5, -60.0, 3.0)));
        control.tick();
        assertEquals(1, control.placeCount(), "with motion prediction off the friend bound stays at the baseline");
    }

    @Test
    void targetHorizonComesFromTheSettings() {
        EngineHarness twoTicks = harness(true, 2);
        DirectTarget target = twoTicks.world.targets.get(0);
        TargetEstimate current = new TargetEstimate(2.0, 2.0, 3.0);
        TargetEstimate exposed = new TargetEstimate(2.0, 10.0, 12.0);
        twoTicks.setTargetEstimateByPosition(priced -> priced.position().y() > -57.8 ? exposed : current);
        twoTicks.setMotion(target.entity(),
            MotionSample.of(target.position().x(), target.position().y(), target.position().z(),
                0.0, 1.0, 0.0, false));
        twoTicks.tick();
        assertEquals(0, twoTicks.placeCount(), "two ticks stay below the minimum target damage");

        EngineHarness threeTicks = harness(true, 3);
        DirectTarget threeTicksTarget = threeTicks.world.targets.get(0);
        threeTicks.setTargetEstimateByPosition(priced -> priced.position().y() > -57.8 ? exposed : current);
        threeTicks.setMotion(threeTicksTarget.entity(),
            MotionSample.of(threeTicksTarget.position().x(), threeTicksTarget.position().y(),
                threeTicksTarget.position().z(), 0.0, 1.0, 0.0, false));
        threeTicks.tick();
        assertEquals(1, threeTicks.placeCount(), "three ticks reach the exposed predicted position");
    }

    @Test
    void placementAndAttackSequenceIsUnchangedWithTacticOn() {
        EngineHarness off = harness(false, 2);
        EngineHarness on = harness(true, 2);

        off.tick();
        on.tick();
        assertEquals(1, off.placeCount());
        assertEquals(1, on.placeCount());

        EntityKey offCrystal = off.spawnCrystal(CRYSTAL_POSITION);
        EntityKey onCrystal = on.spawnCrystal(CRYSTAL_POSITION);
        assertEquals(1, off.attackCount());
        assertEquals(1, on.attackCount());
        assertEquals(offCrystal.id(), onCrystal.id());

        off.removeCrystal(offCrystal);
        on.removeCrystal(onCrystal);
        off.tick();
        on.tick();
        assertEquals(off.sink.attempts, on.sink.attempts, "same number of sends through the one send path");
        assertEquals(FakeSink.Kind.ATTACK, on.sink.last(FakeSink.Kind.ATTACK).kind());
    }
}
