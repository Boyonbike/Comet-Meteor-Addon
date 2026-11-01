/*
 * Original Comet implementation by Boyonabike.
 * Independent: test written against the project's own interfaces; no third-party code.
 * SPDX-License-Identifier: CC0-1.0
 */

package dev.tntcoating.core.combat;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;
import java.util.Set;

import org.junit.jupiter.api.Test;

/**
 * Phase 6.3 table: the pure conflict evidence. Every row must be able to serialise, compatibility only
 * follows positive proof, and the two explosion-region rows use the audited jar rules (a power-6 entity
 * search of twelve blocks and a block ray that pays a per-block travel decay plus the block's own
 * resistance). The trap's source crystal is an entity, so its destruction belongs to the entity row.
 */
final class FootprintConflictTest {
    private static final EntityKey TARGET = ChoiceFixtures.entity(11);
    private static final EntityKey CRYSTAL = ChoiceFixtures.entity(12);
    private static final Cell SOURCE = new Cell(10, 64, 0);
    private static final Cell PISTON = new Cell(9, 64, 0);
    private static final Cell POWER = new Cell(9, 64, 2);
    private static final Cell SUPPORT = new Cell(8, 64, 0);
    private static final Vec FEET = new Vec(10.5, 64.0, 0.5);
    private static final Vec FAR = new Vec(30.5, 70.5, 20.5);
    private static final Box SWEPT = Box.fromCell(SOURCE).expand(1.0);
    private static final CollisionView FLOOR = cell -> cell.y() < 64;
    private static final ExposureBound NO_DAMAGE =
        new ExposureBound(new MitigatedDamage(0.0), Map.of(), new MitigationEnvelope(1L));

    @Test
    void anUnrelatedExplosionIsCompatible() {
        FootprintConflict.Result result =
            FootprintConflict.check(direct(Action.PLACE_CRYSTAL, FAR), trap(), environment());

        assertEquals(FootprintConflict.Verdict.COMPATIBLE, result.verdict(), result.detail());
    }

    @Test
    void aMissingTrapViewConflictsInsteadOfAssumingCompatibility() {
        FootprintConflict.Result result =
            FootprintConflict.check(direct(Action.PLACE_CRYSTAL, FEET), null, environment());

        assertEquals(FootprintConflict.Verdict.CONFLICT, result.verdict());
        assertEquals(FootprintConflict.Row.STRUCTURAL, result.row());
    }

    @Test
    void aMissingEnvironmentConflicts() {
        FootprintConflict.Result result =
            FootprintConflict.check(direct(Action.PLACE_BASE, null), trap(), null);

        assertEquals(FootprintConflict.Verdict.CONFLICT, result.verdict());
    }

    @Test
    void aPowerSixRayThatCouldBreakThePowerCellConflicts() {
        FootprintConflict.Result result = FootprintConflict.check(
            direct(Action.PLACE_CRYSTAL, new Vec(9.5, 64.5, 4.5)), trap(), environment());

        assertEquals(FootprintConflict.Verdict.CONFLICT, result.verdict());
        assertEquals(FootprintConflict.Row.STRUCTURAL, result.row());
    }

    @Test
    void aPowerSixRayThatCouldBreakThePistonCellConflicts() {
        FootprintConflict.Result result = FootprintConflict.check(
            direct(Action.PLACE_CRYSTAL, new Vec(9.5, 64.5, 5.5)), trap(), environment());

        assertEquals(FootprintConflict.Verdict.CONFLICT, result.verdict());
        assertEquals(FootprintConflict.Row.STRUCTURAL, result.row());
    }

    @Test
    void obsidianOnlyCellsStayCompatibleAtPointBlankRange() {
        FootprintConflict.TrapView view = trap(List.of(
            new FootprintConflict.TrapBlock(new Cell(60, 64, 0), FootprintConflict.OBSIDIAN)),
            SOURCE.center(), SWEPT);
        FootprintConflict.Result result = FootprintConflict.check(
            direct(Action.PLACE_CRYSTAL, new Vec(60.5, 64.5, 0.5)), view, environment());

        assertEquals(FootprintConflict.Verdict.COMPATIBLE, result.verdict(), result.detail());
    }

