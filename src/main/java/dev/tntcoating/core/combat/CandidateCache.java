/*
 * Original Comet implementation by Boyonabike.
 * Independent: written from the pinned 26.2 artifact and Meteor build 35 behaviour; no third-party code.
 * SPDX-License-Identifier: CC0-1.0
 */

package dev.tntcoating.core.combat;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/**
 * Phase 4.1 pure candidate cache. It memoises the DirectOnly planner's target/break/place opportunities
 * without changing what an opportunity means: every cached plan is produced by {@link DirectPlanner} and is
 * re-stamped with a fresh intent envelope before it may leave this class.
 *
 * <p>Entries are keyed by the six dimensions plan 4.1 names: world epoch, config generation, the target's
 * equipment/effect revision, the block-collision revision, the overlay revision and the crystal lifecycle
 * revision, plus a separate target-set revision for the eligible-target list and the chosen target. Only
 * affected entries are invalidated: a crystal spawn invalidates crystal-lifecycle candidates, a block update
 * invalidates block-collision/placement candidates, a move invalidates only that target's movement
 * candidates and damage/equipment/effect updates bump only that target's revision. A target-set change (a
 * non-crystal spawn, any removal, death/respawn/setback/explosion/status or a totem pop) drops stale target
 * choices and continuations but keeps per-target crystal/block/overlay candidates for targets that are still
 * eligible. Invalidation events are coalesced until the next planner pass.</p>
 *
 * <p>One pass is capped at {@value #DEFAULT_DIRECT_CANDIDATE_CAP} computed candidates. When the shared
 * {@link TickBudget} runs out (or the cap is reached) the scan stops and leaves a continuation carrying its
 * cursor and revision stamp; the next pass resumes only while every dimension still matches, so candidates
 * from two different snapshots are never mixed. Cache hits are revalidated against the current snapshot
 * before they are returned and never bypass dispatch validation, which stays in {@code CombatRuntime}.</p>
 */
public final class CandidateCache {
    /** Phase 4.1 direct cap: at most 128 candidates per pass. */
    public static final int DEFAULT_DIRECT_CANDIDATE_CAP = 128;

    private static final double EPSILON = 1.0e-9;
    private static final String END_CRYSTAL_ID = "minecraft:end_crystal";

    private static final Comparator<CacheChoice> CHOICE_ORDER = Comparator
        .comparingDouble(CacheChoice::score).reversed()
        .thenComparingDouble(CacheChoice::distance)
        .thenComparingInt(choice -> choice.entity().id())
        .thenComparing(choice -> choice.entity().uuid() == null
            ? "" : choice.entity().uuid().toString());

    private final DirectPlanner planner;
    private final int candidateCap;

    private long worldEpoch = Long.MIN_VALUE;
    private long configGeneration = Long.MIN_VALUE;
    private final Map<Integer, Long> targetRevisions = new HashMap<>();
    private long blockRevision;
    private long overlayRevision;
    private long crystalRevision;
    private long targetSetRevision;

    private boolean blocksChanged;
    private boolean overlaysChanged;
    private boolean crystalsChanged;
    private boolean targetSetChanged;
    private final Set<Integer> pendingTargetChanges = new HashSet<>();
    private final Set<Cell> pendingBlockCells = new HashSet<>();

    private final Map<EntityKey, BreakEntry> breakCache = new LinkedHashMap<>();
    private final Map<EntityKey, PlaceEntry> placeCache = new LinkedHashMap<>();
    private final Map<EntityKey, BreakEntry> eventCache = new LinkedHashMap<>();
    private Continuation continuation;

    private long passes;
    private long hits;
    private long hitsBreak;
    private long hitsPlace;
    private long misses;
    private long missesBreak;
    private long missesPlace;
    private long invalidations;
    private long deferrals;
    private long resumedPasses;
    private long droppedContinuations;
    private long candidatesComputed;
    private long eventPasses;
    private long eventHits;
    private long eventMisses;
    private long oldSnapshotRejections;

    private int candidatesThisPass;
    private long appliedRevisionOfPass;

