/*
 * Original Comet implementation by Boyonabike.
 * Independent: test harness written against the project's own interfaces; no third-party code.
 * SPDX-License-Identifier: CC0-1.0
 */

package dev.tntcoating.core.combat.mc;

import dev.tntcoating.core.combat.Action;
import dev.tntcoating.core.combat.Box;
import dev.tntcoating.core.combat.Capability;
import dev.tntcoating.core.combat.Cell;
import dev.tntcoating.core.combat.CombatDamagePolicy;
import dev.tntcoating.core.combat.CombatEngine;
import dev.tntcoating.core.combat.CombatRiskBudget;
import dev.tntcoating.core.combat.CombatScheduler;
import dev.tntcoating.core.combat.CombatSession;
import dev.tntcoating.core.combat.ConnectionKey;
import dev.tntcoating.core.combat.CrystalLedger;
import dev.tntcoating.core.combat.DirectSettings;
import dev.tntcoating.core.combat.EffectiveHealth;
import dev.tntcoating.core.combat.EntityKey;
import dev.tntcoating.core.combat.ExposureBound;
import dev.tntcoating.core.combat.Footprint;
import dev.tntcoating.core.combat.Intent;
import dev.tntcoating.core.combat.MitigatedDamage;
import dev.tntcoating.core.combat.MitigationEnvelope;
import dev.tntcoating.core.combat.Observation;
import dev.tntcoating.core.combat.PacketLedger;
import dev.tntcoating.core.combat.RiskRequest;
import dev.tntcoating.core.combat.Safety;
import dev.tntcoating.core.combat.SafetyState;
import dev.tntcoating.core.combat.ServerTickInterval;
import dev.tntcoating.core.combat.Strategy;
import dev.tntcoating.core.combat.TargetEstimate;
import dev.tntcoating.core.combat.Ticket;
import dev.tntcoating.core.combat.Vec;
import dev.tntcoating.core.combat.mc.CombatTestFakes.FakeClient;
import dev.tntcoating.core.combat.mc.CombatTestFakes.FakeSink;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/** Minimal real runtime plus scripted client/sink for dispatch-level tests. */
final class CombatRuntimeFixture {
    static final long PLAN = CombatEngine.DIRECT_PLAN;

    final DirectSettings settings =
        DirectSettings.conservative(CombatDamagePolicy.conservativeSafety());
    final Safety safety = settings.safety();
    final CombatSession session = new CombatSession();
    final ConnectionKey connection;
    final FakeClient client;
    final FakeSink sink = new FakeSink();
    final CrystalLedger ledger = new CrystalLedger();
    final CombatRiskBudget risk = new CombatRiskBudget((envelope, current) -> current);
    final PacketLedger packets = PacketLedger.conservative();
    final CombatScheduler scheduler = new CombatScheduler(session, risk, packets);
    final ObservationIntake intake;
    final CombatInventoryAdapter inventory;
    final CombatRuntime runtime;
    final Ticket ticket;
    // Names used by the ported 26.2 fixture consumers; the target fixture's own names are above.
    final CrystalLedger crystals;
    final CombatRiskBudget budget;
    final CombatInventoryAdapter adapter;
    int serverTick;
    private long nextIntentId = 100;
    private TargetEstimate estimateOverride;

    static final Cell CELL = new Cell(0, 64, 0);
    static final Vec CRYSTAL_POSITION = new Vec(0.5, 64.0, 0.5);

    CombatRuntimeFixture() {
        session.open();
        connection = session.connection();
        client = new FakeClient(connection);
        client.putSelf(1, UUID.fromString("00000000-0000-0000-0000-000000000001"));
        client.eye = new Vec(0.5, 65.62, 0.5);
        client.blockRange = 4.5;
        client.entityRange = 3.0;
        client.hotbar[0] = EngineHarness.END_CRYSTAL;
        client.hotbar[1] = EngineHarness.OBSIDIAN;
        client.hotbar[2] = "minecraft:diamond_sword";
        intake = new ObservationIntake(session, client, new EntityDataIds(9, 10), 64);
        inventory = new CombatInventoryAdapter(client, new CombatInventoryAdapter.Ownership() {
            @Override
            public boolean loanActive() {
                return false;
            }

            @Override
            public boolean weaponAttackActive() {
                return false;
            }
        });
        runtime = new CombatRuntime(session, scheduler, risk, intake, inventory, client, sink, safety);
        ticket = session.ticket(PLAN);
        crystals = ledger;
        budget = risk;
        adapter = inventory;
        runtime.tick(0);
    }

