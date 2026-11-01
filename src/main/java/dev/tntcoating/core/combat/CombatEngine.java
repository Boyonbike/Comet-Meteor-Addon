/*
 * Original Comet implementation by Boyonabike.
 * Independent: written from the pinned 26.2 artifact and Meteor build 35 behaviour; no third-party code.
 * SPDX-License-Identifier: CC0-1.0
 */

package dev.tntcoating.core.combat;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/**
 * Phase 3.6 DirectOnly engine. One pass is: read the already-drained world snapshot, resolve the placement
 * sequence, refuse to work while paused, ask the planner for at most one verified action, reserve it through
 * {@link CombatScheduler} and hand it to the single {@link DirectGateway} (production: {@code
 * CombatRuntime.dispatch}). The engine never drains observations, never calls a packet API and never owns a
 * second session: the runtime/controller keep observation and respawn reconciliation exactly once.
 *
 * <p>Conservative caps are one placement and one verified break per tick. A pause refuses new work and
 * keeps every hazard and sent risk; a disabled engine refuses everything. The placement path reserves the
 * spawn hazard and the crystal risk before the placement packet, binds the risk to the unique spawn, and
 * shares that risk with the break so rule 1 retirement applies to the crystal that actually exploded. A
 * rejected or delayed spawn keeps both reservations and the ledger blocks the cell, so the engine cannot
 * retry into it until the hazard resolves.</p>
 */
public final class CombatEngine {
    public static final long DIRECT_PLAN = 2L;
    /** Conservative profile: at most one placement per tick. */
    public static final int MAX_PLACEMENTS_PER_TICK = 1;
    /** Conservative profile: at most one verified break per tick. */
    public static final int MAX_BREAKS_PER_TICK = 1;
    /** A support block that never appears blocks its cell for this many ticks, then is abandoned. */
    public static final int SUPPORT_TIMEOUT_TICKS = 20;
    private static final int PACKET_COST = 2;

    private final CombatSession session;
    private final CombatScheduler scheduler;
    private final CombatRiskBudget risk;
    private final CrystalLedger ledger;
    private volatile DirectSettings settings;
    private volatile DirectPlanner planner;
    private final DirectSequence sequence = new DirectSequence();

    private final Map<EntityKey, Integer> breakAttempts = new HashMap<>();
    private final Map<EntityKey, Integer> lastAttackTick = new HashMap<>();
    private final Map<HazardId, RiskId> hazardRisks = new LinkedHashMap<>();
    private final Map<EntityKey, HazardId> crystalHazards = new HashMap<>();
    private final Set<Cell> abandonedSupports = new HashSet<>();
    /**
     * Hazards that were sent before an action-generation boundary. The risk debt and the ledger hazard
     * stay (rule 1 still retires them on terminal evidence), but a spawn that binds one of these hazards
     * belongs to the old generation and must never be attacked from the new one. Once the hazard resolves
     * (or a spawn binds it, which marks that crystal exhausted for this engine) the entry is dropped.
     */
    private final Set<HazardId> staleHazards = new HashSet<>();

    private EntityKey preferredTarget;
    private int lastToolSwitchTick = -1;
    private int lastTick = -1;
    private int placementsThisTick;
    private int breaksThisTick;
    private EntityKey lastBreakEntity;
    private int lastBreakTick = -1;
    private DirectPause pause;
    private boolean disabled;
    private long nextIntentId = 1L;
    private Cell pendingSupport;
    private int supportSentTick = -1;
    private int placementsSent;
    private int breaksSent;
    private int generationAborts;
    private volatile CandidateCache cache;
    /** Phase 4.6 defensive planner, rebuilt with the settings; never used to bypass a safety gate. */
    private volatile DefensePlanner defensePlanner;
    /** Optional Phase 4.7 metrics tap; null keeps every counter untouched (tests and headless use). */
    private volatile CombatMetrics metrics;

    /** Phase 6: the selected mode. DirectOnly stays the shipped default and its path is unchanged. */
    private volatile Mode mode = Mode.DIRECT_ONLY;
    /** Phase 6.2 per-plan state. Only consulted in Hybrid; never touches risk or timeouts. */
    private final Hysteresis hysteresis = new Hysteresis();
    /** Phase 6.4: ranking-only piston candidates the trap owner offers each Hybrid tick. */
    private List<Opportunity> offeredPlans = List.of();
    /** Phase 6.4: the trap's live plan. While set it blocks every other offensive plan (serialised). */
    private Opportunity committedPlan;
    /** Phase 6.4: Hybrid picked the trap this tick; the trap owner runs it, never this engine. */
    private boolean trapSelected;
    /** Phase 6.1: the last ranking decision, kept for the debug log and for tests. */
    private volatile Choice lastChoice = Choice.empty();
    /** Phase 6.5: cleanup starvation hook. No cleanup intent exists yet, so this only publishes state. */
    private final CleanupPriority cleanupPriority = new CleanupPriority();
    /**
     * Phase 6.3: true whenever the controller reports a live trap, even if the trap's ranking view or
     * footprint cannot be built. The sentinel is deliberately independent of {@link #committedPlan}: a
     * null committed plan is never permission for a direct plan.
     */
    private boolean trapActive;
    /** Phase 6.3: the committed trap footprint, or null when it could not be established. */
    private FootprintConflict.TrapView trapConflictView;
    /** Phase 6.3: one conflict evaluation per direct plan per tick (choose and ranked share it). */
    private final Map<Long, FootprintConflict.Result> concurrencyVerdicts = new HashMap<>();
    /** Phase 6.3: one evidence bucket per tick, so choose+ranked cannot double count. */
    private boolean concurrencyRecorded;
    /** Ranking ids are namespaced per planner so a piston plan id can never alias a direct intent id. */
    private static final long DIRECT_OPPORTUNITY_ID = 0x2000_0000_0000_0000L;

    public CombatEngine(CombatSession session, CombatScheduler scheduler, CombatRiskBudget risk,
                        CrystalLedger ledger, DirectSettings settings, DirectPlanner planner) {
        this(session, scheduler, risk, ledger, settings, planner, new CandidateCache(planner));
    }

    public CombatEngine(CombatSession session, CombatScheduler scheduler, CombatRiskBudget risk,
                        CrystalLedger ledger, DirectSettings settings, DirectPlanner planner,
                        CandidateCache cache) {
        this.session = Objects.requireNonNull(session, "session");
        this.scheduler = Objects.requireNonNull(scheduler, "scheduler");
        this.risk = Objects.requireNonNull(risk, "risk");
        this.ledger = Objects.requireNonNull(ledger, "ledger");
        this.settings = Objects.requireNonNull(settings, "settings");
        this.planner = Objects.requireNonNull(planner, "planner");
        this.cache = Objects.requireNonNull(cache, "cache");
        this.defensePlanner = new DefensePlanner(settings);
    }

    /** Phase 4.7: attach the metrics recorder. The controller does this once when the stack is built. */
    public void attachMetrics(CombatMetrics metrics) {
        this.metrics = metrics;
    }

