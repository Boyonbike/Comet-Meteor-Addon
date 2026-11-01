/*
 * Original Comet implementation by Boyonabike.
 * Independent: written from the pinned 26.2 artifact and Meteor build 35 behaviour; no third-party code.
 * SPDX-License-Identifier: CC0-1.0
 */

package dev.tntcoating.core.combat;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.Test;

/** Phase 4.6: dangerous-crystal breaks and anti-piston blockers, both independently gated. */
final class DefensePlannerTest {
    private static final ConnectionKey CONN = new ConnectionKey(11L);
    private static final ActionGeneration GEN = new ActionGeneration(5L);
    private static final Safety SAFETY = CombatDamagePolicy.conservativeSafety();
    private static final EntityKey SELF = new EntityKey(CONN, 1, new UUID(0L, 1L));
    private static final Vec EYE = new Vec(0.5, 65.62, 0.5);
    private static final Vec FEET = new Vec(0.5, 64.5, 0.5);
    private static final Cell FEET_CELL = new Cell(0, 64, 0);
    private static final Cell HEAD_CELL = new Cell(0, 65, 0);
    private static final Vec CRYSTAL_POSITION = new Vec(2.5, 64.5, 0.5);
    private static final Cell CRYSTAL_CELL = new Cell(2, 64, 0);
    private static final Vec FAR_ENEMY = new Vec(6.4, 64.5, 0.5);
    private static final MitigationState NO_ARMOUR =
        new MitigationState(CombatDifficulty.NORMAL, 0.0, 0.0, 0.0, 0, false, false, false);

    private static final DirectSettings OFF = DirectSettings.conservative(SAFETY);
    private static final DirectSettings DANGEROUS = OFF.withDangerousCrystalBreak(true);
    private static final DirectSettings ANTI_PISTON = OFF.withAntiPiston(true);
    private static final DirectSettings BOTH = OFF.withDangerousCrystalBreak(true).withAntiPiston(true);

    // ------------------------------------------------------------------ dangerous crystal break

    @Test
    void dangerousCrystalIsBrokenEvenWhenItWouldHarmSelf() {
        DirectPlannerTest.Phase3FakeWorld world = world(7.0);
        world.damage.selfUpper = new MitigatedDamage(1.0);
        world.damage.selfUpperWorst = new MitigatedDamage(6.0);
        world.targets.add(target(50, FAR_ENEMY));
        world.crystals.add(crystal(30, CRYSTAL_POSITION, CrystalState.OBSERVED, false, false));
        world.damage.estimate = new TargetEstimate(0.0, 0.0, 0.0);

        DirectPlan plan = plan(DANGEROUS, world).orElseThrow();

        assertEquals(Action.ATTACK_CRYSTAL, plan.action());
        assertEquals(entity(30), plan.entity());
        assertEquals(null, plan.target());
        assertTrue(plan.context().defensive());
        assertEquals(0.0, plan.context().estimate().expected(), 1.0e-9);
        assertEquals(0, plan.context().hotbarSlot());
    }

    @Test
    void crystalThatWouldNotBreachTheMarginIsNotADefensiveOpportunity() {
        DirectPlannerTest.Phase3FakeWorld world = world(20.0);
        world.damage.selfUpper = new MitigatedDamage(1.0);
        world.damage.selfUpperWorst = new MitigatedDamage(6.0);
        world.targets.add(target(50, FAR_ENEMY));
        world.crystals.add(crystal(30, CRYSTAL_POSITION, CrystalState.OBSERVED, false, false));

        assertTrue(plan(DANGEROUS, world).isEmpty());
    }

    @Test
    void dangerousCrystalIsSkippedWhenTheLedgerProtectsItForOurOwnTrap() {
        DirectPlannerTest.Phase3FakeWorld world = world(7.0);
        world.damage.selfUpper = new MitigatedDamage(1.0);
        world.damage.selfUpperWorst = new MitigatedDamage(6.0);
        world.targets.add(target(50, FAR_ENEMY));
        world.crystals.add(crystal(30, CRYSTAL_POSITION, CrystalState.OBSERVED, false, false));
        world.blocks.blocked.add(CRYSTAL_CELL);

        assertTrue(plan(DANGEROUS, world).isEmpty());
    }

