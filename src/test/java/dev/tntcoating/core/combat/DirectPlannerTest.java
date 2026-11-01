/*
 * Original Comet implementation by Boyonabike.
 * Independent: written from the pinned 26.2 artifact and Meteor build 35 behaviour; no third-party code.
 * SPDX-License-Identifier: CC0-1.0
 */

package dev.tntcoating.core.combat;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import org.junit.jupiter.api.Test;

/** Phase 3.1-3.3 behaviour: target selection, break opportunities and placement opportunities. */
final class DirectPlannerTest {
    private static final ConnectionKey CONN = new ConnectionKey(11L);
    private static final ActionGeneration GEN = new ActionGeneration(5L);
    private static final Safety SAFETY = CombatDamagePolicy.conservativeSafety();
    private static final EntityKey SELF = new EntityKey(CONN, 1, new UUID(0L, 1L));
    private static final Vec EYE = new Vec(0.5, 65.62, 0.5);
    private static final Vec SELF_POSITION = new Vec(0.5, 64.5, 0.5);
    private static final Vec CRYSTAL_POSITION = new Vec(2.5, 64.5, 0.5);
    private static final Cell BASE = new Cell(0, 63, 0);
    private static final Cell CRYSTAL_CELL = new Cell(0, 64, 0);
    private static final MitigationState NO_ARMOUR =
        new MitigationState(CombatDifficulty.NORMAL, 0.0, 0.0, 0.0, 0, false, false, false);

    private static final DirectSettings DEFAULT = DirectSettings.conservative(SAFETY);

    // ------------------------------------------------------------------ target selection (3.1)

    @Test
    void targetSelectionExcludesFriendsDeadCreativeSpectatorsAndSelf() {
        Phase3FakeWorld world = world();
        DirectTarget normal = target(10, CRYSTAL_POSITION);
        world.targets.add(normal);
        world.targets.add(withFlags(target(11, new Vec(2.5, 64.5, 2.5)), false, false, false, true));
        world.targets.add(withFlags(target(12, new Vec(2.5, 64.5, 2.5)), true, false, false, false));
        world.targets.add(withFlags(target(13, new Vec(2.5, 64.5, 2.5)), false, true, false, false));
        world.targets.add(withFlags(target(14, new Vec(2.5, 64.5, 2.5)), false, false, true, false));
        world.targets.add(withFlags(target(15, new Vec(2.5, 64.5, 2.5)), false, false, false, false,
            SELF));
        world.crystals.add(new DirectCrystal(entity(31), new Vec(10.5, 64.5, 0.5),
            crystalBox(new Vec(10.5, 64.5, 0.5)), CrystalState.OBSERVED, true, false, 0));

        List<EntityKey> ordered = planner(DEFAULT).selectTargets(world, state(0, null, Map.of(), -1));

        assertEquals(List.of(normal.entity()), ordered);
    }

    @Test
    void targetSelectionIsDeterministicByScoreThenDistanceThenEntityId() {
        Phase3FakeWorld world = world();
        DirectTarget nearLow = target(20, new Vec(2.5, 64.5, 0.5));
        DirectTarget farHigh = target(21, new Vec(0.5, 64.5, 4.5));
        DirectTarget sameDistanceA = target(22, new Vec(-1.5, 64.5, 0.5));
        DirectTarget sameDistanceB = target(23, new Vec(0.5, 64.5, -1.5));
        world.targets.addAll(List.of(nearLow, farHigh, sameDistanceA, sameDistanceB));
        world.damage.byTarget.put(nearLow.entity(), new TargetEstimate(4, 4.5, 6));
        world.damage.byTarget.put(farHigh.entity(), new TargetEstimate(4, 8, 10));
        world.crystals.add(new DirectCrystal(entity(32), new Vec(10.5, 64.5, 0.5),
            crystalBox(new Vec(10.5, 64.5, 0.5)), CrystalState.OBSERVED, true, false, 0));

        List<EntityKey> ordered = planner(DEFAULT).selectTargets(world, state(0, null, Map.of(), -1));

        // Highest score first; the remaining three share a score and distance, so the entity id decides.
        assertEquals(3, ordered.size());
        assertEquals(List.of(farHigh.entity(), sameDistanceA.entity(), sameDistanceB.entity()), ordered);
        assertFalse(ordered.contains(nearLow.entity()), "only three targets are considered per pass");
    }

    @Test
    void targetStaysStickyWhileItIsWithinTwentyPercentOfTheBestAlternative() {
        Phase3FakeWorld world = stickyWorld();
        EntityKey preferred = world.targets.get(2).entity();

        Optional<DirectPlan> plan = planner(DEFAULT).plan(world,
            state(0, preferred, Map.of(), -1));

        assertTrue(plan.isPresent());
        assertEquals(preferred, plan.get().target());
    }

    @Test
    void targetIsNotStickyWhenTheAlternativeIsMoreThanTwentyPercentBetter() {
        Phase3FakeWorld world = stickyWorld();
        world.damage.byTarget.put(world.targets.get(2).entity(), new TargetEstimate(4, 4.5, 6));
        EntityKey preferred = world.targets.get(2).entity();

        Optional<DirectPlan> plan = planner(DEFAULT).plan(world,
            state(0, preferred, Map.of(), -1));

        assertTrue(plan.isPresent());
        assertEquals(world.targets.get(0).entity(), plan.get().target());
    }

    // ------------------------------------------------------------------ break (3.2)

    @Test
    void breakNeedsAnObservedCrystal() {
        for (CrystalState state : List.of(CrystalState.PROPOSED, CrystalState.SENT,
            CrystalState.REMOVED, CrystalState.EXPLODED, CrystalState.TRACKING_LOST)) {
            Phase3FakeWorld world = breakWorld(state, true, false, 0, Map.of());
            assertFalse(planner(DEFAULT).plan(world, state(0, null, Map.of(), -1)).isPresent(),
                "state " + state + " must not be attacked");
        }
        Phase3FakeWorld observed = breakWorld(CrystalState.OBSERVED, true, false, 0, Map.of());
        assertTrue(planner(DEFAULT).plan(observed, state(0, null, Map.of(), -1)).isPresent());
    }

