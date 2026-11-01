/*
 * Original Comet test written against the project's own interfaces; no third-party code.
 * SPDX-License-Identifier: CC0-1.0
 */

package dev.tntcoating.core.combat;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;

/** Phase 5.1: pure piston geometry, layout table, base legality and placement checks. */
final class PistonGeometryTest {
    private static final Cell ANCHOR = new Cell(0, -60, 0);

    @Test
    void eightBaseLayoutsAndBothPowerKinds() {
        assertEquals(8, PistonGeometry.baseLayouts().size());
        for (PistonGeometry.PowerKind kind : PistonGeometry.PowerKind.values()) {
            assertEquals(8, PistonGeometry.layouts(ANCHOR, kind).size());
        }
        for (PistonGeometry.Layout layout : PistonGeometry.baseLayouts()) {
            assertTrue(layout.facing().horizontal());
            assertTrue(layout.vertical() == 0 || layout.vertical() == 1);
        }
    }

    @Test
    void eastFeetTorchMatchesThePhase0TraceTable() {
        PistonGeometry.PistonPush push = push(PistonGeometry.EAST, 0,
            PistonGeometry.PowerKind.REDSTONE_TORCH);
        // Fixture labels: source=(0,-60,0) piston=(-1,-60,0) power=(-2,-60,0).
        assertEquals(new Cell(0, -60, 0), push.source());
        assertEquals(new Cell(-1, -60, 0), push.piston());
        assertEquals(new Cell(-2, -60, 0), push.power());
        assertEquals("minecraft:redstone_torch", push.powerKind().itemId());
        assertEquals(PistonGeometry.MEASURED_DISPLACEMENT,
            push.expectedPushed().x() - push.source().center().x(), 1.0e-9);
    }

    @Test
    void allSixteenTraceLayoutsAreSupportedAndMeasured() {
        for (PistonGeometry.PowerKind kind : PistonGeometry.PowerKind.values()) {
            for (PistonGeometry.Layout layout : PistonGeometry.baseLayouts()) {
                String scenario = PistonGeometry.scenario(layout, kind);
                assertTrue(PistonGeometry.hasTraceEvidence(scenario), "missing trace " + scenario);
                PistonGeometry.PushTrace trace = PistonGeometry.trace(scenario).orElseThrow();
                assertEquals(1.02, trace.displacement(), 1.0e-9);
                assertEquals(0.51, trace.perTick(), 1.0e-9);
                assertEquals(2, trace.ticks());
                assertFalse(trace.displacement() == PistonGeometry.LITERAL_PLAN_DISPLACEMENT,
                    "the plan's literal 0.51+0.01/tick model predicts 1.04 and is wrong");
            }
        }
        assertFalse(PistonGeometry.hasTraceEvidence("piston_up_feet_torch"));
        assertNull(PistonGeometry.trace("piston_up_feet_torch").orElse(null));
        // A vertical (unsupported) layout cannot even be constructed: it throws rather than guessing.
        assertThrows(IllegalArgumentException.class, () -> PistonGeometry.layout(ANCHOR,
            new PistonFacing(0, -1, 0), 0, PistonGeometry.PowerKind.REDSTONE_TORCH));
    }

    @Test
    void sweptPathFollowsTheMeasuredRule() {
        PistonGeometry.PistonPush push = push(PistonGeometry.EAST, 0,
            PistonGeometry.PowerKind.REDSTONE_BLOCK);
        List<Box> frames = push.frames();
        assertEquals(3, frames.size());
        assertEquals(0.0, frames.get(0).center().x() - ANCHOR.center().x(), 1.0e-9);
        assertEquals(0.51, frames.get(1).center().x() - ANCHOR.center().x(), 1.0e-9);
        assertEquals(1.02, frames.get(2).center().x() - ANCHOR.center().x(), 1.0e-9);
        assertEquals(PistonGeometry.destinationOf(ANCHOR, push.facing()), push.destination());
        assertTrue(push.swept().contains(push.expectedPushed()));
    }