    public CandidateCache(DirectPlanner planner) {
        this(planner, DEFAULT_DIRECT_CANDIDATE_CAP);
    }

    public CandidateCache(DirectPlanner planner, int candidateCap) {
        this.planner = Objects.requireNonNull(planner, "planner");
        if (candidateCap <= 0) throw new IllegalArgumentException("candidate cap must be positive");
        this.candidateCap = candidateCap;
    }

    public DirectPlanner planner() {
        return planner;
    }

    public int candidateCap() {
        return candidateCap;
    }

    public long targetRevision(int entityId) {
        return targetRevisions.getOrDefault(entityId, 0L);
    }

    /**
     * Revision of the eligible-target list and therefore of the chosen target. A change drops continuations
     * and stale target choices, but not per-target crystal/block/overlay candidates for targets that remain
     * eligible.
     */
    public long targetSetRevision() {
        return targetSetRevision;
    }

    public boolean continuationPending() {
        return continuation != null;
    }

    // ------------------------------------------------------------------ invalidation

    /**
     * World epoch and config generation are session-level dimensions: changing either discards everything,
     * including any resumable cursor, because a plan from another world/config is never valid.
     */
    public void onSession(long worldEpoch, long configGeneration) {
        if (this.worldEpoch == worldEpoch && this.configGeneration == configGeneration) return;
        this.worldEpoch = worldEpoch;
        this.configGeneration = configGeneration;
        invalidations++;
        clearAll();
    }

    public void onCrystalSpawned() {
        crystalsChanged = true;
    }

    /**
     * A removal is typed by the ledger as the crystal lifecycle change it needs, but the removal packet does
     * not carry the entity type: bump the crystal family and the target-set family together.
     */
    public void onCrystalRemoved() {
        crystalsChanged = true;
        targetSetChanged = true;
    }

    /**
     * The eligible target list or the chosen target may have changed (non-crystal spawn, removal,
     * death/respawn/setback/explosion/status, totem pop). Continuations and the target choice are dropped,
     * while per-target candidates for targets that are still eligible remain usable.
     */
    public void onTargetSetChanged() {
        targetSetChanged = true;
    }

    /** A block-collision change; {@code cell} is recorded for diagnostics and coalescing. */
    public void onBlockUpdated(Cell cell) {
        blocksChanged = true;
        if (cell != null) pendingBlockCells.add(cell);
    }

    /** Overlay/defence revision change (Phase 4.4 owns overlays; Phase 4.1 only keys them). */
    public void onOverlayChanged() {
        overlaysChanged = true;
    }

    /** Move/damage/equipment/effect update for one target: only that target's candidates are stale. */
    public void onTargetChanged(int entityId) {
        pendingTargetChanges.add(entityId);
    }

    /**
     * Applied-observation entry point for the engine's observation stream: spawn/removal hit the crystal
     * lifecycle, block updates hit collision/placement, move hits only that target, and damage/equipment/
     * effect updates bump only that target's revision.
     */
    public void onObservationApplied(Observation observation) {
        if (observation == null) return;
        switch (observation.kind()) {
            case SPAWN -> {
                // Only a crystal spawn belongs to the crystal lifecycle; a player/mob spawn changes the
                // eligible target list and the target choice.
                if (isEndCrystal(observation)) {
                    onCrystalSpawned();
                } else {
                    onTargetSetChanged();
                }
            }
            case REMOVED, TRACKING_REMOVED -> {
                // The removal packet does not carry the entity type: assume both families so a removed
                // crystal and a removed target are both invalidated.
                crystalsChanged = true;
                targetSetChanged = true;
            }
            case TOTEM_POP, DEATH, RESPAWN, SETBACK, EXPLOSION, ENTITY_STATUS -> onTargetSetChanged();
            case BLOCK_UPDATE -> onBlockUpdated(observation.cell());
            case MOVE -> {
                if (observation.entity() != null) onTargetChanged(observation.entity().id());
            }
            case DAMAGE_EVENT, EQUIPMENT, EFFECT, HEALTH, ENTITY_DATA -> {
                if (observation.entity() != null) onTargetChanged(observation.entity().id());
            }
            default -> {
                // CONNECTION_CHANGE is session-level and handled by onSession.
            }
        }
    }

