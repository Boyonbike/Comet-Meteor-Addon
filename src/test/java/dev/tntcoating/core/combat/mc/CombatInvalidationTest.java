/*
 * Original Comet test by Boyonabike.
 * Independent: written from the project's own interfaces; no third-party code.
 * SPDX-License-Identifier: CC0-1.0
 */

package dev.tntcoating.core.combat.mc;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.tntcoating.core.combat.Action;
import dev.tntcoating.core.combat.Box;
import dev.tntcoating.core.combat.CandidateCache;
import dev.tntcoating.core.combat.Cell;
import dev.tntcoating.core.combat.CombatEngine;
import dev.tntcoating.core.combat.CrystalState;
import dev.tntcoating.core.combat.DirectDispatch;
import dev.tntcoating.core.combat.DirectGateway;
import dev.tntcoating.core.combat.DirectGateway.DirectSendResult;
import dev.tntcoating.core.combat.DirectPlan;
import dev.tntcoating.core.combat.DirectPlanState;
import dev.tntcoating.core.combat.DirectTarget;
import dev.tntcoating.core.combat.EntityKey;
import dev.tntcoating.core.combat.Intent;
import dev.tntcoating.core.combat.MitigatedDamage;
import dev.tntcoating.core.combat.Observation;
import dev.tntcoating.core.combat.ObservationKind;
import dev.tntcoating.core.combat.ObservationPayload;
import dev.tntcoating.core.combat.RiskRequest;
import dev.tntcoating.core.combat.SafetyState;
import dev.tntcoating.core.combat.Safety;
import dev.tntcoating.core.combat.ServerTickInterval;
import dev.tntcoating.core.combat.Ticket;
import dev.tntcoating.core.combat.TickBudget;
import dev.tntcoating.core.combat.Vec;
import dev.tntcoating.core.combat.mc.CombatRuntime.DispatchResult;
import dev.tntcoating.core.combat.mc.CombatRuntime.Reason;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

/**
 * Phase 4.1: event-driven candidate invalidation, selective cache reuse, capped resumable searches and the
 * shared tick budget. The cache never sends: every cached plan still travels through
 * {@link RuntimeDirectGateway#send} and {@link CombatRuntime#dispatch}.
 */
class CombatInvalidationTest {
    private static EngineHarness placementHarness() {
        EngineHarness harness = EngineHarness.create(new Vec(0.5, -60.0, 0.5));
        harness.setBase(new Cell(2, -61, 0));
        harness.addTarget(new Vec(2.5, -60.0, 3.0));
        return harness;
    }

    private static DirectPlanState state(EngineHarness harness) {
        return new DirectPlanState(CombatEngine.DIRECT_PLAN, 2L, harness.serverTick, null, Map.of(), -1);
    }

    private static DirectPlan plan(CandidateCache cache, EngineHarness harness) {
        return cache.plan(harness.world, state(harness), harness.revision).orElseThrow();
    }

    @Test
    void cacheHitReproducesThePlannerAndOnlyAffectedEntriesAreInvalidated() {
        EngineHarness harness = placementHarness();
        CandidateCache cache = harness.engine.cache();

        DirectPlan first = plan(cache, harness);
        long invalidations = cache.stats().invalidations();

        DirectPlan cached = plan(cache, harness);
        assertTrue(cache.stats().hits() >= 1);
        assertEquals(first.action(), cached.action());
        assertEquals(first.target(), cached.target());
        assertEquals(first.intent().cell(), cached.intent().cell());

        Optional<DirectPlan> fresh = harness.planner.plan(harness.world, state(harness));
        assertTrue(fresh.isPresent());
        assertEquals(first.action(), fresh.get().action());
        assertEquals(first.intent().cell(), fresh.get().intent().cell());

        DirectPlan cachedAgain = plan(cache, harness);
        assertTrue(cache.stats().hits() >= 2);
        assertEquals(cachedAgain.action(), first.action());
        assertEquals(invalidations, cache.stats().invalidations());
    }

