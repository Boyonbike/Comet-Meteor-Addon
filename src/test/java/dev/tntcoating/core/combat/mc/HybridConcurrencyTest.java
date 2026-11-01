/*
 * Original Comet implementation by Boyonabike.
 * Independent: test harness written against the project's own interfaces; no third-party code.
 * SPDX-License-Identifier: CC0-1.0
 */

package dev.tntcoating.core.combat.mc;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.tntcoating.core.combat.Box;
import dev.tntcoating.core.combat.Capability;
import dev.tntcoating.core.combat.Cell;
import dev.tntcoating.core.combat.Choice;
import dev.tntcoating.core.combat.CombatDamagePolicy;
import dev.tntcoating.core.combat.CombatMetrics;
import dev.tntcoating.core.combat.DirectSettings;
import dev.tntcoating.core.combat.EffectiveHealth;
import dev.tntcoating.core.combat.EntityKey;
import dev.tntcoating.core.combat.ExposureBound;
import dev.tntcoating.core.combat.Footprint;
import dev.tntcoating.core.combat.FootprintConflict;
import dev.tntcoating.core.combat.Intent;
import dev.tntcoating.core.combat.MitigatedDamage;
import dev.tntcoating.core.combat.MitigationEnvelope;
import dev.tntcoating.core.combat.Mode;
import dev.tntcoating.core.combat.Opportunity;
import dev.tntcoating.core.combat.PistonFacing;
import dev.tntcoating.core.combat.PistonGeometry;
import dev.tntcoating.core.combat.PistonPlanner;
import dev.tntcoating.core.combat.SafetyState;
import dev.tntcoating.core.combat.ServerTickInterval;
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
 * Phase 6.4 stage 2: footprint-scoped concurrency, shipped off. With the setting off, a live trap
 * serialises every offensive direct plan exactly as the shipped serialised Hybrid does. With it on, a
 * direct plan is admitted only through a positive {@link FootprintConflict} proof; an active trap whose
 * footprint or ranking view is unavailable still blocks it, and defence keeps its own path either way.
 */
final class HybridConcurrencyTest {
    private static final Vec SELF = new Vec(0.5, 64.5, 0.5);
    private static final Cell BASE = new Cell(2, 62, 0);
    private static final Vec TARGET = new Vec(2.5, 63.0, 3.0);
    /** Far enough from the direct plan that every 6.3 row misses it. */
    private static final Cell FAR_SOURCE = new Cell(40, 64, 0);
    /** The trap crystal is one cell from the direct plan's target: every explosion row reaches it. */
    private static final Cell NEAR_SOURCE = new Cell(2, 64, 2);
    private static final DirectSettings OFF =
        DirectSettings.conservative(CombatDamagePolicy.conservativeSafety());
    private static final DirectSettings ON = OFF.withFootprintConcurrency(true);

    @Test
    void offTheSettingSerialisesExactlyLikeShippedHybrid() {
        EngineHarness harness = EngineHarness.create(OFF, SELF);
        CombatMetrics metrics = new CombatMetrics();
        harness.engine.attachMetrics(metrics);
        harness.setBase(BASE);
        harness.addTarget(TARGET);
        harness.engine.setMode(Mode.HYBRID);
        harness.engine.setActiveTrap(trapView(harness, FAR_SOURCE));

        harness.tick();

        assertEquals(0, harness.placeCount(), "off means the serialised wait, never a concurrent send");
        assertEquals(0, harness.attackCount());
        assertTrue(rejectedDirect(harness.engine.lastChoice(), Choice.Reason.COMMITTED),
            () -> "rejected=" + harness.engine.lastChoice().rejected());
        assertEquals(1, metrics.concurrencySerialised("setting"),
            "the off path records why the direct plan waited");
        assertEquals(0, metrics.concurrencyAdmitted("compatible"));
    }