    public CandidateCache cache() {
        return cache;
    }

    public DirectSettings settings() {
        return settings;
    }

    /**
     * Applies a new immutable settings snapshot to the live engine without rebuilding the runtime stack.
     * The caller must have ended the action generation first; the sequence is deliberately not touched here
     * because hazards and sent risk belong to the runtime and must survive a settings change. A fresh
     * planner and cache are installed so no plan priced under the old settings can be reused.
     */
    public synchronized void reconfigure(DirectSettings next) {
        Objects.requireNonNull(next, "next");
        DirectPlanner nextPlanner = new DirectPlanner(next);
        this.settings = next;
        this.planner = nextPlanner;
        this.cache = new CandidateCache(nextPlanner);
        this.defensePlanner = new DefensePlanner(next);
    }

    /**
     * Phase 6: DirectOnly is the shipped default, PistonOnly leaves this engine off (the controller never
     * ticks it) and Hybrid lets the engine rank direct plans against the piston trap's ranking view. Only
     * candidate selection changes: the scheduler, the risk budget and the single dispatch path are the
     * same in every mode.
     */
    public synchronized void setMode(Mode next) {
        Mode value = Objects.requireNonNull(next, "mode");
        if (value == mode) return;
        mode = value;
        endHybridState();
    }

    public Mode mode() {
        return mode;
    }

    /**
     * Phase 6.4: the piston opportunities the trap owner wants ranked in the next Hybrid tick. Ranking
     * only: this engine never sends them and never touches the trap's hazards or risk reservations.
     */
    public synchronized void offerPlans(List<Opportunity> plans) {
        offeredPlans = plans == null ? List.of() : List.copyOf(plans);
    }

    /**
     * Phase 6.4: the trap's live plan, or null once it finished/aborted. While committed, the serialised
     * rule blocks every other offensive plan; defence keeps its own precedence and is never filtered here.
     */
    public synchronized void setCommittedPlan(Opportunity plan) {
        if (plan == null) {
            committedPlan = null;
            // The trap finished or aborted: a live trap plan is released so the next plan may be adopted
            // without the "live structures" hold. Risk and hazards stay with the trap owner.
            if (hysteresis.current().map(Opportunity::strategy).orElse(Strategy.DIRECT)
                == Strategy.PISTON) {
                hysteresis.actionFinished();
            }
            return;
        }
        if (committedPlan != null && committedPlan.id() != plan.id()) hysteresis.actionFinished();
        committedPlan = plan;
        hysteresis.markLive();
    }

    /**
     * Phase 6.3: reports the live trap and its footprint. A null view still marks the trap active: direct
     * plans then wait because compatibility cannot be established. Called by the controller every Hybrid
     * tick before the engine ranks.
     */
    public synchronized void setActiveTrap(FootprintConflict.TrapView view) {
        trapActive = true;
        trapConflictView = view;
    }

    /** Clears the Phase 6.3 trap context: no live trap owns the offensive field. */
    public synchronized void clearActiveTrap() {
        trapActive = false;
        trapConflictView = null;
        concurrencyVerdicts.clear();
        concurrencyRecorded = false;
    }

    /** True while the controller reports a live trap, even when its ranking view is unavailable. */
    public boolean trapActive() {
        return trapActive;
    }

    /** True when Hybrid chose the trap for this tick: the trap owner must run it, not this engine. */
    public boolean trapSelected() {
        return trapSelected;
    }

    /** Phase 6.1: winner and every rejection reason of the last Hybrid decision. */
    public Choice lastChoice() {
        return lastChoice;
    }

    /** Phase 6.5: the cleanup starvation hook; scheduling state only, it never sends. */
    public CleanupPriority cleanupPriority() {
        return cleanupPriority;
    }

    public DirectSequence sequence() {
        return sequence;
    }

    public DirectPause pause() {
        return pause;
    }

    public EntityKey preferredTarget() {
        return preferredTarget;
    }

    public int placementsSent() {
        return placementsSent;
    }

    public int breaksSent() {
        return breaksSent;
    }

    public int attempts(EntityKey crystal) {
        return breakAttempts.getOrDefault(crystal, 0);
    }

    public int lastAttackTick(EntityKey crystal) {
        return lastAttackTick.getOrDefault(crystal, -1);
    }

    public boolean disabled() {
        return disabled;
    }

    /** How many generation boundaries actually aborted an active sequence; for exactly-once tests. */
    public int generationAborts() {
        return generationAborts;
    }

    /** Stops all sends immediately; observed risk and hazards are untouched. */
    public void onDisabled() {
        disabled = true;
        onGenerationEnded();
    }

    /** Re-enables the engine and guarantees it starts from IDLE, never from a pre-disable sequence. */
    public void onEnabled() {
        disabled = false;
        if (sequence.isAborted()) sequence.reset();
        if (!sequence.isIdle()) sequence.abort("engine re-enabled");
        if (sequence.isAborted()) sequence.reset();
    }

    /**
     * Ends the engine's action generation. The active placement sequence is aborted so no old reserved
     * crystal can be attacked after re-enable; the spawn hazard and every sent risk stay untouched, as do
     * the ledger hazard maps and attempt counts. Only unsent planner bookkeeping (pending support, per-tick
     * caps, sticky target) is cleared. Called exactly once per generation boundary by the controller.
     */
    public void onGenerationEnded() {
        pause = null;
        pendingSupport = null;
        supportSentTick = -1;
        abandonedSupports.clear();
        placementsThisTick = 0;
        breaksThisTick = 0;
        lastBreakEntity = null;
        lastBreakTick = -1;
        preferredTarget = null;
        endHybridState();
        if (sequence.entity() != null) {
            // The old generation's crystal can never be attacked from the new one, even after a spawn
            // binds it later; exhausting the attempt count keeps the planner from proposing it.
            breakAttempts.put(sequence.entity(), Integer.MAX_VALUE);
        }
        if (!hazardRisks.isEmpty()) {
            staleHazards.addAll(hazardRisks.keySet());
            hazardRisks.clear();
            crystalHazards.clear();
        }
        if (sequence.state() == DirectSequence.State.REMOVAL_OBSERVED) {
            sequence.finish();
        } else if (!sequence.isIdle()
            && sequence.abort("action generation ended")) {
            generationAborts++;
        }
    }

    /** Ends every per-plan Hybrid state: a generation boundary must never carry a challenge over. */
    private void endHybridState() {
        hysteresis.reset();
        offeredPlans = List.of();
        committedPlan = null;
        trapSelected = false;
        lastChoice = Choice.empty();
        cleanupPriority.clear();
        clearActiveTrap();
    }

    /**
     * One engine pass, after {@code CombatRuntime.tick} has drained and applied observations. The snapshot
     * must be the current one; the engine applies nothing itself.
     */
    public void tick(int serverTick, DirectWorld world, Snapshot snapshot, DirectGateway gateway) {
        tick(serverTick, world, snapshot, gateway, TickBudget.unlimited());
    }