    @Test
    void repeatedInvalidationsCoalesceAndStaySelective() {
        EngineHarness harness = placementHarness();
        var first = harness.addTarget(new Vec(-2.0, -60.0, 0.5));
        var second = harness.addTarget(new Vec(1.5, -60.0, 1.5));
        CandidateCache cache = new CandidateCache(harness.planner);

        plan(cache, harness);
        long invalidations = cache.stats().invalidations();

        for (int i = 0; i < 50; i++) {
            cache.onTargetChanged(first.entity().id());
            cache.onBlockUpdated(new Cell(i % 5, -61, 0));
            cache.onCrystalSpawned();
        }
        assertFalse(cache.stats().invalidations() > invalidations, "invalidations flush at the next pass");

        plan(cache, harness);
        assertEquals(invalidations + 3, cache.stats().invalidations());
        assertEquals(1L, cache.targetRevision(first.entity().id()));
        assertEquals(0L, cache.targetRevision(second.entity().id()));
    }

    @Test
    void cappedSearchResumesItsCursorAcrossTicksAndMatchesThePlanner() {
        EngineHarness harness = EngineHarness.create(new Vec(0.5, -60.0, 0.5));
        for (int x = 0; x < 5; x++) {
            harness.setBase(new Cell(x, -61, 0));
        }
        harness.addTarget(new Vec(2.5, -60.0, 3.0));
        CandidateCache capped = new CandidateCache(harness.planner, 2);

        Optional<DirectPlan> plan = Optional.empty();
        int calls = 0;
        while (plan.isEmpty() && calls < 20) {
            plan = capped.plan(harness.world, state(harness), harness.revision);
            calls++;
        }

        assertTrue(plan.isPresent(), "the resumable scan eventually completes");
        assertTrue(capped.stats().resumedPasses() >= 2);
        assertEquals(5L, capped.stats().candidatesComputed());
        assertEquals(1L, capped.stats().passes());

        Optional<DirectPlan> reference = harness.planner.plan(harness.world, state(harness));
        assertTrue(reference.isPresent());
        assertEquals(reference.get().action(), plan.get().action());
        assertEquals(reference.get().target(), plan.get().target());
        assertEquals(reference.get().intent().cell(), plan.get().intent().cell());
    }

    @Test
    void oldSnapshotContinuationIsNeverCommitted() {
        EngineHarness harness = EngineHarness.create(new Vec(0.5, -60.0, 0.5));
        for (int x = 0; x < 5; x++) {
            harness.setBase(new Cell(x, -61, 0));
        }
        var target = harness.addTarget(new Vec(2.5, -60.0, 3.0));
        CandidateCache capped = new CandidateCache(harness.planner, 1);

        assertTrue(capped.plan(harness.world, state(harness), harness.revision).isEmpty());
        assertTrue(capped.continuationPending());

        capped.onTargetChanged(target.entity().id());
        assertEquals(0L, capped.stats().droppedContinuations());
        assertTrue(capped.plan(harness.world, state(harness), harness.revision).isEmpty());
        assertEquals(1L, capped.stats().droppedContinuations());
        assertTrue(capped.stats().oldSnapshotRejections() >= 1);

        Optional<DirectPlan> plan = Optional.empty();
        int calls = 0;
        while (plan.isEmpty() && calls < 20) {
            plan = capped.plan(harness.world, state(harness), harness.revision);
            calls++;
        }
        assertTrue(plan.isPresent());
        assertEquals(1L, capped.stats().droppedContinuations());
    }