    @Test
    void breakRespectsOwnOnlyQuarantineAndLiveHazardProtection() {
        Phase3FakeWorld foreign = breakWorld(CrystalState.OBSERVED, false, false, 0, Map.of());
        assertTrue(planner(DEFAULT).plan(foreign, state(0, null, Map.of(), -1)).isPresent(),
            "foreign crystals are breakable by default");

        Phase3FakeWorld ownOnly = breakWorld(CrystalState.OBSERVED, false, false, 0, Map.of());
        DirectSettings settings = ownOnlySettings();
        assertFalse(planner(settings).plan(ownOnly, state(0, null, Map.of(), -1)).isPresent());

        Phase3FakeWorld ownOwnOnly = breakWorld(CrystalState.OBSERVED, true, false, 0, Map.of());
        assertTrue(planner(ownOnlySettings()).plan(ownOwnOnly, state(0, null, Map.of(), -1))
            .isPresent());

        Phase3FakeWorld quarantined = breakWorld(CrystalState.OBSERVED, true, true, 0, Map.of());
        assertFalse(planner(DEFAULT).plan(quarantined, state(0, null, Map.of(), -1)).isPresent());

        Phase3FakeWorld protectedCell = breakWorld(CrystalState.OBSERVED, false, false, 0, Map.of());
        protectedCell.blocks.blocked.add(DirectPlanner.cellOf(CRYSTAL_POSITION));
        assertFalse(planner(DEFAULT).plan(protectedCell, state(0, null, Map.of(), -1)).isPresent(),
            "a foreign crystal under an unresolved hazard/quarantine is protected");
    }

    @Test
    void breakRespectsAgeAndAttemptCaps() {
        Phase3FakeWorld tooYoung = breakWorld(CrystalState.OBSERVED, true, false, 3, Map.of());
        DirectSettings ageThree = new DirectSettings(6.0, 4.5, 3.0, 3.0, SAFETY, false, 4, 2, true,
            8.0, 0.10, false, false, 0, true, true, true, true, true, true, 6.0, true, 10.0,
            1_000_000_000L);
        assertFalse(planner(ageThree).plan(tooYoung, state(0, null, Map.of(), -1)).isPresent());
        DirectSettings ageTwo = new DirectSettings(6.0, 4.5, 3.0, 3.0, SAFETY, false, 2, 2, true,
            8.0, 0.10, false, false, 0, true, true, true, true, true, true, 6.0, true, 10.0,
            1_000_000_000L);
        assertTrue(planner(ageTwo).plan(tooYoung, state(0, null, Map.of(), -1)).isPresent());

        EntityKey crystal = entity(30);
        Phase3FakeWorld used = breakWorld(CrystalState.OBSERVED, true, false, 0,
            Map.of(crystal, 2));
        assertFalse(planner(DEFAULT).plan(used, state(0, null, Map.of(crystal, 2), -1)).isPresent());
        assertTrue(planner(DEFAULT).plan(used, state(0, null, Map.of(crystal, 1), -1)).isPresent());
    }

    @Test
    void breakRespectsAttackRangeAndWallsReach() {
        Phase3FakeWorld outOfReach = breakWorld(CrystalState.OBSERVED, true, false, 0, Map.of());
        outOfReach.reachable = false;
        assertFalse(planner(DEFAULT).plan(outOfReach, state(0, null, Map.of(), -1)).isPresent());
    }

    @Test
    void breakWeaknessSwitchesToAUsableToolOrSkips() {
        Phase3FakeWorld noTool = breakWorld(CrystalState.OBSERVED, true, false, 0, Map.of());
        noTool.weakness = true;
        noTool.hotbar.clear();
        noTool.hotbar.add(new DirectItem(0, "minecraft:iron_sword", false));
        noTool.hotbar.add(new DirectItem(1, "minecraft:ender_pearl", false));
        assertFalse(planner(DEFAULT).plan(noTool, state(0, null, Map.of(), -1)).isPresent(),
            "Weakness with no usable breaking tool must skip, not attack with a useless item");

        Phase3FakeWorld selectedUsable = breakWorld(CrystalState.OBSERVED, true, false, 0, Map.of());
        selectedUsable.weakness = true;
        selectedUsable.hotbar.clear();
        selectedUsable.hotbar.add(new DirectItem(0, "minecraft:diamond_sword", true));
        DirectPlan selectedPlan = planner(DEFAULT)
            .plan(selectedUsable, state(0, null, Map.of(), -1)).orElseThrow();
        assertEquals(0, selectedPlan.context().hotbarSlot());

        Phase3FakeWorld switchTool = breakWorld(CrystalState.OBSERVED, true, false, 0, Map.of());
        switchTool.weakness = true;
        switchTool.selectedSlot = 0;
        switchTool.hotbar.clear();
        switchTool.hotbar.add(new DirectItem(0, "minecraft:iron_sword", false));
        switchTool.hotbar.add(new DirectItem(3, "minecraft:diamond_sword", true));
        DirectPlan switched = planner(DEFAULT)
            .plan(switchTool, state(0, null, Map.of(), -1)).orElseThrow();
        assertEquals(3, switched.context().hotbarSlot());
        assertEquals("minecraft:diamond_sword", switched.context().requiredItemId());

        DirectSettings delayTwo = new DirectSettings(6.0, 4.5, 3.0, 3.0, SAFETY, false, 0, 2, true,
            8.0, 0.10, false, false, 2, true, true, true, true, true, true, 6.0, true, 10.0,
            1_000_000_000L);
        assertFalse(planner(delayTwo).plan(switchTool, state(10, null, Map.of(), 9)).isPresent(),
            "a switch that is still cooling down must wait");
        assertTrue(planner(delayTwo).plan(switchTool, state(10, null, Map.of(), 8)).isPresent());
    }

    @Test
    void breakUsesTheDamagePolicyIncludingSelfAndFriendUpperBounds() {
        Phase3FakeWorld safe = breakWorld(CrystalState.OBSERVED, true, false, 0, Map.of());
        assertTrue(planner(DEFAULT).plan(safe, state(0, null, Map.of(), -1)).isPresent());

        Phase3FakeWorld selfTooHigh = breakWorld(CrystalState.OBSERVED, true, false, 0, Map.of());
        selfTooHigh.damage.selfUpper = new MitigatedDamage(7.0);
        assertFalse(planner(DEFAULT).plan(selfTooHigh, state(0, null, Map.of(), -1)).isPresent());

        Phase3FakeWorld friendTooHigh = breakWorld(CrystalState.OBSERVED, true, false, 0, Map.of());
        friendTooHigh.damage.friendUppers = Map.of(entity(50), new MitigatedDamage(2.5));
        assertFalse(planner(DEFAULT).plan(friendTooHigh, state(0, null, Map.of(), -1)).isPresent());

        Phase3FakeWorld friendAtCeiling = breakWorld(CrystalState.OBSERVED, true, false, 0, Map.of());
        friendAtCeiling.damage.friendUppers = Map.of(entity(51), new MitigatedDamage(2.0));
        assertTrue(planner(DEFAULT).plan(friendAtCeiling, state(0, null, Map.of(), -1)).isPresent());

        Phase3FakeWorld thinMargin = breakWorld(CrystalState.OBSERVED, true, false, 0, Map.of());
        thinMargin.safetyState = new SafetyState(CONN, GEN, SELF, health(8.0), Map.of(), SAFETY);
        thinMargin.damage.selfUpper = new MitigatedDamage(6.0);
        assertFalse(planner(DEFAULT).plan(thinMargin, state(0, null, Map.of(), -1)).isPresent(),
            "remaining health equal to the margin is rejected");
    }