    @Test
    void everyLayoutPassesWithAnExistingObsidianBase() {
        for (PistonGeometry.PowerKind kind : PistonGeometry.PowerKind.values()) {
            for (PistonGeometry.Layout layout : PistonGeometry.baseLayouts()) {
                PistonGeometry.PistonPush push = PistonGeometry.layout(ANCHOR, layout.facing(),
                    layout.vertical(), kind);
                Fake world = withBase(push);
                PistonGeometry.Check check = PistonGeometry.check(world, push);
                assertTrue(check.ok(), () -> push.scenario() + " -> " + check.status());
                assertTrue(check.structural().size() <= PistonGeometry.MAX_STRUCTURAL);
                assertTrue(check.supportBlocks() <= 2, "at most the eye-height piston/power supports");
            }
        }
    }

    @Test
    void everyLayoutAcceptsAPlacedBaseWithinTwoSupportDepth() {
        for (PistonGeometry.PowerKind kind : PistonGeometry.PowerKind.values()) {
            for (PistonGeometry.Layout layout : PistonGeometry.baseLayouts()) {
                PistonGeometry.PistonPush push = PistonGeometry.layout(ANCHOR, layout.facing(),
                    layout.vertical(), kind);
                Fake world = placedBaseWorld(push);
                PistonGeometry.Check check = PistonGeometry.checkPlacement(world, push);
                assertTrue(check.ok(), () -> push.scenario() + " -> " + check.status());
                assertTrue(check.structural().size() <= PistonGeometry.MAX_STRUCTURAL);
                assertTrue(check.maxSupportDepth() <= PistonGeometry.MAX_SUPPORT_DEPTH);
                assertTrue(check.structural().contains(push.source().below()),
                    "the placed crystal base must be part of the structural plan");
            }
        }
    }

    @Test
    void baseDepthThreeIsRejectedAndMissingBaseIsReported() {
        PistonGeometry.PistonPush push = push(PistonGeometry.EAST, 0,
            PistonGeometry.PowerKind.REDSTONE_TORCH);
        Fake deep = new Fake();
        deep.floor(-64);
        assertEquals(PistonGeometry.CheckStatus.SUPPORT_TOO_DEEP,
            PistonGeometry.checkPlacement(deep, push).status());

        Fake occupiedBase = withBase(push);
        occupiedBase.put(push.source().below(), "minecraft:stone"); // not a valid crystal base
        assertEquals(PistonGeometry.CheckStatus.NO_CRYSTAL_BASE,
            PistonGeometry.check(occupiedBase, push).status());
    }

    @Test
    void crystalVolumeMustBeEntityFree() {
        PistonGeometry.PistonPush push = push(PistonGeometry.NORTH, 0,
            PistonGeometry.PowerKind.REDSTONE_TORCH);
        Fake world = withBase(push);
        world.entity(push.source());
        assertEquals(PistonGeometry.CheckStatus.OCCUPIED, PistonGeometry.check(world, push).status());
        world = withBase(push);
        world.entity(push.source().above());
        assertEquals(PistonGeometry.CheckStatus.OCCUPIED, PistonGeometry.check(world, push).status());
    }

    @Test
    void sixStructuralBlocksAcceptedAndSevenRejected() {
        assertTrue(PistonGeometry.withinStructuralBudget(6));
        assertFalse(PistonGeometry.withinStructuralBudget(7));
        PistonGeometry.PistonPush feet = push(PistonGeometry.EAST, 0,
            PistonGeometry.PowerKind.REDSTONE_BLOCK);
        assertTrue(PistonGeometry.check(withBase(feet), feet).ok());
        assertEquals(2, PistonGeometry.check(withBase(feet), feet).structural().size(),
            "piston + power; the base exists");
        PistonGeometry.PistonPush eye = push(PistonGeometry.SOUTH, 1,
            PistonGeometry.PowerKind.REDSTONE_BLOCK);
        assertTrue(PistonGeometry.check(withBase(eye), eye).ok());
        assertTrue(PistonGeometry.check(withBase(eye), eye).structural().size() <= 6);
    }

