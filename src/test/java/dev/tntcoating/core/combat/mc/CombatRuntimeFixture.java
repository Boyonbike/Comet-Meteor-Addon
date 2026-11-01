/*
 * Original Comet implementation by Boyonabike.
 * Independent: test scaffolding written against the project's own interfaces; no third-party code.
 * SPDX-License-Identifier: CC0-1.0
 */

package dev.tntcoating.core.combat.mc;

import dev.tntcoating.core.combat.Action;
import dev.tntcoating.core.combat.Capability;
import dev.tntcoating.core.combat.Cell;
import dev.tntcoating.core.combat.CombatDamagePolicy;
import dev.tntcoating.core.combat.CombatRiskBudget;
import dev.tntcoating.core.combat.CombatScheduler;
import dev.tntcoating.core.combat.CombatSession;
import dev.tntcoating.core.combat.EffectiveHealth;
import dev.tntcoating.core.combat.EntityKey;
import dev.tntcoating.core.combat.ExposureBound;
import dev.tntcoating.core.combat.Intent;
import dev.tntcoating.core.combat.MitigatedDamage;
import dev.tntcoating.core.combat.MitigationEnvelope;
import dev.tntcoating.core.combat.PacketLedger;
import dev.tntcoating.core.combat.RiskId;
import dev.tntcoating.core.combat.RiskRequest;
import dev.tntcoating.core.combat.Safety;
import dev.tntcoating.core.combat.SafetyState;
import dev.tntcoating.core.combat.ServerTickInterval;
import dev.tntcoating.core.combat.Strategy;
import dev.tntcoating.core.combat.TargetEstimate;
import dev.tntcoating.core.combat.Ticket;
import dev.tntcoating.core.combat.Vec;
import dev.tntcoating.core.combat.mc.CombatRuntime.PlanContext;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/** Shared runtime/dispatch/lifecycle fixture with fakes and a conservative safety profile. */
final class CombatRuntimeFixture {
    static final long PLAN = 1L;
    static final Vec CRYSTAL_POSITION = new Vec(0.5, 64.5, 0.5);
    static final Cell CELL = new Cell(0, 64, 0);

    final UUID selfUuid = UUID.nameUUIDFromBytes("self".getBytes());
    final UUID crystalUuid = UUID.nameUUIDFromBytes("crystal".getBytes());
    final Safety safety;
    final CombatSession session = new CombatSession();
    final CombatTestFakes.FakeSink sink = new CombatTestFakes.FakeSink();
    final CombatTestFakes.FakeClient client;
    final ObservationIntake intake;
    final PacketLedger ledger;
    final dev.tntcoating.core.combat.CrystalLedger crystals;
    final CombatScheduler scheduler;
    final CombatRiskBudget budget;
    final CombatInventoryAdapter adapter;
    final CombatRuntime runtime;
    final Ticket ticket;

    private long nextIntentId = 1;

    CombatRuntimeFixture() {
        this(40, false);
    }

    CombatRuntimeFixture(int ledgerCapacity, boolean ownedElsewhere) {
        this(ledgerCapacity, ownedElsewhere, CombatDamagePolicy.conservativeSafety());
    }

    CombatRuntimeFixture(int ledgerCapacity, boolean ownedElsewhere, Safety safety) {
        this.safety = safety;
        session.open();
        ticket = session.ticket(PLAN);
        client = new CombatTestFakes.FakeClient(ticket.conn());
        client.putSelf(1, selfUuid);
        client.putCrystal(3, crystalUuid, CRYSTAL_POSITION);
        client.selectedSlot = 0;
        client.hotbar[0] = "minecraft:end_crystal";
        client.hotbar[2] = "minecraft:end_crystal";
        client.offhand = "minecraft:totem_of_undying";
        intake = new ObservationIntake(session, client, new EntityDataIds(9, 10), 64);
        ledger = new PacketLedger(ledgerCapacity, 20);
        crystals = new dev.tntcoating.core.combat.CrystalLedger(ledgerCapacity);
        budget = new CombatRiskBudget((envelope, current) -> current);
        scheduler = new CombatScheduler(session, budget, ledger);
        adapter = new CombatInventoryAdapter(client, ownership(ownedElsewhere));
        runtime = new CombatRuntime(session, scheduler, budget, intake, adapter, client, sink, safety);
        runtime.tick(0);
    }

