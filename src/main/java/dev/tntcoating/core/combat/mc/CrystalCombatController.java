/*
 * Original Comet implementation by Boyonabike.
 * Independent: written from the pinned 26.2 artifact and Meteor build 35 behaviour; no third-party code.
 * SPDX-License-Identifier: CC0-1.0
 */

package dev.tntcoating.core.combat.mc;

import dev.tntcoating.core.combat.ActionGeneration;
import dev.tntcoating.core.combat.CleanupPriority;
import dev.tntcoating.core.combat.CombatEngine;
import dev.tntcoating.core.combat.Arrival;
import dev.tntcoating.core.combat.CombatHurtModel;
import dev.tntcoating.core.combat.CombatMetrics;
import dev.tntcoating.core.combat.CombatRiskBudget;
import dev.tntcoating.core.combat.Cell;
import dev.tntcoating.core.combat.CombatScheduler;
import dev.tntcoating.core.combat.CombatSession;
import dev.tntcoating.core.combat.ConnectionKey;
import dev.tntcoating.core.combat.CrystalCombatGate;
import dev.tntcoating.core.combat.CrystalLedger;
import dev.tntcoating.core.combat.Debt;
import dev.tntcoating.core.combat.DirectPlanner;
import dev.tntcoating.core.combat.EntityKey;
import dev.tntcoating.core.combat.FootprintConflict;
import dev.tntcoating.core.combat.Mode;
import dev.tntcoating.core.combat.PistonLayoutSelection;
import dev.tntcoating.core.combat.PistonSequence;
import dev.tntcoating.core.combat.RiskDebtSnapshot;
import dev.tntcoating.core.combat.RiskModel;
import dev.tntcoating.core.combat.Observation;
import dev.tntcoating.core.combat.Opportunity;
import dev.tntcoating.core.combat.ObservationKind;
import dev.tntcoating.core.combat.ObservationPayload;
import dev.tntcoating.core.combat.SuspensionCause;
import dev.tntcoating.core.combat.TickBudget;
import dev.tntcoating.core.combat.Vec;
import java.util.EnumSet;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.function.BooleanSupplier;
import net.minecraft.client.Minecraft;

/**
 * Lifecycle and visibility controller for the {@code CrystalCombat} module. This is the one object the
 * Meteor module talks to, so the module itself stays a thin settings/lifecycle shell.
 *
 * <p>It owns no send path: the only combat send path stays {@link CombatRuntime#dispatch}. Enable performs
 * the capability/readiness check <em>before</em> any runtime object is created, then builds the stack, binds
 * the read-only ledger hazard protector and only then activates {@link CrystalCombatGate}. Disable
 * deactivates the gate first and calls {@link CombatRuntime#onDisabled()} immediately; it never sends, never
 * uninstalls the observation hooks, never clears hazards, sent risk, the ledger or the risk budget, and
 * leaves the protector bound so Meteor keeps excluding ledger-protected crystals while CrystalCombat is off.
 *
 * <p>Lifecycle methods mirror {@link CombatRuntime}: {@link #onDeath()}, {@link #onRespawn()} and
 * {@link #onLeft()} reuse the runtime's transitions instead of duplicating generation or budget logic.
 * Respawn additionally reconciles the ledger from a fresh applied snapshot before the budget is allowed to
 * reject stale suspensions. Connection close clears only the closing connection (the runtime already scopes
 * leases, risk and receipts to that {@code ConnectionKey}); a later {@link #onJoined()} opens a new session.
 *
 * <p>Threading: {@link #enable()}, {@link #disable()}, {@link #tick(int)} and the lifecycle methods are
 * called from the client thread (Meteor module callbacks). {@link #infoString()} is callable from any thread
 * and only reads the last published snapshot string, so {@code getInfoString()} stays side-effect-free.
 * {@link #waitReason()} performs read-only queries and is called by {@link #tick(int)} on the client thread
 * (and by tests), never from render or network threads.
 */
public final class CrystalCombatController {
    /** Capability/settings gate checked before any runtime object exists. Returns null when enable may proceed. */
    public interface Readiness {
        String unavailableReason();
    }

    /** Builds the production runtime stack; only called after {@link Readiness} returned null. */
    public interface StackFactory {
        Stack create();
    }

    /** The combat runtime stack, constructed once and reused across disable/enable cycles. */
    public record Stack(CombatRuntime runtime, CombatSession session, CrystalLedger ledger,
                        CombatScheduler scheduler, CombatRiskBudget risk, CombatClient client,
                        ObservationIntake intake, DirectEngineHost engineHost,
                        CombatHurtModel hurtModel, PistonTrapController pistonTrap) {
        /** Existing test/fake stacks stay trap-free; production always passes the adapter. */
        public Stack(CombatRuntime runtime, CombatSession session, CrystalLedger ledger,
                     CombatScheduler scheduler, CombatRiskBudget risk, CombatClient client,
                     ObservationIntake intake, DirectEngineHost engineHost, CombatHurtModel hurtModel) {
            this(runtime, session, ledger, scheduler, risk, client, intake, engineHost, hurtModel, null);
        }

        public Stack {
            Objects.requireNonNull(runtime, "runtime");
            Objects.requireNonNull(session, "session");
            Objects.requireNonNull(ledger, "ledger");
            Objects.requireNonNull(scheduler, "scheduler");
            Objects.requireNonNull(risk, "risk");
            Objects.requireNonNull(client, "client");
            Objects.requireNonNull(intake, "intake");
            Objects.requireNonNull(hurtModel, "hurtModel");
        }
    }