    @Test
    void anUnknownBlastResistanceConflicts() {
        FootprintConflict.TrapView view = trap(
            List.of(new FootprintConflict.TrapBlock(POWER, null)), SOURCE.center(), SWEPT);
        FootprintConflict.Result result = FootprintConflict.check(
            direct(Action.PLACE_CRYSTAL, new Vec(9.5, 64.5, 4.5)), view, environment());

        assertEquals(FootprintConflict.Verdict.CONFLICT, result.verdict());
        assertEquals(FootprintConflict.Row.STRUCTURAL, result.row());
        assertTrue(result.detail().contains("unknown"), result.detail());
    }

    @Test
    void attackingTheBoundTrapCrystalConflicts() {
        FootprintConflict.DirectView direct = new FootprintConflict.DirectView(
            Action.ATTACK_CRYSTAL, FAR, new Footprint(Set.of(), null, null, Set.of(CRYSTAL)),
            false, false);

        FootprintConflict.Result result = FootprintConflict.check(direct, trap(), environment());

        assertEquals(FootprintConflict.Verdict.CONFLICT, result.verdict());
        assertEquals(FootprintConflict.Row.ENTITY, result.row());
    }

    @Test
    void anEntitySearchThatReachesTheTrapCrystalConflicts() {
        FootprintConflict.TrapView view = trap(
            List.of(new FootprintConflict.TrapBlock(new Cell(0, 64, 0), FootprintConflict.OBSIDIAN)),
            SOURCE.center(), SWEPT_FAR);
        FootprintConflict.Result result = FootprintConflict.check(
            direct(Action.PLACE_CRYSTAL, new Vec(10.5, 64.5, 3.0)), view, environment());

        assertEquals(FootprintConflict.Verdict.CONFLICT, result.verdict());
        assertEquals(FootprintConflict.Row.ENTITY, result.row());
        assertTrue(result.detail().contains("without a second explosion"), result.detail());
    }

    @Test
    void anEntitySearchThatReachesTheSweptVolumeConflicts() {
        FootprintConflict.Result result = FootprintConflict.check(
            direct(Action.PLACE_CRYSTAL, new Vec(10.5, 64.5, 13.5)), trap(), environment());

        assertEquals(FootprintConflict.Verdict.CONFLICT, result.verdict());
        assertEquals(FootprintConflict.Row.SWEPT, result.row());
    }

    @Test
    void aVerticalEscapeAtEightToTwelveBlocksConflicts() {
        FootprintConflict.Environment openSky = new FootprintConflict.Environment(false, false, true,
            MotionSample.of(FEET.x(), FEET.y(), FEET.z(), 0.0, 0.0, 0.0, true), CollisionView.open());
        // An explosion 10 blocks below the eye pushes the target up; the trap's priced window is much
        // tighter than one cell, so the reachable set leaving it is a conflict even though the target
        // never leaves the hole sideways. No hole exemption, no feet-level exemption.
        Vec below = new Vec(10.5, 65.62 - 10.0, 0.5);
        FootprintConflict.Result result =
            FootprintConflict.check(direct(Action.PLACE_CRYSTAL, below), syntheticTrap(), openSky);

        assertEquals(FootprintConflict.Verdict.CONFLICT, result.verdict());
        assertEquals(FootprintConflict.Row.TARGET_MOTION, result.row(), result.detail());
        assertTrue(result.detail().contains("leaves the priced target window"), result.detail());
    }

    @Test
    void aMissingMotionSampleConflictsRatherThanAssumingTheTargetStaysPut() {
        FootprintConflict.Environment unknownMotion = new FootprintConflict.Environment(
            false, false, true, null, FLOOR);
        Vec below = new Vec(10.5, 65.62 - 10.0, 0.5);

        FootprintConflict.Result result = FootprintConflict.check(
            direct(Action.PLACE_CRYSTAL, below), syntheticTrap(), unknownMotion);

        assertEquals(FootprintConflict.Verdict.CONFLICT, result.verdict());
        assertEquals(FootprintConflict.Row.TARGET_MOTION, result.row());
        assertTrue(result.detail().contains("motion sample unknown"), result.detail());
    }

