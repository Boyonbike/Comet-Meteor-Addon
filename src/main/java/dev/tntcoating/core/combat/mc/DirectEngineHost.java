/*
 * Original Comet implementation by Boyonabike.
 * Independent: written from the pinned 26.1.2 artifact behaviour and the project's own Phase 8
 * contracts; no third-party code.
 * SPDX-License-Identifier: CC0-1.0
 */

package dev.tntcoating.core.combat.mc;

import dev.tntcoating.core.combat.Cell;
import dev.tntcoating.core.combat.CombatEngine;
import dev.tntcoating.core.combat.DirectGateway;
import dev.tntcoating.core.combat.DirectPause;
import dev.tntcoating.core.combat.DirectSequence;
import dev.tntcoating.core.combat.DirectSettings;
import dev.tntcoating.core.combat.DirectWorld;
import dev.tntcoating.core.combat.EntityKey;
import dev.tntcoating.core.combat.Observation;
import dev.tntcoating.core.combat.TickBudget;
import java.util.Objects;

/**
 * Client-thread glue between the pure {@link CombatEngine} and the production runtime. It reads one world
 * snapshot, hands the post-drain runtime snapshot to the engine and lets {@link CombatRuntime#dispatch} do
 * the only possible send. It never drains intake, never observes the ledger twice and never sends itself.
 */
public final class DirectEngineHost {
    private final CombatRuntime runtime;
    private final DirectWorldSource reader;
    private final CombatEngine engine;
    private final DirectGateway gateway;

    public DirectEngineHost(CombatRuntime runtime, DirectWorldSource reader, CombatEngine engine,
                            DirectGateway gateway) {
        this.runtime = Objects.requireNonNull(runtime, "runtime");
        this.reader = Objects.requireNonNull(reader, "reader");
        this.engine = Objects.requireNonNull(engine, "engine");
        this.gateway = Objects.requireNonNull(gateway, "gateway");
    }

    /** One active client tick. Called only after {@link CombatRuntime#tick} applied this tick's receipts. */
    public void tick(int serverTick) {
        tick(serverTick, TickBudget.unlimited());
    }

    /**
     * Budgeted pass: the shared tick budget covers snapshot capture and every planner/search pass. The
     * runtime tick (queue drain, revision stamping, reconciliation) already ran and is never skipped here.
     */
    public void tick(int serverTick, TickBudget budget) {
        if (engine.disabled()) return;
        DirectWorld world = reader.read();
        if (world == null) return;
        engine.tick(serverTick, world, runtime.capture(), gateway, budget);
    }

    /**
     * Meteor after-application spawn path: binds the unique spawn and proposes the break inside the same
     * client-thread task, so the module does not wait a whole tick for a fresh spawn. This mandatory path is
     * unbudgeted; the shared optional-work budget only covers planning in the normal tick.
     */
    public void onSpawnObserved(EntityKey crystal) {
        onSpawnObserved(crystal, TickBudget.unlimited());
    }

    public void onSpawnObserved(EntityKey crystal, TickBudget budget) {
        if (engine.disabled() || crystal == null) return;
        DirectWorld world = reader.read();
        if (world == null) return;
        engine.onSpawnObserved(crystal, world, runtime.capture(), gateway, budget);
    }

    /** Applied-observation invalidation tap; only the affected candidate families are invalidated. */
    public void onObservationApplied(Observation observation) {
        engine.onObservationApplied(observation);
    }

    public void onBlockUpdated(Cell cell) {
        engine.onBlockUpdated(cell);
    }

    public void onTargetChanged(int entityId) {
        engine.onTargetChanged(entityId);
    }

    public void onCrystalSpawned() {
        engine.onCrystalSpawned();
    }

    public void onCrystalRemoved() {
        engine.onCrystalRemoved();
    }

    public void onTargetSetChanged() {
        engine.onTargetSetChanged();
    }

    public void onOverlayChanged() {
        engine.onOverlayChanged();
    }

    public void onDisabled() {
        engine.onDisabled();
    }

    public void onEnabled() {
        engine.onEnabled();
    }

    /**
     * Action-generation boundary (death/respawn/disable/connection close/dimension/config). Aborts the
     * placement sequence so no pre-boundary crystal can be attacked from stale engine state; hazards and
     * sent risk stay owned by the runtime/ledger and are never cleared here.
     */
    public void onGenerationEnded() {
        engine.onGenerationEnded();
    }

    /** Applies a live settings change; the caller must have ended the generation first. */
    public void reconfigure(DirectSettings settings) {
        engine.reconfigure(settings);
    }

    public DirectPause pause() {
        return engine.pause();
    }

    public CombatEngine engine() {
        return engine;
    }

    /** Visible sequence state, for the wait reason and tests. */
    public DirectSequence.State sequenceState() {
        return engine.sequence().state();
    }
}