    private static final String WAITING_FOR_CONNECTION = "waiting for connection";
    private static final String DISABLED = "disabled";
    private static final String ENABLED = "enabled";
    /**
     * Receipt numbers for Meteor after-application observations start above any counter a packet receipt can
     * realistically reach, so the ledger's {@code (connection, receiptSeq)} dedupe key can never confuse the
     * two observation sources. The intake counter starts at one; this is 2^62.
     */
    private static final long HOST_RECEIPT_BASE = 0x4000_0000_0000_0000L;

    private final Readiness readiness;
    private final StackFactory stackFactory;
    private final CombatMetrics metrics;
    /** Phase 6.3 evidence: last setting/mode pair actually logged, so a quiet tick stays quiet. */
    private Boolean concurrencyLoggedSetting;
    private Mode concurrencyLoggedMode;
    private final EnumSet<SuspensionCause> lastSuspensions = EnumSet.noneOf(SuspensionCause.class);
    /**
     * Server ticks a non-evidence suspension (setback, connection change, queue overflow, malformed damage
     * data) must persist before the controller trusts a fresh applied snapshot and reconciles. Without this
     * only a respawn ever reconciled, so these causes stalled the module until the player died.
     */
    static final int RECONCILE_SETTLE_TICKS = 5;
    private static final Set<SuspensionCause> SETTLE_RECONCILABLE = EnumSet.of(SuspensionCause.SETBACK,
        SuspensionCause.CONNECTION_CHANGE, SuspensionCause.QUEUE_OVERFLOW, SuspensionCause.MISSING_DAMAGE_DATA);
    private int reconcilableSince = -1;
    /** Opt-in tester diagnostics; null while the Debug log setting is off or nothing was enabled yet. */
    private CombatDebugHost debugHost;

    private Stack stack;
    private boolean enabled;
    /** Production gate: the module's piston-trap setting owns this; the controller only obeys it. */
    private boolean trapEnabled;
    /** Beta cleanup setting; off keeps the legacy manual-cleanup path and sends nothing. */
    private boolean autoCleanup;
    /** The single runner mode selector: DirectOnly, PistonOnly or serialised Hybrid. */
    private Mode mode = Mode.DIRECT_ONLY;
    /**
     * Last risk model pushed to the live budget (or the one the next built stack starts with). The model is
     * live state, not a build input: switching it never rebuilds the stack and never touches debt.
     */
    private RiskModel riskModel = RiskModel.defaultModel();
    /** Beta 16-combination layout filter; consulted by the trap planner in PistonOnly and Hybrid. */
    private PistonLayoutSelection pistonLayouts = PistonLayoutSelection.all();
    private String refusalReason;
    private volatile String info = DISABLED;
    /** Client-tick-published view for the read-only renderer; never queried from render code. */
    private volatile RiskDebtSnapshot riskSnapshot = RiskDebtSnapshot.empty();
    private int lastServerTick = -1;
    /** Client tick the host observations are stamped with; the last tick the controller actually applied. */
    private int clientTick;
    private long nextHostReceipt = HOST_RECEIPT_BASE;
    /** The connection the ledger currently observes; null when no session or before the first tick. */
    private ConnectionKey trackedConnection;
    /**
     * The action generation the engine last saw. A change observed in {@link #tick(int)} or a direct
     * lifecycle transition aborts the engine sequence exactly once before any work in the new generation.
     */
    private ActionGeneration engineGeneration;
    /**
     * The action generation the trap last ran in. Tracked apart from {@link #engineGeneration}, which only
     * advances while the engine runs, so PistonOnly sees every transition exactly once too.
     */
    private ActionGeneration trapGeneration;
    /** A generation transition stopped the trap; it re-arms once the budget is no longer suspended. */
    private boolean trapRearmPending;

    public CrystalCombatController(Readiness readiness, StackFactory stackFactory) {
        this(readiness, stackFactory, new CombatMetrics());
    }

    public CrystalCombatController(Readiness readiness, StackFactory stackFactory, CombatMetrics metrics) {
        this.readiness = Objects.requireNonNull(readiness, "readiness");
        this.stackFactory = Objects.requireNonNull(stackFactory, "stackFactory");
        this.metrics = Objects.requireNonNull(metrics, "metrics");
    }

    /**
     * Runs the readiness check first; on refusal nothing is built and the module must stay disabled with
     * {@link #infoString()} as the visible reason. On success the stack is created, the ledger protector is
     * bound, then the gate is activated — in that order.
     *
     * @return whether the module is now enabled
     */
    public boolean enable() {
        if (enabled) return true;
        String refusal;
        try {
            refusal = readiness.unavailableReason();
        } catch (RuntimeException failure) {
            refusal = "capability check failed: " + failure.getMessage();
        }
        if (refusal != null) {
            refusalReason = refusal;
            info = refusal;
            return false;
        }
        if (stack == null) {
            try {
                stack = stackFactory.create();
            } catch (RuntimeException failure) {
                refusalReason = "runtime start failed: " + failure.getMessage();
                info = refusalReason;
                return false;
            }
        }
        refusalReason = null;
        trackedConnection = stack.session().isOpen() ? stack.session().connection() : null;
        stack.runtime().setRespawnReconciler(this::reconcileAfterRespawn);
        CrystalCombatGate.setProtector(stack.ledger()::protects);
        CrystalCombatGate.activate();
        if (stack.engineHost() != null && mode != Mode.PISTON_ONLY) {
            stack.engineHost().onEnabled();
            applyEngineMode();
        }
        if (stack.pistonTrap() != null) {
            stack.pistonTrap().setLayouts(pistonLayouts);
            stack.pistonTrap().setDiagnostics(new PistonTrapController.Diagnostics() {
                @Override
                public void debug(String line) {
                    debugLine(line);
                }

                @Override
                public void incident(String line) {
                    debugMarker(line);
                }
            });
            if (effectiveTrap()) stack.pistonTrap().enable();
            else stack.pistonTrap().disable();
            stack.pistonTrap().setAutoCleanup(autoCleanup);
        }
        engineGeneration = stack.session().isOpen() ? stack.session().actionGeneration() : null;
        trapGeneration = engineGeneration;
        trapRearmPending = false;
        enabled = true;
        stack.runtime().onEnabled();
        lastSuspensions.clear();
        lastServerTick = -1;
        info = ENABLED;
        return true;
    }

