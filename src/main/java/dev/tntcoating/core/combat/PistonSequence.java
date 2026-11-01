/*
 * Original Comet implementation by Boyonabike.
 * Independent: written from the pinned 26.2 artifact behaviour and the CrystalCombat v2.1 plan/audit; no
 * third-party code. Ordering ideas reimplemented from the public plan only.
 * SPDX-License-Identifier: CC0-1.0
 */

package dev.tntcoating.core.combat;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * Phase 5.3/5.4/5.5 pure single-push trap sequence.
 *
 * <p>The fixed order is RESERVE - SUPPORTS - PISTON - CRYSTAL - POWER - WAIT_PUSH - ATTACK - WAIT_REMOVE -
 * CLEANUP - COOLDOWN, plus the terminal ABORTED/MANUAL_CLEANUP states. The constructor rejects any plan in
 * which power is wired before the crystal; that known-bad order (powering the piston before the crystal
 * exists) is the HongShi/Alien defect, and it is refused in code as well as in tests.</p>
 *
 * <p>C21: WAIT_PUSH never waits for a crystal move observation. The client may not sync the pushed crystal at
 * all. The sequence waits for the authoritative piston block events, computes the final position from the
 * measured trace rule ({@code 1.02} blocks over two extension ticks) and uses that computed position with a
 * small tolerance for damage, reach and the attack. The client piston simulation is a cross-check only.</p>
 *
 * <p>Failure handling is exact: per-stage timeout is {@code max(20, RTT p99 + 5)}; structural retries stop at
 * two; an unresolved crystal placement is never retried; an ambiguous spawn is aborted/quarantined; a target
 * leaving the envelope aborts before POWER and after POWER only if the attack is no longer safe; failed
 * damage at the computed position insists on CLEANUP with no attack; deadlines change retry/backoff only and
 * never retire risk. At the trap deadline (100 ticks) broad locks are released while spatial/entity
 * exclusions and all risk are kept, and MANUAL_CLEANUP is visible. No new trap is begun until reconciled.</p>
 */
public final class PistonSequence {
    /** Trap deadline: at this age the trap releases broad locks and becomes visible manual cleanup. */
    public static final int TRAP_DEADLINE_TICKS = 100;
    /** Cleanup deadline: cleanup must finish or escalate within 40 ticks. */
    public static final int CLEANUP_DEADLINE_TICKS = 40;
    /** Structural stages may be retried at most twice. */
    public static final int MAX_STRUCTURAL_RETRIES = 2;
    /** Minimum stage timeout even on a zero-latency connection. */
    public static final int MIN_STAGE_TIMEOUT_TICKS = 20;
    /** RTT p99 padding for the per-stage timeout. */
    public static final int STAGE_TIMEOUT_PADDING = 5;
    /** Cross-check disagreement beyond this tolerance makes the computed position unsafe. */
    public static final double CROSS_CHECK_TOLERANCE = PistonGeometry.POSITION_TOLERANCE;

    public enum PistonPhase {
        RESERVE,
        SUPPORTS,
        PISTON,
        CRYSTAL,
        POWER,
        WAIT_PUSH,
        ATTACK,
        WAIT_REMOVE,
        CLEANUP,
        COOLDOWN,
        ABORTED,
        MANUAL_CLEANUP
    }

    /** Ownership is vanilla-unprovable by design; automatic mining needs CONFIRMED_OURS or an explicit inferred opt-in. */
    public enum Ownership {
        CONFIRMED_OURS,
        INFERRED_OURS,
        CONTESTED,
        UNKNOWN
    }

    public enum CleanupAction {
        NONE,
        MINE,
        MANUAL
    }

    /** Spawn binding facts: only a unique id+UUID match in the reserved source cell may bind. */
    public record Spawn(EntityKey entity, String entityType, UUID uuid, Cell cell) {
        public Spawn {
            Objects.requireNonNull(entity, "entity");
            Objects.requireNonNull(entityType, "entityType");
            Objects.requireNonNull(uuid, "uuid");
            Objects.requireNonNull(cell, "cell");
        }
    }

    public enum BindResult {
        UNIQUE,
        AMBIGUOUS,
        UNMATCHED,
        ALREADY_BOUND
    }

