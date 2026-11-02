/*
 * Original Comet implementation by Boyonabike.
 * Independent: test harness written against the project's own interfaces; no third-party code.
 * SPDX-License-Identifier: CC0-1.0
 */

package dev.tntcoating.core.combat.mc;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.tntcoating.core.combat.Box;
import dev.tntcoating.core.combat.Capability;
import dev.tntcoating.core.combat.Cell;
import dev.tntcoating.core.combat.CleanupPriority;
import dev.tntcoating.core.combat.Choice;
import dev.tntcoating.core.combat.CombatDamagePolicy;
import dev.tntcoating.core.combat.CombatMetrics;
import dev.tntcoating.core.combat.DirectSettings;
import dev.tntcoating.core.combat.EntityKey;
import dev.tntcoating.core.combat.Footprint;
import dev.tntcoating.core.combat.HazardId;
import dev.tntcoating.core.combat.Intent;
import dev.tntcoating.core.combat.MitigatedDamage;
import dev.tntcoating.core.combat.MitigationEnvelope;
import dev.tntcoating.core.combat.Mode;
import dev.tntcoating.core.combat.Observation;
import dev.tntcoating.core.combat.ObservationKind;
import dev.tntcoating.core.combat.ObservationPayload;
import dev.tntcoating.core.combat.Opportunity;
import dev.tntcoating.core.combat.PistonFacing;
import dev.tntcoating.core.combat.PistonGeometry;
import dev.tntcoating.core.combat.PistonOpportunity;
import dev.tntcoating.core.combat.PistonPlanner;
import dev.tntcoating.core.combat.Strategy;
import dev.tntcoating.core.combat.TargetEstimate;
import dev.tntcoating.core.combat.Vec;
import dev.tntcoating.core.combat.mc.CombatTestFakes.FakeSink;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import org.junit.jupiter.api.Test;

/**
 * Phase 6.1/6.4 serialised Hybrid at engine level: one committed plan, one decision owner, no second
 * send path. The trap's ranking view is offered to this engine and the engine only reports which plan
 * owns the tick; the trap controller keeps owning every trap send, lease, hazard and risk reservation.
 */
final class HybridEngineTest {
    private static final Vec SELF = new Vec(0.5, 64.5, 0.5);
    private static final Cell BASE = new Cell(2, 62, 0);
    private static final Vec TARGET = new Vec(2.5, 63.0, 3.0);
    private static final Vec DANGER = new Vec(2.5, 64.5, 0.5);
    private static final DirectSettings BASE_SETTINGS =
        DirectSettings.conservative(CombatDamagePolicy.conservativeSafety());
    private static final DirectSettings DANGER_SETTINGS = BASE_SETTINGS.withDangerousCrystalBreak(true);

    /**
     * Phase 6.1: a committed trap plan is exempt only for the crystal its own reservation protects, so the
     * trap is not filtered by its own hazard; a foreign plan that would break that same protected crystal
     * is still rejected by the ledger, and every other direct plan is rejected by the commitment.
     */
    @Test
    void committedTrapKeepsItsOwnProtectedCrystalAndBlocksEveryOtherDirectPlan() {
        EngineHarness harness = EngineHarness.create(BASE_SETTINGS, SELF);
        harness.engine.setMode(Mode.HYBRID);
        EntityKey trapCrystal = bindTrapCrystal(harness);
        assertTrue(harness.crystals.protects(trapCrystal.uuid()),
            "the trap reservation must protect the crystal it is about to spawn");
        Opportunity trap = PistonOpportunity.of(trapPlan(trapCrystal, 12.0), new MitigationEnvelope(0L))
            .orElseThrow();
        harness.engine.setCommittedPlan(trap);

        Opportunity attacking = new Opportunity(9001L, Strategy.DIRECT, trapCrystal,
            new Vec(10.5, 64.5, 0.5), new TargetEstimate(10.0, 10.0, 10.0), zeroExposure(), 1, 10.0, 1,
            List.of(), 1.0, new Footprint(Set.of(), Box.fromCell(new Cell(10, 64, 0)),
                Box.fromCell(new Cell(10, 64, 0)), Set.of(trapCrystal)));
        EntityKey elsewhere = new EntityKey(harness.connection, 77, UUID.randomUUID());
        Opportunity other = new Opportunity(9002L, Strategy.DIRECT, elsewhere,
            new Vec(2.5, 64.5, 3.5), new TargetEstimate(10.0, 10.0, 10.0), zeroExposure(), 1, 10.0, 1,
            List.of(), 1.0, new Footprint(Set.of(), Box.fromCell(new Cell(2, 64, 3)),
                Box.fromCell(new Cell(2, 64, 3)), Set.of(elsewhere)));
        harness.engine.offerPlans(List.of(attacking, other));

        harness.tick();

        Choice choice = harness.engine.lastChoice();
        assertEquals(trap.id(), choice.winner().map(Opportunity::id).orElse(-1L),
            () -> "rejected=" + choice.rejected());
        assertTrue(harness.engine.trapSelected());
        assertTrue(rejected(choice, attacking.id(), Choice.Reason.LEDGER),
            "a foreign break of the trap crystal is a ledger conflict, before the commitment check");
        assertTrue(rejected(choice, other.id(), Choice.Reason.COMMITTED),
            "a committed trap serialises every other offensive plan");
        assertEquals(0, harness.sink.count(FakeSink.Kind.PLACE), "the engine never sends the trap");
        assertEquals(0, harness.sink.count(FakeSink.Kind.ATTACK), "the engine never sends the trap");
        assertEquals(0.0, harness.budget.current(harness.self()).value(), 1.0e-9,
            "ranking a trap must not spend risk; the trap owner keeps its own reservation");
    }