    @Test
    void cacheHitStillRunsFullDispatchValidation() {
        EngineHarness harness = EngineHarness.create(new Vec(0.5, -60.0, 0.5));
        harness.addTarget(new Vec(2.5, -60.0, 3.0));
        Vec crystalPosition = new Vec(2.5, -60.0, 0.5);
        int crystalId = 700;
        UUID crystalUuid = UUID.randomUUID();
        EntityKey crystal = new EntityKey(harness.connection, crystalId, crystalUuid);
        harness.client.putCrystal(crystalId, crystalUuid, crystalPosition);
        harness.crystals.observe(new Observation(harness.connection, harness.receipt++,
            harness.revision++, System.nanoTime(), harness.serverTick, ObservationKind.SPAWN, crystal,
            null, crystalPosition, Box.fromCell(EngineHarness.cellOf(crystalPosition)),
            new ObservationPayload.Spawn(EngineHarness.END_CRYSTAL)));
        CandidateCache cache = new CandidateCache(harness.planner);

        plan(cache, harness);
        DirectPlan cached = plan(cache, harness);
        assertTrue(cache.stats().hits() >= 1);
        assertEquals(Action.ATTACK_CRYSTAL, cached.action());

        AtomicReference<DispatchResult> last = new AtomicReference<>();
        harness.runtime.setDispatchObserver(last::set);
        TickBudget budget = TickBudget.workLimited(10_000);
        harness.runtime.activateBudget(budget);

        // (a) The cached plan is rejected when the current snapshot no longer contains the entity.
        harness.client.entities.remove(crystal.id());
        harness.client.crystals.removeIf(value -> value.entity().equals(crystal));
        assertRejected(harness, cached, crystalPosition, last, DirectSendResult.BLOCKED,
            Reason.UNKNOWN_ENTITY);

        // (b) Restore the entity, then apply a stricter current policy than the cached plan was made under.
        harness.client.putCrystal(crystal.id(), crystalUuid, crystalPosition);
        SafetyState strict = new SafetyState(harness.world.safetyState().conn(),
            harness.world.safetyState().gen(), harness.world.safetyState().self(),
            harness.world.safetyState().selfHealth(), harness.world.safetyState().friends(),
            new Safety(harness.safety.minTarget(), 0.5, harness.safety.minRemaining(),
                harness.safety.maxFriend()));
        assertRejected(harness, cached, crystalPosition, last, strict, DirectSendResult.FAILED,
            Reason.POLICY_REJECTED);

        // (c) Restore the policy, then move the current eye out of reach before validation.
        Vec eye = harness.client.eye;
        harness.client.eye = new Vec(100.0, -60.0, 100.0);
        assertRejected(harness, cached, crystalPosition, last, DirectSendResult.FAILED,
            Reason.OUT_OF_REACH);

        // (d) Restore reach, then change the required item out from under the cached plan.
        harness.client.eye = eye;
        harness.client.hotbar[0] = "minecraft:stone";
        assertRejected(harness, cached, crystalPosition, last, DirectSendResult.BLOCKED,
            Reason.ITEM_MISSING);

        // (e) A fully valid snapshot still sends, proving validation was not bypassed or short-circuited.
        harness.client.hotbar[0] = EngineHarness.END_CRYSTAL;
        int attempts = harness.sink.attempts;
        Intent intent = attemptIntent(cached);
        Ticket ticket = harness.session.ticket(CombatEngine.DIRECT_PLAN);
        assertTrue(harness.scheduler.reserve(intent, ticket, request(harness, cached,
            crystalPosition)));
        DirectSendResult result = harness.gateway.send(new DirectDispatch(intent,
            cached.context(), ticket, CombatEngine.DIRECT_PLAN, harness.world.safetyState()));

        assertEquals(DirectSendResult.SENT, result);
        assertEquals(Reason.NONE, last.get().reason());
        assertEquals(attempts + 2, harness.sink.attempts);
        assertEquals(5L, budget.phaseWork(TickBudget.Phase.VALIDATION));
    }