    /**
     * Stops automatic work immediately. The gate goes first so no delayed Meteor callback can slip a send in
     * between; then the runtime invalidates tickets and the action generation. Everything observational —
     * hazards, sent risk, the risk budget, the ledger and the observation hooks — is deliberately untouched.
     */
    public void disable() {
        boolean wasEnabled = enabled;
        enabled = false;
        CrystalCombatGate.deactivate();
        if (stack != null && wasEnabled) {
            stack.runtime().onDisabled();
            if (stack.engineHost() != null) stack.engineHost().onDisabled();
        }
        if (stack != null && stack.pistonTrap() != null) stack.pistonTrap().disable();
        engineGeneration = null;
        trapGeneration = null;
        trapRearmPending = false;
        riskSnapshot = RiskDebtSnapshot.empty();
        info = refusalReason != null ? refusalReason : DISABLED;
    }

    /**
     * Budgeted tick: one deadline and one work counter cover the queue drain, revision stamping, snapshot
     * capture, event-triggered passes, every planner pass and before-send validation. When it runs out only
     * optional planning/search defers to the next tick; reconciliation and observation application always
     * run here.
     */
    public void tick(int serverTick, TickBudget budget) {
        if (stack == null) return;
        if (serverTick < 0 || serverTick <= lastServerTick) return;
        lastServerTick = serverTick;
        clientTick = serverTick;
        if (budget == null) budget = TickBudget.unlimited();
        stack.runtime().activateBudget(budget);
        long start = System.nanoTime();
        boolean open = stack.session().isOpen();
        if (open) {
            // One passive observer: the ledger sees every applied observation before the budget does, so a
            // spawn can bind a sent hazard and the gate protector keeps protecting that crystal. The debug
            // tap only reads the applied observation and the budget; it can never influence a decision.
            // The cache tap runs after application so invalidation follows the applied stream, never a raw
            // packet, and can only shrink the amount of optional work next pass.
            stack.runtime().tick(serverTick, stack.ledger()::observe, observation -> {
                CombatDebugHost debug = debugHost;
                if (debug != null) debug.apply(observation);
                if (stack.engineHost() != null) {
                    stack.engineHost().onObservationApplied(observation);
                }
            }, budget);
            reconcileSettledSuspension(serverTick);
            if (enabled && stack.pistonTrap() != null) syncTrapGeneration();
            if (enabled && mode == Mode.HYBRID) hybridBeforeTick(serverTick, budget);
            if (enabled && stack.engineHost() != null && mode != Mode.PISTON_ONLY) {
                ActionGeneration current = stack.session().actionGeneration();
                if (!Objects.equals(engineGeneration, current)) {
                    stack.engineHost().onGenerationEnded();
                    engineGeneration = current;
                }
                stack.engineHost().tick(serverTick, budget);
            }
            if (enabled && mode == Mode.HYBRID) hybridAfterTick(serverTick);
            if (enabled && effectiveTrap()) {
                // DirectOnly never runs the trap. PistonOnly always runs it. Hybrid runs it while a trap
                // is live and only starts a new one when the engine selected the previewed plan.
                if (mode != Mode.HYBRID || trapSelected() || stack.pistonTrap().activeTrap().isPresent()) {
                    stack.pistonTrap().tick(serverTick, budget);
                }
            }
            CombatDebugHost debug = debugHost;
            if (debug != null) debug.afterTick(serverTick);
        }
        long elapsed = System.nanoTime() - start;
        metrics.recordCombatTickMs(elapsed / 1_000_000.0);
        if (open) closeTrackedConnectionIfChanged();
        if (enabled) {
            sampleRisk();
            info = waitReason();
        }
    }


    /**
     * One production client tick on the plan 9.3 default budget. The budget defers optional planning only;
     * the Meteor after-application spawn path is mandatory and runs unbudgeted. It advances the runtime
     * exactly once per server tick and publishes the visible wait reason. Passive observation does not stop at
     * disable: while a stack exists the runtime keeps draining and applying observations so sent risk can
     * retire and an observed respawn can reconcile; only the visible status and the automatic-work gate
     * follow {@link #enabled()}.
     */
    public void tick(int serverTick) {
        tick(serverTick, TickBudget.afterMillis(TickBudget.DEFAULT_MILLIS, System::nanoTime));
    }

    /**
     * A death, respawn or dimension change (any generation transition) stops trap sends immediately; a
     * retained sequence becomes manual cleanup and keeps its hazards until explicit reconciliation. The
     * trap re-arms on a later tick once the budget is no longer suspended, so it never stays off silently
     * after the first death. Re-arming is checked before the transition so it always waits at least one tick.
     */
    private void syncTrapGeneration() {
        if (trapRearmPending && !stack.risk().suspended()) {
            trapRearmPending = false;
            if (effectiveTrap()) stack.pistonTrap().enable();
        }
        ActionGeneration current = stack.session().actionGeneration();
        if (Objects.equals(trapGeneration, current)) return;
        trapGeneration = current;
        stack.pistonTrap().disable();
        trapRearmPending = true;
    }

    /**
     * A dimension change migrates the budget but starts a new ledger connection epoch, so the old epoch is
     * closed <em>after</em> the tick that observed the change. Only the old connection's ledger state is
     * dropped; every other connection and the risk budget stay untouched.
     */
    private void closeTrackedConnectionIfChanged() {
        ConnectionKey current = stack.session().connection();
        if (Objects.equals(current, trackedConnection)) return;
        if (trackedConnection != null) stack.ledger().connectionClosed(trackedConnection);
        trackedConnection = current;
    }