    @Test
    void ownCrystalIsNeverBrokenDefensively() {
        DirectPlannerTest.Phase3FakeWorld world = world(7.0);
        world.damage.selfUpper = new MitigatedDamage(1.0);
        world.damage.selfUpperWorst = new MitigatedDamage(6.0);
        world.targets.add(target(50, FAR_ENEMY));
        world.crystals.add(crystal(30, CRYSTAL_POSITION, CrystalState.OBSERVED, true, false));

        assertTrue(plan(DANGEROUS, world).isEmpty());
    }

    @Test
    void defensiveBreakStillObeysTheFriendCeiling() {
        DirectPlannerTest.Phase3FakeWorld world = world(7.0);
        world.damage.selfUpper = new MitigatedDamage(1.0);
        world.damage.selfUpperWorst = new MitigatedDamage(6.0);
        world.targets.add(target(50, FAR_ENEMY));
        world.crystals.add(crystal(30, CRYSTAL_POSITION, CrystalState.OBSERVED, false, false));
        EntityKey friend = entity(77);
        world.damage.friendUppers = Map.of(friend, new MitigatedDamage(SAFETY.maxFriend() + 0.5));

        assertTrue(plan(DANGEROUS, world).isEmpty());
    }

    @Test
    void defensiveBreakStillObeysTheSelfCeiling() {
        DirectPlannerTest.Phase3FakeWorld world = world(7.0);
        world.damage.selfUpper = new MitigatedDamage(SAFETY.maxSelf() + 0.5);
        world.damage.selfUpperWorst = new MitigatedDamage(6.0);
        world.targets.add(target(50, FAR_ENEMY));
        world.crystals.add(crystal(30, CRYSTAL_POSITION, CrystalState.OBSERVED, false, false));

        assertTrue(plan(DANGEROUS, world).isEmpty());
    }

    @Test
    void defensiveBreakNeedsNoMinimumTargetDamage() {
        DirectPlannerTest.Phase3FakeWorld world = world(7.0);
        world.damage.selfUpper = new MitigatedDamage(1.0);
        world.damage.selfUpperWorst = new MitigatedDamage(6.0);
        world.targets.add(target(50, FAR_ENEMY));
        world.crystals.add(crystal(30, CRYSTAL_POSITION, CrystalState.OBSERVED, false, false));
        world.damage.estimate = new TargetEstimate(0.0, 0.0, 0.0);

        DirectPlan plan = plan(DANGEROUS, world).orElseThrow();
        assertEquals(0.0, plan.context().estimate().expected(), 1.0e-9);
    }

    @Test
    void dangerousCrystalGateOffProducesNoPlan() {
        DirectPlannerTest.Phase3FakeWorld world = world(7.0);
        world.damage.selfUpper = new MitigatedDamage(1.0);
        world.damage.selfUpperWorst = new MitigatedDamage(6.0);
        world.targets.add(target(50, FAR_ENEMY));
        world.crystals.add(crystal(30, CRYSTAL_POSITION, CrystalState.OBSERVED, false, false));

        assertTrue(plan(OFF, world).isEmpty());
        assertTrue(plan(ANTI_PISTON, world).isEmpty());
    }

    @Test
    void outstandingRiskBoundIsCountedAgainstTheMargin() {
        DirectPlannerTest.Phase3FakeWorld world = world(20.0);
        world.damage.selfUpper = new MitigatedDamage(1.0);
        world.damage.selfUpperWorst = new MitigatedDamage(6.0);
        world.targets.add(target(50, FAR_ENEMY));
        world.crystals.add(crystal(30, CRYSTAL_POSITION, CrystalState.OBSERVED, false, false));

        // 20 - 12 - 6 = 2 <= margin: danger only because the engine already holds a 12-point bound.
        Optional<DirectPlan> plan = new DefensePlanner(DANGEROUS)
            .plan(world, state(), 12.0);
        assertTrue(plan.isPresent());
        assertEquals(Action.ATTACK_CRYSTAL, plan.get().action());
    }

    @Test
    void dangerousCrystalIsDetectedWithoutAnyVisibleEnemy() {
        // The threat is "someone else detonates it"; a foreign crystal in range is enough evidence.
        DirectPlannerTest.Phase3FakeWorld world = world(7.0);
        world.damage.selfUpper = new MitigatedDamage(1.0);
        world.damage.selfUpperWorst = new MitigatedDamage(6.0);
        world.crystals.add(crystal(30, CRYSTAL_POSITION, CrystalState.OBSERVED, false, false));

        DirectPlan plan = plan(DANGEROUS, world).orElseThrow();
        assertEquals(Action.ATTACK_CRYSTAL, plan.action());
        assertEquals(entity(30), plan.entity());
    }