    /** Immutable sequence state for visibility and tests; risk is never retired by a deadline. */
    public record Flags(boolean disabled, boolean riskRetained, boolean broadLocksHeld,
                        boolean spatialExclusionsKept, boolean cleanupPending,
                        boolean manualCleanupRequired, boolean noNewTrapUntilReconciled,
                        boolean attackSafe, boolean waitingForCrystalMove) {
    }

    public record Config(int rttP99Ticks, int trapDeadlineTicks, int cleanupDeadlineTicks,
                         boolean allowInferredCleanup) {
        public Config {
            if (rttP99Ticks < 0 || trapDeadlineTicks < 1 || cleanupDeadlineTicks < 1) {
                throw new IllegalArgumentException("config values");
            }
        }

        public static Config defaults() {
            return new Config(0, TRAP_DEADLINE_TICKS, CLEANUP_DEADLINE_TICKS, false);
        }

        public int stageTimeoutTicks() {
            return Math.max(MIN_STAGE_TIMEOUT_TICKS, rttP99Ticks + STAGE_TIMEOUT_PADDING);
        }
    }

    /** Structural stages (and cleanup escalation) that may be retried a bounded number of times. */
    private static final Set<PistonPhase> STRUCTURAL_STAGES = EnumSet.of(PistonPhase.SUPPORTS,
        PistonPhase.PISTON, PistonPhase.POWER);

    private static final List<PistonPhase> ORDER = List.of(PistonPhase.RESERVE, PistonPhase.SUPPORTS,
        PistonPhase.PISTON, PistonPhase.CRYSTAL, PistonPhase.POWER, PistonPhase.WAIT_PUSH,
        PistonPhase.ATTACK, PistonPhase.WAIT_REMOVE, PistonPhase.CLEANUP, PistonPhase.COOLDOWN);

    private final PistonPlanner.Plan plan;
    private Config config;
    private final Vec computedPushed;
    private final List<PistonPhase> history = new ArrayList<>();

    private PistonPhase phase = PistonPhase.RESERVE;
    private boolean began;
    private boolean disabled;
    private boolean riskRetained;
    private boolean broadLocksHeld;
    private boolean spatialExclusionsKept = true;
    private boolean cleanupPending;
    private boolean manualCleanupRequired;
    private boolean noNewTrapUntilReconciled;
    private boolean attackSafe = true;
    private boolean waitingForCrystalMove;
    private boolean powerSent;
    private boolean crossCheckUsed;
    private boolean clientDisagreement;
    private Spawn bound;
    private int stageStartTick;
    private int beginTick;
    private int structuralRetries;
    private int damageCheckedTick = Integer.MIN_VALUE;

    public PistonSequence(PistonPlanner.Plan plan, Config config) {
        this.plan = Objects.requireNonNull(plan, "plan");
        this.config = Objects.requireNonNull(config, "config");
        this.computedPushed = plan.expectedPushed();
        validateOrder(plan);
        this.riskRetained = true;
        this.broadLocksHeld = true;
    }

    /** Rejects a plan whose power intent precedes the crystal intent (known-bad HongShi/Alien order). */
    private static void validateOrder(PistonPlanner.Plan plan) {
        List<Intent> intents = plan.intents();
        int crystalIndex = -1;
        int pistonIndex = -1;
        int powerIndex = -1;
        int supportsEnd = -1;
        for (int i = 0; i < intents.size(); i++) {
            Intent intent = intents.get(i);
            switch (intent.action()) {
                case PLACE_SUPPORT -> supportsEnd = i;
                case PLACE_PISTON -> {
                    if (pistonIndex >= 0) throw new IllegalArgumentException("duplicate piston intent");
                    pistonIndex = i;
                }
                case PLACE_CRYSTAL -> {
                    if (crystalIndex >= 0) throw new IllegalArgumentException("duplicate crystal intent");
                    crystalIndex = i;
                }
                case PLACE_POWER -> {
                    if (powerIndex >= 0) throw new IllegalArgumentException("duplicate power intent");
                    powerIndex = i;
                }
                default -> throw new IllegalArgumentException("unexpected action in piston plan: "
                    + intent.action());
            }
        }
        if (pistonIndex < 0 || crystalIndex < 0 || powerIndex < 0) {
            throw new IllegalArgumentException("power-before-crystal: plan must place piston, crystal, power");
        }
        if (powerIndex <= crystalIndex) {
            throw new IllegalArgumentException("power-before-crystal: PLACE_POWER must follow PLACE_CRYSTAL");
        }
        if (crystalIndex <= pistonIndex) {
            throw new IllegalArgumentException("power-before-crystal: PLACE_CRYSTAL must follow PLACE_PISTON");
        }
        if (crystalIndex <= supportsEnd) {
            throw new IllegalArgumentException("power-before-crystal: supports must precede the crystal");
        }
        for (Intent intent : intents) {
            for (Intent dependency : intent.dependsOn()) {
                if (!intents.contains(dependency)) {
                    throw new IllegalArgumentException("dependency outside plan: " + dependency.action());
                }
            }
        }
        for (Intent dependency : plan.crystalIntent().orElseThrow().dependsOn()) {
            if (dependency.action() != Action.PLACE_PISTON) {
                throw new IllegalArgumentException("crystal must depend on the piston");
            }
        }
        for (Intent dependency : plan.powerIntent().orElseThrow().dependsOn()) {
            if (dependency.action() != Action.PLACE_CRYSTAL) {
                throw new IllegalArgumentException("power-before-crystal: power must depend on the crystal");
            }
        }
    }