    @Test
    void eventStormStaysInBudgetByDeferringOptionalWorkOnly() {
        EngineHarness harness = placementHarness();
        harness.tick();
        assertEquals(1, harness.placeCount());
        assertEquals(0, harness.attackCount());
        long invalidations = harness.engine.cache().stats().invalidations();

        int nextTick = harness.serverTick + 1;
        List<EntityKey> spawnKeys = new ArrayList<>();
        for (int i = 0; i < 52; i++) {
            EntityKey key = new EntityKey(harness.connection, 5000 + i, UUID.randomUUID());
            Vec position = i == 0
                ? new Vec(2.5, -60.0, 0.5)
                : new Vec(6.0 + i * 0.5, -60.0, 0.5);
            if (i == 0) {
                // The crystal appears in the cell this tick's placement reserved, so the resumed break is
                // a real candidate on the next tick; it is still only observed through the applied stream.
                harness.client.putCrystal(5000, key.uuid(), position);
            }
            spawnKeys.add(key);
            harness.intake.enqueueForTest(new Observation(harness.connection, harness.receipt++,
                harness.revision++, System.nanoTime(), nextTick, ObservationKind.SPAWN, key, null,
                position, Box.fromCell(EngineHarness.cellOf(position)),
                new ObservationPayload.Spawn(EngineHarness.END_CRYSTAL)));
        }
        for (int i = 0; i < 10; i++) {
            harness.intake.enqueueForTest(new Observation(harness.connection, harness.receipt++,
                harness.revision++, System.nanoTime(), nextTick, ObservationKind.BLOCK_UPDATE, null,
                new Cell(0, -61, i % 5), null, null,
                new ObservationPayload.BlockUpdate(EngineHarness.OBSIDIAN, "minecraft:air", false)));
        }

        TickBudget budget = TickBudget.workLimited(50);
        harness.runtime.activateBudget(budget);
        harness.runtime.tick(nextTick, harness.crystals::observe,
            harness.engine::onObservationApplied, budget);
        harness.engine.tick(nextTick, harness.world, harness.snapshot(), harness.gateway, budget);

        // Required work (queue drain, revision stamping, reconciliation) always runs; the budget only
        // deferred optional planning. No send happened on the exhausted, partially invalidated snapshot.
        assertEquals(62L, budget.phaseWork(TickBudget.Phase.QUEUE_DRAIN));
        assertTrue(budget.phaseWork(TickBudget.Phase.REVISION_STAMPING) >= 1);
        assertTrue(budget.phaseWork(TickBudget.Phase.RECONCILIATION) >= 1);
        assertTrue(budget.exhausted());
        assertTrue(budget.deferrals() >= 1);

        for (EntityKey key : spawnKeys) {
            assertEquals(CrystalState.OBSERVED, harness.crystals.state(key));
        }
        assertTrue(harness.intake.drain().isEmpty());
        assertEquals(1, harness.placeCount());
        assertEquals(0, harness.attackCount());
        assertTrue(harness.engine.cache().stats().deferrals() >= 1);
        assertEquals(invalidations + 2, harness.engine.cache().stats().invalidations());

        // Next tick, with the same applied stream and an ample budget, dispatch revalidates and sends once.
        AtomicReference<DispatchResult> last = new AtomicReference<>();
        harness.runtime.setDispatchObserver(last::set);
        int next = nextTick + 1;
        harness.runtime.tick(next, harness.crystals::observe, harness.engine::onObservationApplied,
            TickBudget.unlimited());
        harness.engine.tick(next, harness.world, harness.snapshot(), harness.gateway,
            TickBudget.unlimited());

        assertEquals(1, harness.attackCount());
        assertEquals(Reason.NONE, last.get().reason());
    }

    @Test
    void budgetIsConsumedAcrossEveryPhaseNotJustPlannerTime() {
        EngineHarness harness = placementHarness();
        harness.tick();

        TickBudget budget = TickBudget.workLimited(10_000);
        harness.runtime.activateBudget(budget);
        harness.runtime.capture();
        int next = harness.serverTick + 1;
        harness.runtime.tick(next, harness.crystals::observe, harness.engine::onObservationApplied,
            budget);
        harness.engine.tick(next, harness.world, harness.runtime.capture(), harness.gateway, budget);

        assertTrue(budget.phaseWork(TickBudget.Phase.SNAPSHOT_CAPTURE) >= 2);
        assertTrue(budget.phaseWork(TickBudget.Phase.REVISION_STAMPING) >= 1);
        assertTrue(budget.phaseWork(TickBudget.Phase.RECONCILIATION) >= 1);
        assertTrue(budget.phaseWork(TickBudget.Phase.PLANNING) >= 1);
        assertTrue(budget.work() > budget.phaseWork(TickBudget.Phase.PLANNING),
            "the planner is not the only phase charged to the shared budget");

        EngineHarness eventHarness = EngineHarness.create(new Vec(0.5, -60.0, 0.5));
        eventHarness.addTarget(new Vec(2.5, -60.0, 3.0));
        EntityKey crystal = eventHarness.spawnCrystal(new Vec(2.5, -60.0, 0.5));
        TickBudget eventBudget = TickBudget.workLimited(10_000);
        eventHarness.engine.cache().breakPlan(eventHarness.world, state(eventHarness), crystal,
            eventHarness.revision, eventBudget);
        assertTrue(eventBudget.phaseWork(TickBudget.Phase.EVENT_PASS) >= 1);
    }