    private static boolean isEndCrystal(Observation observation) {
        return observation.payload() instanceof ObservationPayload.Spawn spawn
            && END_CRYSTAL_ID.equals(spawn.entityType());
    }

    // ------------------------------------------------------------------ planning

    /** Pre-budget entry point: an unlimited budget so Phase 3 entry points keep their exact behaviour. */
    public Optional<DirectPlan> plan(DirectWorld world, DirectPlanState state, long appliedRevision) {
        return plan(world, state, appliedRevision, TickBudget.unlimited());
    }

    public Optional<DirectPlan> plan(DirectWorld world, DirectPlanState state, long appliedRevision,
                                     TickBudget budget) {
        if (world == null || state == null || budget == null) return Optional.empty();
        SafetyState safetyState = world.safetyState();
        if (safetyState == null || !Objects.equals(world.connection(), safetyState.conn())) {
            return Optional.empty();
        }
        if (planner.settings().surroundGap()) {
            // Phase 4.5: the gap preference is a choice among base candidates in DirectPlanner.bestPlace.
            // This cache selects per-base plans itself, so the opt-in tactic uses the authoritative planner
            // path instead of a second, subtly different selection rule. The off path is unchanged.
            return planner.plan(world, state);
        }

        flushInvalidations();
        // The candidate cap bounds work per planning call, while the cursor spans calls: reset the
        // per-call counter on every resume so a capped continuation still makes forward progress.
        candidatesThisPass = 0;
        Stamp stamp = stamp();
        if (continuation != null && !continuation.stamp().equals(stamp)) {
            droppedContinuations++;
            oldSnapshotRejections++;
            continuation = null;
        } else if (continuation != null) {
            resumedPasses++;
        }
        if (continuation == null) {
            passes++;
            candidatesThisPass = 0;
            appliedRevisionOfPass = appliedRevision;
            if (budget.exhausted()) {
                budget.defer();
                deferrals++;
                return Optional.empty();
            }
            budget.spend(TickBudget.Phase.PLANNING);
            List<EntityKey> targets = new ArrayList<>();
            for (DirectTarget target : planner.eligibleTargets(world)) {
                targets.add(target.entity());
            }
            continuation = new Continuation(stamp, appliedRevision, List.copyOf(targets), 0, null,
                List.of(), 0, false, null, false);
        }

        Continuation pass = continuation;
        Map<EntityKey, DirectTarget> byKey = new LinkedHashMap<>();
        for (DirectTarget target : planner.eligibleTargets(world)) {
            byKey.put(target.entity(), target);
        }

        while (pass.targetIndex < pass.targets.size()) {
            EntityKey key = pass.targets.get(pass.targetIndex);
            DirectTarget target = byKey.get(key);
            if (target == null) {
                pass = pass.advanceTarget();
                continue;
            }
            if (pass.scanTarget == null || !pass.scanTarget.equals(key)) {
                if (!pass.breakDone) {
                    if (budget.exhausted()) return defer(pass, budget);
                    budget.spend(TickBudget.Phase.PLANNING);
                    Optional<DirectPlan> breakPlan = breakCandidate(world, state, target);
                    pass = pass.withBreak(breakPlan.orElse(null));
                }
                PlaceEntry cached = placeCache.get(key);
                if (cached != null && validPlace(cached, world, state)) {
                    hits++;
                    hitsPlace++;
                    pass = pass.advanceTarget();
                    continue;
                }
                if (cached != null) {
                    placeCache.remove(key);
                    oldSnapshotRejections++;
                }
                List<DirectPlanner.BaseCandidate> bases = planner.baseCandidates(world);
                pass = pass.startScan(bases);
                if (budget.exhausted()) return defer(pass, budget);
            }

            DirectPlan bestPlace = pass.directBest;
            List<DirectPlanner.BaseCandidate> bases = pass.bases;
            int index = pass.baseIndex;
            boolean supportPhase = pass.supportPhase;
            boolean deferred = false;
            while (index < bases.size()) {
                DirectPlanner.BaseCandidate base = bases.get(index);
                if (!supportPhase && base.support()) {
                    index++;
                    continue;
                }
                if (supportPhase && !base.support()) {
                    index++;
                    continue;
                }
                if (candidatesThisPass >= candidateCap || budget.exhausted()) {
                    deferred = true;
                    break;
                }
                budget.spend(TickBudget.Phase.PLANNING);
                candidatesComputed++;
                candidatesThisPass++;
                DirectPlan plan = planner.placeAt(world, state, target, base);
                if (plan != null && (bestPlace == null || betterPlace(plan, bestPlace))) {
                    bestPlace = plan;
                }
                index++;
            }
            if (deferred) {
                return defer(pass.withScan(index, supportPhase, bestPlace), budget);
            }
            if (bestPlace == null && !supportPhase && hasSupportCandidate(bases)) {
                // Direct pass produced nothing; only now does the planner fall back to support placement.
                pass = pass.withScan(0, true, null);
                continue;
            }
            misses += bestPlace == null ? 1 : 0;
            missesPlace += bestPlace == null ? 1 : 0;
            if (bestPlace != null) {
                storePlace(world, target, bestPlace, state);
            }
            pass = pass.advanceTarget();
        }

        continuation = null;
        candidatesThisPass = 0;
        return select(world, state, byKey);
    }

