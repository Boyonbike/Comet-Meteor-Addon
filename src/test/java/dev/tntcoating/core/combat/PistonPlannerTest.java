/*
 * Original Comet test written against the project's own interfaces; no third-party code.
 * SPDX-License-Identifier: CC0-1.0
 */

package dev.tntcoating.core.combat;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/** Phase 5.2: stationary/hole eligibility, swept-path damage and bounded resumable enumeration. */
final class PistonPlannerTest {
    private static final ConnectionKey CONN = new ConnectionKey(1);
    private static final EntityKey TARGET = new EntityKey(CONN, 1, UUID.randomUUID());
    private static final EntityKey SELF = new EntityKey(CONN, 99, UUID.randomUUID());

    @Test
    void emptyBetaLayoutSelectionLeavesThePlannerInertWithoutAFallback() {
        PistonPlanner planner = new PistonPlanner();
        planner.setSelection(PistonLayoutSelection.none());
        PistonPlanner.Result result = planner.tick(cradle(), List.of(TARGET), 64);
        assertTrue(result.plan().isEmpty());
        assertTrue(result.complete());
        assertEquals(0, result.evaluated());
        assertEquals(PistonPlanner.Reject.LAYOUT_DISABLED, result.lastReject());
    }

    @Test
    void stationaryHoledTargetProducesTheSinglePushPlan() {
        PistonPlanner planner = new PistonPlanner();
        PistonPlanner.Result result = planner.tick(cradle(), List.of(TARGET), 64);
        assertTrue(result.plan().isPresent(), () -> "no plan, last reject " + result.lastReject());
        PistonPlanner.Plan plan = result.plan().orElseThrow();
        assertEquals(TARGET, plan.target());
        assertEquals(PistonGeometry.EAST, plan.push().facing());
        assertEquals(0, plan.push().vertical());
        assertEquals(new Cell(-1, -60, 0), plan.source());
        assertEquals(new Cell(0, -60, 0), plan.push().destination());
        assertEquals(10.0, plan.targetDamage(), 1.0e-9);
        assertEquals(List.of(Action.PLACE_PISTON, Action.PLACE_CRYSTAL, Action.PLACE_POWER),
            plan.intents().stream().map(Intent::action).toList());
        Intent crystal = plan.crystalIntent().orElseThrow();
        Intent piston = plan.pistonIntent().orElseThrow();
        Intent power = plan.powerIntent().orElseThrow();
        assertTrue(crystal.dependsOn().contains(piston), "crystal must depend on the piston");
        assertTrue(power.dependsOn().contains(crystal), "power must depend on the crystal");
        assertTrue(crystal.blockLocks().contains(plan.source()), "the crystal sits in the source cell");
        assertTrue(power.blockLocks().contains(plan.push().power()));
        // The risk reservation point is the crystal intent, not the power intent.
        assertEquals(Action.PLACE_CRYSTAL, plan.intents().get(1).action());
    }

    @Test
    void expectedPushedPositionComesFromTheMeasuredTraceRule() {
        PistonPlanner.Result result = new PistonPlanner().tick(cradle(), List.of(TARGET), 64);
        PistonPlanner.Plan plan = result.plan().orElseThrow();
        Vec expected = plan.expectedPushed();
        assertEquals(plan.source().center().x() + 1.02, expected.x(), 1.0e-9);
        assertEquals(plan.source().center().y(), expected.y(), 1.0e-9);
        assertEquals(1.02, expected.x() - plan.source().center().x(), 1.0e-9);
        // The plan's literal 0.51+0.01/tick model predicts 1.04; the measured trace value is authoritative.
        assertFalse(Math.abs(1.02 - PistonGeometry.LITERAL_PLAN_DISPLACEMENT) < 1.0e-9);
    }

    @Test
    void movingTargetIsRejectedNotSpeculated() {
        Fake world = cradle();
        world.speed = 0.2;
        PistonPlanner.Result result = new PistonPlanner().tick(world, List.of(TARGET), 64);
        assertTrue(result.exhausted());
        assertEquals(PistonPlanner.Reject.NOT_STATIONARY, result.lastReject());
        assertEquals(0, result.evaluated());
    }