    /** Applies new settings to the existing stack; hazards, risk and observations are preserved. */
    public void reconfigure(dev.tntcoating.core.combat.DirectSettings settings) {
        if (stack == null) return;
        if (stack.engineHost() != null) stack.engineHost().reconfigure(settings);
        if (stack.pistonTrap() != null) stack.pistonTrap().reconfigure(settings);
        // The cleanup switch is trap-local (not part of the pure DirectSettings record), but every config
        // change still re-applies it here so a live stack can never keep a stale cleanup mode.
        if (stack.pistonTrap() != null) stack.pistonTrap().setAutoCleanup(autoCleanup);
    }

    /**
     * Production setting bridge for the beta auto-cleanup. Off by default; toggling takes effect on the
     * current trap immediately and never touches risk, hazards or the offhand.
     */
    public void setAutoCleanup(boolean value) {
        autoCleanup = value;
        if (stack != null && stack.pistonTrap() != null) stack.pistonTrap().setAutoCleanup(value);
    }

    /** Death: the runtime ends the action generation and keeps hazards and friend debt untouched. */
    public void onDeath() {
        if (stack == null) return;
        if (stack.pistonTrap() != null && mode != Mode.DIRECT_ONLY) stack.pistonTrap().death();
        stack.runtime().onDeath();
        endEngineGeneration();
        refresh();
    }

    /**
     * Respawn: the runtime ends the action generation and observes RESPAWN, then its respawn hook reconciles
     * the ledger against a fresh applied snapshot and clears rejection suspensions. The observation path and
     * this direct call share that one implementation, so either path reconciles exactly once.
     */
    public void onRespawn() {
        if (stack == null) return;
        if (stack.pistonTrap() != null) {
            if (effectiveTrap() && enabled) stack.pistonTrap().enable();
            stack.pistonTrap().respawn();
        }
        stack.runtime().onRespawn();
        endEngineGeneration();
        refresh();
    }

    /**
     * Installed as the runtime respawn hook: reconcile only after the budget observed RESPAWN and the action
     * generation transitioned, then clear suspensions. Never sends.
     */
    private void reconcileAfterRespawn() {
        if (stack == null) return;
        stack.ledger().reconcile(stack.runtime().capture());
        stack.runtime().onReconciled();
        refresh();
    }

    /**
     * Reconciles a suspension whose causes are all non-evidence ones once they have persisted for
     * {@link #RECONCILE_SETTLE_TICKS} server ticks, through the same ledger-rescan path a respawn uses. A
     * pending death waits for the respawn path; incoherent-health and widening holds survive reconciliation.
     */
    private void reconcileSettledSuspension(int serverTick) {
        Set<SuspensionCause> causes = stack.risk().suspensionCauses();
        boolean reconcilable = stack.client().selfKey() != null
            && !causes.contains(SuspensionCause.DEATH)
            && !causes.contains(SuspensionCause.RESPAWN)
            && causes.stream().anyMatch(SETTLE_RECONCILABLE::contains);
        if (!reconcilable) {
            reconcilableSince = -1;
            return;
        }
        if (reconcilableSince < 0) {
            reconcilableSince = serverTick;
            return;
        }
        if (serverTick - reconcilableSince < RECONCILE_SETTLE_TICKS) return;
        reconcilableSince = -1;
        stack.ledger().reconcile(stack.runtime().capture());
        stack.runtime().onReconciled();
        debugMarker("RECONCILE settled causes=" + causes);
    }

    /** Connection close: clears that connection's ledger state only, then waits for a new session. */
    public void onLeft() {
        // The close below retires this connection's risk and ledger state, so the renderer must stop
        // showing the old debt immediately. Publishing empty here covers every teardown path that calls
        // onLeft without disable(); the next enabled tick in a new world publishes a fresh snapshot and
        // onJoined() never restores this one.
        riskSnapshot = RiskDebtSnapshot.empty();
        if (stack == null) return;
        if (stack.pistonTrap() != null) stack.pistonTrap().connectionClosed();
        if (stack.session().isOpen()) {
            ConnectionKey closing = stack.session().connection();
            stack.runtime().onConnectionClosed();
            stack.ledger().connectionClosed(closing);
        }
        endEngineGeneration();
        engineGeneration = null;
        trapGeneration = null;
        trackedConnection = null;
        refresh();
    }

    /** A world/connection is available: starts a fresh session unless one is already open. */
    public void onJoined() {
        if (stack == null) return;
        if (!stack.session().isOpen()) {
            stack.session().open();
            trackedConnection = stack.session().connection();
        }
        if (enabled) stack.runtime().onEnabled();
        refresh();
    }

    /** Config change ends in-flight actions exactly like a disable, without stopping passive state. */
    public void onConfigChanged() {
        if (!enabled || stack == null) return;
        if (stack.pistonTrap() != null) {
            stack.pistonTrap().disable();
            if (effectiveTrap()) stack.pistonTrap().enable();
        }
        stack.runtime().onConfigChanged();
        endEngineGeneration();
        refresh();
    }

    /**
     * Aborts the engine's action generation after the runtime already transitioned the session. Exactly one
     * engine abort per transition: the tracked generation is refreshed here so the next {@link #tick(int)}
     * does not repeat it. Hazards and sent risk remain untouched; only the engine sequence is stopped.
     */
    private void endEngineGeneration() {
        if (stack == null) return;
        // The caller already stopped or re-armed the trap for this transition; the tick must not repeat it.
        trapGeneration = stack.session().isOpen() ? stack.session().actionGeneration() : null;
        trapRearmPending = false;
        if (stack.engineHost() == null) return;
        stack.engineHost().onGenerationEnded();
        // A generation boundary always drops the Hybrid trap sentinel; the next tick may re-arm it only
        // if the trap is still enabled and owns a sequence.
        CombatEngine engine = engineOrNull();
        if (engine != null) engine.clearActiveTrap();
        engineGeneration = stack.session().isOpen() ? stack.session().actionGeneration() : null;
    }