    /** Phase 6.5: defence is evaluated and dispatched every tick, including during a committed trap. */
    @Test
    void defenceStillExecutesWhileATrapIsCommitted() {
        EngineHarness harness = EngineHarness.create(DANGER_SETTINGS, SELF);
        CombatMetrics metrics = new CombatMetrics();
        harness.engine.attachMetrics(metrics);
        harness.setSelfHealth(7.0);
        harness.setSelfUpper(1.0);
        harness.setSelfUpperWorst(6.0);
        harness.addTarget(new Vec(2.5, 64.5, 3.0));
        EntityKey danger = observeCrystal(harness, 30, DANGER);
        harness.engine.setMode(Mode.HYBRID);
        Opportunity trap = PistonOpportunity.of(trapPlan(bindTrapCrystal(harness), 12.0),
            new MitigationEnvelope(0L)).orElseThrow();
        harness.engine.setCommittedPlan(trap);

        harness.tick();

        assertEquals(1, harness.attackCount(), "an imminent defensive break still runs during a trap");
        assertEquals(1, metrics.defenceActions("break_crystal"));
        assertEquals(0, harness.sink.count(FakeSink.Kind.PISTON),
            "the trap's own sends stay with the trap controller");
        assertEquals(1, harness.engine.attempts(danger),
            "the defensive break is recorded as an attempt so it is never repeated blindly");
        assertTrue(harness.engine.sequence().isIdle(),
            "a defensive break must not start an offensive placement sequence");
    }

    /** Phase 6.1: with no committed trap the higher utility wins; a weak trap preview loses to direct. */
    @Test
    void aWeakTrapPreviewLosesToADirectPlan() {
        EngineHarness harness = EngineHarness.create(BASE_SETTINGS, SELF);
        harness.setBase(BASE);
        harness.addTarget(TARGET);
        harness.engine.setMode(Mode.HYBRID);
        EntityKey trapCrystal = bindTrapCrystal(harness);
        harness.engine.offerPlans(List.of(PistonOpportunity
            .of(trapPlan(trapCrystal, 4.5), new MitigationEnvelope(0L)).orElseThrow()));

        harness.tick();

        assertFalse(harness.engine.trapSelected(), "a weak trap preview must not claim the tick");
        assertTrue(harness.placeCount() >= 1, "the direct plan keeps its own dispatch path");
    }