    @Test
    void targetOutsideAHoleIsRejected() {
        Fake world = cradle();
        world.blocks.put(new Cell(1, -60, 0), "minecraft:air");
        world.blocks.put(new Cell(1, -59, 0), "minecraft:air");
        world.blocks.put(new Cell(0, -60, 1), "minecraft:air");
        world.blocks.put(new Cell(0, -59, 1), "minecraft:air");
        world.blocks.put(new Cell(0, -60, -1), "minecraft:air");
        world.blocks.put(new Cell(0, -59, -1), "minecraft:air");
        PistonPlanner.Result result = new PistonPlanner().tick(world, List.of(TARGET), 64);
        assertTrue(result.plan().isEmpty());
        assertEquals(PistonPlanner.Reject.NOT_HOLED, result.lastReject());
    }

    @Test
    void friendBoundsUseTheWeakestMitigationEnvelope() {
        Fake world = cradle();
        EntityKey friend = new EntityKey(CONN, 2, UUID.randomUUID());
        world.friends.add(friend);
        world.positions.put(friend, new Vec(0.5, -60.0, 2.5));
        world.feasible.put(friend, List.of(new Vec(0.5, -60.0, 0.5)));
        world.friendCeiling = 4.0; // the friend's feasible point takes 10 > 4
        world.mitigation = 1.0;
        PistonPlanner.Result result = new PistonPlanner().tick(world, List.of(TARGET), 64);
        assertTrue(result.plan().isEmpty());
        assertEquals(PistonPlanner.Reject.FRIEND_BOUNDS, result.lastReject());
    }

    @Test
    void selfBoundsRejectACandidateWhoseBlastReachesThePlayer() {
        Fake world = cradle();
        world.self = new EntityKey(CONN, 98, UUID.randomUUID());
        world.positions.put(world.self, new Vec(0.5, -60.0, 1.5));
        PistonPlanner.Result result = new PistonPlanner().tick(world, List.of(TARGET), 64);
        assertTrue(result.plan().isEmpty());
        assertEquals(PistonPlanner.Reject.SELF_BOUNDS, result.lastReject());
    }

    @Test
    void selfBoundsUseTheFullThreeDimensionalFeasibleSet() {
        Fake world = cradle();
        world.self = new EntityKey(CONN, 98, UUID.randomUUID());
        // Current position is far away; the only exposed feasible point is vertically below.
        world.positions.put(world.self, new Vec(40.0, 0.0, 40.0));
        world.feasible.put(world.self, List.of(new Vec(0.5, -60.5, 1.5)));
        PistonPlanner.Result result = new PistonPlanner().tick(world, List.of(TARGET), 64);
        assertTrue(result.plan().isEmpty());
        assertEquals(PistonPlanner.Reject.SELF_BOUNDS, result.lastReject());
    }

    @Test
    void unknownMotionIsRejectedAndNeverSampled() {
        Fake world = cradle();
        world.motionUnknown.add(world.self);
        PistonPlanner.Result result = new PistonPlanner().tick(world, List.of(TARGET), 64);
        assertTrue(result.plan().isEmpty());
        assertEquals(PistonPlanner.Reject.MOTION_UNKNOWN, result.lastReject());
    }

    @Test
    void guaranteedTargetDamageUsesTheWholeFeasibleTargetSet() {
        Fake world = cradle();
        // Current position gets 10; a feasible pushed point gets only 2, so the minimum is lethal-level 2.
        Vec weak = new Vec(2.5, -60.0, 0.5);
        world.feasible.put(TARGET, List.of(new Vec(0.5, -60.0, 0.5), weak));
        world.victimDamage.put(weak, 2.0);
        PistonPlanner.Result result = new PistonPlanner().tick(world, List.of(TARGET), 64);
        assertTrue(result.plan().isEmpty());
        assertEquals(PistonPlanner.Reject.DAMAGE_TOO_LOW, result.lastReject());
    }