    /**
     * Spawn-event break path: only the newly bound crystal is considered, exactly like
     * {@link DirectPlanner#planBreak}. Cached entries are revalidated against the current snapshot before use.
     */
    public Optional<DirectPlan> breakPlan(DirectWorld world, DirectPlanState state, EntityKey crystalEntity,
                                          long appliedRevision, TickBudget budget) {
        if (world == null || state == null || crystalEntity == null || budget == null) {
            return Optional.empty();
        }
        SafetyState safetyState = world.safetyState();
        if (safetyState == null || !Objects.equals(world.connection(), safetyState.conn())) {
            return Optional.empty();
        }
        flushInvalidations();
        eventPasses++;
        if (budget.exhausted()) {
            budget.defer();
            deferrals++;
            return Optional.empty();
        }
        budget.spend(TickBudget.Phase.EVENT_PASS);
        BreakEntry cached = eventCache.get(crystalEntity);
        if (cached != null && validEventBreak(cached, world, state)) {
            hits++;
            hitsBreak++;
            eventHits++;
            return Optional.of(planner.refresh(cached.plan(), state));
        }
        if (cached != null) {
            eventCache.remove(crystalEntity);
            oldSnapshotRejections++;
        }
        eventMisses++;
        misses++;
        missesBreak++;
        Optional<DirectPlan> plan = planner.planBreak(world, state, crystalEntity);
        plan.ifPresent(value -> eventCache.put(crystalEntity, new BreakEntry(value,
            targetRevision(value.target().id()), blockRevision, crystalRevision, targetSetRevision,
            attempts(state, value.entity()), state.lastToolSwitchTick(), safetyStamp(world),
            appliedRevision)));
        return plan.map(value -> planner.refresh(value, state));
    }

    // ------------------------------------------------------------------ cache helpers

    private Optional<DirectPlan> breakCandidate(DirectWorld world, DirectPlanState state,
                                                DirectTarget target) {
        BreakEntry cached = breakCache.get(target.entity());
        if (cached != null && validBreak(cached, world, state)) {
            hits++;
            hitsBreak++;
            return Optional.of(cached.plan());
        }
        if (cached != null) {
            breakCache.remove(target.entity());
            oldSnapshotRejections++;
        }
        misses++;
        missesBreak++;
        Optional<DirectPlan> plan = planner.bestBreakFor(world, state, target);
        plan.ifPresent(value -> breakCache.put(target.entity(), new BreakEntry(value,
            targetRevision(target.entity().id()), blockRevision, crystalRevision, targetSetRevision,
            attempts(state, value.entity()), state.lastToolSwitchTick(), safetyStamp(world),
            appliedRevisionOfPass)));
        return plan;
    }