    @Test
    void unknownSelfHealthIsNotEnoughEvidenceForADangerousBreak() {
        DirectPlannerTest.Phase3FakeWorld world = world(7.0);
        world.safetyState = new SafetyState(CONN, GEN, SELF,
            new EffectiveHealth(7.0, 0.0, Capability.UNKNOWN, 1L), Map.of(), SAFETY);
        world.damage.selfUpper = new MitigatedDamage(1.0);
        world.damage.selfUpperWorst = new MitigatedDamage(6.0);
        world.crystals.add(crystal(30, CRYSTAL_POSITION, CrystalState.OBSERVED, false, false));

        assertTrue(plan(DANGEROUS, world).isEmpty());
    }

    // ------------------------------------------------------------------ anti-piston blocker

    @Test
    void blockerIsPlacedInThePushPathForAllFourDirections() {
        int[][] facings = {{1, 0, 0}, {-1, 0, 0}, {0, 0, 1}, {0, 0, -1}};
        for (int[] facing : facings) {
            DirectPlannerTest.Phase3FakeWorld world = world(20.0);
            PistonFacing direction = new PistonFacing(facing[0], 0, facing[2]);
            Cell piston = new Cell(FEET_CELL.x() - 2 * facing[0], FEET_CELL.y(),
                FEET_CELL.z() - 2 * facing[2]);
            Cell front = new Cell(piston.x() + facing[0], piston.y(), piston.z() + facing[2]);
            world.blocks.pistons.add(piston);
            world.blocks.pistonFacings.put(piston, direction);
            world.blocks.ids.put(front.below(), "minecraft:stone");

            DirectPlan plan = plan(ANTI_PISTON, world).orElseThrow(
                () -> new AssertionError("no blocker for " + direction));
            assertEquals(Action.PLACE_BLOCKER, plan.action());
            assertEquals(front, plan.intent().cell());
            assertEquals(DefensePlanner.OBSIDIAN, plan.context().requiredItemId());
            assertTrue(plan.context().defensive());
            assertNotEquals(Action.MINE_POWER, plan.action());
        }
    }

    @Test
    void blockerIsPlacedForAPistonAimedAtTheHeadCell() {
        DirectPlannerTest.Phase3FakeWorld world = world(20.0);
        Cell piston = new Cell(-2, HEAD_CELL.y(), 0);
        Cell front = new Cell(-1, HEAD_CELL.y(), 0);
        world.blocks.pistons.add(piston);
        world.blocks.pistonFacings.put(piston, new PistonFacing(1, 0, 0));
        world.blocks.ids.put(front.below(), "minecraft:stone");

        DirectPlan plan = plan(ANTI_PISTON, world).orElseThrow();
        assertEquals(Action.PLACE_BLOCKER, plan.action());
        assertEquals(front, plan.intent().cell());
    }

    @Test
    void blockerIsPlacedForAPistonTwoAboveTheHead() {
        DirectPlannerTest.Phase3FakeWorld world = world(20.0);
        Cell piston = new Cell(HEAD_CELL.x(), HEAD_CELL.y() + 2, HEAD_CELL.z());
        Cell front = new Cell(HEAD_CELL.x(), HEAD_CELL.y() + 1, HEAD_CELL.z());
        world.blocks.pistons.add(piston);
        world.blocks.pistonFacings.put(piston, new PistonFacing(0, -1, 0));
        world.blocks.ids.put(front.below(), "minecraft:stone");

        DirectPlan plan = plan(ANTI_PISTON, world).orElseThrow();
        assertEquals(Action.PLACE_BLOCKER, plan.action());
        assertEquals(front, plan.intent().cell());
    }

    @Test
    void crystalInThePushPathIsBrokenWhenSafe() {
        DirectPlannerTest.Phase3FakeWorld world = world(20.0);
        Cell piston = new Cell(-2, FEET_CELL.y(), 0);
        Cell front = new Cell(-1, FEET_CELL.y(), 0);
        world.blocks.pistons.add(piston);
        world.blocks.pistonFacings.put(piston, new PistonFacing(1, 0, 0));
        world.blocks.ids.put(front.below(), "minecraft:stone");
        Vec frontCentre = new Vec(front.x() + 0.5, front.y(), front.z() + 0.5);
        world.crystals.add(crystal(31, frontCentre, CrystalState.OBSERVED, false, false));
        world.damage.selfUpper = new MitigatedDamage(1.0);

        DirectPlan plan = plan(ANTI_PISTON, world).orElseThrow();
        assertEquals(Action.ATTACK_CRYSTAL, plan.action());
        assertEquals(entity(31), plan.entity());
    }