    @Test
    void bothPowerKindsAreEnumeratedAndThePlanCarriesTheFullContract() {
        PistonPlanner.Result result = new PistonPlanner().tick(cradle(), List.of(TARGET), 64);
        PistonPlanner.Plan plan = result.plan().orElseThrow();
        assertEquals(16, result.evaluated(), "one target x eight layouts x two power kinds");
        assertTrue(plan.push().powerKind() == PistonGeometry.PowerKind.REDSTONE_TORCH
            || plan.push().powerKind() == PistonGeometry.PowerKind.REDSTONE_BLOCK);
        PistonPlanner.PistonPlan contract = plan.pistonPlan();
        assertEquals(1, contract.pushes().size());
        assertEquals(plan.push().piston(), contract.pushes().get(0).piston());
        assertEquals(plan.push().power(), contract.pushes().get(0).power());
        assertTrue(contract.footprint().structural().contains(plan.push().piston()));
        assertTrue(contract.footprint().structural().contains(plan.push().power()));
        assertTrue(contract.footprint().swept().contains(plan.expectedPushed()));
        assertFalse(contract.footprint().blastSearch().equals(contract.footprint().swept()));
        assertTrue(contract.opportunity().isEmpty(), "the pure planner must not fabricate an estimate");
    }

    @Test
    void armouredTargetIsRejectedWhileUnarmouredIsAccepted() {
        Fake armoured = cradle();
        armoured.mitigation = 0.1; // 10 raw x 0.1 mitigation = 1 < 6 lethal floor
        PistonPlanner.Result rejectedResult = new PistonPlanner().tick(armoured, List.of(TARGET), 64);
        assertTrue(rejectedResult.plan().isEmpty());
        assertEquals(PistonPlanner.Reject.DAMAGE_TOO_LOW, rejectedResult.lastReject());
        PistonPlanner.Result accepted = new PistonPlanner().tick(cradle(), List.of(TARGET), 64);
        assertTrue(accepted.plan().isPresent());
    }

    @Test
    void damageBelowTheLethalThresholdIsRejected() {
        Fake world = cradle();
        world.minimumDamage = 20.0;
        PistonPlanner.Result result = new PistonPlanner().tick(world, List.of(TARGET), 64);
        assertTrue(result.plan().isEmpty());
        assertEquals(PistonPlanner.Reject.DAMAGE_TOO_LOW, result.lastReject());
    }

    @Test
    void enumerationResumesInsideTheTickBudget() {
        PistonPlanner planner = new PistonPlanner();
        Fake world = cradle();
        PistonPlanner.Result first = planner.tick(world, List.of(TARGET), 1);
        assertEquals(1, first.evaluated());
        assertFalse(first.complete());
        int ticks = 1;
        PistonPlanner.Result current = first;
        while (!current.complete() && ticks < 64) {
            current = planner.tick(world, List.of(TARGET), 1);
            ticks++;
        }
        assertTrue(current.complete(), "scan must finish inside the bounded budget");
        assertNotNull(current.plan().orElse(null));
        assertEquals(16, current.evaluated(), "one target x eight base layouts x two power kinds");
        assertTrue(ticks <= 16);
    }

    @Test
    void candidateCapIsSixtyFour() {
        assertEquals(64, PistonPlanner.CANDIDATE_CAP);
        PistonPlanner.Result result = new PistonPlanner().tick(cradle(), List.of(TARGET), 1000);
        assertTrue(result.evaluated() <= PistonPlanner.CANDIDATE_CAP);
    }

    @Test
    void missingCrystalBaseIsPlannedAsASupportWithinBudget() {
        Fake world = cradle();
        world.blocks.remove(new Cell(-1, -61, 0)); // no obsidian base under the source
        world.put(new Cell(-1, -62, 0), "minecraft:stone");
        PistonPlanner.Result result = new PistonPlanner().tick(world, List.of(TARGET), 64);
        PistonPlanner.Plan plan = result.plan().orElseThrow(() -> new AssertionError("last reject " + result.lastReject()));
        assertEquals(Action.PLACE_SUPPORT, plan.intents().get(0).action());
        assertTrue(plan.intents().get(0).cell().equals(plan.source().below()));
        assertTrue(plan.push().source().below().equals(plan.intents().get(0).cell()));
        assertTrue(plan.intents().get(1).dependsOn().contains(plan.intents().get(0)),
            "the piston depends on the placed base support");
        assertTrue(plan.structural().contains(plan.source().below()));
        assertTrue(plan.structural().size() <= PistonGeometry.MAX_STRUCTURAL);
    }