    /**
     * The live connection epoch observations belong to, or {@code null} when there is no stack or no open
     * session. Used by the always-subscribed observation host and never mutates state.
     */
    public ConnectionKey connection() {
        if (stack == null || !stack.session().isOpen()) return null;
        return stack.session().connection();
    }

    /**
     * Feeds one Meteor after-application spawn into the passive ledger. The fully-formed observation is built
     * here, not in the Meteor-facing host: live connection, a receipt sequence from the reserved host range,
     * the current applied revision, the controller's client tick and the resolution time. Ignored when there
     * is no live session, when the key belongs to another connection, or when a field is missing.
     */
    public void observeSpawn(EntityKey key, Vec position, String entityType) {
        if (key == null || position == null || entityType == null || entityType.isEmpty()) return;
        Stack live = liveStackFor(key);
        if (live == null) return;
        live.ledger().observe(new Observation(live.session().connection(), nextHostReceipt++,
            live.intake().appliedRevision(), System.nanoTime(), clientTick, ObservationKind.SPAWN, key, null,
            position, null, new ObservationPayload.Spawn(entityType)));
        if (live.pistonTrap() != null && DirectPlanner.END_CRYSTAL.equals(entityType)) {
            live.pistonTrap().onCrystalSpawn(key, entityType, key.uuid(), cellOf(position));
        }
        if (live.engineHost() != null) {
            // A crystal spawn changes only the crystal lifecycle; any other spawn changes the eligible
            // target list, so the cached target choice must drop before the next pass.
            if (DirectPlanner.END_CRYSTAL.equals(entityType)) {
                live.engineHost().onCrystalSpawned();
                // The spawn-to-attack path is mandatory and runs in the same client-thread task; it is
                // never deferred by the optional per-tick planning budget.
                if (enabled) live.engineHost().onSpawnObserved(key);
            } else {
                live.engineHost().onTargetSetChanged();
            }
        }
    }

    /** Feeds one Meteor after-application removal into the passive ledger; same rules as {@link #observeSpawn}. */
    public void observeRemoval(EntityKey key) {
        if (key == null) return;
        Stack live = liveStackFor(key);
        if (live == null) return;
        live.ledger().observe(new Observation(live.session().connection(), nextHostReceipt++,
            live.intake().appliedRevision(), System.nanoTime(), clientTick, ObservationKind.REMOVED, key, null,
            null, null, new ObservationPayload.Removed("entity-removed", false)));
        if (live.engineHost() != null) live.engineHost().onCrystalRemoved();
        if (live.pistonTrap() != null) live.pistonTrap().onCrystalRemoved(key);
    }

    /**
     * Feeds one applied block update into the engine cache: block-collision/placement candidates become
     * stale, break/equipment candidates do not. Coalesced with other block updates until the next pass.
     */
    public void observeBlockUpdate(Cell cell) {
        Stack live = stack;
        if (live == null || !live.session().isOpen() || cell == null) return;
        // The trap sees the server-authored block update before the engine cache; neither path sends here.
        if (live.pistonTrap() != null) live.pistonTrap().onBlockUpdate(cell);
        if (live.engineHost() != null) live.engineHost().onBlockUpdated(cell);
    }

    /**
     * Ordered applied block update with the old and new block ids. This is the only production entry point
     * that lets the ledger answer a structural placement (and thus mark a cell inferred-ours); the legacy
     * cell-only overload above deliberately cannot earn ownership.
     */
    public void observeBlockUpdate(Cell cell, String oldBlock, String newBlock) {
        Stack live = stack;
        if (live == null || !live.session().isOpen() || cell == null) return;
        live.ledger().observe(new Observation(live.session().connection(), nextHostReceipt++,
            live.intake().appliedRevision(), System.nanoTime(), clientTick, ObservationKind.BLOCK_UPDATE,
            null, cell, null, null, new ObservationPayload.BlockUpdate(oldBlock, newBlock, false)));
        if (live.pistonTrap() != null) live.pistonTrap().onBlockUpdate(cell);
        if (live.engineHost() != null) live.engineHost().onBlockUpdated(cell);
    }

    /** Feeds one applied move/damage/equipment/effect update into the engine cache, per target. */
    public void observeTargetChanged(int entityId) {
        Stack live = stack;
        if (live == null || !live.session().isOpen()) return;
        if (live.engineHost() != null) live.engineHost().onTargetChanged(entityId);
        if (live.pistonTrap() != null) {
            EntityKey key = live.client().entity(entityId);
            if (key != null) live.pistonTrap().onTargetChanged(key);
        }
    }

    /**
     * Production setting bridge for the piston-trap setting. Off keeps the trap dormant; DirectOnly never
     * arms it either way. On only arms the trap while the runtime itself is enabled; hazards/risk are never
     * cleared.
     */
    public void setTrapEnabled(boolean value) {
        trapEnabled = value;
        if (stack == null || stack.pistonTrap() == null) return;
        if (effectiveTrap() && enabled) stack.pistonTrap().enable();
        else {
            stack.pistonTrap().disable();
            CombatEngine engine = engineOrNull();
            if (engine != null) engine.clearActiveTrap();
        }
    }

    public boolean trapEnabled() {
        return trapEnabled;
    }