    /**
     * Phase 6.1: the target is fully surrounded, so a trap is plausible, but the direct faceplace still
     * exists and ranks higher. Choice is made before any commitment, so direct wins without serialising
     * against a running trap.
     */
    @Test
    void surroundedTargetWhereDirectFaceplaceRanksHigherChoosesDirect() {
        EngineHarness harness = EngineHarness.create(BASE_SETTINGS, SELF);
        harness.setBase(BASE);
        harness.addTarget(TARGET);
        // A sealed one-block tube: four blocked sides at feet and head, plus a ceiling.
        Cell feet = new Cell(2, 63, 3);
        harness.setWall(new Cell(feet.x() + 1, feet.y(), feet.z()));
        harness.setWall(new Cell(feet.x() + 1, feet.y() + 1, feet.z()));
        harness.setWall(new Cell(feet.x() - 1, feet.y(), feet.z()));
        harness.setWall(new Cell(feet.x() - 1, feet.y() + 1, feet.z()));
        harness.setWall(new Cell(feet.x(), feet.y(), feet.z() + 1));
        harness.setWall(new Cell(feet.x(), feet.y() + 1, feet.z() + 1));
        harness.setWall(new Cell(feet.x(), feet.y(), feet.z() - 1));
        harness.setWall(new Cell(feet.x(), feet.y() + 1, feet.z() - 1));
        harness.setWall(new Cell(feet.x(), feet.y() + 1, feet.z()));
        harness.engine.setMode(Mode.HYBRID);
        // Unbound preview entity: this test is about the ranking comparison, not the reservation.
        EntityKey trapCrystal = new EntityKey(harness.connection, 5150, UUID.randomUUID());
        Opportunity trap = PistonOpportunity.of(trapPlan(trapCrystal, 12.0),
            new MitigationEnvelope(0L)).orElseThrow();
        harness.engine.offerPlans(List.of(trap));

        harness.tick();

        assertFalse(harness.engine.trapSelected(), "the lower-utility preview must not claim the tick");
        assertTrue(harness.placeCount() >= 1, "the higher-ranked direct faceplace must execute");
        Choice choice = harness.engine.lastChoice();
        assertTrue(choice.winner().isPresent());
        assertEquals(Strategy.DIRECT, choice.winner().orElseThrow().strategy());
        assertTrue(rejected(choice, trap.id(), Choice.Reason.LOWER_RANK),
            () -> "trap rejections: " + choice.rejected());
    }

    /**
     * Phase 6.5: the wait is measured in server ticks, not wall clock, and a starved cleanup is visible in
     * metrics. The hook only claims the next free slot while the trap can actually reserve its cleanup
     * packet; the trap's own intent origin is covered by {@link PistonTrapControllerTest}.
     */
    @Test
    void cleanupWaitReachesTheStarvationDeadlineInServerTicks() {
        EngineHarness harness = EngineHarness.create(BASE_SETTINGS, SELF);
        harness.setBase(BASE);
        harness.addTarget(TARGET);
        CombatMetrics metrics = new CombatMetrics();
        harness.engine.attachMetrics(metrics);
        harness.engine.setMode(Mode.HYBRID);
        harness.tick();
        harness.engine.cleanupPriority().request(harness.serverTick,
            new CleanupPriority.Deadline(harness.session.connection(),
                harness.session.actionGeneration()), null);

        for (int tick = 0; tick < 19; tick++) harness.tick();
        assertFalse(harness.engine.cleanupPriority().state().due(),
            "19 ticks of waiting are not starvation");
        harness.tick();

        CleanupPriority.State state = harness.engine.cleanupPriority().state();
        assertTrue(state.due(), "cleanup is due after 20 waiting server ticks");
        assertEquals(20, state.waitedTicks());
        assertEquals(20, metrics.cleanupWaitTicks());
        assertEquals(20.0, metrics.cleanupMaxWaitTicks(), 1.0e-9);
        assertTrue(metrics.cleanupStarvedTicks() >= 1, "the overdue tick is recorded as starved");
        assertTrue(harness.engine.cleanupPriority().prioritise(),
            "a due cleanup with a feasible reservation claims the next free slot");
    }

    /**
     * Phase 6.5: the engine must only yield the next free slot when the trap can actually reserve. The
     * trap installs a probe that checks the rolling packet budget and the hotbar slot; when it refuses,
     * prioritise() is false and direct offence runs instead of both actions starving.
     */
    @Test
    void aDueCleanupThatCannotReserveGivesTheTickBackToDirect() {
        EngineHarness harness = EngineHarness.create(BASE_SETTINGS, SELF);
        harness.setBase(BASE);
        harness.addTarget(TARGET);
        harness.engine.setMode(Mode.HYBRID);
        harness.engine.cleanupPriority().request(6,
            new CleanupPriority.Deadline(harness.session.connection(),
                harness.session.actionGeneration()), null);
        harness.engine.cleanupPriority().feasibility(() -> false);
        harness.serverTick = 25;

        harness.tick();

        assertTrue(harness.engine.cleanupPriority().state().due());
        assertFalse(harness.engine.cleanupPriority().prioritise(),
            "the trap cannot reserve a cleanup packet");
        assertTrue(harness.placeCount() >= 1,
            "direct offence gets the tick when cleanup yields back");
    }