    // ------------------------------------------------------------------ place (3.3)

    @Test
    void placementAppliesTheVanillaRuleOnObsidianAndBedrock() {
        Phase3FakeWorld world = placeWorld();
        DirectPlan plan = planner(DEFAULT).plan(world, state(0, null, Map.of(), -1)).orElseThrow();
        assertEquals(Action.PLACE_CRYSTAL, plan.action());
        assertEquals(CRYSTAL_CELL, plan.intent().cell());
        assertEquals("minecraft:end_crystal", plan.context().requiredItemId());

        Phase3FakeWorld bedrock = placeWorld();
        bedrock.blocks.ids.put(new Cell(0, 62, 0), "minecraft:bedrock");
        assertTrue(planner(DEFAULT).plan(bedrock, state(0, null, Map.of(), -1)).isPresent());
    }

    @Test
    void placementRejectsOccupiedOrObstructedCells() {
        Phase3FakeWorld occupiedEntity = placeWorld();
        occupiedEntity.blocks.entityFree = false;
        assertFalse(planner(DEFAULT).plan(occupiedEntity, state(0, null, Map.of(), -1)).isPresent(),
            "a player/item hitbox in the 1x2x1 volume blocks the placement");

        Phase3FakeWorld occupiedCrystal = placeWorld();
        occupiedCrystal.blocks.crystalCells.add(CRYSTAL_CELL);
        assertFalse(planner(DEFAULT).plan(occupiedCrystal, state(0, null, Map.of(), -1)).isPresent(),
            "a crystal still counts present until removal is observed");

        Phase3FakeWorld notAir = placeWorld();
        notAir.blocks.ids.put(CRYSTAL_CELL, "minecraft:short_grass");
        assertFalse(planner(DEFAULT).plan(notAir, state(0, null, Map.of(), -1)).isPresent());

        Phase3FakeWorld blockedCell = placeWorld();
        blockedCell.blocks.blocked.add(CRYSTAL_CELL);
        assertFalse(planner(DEFAULT).plan(blockedCell, state(0, null, Map.of(), -1)).isPresent());

        Phase3FakeWorld noFace = placeWorld();
        noFace.blocks.invisibleFaces.add(BASE);
        assertFalse(planner(DEFAULT).plan(noFace, state(0, null, Map.of(), -1)).isPresent());

        Phase3FakeWorld outOfRange = placeWorld();
        outOfRange.placeReachable = false;
        assertFalse(planner(DEFAULT).plan(outOfRange, state(0, null, Map.of(), -1)).isPresent());
    }

    @Test
    void twoAirProfileRequiresTheCellAboveToBeAirToo() {
        Phase3FakeWorld strict = placeWorld();
        strict.blocks.ids.put(CRYSTAL_CELL.above(), "minecraft:stone");
        assertTrue(planner(DEFAULT).plan(strict, state(0, null, Map.of(), -1)).isPresent(),
            "default profile only requires the crystal cell itself to be air");

        DirectSettings twoAir = new DirectSettings(6.0, 4.5, 3.0, 3.0, SAFETY, false, 0, 2, true,
            8.0, 0.10, true, false, 0, true, true, true, true, true, true, 6.0, true, 10.0,
            1_000_000_000L);
        assertFalse(planner(twoAir).plan(strict, state(0, null, Map.of(), -1)).isPresent());
    }

    @Test
    void placementUsesTheSameDamagePolicyForSelfAndFriends() {
        Phase3FakeWorld world = placeWorld();
        world.damage.selfUpper = new MitigatedDamage(7.0);
        assertFalse(planner(DEFAULT).plan(world, state(0, null, Map.of(), -1)).isPresent());

        Phase3FakeWorld friend = placeWorld();
        friend.damage.friendUppers = Map.of(entity(60), new MitigatedDamage(2.5));
        assertFalse(planner(DEFAULT).plan(friend, state(0, null, Map.of(), -1)).isPresent());
    }

    @Test
    void facePlaceOnlyRelaxesTheMinimumTargetDamageUnderHealthOrArmourPressure() {
        Phase3FakeWorld lowHealth = placeWorldWithEstimate(new TargetEstimate(1.5, 2.0, 3.0), 8.0);
        DirectPlan plan = planner(DEFAULT).plan(lowHealth, state(0, null, Map.of(), -1)).orElseThrow();
        assertTrue(plan.context().faceplace());
        assertFalse(plan.context().targetOverride(), "faceplace is never a lethal override");

        Phase3FakeWorld healthy = placeWorldWithEstimate(new TargetEstimate(1.5, 2.0, 3.0), 9.0);
        assertFalse(planner(DEFAULT).plan(healthy, state(0, null, Map.of(), -1)).isPresent());

        Phase3FakeWorld armourPressure = placeWorldWithEstimate(new TargetEstimate(1.5, 2.0, 3.0), 20.0);
        armourPressure.targets.get(0);
        armourPressure.targets.set(0, withArmourPressure(armourPressure.targets.get(0)));
        assertTrue(planner(DEFAULT).plan(armourPressure, state(0, null, Map.of(), -1)).isPresent());

        Phase3FakeWorld stillTooLow = placeWorldWithEstimate(new TargetEstimate(1.0, 1.4, 2.0), 8.0);
        assertFalse(planner(DEFAULT).plan(stillTooLow, state(0, null, Map.of(), -1)).isPresent(),
            "faceplace still demands the relaxed 1.5 expected damage");

        Phase3FakeWorld unsafeSelf = placeWorldWithEstimate(new TargetEstimate(1.5, 2.0, 3.0), 8.0);
        unsafeSelf.damage.selfUpper = new MitigatedDamage(7.0);
        assertFalse(planner(DEFAULT).plan(unsafeSelf, state(0, null, Map.of(), -1)).isPresent(),
            "faceplace never relaxes self or friend limits");
    }

