/*
 * Original Comet implementation by Boyonabike.
 * Independent: tests written against the project's own interfaces; no third-party code.
 * SPDX-License-Identifier: CC0-1.0
 */

package dev.tntcoating.core.combat.mc;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.tntcoating.core.combat.CandidateCache;
import dev.tntcoating.core.combat.Cell;
import dev.tntcoating.core.combat.CombatEngine;
import dev.tntcoating.core.combat.DirectPlanState;
import dev.tntcoating.core.combat.Observation;
import dev.tntcoating.core.combat.ObservationKind;
import dev.tntcoating.core.combat.ObservationPayload;
import dev.tntcoating.core.combat.Vec;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** Candidate cache invalidation taps the runtime drives from applied observations. */
final class CombatInvalidationTest {
    private static final Vec SELF = new Vec(0.5, -60.0, 0.5);
    private static final Vec BASE = new Vec(2.0, -61.0, 0.0);
    private static final Vec TARGET = new Vec(2.5, -60.0, 3.0);

    private static DirectPlanState state() {
        return new DirectPlanState(CombatEngine.DIRECT_PLAN, 2L, 0, null, Map.of(), -1);
    }

    private static EngineHarness harness() {
        EngineHarness harness = EngineHarness.create(SELF);
        harness.setBase(EngineHarness.cellOf(BASE));
        harness.addTarget(TARGET);
        return harness;
    }

    @Test
    void aBlockUpdateInvalidatesTheBlockCandidatesOnTheNextPass() {
        EngineHarness harness = harness();
        CandidateCache cache = harness.engine.cache();
        assertTrue(cache.plan(harness.world, state(), 0L).isPresent());

        long before = cache.stats().invalidations();
        cache.onBlockUpdated(EngineHarness.cellOf(BASE));
        assertTrue(cache.plan(harness.world, state(), 1L).isPresent());
        assertTrue(cache.stats().invalidations() > before);
    }

    @Test
    void anAppliedSpawnObservationIsAcceptedWithoutFailingTheNextPass() {
        EngineHarness harness = harness();
        CandidateCache cache = harness.engine.cache();
        assertTrue(cache.plan(harness.world, state(), 0L).isPresent());

        Vec crystal = new Vec(2.5, -60.0, 0.5);
        cache.onObservationApplied(new Observation(harness.connection, 0L, 0L, 0L, 0,
            ObservationKind.SPAWN, harness.spawnCrystal(crystal), null, crystal, null,
            new ObservationPayload.Spawn(EngineHarness.END_CRYSTAL)));
        assertTrue(cache.plan(harness.world, state(), 1L).isPresent());
        assertEquals(0, harness.sink.attempts);
    }

    @Test
    void aTargetChangeBumpsOnlyThatTargetsRevision() {
        EngineHarness harness = harness();
        CandidateCache cache = harness.engine.cache();
        assertEquals(0L, cache.targetRevision(200));

        cache.onTargetChanged(200);
        assertTrue(cache.plan(harness.world, state(), 0L).isPresent());
        assertTrue(cache.targetRevision(200) > 0L);
    }

    @Test
    void aTargetSetChangeBumpsTheTargetSetRevisionOnTheNextPass() {
        EngineHarness harness = harness();
        CandidateCache cache = harness.engine.cache();
        assertTrue(cache.plan(harness.world, state(), 0L).isPresent());
        long before = cache.targetSetRevision();

        cache.onTargetSetChanged();
        assertTrue(cache.plan(harness.world, state(), 1L).isPresent());
        assertTrue(cache.targetSetRevision() > before);
        assertEquals(0, harness.sink.attempts);
    }

    @Test
    void aFarBlockUpdateDoesNotReachTheSendPath() {
        EngineHarness harness = harness();
        CandidateCache cache = harness.engine.cache();
        Cell far = new Cell(10, 64, 10);
        cache.onBlockUpdated(far);
        long first = cache.stats().invalidations();
        cache.onBlockUpdated(far);
        assertTrue(cache.stats().invalidations() >= first);
        assertEquals(0, harness.sink.attempts);
    }
}