    private void storePlace(DirectWorld world, DirectTarget target, DirectPlan plan,
                            DirectPlanState state) {
        Cell base = plan.action() == Action.PLACE_SUPPORT
            ? plan.intent().cell() : plan.intent().cell().below();
        placeCache.put(target.entity(), new PlaceEntry(plan, targetRevision(target.entity().id()),
            blockRevision, crystalRevision, targetSetRevision, overlayRevision, safetyStamp(world), base,
            plan.action() == Action.PLACE_SUPPORT, appliedRevisionOfPass));
    }

    /** The exact planner fallback rule: support placement is only considered when a support base exists. */
    private boolean hasSupportCandidate(List<DirectPlanner.BaseCandidate> bases) {
        for (DirectPlanner.BaseCandidate base : bases) {
            if (base.support()) return true;
        }
        return false;
    }

    private boolean betterPlace(DirectPlan candidate, DirectPlan incumbent) {
        double candidateScore = candidate.context().estimate().expected()
            * candidate.context().confidence();
        double incumbentScore = incumbent.context().estimate().expected()
            * incumbent.context().confidence();
        if (candidateScore > incumbentScore + EPSILON) return true;
        if (Math.abs(candidateScore - incumbentScore) > EPSILON) return false;
        return compareCells(candidate.intent().cell(), incumbent.intent().cell()) < 0;
    }

    private static int compareCells(Cell left, Cell right) {
        if (left == null && right == null) return 0;
        if (left == null) return 1;
        if (right == null) return -1;
        int byX = Integer.compare(left.x(), right.x());
        if (byX != 0) return byX;
        int byY = Integer.compare(left.y(), right.y());
        return byY != 0 ? byY : Integer.compare(left.z(), right.z());
    }

    private Optional<DirectPlan> select(DirectWorld world, DirectPlanState state,
                                        Map<EntityKey, DirectTarget> byKey) {
        List<CacheChoice> choices = new ArrayList<>();
        for (Map.Entry<EntityKey, DirectTarget> entry : byKey.entrySet()) {
            EntityKey key = entry.getKey();
            BreakEntry breakEntry = breakCache.get(key);
            PlaceEntry placeEntry = placeCache.get(key);
            Optional<DirectPlan> breakPlan = breakEntry == null
                ? Optional.empty() : Optional.of(breakEntry.plan());
            Optional<DirectPlan> placePlan = placeEntry == null
                ? Optional.empty() : Optional.of(placeEntry.plan());
            DirectPlan chosen = planner.choosePlan(breakPlan, placePlan);
            if (chosen == null) continue;
            choices.add(new CacheChoice(key, world.selfPosition().distance(entry.getValue().position()),
                chosen));
        }
        if (choices.isEmpty()) return Optional.empty();
        choices.sort(CHOICE_ORDER);
        int limit = Math.min(DirectPlanner.MAX_TARGETS_PER_PASS, choices.size());
        CacheChoice chosen = sticky(choices.subList(0, limit), state.preferredTarget());
        if (chosen == null) return Optional.empty();
        return Optional.of(planner.refresh(chosen.plan(), state));
    }

    private CacheChoice sticky(List<CacheChoice> shortlist, EntityKey preferred) {
        CacheChoice best = shortlist.get(0);
        if (preferred == null) return best;
        CacheChoice sticky = null;
        CacheChoice alternative = null;
        for (CacheChoice choice : shortlist) {
            if (Objects.equals(choice.entity(), preferred)) {
                sticky = choice;
            } else if (alternative == null) {
                alternative = choice;
            }
        }
        if (sticky == null) return best;
        if (alternative == null) return sticky;
        return sticky.score() + EPSILON >= DirectPlanner.TARGET_STICKY_FRACTION * alternative.score()
            ? sticky : best;
    }

    private Optional<DirectPlan> defer(Continuation pass, TickBudget budget) {
        continuation = pass;
        budget.defer();
        deferrals++;
        return Optional.empty();
    }