    /**
     * A non-crystal spawn is a target-set event: the eligible list and chosen target drop, but the existing
     * target's crystal break candidate stays cached because it is still the same target, snapshot and world.
     */
    @Test
    void targetSpawnBumpsTargetSetRevisionButKeepsPerTargetCrystalBreakCandidates() {
        BreakSetup setup = breakSetup();
        CandidateCache cache = new CandidateCache(setup.harness().planner);
        DirectPlan first = plan(cache, setup.harness());
        assertEquals(Action.ATTACK_CRYSTAL, first.action());
        assertEquals(setup.target().entity(), first.target());
        var before = cache.stats();
        assertEquals(0L, cache.targetSetRevision());

        DirectTarget spawned = setup.harness().addTarget(new Vec(1.5, -60.0, 1.5));
        cache.onObservationApplied(spawnedObservation(setup.harness(), spawned.entity(),
            "minecraft:player"));
        assertEquals(0L, cache.targetSetRevision(), "invalidation is coalesced until the next pass");

        DirectPlan next = plan(cache, setup.harness());
        assertEquals(1L, cache.targetSetRevision());
        assertTrue(next.action() == Action.ATTACK_CRYSTAL || next.action() == Action.PLACE_CRYSTAL);
        var after = cache.stats();
        assertTrue(after.hitsBreak() > before.hitsBreak(),
            "the existing target's crystal break candidate is still cacheable");
        assertTrue(after.missesBreak() > before.missesBreak(),
            "the newly spawned target is evaluated on the next plan");
    }

    /**
     * A dead or removed target is never handed out again: the fresh eligible list cannot select it, and a
     * free-standing spawn-event crystal attack is re-planned instead of being committed against a dead target.
     */
    @Test
    void targetRemovalOrDeathInvalidatesTheTargetChoiceAndFreeStandingPlan() {
        BreakSetup setup = breakSetup();
        CandidateCache cache = new CandidateCache(setup.harness().planner);
        DirectPlan first = plan(cache, setup.harness());
        assertEquals(setup.target().entity(), first.target());

        // Death: the world reports the target dead, and the applied DEATH observation bumps the target set.
        DirectTarget alive = setup.target();
        DirectTarget dead = new DirectTarget(alive.entity(), alive.position(), alive.box(), alive.health(),
            alive.mitigation(), true, false, false, false, false);
        setup.harness().world.targets.set(0, dead);
        cache.onObservationApplied(targetSetObservation(setup.harness(),
            ObservationKind.DEATH, alive.entity()));
        assertTrue(cache.plan(setup.harness().world, state(setup.harness()),
            setup.harness().revision).isEmpty(), "a plan for a dead target must not be returned");
        assertEquals(1L, cache.targetSetRevision());

        // Restore the target; its crystal break candidate is still valid, so the cache keeps working.
        setup.harness().world.targets.set(0, alive);
        assertTrue(cache.plan(setup.harness().world, state(setup.harness()),
            setup.harness().revision).isPresent());

        // Free-standing spawn-event plan for the crystal is cached, then its target is removed.
        assertTrue(cache.breakPlan(setup.harness().world, state(setup.harness()), setup.crystal(),
            setup.harness().revision, TickBudget.unlimited()).isPresent());
        setup.harness().world.targets.clear();
        cache.onObservationApplied(removedObservation(setup.harness(), alive.entity()));
        assertEquals(1L, cache.targetSetRevision(), "the invalidation is coalesced until the next pass");
        assertTrue(cache.plan(setup.harness().world, state(setup.harness()),
            setup.harness().revision).isEmpty(), "a plan for the removed target must not be returned");
        assertEquals(2L, cache.targetSetRevision());
        assertTrue(cache.breakPlan(setup.harness().world, state(setup.harness()), setup.crystal(),
            setup.harness().revision, TickBudget.unlimited()).isEmpty(),
            "a free-standing crystal plan must be re-planned, never committed against a gone target");
    }

    /** A target that appears after the last plan is considered by the next plan, without clearing the cache. */
    @Test
    void newTargetSpawnIsConsideredByTheNextPlan() {
        BreakSetup setup = breakSetup();
        CandidateCache cache = new CandidateCache(setup.harness().planner);
        DirectPlan first = plan(cache, setup.harness());
        assertEquals(setup.target().entity(), first.target());

        DirectTarget spawned = setup.harness().addTarget(new Vec(2.5, -60.0, 0.5));
        cache.onObservationApplied(spawnedObservation(setup.harness(), spawned.entity(),
            "minecraft:player"));

        DirectPlan next = plan(cache, setup.harness());
        assertEquals(spawned.entity(), next.target(), "the next plan recomputes eligible targets");
    }