    @Test
    void supportPlacementIsUsedOnlyWithoutADirectBaseAndAtMostOnce() {
        DirectSettings supportSettings = new DirectSettings(6.0, 4.5, 3.0, 3.0, SAFETY, false, 0, 2,
            true, 8.0, 0.10, false, true, 0, true, true, true, true, true, true, 6.0, true, 10.0,
            1_000_000_000L);

        Phase3FakeWorld support = supportWorld();
        DirectPlan plan = planner(supportSettings).plan(support, state(0, null, Map.of(), -1))
            .orElseThrow();
        assertEquals(Action.PLACE_SUPPORT, plan.action());
        assertEquals(new Cell(0, 64, 0), plan.intent().cell());
        assertEquals("minecraft:obsidian", plan.context().requiredItemId());
        assertTrue(plan.intent().blockLocks().contains(new Cell(0, 64, 0)));
        assertEquals(2, plan.actionCost(), "the support action plus the later crystal are two actions");
        assertTrue(support.damage.targetOverlays.contains(Set.of(new Cell(0, 64, 0))),
            "the support overlay must reach the damage adapter");
        assertTrue(support.damage.selfOverlays.contains(Set.of(new Cell(0, 64, 0))),
            "the support overlay must reach the self/friend upper-bound adapter");

        Phase3FakeWorld disabled = supportWorld();
        assertFalse(planner(DEFAULT).plan(disabled, state(0, null, Map.of(), -1)).isPresent(),
            "support is disabled by default");

        Phase3FakeWorld baseExists = supportWorld();
        baseExists.blocks.ids.put(new Cell(4, 63, 0), "minecraft:obsidian");
        DirectPlan direct = planner(supportSettings).plan(baseExists, state(0, null, Map.of(), -1))
            .orElseThrow();
        assertEquals(Action.PLACE_CRYSTAL, direct.action(),
            "a direct obsidian base always wins over a support block");
        assertEquals(1, direct.actionCost(), "a direct crystal placement is one action");
        assertTrue(baseExists.damage.targetOverlays.contains(Set.of()),
            "a direct placement must not fabricate a support overlay");
    }

    // ------------------------------------------------------------------ surround gap (4.5)

    @Test
    void surroundGapIsOffByDefaultAndTheOffPathKeepsTheNormalChoice() {
        assertFalse(DEFAULT.surroundGap(), "the tactic must be off in the Conservative preset");

        Phase3FakeWorld world = gapWorld();
        DirectPlan plan = planner(DEFAULT).plan(world, state(0, null, Map.of(), -1)).orElseThrow();

        assertEquals(Action.PLACE_CRYSTAL, plan.action());
        assertEquals(new Cell(0, 64, 0), plan.intent().cell(),
            "the higher-estimate non-gap base wins while the tactic is off");
        assertEquals(9.0, plan.context().estimate().expected(), 1.0e-9);
    }

    @Test
    void surroundGapPrefersTheOpenColumnEvenWhenTheNormalBestScoresHigher() {
        Phase3FakeWorld world = gapWorld();

        DirectPlan plan = planner(DEFAULT.withSurroundGap(true))
            .plan(world, state(0, null, Map.of(), -1)).orElseThrow();

        assertEquals(Action.PLACE_CRYSTAL, plan.action());
        assertEquals(GAP_CRYSTAL, plan.intent().cell(),
            "the gap candidate is chosen even though the normal best base prices higher");
        assertEquals(5.0, plan.context().estimate().expected(), 1.0e-9);
        assertEquals(1, plan.actionCost(), "the gap preference is one direct placement, never a support chain");
        assertTrue(world.damage.targetOverlays.contains(Set.of()),
            "the gap path uses the unchanged direct overlay set");
    }

    @Test
    void surroundGapFallsBackWhenTheGapCellIsOccupied() {
        Phase3FakeWorld world = gapWorld();
        world.blocks.crystalCells.add(GAP_CRYSTAL);

        DirectPlan plan = planner(DEFAULT.withSurroundGap(true))
            .plan(world, state(0, null, Map.of(), -1)).orElseThrow();

        assertEquals(new Cell(0, 64, 0), plan.intent().cell(),
            "a crystal still present in the gap is never treated as free; the normal fallback wins");
    }

    @Test
    void surroundGapDoesNotFabricateAPlacementWithoutABaseInTheGap() {
        Phase3FakeWorld world = gapWorld();
        world.blocks.ids.remove(GAP_BASE);

        DirectPlan plan = planner(DEFAULT.withSurroundGap(true))
            .plan(world, state(0, null, Map.of(), -1)).orElseThrow();

        assertEquals(new Cell(0, 64, 0), plan.intent().cell(),
            "detection alone never places in the gap; an existing base below the gap cell is required");
    }

    @Test
    void supportIsNeverPlacedInTheDetectedGapColumn() {
        Phase3FakeWorld world = gapOnlyWorld();
        // The gap column's feet cell becomes a replaceable block with a valid support underneath. Without
        // the gap-column exclusion the support fallback would fill the gap the tactic is trying to use.
        world.blocks.ids.put(GAP_BASE, "minecraft:stone");
        world.blocks.ids.put(GAP_CRYSTAL, "minecraft:short_grass");

        DirectSettings support = new DirectSettings(6.0, 4.5, 3.0, 3.0, SAFETY, false, 0, 2,
            true, 8.0, 0.10, false, true, 0, true, true, true, true, true, true, 6.0, true, 10.0,
            1_000_000_000L);
        boolean fillsGap = planner(support.withSurroundGap(true))
            .plan(world, state(0, null, Map.of(), -1))
            .map(plan -> plan.action() == Action.PLACE_SUPPORT
                && plan.intent().cell().equals(GAP_CRYSTAL))
            .orElse(false);
        assertFalse(fillsGap, "the support scan must never fill the gap column with obsidian");
    }

    @Test
    void surroundGapOpenGroundAndFullSurroundKeepTheNormalChoice() {
        Phase3FakeWorld open = placeWorld();
        DirectPlan openOff = planner(DEFAULT).plan(open, state(0, null, Map.of(), -1)).orElseThrow();
        DirectPlan openOn = planner(DEFAULT.withSurroundGap(true))
            .plan(open, state(0, null, Map.of(), -1)).orElseThrow();
        assertEquals(openOff.intent().cell(), openOn.intent().cell());
        assertEquals(openOff.context().estimate(), openOn.context().estimate());

        Phase3FakeWorld full = gapWorld();
        full.blocks.ids.put(GAP_CRYSTAL, "minecraft:stone");
        full.blocks.ids.put(GAP_HEAD, "minecraft:stone");
        DirectPlan fullOff = planner(DEFAULT).plan(full, state(0, null, Map.of(), -1)).orElseThrow();
        DirectPlan fullOn = planner(DEFAULT.withSurroundGap(true))
            .plan(full, state(0, null, Map.of(), -1)).orElseThrow();
        assertEquals(fullOff.intent().cell(), fullOn.intent().cell());
        assertEquals(new Cell(0, 64, 0), fullOn.intent().cell());
    }