    /**
     * Budgeted engine pass: reconciliation always runs; optional planning only consumes what the shared
     * tick budget leaves and otherwise leaves a resumable continuation for the next tick.
     */
    public void tick(int serverTick, DirectWorld world, Snapshot snapshot, DirectGateway gateway,
                     TickBudget budget) {
        if (disabled || !session.isOpen()) return;
        if (budget == null) budget = TickBudget.unlimited();
        cache.onSession(session.connection().epoch(), session.config());
        if (world == null || snapshot == null || gateway == null) return;
        if (serverTick < 0) return;
        if (lastTick >= 0 && serverTick < lastTick) {
            throw new IllegalArgumentException("server tick went backwards: " + serverTick);
        }
        lastTick = serverTick;
        placementsThisTick = 0;
        breaksThisTick = 0;

        reconcileStaleHazards();
        boolean resolved = resolveSequence(serverTick, world, snapshot);
        pause = pauseReason(serverTick, world).orElse(null);
        if (pause != null || resolved) return;

        settlePendingSupport(serverTick, world);
        if (mode == Mode.HYBRID) {
            hybridTick(serverTick, world, snapshot, gateway, budget);
            return;
        }
        // Phase 4.6: an imminent threat (arrival within IMMINENT_TICKS) outranks offence. A non-imminent
        // defensive opportunity only runs when offence sends nothing this tick.
        Optional<DefensePlanner.Choice> defence = defensiveChoice(serverTick, world);
        boolean imminent = defence.isPresent()
            && defence.get().arrivalTicks() <= DefensePlanner.IMMINENT_TICKS;
        if (imminent && dispatchDefence(defence.get().plan(), serverTick, world, snapshot, gateway)) {
            return;
        }
        if (planAndDispatch(serverTick, world, snapshot, gateway, null, budget)) return;
        if (defence.isPresent() && !imminent) {
            dispatchDefence(defence.get().plan(), serverTick, world, snapshot, gateway);
        }
    }

    /** Applied-observation tap: only affected candidates are invalidated by the runtime/controller. */
    public void onObservationApplied(Observation observation) {
        cache.onObservationApplied(observation);
    }

    public void onBlockUpdated(Cell cell) {
        cache.onBlockUpdated(cell);
    }

    public void onTargetChanged(int entityId) {
        cache.onTargetChanged(entityId);
    }

    public void onCrystalSpawned() {
        cache.onCrystalSpawned();
    }

    public void onCrystalRemoved() {
        cache.onCrystalRemoved();
    }

    /** Target-set change (non-crystal spawn/removal/death/respawn/status): stale target choices drop. */
    public void onTargetSetChanged() {
        cache.onTargetSetChanged();
    }

    public void onOverlayChanged() {
        cache.onOverlayChanged();
    }

    /**
     * Event path for a freshly observed spawn. The controller calls this after the ledger observed the
     * entity; the unique matching spawn binds the sequence and the attack is proposed in this same
     * client-thread task. It is still one pass through reserve/dispatch; a tick pass in the same tick can
     * never repeat the attack because the break budget is shared.
     */
    public void onSpawnObserved(EntityKey crystal, DirectWorld world, Snapshot snapshot,
                                DirectGateway gateway) {
        onSpawnObserved(crystal, world, snapshot, gateway, TickBudget.unlimited());
    }

    public void onSpawnObserved(EntityKey crystal, DirectWorld world, Snapshot snapshot,
                                DirectGateway gateway, TickBudget budget) {
        if (disabled || !session.isOpen()) return;
        if (budget == null) budget = TickBudget.unlimited();
        cache.onSession(session.connection().epoch(), session.config());
        if (crystal == null || world == null || snapshot == null || gateway == null) return;
        if (lastTick < 0) return;
        int serverTick = lastTick;
        if (breaksThisTick >= MAX_BREAKS_PER_TICK) return;
        resolveSequence(serverTick, world, snapshot);
        if (!sequence.owns(crystal)) return;
        // One event attack per bound spawn: a duplicate after-application callback for the same entity must
        // not become a second attack, and the next tick's normal pass owns any legitimate retry.
        if (sequence.state() != DirectSequence.State.SPAWN_OBSERVED) return;
        Optional<DirectPause> activePause = pauseReason(serverTick, world);
        if (activePause.isPresent()) {
            pause = activePause.get();
            return;
        }
        planAndDispatch(serverTick, world, snapshot, gateway, crystal, budget);
    }

    // ------------------------------------------------------------------ sequence

    /**
     * A hazard that predates the current action generation must never produce an attack even if its spawn
     * arrives later. A bound stale spawn marks that crystal's attempt count exhausted; a terminal/quarantined
     * hazard is dropped. This is bookkeeping only: the hazard and its risk debt stay in the ledger/budget.
     */
    private void reconcileStaleHazards() {
        java.util.Iterator<HazardId> iterator = staleHazards.iterator();
        while (iterator.hasNext()) {
            HazardId hazard = iterator.next();
            Optional<EntityKey> bound = ledger.bound(hazard);
            if (bound.isPresent()) {
                breakAttempts.put(bound.get(), Integer.MAX_VALUE);
                iterator.remove();
                continue;
            }
            CrystalState state;
            try {
                state = ledger.stateOf(hazard);
            } catch (IllegalArgumentException gone) {
                // The connection epoch that owned the hazard was closed (dimension/connection boundary);
                // there is no ledger state left to reconcile, only the risk budget's debt.
                iterator.remove();
                continue;
            }
            if (state == CrystalState.REMOVED || state == CrystalState.EXPLODED
                || state == CrystalState.QUARANTINED) {
                iterator.remove();
            }
        }
    }

    private boolean resolveSequence(int serverTick, DirectWorld world, Snapshot snapshot) {
        boolean resolved = false;
        if (sequence.state() == DirectSequence.State.PLACE_SENT) {
            HazardId hazard = sequence.hazard();
            if (sequence.entity() == null) {
                Optional<EntityKey> bound = ledger.bound(hazard);
                if (bound.isEmpty() && ledger.stateOf(hazard) == CrystalState.QUARANTINED) {
                    sequence.abort("spawn quarantined");
                }
                if (bound.isPresent()) {
                    EntityKey crystal = bound.get();
                    DirectCrystal observed = findCrystal(world, crystal);
                    Vec position = observed != null ? observed.position() : null;
                    if (position != null && sequence.bind(crystal, position)) {
                        risk.bindCrystal(sequence.risk(), crystal, position);
                        hazardRisks.put(hazard, sequence.risk());
                        crystalHazards.put(crystal, hazard);
                        breakAttempts.putIfAbsent(crystal, 0);
                    }
                }
            }
        }

        if (sequence.entity() != null) {
            CrystalState crystalState = ledger.state(sequence.entity());
            if (crystalState == CrystalState.REMOVED || crystalState == CrystalState.EXPLODED) {
                if (sequence.state() == DirectSequence.State.SPAWN_OBSERVED
                    || sequence.state() == DirectSequence.State.ATTACK_SENT) {
                    sequence.removalObserved(sequence.entity(), serverTick);
                }
            }
        }

        if (sequence.state() == DirectSequence.State.REMOVAL_OBSERVED) {
            EntityKey crystal = sequence.entity();
            HazardId hazard = sequence.hazard();
            if (hazard != null) hazardRisks.remove(hazard);
            if (crystal != null) {
                crystalHazards.remove(crystal);
                breakAttempts.remove(crystal);
            }
            sequence.finish();
            hysteresis.actionFinished();
            resolved = true;
        } else if (sequence.isAborted()) {
            sequence.reset();
            hysteresis.actionFinished();
            resolved = true;
        }
        return resolved;
    }