    @Test
    void unsafeCrystalInThePushPathDropsTheOpportunityRatherThanBlockingIntoIt() {
        DirectPlannerTest.Phase3FakeWorld world = world(20.0);
        Cell piston = new Cell(-2, FEET_CELL.y(), 0);
        Cell front = new Cell(-1, FEET_CELL.y(), 0);
        world.blocks.pistons.add(piston);
        world.blocks.pistonFacings.put(piston, new PistonFacing(1, 0, 0));
        world.blocks.ids.put(front.below(), "minecraft:stone");
        Vec frontCentre = new Vec(front.x() + 0.5, front.y(), front.z() + 0.5);
        world.crystals.add(crystal(31, frontCentre, CrystalState.OBSERVED, false, false));
        world.damage.selfUpper = new MitigatedDamage(SAFETY.maxSelf() + 1.0);

        assertTrue(plan(ANTI_PISTON, world).isEmpty());
    }

    @Test
    void ownCrystalInThePushPathIsSkippedRatherThanBlockedInto() {
        DirectPlannerTest.Phase3FakeWorld world = world(20.0);
        Cell piston = new Cell(-2, FEET_CELL.y(), 0);
        Cell front = new Cell(-1, FEET_CELL.y(), 0);
        world.blocks.pistons.add(piston);
        world.blocks.pistonFacings.put(piston, new PistonFacing(1, 0, 0));
        world.blocks.ids.put(front.below(), "minecraft:stone");
        Vec frontCentre = new Vec(front.x() + 0.5, front.y(), front.z() + 0.5);
        world.crystals.add(crystal(31, frontCentre, CrystalState.OBSERVED, true, false));

        assertTrue(plan(ANTI_PISTON, world).isEmpty());
    }

    @Test
    void blockerIsNotPlacedIntoAnUnresolvedCrystalCell() {
        DirectPlannerTest.Phase3FakeWorld world = world(20.0);
        Cell piston = new Cell(-2, FEET_CELL.y(), 0);
        Cell front = new Cell(-1, FEET_CELL.y(), 0);
        world.blocks.pistons.add(piston);
        world.blocks.pistonFacings.put(piston, new PistonFacing(1, 0, 0));
        world.blocks.ids.put(front.below(), "minecraft:stone");
        // The ledger still treats the cell as occupied even though no DirectCrystal is visible this pass.
        world.blocks.crystalCells.add(front);

        assertTrue(plan(ANTI_PISTON, world).isEmpty());
    }

    @Test
    void blockerRequiresASolidSupportBelowTheCell() {
        DirectPlannerTest.Phase3FakeWorld world = world(20.0);
        Cell piston = new Cell(-2, FEET_CELL.y(), 0);
        world.blocks.pistons.add(piston);
        world.blocks.pistonFacings.put(piston, new PistonFacing(1, 0, 0));

        assertTrue(plan(ANTI_PISTON, world).isEmpty());
    }

    @Test
    void blockerRequiresAnEntityFreeCell() {
        DirectPlannerTest.Phase3FakeWorld world = world(20.0);
        Cell piston = new Cell(-2, FEET_CELL.y(), 0);
        Cell front = new Cell(-1, FEET_CELL.y(), 0);
        world.blocks.pistons.add(piston);
        world.blocks.pistonFacings.put(piston, new PistonFacing(1, 0, 0));
        world.blocks.ids.put(front.below(), "minecraft:stone");
        world.blocks.entityFree = false;

        assertTrue(plan(ANTI_PISTON, world).isEmpty());
    }

    @Test
    void blockerRequiresFaceVisibility() {
        DirectPlannerTest.Phase3FakeWorld world = world(20.0);
        Cell piston = new Cell(-2, FEET_CELL.y(), 0);
        Cell front = new Cell(-1, FEET_CELL.y(), 0);
        world.blocks.pistons.add(piston);
        world.blocks.pistonFacings.put(piston, new PistonFacing(1, 0, 0));
        world.blocks.ids.put(front.below(), "minecraft:stone");
        world.blocks.invisibleFaces.add(front.below());

        assertTrue(plan(ANTI_PISTON, world).isEmpty());
    }