    /**
     * Explicit tester reconciliation after manually removing the trap. Releases the controller's single
     * trap slot (and the plan's scheduler footprint), while ledger hazards and sent risk stay untouched.
     * No packet is sent. When the module is enabled in PistonOnly, the trap becomes eligible to scan again.
     *
     * @return whether an active trap existed
     */
    public boolean confirmTrapCleanup() {
        Stack live = stack;
        if (live == null || live.pistonTrap() == null || live.pistonTrap().activeTrap().isEmpty()) {
            return false;
        }
        live.pistonTrap().reconcile();
        if (enabled && effectiveTrap()) live.pistonTrap().enable();
        refresh();
        return true;
    }

    /**
     * The tester mode selector. Hybrid is the serialised Phase 6 mode: a committed trap blocks direct
     * plans and defence keeps evaluating. Footprint-scoped concurrency exists only behind the default-off
     * Phase 6.4 setting; with it off (the shipped default) Hybrid never runs two offensive plans at once.
     */
    public void setMode(Mode value) {
        if (value == null) throw new IllegalArgumentException("mode");
        Mode previous = mode;
        if (previous == value) return;
        mode = value;
        if (stack == null || !enabled) {
            applyRunnerState();
            return;
        }
        // One generation transition ends unsent work on both paths; the retained ledger, hazards and sent
        // risk are untouched, and no packet is sent by the transition itself.
        if (stack.pistonTrap() != null) stack.pistonTrap().disable();
        stack.runtime().onConfigChanged();
        endEngineGeneration();
        applyRunnerState();
        engineGeneration = stack.session().isOpen() ? stack.session().actionGeneration() : null;
        refresh();
    }

    public Mode mode() {
        return mode;
    }

    /**
     * Live risk-model switch. The stack is never rebuilt and nothing is retired: the existing budget is
     * handed the new model, which only drops in-flight BARRIER probe evidence when it leaves BARRIER.
     * Sent risk, debt, hazards and the ledger are untouched, so neither a config change nor a profile
     * switch can clear risk that has already left.
     */
    public void setRiskModel(RiskModel value) {
        if (value == null) throw new IllegalArgumentException("riskModel");
        riskModel = value;
        if (stack != null) stack.risk().setRiskModel(value);
    }

    /** The live budget's model, or the staged value before the stack exists. */
    public RiskModel riskModel() {
        return stack != null ? stack.risk().riskModel() : riskModel;
    }

    /** Installs the beta layout filter; disabled combinations are never enumerated by the trap planner. */
    public void setPistonLayouts(PistonLayoutSelection selection) {
        pistonLayouts = selection == null ? PistonLayoutSelection.all() : selection;
        if (stack != null && stack.pistonTrap() != null) stack.pistonTrap().setLayouts(pistonLayouts);
    }

    public PistonLayoutSelection pistonLayouts() {
        return pistonLayouts;
    }

    /**
     * True only while the trap is both enabled and holding a sequence. A disabled/dead/closed trap keeps
     * its sequence for manual cleanup, but it must never re-arm the Hybrid active-trap sentinel.
     */
    static boolean trapIsLive(PistonTrapController trap) {
        if (trap == null || !trap.enabled() || trap.activeTrap().isEmpty()) return false;
        PistonSequence sequence = trap.activeTrap().get();
        // A retained sequence that already needs manual cleanup is not a live offensive trap. It keeps its
        // ledger hazards and sent risk, but it must not re-arm the Hybrid serialisation sentinel and block
        // direct play until the tester runs ".crystalcombat cleanup confirm".
        if (sequence.phase() == PistonSequence.PistonPhase.MANUAL_CLEANUP
            || sequence.phase() == PistonSequence.PistonPhase.ABORTED) return false;
        return !sequence.flags().disabled() && !sequence.flags().noNewTrapUntilReconciled();
    }

    /** The trap runs in PistonOnly and in serialised Hybrid; DirectOnly never arms it. */
    private boolean effectiveTrap() {
        return (mode == Mode.PISTON_ONLY || mode == Mode.HYBRID) && trapEnabled;
    }

    /** Starts/stops the runner selected by {@link #mode}; the unselected runner never ticks. */
    private void applyRunnerState() {
        if (stack == null) return;
        if (stack.engineHost() != null) {
            if (mode != Mode.PISTON_ONLY) stack.engineHost().onEnabled();
            else stack.engineHost().onDisabled();
            applyEngineMode();
        }
        if (stack.pistonTrap() != null) {
            if (effectiveTrap() && enabled) stack.pistonTrap().enable();
            else stack.pistonTrap().disable();
        }
    }

    // ------------------------------------------------------------------ hybrid (Phase 6.4/6.5)

    private CombatEngine engineOrNull() {
        Stack live = stack;
        if (live == null || live.engineHost() == null) return null;
        return live.engineHost().engine();
    }

    /** True when the engine handed this tick to the trap; the trap owner must run it. */
    private boolean trapSelected() {
        CombatEngine engine = engineOrNull();
        return engine != null && engine.trapSelected();
    }

    /** Pushes the selected mode into the engine; the mode only changes which candidates are ranked. */
    private void applyEngineMode() {
        CombatEngine engine = engineOrNull();
        if (engine != null) engine.setMode(mode);
    }