    @Test
    void surroundGapStillEnforcesSelfAndFriendUpperBounds() {
        Phase3FakeWorld selfUnsafe = gapOnlyWorld();
        selfUnsafe.damage.selfUpper = new MitigatedDamage(7.0);
        assertFalse(planner(DEFAULT.withSurroundGap(true))
            .plan(selfUnsafe, state(0, null, Map.of(), -1)).isPresent(),
            "the gap path never relaxes the self upper bound");
        assertFalse(planner(DEFAULT).plan(selfUnsafe, state(0, null, Map.of(), -1)).isPresent(),
            "the off path is unchanged");

        Phase3FakeWorld friendUnsafe = gapOnlyWorld();
        friendUnsafe.damage.friendUppers = Map.of(entity(61), new MitigatedDamage(2.5));
        assertFalse(planner(DEFAULT.withSurroundGap(true))
            .plan(friendUnsafe, state(0, null, Map.of(), -1)).isPresent(),
            "the gap path never relaxes the friend upper bound");
        assertFalse(planner(DEFAULT).plan(friendUnsafe, state(0, null, Map.of(), -1)).isPresent());
    }

    @Test
    void surroundGapStillEnforcesReachVisibilityEntityAndMinimumDamage() {
        Phase3FakeWorld outOfReach = gapOnlyWorld();
        outOfReach.placeReachable = false;
        assertFalse(planner(DEFAULT.withSurroundGap(true))
            .plan(outOfReach, state(0, null, Map.of(), -1)).isPresent());

        Phase3FakeWorld invisible = gapOnlyWorld();
        invisible.blocks.invisibleFaces.add(GAP_BASE);
        assertFalse(planner(DEFAULT.withSurroundGap(true))
            .plan(invisible, state(0, null, Map.of(), -1)).isPresent());

        Phase3FakeWorld obstructed = gapOnlyWorld();
        obstructed.blocks.entityFree = false;
        assertFalse(planner(DEFAULT.withSurroundGap(true))
            .plan(obstructed, state(0, null, Map.of(), -1)).isPresent());

        Phase3FakeWorld weak = gapOnlyWorld();
        weak.damage.byCentre.put(GAP_CENTRE, new TargetEstimate(4.0, 3.9, 6.0));
        assertFalse(planner(DEFAULT.withSurroundGap(true))
            .plan(weak, state(0, null, Map.of(), -1)).isPresent(),
            "the usual minimum target damage still applies to the gap candidate");
    }

    @Test
    void sameSnapshotPrefersTheHigherExpectedActionAndBreaksWinTies() {
        Phase3FakeWorld world = bothWorld();
        world.damage.byCentre.put(CRYSTAL_POSITION, new TargetEstimate(4, 9, 10));
        world.damage.byCentre.put(new Vec(CRYSTAL_CELL.x() + 0.5, CRYSTAL_CELL.y(),
            CRYSTAL_CELL.z() + 0.5), new TargetEstimate(4, 6, 8));
        DirectPlan breakPlan = planner(DEFAULT).plan(world, state(0, null, Map.of(), -1)).orElseThrow();
        assertEquals(Action.ATTACK_CRYSTAL, breakPlan.action());

        Phase3FakeWorld placeWins = bothWorld();
        placeWins.damage.byCentre.put(CRYSTAL_POSITION, new TargetEstimate(4, 5, 6));
        placeWins.damage.byCentre.put(new Vec(CRYSTAL_CELL.x() + 0.5, CRYSTAL_CELL.y(),
            CRYSTAL_CELL.z() + 0.5), new TargetEstimate(4, 8, 10));
        DirectPlan placed = planner(DEFAULT).plan(placeWins, state(0, null, Map.of(), -1))
            .orElseThrow();
        assertEquals(Action.PLACE_CRYSTAL, placed.action());

        Phase3FakeWorld tie = bothWorld();
        tie.damage.byCentre.put(CRYSTAL_POSITION, new TargetEstimate(4, 8, 10));
        tie.damage.byCentre.put(new Vec(CRYSTAL_CELL.x() + 0.5, CRYSTAL_CELL.y(),
            CRYSTAL_CELL.z() + 0.5), new TargetEstimate(4, 8, 10));
        assertEquals(Action.ATTACK_CRYSTAL,
            planner(DEFAULT).plan(tie, state(0, null, Map.of(), -1)).orElseThrow().action());
    }

    // ------------------------------------------------------------------ fixtures

    private static DirectPlanner planner(DirectSettings settings) {
        return new DirectPlanner(settings);
    }

    private static DirectPlanState state(int tick, EntityKey preferred,
                                         Map<EntityKey, Integer> attempts, int lastSwitch) {
        return new DirectPlanState(2L, 7L, tick, preferred, attempts, lastSwitch);
    }

    private static DirectTarget target(int id, Vec position) {
        return new DirectTarget(entity(id), position, new Box(
            new Vec(position.x() - 0.3, position.y(), position.z() - 0.3),
            new Vec(position.x() + 0.3, position.y() + 1.8, position.z() + 0.3)),
            health(20.0), MitigationView.of(NO_ARMOUR), false, false, false, false, false);
    }

    private static DirectTarget withFlags(DirectTarget target, boolean dead, boolean creative,
                                          boolean spectator, boolean friend) {
        return new DirectTarget(target.entity(), target.position(), target.box(), target.health(),
            target.mitigation(), dead, creative, spectator, friend, target.armourPressure());
    }

    private static DirectTarget withFlags(DirectTarget target, boolean dead, boolean creative,
                                          boolean spectator, boolean friend, EntityKey entity) {
        return new DirectTarget(entity, target.position(), target.box(), target.health(),
            target.mitigation(), dead, creative, spectator, friend, target.armourPressure());
    }

    private static DirectTarget withArmourPressure(DirectTarget target) {
        return new DirectTarget(target.entity(), target.position(), target.box(), target.health(),
            target.mitigation(), target.dead(), target.creative(), target.spectator(),
            target.friend(), true);
    }

    private static EffectiveHealth health(double value) {
        return new EffectiveHealth(value, 0.0, Capability.KNOWN, 1L);
    }

    private static EntityKey entity(int id) {
        return new EntityKey(CONN, id, new UUID(0L, id));
    }