    @Test
    void targetHullIsUsedEvenWhenTheDiscreteFeasibleSetLooksLethal() {
        Fake world = cradle();
        // The single-list path only contains the current, lethal point; the hull reaches far enough that
        // the guaranteed lower bound is zero.
        world.feasible.put(TARGET, List.of(new Vec(0.5, -60.0, 0.5)));
        world.hulls.put(TARGET, new Box(new Vec(0.5, -60.0, 0.5), new Vec(0.5, -50.0, 0.5)));
        PistonPlanner.Result result = new PistonPlanner().tick(world, List.of(TARGET), 64);
        assertTrue(result.plan().isEmpty());
        assertEquals(PistonPlanner.Reject.DAMAGE_TOO_LOW, result.lastReject());
    }

    @Test
    void selfHullIsUsedEvenWhenTheDiscreteFeasibleSetLooksSafe() {
        Fake world = cradle();
        world.self = new EntityKey(CONN, 98, UUID.randomUUID());
        world.positions.put(world.self, new Vec(40.0, 0.0, 40.0));
        // The discrete list only shows the far current point; the continuous hull reaches beside the
        // crystal path, so the self upper bound must reject the plan.
        world.feasible.put(world.self, List.of(new Vec(40.0, 0.0, 40.0)));
        world.hulls.put(world.self, new Box(new Vec(0.5, -60.5, 0.5), new Vec(0.52, -59.5, 0.5)));
        PistonPlanner.Result result = new PistonPlanner().tick(world, List.of(TARGET), 64);
        assertTrue(result.plan().isEmpty());
        assertEquals(PistonPlanner.Reject.SELF_BOUNDS, result.lastReject());
    }

    @Test
    void stationaryTightHullUsesTheExactVictimSet() {
        Fake world = cradle();
        // Residual sub-tolerance motion: the hull is non-degenerate but every axis is within the
        // position tolerance, and the target has been stationary. Exact current/pushed/midpoint
        // victims are a real lower-bound set here.
        world.speed = 0.0;
        world.stationaryTicks = 10;
        world.hulls.put(TARGET, new Box(new Vec(0.49, -60.01, 0.49), new Vec(0.51, -59.99, 0.51)));
        PistonPlanner.Result result = new PistonPlanner().tick(world, List.of(TARGET), 64);
        assertTrue(result.plan().isPresent());
    }

    @Test
    void looseHullStillFailsClosed() {
        Fake world = cradle();
        world.speed = 0.0;
        world.stationaryTicks = 10;
        world.hulls.put(TARGET, new Box(new Vec(0.4, -60.1, 0.4), new Vec(0.6, -59.9, 0.6)));
        PistonPlanner.Result result = new PistonPlanner().tick(world, List.of(TARGET), 64);
        assertTrue(result.plan().isEmpty());
        assertEquals(PistonPlanner.Reject.DAMAGE_TOO_LOW, result.lastReject());
    }

    @Test
    void zeroVolumeHullIsAPointAndKeepsTheDiscretePlan() {
        Fake world = cradle();
        Vec point = new Vec(0.5, -60.0, 0.5);
        world.hulls.put(TARGET, new Box(point, point));
        PistonPlanner.Result result = new PistonPlanner().tick(world, List.of(TARGET), 64);
        assertTrue(result.plan().isPresent());
    }

    @Test
    void interiorHullPointCannotBeClaimedFromSampledExposedCorners() {
        Fake world = cradle();
        // Every sampled point (list, current, destination, midpoint) is lethal, but a tiny hull makes the
        // interior unproven. A sampled minimum must never be presented as a guaranteed lower bound.
        world.feasible.put(TARGET, List.of(new Vec(0.5, -60.0, 0.5)));
        world.hulls.put(TARGET, new Box(new Vec(0.4, -60.05, 0.4), new Vec(0.6, -59.95, 0.6)));
        PistonPlanner.Result result = new PistonPlanner().tick(world, List.of(TARGET), 64);
        assertTrue(result.plan().isEmpty());
        assertEquals(PistonPlanner.Reject.DAMAGE_TOO_LOW, result.lastReject());
    }