    /**
     * Phase 6.4, before the engine tick: hand it the trap's committed plan (which serialises every other
     * offensive plan) or, while no trap is active, a ranking-only preview of the best trap the planner
     * could start. The trap stays the owner of every send, lease, hazard and risk reservation; this only
     * passes a ranking view and never reserves risk.
     */
    private void hybridBeforeTick(int serverTick, TickBudget budget) {
        CombatEngine engine = engineOrNull();
        if (engine == null) return;
        PistonTrapController trap = stack.pistonTrap();
        if (trap == null) {
            engine.clearActiveTrap();
            engine.offerPlans(List.of());
            engine.setCommittedPlan(null);
            logConcurrency(engine, "no-trap");
            return;
        }
        if (trapIsLive(trap)) {
            // The sentinel is reported separately from the ranking view: a live trap with an
            // unavailable view still blocks direct plans, and is never treated as compatible.
            Optional<Opportunity> committed = trap.rankedPlan();
            Optional<FootprintConflict.TrapView> view = trap.conflictView();
            engine.setActiveTrap(view.orElse(null));
            engine.offerPlans(committed.map(List::of).orElse(List.of()));
            engine.setCommittedPlan(committed.orElse(null));
            logConcurrency(engine, view.isPresent() ? "trap-view" : "trap-view-unavailable");
            noteCleanup(engine);
            return;
        }
        engine.clearActiveTrap();
        engine.setCommittedPlan(null);
        // A disabled/dead/closed trap keeps its sequence for manual cleanup, but it is not a live
        // offensive owner: do not re-arm the sentinel or offer its stale preview.
        engine.offerPlans(trap.enabled()
            ? trap.previewPlan(serverTick, budget).map(List::of).orElse(List.of())
            : List.of());
        logConcurrency(engine, "no-active-trap");
        noteCleanup(engine);
    }

    /**
     * Phase 6.3 evidence tap: records the active mode every Hybrid tick and emits one debug line
     * whenever the footprint-concurrency setting or the runner mode changes. Read-only: no send, reserve
     * or decision is affected.
     */
    private void logConcurrency(CombatEngine engine, String phase) {
        boolean setting = engine.settings().footprintConcurrency();
        boolean changed = concurrencyLoggedSetting == null || concurrencyLoggedSetting != setting
            || concurrencyLoggedMode != mode;
        concurrencyLoggedSetting = setting;
        concurrencyLoggedMode = mode;
        CombatMetrics recorder = metrics;
        if (changed && recorder != null) recorder.recordConcurrencySetting(setting);
        if (changed) {
            debugLine("[concurrency] setting=" + (setting ? "on" : "off") + " mode=" + mode
                + " phase=" + phase);
        }
        if (recorder != null) recorder.recordConcurrencyMode(mode.name());
    }

    /** Phase 6.4, after the engine tick: start exactly the previewed plan when Hybrid selected it. */
    private void hybridAfterTick(int serverTick) {
        if (!trapSelected()) return;
        PistonTrapController trap = stack.pistonTrap();
        if (trap != null) trap.startPreviewed(serverTick);
    }

    /**
     * Phase 6.5 cleanup starvation hook. The trap owns the request now: its cleanup stage asks for the
     * starvation wait and clears it when it finishes, stops or loses the trap. This hook only retires a
     * wait whose connection or action generation is gone. No packet is sent and no hazard or risk
     * reservation is touched here.
     */
    private void noteCleanup(CombatEngine engine) {
        if (!stack.session().isOpen()) {
            engine.cleanupPriority().clear();
            return;
        }
        ConnectionKey conn = stack.session().connection();
        ActionGeneration gen = stack.session().actionGeneration();
        if (engine.cleanupPriority().expired(conn, gen)) {
            engine.cleanupPriority().clear();
        }
    }

    /** Appends one free-form tester debug record; no effect when the debug log is not running. */
    public void debugLine(String text) {
        CombatDebugHost host = debugHost;
        if (host != null && host.running()) host.debug(text);
    }

    private static Cell cellOf(Vec position) {
        return new Cell((int) Math.floor(position.x()), (int) Math.floor(position.y()),
            (int) Math.floor(position.z()));
    }

    /**
     * Phase 4.3 client-side seam: record one landed raw hit (the amount the hurt path received, before
     * armour) for the target. This is the only way hurt-sync learns about the target's window; until a
     * packet hook that carries a real damage amount is wired, nothing in production calls it and every
     * target keeps an unknown window (exactly the Phase 3 estimate with a zero guaranteed lower bound).
     *
     * <p>Must be called on the client thread. Returns false when the target does not belong to the live
     * session or the observation is malformed; the model is not touched in that case.</p>
     */
    public boolean observeRawHit(EntityKey target, double rawDamage, Arrival arrival) {
        Stack live = stack;
        if (live == null || !live.session().isOpen() || target == null || arrival == null) return false;
        if (!live.session().connection().equals(target.conn())) return false;
        try {
            live.hurtModel().observeHit(target, rawDamage, arrival);
        } catch (IllegalArgumentException invalid) {
            return false;
        }
        if (live.engineHost() != null) live.engineHost().onTargetChanged(target.id());
        return true;
    }

    private Stack liveStackFor(EntityKey key) {
        if (stack == null || !stack.session().isOpen()) return null;
        return stack.session().connection().equals(key.conn()) ? stack : null;
    }