    /** A continuation stamped before a target-set change is dropped instead of being mixed with the new set. */
    @Test
    void targetSetChangeDropsOldSnapshotContinuation() {
        EngineHarness harness = EngineHarness.create(new Vec(0.5, -60.0, 0.5));
        for (int x = 0; x < 5; x++) {
            harness.setBase(new Cell(x, -61, 0));
        }
        harness.addTarget(new Vec(2.5, -60.0, 3.0));
        CandidateCache capped = new CandidateCache(harness.planner, 1);

        assertTrue(capped.plan(harness.world, state(harness), harness.revision).isEmpty());
        assertTrue(capped.continuationPending());

        DirectTarget spawned = harness.addTarget(new Vec(-2.0, -60.0, 0.5));
        capped.onObservationApplied(spawnedObservation(harness, spawned.entity(), "minecraft:player"));
        assertEquals(0L, capped.stats().droppedContinuations());

        assertTrue(capped.plan(harness.world, state(harness), harness.revision).isEmpty());
        assertEquals(1L, capped.stats().droppedContinuations());
        assertEquals(1L, capped.targetSetRevision());
    }

    /** A cached break priced with a stale self exposure must be repriced before reserve and send. */
    @Test
    void cachedBreakIsRepricedBeforeReserveSoStaleSelfExposureCannotBeSent() {
        BreakSetup setup = breakSetup();
        EngineHarness harness = setup.harness();
        CandidateCache cache = harness.engine.cache();
        cache.onSession(harness.session.connection().epoch(), harness.session.config());
        plan(cache, harness);
        DirectPlan cached = plan(cache, harness);
        assertTrue(cache.stats().hitsBreak() >= 1);
        assertEquals(1.0, cached.context().exposure().selfUpper().beforeAbsorption(), 1e-9);

        // Self movement changes the current position-priced exposure after the plan was cached.
        harness.world.selfUpper = new MitigatedDamage(20.0);
        int attempts = harness.sink.attempts;
        int outstanding = harness.budget.outstanding();

        harness.tick();

        assertEquals(attempts, harness.sink.attempts, "a stale cached break must never be sent");
        assertEquals(outstanding, harness.budget.outstanding(),
            "a rejected reprice must not reserve risk");
    }

    /** A cached break priced while a friend was far must be repriced when the friend moves close. */
    @Test
    void cachedBreakIsRepricedBeforeReserveSoStaleFriendExposureCannotBeSent() {
        BreakSetup setup = breakSetup();
        EngineHarness harness = setup.harness();
        EntityKey friend = harness.addFriend(new Vec(2.5, -60.0, 4.5), 20.0);
        harness.setFriendUpper(friend, 0.5);
        CandidateCache cache = harness.engine.cache();
        cache.onSession(harness.session.connection().epoch(), harness.session.config());
        plan(cache, harness);
        DirectPlan cached = plan(cache, harness);
        assertTrue(cache.stats().hitsBreak() >= 1);
        assertEquals(1, cached.context().exposure().friendUpper().size());

        // The friend moves onto the blast line: the fresh upper bound now breaches the friend limit.
        harness.setFriendUpper(friend, 20.0);
        int attempts = harness.sink.attempts;
        int outstanding = harness.budget.outstanding();

        harness.tick();

        assertEquals(attempts, harness.sink.attempts, "a stale cached break must never be sent");
        assertEquals(outstanding, harness.budget.outstanding(),
            "a rejected reprice must not reserve risk");
    }

    /** A cached placement must never survive the base it was priced against. */
    @Test
    void cachedPlacementCanNeverSendAfterItsBaseDisappears() {
        EngineHarness harness = placementHarness();
        CandidateCache cache = harness.engine.cache();
        cache.onSession(harness.session.connection().epoch(), harness.session.config());
        plan(cache, harness);
        DirectPlan cached = plan(cache, harness);
        assertTrue(cached.isPlacement());
        assertTrue(cache.stats().hitsPlace() >= 1, "the placement must be a cache hit");
        assertEquals(Action.PLACE_CRYSTAL, cached.action());
        Cell base = cached.intent().cell().below();
        assertTrue(harness.world.bases.remove(base), "the priced base must be removable");

        int attempts = harness.sink.attempts;
        int outstanding = harness.budget.outstanding();

        harness.tick();

        assertEquals(attempts, harness.sink.attempts, "a placement whose base vanished must not be sent");
        assertEquals(outstanding, harness.budget.outstanding(),
            "a stale placement must not reserve risk");
    }