    // ------------------------------------------------------------------ lifecycle

    /** Begins the trap; returns false while another trap is active or the trap is not reconciled. */
    public boolean begin(int tick) {
        if (began || disabled) return false;
        began = true;
        beginTick = tick;
        stageStartTick = tick;
        history.add(PistonPhase.RESERVE);
        return true;
    }

    public boolean began() {
        return began;
    }

    public PistonPhase phase() {
        return phase;
    }

    public List<PistonPhase> history() {
        return List.copyOf(history);
    }

    public PistonPlanner.Plan plan() {
        return plan;
    }

    public Config config() {
        return config;
    }

    /**
     * Live setting change for the running trap: inferred ownership may only be mined while the beta
     * auto-cleanup setting is on. Nothing else about the sequence changes, and contested cells stay manual.
     */
    public void allowInferredCleanup(boolean allow) {
        if (config.allowInferredCleanup() == allow) return;
        config = new Config(config.rttP99Ticks(), config.trapDeadlineTicks(),
            config.cleanupDeadlineTicks(), allow);
    }

    /** Final crystal position computed from the authoritative piston events + measured push rule. */
    public Vec computedPushed() {
        return computedPushed;
    }

    public Flags flags() {
        return new Flags(disabled, riskRetained, broadLocksHeld, spatialExclusionsKept, cleanupPending,
            manualCleanupRequired, noNewTrapUntilReconciled, attackSafe, waitingForCrystalMove);
    }

    public Optional<Spawn> bound() {
        return Optional.ofNullable(bound);
    }

    public int structuralRetries() {
        return structuralRetries;
    }

    public boolean clientDisagreement() {
        return clientDisagreement;
    }

    public boolean crossCheckUsed() {
        return crossCheckUsed;
    }

    // ------------------------------------------------------------------ stage completion

    /** The reservation was accepted: SUPPORTS begins. */
    public void reservationAccepted(int tick) {
        transition(PistonPhase.SUPPORTS, tick);
    }

    /** A stage's placement/observation settled; advances along the fixed order. */
    public void stageSettled(PistonPhase stage, int tick) {
        if (stage != phase) return;
        switch (stage) {
            case SUPPORTS -> transition(PistonPhase.PISTON, tick);
            case PISTON -> transition(PistonPhase.CRYSTAL, tick);
            case CRYSTAL -> transition(PistonPhase.POWER, tick);
            case POWER -> {
                powerSent = true;
                transition(PistonPhase.WAIT_PUSH, tick);
                // C21: the push is never inferred from a crystal move; the authoritative piston events win.
                waitingForCrystalMove = false;
            }
            case WAIT_REMOVE -> transition(PistonPhase.CLEANUP, tick);
            case CLEANUP -> transition(PistonPhase.COOLDOWN, tick);
            case COOLDOWN -> {
                cleanupPending = false;
                broadLocksHeld = false;
                // riskRetained stays true: only the risk budget retires risk, on evidence.
                history.add(PistonPhase.COOLDOWN);
            }
            default -> {
            }
        }
    }