    private static Phase3FakeWorld world() {
        Phase3FakeWorld world = new Phase3FakeWorld();
        world.conn = CONN;
        world.self = SELF;
        world.eye = EYE;
        world.selfPosition = SELF_POSITION;
        world.safetyState = new SafetyState(CONN, GEN, SELF, health(20.0), Map.of(), SAFETY);
        world.hotbar.add(new DirectItem(0, "minecraft:diamond_sword", true));
        world.hotbar.add(new DirectItem(1, "minecraft:ender_pearl", false));
        world.hotbar.add(new DirectItem(2, "minecraft:end_crystal", false));
        world.hotbar.add(new DirectItem(3, "minecraft:obsidian", false));
        return world;
    }

    private static Phase3FakeWorld breakWorld(CrystalState crystalState, boolean own,
                                               boolean quarantined, int age,
                                               Map<EntityKey, Integer> ignored) {
        Phase3FakeWorld world = world();
        world.targets.add(target(40, CRYSTAL_POSITION));
        world.crystals.add(new DirectCrystal(entity(30), CRYSTAL_POSITION, crystalBox(CRYSTAL_POSITION),
            crystalState, own, quarantined, age));
        return world;
    }

    private static Phase3FakeWorld placeWorld() {
        Phase3FakeWorld world = world();
        world.selfPosition = new Vec(2.5, 64.5, 0.5);
        world.eye = new Vec(2.5, 65.62, 0.5);
        world.targets.add(target(41, new Vec(4.5, 64.5, 0.5)));
        world.blocks.ids.put(BASE, "minecraft:obsidian");
        return world;
    }

    private static Phase3FakeWorld placeWorldWithEstimate(TargetEstimate estimate, double targetHealth) {
        Phase3FakeWorld world = placeWorld();
        world.damage.estimate = estimate;
        world.damage.byCentre.put(new Vec(CRYSTAL_CELL.x() + 0.5, CRYSTAL_CELL.y(),
            CRYSTAL_CELL.z() + 0.5), estimate);
        DirectTarget low = new DirectTarget(world.targets.get(0).entity(),
            world.targets.get(0).position(), world.targets.get(0).box(), health(targetHealth),
            MitigationView.of(NO_ARMOUR), false, false, false, false, false);
        world.targets.set(0, low);
        return world;
    }

    private static Phase3FakeWorld supportWorld() {
        Phase3FakeWorld world = world();
        world.selfPosition = new Vec(2.5, 64.5, 0.5);
        world.eye = new Vec(2.5, 65.62, 0.5);
        world.targets.add(target(42, new Vec(4.5, 64.5, 0.5)));
        world.blocks.ids.put(new Cell(0, 63, 0), "minecraft:stone");
        world.blocks.ids.put(new Cell(0, 64, 0), "minecraft:short_grass");
        return world;
    }

    private static final Cell GAP_BASE = new Cell(4, 63, 1);
    private static final Cell GAP_CRYSTAL = new Cell(4, 64, 1);
    private static final Cell GAP_HEAD = new Cell(4, 65, 1);
    private static final Vec GAP_CENTRE = new Vec(4.5, 64.0, 1.5);

    /**
     * A target in a one-column hole: solid stone on three horizontal sides, the +z column air with an
     * obsidian base below the gap feet cell. A second, non-gap base prices higher so the gap preference is
     * observable.
     */
    private static Phase3FakeWorld gapWorld() {
        Phase3FakeWorld world = world();
        world.targets.add(target(80, new Vec(4.5, 64.5, 0.5)));
        for (Cell wall : List.of(new Cell(3, 64, 0), new Cell(3, 65, 0),
            new Cell(5, 64, 0), new Cell(5, 65, 0), new Cell(4, 64, -1), new Cell(4, 65, -1))) {
            world.blocks.ids.put(wall, "minecraft:stone");
        }
        world.blocks.ids.put(GAP_BASE, "minecraft:obsidian");
        world.blocks.ids.put(BASE, "minecraft:obsidian");
        world.damage.byCentre.put(GAP_CENTRE, new TargetEstimate(4.0, 5.0, 8.0));
        world.damage.byCentre.put(new Vec(0.5, 64.0, 0.5), new TargetEstimate(4.0, 9.0, 12.0));
        return world;
    }

    /** The same hole with only the gap base legal, so a rejected gap placement cannot be masked. */
    private static Phase3FakeWorld gapOnlyWorld() {
        Phase3FakeWorld world = gapWorld();
        world.blocks.ids.remove(BASE);
        world.damage.byCentre.remove(new Vec(0.5, 64.0, 0.5));
        return world;
    }

    private static Phase3FakeWorld bothWorld() {
        Phase3FakeWorld world = breakWorld(CrystalState.OBSERVED, true, false, 0, Map.of());
        world.blocks.ids.put(BASE, "minecraft:obsidian");
        return world;
    }

    private static Phase3FakeWorld stickyWorld() {
        Phase3FakeWorld world = world();
        DirectTarget first = target(70, new Vec(2.5, 64.5, 0.5));
        DirectTarget second = target(71, new Vec(-1.5, 64.5, 0.5));
        DirectTarget preferred = target(72, new Vec(0.5, 64.5, 2.5));
        world.targets.addAll(List.of(first, second, preferred));
        world.crystals.add(new DirectCrystal(entity(33), new Vec(10.5, 64.5, 0.5),
            crystalBox(new Vec(10.5, 64.5, 0.5)), CrystalState.OBSERVED, true, false, 0));
        world.damage.byTarget.put(first.entity(), new TargetEstimate(4, 6, 8));
        world.damage.byTarget.put(second.entity(), new TargetEstimate(4, 6, 8));
        world.damage.byTarget.put(preferred.entity(), new TargetEstimate(4, 5, 7));
        return world;
    }

    private static Box crystalBox(Vec position) {
        return boxOf(position);
    }

    private static DirectSettings ownOnlySettings() {
        return new DirectSettings(6.0, 4.5, 3.0, 3.0, SAFETY, true, 0, 2, true, 8.0, 0.10, false,
            false, 0, true, true, true, true, true, true, 6.0, true, 10.0, 1_000_000_000L);
    }

    private static Box boxOf(Vec position) {
        return new Box(new Vec(position.x() - 1.0, position.y(), position.z() - 1.0),
            new Vec(position.x() + 1.0, position.y() + 2.0, position.z() + 1.0));
    }

    // ------------------------------------------------------------------ hurt-sync (4.3)