    private static boolean rejected(Choice choice, long id, Choice.Reason reason) {
        return choice.rejected().stream()
            .anyMatch(rejection -> rejection.opportunityId() == id && rejection.reason() == reason);
    }

    private static dev.tntcoating.core.combat.ExposureBound zeroExposure() {
        return new dev.tntcoating.core.combat.ExposureBound(new MitigatedDamage(0.0), Map.of(),
            new MitigationEnvelope(0L));
    }

    /** Reserve and bind a crystal the way the trap owner does, without running the trap itself. */
    private static EntityKey bindTrapCrystal(EngineHarness harness) {
        Cell source = new Cell(10, 64, 0);
        HazardId hazard = harness.crystals.reserveSpawn(harness.connection, Box.fromCell(source),
            Box.fromCell(source).expand(1.0), Set.of());
        harness.crystals.markSent(hazard, harness.serverTick);
        EntityKey crystal = new EntityKey(harness.connection, 5150, UUID.randomUUID());
        harness.observe(new Observation(harness.connection, harness.receipt++, harness.revision++,
            System.nanoTime(), harness.serverTick, ObservationKind.SPAWN, crystal, null,
            new Vec(10.5, 64.5, 0.5), Box.fromCell(source),
            new ObservationPayload.Spawn(EngineHarness.END_CRYSTAL)));
        return crystal;
    }

    /** A ranking-only trap plan: one serialised intent, a horizontal north push, established damage. */
    private static PistonPlanner.Plan trapPlan(EntityKey target, double targetDamage) {
        Cell source = new Cell(10, 64, 0);
        Cell piston = new Cell(9, 64, 0);
        PistonFacing facing = new PistonFacing(0, 0, 1);
        PistonGeometry.PistonPush push = new PistonGeometry.PistonPush(
            new PistonGeometry.Layout(facing, 0), source, piston, new Cell(9, 64, 1),
            new Cell(9, 64, 2), PistonGeometry.PowerKind.REDSTONE_TORCH,
            Box.fromCell(new Cell(9, 64, 2)).expand(2.0), new Vec(10.5, 64.5, 1.5));
        Intent intent = new Intent(11L, 7001L, Strategy.PISTON, dev.tntcoating.core.combat.Action.PLACE_CRYSTAL,
            source, null, Set.of(), Set.of(), Set.of(), 500);
        return new PistonPlanner.Plan(7001L, target, push, List.of(intent), List.of(piston),
            targetDamage, 0.0, 0.0, Map.of(), new Vec(10.5, 64.5, 1.5));
    }

    private static EntityKey observeCrystal(EngineHarness harness, int id, Vec position) {
        UUID uuid = UUID.randomUUID();
        EntityKey key = new EntityKey(harness.connection, id, uuid);
        harness.client.putCrystal(id, uuid, position);
        harness.observe(new Observation(harness.connection, harness.receipt++, harness.revision++,
            System.nanoTime(), harness.serverTick, ObservationKind.SPAWN, key, null, position,
            Box.fromCell(EngineHarness.cellOf(position)),
            new ObservationPayload.Spawn(EngineHarness.END_CRYSTAL)));
        return key;
    }

    /** A paused pass must not leave the previous tick's trap selection standing for the controller. */
    @Test
    void aPausedTickDropsThePreviousTrapSelection() {
        EngineHarness harness = EngineHarness.create(BASE_SETTINGS, SELF);
        harness.engine.setMode(Mode.HYBRID);
        Opportunity trap = PistonOpportunity.of(trapPlan(bindTrapCrystal(harness), 12.0),
            new MitigationEnvelope(0L)).orElseThrow();
        harness.engine.setCommittedPlan(trap);
        harness.tick();
        assertTrue(harness.engine.trapSelected());

        harness.world.eating = true;
        harness.tick();

        assertFalse(harness.engine.trapSelected(), "eating pauses the trap hand-off too");
    }
}