    @Test
    void blockerRequiresPlacementReach() {
        DirectPlannerTest.Phase3FakeWorld world = world(20.0);
        Cell piston = new Cell(-2, FEET_CELL.y(), 0);
        Cell front = new Cell(-1, FEET_CELL.y(), 0);
        world.blocks.pistons.add(piston);
        world.blocks.pistonFacings.put(piston, new PistonFacing(1, 0, 0));
        world.blocks.ids.put(front.below(), "minecraft:stone");
        world.placeReachable = false;

        assertTrue(plan(ANTI_PISTON, world).isEmpty());
    }

    @Test
    void pistonFacingAwayIsNotAThreat() {
        DirectPlannerTest.Phase3FakeWorld world = world(20.0);
        Cell piston = new Cell(-2, FEET_CELL.y(), 0);
        world.blocks.pistons.add(piston);
        world.blocks.pistonFacings.put(piston, new PistonFacing(-1, 0, 0));

        assertTrue(plan(ANTI_PISTON, world).isEmpty());
    }

    @Test
    void extendedPistonIsNotTreatedAsAnIncomingPush() {
        DirectPlannerTest.Phase3FakeWorld world = world(20.0);
        Cell piston = new Cell(-2, FEET_CELL.y(), 0);
        world.blocks.pistons.add(piston);
        world.blocks.pistonFacings.put(piston, new PistonFacing(1, 0, 0));
        world.blocks.extendedPistons.add(piston);

        assertTrue(plan(ANTI_PISTON, world).isEmpty());
    }

    @Test
    void pistonBeyondTwoBlocksIsIgnored() {
        DirectPlannerTest.Phase3FakeWorld world = world(20.0);
        Cell piston = new Cell(-3, FEET_CELL.y(), 0);
        world.blocks.pistons.add(piston);
        world.blocks.pistonFacings.put(piston, new PistonFacing(1, 0, 0));
        world.blocks.ids.put(new Cell(-2, FEET_CELL.y(), 0).below(), "minecraft:stone");

        assertTrue(plan(ANTI_PISTON, world).isEmpty());
    }

    @Test
    void antiPistonGateOffProducesNoPlan() {
        DirectPlannerTest.Phase3FakeWorld world = world(20.0);
        Cell piston = new Cell(-2, FEET_CELL.y(), 0);
        Cell front = new Cell(-1, FEET_CELL.y(), 0);
        world.blocks.pistons.add(piston);
        world.blocks.pistonFacings.put(piston, new PistonFacing(1, 0, 0));
        world.blocks.ids.put(front.below(), "minecraft:stone");

        assertTrue(plan(OFF, world).isEmpty());
        assertTrue(plan(DANGEROUS, world).isEmpty());
    }

    @Test
    void blockerPlanNeverAsksForSpeedmineOrAnyMiningAction() {
        DirectPlannerTest.Phase3FakeWorld world = world(20.0);
        Cell piston = new Cell(-2, FEET_CELL.y(), 0);
        Cell front = new Cell(-1, FEET_CELL.y(), 0);
        world.blocks.pistons.add(piston);
        world.blocks.pistonFacings.put(piston, new PistonFacing(1, 0, 0));
        world.blocks.ids.put(front.below(), "minecraft:stone");

        DirectPlan plan = plan(ANTI_PISTON, world).orElseThrow();
        assertNotEquals(Action.MINE_POWER, plan.action());
        assertNotEquals(Action.MINE_PISTON, plan.action());
        assertFalse(plan.intent().entityLocks().stream()
            .anyMatch(key -> world.crystals.stream().noneMatch(crystal -> crystal.entity().equals(key))));
    }

    // ------------------------------------------------------------------ priority and gating