    // ------------------------------------------------------------------ planning and dispatch

    /** @return true when an offence action was sent, false when the tick sent nothing. */
    private boolean planAndDispatch(int serverTick, DirectWorld world, Snapshot snapshot,
                                    DirectGateway gateway, EntityKey onlyCrystal, TickBudget budget) {
        Optional<DirectPlan> planned = findDirectPlan(serverTick, world, snapshot, onlyCrystal, budget);
        if (planned.isEmpty()) return false;
        return dispatchDirect(planned.get(), serverTick, world, snapshot, gateway);
    }

    /**
     * Plans and reprices at most one direct action for this tick without reserving or sending. Phase 6.4
     * Hybrid uses this half to rank a direct proposal against the trap; DirectOnly and PistonOnly call it
     * from {@link #planAndDispatch} exactly where the old inline code ran, so their behaviour is unchanged.
     */
    private Optional<DirectPlan> findDirectPlan(int serverTick, DirectWorld world, Snapshot snapshot,
                                                EntityKey onlyCrystal, TickBudget budget) {
        if (placementsThisTick >= MAX_PLACEMENTS_PER_TICK && breaksThisTick >= MAX_BREAKS_PER_TICK) {
            return Optional.empty();
        }
        DirectPlanState state = new DirectPlanState(DIRECT_PLAN, nextIntentId, serverTick,
            preferredTarget, breakAttempts, lastToolSwitchTick);
        Optional<DirectPlan> planned;
        long appliedRevision = snapshot.appliedRevision();
        if (onlyCrystal != null) {
            // Spawn event path: force this exact owned-crystal break instead of filtering the general
            // planner, so a higher-scoring placement can never starve the just-observed attack.
            planned = cache.breakPlan(world, state, onlyCrystal, appliedRevision, budget);
        } else if (sequence.state() == DirectSequence.State.SPAWN_OBSERVED
            && sequence.entity() != null) {
            // A bound spawn is always attacked before anything else; this is the only deferral point (when
            // the shared break cap or a pause already consumed this tick) and the next tick retries here.
            planned = cache.breakPlan(world, state, sequence.entity(), appliedRevision, budget);
        } else {
            planned = cache.plan(world, state, appliedRevision, budget);
        }
        if (planned.isEmpty()) return Optional.empty();
        DirectPlan plan = planned.get();
        // A cache hit is only a ranking hint: reprice the chosen action against the current world so a
        // plan-time estimate/exposure (self, friend or crystal movement) can never be reserved or sent.
        // An empty or mismatched reprice drops the action for this tick instead of dispatching stale data.
        budget.spend(TickBudget.Phase.PLANNING);
        plan = reprice(plan, world, state);
        if (plan == null) return Optional.empty();
        if (onlyCrystal != null && !onlyCrystal.equals(plan.entity())) return Optional.empty();
        if (plan.isPlacement()) {
            if (!sequence.isIdle()) return Optional.empty();
            if (placementsThisTick >= MAX_PLACEMENTS_PER_TICK) return Optional.empty();
            if (plan.action() == Action.PLACE_SUPPORT) {
                if (pendingSupport != null || abandonedSupports.contains(plan.intent().cell())) {
                    return Optional.empty();
                }
            }
        } else {
            if (breaksThisTick >= MAX_BREAKS_PER_TICK) return Optional.empty();
            if (lastBreakTick == serverTick && Objects.equals(lastBreakEntity, plan.entity())) {
                return Optional.empty();
            }
        }

        // Phase 4.3: a hurt-sync wait is not an action. Return before reserving or sending; the
        // sequence, any sent hazard/risk and every other engine field stay untouched. The planner is
        // re-run next tick, so the send still goes through this single dispatch path once.
        if (plan.action() == Action.ATTACK_CRYSTAL && plan.context().deferUntilTick() > serverTick) {
            pause = DirectPause.HURT_SYNC;
            return Optional.empty();
        }
        return Optional.of(plan);
    }

    /** Reserves and dispatches one already-priced direct plan through the single gateway. */
    private boolean dispatchDirect(DirectPlan plan, int serverTick, DirectWorld world,
                                   Snapshot snapshot, DirectGateway gateway) {
        Optional<RiskId> riskId = reserve(plan, serverTick, world, snapshot);
        if (riskId.isEmpty()) return false;
        boolean supportDispatched = false;
        HazardId hazard = null;
        PlacementId placement = null;
        if (plan.action() == Action.PLACE_CRYSTAL) {
            hazard = ledger.reserveSpawn(session.connection(), Box.fromCell(plan.intent().cell()),
                Box.fromCell(plan.intent().cell()).expand(1.0), baseline(snapshot));
            placement = ledger.reservePlacement(session.connection(), plan.intent().cell(),
                DirectPlanner.END_CRYSTAL);
        } else if (plan.action() == Action.PLACE_SUPPORT) {
            supportDispatched = true;
        }

        Ticket ticket = session.ticket(DIRECT_PLAN);
        DirectGateway.DirectSendResult result;
        try {
            result = gateway.send(new DirectDispatch(plan.intent(), plan.context(), ticket, DIRECT_PLAN, world.safetyState()));
        } catch (RuntimeException failure) {
            result = DirectGateway.DirectSendResult.FAILED;
        }

        if (result == DirectGateway.DirectSendResult.SENT) {
            nextIntentId++;
            onSent(plan, serverTick, riskId.get(), hazard, placement, world);
            return true;
        }

        scheduler.releaseIntent(DIRECT_PLAN, plan.intent().id());
        if (hazard != null) ledger.cancelUnsent(hazard);
        if (placement != null) ledger.cancelUnsentPlacement(placement);
        if (supportDispatched) abandonedSupports.add(plan.intent().cell());
        return false;
    }

    // ------------------------------------------------------------------ hybrid (Phase 6.1/6.4)