    @Test
    void twoSupportBlocksAreAcceptedAndThreeAreRejected() {
        PistonGeometry.PistonPush push = push(PistonGeometry.EAST, 0,
            PistonGeometry.PowerKind.REDSTONE_TORCH);
        Fake two = new Fake();
        two.put(push.piston().below(), "minecraft:stone");
        two.put(push.power().below(), "minecraft:stone");
        two.put(push.source().below().below().below(), "minecraft:stone"); // base -> -62 -> -63
        PistonGeometry.Check accepted = PistonGeometry.checkPlacement(two, push);
        assertTrue(accepted.ok(), () -> "depth-2 -> " + accepted.status());
        assertEquals(2, accepted.maxSupportDepth());
        assertTrue(accepted.structural().size() <= PistonGeometry.MAX_STRUCTURAL);

        Fake three = new Fake();
        three.put(push.piston().below(), "minecraft:stone");
        three.put(push.power().below(), "minecraft:stone");
        three.put(push.source().below().below().below().below(), "minecraft:stone");
        assertEquals(PistonGeometry.CheckStatus.SUPPORT_TOO_DEEP,
            PistonGeometry.checkPlacement(three, push).status());
    }

    @Test
    void torchAndRedstoneBlockLegality() {
        PistonGeometry.PistonPush torch = push(PistonGeometry.NORTH, 0,
            PistonGeometry.PowerKind.REDSTONE_TORCH);
        assertEquals("minecraft:redstone_torch", torch.powerKind().itemId());
        assertFalse(torch.powerKind().solidPowerSource());
        assertTrue(PistonGeometry.check(withBase(torch), torch).ok());

        PistonGeometry.PistonPush block = push(PistonGeometry.WEST, 1,
            PistonGeometry.PowerKind.REDSTONE_BLOCK);
        assertEquals("minecraft:redstone_block", block.powerKind().itemId());
        assertTrue(block.powerKind().solidPowerSource());
        assertTrue(PistonGeometry.check(withBase(block), block).ok());
    }

    @Test
    void powerPresentEarlyAndEarlyPistonPoweringAreRejected() {
        PistonGeometry.PistonPush push = push(PistonGeometry.EAST, 0,
            PistonGeometry.PowerKind.REDSTONE_BLOCK);
        Fake world = withBase(push);
        world.put(push.power(), "minecraft:redstone_block");
        assertEquals(PistonGeometry.CheckStatus.POWER_PRESENT_EARLY,
            PistonGeometry.check(world, push).status());

        world = withBase(push);
        world.powered(push.piston(), true);
        assertEquals(PistonGeometry.CheckStatus.PISTON_POWERED_EARLY,
            PistonGeometry.check(world, push).status());
    }

    @Test
    void powerThatWouldActivateAnotherComponentIsRejected() {
        PistonGeometry.PistonPush push = push(PistonGeometry.EAST, 0,
            PistonGeometry.PowerKind.REDSTONE_TORCH);
        Fake world = withBase(push);
        world.put(push.power().above(), "minecraft:piston");
        world.facing(push.power().above(), PistonGeometry.WEST);
        assertEquals(PistonGeometry.CheckStatus.POWER_AFFECTS_COMPONENT,
            PistonGeometry.check(world, push).status());

        world = withBase(push);
        world.put(push.power().below(), "minecraft:oak_door");
        assertEquals(PistonGeometry.CheckStatus.POWER_AFFECTS_COMPONENT,
            PistonGeometry.check(world, push).status());
    }

    @Test
    void wrongObservedPistonFacingIsRejected() {
        PistonGeometry.PistonPush push = push(PistonGeometry.EAST, 0,
            PistonGeometry.PowerKind.REDSTONE_BLOCK);
        Fake world = withBase(push);
        world.put(push.piston(), "minecraft:piston");
        world.facing(push.piston(), PistonGeometry.NORTH);
        assertEquals(PistonGeometry.CheckStatus.WRONG_FACING, PistonGeometry.check(world, push).status());
        world.facing(push.piston(), PistonGeometry.EAST);
        assertTrue(PistonGeometry.check(world, push).ok());
    }