    @Test
    void selfUpperPricesTheBodyBoxNotOnlyTheFeet() {
        Fake world = cradle();
        world.self = new EntityKey(CONN, 98, UUID.randomUUID());
        world.positions.put(world.self, new Vec(40.0, 0.0, 40.0));
        world.hulls.put(world.self, new Box(new Vec(40.0, 0.0, 40.0), new Vec(40.0, 2.0, 40.0)));
        world.selfBodyUpper = 10.0; // head-height body-box exposure; the far feet point alone prices 0
        PistonPlanner.Result result = new PistonPlanner().tick(world, List.of(TARGET), 64);
        assertTrue(result.plan().isEmpty());
        assertEquals(PistonPlanner.Reject.SELF_BOUNDS, result.lastReject());
    }

    @Test
    void selfUpperAppliesMitigationExactlyOnce() {
        Fake world = cradle();
        world.self = new EntityKey(CONN, 98, UUID.randomUUID());
        world.positions.put(world.self, new Vec(0.5, -59.5, 0.5));
        world.mitigation = 0.5;
        world.minimumDamage = 4.0;
        world.selfCeiling = 4.0;
        // Raw 10 at the body box, mitigation 0.5 -> 5, which is above the ceiling. A second discount
        // would produce 2.5 and wrongly accept the trap.
        PistonPlanner.Result result = new PistonPlanner().tick(world, List.of(TARGET), 64);
        assertTrue(result.plan().isEmpty());
        assertEquals(PistonPlanner.Reject.SELF_BOUNDS, result.lastReject());
    }

    @Test
    void targetLowerBoundSeamRejectsWhenRawDamageWouldPass() {
        Fake world = cradle();
        // Raw swept damage is 10 everywhere the plan samples, but an occluded target's audited
        // lower-bound exposure reports no positive damage.
        world.targetLowerZero = true;
        PistonPlanner.Result result = new PistonPlanner().tick(world, List.of(TARGET), 64);
        assertTrue(result.plan().isEmpty());
        assertEquals(PistonPlanner.Reject.DAMAGE_TOO_LOW, result.lastReject());
    }

    // ------------------------------------------------------------------ fake world

    private static Fake cradle() {
        Fake world = new Fake();
        world.floor(-61);
        world.put(new Cell(-1, -61, 0), "minecraft:obsidian"); // crystal base under the source cell
        world.put(new Cell(-1, -62, 0), "minecraft:stone"); // its support, inside the depth-2 budget
        world.blocks.put(new Cell(1, -60, 0), "minecraft:stone");
        world.blocks.put(new Cell(1, -59, 0), "minecraft:stone");
        world.blocks.put(new Cell(0, -60, 1), "minecraft:stone");
        world.blocks.put(new Cell(0, -59, 1), "minecraft:stone");
        world.blocks.put(new Cell(0, -60, -1), "minecraft:stone");
        world.blocks.put(new Cell(0, -59, -1), "minecraft:stone");
        // One-block-high trench to the west: the crystal slot, piston and power fit under a ceiling.
        world.blocks.put(new Cell(-1, -59, 0), "minecraft:stone");
        world.blocks.put(new Cell(-2, -59, 0), "minecraft:stone");
        world.blocks.put(new Cell(-3, -59, 0), "minecraft:stone");
        return world;
    }

    static final class Fake implements PistonPlanner.World {
        final Map<Cell, String> blocks = new HashMap<>();
        final Map<Cell, PistonFacing> facings = new HashMap<>();
        final Map<EntityKey, Vec> positions = new HashMap<>();
        final Map<EntityKey, List<Vec>> feasible = new HashMap<>();
        final Map<EntityKey, Box> hulls = new HashMap<>();
        final Set<EntityKey> motionUnknown = new HashSet<>();
        final Map<Vec, Double> victimDamage = new HashMap<>();
        final Set<EntityKey> friends = new HashSet<>();
        final Set<Cell> entities = new HashSet<>();
        final Set<EntityKey> friendlyKeys = new HashSet<>();
        boolean targetLowerZero;
        double selfBodyUpper = Double.NaN;
        EntityKey self = SELF;
        double speed;
        int stationaryTicks = 10;
        double selfCeiling = 6.0;
        double friendCeiling = 4.0;
        double minimumDamage = 6.0;
        double mitigation = 1.0;
        long planId = 7L;
        int currentTick = 100;