    /**
     * Phase 6.4 serialised Hybrid. Defensive precedence does not change: an imminent threat is dispatched
     * before any offensive ranking, and a non-imminent defensive opportunity keeps its old fallback slot.
     * The offensive candidates are this engine's repriced direct plan, the trap's ranking view and, while
     * a trap plan is live, that committed plan. The engine never sends the trap: it only sets
     * {@link #trapSelected()} so the trap owner can run its own serialised sequence. Phase 6.3
     * footprint-scoped concurrency is off by default: while it is off, a live trap serialises every other
     * offensive plan. With {@code settings.footprintConcurrency()} on, a direct plan is admissible only
     * when {@link FootprintConflict} proves every 6.3 row compatible; an unavailable trap footprint is a
     * conflict, never a permit.
     */
    private void hybridTick(int serverTick, DirectWorld world, Snapshot snapshot,
                            DirectGateway gateway, TickBudget budget) {
        trapSelected = false;
        concurrencyVerdicts.clear();
        concurrencyRecorded = false;
        cleanupPriority.sample(serverTick);
        Optional<DefensePlanner.Choice> defence = defensiveChoice(serverTick, world);
        boolean imminent = defence.isPresent()
            && defence.get().arrivalTicks() <= DefensePlanner.IMMINENT_TICKS;
        if (imminent && dispatchDefence(defence.get().plan(), serverTick, world, snapshot, gateway)) {
            return;
        }

        Optional<DirectPlan> direct = findDirectPlan(serverTick, world, snapshot, null, budget);
        Map<Long, DirectPlan> directPlans = new HashMap<>();
        List<Opportunity> candidates = new ArrayList<>();
        if (direct.isPresent()) {
            Opportunity view = directOpportunity(direct.get());
            directPlans.put(view.id(), direct.get());
            candidates.add(view);
        }
        candidates.addAll(offeredPlans);
        if (committedPlan != null) candidates.add(committedPlan);

        HybridGate gate = new HybridGate(world, directPlans);
        Choice choice = CombatChoice.choose(candidates, snapshot, gate);
        List<CombatChoice.Ranked> ranked = CombatChoice.ranked(candidates, gate);
        Hysteresis.Decision decision = hysteresis.advance(serverTick, ranked, safetyInvalidated(gate));
        lastChoice = choice;
        recordChoice(choice, decision);

        if (cleanupPriority.prioritise()) {
            // Cleanup has waited its starvation window and its installed feasibility probe confirms the
            // trap can actually reserve the cleanup packet this tick, so cleanup owns the next free slot.
            // The engine still dispatches a non-imminent defence (cleanup is never a defence substitute),
            // then yields so the trap's own serialised cleanup dispatch can claim this tick. When the
            // probe refuses (budget or slot), prioritise() is false and direct offence runs below instead.
            if (defence.isPresent() && !imminent) {
                if (committedPlan != null) recordDefenceDuringTrap();
                dispatchDefence(defence.get().plan(), serverTick, world, snapshot, gateway);
            }
            return;
        }

        Opportunity winner = decision.plan().orElse(null);
        if (winner != null && winner.strategy() == Strategy.PISTON) {
            // The trap owns its own sends, hazards and risk reservations: this engine only hands the tick
            // to it. A committed trap plan is live (its structures exist), so it can never be scored away
            // from; defence is still evaluated and dispatched, never serialised behind the trap.
            if (committedPlan != null && winner.id() == committedPlan.id()) hysteresis.markLive();
            trapSelected = true;
            if (committedPlan != null) recordTrapBlockedDirect(choice);
            if (defence.isPresent() && !imminent) {
                if (committedPlan != null) recordDefenceDuringTrap();
                dispatchDefence(defence.get().plan(), serverTick, world, snapshot, gateway);
            }
            return;
        }

        DirectPlan chosen = winner == null ? null : directPlans.get(winner.id());
        if (chosen != null && dispatchDirect(chosen, serverTick, world, snapshot, gateway)) {
            if (chosen.action() == Action.PLACE_CRYSTAL) hysteresis.irreversibleActionSent();
            return;
        }
        if (defence.isPresent() && !imminent) {
            if (committedPlan != null) recordDefenceDuringTrap();
            dispatchDefence(defence.get().plan(), serverTick, world, snapshot, gateway);
        }
    }

    /**
     * Phase 6.1 hard filters. Policy and risk are re-checked here even though each planner already applied
     * them: a direct plan and a trap plan are priced by different planners and must not disagree about the
     * safety room that is left. Any gate that cannot be answered returns {@code false}.
     */
    private final class HybridGate implements CombatChoice.Gate {
        private final DirectWorld world;
        private final Map<Long, DirectPlan> directPlans;

        private HybridGate(DirectWorld world, Map<Long, DirectPlan> directPlans) {
            this.world = world;
            this.directPlans = directPlans == null ? Map.of() : directPlans;
        }

        @Override
        public boolean policyAllows(Opportunity opportunity) {
            SafetyState state = world.safetyState();
            if (state == null || state.selfHealth() == null || state.safety() == null) return false;
            if (state.conn() == null || !state.conn().equals(session.connection())) {
                return false;
            }
            DirectPlan plan = directPlans.get(opportunity.id());
            if (plan != null) {
                DirectContext context = plan.context();
                return new CombatDamagePolicy(state.selfHealth(), context.targetHealth())
                    .allows(context.estimate(), context.exposure(), state.safety(),
                        context.targetOverride(), context.faceplace());
            }
            // Piston ranking view: the trap planner's target floor already cleared, so the same policy is
            // applied to the plan's own exposure and estimate without inventing a target-health reading.
            return new CombatDamagePolicy(state.selfHealth(), noTarget())
                .allows(opportunity.targetDamage(), opportunity.exposure(), state.safety());
        }

        @Override
        public boolean riskAllows(Opportunity opportunity) {
            // P6.1 gap fix: the hybrid filter must enforce exactly what the reservation would enforce,
            // including the per-friend ceiling and KNOWN-friend survival. CombatRiskGate is the shared,
            // read-only mirror of CombatRiskBudget.reserve; it never fabricates a health for an
            // unknown/censored friend (ceiling only).
            SafetyState state = world.safetyState();
            if (state == null || opportunity.exposure() == null) return false;
            return CombatRiskGate.allows(risk, world.self(), state, opportunity.exposure());
        }

        @Override
        public boolean ledgerAllows(Opportunity opportunity) {
            // The trap owner admits its own plan through its own reserve path before it offers it here, and
            // this engine never sends it. Re-applying ledger exclusions to the owner's own reservations
            // would reject the committed trap for the very cells and crystal it just reserved. The
            // exemption is keyed on the exact committed plan object, never on a strategy or a footprint, so
            // a foreign plan that would attack the same protected crystal still takes the full check.
            if (isCommittedPlan(opportunity)) return true;
            Set<Intent> plan = opportunity.plan().isEmpty() ? Set.of()
                : new HashSet<>(opportunity.plan());
            for (Intent intent : plan) {
                if (intent == null) return false;
                if (intent.cell() != null && ledger.blocksCell(intent.cell())) return false;
                for (Cell cell : intent.blockLocks()) {
                    if (cell != null && ledger.blocksCell(cell)) return false;
                }
                for (EntityKey entity : intent.entityLocks()) {
                    CrystalState state = ledger.state(entity);
                    if (state == CrystalState.QUARANTINED) return false;
                }
            }
            for (Cell cell : opportunity.footprint().structural()) {
                if (cell != null && ledger.blocksCell(cell)) return false;
            }
            for (EntityKey entity : opportunity.footprint().bound()) {
                if (entity == null || ledger.protects(entity.uuid())) return false;
            }
            return true;
        }