    @Test
    void onAndACompatibleFootprintAdmitsTheDirectPlan() {
        EngineHarness harness = EngineHarness.create(ON, SELF);
        CombatMetrics metrics = new CombatMetrics();
        harness.engine.attachMetrics(metrics);
        harness.setBase(BASE);
        harness.addTarget(TARGET);
        harness.engine.setMode(Mode.HYBRID);
        harness.engine.setActiveTrap(trapView(harness, FAR_SOURCE));

        harness.tick();

        assertTrue(harness.placeCount() >= 1, "a positive conflict proof admits the direct plan");
        assertEquals(1, metrics.concurrencyAdmitted("compatible"));
        assertEquals(0, metrics.concurrencySerialised("setting"),
            "the setting bucket is reserved for the off path");
    }

    @Test
    void onAndAConflictingFootprintSerialisesTheDirectPlan() {
        EngineHarness harness = EngineHarness.create(ON, SELF);
        CombatMetrics metrics = new CombatMetrics();
        harness.engine.attachMetrics(metrics);
        harness.setBase(BASE);
        harness.addTarget(TARGET);
        harness.engine.setMode(Mode.HYBRID);
        harness.engine.setActiveTrap(trapView(harness, NEAR_SOURCE));

        harness.tick();

        assertEquals(0, harness.placeCount(), "the conflict rejects the direct plan before dispatch");
        assertEquals(0, harness.attackCount());
        assertTrue(rejectedDirect(harness.engine.lastChoice(), Choice.Reason.COMMITTED),
            () -> "rejected=" + harness.engine.lastChoice().rejected());
        assertEquals(0, metrics.concurrencyAdmitted("compatible"));
        assertTrue(metrics.concurrencySerialisedByRow().values().stream().mapToLong(Long::longValue).sum() == 1,
            () -> "serialised=" + metrics.concurrencySerialisedByRow());
    }

    /**
     * The active-trap sentinel: an active trap with no usable footprint is never compatible. A missing
     * view is a conflict; only a positive proof can admit. The second tick proves the discrimination is
     * the view, not a general prohibition.
     */
    @Test
    void anActiveTrapWithoutAFootprintStillBlocksDirectAndANullCommittedPlanNeverAdmits() {
        EngineHarness harness = EngineHarness.create(ON, SELF);
        CombatMetrics metrics = new CombatMetrics();
        harness.engine.attachMetrics(metrics);
        harness.setBase(BASE);
        harness.addTarget(TARGET);
        harness.engine.setMode(Mode.HYBRID);
        harness.engine.setActiveTrap(null);

        harness.tick();

        assertEquals(0, harness.placeCount(), "no footprint evidence, no concurrency");
        assertTrue(rejectedDirect(harness.engine.lastChoice(), Choice.Reason.COMMITTED));
        assertEquals(1, metrics.concurrencySerialised(FootprintConflict.Row.STRUCTURAL.name()));

        harness.engine.setActiveTrap(trapView(harness, FAR_SOURCE));
        harness.tick();

        assertTrue(harness.placeCount() >= 1, "the same engine admits once the positive proof exists");
        assertEquals(1, metrics.concurrencyAdmitted("compatible"));
    }