        Fake() {
            positions.put(TARGET, new Vec(0.5, -60.0, 0.5));
            positions.put(self, new Vec(40.0, 0.0, 40.0));
        }

        void put(Cell cell, String id) {
            blocks.put(cell, id);
        }

        void floor(int y) {
            for (int x = -6; x <= 6; x++) {
                for (int z = -6; z <= 6; z++) put(new Cell(x, y, z), "minecraft:stone");
            }
        }

        @Override
        public EntityKey self() {
            return self;
        }

        @Override
        public List<Vec> feasiblePositions(EntityKey entity) {
            if (motionUnknown.contains(entity)) return null;
            return feasible.getOrDefault(entity, List.of(position(entity)));
        }

        @Override
        public Box feasibleHull(EntityKey entity) {
            return hulls.get(entity);
        }

        @Override
        public double targetDamageLower(EntityKey target, Vec crystalCentre, Vec victimFeet) {
            if (targetLowerZero) return 0.0;
            return crystalDamage(crystalCentre, victimFeet) * Math.max(0.0, Math.min(1.0, mitigation));
        }

        @Override
        public double crystalDamageUpper(EntityKey entity, Vec crystalCentre, Vec victimFeet) {
            if (entity.equals(self) && !Double.isNaN(selfBodyUpper)) return selfBodyUpper;
            return crystalDamage(crystalCentre, victimFeet)
                * Math.max(0.0, Math.min(1.0, mitigation(entity)));
        }

        @Override
        public Vec position(EntityKey entity) {
            return positions.getOrDefault(entity, new Vec(0.5, -60.0, 0.5));
        }

        @Override
        public double speed(EntityKey entity) {
            return speed;
        }

        @Override
        public int stationaryTicks(EntityKey entity) {
            return stationaryTicks;
        }

        @Override
        public double crystalDamage(Vec crystalCentre, Vec victimFeet) {
            Double override = victimDamage.get(victimFeet);
            if (override != null) return override;
            double dx = crystalCentre.x() - victimFeet.x();
            double dy = crystalCentre.y() - victimFeet.y();
            double dz = crystalCentre.z() - victimFeet.z();
            return Math.sqrt(dx * dx + dy * dy + dz * dz) < 2.0 ? 10.0 : 0.0;
        }

        @Override
        public List<EntityKey> nearbyFriends() {
            return new ArrayList<>(friends);
        }

        @Override
        public boolean friendly(EntityKey entity) {
            return friends.contains(entity);
        }

        @Override
        public double mitigation(EntityKey entity) {
            return mitigation;
        }

        @Override
        public double selfDamageCeiling() {
            return selfCeiling;
        }

        @Override
        public double friendDamageCeiling() {
            return friendCeiling;
        }

        @Override
        public double minimumTargetDamage() {
            return minimumDamage;
        }

        @Override
        public long planId() {
            return planId;
        }

        @Override
        public int currentTick() {
            return currentTick;
        }

        @Override
        public String blockId(Cell cell) {
            return blocks.get(cell);
        }

        @Override
        public boolean replaceable(Cell cell) {
            String id = blocks.get(cell);
            return id == null || id.equals("minecraft:air") || id.equals("minecraft:cave_air");
        }

        @Override
        public boolean fullSupport(Cell cell) {
            String id = blocks.get(cell);
            return "minecraft:stone".equals(id) || "minecraft:obsidian".equals(id)
                || "minecraft:bedrock".equals(id);
        }

        @Override
        public boolean entityFree(Cell cell) {
            return !entities.contains(cell);
        }

        @Override
        public boolean crystalPresent(Cell cell) {
            return false;
        }

        @Override
        public boolean pistonPowered(Cell cell) {
            return false;
        }

        @Override
        public PistonFacing pistonFacing(Cell cell) {
            return facings.get(cell);
        }

        @Override
        public boolean redstoneComponent(Cell cell) {
            return false;
        }
    }
}