    @Test
    void hurtSyncUsesTheAdjustedEstimateForPolicyAndRanking() {
        Phase3FakeWorld world = placeWorldWithEstimate(new TargetEstimate(4.0, 10.0, 12.0), 20.0);
        TargetEstimate hurt = new TargetEstimate(0.0, 5.0, 12.0);
        world.damage.hurtEstimate = hurt;
        world.damage.deferUntil = 7L;

        DirectPlan plan = planner(DEFAULT.withHurtSync(true))
            .plan(world, state(0, null, Map.of(), -1)).orElseThrow();

        assertEquals(hurt, plan.context().estimate(),
            "the policy checks the hurt-adjusted target estimate");
        assertEquals(hurt, plan.context().hurtEstimate(),
            "the hurt-adjusted estimate is also carried for ranking");
        assertEquals(-1L, plan.context().deferUntilTick(),
            "a placement is not a detonation; attack deferral is covered by HurtSyncEngineTest");
    }

    @Test
    void hurtAdjustedExpectedBelowTheMinimumIsRejected() {
        Phase3FakeWorld world = placeWorldWithEstimate(new TargetEstimate(4.0, 10.0, 12.0), 20.0);
        world.damage.hurtEstimate = new TargetEstimate(0.0, 3.9, 12.0);

        assertTrue(planner(DEFAULT.withHurtSync(true))
            .plan(world, state(0, null, Map.of(), -1)).isEmpty(),
            "the immune target's expected damage is below the minimum target damage");
        assertTrue(planner(DEFAULT)
            .plan(world, state(0, null, Map.of(), -1)).isPresent(),
            "the Phase 3 estimate is still allowed when hurt-sync is off");
    }

    @Test
    void hurtSyncCarriesTheAttackDeferralOnABreakPlan() {
        Phase3FakeWorld world = breakWorld(CrystalState.OBSERVED, true, false, 0, Map.of());
        TargetEstimate hurt = new TargetEstimate(0.0, 5.0, 12.0);
        world.damage.hurtEstimate = hurt;
        world.damage.deferUntil = 7L;

        DirectPlan plan = planner(DEFAULT.withHurtSync(true))
            .plan(world, state(0, null, Map.of(), -1)).orElseThrow();

        assertEquals(Action.ATTACK_CRYSTAL, plan.action());
        assertEquals(7L, plan.context().deferUntilTick(),
            "an attack waits for the hurt-sync target tick");
    }

    @Test
    void hurtSyncOffIsExactlyPhase3() {
        Phase3FakeWorld world = placeWorldWithEstimate(new TargetEstimate(4.0, 10.0, 12.0), 20.0);
        world.damage.hurtEstimate = new TargetEstimate(0.0, 1.0, 12.0);
        world.damage.deferUntil = 9L;

        DirectPlan plan = planner(DEFAULT)
            .plan(world, state(0, null, Map.of(), -1)).orElseThrow();

        assertEquals(new TargetEstimate(4.0, 10.0, 12.0), plan.context().estimate());
        assertEquals(null, plan.context().hurtEstimate());
        assertEquals(-1L, plan.context().deferUntilTick());
    }

    @Test
    void hurtSyncChangesTheRankingOrderOnly() {
        Phase3FakeWorld world = world();
        world.selfPosition = new Vec(2.5, 64.5, 0.5);
        world.eye = new Vec(2.5, 65.62, 0.5);
        DirectTarget a = target(50, new Vec(0.5, 64.5, 2.5));
        DirectTarget b = target(51, new Vec(-2.5, 64.5, 2.5));
        world.targets.addAll(List.of(a, b));
        world.blocks.ids.put(BASE, "minecraft:obsidian");
        world.blocks.ids.put(new Cell(-2, 63, 0), "minecraft:obsidian");
        world.damage.byTarget.put(a.entity(), new TargetEstimate(4.0, 10.0, 12.0));
        world.damage.byTarget.put(b.entity(), new TargetEstimate(4.0, 8.0, 10.0));
        world.damage.hurtByTarget.put(a.entity(), new TargetEstimate(0.0, 2.0, 12.0));
        world.damage.hurtByTarget.put(b.entity(), new TargetEstimate(0.0, 8.0, 10.0));

        List<EntityKey> phase3 = planner(DEFAULT).selectTargets(world, state(0, null, Map.of(), -1));
        List<EntityKey> hurt = planner(DEFAULT.withHurtSync(true))
            .selectTargets(world, state(0, null, Map.of(), -1));

        assertEquals(a.entity(), phase3.get(0), "Phase 3 ranks the higher expected damage first");
        assertEquals(b.entity(), hurt.get(0),
            "hurt-sync re-ranks with the immunity-adjusted expected damage");
    }

    @Test
    void hurtSyncNeverChangesExposureBounds() {
        Phase3FakeWorld world = world();
        world.selfPosition = new Vec(2.5, 64.5, 0.5);
        world.eye = new Vec(2.5, 65.62, 0.5);
        world.targets.add(target(52, new Vec(4.5, 64.5, 0.5)));
        world.blocks.ids.put(BASE, "minecraft:obsidian");
        world.damage.hurtEstimate = new TargetEstimate(0.0, 5.0, 12.0);
        world.damage.selfUpper = new MitigatedDamage(1.0);
        world.damage.friendUppers = Map.of(entity(60), new MitigatedDamage(1.5));

        DirectPlan off = planner(DEFAULT)
            .plan(world, state(0, null, Map.of(), -1)).orElseThrow();
        DirectPlan on = planner(DEFAULT.withHurtSync(true))
            .plan(world, state(0, null, Map.of(), -1)).orElseThrow();

        assertEquals(off.context().exposure(), on.context().exposure(),
            "hurt-sync must not move self/friend upper bounds or the envelope");
    }

    // ------------------------------------------------------------------ fakes

    static final class Phase3FakeWorld implements DirectWorld {
        ConnectionKey conn = CONN;
        SafetyState safetyState;
        EntityKey self = SELF;
        Vec eye = EYE;
        Vec selfPosition = SELF_POSITION;
        final List<DirectTarget> targets = new ArrayList<>();
        final List<DirectCrystal> crystals = new ArrayList<>();
        final List<DirectItem> hotbar = new ArrayList<>();
        int selectedSlot;
        boolean weakness;
        final Phase3FakeBlocks blocks = new Phase3FakeBlocks();
        final Phase3FakeDamage damage = new Phase3FakeDamage();
        boolean reachable = true;
        boolean placeReachable = true;
        boolean visible = true;
        boolean eating;
        boolean drinking;
        boolean mining;
        boolean screenOpen;
        boolean baritone;
        double tps = 20.0;
        long nanosSinceLastFeedback;