    // ------------------------------------------------------------------ validity

    /**
     * Per-target break entries stay usable across a target-set change: selection always re-derives the chosen
     * target from the current eligible list, so a new target is still considered and a removed target cannot
     * be selected. A free-standing spawn-event plan also carries its chosen target, so it must additionally
     * drop when that target is no longer eligible.
     */
    private boolean validEventBreak(BreakEntry entry, DirectWorld world, DirectPlanState state) {
        if (!validBreak(entry, world, state)) return false;
        for (DirectTarget target : planner.eligibleTargets(world)) {
            if (Objects.equals(target.entity(), entry.plan().target())) return true;
        }
        return false;
    }

    private boolean validBreak(BreakEntry entry, DirectWorld world, DirectPlanState state) {
        if (entry.plan() == null) return false;
        if (entry.targetRevision() != targetRevision(entry.plan().target().id())) return false;
        if (entry.blockRevision() != blockRevision) return false;
        if (entry.crystalRevision() != crystalRevision) return false;
        if (entry.attempts() != attempts(state, entry.plan().entity())) return false;
        if (entry.lastToolSwitchTick() != state.lastToolSwitchTick()) return false;
        return entry.safety().equals(safetyStamp(world));
    }

    private boolean validPlace(PlaceEntry entry, DirectWorld world, DirectPlanState state) {
        if (entry.targetRevision() != targetRevision(entry.plan().target().id())) return false;
        if (entry.blockRevision() != blockRevision) return false;
        if (entry.crystalRevision() != crystalRevision) return false;
        if (entry.overlayRevision() != overlayRevision) return false;
        if (!entry.safety().equals(safetyStamp(world))) return false;
        DirectTarget target = null;
        for (DirectTarget candidate : world.targets()) {
            if (Objects.equals(candidate.entity(), entry.plan().target())) {
                target = candidate;
                break;
            }
        }
        if (target == null) return false;
        // A hit must still be the exact candidate the planner would produce on the current snapshot.
        DirectPlan fresh = planner.placeAt(world, state, target,
            new DirectPlanner.BaseCandidate(entry.base(), entry.support()));
        return fresh != null && fresh.action() == entry.plan().action()
            && Objects.equals(fresh.intent().cell(), entry.plan().intent().cell());
    }

    private static int attempts(DirectPlanState state, EntityKey crystal) {
        return state.breakAttempts().getOrDefault(crystal, 0);
    }

    private void flushInvalidations() {
        if (crystalsChanged) {
            crystalRevision++;
            crystalsChanged = false;
            invalidations++;
        }
        if (targetSetChanged) {
            targetSetRevision++;
            targetSetChanged = false;
            invalidations++;
        }
        if (blocksChanged) {
            blockRevision++;
            blocksChanged = false;
            pendingBlockCells.clear();
            invalidations++;
        }
        if (overlaysChanged) {
            overlayRevision++;
            overlaysChanged = false;
            invalidations++;
        }
        if (!pendingTargetChanges.isEmpty()) {
            for (Integer id : pendingTargetChanges) {
                targetRevisions.merge(id, 1L, Long::sum);
            }
            pendingTargetChanges.clear();
            invalidations++;
        }
    }

    private void clearAll() {
        breakCache.clear();
        placeCache.clear();
        eventCache.clear();
        continuation = null;
        targetRevisions.clear();
        pendingTargetChanges.clear();
        pendingBlockCells.clear();
        crystalsChanged = false;
        blocksChanged = false;
        overlaysChanged = false;
        targetSetChanged = false;
        candidatesThisPass = 0;
    }

    private Stamp stamp() {
        return new Stamp(worldEpoch, configGeneration, blockRevision, overlayRevision, crystalRevision,
            targetSetRevision, Map.copyOf(targetRevisions));
    }

    // ------------------------------------------------------------------ stats (internal/test-only)

    public Stats stats() {
        return new Stats(passes, hits, hitsBreak, hitsPlace, misses, missesBreak, missesPlace,
            invalidations, deferrals, resumedPasses, droppedContinuations, candidatesComputed, eventPasses,
            eventHits, eventMisses, oldSnapshotRejections);
    }