    @Test
    void imminentDangerousCrystalOutranksANonImminentPiston() {
        DirectPlannerTest.Phase3FakeWorld world = world(7.0);
        world.damage.selfUpper = new MitigatedDamage(1.0);
        world.damage.selfUpperWorst = new MitigatedDamage(6.0);
        // Enemy standing within attack range of the crystal: arrival 1 (imminent).
        world.targets.add(target(50, new Vec(2.5, 64.5, 2.5)));
        world.crystals.add(crystal(30, CRYSTAL_POSITION, CrystalState.OBSERVED, false, false));
        Cell piston = new Cell(-2, FEET_CELL.y(), 0);
        Cell front = new Cell(-1, FEET_CELL.y(), 0);
        world.blocks.pistons.add(piston);
        world.blocks.pistonFacings.put(piston, new PistonFacing(1, 0, 0));
        world.blocks.ids.put(front.below(), "minecraft:stone");

        DirectPlan plan = plan(BOTH, world).orElseThrow();
        assertEquals(Action.ATTACK_CRYSTAL, plan.action());
        assertEquals(entity(30), plan.entity());
    }

    @Test
    void nonImminentDangerIsStillPlannedWhenNothingIsImminent() {
        DirectPlannerTest.Phase3FakeWorld world = world(7.0);
        world.damage.selfUpper = new MitigatedDamage(1.0);
        world.damage.selfUpperWorst = new MitigatedDamage(6.0);
        world.targets.add(target(50, FAR_ENEMY));
        world.crystals.add(crystal(30, CRYSTAL_POSITION, CrystalState.OBSERVED, false, false));

        assertTrue(plan(DANGEROUS, world).isPresent());
    }

    @Test
    void repricedDefensivePlanKeepsIdentityAndRejectsAnUnsafeWorld() {
        DirectPlannerTest.Phase3FakeWorld world = world(7.0);
        world.damage.selfUpper = new MitigatedDamage(1.0);
        world.damage.selfUpperWorst = new MitigatedDamage(6.0);
        world.targets.add(target(50, FAR_ENEMY));
        world.crystals.add(crystal(30, CRYSTAL_POSITION, CrystalState.OBSERVED, false, false));
        DefensePlanner planner = new DefensePlanner(DANGEROUS);
        DirectPlan planned = planner.plan(world, state(), 0.0).orElseThrow();
        assertEquals(Optional.of(planned.action()),
            planner.reprice(planned, world, state(), 0.0).map(DirectPlan::action));

        world.damage.selfUpper = new MitigatedDamage(SAFETY.maxSelf() + 1.0);
        assertTrue(planner.reprice(planned, world, state(), 0.0).isEmpty());
    }

    // ------------------------------------------------------------------ helpers

    private static Optional<DirectPlan> plan(DirectSettings settings, DirectPlannerTest.Phase3FakeWorld world) {
        return new DefensePlanner(settings).plan(world, state(), 0.0);
    }

    private static DirectPlanState state() {
        return new DirectPlanState(2L, 7L, 100, null, new HashMap<>(), -1);
    }

    private static DirectPlannerTest.Phase3FakeWorld world(double selfHealth) {
        DirectPlannerTest.Phase3FakeWorld world = new DirectPlannerTest.Phase3FakeWorld();
        world.conn = CONN;
        world.self = SELF;
        world.eye = EYE;
        world.selfPosition = FEET;
        world.safetyState = new SafetyState(CONN, GEN, SELF, health(selfHealth), Map.of(), SAFETY);
        world.hotbar.add(new DirectItem(0, "minecraft:diamond_sword", true));
        world.hotbar.add(new DirectItem(1, "minecraft:ender_pearl", false));
        world.hotbar.add(new DirectItem(2, "minecraft:end_crystal", false));
        world.hotbar.add(new DirectItem(3, "minecraft:obsidian", false));
        return world;
    }

    private static DirectTarget target(int id, Vec position) {
        return new DirectTarget(entity(id), position, new Box(
            new Vec(position.x() - 0.3, position.y(), position.z() - 0.3),
            new Vec(position.x() + 0.3, position.y() + 1.8, position.z() + 0.3)),
            health(20.0), MitigationView.of(NO_ARMOUR), false, false, false, false, false);
    }

    private static DirectCrystal crystal(int id, Vec position, CrystalState state, boolean own,
                                         boolean quarantined) {
        return new DirectCrystal(entity(id), position, new Box(
            new Vec(position.x() - 0.5, position.y(), position.z() - 0.5),
            new Vec(position.x() + 0.5, position.y() + 1.5, position.z() + 0.5)),
            state, own, quarantined, 0);
    }

    private static EffectiveHealth health(double value) {
        return new EffectiveHealth(value, 0.0, Capability.KNOWN, 1L);
    }

    private static EntityKey entity(int id) {
        return new EntityKey(CONN, id, new UUID(0L, id));
    }
}