    private record BreakSetup(EngineHarness harness, EntityKey crystal, DirectTarget target) {
    }

    private static BreakSetup breakSetup() {
        EngineHarness harness = EngineHarness.create(new Vec(0.5, -60.0, 0.5));
        DirectTarget target = harness.addTarget(new Vec(2.5, -60.0, 3.0));
        Vec crystalPosition = new Vec(2.5, -60.0, 0.5);
        EntityKey crystal = new EntityKey(harness.connection, 700, UUID.randomUUID());
        harness.client.putCrystal(crystal.id(), crystal.uuid(), crystalPosition);
        harness.crystals.observe(new Observation(harness.connection, harness.receipt++,
            harness.revision++, System.nanoTime(), harness.serverTick, ObservationKind.SPAWN, crystal,
            null, crystalPosition, Box.fromCell(EngineHarness.cellOf(crystalPosition)),
            new ObservationPayload.Spawn(EngineHarness.END_CRYSTAL)));
        return new BreakSetup(harness, crystal, target);
    }

    private static Observation spawnedObservation(EngineHarness harness, EntityKey key, String entityType) {
        return new Observation(harness.connection, harness.receipt++, harness.revision++,
            System.nanoTime(), harness.serverTick, ObservationKind.SPAWN, key, null,
            new Vec(0.0, -60.0, 0.0), null, new ObservationPayload.Spawn(entityType));
    }

    private static Observation targetSetObservation(EngineHarness harness, ObservationKind kind,
                                                    EntityKey entity) {
        return new Observation(harness.connection, harness.receipt++, harness.revision++,
            System.nanoTime(), harness.serverTick, kind, entity, null, null, null,
            new ObservationPayload.None());
    }

    private static Observation removedObservation(EngineHarness harness, EntityKey key) {
        return new Observation(harness.connection, harness.receipt++, harness.revision++,
            System.nanoTime(), harness.serverTick, ObservationKind.REMOVED, key, null, null, null,
            new ObservationPayload.Removed("entity-removed", false));
    }

    private static RiskRequest request(EngineHarness harness, DirectPlan plan, Vec crystalPosition) {
        return new RiskRequest(plan.context().exposure(),
            new ServerTickInterval(harness.serverTick + 1, harness.serverTick + 3),
            harness.world.safetyState(), plan.entity(), crystalPosition, 2, harness.serverTick,
            false, false, false);
    }

    private static long nextAttemptIntent = 100;

    private static Intent attemptIntent(DirectPlan plan) {
        Intent source = plan.intent();
        return new Intent(nextAttemptIntent++, source.plan(), source.strategy(), source.action(),
            source.cell(), source.entity(), source.blockLocks(), source.entityLocks(),
            source.dependsOn(), source.expiresTick());
    }

    private static void assertRejected(EngineHarness harness, DirectPlan plan, Vec crystalPosition,
                                       AtomicReference<DispatchResult> last,
                                       DirectSendResult expectedResult, Reason expectedReason) {
        assertRejected(harness, plan, crystalPosition, last, harness.world.safetyState(),
            expectedResult, expectedReason);
    }

    private static void assertRejected(EngineHarness harness, DirectPlan plan, Vec crystalPosition,
                                       AtomicReference<DispatchResult> last, SafetyState dispatchSafety,
                                       DirectSendResult expectedResult, Reason expectedReason) {
        int attempts = harness.sink.attempts;
        Intent intent = attemptIntent(plan);
        Ticket ticket = harness.session.ticket(CombatEngine.DIRECT_PLAN);
        assertTrue(harness.scheduler.reserve(intent, ticket,
            request(harness, plan, crystalPosition)));
        DirectSendResult result = harness.gateway.send(new DirectDispatch(intent,
            plan.context(), ticket, CombatEngine.DIRECT_PLAN, dispatchSafety));

        assertEquals(expectedResult, result);
        assertEquals(expectedReason, last.get().reason());
        assertEquals(attempts, harness.sink.attempts, "a rejected cached plan never reaches the sink");
    }

}