        /** True when this opportunity is the plan the trap owner has already committed to. */
        private boolean isCommittedPlan(Opportunity opportunity) {
            return committedPlan != null && opportunity.strategy() == Strategy.PISTON
                && opportunity.id() == committedPlan.id();
        }

        @Override
        public boolean committedPlanAllows(Opportunity opportunity) {
            if (isCommittedPlan(opportunity)) return true;
            if (!trapActive && committedPlan == null) return true;
            // Phase 6.3 sentinel: a live trap owns the offensive field. With concurrency off (the shipped
            // default) nothing but the trap's own plan passes; with it on, a direct plan passes only
            // through a positive FootprintConflict result. A null committed plan / null trap view is
            // never a permit.
            if (opportunity.strategy() != Strategy.DIRECT) return false;
            if (!settings.footprintConcurrency()) {
                recordConcurrency("setting", false);
                return false;
            }
            DirectPlan plan = directPlans.get(opportunity.id());
            if (plan == null) {
                recordConcurrency("uncached", false);
                return false;
            }
            FootprintConflict.Result verdict = concurrencyVerdict(plan);
            return verdict.compatible();
        }

        /** The single Phase 6.3 evaluation point for a direct plan; cached per tick. */
        private FootprintConflict.Result concurrencyVerdict(DirectPlan plan) {
            long key = plan.intent().id();
            FootprintConflict.Result cached = concurrencyVerdicts.get(key);
            if (cached != null) return cached;
            FootprintConflict.Result result = FootprintConflict.check(directView(plan),
                trapConflictView, environment(plan));
            concurrencyVerdicts.put(key, result);
            if (result.compatible()) {
                recordConcurrency("compatible", true);
            } else {
                recordConcurrency(result.row().name(), false);
            }
            return result;
        }

        private FootprintConflict.DirectView directView(DirectPlan plan) {
            DirectContext context = plan.context();
            Intent intent = plan.intent();
            Vec explosion = FootprintConflict.explosive(plan.action()) && intent.cell() != null
                ? intent.cell().center() : null;
            // Mirror CombatEngine.reserve exactly: the slot is needed only when the planner's expected
            // slot differs from the current one; rotation is gated by the same setting.
            boolean needsSlot = context.hotbarSlot() != world.selectedSlot();
            return new FootprintConflict.DirectView(plan.action(), explosion, footprint(intent),
                needsSlot, settings.rotationsOn());
        }

        private FootprintConflict.Environment environment(DirectPlan plan) {
            FootprintConflict.TrapView view = trapConflictView;
            boolean slotHeld = scheduler.hotbarSlotHeld() || (view != null && view.needsSlot());
            boolean rotationHeld = scheduler.rotationHeld() || (view != null && view.needsRotation());
            boolean combinedRiskFits = combinedRiskFits(plan, view);
            MotionSample motion = view == null || view.target() == null ? null : world.motion(view.target());
            return new FootprintConflict.Environment(slotHeld, rotationHeld, combinedRiskFits,
                motion, world.collision());
        }

        private boolean combinedRiskFits(DirectPlan plan, FootprintConflict.TrapView view) {
            SafetyState state = world.safetyState();
            if (view == null || view.exposure() == null || state == null
                || plan.context().exposure() == null) {
                return false;
            }
            // Conservative: the trap's own reservation may or may not already be in the budget debt, so
            // the combined bound is always checked as if both reservations were pending. Over-counting
            // can only serialise earlier, never admit an unsafe pair.
            return CombatRiskGate.allows(risk, world.self(), state,
                CombatRiskGate.combine(plan.context().exposure(), view.exposure()));
        }

        private void recordConcurrency(String bucket, boolean admitted) {
            if (concurrencyRecorded) return;
            concurrencyRecorded = true;
            CombatMetrics recorder = metrics;
            if (recorder == null) return;
            if (admitted) {
                recorder.recordConcurrencyAdmitted(bucket);
            } else {
                recorder.recordConcurrencySerialised(bucket);
            }
        }

        @Override
        public double targetHealthUpper(EntityKey target) {
            DirectTarget known = findTarget(world, target);
            if (known == null || known.health() == null
                || known.health().cap() != Capability.KNOWN) {
                return Double.POSITIVE_INFINITY;
            }
            double value = known.health().value();
            return Double.isFinite(value) ? Math.max(0.0, value) : Double.POSITIVE_INFINITY;
        }
    }

    /** Phase 6.1 ranking view of an already-repriced direct plan; never a second send path. */
    private Opportunity directOpportunity(DirectPlan plan) {
        DirectContext context = plan.context();
        Intent intent = plan.intent();
        Vec explosion = intent.cell() == null ? null : intent.cell().center();
        return new Opportunity(DIRECT_OPPORTUNITY_ID + intent.id(), Strategy.DIRECT, plan.target(),
            explosion, context.estimate(), context.exposure(), 1, context.estimate().expected(),
            plan.actionCost(), List.of(intent), context.confidence(), footprint(intent));
    }

    /**
     * Phase 6.2 "safety invalidation overrides hysteresis immediately". The hysteresis hold is void as soon
     * as policy or ledger evidence says the current plan is no longer safe, whether or not it has reached
     * its first irreversible action: waiting out the switch margin would keep an unsafe plan eligible.
     * Only direct plans are tested here; the trap plan is excluded because its owner invalidates it and
     * keeps its hazards and risk reservations. Nothing in this method retires risk.
     */
    boolean safetyInvalidated(CombatChoice.Gate gate) {
        Optional<Opportunity> current = hysteresis.current();
        if (current.isEmpty()) return false;
        Opportunity plan = current.get();
        if (plan.strategy() != Strategy.DIRECT) return false;
        return !gate.ledgerAllows(plan) || !gate.policyAllows(plan);
    }

    /** Phase 6.2 hysteresis state, exposed for the invalidation tests; not a control surface. */
    Hysteresis hysteresis() {
        return hysteresis;
    }

    private void recordChoice(Choice choice, Hysteresis.Decision decision) {
        CombatMetrics recorder = metrics;
        if (recorder == null) return;
        recorder.recordChoiceMode(mode.name());
        recorder.recordChoiceWinner(choice.winner().isPresent()
            ? (choice.lethal() ? "lethal" : "utility") : "none");
        for (Choice.Rejection rejection : choice.rejected()) {
            recorder.recordChoiceRejection(rejection.reason().name());
        }
        if (decision.held()) recorder.recordHysteresisHold();
        if (decision.adopted()) {
            recorder.recordHysteresisSwitch(decision.invalidated() ? "invalidated" : "score");
        }
        CleanupPriority.State cleanup = cleanupPriority.state();
        if (cleanup.waiting()) {
            recorder.recordCleanupWait(cleanup.waitedTicks());
            if (cleanup.due()) recorder.recordCleanupStarved();
        }
        if (cleanup.due()) cleanupPriority.prioritise();
    }