    public record Stats(long passes, long hits, long hitsBreak, long hitsPlace, long misses,
                        long missesBreak, long missesPlace, long invalidations, long deferrals,
                        long resumedPasses, long droppedContinuations, long candidatesComputed,
                        long eventPasses, long eventHits, long eventMisses,
                        long oldSnapshotRejections) {
    }

    // ------------------------------------------------------------------ internal records

    private record Stamp(long worldEpoch, long configGeneration, long blockRevision, long overlayRevision,
                         long crystalRevision, long targetSetRevision, Map<Integer, Long> targetRevisions) {
    }

    private record CacheChoice(EntityKey entity, double distance, DirectPlan plan) {
        double score() {
            return plan.context().estimate().expected() * plan.context().confidence();
        }
    }

    private record BreakEntry(DirectPlan plan, long targetRevision, long blockRevision,
                              long crystalRevision, long targetSetRevision, int attempts,
                              int lastToolSwitchTick, SafetyStamp safety, long appliedRevision) {
    }

    private record PlaceEntry(DirectPlan plan, long targetRevision, long blockRevision, long crystalRevision,
                              long targetSetRevision, long overlayRevision, SafetyStamp safety, Cell base,
                              boolean support, long appliedRevision) {
    }

    private record SafetyStamp(Capability selfCap, double selfValue, Map<EntityKey, FriendStamp> friends,
                               Safety safety) {
    }

    private record FriendStamp(Capability cap, double value) {
    }

    private static SafetyStamp safetyStamp(DirectWorld world) {
        SafetyState state = world.safetyState();
        Map<EntityKey, FriendStamp> friends = new HashMap<>();
        for (Map.Entry<EntityKey, EffectiveHealth> entry : state.friends().entrySet()) {
            friends.put(entry.getKey(),
                new FriendStamp(entry.getValue().cap(), entry.getValue().value()));
        }
        return new SafetyStamp(state.selfHealth().cap(), state.selfHealth().value(),
            Map.copyOf(friends), state.safety());
    }

    private static final class Continuation {
        private final Stamp stamp;
        private final long appliedRevision;
        private final List<EntityKey> targets;
        private final int targetIndex;
        private final EntityKey scanTarget;
        private final List<DirectPlanner.BaseCandidate> bases;
        private final int baseIndex;
        private final boolean supportPhase;
        private final DirectPlan directBest;
        private final boolean breakDone;

        private Continuation(Stamp stamp, long appliedRevision, List<EntityKey> targets, int targetIndex,
                             EntityKey scanTarget, List<DirectPlanner.BaseCandidate> bases, int baseIndex,
                             boolean supportPhase, DirectPlan directBest, boolean breakDone) {
            this.stamp = stamp;
            this.appliedRevision = appliedRevision;
            this.targets = targets;
            this.targetIndex = targetIndex;
            this.scanTarget = scanTarget;
            this.bases = bases;
            this.baseIndex = baseIndex;
            this.supportPhase = supportPhase;
            this.directBest = directBest;
            this.breakDone = breakDone;
        }

        Stamp stamp() {
            return stamp;
        }

        Continuation advanceTarget() {
            return new Continuation(stamp, appliedRevision, targets, targetIndex + 1, null, List.of(), 0,
                false, null, false);
        }

        Continuation withBreak(DirectPlan plan) {
            return new Continuation(stamp, appliedRevision, targets, targetIndex, null, List.of(), 0, false,
                null, true);
        }

        Continuation startScan(List<DirectPlanner.BaseCandidate> bases) {
            return new Continuation(stamp, appliedRevision, targets, targetIndex,
                targets.get(targetIndex), List.copyOf(bases), 0, false, null, true);
        }

        Continuation withScan(int index, boolean support, DirectPlan best) {
            return new Continuation(stamp, appliedRevision, targets, targetIndex, scanTarget, bases, index,
                support, best, true);
        }
    }
}