    ExposureBound zeroBound() {
        return new ExposureBound(new MitigatedDamage(0.0), Map.of(), new MitigationEnvelope(0L));
    }

    SafetyState safetyState() {
        return new SafetyState(connection, session.actionGeneration(), client.self,
            new EffectiveHealth(20.0, 0.0, Capability.KNOWN, 0L), Map.of(), safety);
    }

    CombatRuntime.PlanContext context(Action action, Cell cell, EntityKey entity) {
        return new CombatRuntime.PlanContext(safetyState(), zeroBound(),
            new TargetEstimate(4.0, 10.0, 12.0), new EffectiveHealth(20.0, 0.0, Capability.KNOWN, 0L),
            true, 0, EngineHarness.END_CRYSTAL, client.selectedSlot, client.offhandItem(),
            false, false, false, null, action == Action.PLACE_SUPPORT || action == Action.PLACE_BLOCKER
                || action == Action.PLACE_PISTON || action == Action.PLACE_POWER, null);
    }

    Intent intent(long id, Action action, Cell cell, EntityKey entity) {
        return new Intent(id, PLAN, Strategy.DIRECT, action, cell, entity, Set.of(), Set.of(), Set.of(),
            serverTick + 20);
    }

    boolean reserve(Intent intent, CombatRuntime.PlanContext context) {
        Box blast = context.safetyState().self() == null
            ? new Box(new Vec(0, 0, 0), new Vec(1, 1, 1))
            : Box.fromCell(intent.cell() == null
                ? new Cell(0, 64, 0)
                : intent.cell());
        RiskRequest request = new RiskRequest(context.exposure(),
            new ServerTickInterval(serverTick + 1, serverTick + 2), context.safetyState(), null, null,
            2, serverTick, false, false, false,
            new Footprint(Set.of(), blast, blast, Set.of()));
        return scheduler.reserve(intent, ticket, request);
    }

    CombatRuntime.DispatchResult dispatch(Intent intent, CombatRuntime.PlanContext context) {
        runtime.plan(intent, context);
        return runtime.dispatch(intent, ticket);
    }

    EffectiveHealth healthySelf() {
        return new EffectiveHealth(20.0, 0.0, Capability.KNOWN, 0L);
    }

    TargetEstimate estimate() {
        return estimateOverride != null ? estimateOverride : new TargetEstimate(4.0, 10.0, 12.0);
    }

    void setEstimateForTest(TargetEstimate value) {
        estimateOverride = value;
    }

    EntityKey self() {
        return client.self;
    }

    CombatRuntime.PlanContext exposure() {
        return zeroBoundContext(Action.PLACE_CRYSTAL);
    }

    private CombatRuntime.PlanContext zeroBoundContext(Action action) {
        return new CombatRuntime.PlanContext(safetyState(), zeroBound(), estimate(), healthySelf(),
            true, 0, EngineHarness.END_CRYSTAL, client.selectedSlot, client.offhandItem(),
            false, false, false, null, action == Action.PLACE_SUPPORT || action == Action.PLACE_BLOCKER
                || action == Action.PLACE_PISTON || action == Action.PLACE_POWER, null);
    }

    long freshId() {
        return nextIntentId++;
    }

    Intent place() {
        return intent(freshId(), Action.PLACE_CRYSTAL, CELL, null);
    }

    Intent placeAt(Cell cell) {
        return intent(freshId(), Action.PLACE_CRYSTAL, cell, null);
    }

    boolean reserve(Intent intent) {
        return reserve(intent, context(intent.action(), intent.cell(), intent.entity()));
    }

    EntityKey crystal() {
        return crystal(CRYSTAL_POSITION);
    }

    Intent support() {
        Intent intent = intent(freshId(), Action.PLACE_SUPPORT, CELL, null);
        return intent;
    }

    Intent attack() {
        EntityKey key = crystal(CRYSTAL_POSITION);
        return intent(freshId(), Action.ATTACK_CRYSTAL, null, key);
    }

    CombatRuntime.DispatchResult dispatchPlace() {
        Intent intent = place();
        reserve(intent, exposure());
        return dispatch(intent, context(Action.PLACE_CRYSTAL, CELL, null));
    }

    EntityKey crystal(Vec position) {
        int id = 500 + client.crystals.size();
        client.putCrystal(id, UUID.randomUUID(), position);
        return client.entity(id);
    }

    void tick() {
        serverTick++;
        runtime.tick(serverTick);
    }

    List<Observation> drain() {
        return runtime.drain();
    }
}