    private void recordTrapBlockedDirect(Choice choice) {
        CombatMetrics recorder = metrics;
        if (recorder == null) return;
        for (Choice.Rejection rejection : choice.rejected()) {
            if (rejection.reason() == Choice.Reason.COMMITTED
                && rejection.strategy() == Strategy.DIRECT) {
                recorder.recordTrapBlockedDirect();
            }
        }
    }

    private void recordDefenceDuringTrap() {
        CombatMetrics recorder = metrics;
        if (recorder != null) recorder.recordDefenceDuringTrap();
    }

    // ------------------------------------------------------------------ defence (Phase 4.6)

    private boolean defenceEnabled() {
        return settings.dangerousCrystalBreak() || settings.antiPiston();
    }

    /**
     * Phase 4.6: a defensive opportunity is a candidate for the tick; an active placement sequence keeps
     * priority because the engine never abandons its own bound spawn for defence.
     */
    private Optional<DefensePlanner.Choice> defensiveChoice(int serverTick, DirectWorld world) {
        if (!defenceEnabled() || !sequence.isIdle()) return Optional.empty();
        DirectPlanState state = new DirectPlanState(DIRECT_PLAN, nextIntentId, serverTick,
            preferredTarget, breakAttempts, lastToolSwitchTick);
        double currentSelfBound = risk.current(world.self()).value();
        return defensePlanner.choose(world, state, currentSelfBound);
    }

    /**
     * Phase 4.6: dispatches an already-selected defensive plan. A send returns true and the tick ends; a plan
     * that cannot be reserved or dispatched releases its unsent lease and falls through to normal offence in
     * the same tick, so a blocked defensive attempt is not a wasted tick.
     */
    private boolean dispatchDefence(DirectPlan plan, int serverTick, DirectWorld world,
                                    Snapshot snapshot, DirectGateway gateway) {
        if (!defenceEnabled() || !sequence.isIdle()) return false;
        if (plan.isPlacement()) {
            if (placementsThisTick >= MAX_PLACEMENTS_PER_TICK) return false;
        } else {
            if (breaksThisTick >= MAX_BREAKS_PER_TICK) return false;
            if (lastBreakTick == serverTick && Objects.equals(lastBreakEntity, plan.entity())) {
                return false;
            }
        }

        Optional<RiskId> riskId = reserve(plan, serverTick, world, snapshot);
        if (riskId.isEmpty()) return false;
        Ticket ticket = session.ticket(DIRECT_PLAN);
        DirectGateway.DirectSendResult result;
        try {
            result = gateway.send(new DirectDispatch(plan.intent(), plan.context(), ticket, DIRECT_PLAN,
                world.safetyState()));
        } catch (RuntimeException failure) {
            result = DirectGateway.DirectSendResult.FAILED;
        }
        if (result != DirectGateway.DirectSendResult.SENT) {
            scheduler.releaseIntent(DIRECT_PLAN, plan.intent().id());
            return false;
        }
        nextIntentId++;
        onDefensiveSent(plan, serverTick, world);
        return true;
    }

    /**
     * Defensive sends deliberately skip the offence bookkeeping: no sequence, no ledger spawn/placement
     * reservation, no hazard-risk association and no sticky target. A blocker is a plain placement and is
     * not a detonation, so it never carries a crystal risk. Only the attempt count, per-tick caps and the
     * Phase 4.7 defence counters change.
     */
    private void onDefensiveSent(DirectPlan plan, int serverTick, DirectWorld world) {
        if (plan.action() == Action.ATTACK_CRYSTAL) {
            breakAttempts.merge(plan.entity(), 1, Integer::sum);
            lastBreakEntity = plan.entity();
            lastBreakTick = serverTick;
            breaksThisTick++;
            breaksSent++;
            if (plan.context().hotbarSlot() != world.selectedSlot()) {
                lastToolSwitchTick = serverTick;
            }
            recordDefence("break_crystal");
        } else if (plan.action() == Action.PLACE_BLOCKER) {
            placementsThisTick++;
            placementsSent++;
            recordDefence("place_blocker");
        }
        scheduler.releaseSent(DIRECT_PLAN, plan.intent().id());
    }

    private void recordDefence(String type) {
        CombatMetrics recorder = metrics;
        if (recorder != null) recorder.recordDefenceAction(type);
    }

    /**
     * Re-runs the planner for the chosen action on the current snapshot. The cached plan only decides
     * <em>what</em> to attempt; the fresh plan supplies the context (estimate, exposure, faceplace, tool)
     * that is reserved and dispatched. A vanished/moved target, base, crystal or a changed policy yields
     * {@code null} and the tick sends nothing.
     */
    private DirectPlan reprice(DirectPlan planned, DirectWorld world, DirectPlanState state) {
        if (planned.context().defensive()) {
            Optional<DirectPlan> fresh = defensePlanner.reprice(planned, world, state,
                risk.current(world.self()).value());
            if (fresh.isEmpty()) return null;
            DirectPlan repriced = fresh.get();
            if (repriced.action() != planned.action()) return null;
            if (repriced.action() == Action.ATTACK_CRYSTAL) {
                return Objects.equals(repriced.entity(), planned.entity()) ? repriced : null;
            }
            return Objects.equals(repriced.intent().cell(), planned.intent().cell()) ? repriced : null;
        }
        if (planned.action() == Action.ATTACK_CRYSTAL) {
            Optional<DirectPlan> fresh = planner.planBreak(world, state, planned.entity());
            if (fresh.isEmpty()) return null;
            DirectPlan repriced = fresh.get();
            if (repriced.action() != planned.action()) return null;
            if (!Objects.equals(repriced.entity(), planned.entity())) return null;
            return repriced;
        }
        Cell cell = planned.intent().cell();
        if (cell == null) return null;
        DirectTarget target = null;
        for (DirectTarget candidate : world.targets()) {
            if (Objects.equals(candidate.entity(), planned.target())) {
                target = candidate;
                break;
            }
        }
        if (target == null) return null;
        boolean support = planned.action() == Action.PLACE_SUPPORT;
        Cell base = support ? cell : cell.below();
        DirectPlan repriced = planner.placeAt(world, state, target,
            new DirectPlanner.BaseCandidate(base, support));
        if (repriced == null) return null;
        if (repriced.action() != planned.action()) return null;
        if (!Objects.equals(repriced.intent().cell(), planned.intent().cell())) return null;
        return repriced;
    }