    private static CombatInventoryAdapter.Ownership ownership(boolean ownedElsewhere) {
        return new CombatInventoryAdapter.Ownership() {
            @Override
            public boolean loanActive() {
                return ownedElsewhere;
            }

            @Override
            public boolean weaponAttackActive() {
                return false;
            }
        };
    }

    long freshId() {
        return nextIntentId++;
    }

    EntityKey self() {
        return new EntityKey(ticket.conn(), 1, selfUuid);
    }

    EntityKey crystal() {
        return new EntityKey(ticket.conn(), 3, crystalUuid);
    }

    Intent place() {
        return place(freshId());
    }

    Intent place(long id) {
        return new Intent(id, PLAN, Strategy.DIRECT, Action.PLACE_CRYSTAL, CELL, null,
            Set.of(CELL), Set.of(), Set.of(), 20);
    }

    Intent placeAt(Cell cell) {
        return new Intent(freshId(), PLAN, Strategy.DIRECT, Action.PLACE_CRYSTAL, cell, null,
            Set.of(cell), Set.of(), Set.of(), 20);
    }

    Intent attack() {
        return attack(freshId());
    }

    Intent attack(long id) {
        return new Intent(id, PLAN, Strategy.DIRECT, Action.ATTACK_CRYSTAL, null, crystal(),
            Set.of(), Set.of(crystal()), Set.of(), 20);
    }

    EffectiveHealth healthySelf() {
        return new EffectiveHealth(20.0, 0.0, Capability.KNOWN, 0L);
    }

    SafetyState state() {
        return state(healthySelf());
    }

    SafetyState state(EffectiveHealth selfHealth) {
        return new SafetyState(ticket.conn(), ticket.gen(), self(), selfHealth, Map.of(), safety);
    }

    ExposureBound exposure() {
        return exposure(6.0);
    }

    ExposureBound exposure(double selfUpper) {
        return new ExposureBound(new MitigatedDamage(selfUpper), Map.of(),
            new MitigationEnvelope(0L));
    }

    TargetEstimate estimate() {
        return new TargetEstimate(4.0, 6.0, 8.0);
    }

    PlanContext context() {
        return context(state(), exposure(), estimate());
    }

    PlanContext context(SafetyState state, ExposureBound exposure, TargetEstimate estimate) {
        return new PlanContext(state, exposure, estimate, healthySelf(), false,
            client.selectedSlot, client.hotbar[client.selectedSlot], client.selectedSlot,
            client.offhand, false);
    }

    PlanContext rotationContext() {
        return new PlanContext(state(), exposure(), estimate(), healthySelf(), true,
            client.selectedSlot, client.hotbar[client.selectedSlot], client.selectedSlot,
            client.offhand, false);
    }

    PlanContext switchContext(int expectedSlot) {
        return new PlanContext(state(), exposure(), estimate(), healthySelf(), false,
            expectedSlot, client.hotbar[expectedSlot], client.selectedSlot, client.offhand, false);
    }

    /** The same runtime stack a production controller would own, built from the fakes. */
    CrystalCombatController.Stack controllerStack() {
        return new CrystalCombatController.Stack(runtime, session, crystals, scheduler, budget, client,
            intake, null, new dev.tntcoating.core.combat.CombatHurtModel());
    }

    /** Reserves through the scheduler and registers the matching revalidation context. */
    RiskId reserve(Intent intent) {
        return reserve(intent, context());
    }

    RiskId reserve(Intent intent, PlanContext context) {
        boolean reserved = scheduler.reserve(intent, ticket, new RiskRequest(exposure(),
            new ServerTickInterval(10, 12), state(), crystal(), CRYSTAL_POSITION, 2, 0,
            true, false, false));
        if (!reserved) throw new IllegalStateException("fixture reservation failed");
        runtime.plan(intent, context);
        return scheduler.riskId(intent.plan(), intent.id()).orElseThrow();
    }
}