    /**
     * Authoritative piston progress (0..1) from block events. WAIT_PUSH completes on the measured extension,
     * without any crystal move observation, and computes the attack position from the measured rule.
     */
    public void pistonProgress(double progress, int tick) {
        if (phase != PistonPhase.WAIT_PUSH) return;
        if (progress < 1.0) {
            waitingForCrystalMove = false; // explicitly false: we are not waiting for the client crystal
            return;
        }
        if (tick - stageStartTick < PistonGeometry.EXTENSION_TICKS) return;
        transition(PistonPhase.ATTACK, tick);
    }

    /**
     * Client piston simulation cross-check. It never advances the phase and never supplies the attack
     * position; a disagreement beyond tolerance is fail-closed (no attack, keep the crystal protected).
     */
    public void clientPushObserved(Vec observedPosition, int tick) {
        crossCheckUsed = true;
        if (observedPosition == null) return;
        double displacement = horizontalDistance(plan.source().center(), observedPosition);
        if (!PistonGeometry.matchesTrace(displacement, PistonGeometry.MEASURED_DISPLACEMENT)) {
            clientDisagreement = true;
            attackSafe = false;
            cleanupPending = true;
            manualCleanupRequired = true;
            noNewTrapUntilReconciled = true;
            transition(PistonPhase.MANUAL_CLEANUP, tick);
        }
    }

    /**
     * Diagnostic: the sequence never retires risk. Only {@code CombatRiskBudget} may retire a reservation on
     * evidence; this stays zero for the sequence's whole lifetime.
     */
    public int riskRetirementRequests() {
        return 0;
    }

    // ------------------------------------------------------------------ spawn binding

    /**
     * Binds a spawn only when exactly one <em>new</em> spawn matches the reserved cell, after the crystal
     * placement tick, and no pre-existing/foreign identity is involved. The caller passes the ledger hazard's
     * baseline UUID/entity set so a foreign crystal, or one seen before our placement, can never be attacked.
     */
    public BindResult bindSpawn(List<Spawn> spawns, int tick) {
        return bindSpawn(spawns, Set.of(), Set.of(), tick);
    }

    public BindResult bindSpawn(List<Spawn> spawns, Set<UUID> baselineUuids, Set<EntityKey> baselineEntities,
                                int tick) {
        Objects.requireNonNull(spawns, "spawns");
        Objects.requireNonNull(baselineUuids, "baselineUuids");
        Objects.requireNonNull(baselineEntities, "baselineEntities");
        if (bound != null) return BindResult.ALREADY_BOUND;
        if (phase != PistonPhase.CRYSTAL) return BindResult.UNMATCHED;
        // A spawn observed at or before our crystal placement tick is pre-existing, never ours.
        if (tick <= stageStartTick) return BindResult.UNMATCHED;
        List<Spawn> matches = new ArrayList<>();
        for (Spawn spawn : spawns) {
            if (!DirectPlanner.END_CRYSTAL.equals(spawn.entityType())) continue;
            if (spawn.uuid() == null) continue;
            if (baselineUuids.contains(spawn.uuid()) || baselineEntities.contains(spawn.entity())) continue;
            matches.add(spawn);
        }
        if (matches.isEmpty()) return BindResult.UNMATCHED;
        if (matches.size() > 1) {
            // An ambiguous spawn is quarantined: the trap aborts with visible manual cleanup and keeps risk.
            manualCleanupRequired = true;
            cleanupPending = true;
            noNewTrapUntilReconciled = true;
            attackSafe = false;
            transition(PistonPhase.MANUAL_CLEANUP, tick);
            return BindResult.AMBIGUOUS;
        }
        Spawn match = matches.get(0);
        // The reserved cell must contain the spawn: a crystal elsewhere is not ours.
        if (!plan.source().equals(match.cell())) {
            cleanupPending = true;
            manualCleanupRequired = true;
            noNewTrapUntilReconciled = true;
            attackSafe = false;
            transition(PistonPhase.MANUAL_CLEANUP, tick);
            return BindResult.UNMATCHED;
        }
        bound = match;
        return BindResult.UNIQUE;
    }

    // ------------------------------------------------------------------ target movement