    private Optional<RiskId> reserve(DirectPlan plan, int serverTick, DirectWorld world,
                                     Snapshot snapshot) {
        Ticket ticket = session.ticket(DIRECT_PLAN);
        Intent intent = plan.intent();
        int previousSlot = world.selectedSlot();
        boolean needsSlot = plan.context().hotbarSlot() != previousSlot;
        boolean needsRotation = settings.rotationsOn();
        Footprint footprint = footprint(intent);

        if (plan.isPlacement()) {
            ServerTickInterval arrival = new ServerTickInterval(serverTick + 1, serverTick + 3);
            // A support or phase 4.6 blocker placement is not a detonation: zero exposure and no bound
            // crystal. It still pays the packet/slot cost through the scheduler lease.
            ExposureBound upper = plan.action() == Action.PLACE_SUPPORT
                || plan.action() == Action.PLACE_BLOCKER
                ? new ExposureBound(new MitigatedDamage(0.0), Map.of(),
                    world.damage().envelope())
                : plan.context().exposure();
            RiskRequest request = new RiskRequest(upper, arrival, world.safetyState(), null, null,
                PACKET_COST, serverTick, needsSlot, needsRotation, false, footprint);
            if (!scheduler.reserve(intent, ticket, request)) return Optional.empty();
            return scheduler.riskId(DIRECT_PLAN, intent.id());
        }

        HazardId hazard = plan.entity() == null ? null : crystalHazards.get(plan.entity());
        if (hazard != null) {
            RiskId shared = hazardRisks.get(hazard);
            if (shared != null
                && scheduler.attach(intent, ticket, shared, PACKET_COST, serverTick, needsSlot,
                    needsRotation, false, footprint)) {
                return Optional.of(shared);
            }
        }

        DirectCrystal crystal = findCrystal(world, plan.entity());
        Vec position = crystal != null ? crystal.position() : null;
        if (position == null) return Optional.empty();
        // A defensive break is priced against the enemy crystal's possible detonation. It may arrive later
        // than our own placement window, so the interval is longer; the standard budget still owns it.
        int latestArrival = plan.context().defensive() ? serverTick + 5 : serverTick + 3;
        RiskRequest request = new RiskRequest(plan.context().exposure(),
            new ServerTickInterval(serverTick + 1, latestArrival), world.safetyState(),
            plan.entity(), position, PACKET_COST, serverTick, needsSlot, needsRotation, false,
            footprint);
        if (!scheduler.reserve(intent, ticket, request)) return Optional.empty();
        return scheduler.riskId(DIRECT_PLAN, intent.id());
    }

    private void onSent(DirectPlan plan, int serverTick, RiskId riskId, HazardId hazard,
                        PlacementId placement, DirectWorld world) {
        switch (plan.action()) {
            case ATTACK_CRYSTAL -> {
                EntityKey crystal = plan.entity();
                breakAttempts.merge(crystal, 1, Integer::sum);
                lastAttackTick.put(crystal, serverTick);
                lastBreakEntity = crystal;
                lastBreakTick = serverTick;
                breaksThisTick++;
                breaksSent++;
                preferredTarget = plan.target();
                if (plan.context().hotbarSlot() != world.selectedSlot()) {
                    lastToolSwitchTick = serverTick;
                }
                if (sequence.owns(crystal)) sequence.attackSent(crystal, serverTick);
            }
            case PLACE_CRYSTAL -> {
                ledger.markSent(hazard, serverTick);
                ledger.markPlacementSent(placement, serverTick);
                hazardRisks.put(hazard, riskId);
                sequence.beginPlacement(plan.intent().cell(), hazard, placement, riskId, serverTick);
                placementsThisTick++;
                placementsSent++;
                preferredTarget = plan.target();
            }
            case PLACE_SUPPORT -> {
                pendingSupport = plan.intent().cell();
                supportSentTick = serverTick;
                placementsThisTick++;
                placementsSent++;
                preferredTarget = plan.target();
            }
            default -> {
                // The planner only returns DirectOnly actions; anything else is a planner bug.
            }
        }
        // The risk keeps its debt; the physical footprint is free for the next attempted break.
        scheduler.releaseSent(DIRECT_PLAN, plan.intent().id());
    }

    private void settlePendingSupport(int serverTick, DirectWorld world) {
        if (pendingSupport == null) return;
        if (world.blocks().isBaseBlock(pendingSupport)) {
            pendingSupport = null;
            supportSentTick = -1;
            return;
        }
        if (serverTick - supportSentTick > SUPPORT_TIMEOUT_TICKS) {
            abandonedSupports.add(pendingSupport);
            pendingSupport = null;
            supportSentTick = -1;
        }
    }

    // ------------------------------------------------------------------ pauses

    /** The Phase 3.5 pause decision for this instant, or empty when the engine may work. */
    public Optional<DirectPause> pauseReason(int serverTick, DirectWorld world) {
        if (disabled) return Optional.of(DirectPause.SCREEN);
        if (risk.suspended()) return Optional.of(DirectPause.SUSPENDED);
        if (settings.pauseOnEating() && world.eating()) return Optional.of(DirectPause.EATING);
        if (settings.pauseOnDrinking() && world.drinking()) return Optional.of(DirectPause.DRINKING);
        if (settings.pauseOnMining() && world.mining()) return Optional.of(DirectPause.MINING);
        if (settings.pauseOnScreen() && world.screenOpen()) return Optional.of(DirectPause.SCREEN);
        if (settings.pauseOnBaritone() && world.baritoneActive()) {
            return Optional.of(DirectPause.BARITONE);
        }
        if (world.safetyState() != null && world.safetyState().selfHealth() != null
            && world.safetyState().selfHealth().cap() == Capability.KNOWN
            && world.safetyState().selfHealth().value() <= settings.healthFloor()) {
            return Optional.of(DirectPause.HEALTH);
        }
        if (settings.pauseOnLag()) {
            if (!Double.isFinite(world.tps()) || world.tps() < settings.minTps()) {
                return Optional.of(DirectPause.LAG);
            }
            if (world.nanosSinceLastFeedback() > settings.staleFeedbackNanos()) {
                return Optional.of(DirectPause.LAG);
            }
        }
        return Optional.empty();
    }

    // ------------------------------------------------------------------ helpers

    private Set<EntityKey> baseline(Snapshot snapshot) {
        Set<EntityKey> baseline = new HashSet<>();
        for (Snapshot.Crystal crystal : snapshot.crystals()) {
            if (crystal != null && crystal.entity() != null) baseline.add(crystal.entity());
        }
        return baseline;
    }

    private static Footprint footprint(Intent intent) {
        Cell cell = intent.cell();
        Box cellBox = cell == null ? null : Box.fromCell(cell);
        Box swept = cellBox == null ? null : cellBox.expand(1.0);
        Set<EntityKey> entities = intent.entityLocks().isEmpty() ? Set.of() : intent.entityLocks();
        return new Footprint(intent.blockLocks(), cellBox, swept, entities);
    }

    private static EffectiveHealth noTarget() {
        return new EffectiveHealth(0.0, 0.0, Capability.UNKNOWN, 0L);
    }

    private static DirectTarget findTarget(DirectWorld world, EntityKey target) {
        if (world == null || target == null) return null;
        for (DirectTarget candidate : world.targets()) {
            if (candidate != null && target.equals(candidate.entity())) return candidate;
        }
        return null;
    }

    private static DirectCrystal findCrystal(DirectWorld world, EntityKey crystal) {
        if (crystal == null) return null;
        for (DirectCrystal candidate : world.crystals()) {
            if (candidate != null && crystal.equals(candidate.entity())) return candidate;
        }
        return null;
    }
}