    /**
     * The P6.1 gap, discriminating: the damage policy sees only the per-request friend bound, so a
     * 1.5-damage request passes both the policy and the old riskAllows. The budget already carries 1.0
     * friend damage, so the cumulative friend bound (2.5) breaks maxFriend (2.0): the hard filter must
     * reject for RISK even though the policy passed.
     */
    @Test
    void theHybridRiskFilterEnforcesThePerFriendCeilingAgainstExistingDebt() {
        EngineHarness harness = EngineHarness.create(OFF, SELF);
        harness.engine.setMode(Mode.HYBRID);
        EntityKey friend = harness.addFriend(new Vec(5.5, 64.5, 0.5), 20.0);
        SafetyState state = new SafetyState(harness.connection, harness.session.actionGeneration(),
            harness.self(), new EffectiveHealth(20.0, 0.0, Capability.KNOWN, 0L),
            Map.of(friend, new EffectiveHealth(20.0, 0.0, Capability.KNOWN, 0L)), harness.safety);
        assertTrue(harness.budget.reserve(1L, exposure(0.0, Map.of(friend, 1.0)),
            new ServerTickInterval(0, 3), state).isPresent());
        assertTrue(harness.budget.current(friend).value() > 0.0);

        Opportunity greedy = new Opportunity(9500L, Strategy.PISTON,
            new EntityKey(harness.connection, 501, UUID.randomUUID()), new Vec(5.5, 64.5, 3.0),
            new TargetEstimate(10.0, 10.0, 10.0), exposure(1.0, Map.of(friend, 1.5)), 1, 10.0, 1,
            List.of(), 1.0, new Footprint(Set.of(), Box.fromCell(new Cell(5, 64, 3)),
                Box.fromCell(new Cell(5, 64, 3)), Set.of()));
        harness.engine.offerPlans(List.of(greedy));

        harness.tick();

        Choice choice = harness.engine.lastChoice();
        assertTrue(choice.rejected().stream().anyMatch(rejection ->
                rejection.opportunityId() == greedy.id() && rejection.reason() == Choice.Reason.RISK),
            () -> "a per-request policy pass must not bypass the friend cumulative ceiling: "
                + choice.rejected());
        assertEquals(0, harness.placeCount());
    }

    private static boolean rejectedDirect(Choice choice, Choice.Reason reason) {
        return choice.rejected().stream()
            .anyMatch(rejection -> rejection.strategy() == Strategy.DIRECT && rejection.reason() == reason);
    }

    private static ExposureBound exposure(double selfUpper, Map<EntityKey, Double> friends) {
        Map<EntityKey, MitigatedDamage> bounds = new java.util.LinkedHashMap<>();
        for (Map.Entry<EntityKey, Double> entry : friends.entrySet()) {
            bounds.put(entry.getKey(), new MitigatedDamage(entry.getValue()));
        }
        return new ExposureBound(new MitigatedDamage(selfUpper), bounds, new MitigationEnvelope(0L));
    }

    /** A trap footprint at {@code source}; the target feet sit in the source cell, as the trap plans it. */
    private static FootprintConflict.TrapView trapView(EngineHarness harness, Cell source) {
        EntityKey target = new EntityKey(harness.connection, 500,
            UUID.nameUUIDFromBytes("trap-target".getBytes()));
        PistonPlanner.Plan plan = trapPlan(target, source);
        EntityKey crystal = new EntityKey(harness.connection, 501,
            UUID.nameUUIDFromBytes("trap-crystal".getBytes()));
        Vec feet = new Vec(source.x() + 0.5, source.y(), source.z() + 0.5);
        return FootprintConflict.trapView(plan, crystal, source.center(), feet,
            exposure(0.0, Map.of()), false, false).orElseThrow();
    }

    private static PistonPlanner.Plan trapPlan(EntityKey target, Cell source) {
        Cell piston = new Cell(source.x() - 1, source.y(), source.z());
        PistonFacing facing = new PistonFacing(0, 0, 1);
        PistonGeometry.PistonPush push = new PistonGeometry.PistonPush(
            new PistonGeometry.Layout(facing, 0), source, piston,
            new Cell(piston.x(), piston.y(), piston.z() + 1),
            new Cell(piston.x(), piston.y(), piston.z() + 2),
            PistonGeometry.PowerKind.REDSTONE_TORCH,
            Box.fromCell(new Cell(piston.x(), piston.y(), piston.z() + 2)).expand(2.0),
            new Vec(source.x() + 0.5, source.y() + 0.5, source.z() + 1.5));
        Intent intent = new Intent(11L, 7001L, Strategy.PISTON,
            dev.tntcoating.core.combat.Action.PLACE_CRYSTAL, source, null, Set.of(), Set.of(),
            Set.of(), 500);
        return new PistonPlanner.Plan(7001L, target, push, List.of(intent), List.of(piston),
            12.0, 0.0, 0.0, Map.of(), new Vec(source.x() + 0.5, source.y() + 0.5, source.z() + 1.5));
    }
}
