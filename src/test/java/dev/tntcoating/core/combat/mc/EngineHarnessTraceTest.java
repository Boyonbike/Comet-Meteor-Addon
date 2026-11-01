/*
 * Original Comet implementation by Boyonabike.
 * Independent: written against the project's own interfaces; no third-party code.
 * SPDX-License-Identifier: CC0-1.0
 */

package dev.tntcoating.core.combat.mc;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.tntcoating.core.combat.Cell;
import dev.tntcoating.core.combat.CrystalState;
import dev.tntcoating.core.combat.DirectSequence;
import dev.tntcoating.core.combat.EntityKey;
import dev.tntcoating.core.combat.Observation;
import dev.tntcoating.core.combat.ObservationKind;
import dev.tntcoating.core.combat.TraceReplayer;
import dev.tntcoating.core.combat.Vec;
import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * Replays a Phase 0 trace through the real engine, ledger, budget and dispatch path.
 *
 * <p>The open_target trace is the only recorded placement whose spawn then dies by an explosion. The
 * recorder could not attribute that explosion to an entity (payload id -1), so the ledger can still
 * mark the crystal exploded by proximity while the budget must not retire the risk; retirement is
 * then proven with attributed evidence. That is exactly the safety model the trace exists to pin.
 */
class EngineHarnessTraceTest {
    private static final Vec SELF = new Vec(0.5, -60, 0.5);
    private static final Cell BASE = new Cell(2, -61, 0);
    private static final Vec TARGET = new Vec(4.5, -60, 0.5);
    private static final Path TRACE =
        Path.of("src/test/resources/combat/traces/open_target/delay-0-tps-20/run-0/client.jsonl");

    @Test
    void replaysPlacementSpawnRemovalAndRefusesToRetireOnUnattributedEvidence() throws IOException {
        TraceReplayer.Replay replay = TraceReplayer.load(TRACE);
        Observation spawn = replay.observations().stream()
            .filter(TraceReplayer::isCrystalSpawn)
            .findFirst()
            .orElseThrow();
        EntityKey traced = spawn.entity();

        EngineHarness harness = EngineHarness.create(SELF);
        harness.setBase(BASE);
        harness.addTarget(TARGET);
        harness.tick();
        assertEquals(1, harness.placeCount(), "the traced layout must place exactly once");

        harness.client.putCrystal(traced.id(), traced.uuid(), spawn.position());
        EntityKey crystal = remap(harness, traced);

        // Feed the trace's own spawn receipt exactly as the recorder saw it.
        harness.observe(remap(harness, spawn));
        assertEquals(CrystalState.OBSERVED, harness.crystals.state(crystal));

        harness.engine.onSpawnObserved(crystal, harness.world, harness.snapshot(), harness.gateway);
        assertEquals(0, harness.budget.neverRetirableComponents(),
            "the placement risk must be bound to the traced spawn before the break");
        assertEquals(1, harness.attackCount());
        assertEquals(DirectSequence.State.ATTACK_SENT, harness.engine.sequence().state());
        assertEquals(1, harness.budget.outstanding());

        // Replay the rest of that crystal's lifecycle from the trace.
        feedRest(harness, replay, traced);
        assertEquals(CrystalState.EXPLODED, harness.crystals.state(crystal),
            "proximity attribution marks the crystal exploded even though the packet carried no entity");

        harness.tick();
        assertEquals(DirectSequence.State.IDLE, harness.engine.sequence().state(),
            "removal evidence ends the sequence without a retry");

        // The recorded explosion carries entity id -1: it is evidence of nothing we sent.
        assertEquals(1, harness.budget.outstanding(),
            "unattributed explosion evidence must never retire a sent placement risk");

        // Attributed evidence (bound crystal + causally later coherent snapshot) is the only retirement.
        harness.explosion(crystal, spawn.position());
        harness.selfHealth(20.0, 0.0);
        assertEquals(0, harness.budget.outstanding());
        assertTrue(harness.budget.neverRetirableComponents() == 0);
    }

    private static void feedRest(EngineHarness harness, TraceReplayer.Replay replay, EntityKey traced) {
        List<Observation> observations = replay.observations();
        boolean afterSpawn = false;
        for (Observation observation : observations) {
            if (observation.entity() != null && observation.entity().equals(traced)
                && observation.kind() == ObservationKind.SPAWN) {
                afterSpawn = true;
                continue;
            }
            if (!afterSpawn) continue;
            if (observation.kind() != ObservationKind.REMOVED
                && observation.kind() != ObservationKind.EXPLOSION) {
                continue;
            }
            if (observation.entity() != null && !observation.entity().equals(traced)) continue;
            harness.observe(remap(harness, observation));
        }
    }

    private static Observation remap(EngineHarness harness, Observation observation) {
        EntityKey entity = observation.entity() == null ? null : remap(harness, observation.entity());
        return new Observation(harness.self().conn(), observation.receiptSeq(),
            observation.appliedRevision(), observation.receiptNanos(), observation.clientTick(),
            observation.kind(), entity, observation.cell(), observation.position(),
            observation.bounds(), observation.payload());
    }

    private static EntityKey remap(EngineHarness harness, EntityKey traced) {
        if (traced.id() == harness.self().id() && traced.uuid() != null
            && !traced.uuid().equals(harness.self().uuid())) {
            return harness.self();
        }
        return new EntityKey(harness.self().conn(), traced.id(), traced.uuid());
    }

    @Test
    void unmatchedSpawnTraceNeverRetriesIntoTheReservedCell() throws IOException {
        TraceReplayer.Replay replay = TraceReplayer.load(TRACE);
        EngineHarness harness = EngineHarness.create(SELF);
        harness.setBase(BASE);
        harness.addTarget(TARGET);
        harness.tick();
        assertEquals(1, harness.placeCount());

        Observation spawn = replay.observations().stream()
            .filter(TraceReplayer::isCrystalSpawn).findFirst().orElseThrow();
        EntityKey crystal = remap(harness, spawn.entity());
        Vec elsewhere = new Vec(2.5, -59.0, 20.5);
        harness.client.putCrystal(crystal.id(), crystal.uuid(), elsewhere);
        harness.observe(new Observation(spawn.conn(), spawn.receiptSeq(), spawn.appliedRevision(),
            spawn.receiptNanos(), spawn.clientTick(), ObservationKind.SPAWN, crystal, spawn.cell(),
            elsewhere, spawn.bounds(), spawn.payload()));

        assertEquals(CrystalState.OBSERVED, harness.crystals.state(crystal),
            "a spawn outside every reserved source is recorded but cannot bind");
        harness.tick(39);
        assertEquals(1, harness.placeCount(), "no retry into the unresolved cell");
        assertEquals(0, harness.attackCount(), "an unmatched spawn is never attacked");
        assertEquals(DirectSequence.State.PLACE_SENT, harness.engine.sequence().state());
        assertTrue(harness.crystals.blocksCell(new Cell(2, -60, 0)),
            "the spawn hazard keeps the cell blocked until it is resolved");
    }
}