    /**
     * The target moved. Before POWER the trap aborts (no power is sent); after POWER the trap continues only
     * while the attack stays safe, otherwise it keeps the crystal protected and goes to manual cleanup.
     */
    public void targetMoved(Vec newPosition, boolean withinAttackEnvelope, int tick) {
        boolean beforePower = phase.ordinal() < PistonPhase.POWER.ordinal();
        if (beforePower) {
            abort("target-left-envelope", tick);
            return;
        }
        if (!withinAttackEnvelope) {
            manualCleanupRequired = true;
            cleanupPending = true;
            noNewTrapUntilReconciled = true;
            attackSafe = false;
            transition(PistonPhase.MANUAL_CLEANUP, tick);
        }
    }

    // ------------------------------------------------------------------ damage / attack

    /**
     * Damage evaluated at the computed pushed position. Below the minimum, the attack is refused, the crystal
     * stays protected, and cleanup begins. Never attacks from a best-case estimate.
     */
    public boolean damageAtComputedPosition(double damage, double minimumDamage, int tick) {
        if (phase != PistonPhase.ATTACK) return false;
        damageCheckedTick = tick;
        if (damage < minimumDamage) {
            attackSafe = false;
            cleanupPending = true;
            broadLocksHeld = false;
            transition(PistonPhase.CLEANUP, tick);
            return false;
        }
        return true;
    }

    public void attackSent(int tick) {
        if (phase != PistonPhase.ATTACK) return;
        transition(PistonPhase.WAIT_REMOVE, tick);
    }

    public void removalObserved(int tick) {
        if (phase != PistonPhase.WAIT_REMOVE) return;
        transition(PistonPhase.CLEANUP, tick);
    }

    public void cleanupCompleted(int tick) {
        if (phase != PistonPhase.CLEANUP) return;
        // riskRetained stays true: cleanup completion never retires risk.
        broadLocksHeld = false;
        cleanupPending = false;
        transition(PistonPhase.COOLDOWN, tick);
    }

    public void cooldownCompleted(int tick) {
        if (phase != PistonPhase.COOLDOWN) return;
        // riskRetained stays true: cooldown completion never retires risk.
        broadLocksHeld = false;
    }

    /** Ownership-aware cleanup: automatic mining for CONFIRMED_OURS, inferred only when explicitly enabled. */
    public CleanupAction cleanupAction(Ownership ownership) {
        Objects.requireNonNull(ownership, "ownership");
        return switch (ownership) {
            case CONFIRMED_OURS -> CleanupAction.MINE;
            case INFERRED_OURS -> config.allowInferredCleanup() ? CleanupAction.MINE : CleanupAction.MANUAL;
            case CONTESTED, UNKNOWN -> CleanupAction.MANUAL;
        };
    }

    // ------------------------------------------------------------------ failures / deadlines

    /** A failed non-structural stage: CRYSTAL is never retried while unresolved. */
    public void stageFailed(PistonPhase stage, String reason, int tick) {
        if (stage != phase) return;
        if (STRUCTURAL_STAGES.contains(stage) && structuralRetries < MAX_STRUCTURAL_RETRIES) {
            structuralRetries++;
            stageStartTick = tick;
            return;
        }
        if (stage == PistonPhase.CRYSTAL) {
            // Unresolved crystal placement: abort, keep it protected, no retry, manual cleanup visible.
            manualCleanupRequired = true;
            cleanupPending = true;
            noNewTrapUntilReconciled = true;
            attackSafe = false;
            transition(PistonPhase.MANUAL_CLEANUP, tick);
            return;
        }
        abort(reason, tick);
    }

    /**
     * Fail closed on a block observation that proves the trap is not the one we placed (wrong block,
     * wrong facing, already extended). This is deliberately not a structural retry: the cell may now hold
     * someone else's block, so the whole trap aborts and the hazards/risk stay with the ledger.
     */
    public void blockRejected(String reason, int tick) {
        if (!began || phase == PistonPhase.ABORTED || phase == PistonPhase.MANUAL_CLEANUP) return;
        abort(reason, tick);
    }

    /**
     * Abort for a whole-trap reason that is not a failed placement (for example the target left the hole):
     * structural retries never apply, even when the current phase is structural.
     */
    public void abortTrap(String reason, int tick) {
        if (!began || phase == PistonPhase.ABORTED || phase == PistonPhase.MANUAL_CLEANUP) return;
        abort(reason, tick);
    }