    /**
     * The visible wait reason, in priority order: not enabled, no connection, suspension (with cause), risk
     * debt, no crystals, then idle. Side-effect-free: it only queries the ledger/client/budget.
     */
    public String waitReason() {
        if (!enabled) return refusalReason != null ? refusalReason : DISABLED;
        if (stack == null) return DISABLED;
        if (!stack.session().isOpen()) return WAITING_FOR_CONNECTION;
        Set<SuspensionCause> causes = stack.risk().suspensionCauses();
        if (!causes.isEmpty()) {
            StringBuilder text = new StringBuilder("paused: ");
            boolean first = true;
            for (SuspensionCause cause : causes) {
                if (!first) text.append("; ");
                text.append(cause.label());
                first = false;
            }
            return text.toString();
        }
        if (stack.engineHost() != null && stack.engineHost().pause() != null) {
            return "paused: " + stack.engineHost().pause().describe();
        }
        // PistonOnly never ticks the engine, so the trap reports the shared Pauses settings itself.
        if (mode == Mode.PISTON_ONLY && stack.pistonTrap() != null && stack.pistonTrap().pause() != null) {
            return "paused: " + stack.pistonTrap().pause().describe();
        }
        if (mode != Mode.DIRECT_ONLY) {
            if (!trapEnabled) return "piston trap disabled";
            if (pistonLayouts.empty()) return "all piston layouts disabled";
            if (stack.pistonTrap() != null) {
                if (stack.pistonTrap().manualCleanupRequired()) {
                    // Manual cleanup is the default when auto-cleanup is off (or skipped a cell): list the
                    // exact blocks this trap placed so the player can remove them by hand before
                    // confirming. The beta auto path removes only inferred-ours cells.
                    String reason = stack.pistonTrap().lastFailure();
                    String blocks = stack.pistonTrap().manualCleanupText();
                    StringBuilder text = new StringBuilder("manual cleanup required");
                    if (!reason.isEmpty()) text.append(" (").append(reason).append(')');
                    text.append(": ");
                    if (!blocks.isEmpty()) text.append("remove ").append(blocks).append("; ");
                    text.append(".crystalcombat cleanup confirm");
                    return text.toString();
                }
                if (trapRearmPending) return "piston trap: re-arming after respawn or world change";
                var active = stack.pistonTrap().activeTrap();
                if (active.isPresent()) {
                    return "piston trap: " + active.orElseThrow().phase().name()
                        .toLowerCase(java.util.Locale.ROOT);
                }
                List<String> missing = stack.pistonTrap().missingMaterials();
                if (!missing.isEmpty()) {
                    return "piston trap: missing materials: "
                        + stack.pistonTrap().missingMaterialsText();
                }
            }
        }
        CombatEngine engine = engineOrNull();
        if (mode == Mode.HYBRID && engine != null && engine.cleanupPriority().state().due()) {
            return "cleanup due: waiting for the trap's cleanup slot";
        }
        EntityKey self = stack.client().selfKey();
        if (self != null && stack.risk().debt(self).count() > 0) return "waiting for your crystals to resolve";
        if (stack.client().crystals().isEmpty()) return "no crystals";
        if (mode == Mode.HYBRID) return "hybrid: idle";
        return "idle";
    }

    /** The last published visible status; safe from any thread and never queries the world. */
    public String infoString() {
        return info;
    }

    /**
     * The risk debt and suspension view published by the last client tick. Safe from any thread, never
     * queries the world, the budget or the ledger, and is cleared on disable and when leaving the world.
     * Render code may read only this.
     */
    public RiskDebtSnapshot riskSnapshot() {
        return riskSnapshot;
    }

    public boolean enabled() {
        return enabled;
    }

    /** True once a successful enable built the stack; stays true across disable for passive observation. */
    public boolean hasStack() {
        return stack != null;
    }

    /** The refusal reason from the last failed enable, or null. */
    public String refusalReason() {
        return refusalReason;
    }

    public CombatMetrics metrics() {
        return metrics;
    }

    public boolean sessionOpen() {
        return stack != null && stack.session().isOpen();
    }

    /**
     * Starts the opt-in in-memory debug log on the existing stack. Returns false when there is no stack yet;
     * the module retries after the next successful enable. Never throws: a diagnostics failure must not stop
     * combat.
     */
    public boolean startDebugLog(Minecraft mc, BooleanSupplier moduleEnabled) {
        if (stack == null) return false;
        if (debugHost != null && debugHost.running()) return true;
        debugHost = CombatDebugHost.start(mc, stack, moduleEnabled);
        if (debugHost != null) {
            // The budget already emits one line per model change and per BARRIER/METEOR-style retirement;
            // routing that sink into the recorder keeps those decisions in the exported debug log.
            stack.risk().setEventSink(debugHost::debug);
            debugMarker("combat mode=" + mode.name() + " hysteresis="
                + (mode == Mode.HYBRID ? "on" : "off"));
        }
        return debugHost != null;
    }

    /** Stops the debug taps; the rendered buffer is retained for one later export. */
    public void stopDebugLog() {
        if (debugHost == null) return;
        if (stack != null) stack.risk().setEventSink(null);
        debugHost.stop();
    }

    public boolean debugLogRunning() {
        return debugHost != null && debugHost.running();
    }

    /** Rolling five-minute debug lines, oldest first; empty when the log was never started. */
    public List<String> debugLines() {
        return debugHost == null ? List.of() : debugHost.lines();
    }

    public long debugDroppedLines() {
        return debugHost == null ? 0L : debugHost.droppedLines();
    }

    /** Adds a tester incident marker; false when the debug log is not running. */
    public boolean debugMarker(String text) {
        if (debugHost == null || !debugHost.running()) return false;
        debugHost.marker(text);
        return true;
    }

    Stack stack() {
        return stack;
    }

    private void refresh() {
        if (!enabled) {
            info = refusalReason != null ? refusalReason : DISABLED;
            return;
        }
        if (stack == null) {
            info = DISABLED;
            return;
        }
        if (!stack.session().isOpen()) {
            info = WAITING_FOR_CONNECTION;
            return;
        }
        info = waitReason();
    }

    private void sampleRisk() {
        EntityKey self = stack.client().selfKey();
        Debt debt = self == null ? Debt.zero() : stack.risk().debt(self);
        if (self != null) metrics.recordRiskDebt(debt.bound());
        Set<SuspensionCause> current = stack.risk().suspensionCauses();
        for (SuspensionCause cause : current) {
            if (!lastSuspensions.contains(cause)) metrics.recordSuspension(cause);
        }
        lastSuspensions.clear();
        lastSuspensions.addAll(current);
        // Publish one immutable view per client tick; the renderer only reads this volatile reference.
        riskSnapshot = new RiskDebtSnapshot(debt.count(), debt.bound(), List.copyOf(current));
    }
}