        @Override public ConnectionKey connection() { return conn; }
        @Override public SafetyState safetyState() { return safetyState; }
        @Override public EntityKey self() { return self; }
        @Override public Vec eye() { return eye; }
        @Override public Vec selfPosition() { return selfPosition; }
        @Override public List<DirectTarget> targets() { return targets; }
        @Override public List<DirectCrystal> crystals() { return crystals; }
        @Override public List<DirectItem> hotbar() { return hotbar; }
        @Override public int selectedSlot() { return selectedSlot; }
        @Override public boolean weakness() { return weakness; }
        @Override public DirectBlocks blocks() { return blocks; }
        @Override public DirectDamage damage() { return damage; }
        @Override public boolean crystalReachable(Vec crystalPosition, Box crystalBox,
                                                  double attackRange, double wallsRange) {
            return reachable;
        }
        @Override public boolean placementReachable(Cell crystalCell, double placeRange) {
            return placeReachable;
        }
        @Override public boolean visible(Vec eye, Box box) { return visible; }
        @Override public boolean eating() { return eating; }
        @Override public boolean drinking() { return drinking; }
        @Override public boolean mining() { return mining; }
        @Override public boolean screenOpen() { return screenOpen; }
        @Override public boolean baritoneActive() { return baritone; }
        @Override public double tps() { return tps; }
        @Override public long nanosSinceLastFeedback() { return nanosSinceLastFeedback; }
    }

    static final class Phase3FakeBlocks implements DirectBlocks {
        final Map<Cell, String> ids = new HashMap<>();
        final Set<Cell> blocked = new HashSet<>();
        final Set<Cell> crystalCells = new HashSet<>();
        final Set<Cell> invisibleFaces = new HashSet<>();
        final Set<Cell> pistons = new HashSet<>();
        final Map<Cell, PistonFacing> pistonFacings = new HashMap<>();
        final Set<Cell> poweredPistons = new HashSet<>();
        final Set<Cell> extendedPistons = new HashSet<>();
        boolean entityFree = true;

        @Override public String blockId(Cell cell) { return ids.getOrDefault(cell, ""); }
        @Override public boolean isAir(Cell cell) {
            String id = ids.get(cell);
            return id == null || id.equals("minecraft:air") || id.equals("minecraft:cave_air")
                || id.equals("minecraft:void_air");
        }
        @Override public boolean isBaseBlock(Cell cell) {
            String id = ids.get(cell);
            return "minecraft:obsidian".equals(id) || "minecraft:bedrock".equals(id);
        }
        @Override public boolean isReplaceable(Cell cell) {
            return !isBaseBlock(cell) && (isAir(cell) || "minecraft:short_grass".equals(ids.get(cell)));
        }
        @Override public boolean hasSupportBelow(Cell cell) {
            Cell below = cell.below();
            return !isAir(below) && !isReplaceable(below);
        }
        @Override public boolean blocked(Cell cell) { return blocked.contains(cell); }
        @Override public boolean entityFree(Box box) { return entityFree; }
        @Override public boolean crystalPresent(Cell cell) { return crystalCells.contains(cell); }
        @Override public boolean faceVisible(Vec eye, Cell base) { return !invisibleFaces.contains(base); }
        @Override public boolean isPiston(Cell cell) { return pistons.contains(cell); }
        @Override public PistonFacing pistonFacing(Cell cell) { return pistonFacings.get(cell); }
        @Override public boolean pistonPowered(Cell cell) { return poweredPistons.contains(cell); }
        @Override public boolean pistonExtended(Cell cell) { return extendedPistons.contains(cell); }
    }

    static final class Phase3FakeDamage implements DirectDamage {
        TargetEstimate estimate = new TargetEstimate(4.0, 6.0, 8.0);
        TargetEstimate hurtEstimate;
        long deferUntil = -1L;
        final Map<EntityKey, TargetEstimate> byTarget = new HashMap<>();
        final Map<EntityKey, TargetEstimate> hurtByTarget = new HashMap<>();
        final Map<Vec, TargetEstimate> byCentre = new HashMap<>();
        final List<Set<Cell>> targetOverlays = new ArrayList<>();
        final List<Set<Cell>> selfOverlays = new ArrayList<>();
        MitigatedDamage selfUpper = new MitigatedDamage(0.0);
        MitigatedDamage selfUpperWorst;
        Map<EntityKey, MitigatedDamage> friendUppers = Map.of();
        Map<EntityKey, MitigatedDamage> friendUppersWorst = Map.of();
        MitigationEnvelope envelope = new MitigationEnvelope(0L);

        @Override public MitigationEnvelope envelope() { return envelope; }

        @Override public TargetEstimate targetEstimate(DirectTarget target, Vec crystalCentre,
                                                       java.util.Collection<Cell> overlays) {
            targetOverlays.add(Set.copyOf(overlays));
            TargetEstimate byPosition = byCentre.get(crystalCentre);
            if (byPosition != null) return byPosition;
            return byTarget.getOrDefault(target.entity(), estimate);
        }

        @Override public TargetEstimate targetEstimate(DirectTarget target, Vec crystalCentre,
                                                       java.util.Collection<Cell> overlays,
                                                       Arrival arrival) {
            TargetEstimate hurt = hurtByTarget.get(target.entity());
            if (hurt != null) return hurt;
            return hurtEstimate == null ? targetEstimate(target, crystalCentre, overlays)
                : hurtEstimate;
        }

        @Override public long hurtSyncDeferUntil(DirectTarget target, Vec crystalCentre,
                                                  java.util.Collection<Cell> overlays,
                                                  Arrival immediateArrival, int serverTick,
                                                  TargetEstimate immediate) {
            return deferUntil;
        }

        @Override public MitigatedDamage selfUpper(Vec crystalCentre,
                                                   java.util.Collection<Cell> overlays) {
            selfOverlays.add(Set.copyOf(overlays));
            return selfUpper;
        }

        @Override public Map<EntityKey, MitigatedDamage> friendUppers(Vec crystalCentre,
                                                                      java.util.Collection<Cell> overlays) {
            return friendUppers;
        }

        @Override public MitigatedDamage selfUpperWorst(Vec selfFeet, Vec crystalCentre,
                                                         java.util.Collection<Cell> overlays) {
            return selfUpperWorst != null ? selfUpperWorst
                : DirectDamage.super.selfUpperWorst(selfFeet, crystalCentre, overlays);
        }

        @Override public MitigatedDamage friendUpperWorst(EntityKey friend, Vec friendFeet,
                                                           Vec crystalCentre,
                                                           java.util.Collection<Cell> overlays) {
            if (friendUppersWorst.containsKey(friend)) return friendUppersWorst.get(friend);
            return DirectDamage.super.friendUpperWorst(friend, friendFeet, crystalCentre, overlays);
        }
    }

}