    /** Advance deadlines. Returns a non-empty string when this tick changed the trap state. */
    public Optional<String> tick(int tick) {
        if (!began || phase == PistonPhase.ABORTED || phase == PistonPhase.MANUAL_CLEANUP) {
            return Optional.empty();
        }
        if (tick - beginTick >= config.trapDeadlineTicks()) {
            // Deadline changes retry/backoff only: risk stays, broad locks are released, exclusions stay.
            broadLocksHeld = false;
            cleanupPending = true;
            manualCleanupRequired = true;
            noNewTrapUntilReconciled = true;
            attackSafe = false;
            transition(PistonPhase.MANUAL_CLEANUP, tick);
            return Optional.of("trap-deadline");
        }
        int timeout = phase == PistonPhase.CLEANUP ? config.cleanupDeadlineTicks()
            : config.stageTimeoutTicks();
        if (tick - stageStartTick > timeout) {
            if (phase == PistonPhase.CLEANUP) {
                // The cleanup deadline is a deadline, not evidence: end in the documented manual fallback
                // with the remaining blocks still listed. Risk stays retained and nothing is retired.
                transition(PistonPhase.MANUAL_CLEANUP, tick);
                return Optional.of("cleanup-deadline");
            }
            stageFailed(phase, "timeout", tick);
            return Optional.of("timeout:" + phase.name().toLowerCase(java.util.Locale.ROOT));
        }
        return Optional.empty();
    }

    /** Disable: stop sending immediately, keep hazards and risk, never emit a cleanup packet. */
    public void disable() {
        disabled = true;
        cleanupPending = false;
        riskRetained = true;
        broadLocksHeld = false;
        manualCleanupRequired = true;
        noNewTrapUntilReconciled = true;
        history.add(PistonPhase.MANUAL_CLEANUP);
        phase = PistonPhase.MANUAL_CLEANUP;
    }

    /** Death: end the generation, keep hazards and risk; cleanup stays manual. */
    public void death() {
        riskRetained = true;
        broadLocksHeld = false;
        manualCleanupRequired = true;
        noNewTrapUntilReconciled = true;
        cleanupPending = false;
        history.add(PistonPhase.MANUAL_CLEANUP);
        phase = PistonPhase.MANUAL_CLEANUP;
    }

    /** Respawn: rescan, cleanup stays manual; no silent continuation of the old trap. */
    public void respawn() {
        riskRetained = true;
        broadLocksHeld = false;
        manualCleanupRequired = true;
        noNewTrapUntilReconciled = true;
        history.add(PistonPhase.MANUAL_CLEANUP);
        phase = PistonPhase.MANUAL_CLEANUP;
    }

    /** Reconciliation clears the no-new-trap gate; risk is retired elsewhere, never here. */
    public void reconcile() {
        noNewTrapUntilReconciled = false;
    }

    // ------------------------------------------------------------------ internals

    private void transition(PistonPhase next, int tick) {
        if (next == phase) return;
        phase = next;
        stageStartTick = tick;
        history.add(next);
        if (next == PistonPhase.MANUAL_CLEANUP || next == PistonPhase.ABORTED) {
            broadLocksHeld = false;
            riskRetained = true;
            cleanupPending = true;
            manualCleanupRequired = true;
            noNewTrapUntilReconciled = true;
        }
        if (next == PistonPhase.CLEANUP) cleanupPending = true;
    }

    private void abort(String reason, int tick) {
        attackSafe = false;
        cleanupPending = true;
        broadLocksHeld = false;
        riskRetained = true;
        manualCleanupRequired = true;
        noNewTrapUntilReconciled = true;
        history.add(PistonPhase.ABORTED);
        phase = PistonPhase.ABORTED;
        stageStartTick = tick;
    }

    private static double horizontalDistance(Vec a, Vec b) {
        double dx = a.x() - b.x();
        double dz = a.z() - b.z();
        return Math.sqrt(dx * dx + dz * dz);
    }

    /**
     * One-trap slot. Only one active trap exists at a time and a new one cannot start until the old one is
     * reconciled.
     */
    public static final class TrapSlot {
        private PistonSequence active;

        public boolean begin(PistonSequence candidate, int tick) {
            if (active != null && active != candidate) return false;
            if (candidate.flags().noNewTrapUntilReconciled()) return false;
            if (!candidate.begin(tick)) return false;
            active = candidate;
            return true;
        }

        public void release(PistonSequence trap) {
            if (active == trap) active = null;
        }

        public Optional<PistonSequence> active() {
            return Optional.ofNullable(active);
        }
    }
}