    @Test
    void blockedPushPathAndMissingCrystalAreRejected() {
        PistonGeometry.PistonPush push = push(PistonGeometry.SOUTH, 0,
            PistonGeometry.PowerKind.REDSTONE_BLOCK);
        Fake world = withBase(push);
        world.crystal = null;
        assertEquals(PistonGeometry.CheckStatus.SOURCE_CRYSTAL_MISSING,
            PistonGeometry.check(world, push).status());

        world = withBase(push);
        world.put(push.destination(), "minecraft:obsidian");
        assertEquals(PistonGeometry.CheckStatus.PATH_BLOCKED, PistonGeometry.check(world, push).status());
    }

    @Test
    void scenarioNamesAreUniqueAndMatchFixtureNaming() {
        List<String> scenarios = new ArrayList<>();
        for (PistonGeometry.PowerKind kind : PistonGeometry.PowerKind.values()) {
            for (PistonGeometry.Layout layout : PistonGeometry.baseLayouts()) {
                String name = PistonGeometry.scenario(layout, kind);
                assertNotNull(name);
                assertTrue(name.startsWith("piston_"), name);
                assertFalse(scenarios.contains(name), "duplicate " + name);
                scenarios.add(name);
            }
        }
        assertEquals(16, scenarios.size());
        assertTrue(scenarios.contains("piston_east_feet_redstone"));
        assertTrue(scenarios.contains("piston_west_eye_torch"));
    }

    // ------------------------------------------------------------------ helpers

    private static PistonGeometry.PistonPush push(PistonFacing facing, int vertical,
                                                 PistonGeometry.PowerKind kind) {
        return PistonGeometry.layouts(ANCHOR, kind).stream()
            .filter(candidate -> candidate.facing().equals(facing) && candidate.vertical() == vertical)
            .findFirst().orElseThrow();
    }

    /** Existing obsidian base plus a stone floor one below it and under the piston/power. */
    private static Fake placedBaseWorld(PistonGeometry.PistonPush push) {
        Fake world = new Fake();
        world.put(push.source().below().below(), "minecraft:stone");
        world.put(push.piston().below(), "minecraft:stone");
        world.put(push.power().below(), "minecraft:stone");
        return world;
    }

    private static Fake withBase(PistonGeometry.PistonPush push) {
        Fake world = new Fake();
        world.floor(-61);
        world.put(push.source().below(), "minecraft:obsidian");
        world.crystal = push.source();
        return world;
    }

    /** Minimal geometry-only world fake. */
    static final class Fake implements PistonGeometry.World {
        final Map<Cell, String> blocks = new HashMap<>();
        final Map<Cell, PistonFacing> facings = new HashMap<>();
        final Set<Cell> powered = new HashSet<>();
        final Set<Cell> entities = new HashSet<>();
        Cell crystal;

        void put(Cell cell, String id) {
            blocks.put(cell, id);
        }

        void crystal(Cell cell) {
            crystal = cell;
        }

        void powered(Cell cell, boolean on) {
            if (on) powered.add(cell); else powered.remove(cell);
        }

        void facing(Cell cell, PistonFacing facing) {
            facings.put(cell, facing);
        }

        void entity(Cell cell) {
            entities.add(cell);
        }

        void floor(int y) {
            for (int x = -4; x <= 4; x++) {
                for (int z = -4; z <= 4; z++) put(new Cell(x, y, z), "minecraft:stone");
            }
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
                || "minecraft:bedrock".equals(id) || "minecraft:dirt".equals(id);
        }

        @Override
        public boolean entityFree(Cell cell) {
            return !entities.contains(cell);
        }

        @Override
        public boolean crystalPresent(Cell cell) {
            return crystal != null && crystal.equals(cell);
        }

        @Override
        public boolean pistonPowered(Cell cell) {
            return powered.contains(cell);
        }

        @Override
        public PistonFacing pistonFacing(Cell cell) {
            return facings.get(cell);
        }

        @Override
        public boolean redstoneComponent(Cell cell) {
            String id = blocks.get(cell);
            if (id == null) return false;
            return id.equals("minecraft:piston") || id.equals("minecraft:sticky_piston")
                || id.equals("minecraft:redstone_torch") || id.equals("minecraft:redstone_block")
                || id.equals("minecraft:redstone_wire") || id.equals("minecraft:repeater")
                || id.equals("minecraft:comparator") || id.equals("minecraft:lever")
                || id.equals("minecraft:oak_door") || id.equals("minecraft:observer");
        }
    }
}