    @Test
    void aSameTickSlotNeedSerialisesInsteadOfRejecting() {
        FootprintConflict.Environment held = new FootprintConflict.Environment(
            true, false, true, MotionSample.of(FEET.x(), FEET.y(), FEET.z(), 0, 0, 0, true), FLOOR);
        FootprintConflict.DirectView slot = new FootprintConflict.DirectView(
            Action.PLACE_BASE, null, new Footprint(Set.of(), null, null, Set.of()), true, false);

        FootprintConflict.Result result = FootprintConflict.check(slot, trap(), held);

        assertEquals(FootprintConflict.Verdict.SERIALISE, result.verdict());
        assertEquals(FootprintConflict.Row.RESOURCES, result.row());
    }

    @Test
    void aSameTickRotationNeedSerialises() {
        FootprintConflict.Environment held = new FootprintConflict.Environment(
            false, true, true, MotionSample.of(FEET.x(), FEET.y(), FEET.z(), 0, 0, 0, true), FLOOR);
        FootprintConflict.DirectView rotation = new FootprintConflict.DirectView(
            Action.PLACE_SUPPORT, null, new Footprint(Set.of(), null, null, Set.of()), false, true);

        FootprintConflict.Result result = FootprintConflict.check(rotation, trap(), held);

        assertEquals(FootprintConflict.Verdict.SERIALISE, result.verdict());
        assertEquals(FootprintConflict.Row.RESOURCES, result.row());
    }

    @Test
    void combinedReservationsThatFailTheBudgetConflict() {
        FootprintConflict.Environment overBudget = new FootprintConflict.Environment(
            false, false, false, MotionSample.of(FEET.x(), FEET.y(), FEET.z(), 0, 0, 0, true), FLOOR);
        FootprintConflict.DirectView clean = new FootprintConflict.DirectView(
            Action.PLACE_BASE, null, new Footprint(Set.of(), null, null, Set.of()), false, false);

        FootprintConflict.Result result = FootprintConflict.check(clean, trap(), overBudget);

        assertEquals(FootprintConflict.Verdict.CONFLICT, result.verdict());
        assertEquals(FootprintConflict.Row.RISK, result.row());
    }

    @Test
    void theAuditedReachRulesAreMonotone() {
        assertTrue(FootprintConflict.couldBreak(0.0, 0.0), "a torch at the centre must break");
        assertFalse(FootprintConflict.couldBreak(0.0, FootprintConflict.OBSIDIAN_RESISTANCE),
            "obsidian must stop the ray even at point blank");
        assertTrue(FootprintConflict.couldBreak(5.0, FootprintConflict.REDSTONE_BLOCK_RESISTANCE),
            "a redstone block five blocks away is still inside the audited reach");
        assertFalse(FootprintConflict.couldBreak(11.0, 0.0),
            "the per-step decay makes an eleven-block air path safe");
    }

    // ------------------------------------------------------------------ fixtures

    private static final Vec CRYSTAL_FAR = new Vec(100.5, 64.5, 0.5);
    private static final Box SWEPT_FAR = Box.fromCell(new Cell(100, 64, 0));

    private static FootprintConflict.DirectView direct(Action action, Vec explosion) {
        return new FootprintConflict.DirectView(action, explosion,
            new Footprint(Set.of(), null, null, Set.of()), false, false);
    }

    private static FootprintConflict.TrapView trap() {
        return trap(List.of(
            new FootprintConflict.TrapBlock(SUPPORT, FootprintConflict.OBSIDIAN),
            new FootprintConflict.TrapBlock(PISTON, FootprintConflict.PISTON),
            new FootprintConflict.TrapBlock(POWER, "minecraft:redstone_torch")),
            SOURCE.center(), SWEPT);
    }

    private static FootprintConflict.TrapView syntheticTrap() {
        return trap(List.of(
            new FootprintConflict.TrapBlock(new Cell(60, 64, 0), FootprintConflict.OBSIDIAN)),
            CRYSTAL_FAR, SWEPT_FAR);
    }

    private static FootprintConflict.TrapView trap(List<FootprintConflict.TrapBlock> blocks,
                                                   Vec crystalPosition, Box swept) {
        return new FootprintConflict.TrapView(TARGET, FEET,
            Box.around(FEET, PistonGeometry.POSITION_TOLERANCE), blocks, SOURCE, CRYSTAL,
            crystalPosition, swept, NO_DAMAGE, false, false);
    }

    private static FootprintConflict.Environment environment() {
        return new FootprintConflict.Environment(false, false, true,
            MotionSample.of(FEET.x(), FEET.y(), FEET.z(), 0.0, 0.0, 0.0, true), FLOOR);
    }
}
