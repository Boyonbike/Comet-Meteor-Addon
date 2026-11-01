/*
 * Original Comet implementation by Boyonabike.
 * Independent: written from the pinned 26.2 artifact behaviour, the CrystalCombat v2.1 plan and the
 * project's own Phase 5 contracts; no third-party code.
 * SPDX-License-Identifier: CC0-1.0
 */

package dev.tntcoating.core.combat.mc;

import dev.tntcoating.core.combat.Action;
import dev.tntcoating.core.combat.Box;
import dev.tntcoating.core.combat.Capability;
import dev.tntcoating.core.combat.Cell;
import dev.tntcoating.core.combat.CombatDamagePolicy;
import dev.tntcoating.core.combat.CombatRiskBudget;
import dev.tntcoating.core.combat.CombatScheduler;
import dev.tntcoating.core.combat.CombatSession;
import dev.tntcoating.core.combat.CrystalLedger;
import dev.tntcoating.core.combat.DirectPlanner;
import dev.tntcoating.core.combat.EffectiveHealth;
import dev.tntcoating.core.combat.EntityKey;
import dev.tntcoating.core.combat.ExposureBound;
import dev.tntcoating.core.combat.FootprintConflict;
import dev.tntcoating.core.combat.HazardId;
import dev.tntcoating.core.combat.Intent;
import dev.tntcoating.core.combat.MitigatedDamage;
import dev.tntcoating.core.combat.MitigationEnvelope;
import dev.tntcoating.core.combat.Opportunity;
import dev.tntcoating.core.combat.PistonOpportunity;
import dev.tntcoating.core.combat.PistonFacing;
import dev.tntcoating.core.combat.PistonGeometry;
import dev.tntcoating.core.combat.PistonLayoutSelection;
import dev.tntcoating.core.combat.PistonPlanner;
import dev.tntcoating.core.combat.PistonSequence;
import dev.tntcoating.core.combat.PlacementId;
import dev.tntcoating.core.combat.RiskId;
import dev.tntcoating.core.combat.RiskRequest;
import dev.tntcoating.core.combat.Safety;
import dev.tntcoating.core.combat.SafetyState;
import dev.tntcoating.core.combat.ServerTickInterval;
import dev.tntcoating.core.combat.Strategy;
import dev.tntcoating.core.combat.TargetEstimate;
import dev.tntcoating.core.combat.TickBudget;
import dev.tntcoating.core.combat.Ticket;
import dev.tntcoating.core.combat.Vec;
import dev.tntcoating.core.combat.mc.CombatRuntime.PlanContext;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * Phase 5.1 client-thread piston trap runtime. Pure planning and sequencing stay in {@link PistonPlanner}
 * and {@link PistonSequence}; this class only reserves through the scheduler, dispatches through
 * {@link CombatRuntime}, and feeds observations back into the sequence.
 *
 * <p>Single trap: a plan is scanned for only while no trap exists; the slot is held until
 * {@link #reconcile()} after manual/automatic cleanup. One send path: every packet leaves from
 * {@link #tick(int, TickBudget)}; observation callbacks only record facts. Risk: structural intents reserve
 * a zero-bound exposure and the crystal intent is the first and only reservation carrying the planner's
 * worst-case self/friend bounds. The crystal risk is never released here, including on abort, timeout,
 * disable, death or connection close.</p>
 */
public final class PistonTrapController {
    /** Packet cost shared with the direct engine's single-plan budget. */
    public static final int PACKET_COST = 2;
    /** Vanilla item ids used by the structural intents. */
    public static final String SUPPORT_ITEM = "minecraft:obsidian";
    public static final String PISTON_ITEM = "minecraft:piston";
    public static final String CRYSTAL_ITEM = DirectPlanner.END_CRYSTAL;
    private static final int ATTACK_ID_OFFSET = 1000;
    /** Documented computed/client position tolerance for the diagnostic cross-check. */
    public static final double POSITION_TOLERANCE = PistonSequence.CROSS_CHECK_TOLERANCE;

    /**
     * Tester diagnostics seam. Implementations only append to the opt-in in-memory debug log; nothing here
     * sends, reserves or changes a decision. {@link #NONE} leaves every hook dormant.
     */
    public interface Diagnostics {
        Diagnostics NONE = new Diagnostics() {
            @Override
            public void debug(String line) {
            }

            @Override
            public void incident(String line) {
            }
        };

        void debug(String line);

        void incident(String line);
    }

    /** The inventory facts dispatch revalidates; a fake satisfies it in tests. */
    public interface Inventory {
        boolean ownedElsewhere();

        boolean screenOpen();

        int selectedSlot();

        String hotbarItem(int slot);

        String offhandItem();
    }

    /**
     * Everything the trap needs from the world. Planner methods are inherited; the remaining defaults are
     * fail-closed (no health means no usable target estimate, no piston state means no invented progress).
     */
    public interface World extends PistonPlanner.World {
        default List<EntityKey> targets() {
            return List.of();
        }

        default boolean alive(EntityKey entity) {
            return true;
        }

        default EffectiveHealth health(EntityKey entity) {
            return new EffectiveHealth(0.0, 0.0, Capability.UNKNOWN, 0L);
        }

        default Map<EntityKey, EffectiveHealth> friendHealth() {
            return Map.of();
        }

        default Safety safety() {
            return new Safety(minimumTargetDamage(), selfDamageCeiling(),
                CombatDamagePolicy.MIN_REMAINING_HEALTH, friendDamageCeiling());
        }

        default long mitigationRevision() {
            return 0L;
        }

        default boolean withinAttackEnvelope(EntityKey target, Vec pushed) {
            return position(target).distance(pushed) <= 4.5;
        }

        default UUID uuid(EntityKey entity) {
            return null;
        }

        default Set<EntityKey> crystalEntities() {
            return Set.of();
        }

        default Set<UUID> crystalUuids() {
            return Set.of();
        }

        /**
         * The client's observed crystal position when one exists. The client suppresses crystal position
         * sync (C21), so callers must treat an empty value as "no observation", not as a disagreement.
         */
        default Optional<Vec> crystalClientPosition(EntityKey entity) {
            return Optional.empty();
        }

        /** Production sample point: refresh the read view before planning. Fakes keep the default. */
        default void onServerTick(int serverTick) {
        }
    }

    private final CombatRuntime runtime;
    private final CombatSession session;
    private final CombatScheduler scheduler;
    private final CombatRiskBudget risk;
    private final CrystalLedger ledger;
    private final Inventory inventory;
    private final CombatClient client;
    private final World world;
    private final PistonPlanner planner = new PistonPlanner();
    private final PistonSequence.TrapSlot slot = new PistonSequence.TrapSlot();
    /** Phase 6.4 ranking-only scan; never begun, leased or sent until Hybrid selects it. */
    private PistonPlanner.Plan preview;
    private final List<PistonSequence.Spawn> spawns = new ArrayList<>();

    /**
     * The crystal identities that existed when the placement was sent. The live world changes between
     * dispatch and bind: the crystal we placed is in it by then, so only this pre-send snapshot can
     * distinguish our new spawn from crystals that were already present.
     */
    private Set<UUID> crystalBaselineUuids = Set.of();
    private Set<EntityKey> crystalBaselineEntities = Set.of();

    /**
     * A structural packet is only a request. Nothing advances until the server-authored block state for
     * the applied cell has been observed after the packet was accepted; the serial makes an observation
     * that predates the send (or a stale pre-existing block) unusable.
     */
    private record Pending(PistonSequence.PistonPhase stage, Cell cell, String blockId,
                           PistonFacing facing, long sentSerial) {
    }

    private Pending pending;
    private long blockObservationSerial;
    private boolean enabled = true;
    private boolean reconciled = true;
    private PistonSequence sequence;
    private Ticket ticket;
    private int supportIndex;
    private int lastServerTick = -1;
    private boolean crystalDispatched;
    private boolean powerDispatched;
    private boolean attackChecked;
    private boolean attackAttempted;
    private boolean spawnRejected;
    private boolean earlyPowerObserved;
    private boolean wrongFacingObserved;
    private int crystalSentTick = -1;
    private HazardId hazard;
    private PlacementId placement;
    private RiskId crystalRisk;
    private Vec lastTargetPosition;
    private String lastFailure = "";
    private PistonLayoutSelection layouts = PistonLayoutSelection.all();
    private Diagnostics diagnostics = Diagnostics.NONE;
    /** Last phase the debug tap saw; only the diagnostic tap compares it. */
    private PistonSequence.PistonPhase observedPhase;
    private boolean positionChecked;

    public PistonTrapController(CombatRuntime runtime, CombatSession session, CombatScheduler scheduler,
                                CombatRiskBudget risk, CrystalLedger ledger, Inventory inventory,
                                CombatClient client, World world) {
        this.runtime = Objects.requireNonNull(runtime, "runtime");
        this.session = Objects.requireNonNull(session, "session");
        this.scheduler = Objects.requireNonNull(scheduler, "scheduler");
        this.risk = Objects.requireNonNull(risk, "risk");
        this.ledger = Objects.requireNonNull(ledger, "ledger");
        this.inventory = Objects.requireNonNull(inventory, "inventory");
        this.client = Objects.requireNonNull(client, "client");
        this.world = Objects.requireNonNull(world, "world");
    }

    public static Inventory inventory(CombatInventoryAdapter adapter) {
        Objects.requireNonNull(adapter, "adapter");
        return new Inventory() {
            @Override
            public boolean ownedElsewhere() {
                return adapter.ownedElsewhere();
            }

            @Override
            public boolean screenOpen() {
                return adapter.screenOpen();
            }

            @Override
            public int selectedSlot() {
                return adapter.selectedSlot();
            }

            @Override
            public String hotbarItem(int hotbarSlot) {
                return adapter.hotbarItem(hotbarSlot);
            }

            @Override
            public String offhandItem() {
                return adapter.offhandItem();
            }
        };
    }

    // ------------------------------------------------------------------ lifecycle

    public boolean enabled() {
        return enabled;
    }

    public Optional<PistonSequence> activeTrap() {
        return Optional.ofNullable(sequence);
    }

    /**
     * Phase 6.4: ranking-only view of the live trap plan. Empty while no trap is active or a field the
     * choice needs cannot be established; the caller reports that no piston opportunity was offered.
     */
    public Optional<Opportunity> rankedPlan() {
        PistonSequence active = sequence;
        if (active == null || active.plan() == null) return Optional.empty();
        return PistonOpportunity.of(active.plan(),
            new MitigationEnvelope(world.mitigationRevision()));
    }

    /**
     * Phase 6.3: the trap-side footprint the Hybrid gate compares a direct plan against. Empty while no
     * trap is active; the engine still treats an active trap with an empty view as "direct waits" and never
     * as compatible, so a missing ranking view can never open the concurrency path.
     *
     * <p>The resource claims mirror the exact dispatches this tick may attempt: structural and crystal
     * stages rotate and may need a hotbar slot, the attack stage rotates; a RESERVE-phase trap claims both
     * conservatively because its first structural dispatch is still ahead. This method never sends and
     * never reserves anything.</p>
     */
    public Optional<FootprintConflict.TrapView> conflictView() {
        PistonSequence active = sequence;
        if (active == null || active.plan() == null) return Optional.empty();
        PistonPlanner.Plan plan = active.plan();
        Vec targetFeet = plan.target() == null ? null : world.position(plan.target());
        Optional<PistonSequence.Spawn> bound = active.bound();
        EntityKey crystal = bound.map(PistonSequence.Spawn::entity).orElse(null);
        Vec crystalPosition = bound.map(spawn -> spawn.cell().center())
            .orElseGet(() -> plan.push().source() == null ? null : plan.push().source().center());
        ExposureBound exposure = plan.exposure(new MitigationEnvelope(world.mitigationRevision()));
        boolean needsSlot = false;
        boolean needsRotation = false;
        PistonSequence.PistonPhase phase = active.phase();
        if (phase == PistonSequence.PistonPhase.RESERVE || phase == PistonSequence.PistonPhase.SUPPORTS
            || phase == PistonSequence.PistonPhase.PISTON || phase == PistonSequence.PistonPhase.CRYSTAL
            || phase == PistonSequence.PistonPhase.POWER) {
            needsRotation = true;
            needsSlot = stageNeedsSlot(phase);
        } else if (phase == PistonSequence.PistonPhase.ATTACK) {
            needsRotation = true;
        }
        return FootprintConflict.trapView(plan, crystal, crystalPosition, targetFeet, exposure,
            needsSlot, needsRotation);
    }

    /** Whether the next dispatch of this stage would need a hotbar slot change. */
    private boolean stageNeedsSlot(PistonSequence.PistonPhase phase) {
        return switch (phase) {
            case RESERVE -> true;
            case SUPPORTS -> needsSlotFor(SUPPORT_ITEM);
            case PISTON -> needsSlotFor(PISTON_ITEM);
            case CRYSTAL -> needsSlotFor(CRYSTAL_ITEM);
            case POWER -> needsSlotFor(sequence.plan().push().powerKind().itemId());
            default -> false;
        };
    }

    private boolean needsSlotFor(String itemId) {
        int slot = hotbarSlot(itemId);
        return slot >= 0 && slot != inventory.selectedSlot();
    }

    /**
     * Phase 6.4: scans for a ranking-only candidate without beginning the trap. The scan is the same one a
     * normal start uses, so the plan the engine ranks is the plan that starts; no slot, lease, hazard or
     * send is touched. Empty while a trap is already active or a new trap is gated by reconciliation.
     */
    public Optional<Opportunity> previewPlan(TickBudget budget) {
        preview = null;
        if (!enabled || sequence != null || !reconciled || !session.isOpen() || budget == null) {
            return Optional.empty();
        }
        if (!budget.spend(TickBudget.Phase.PLANNING)) return Optional.empty();
        Optional<PistonPlanner.Plan> plan = planner.tick(world, world.targets(), 8).plan();
        if (plan.isEmpty()) return Optional.empty();
        preview = plan.orElseThrow();
        return PistonOpportunity.of(preview, new MitigationEnvelope(world.mitigationRevision()));
    }

    /** Phase 6.4: starts exactly the previewed plan after Hybrid selected it; never scans again. */
    public boolean startPreviewed(int serverTick) {
        PistonPlanner.Plan plan = preview;
        preview = null;
        if (plan == null || !enabled || !session.isOpen()) return false;
        return beginPlan(plan, serverTick);
    }

    /** Installs the exact sixteen-combination beta layout filter; empty disables planning entirely. */
    public void setLayouts(PistonLayoutSelection layouts) {
        this.layouts = layouts == null ? PistonLayoutSelection.all() : layouts;
        planner.setSelection(this.layouts);
    }

    public PistonLayoutSelection layouts() {
        return layouts;
    }

    /** Installs the debug/incident tap; {@code null} restores the dormant no-op. */
    public void setDiagnostics(Diagnostics diagnostics) {
        this.diagnostics = diagnostics == null ? Diagnostics.NONE : diagnostics;
    }

    public PistonSequence.PistonPhase phase() {
        return sequence == null ? PistonSequence.PistonPhase.RESERVE : sequence.phase();
    }

    public String lastFailure() {
        return lastFailure;
    }

    public boolean spawnRejected() {
        return spawnRejected;
    }

    public boolean earlyPowerObserved() {
        return earlyPowerObserved;
    }

    public boolean wrongFacingObserved() {
        return wrongFacingObserved;
    }

    /** Manual cleanup is visible; no cleanup packet is ever emitted by this controller. */
    public boolean manualCleanupRequired() {
        return sequence != null && sequence.flags().manualCleanupRequired();
    }

    public boolean riskRetained() {
        return sequence == null || sequence.flags().riskRetained();
    }

    /** True while a sent structural placement is waiting for its block observation. */
    public boolean awaitingBlockConfirmation() {
        return pending != null;
    }

    public Optional<Cell> pendingCell() {
        return pending == null ? Optional.empty() : Optional.of(pending.cell());
    }

    /** Disable stops all further ticks/sends without releasing hazards, risk or the trap slot. */
    public void disable() {
        enabled = false;
        pending = null;
        if (sequence != null) sequence.disable();
        tracePhase();
    }

    /** Re-arm after a disable; the sequence (and its retained risk) still decides what may run. */
    public void enable() {
        enabled = true;
    }

    public void death() {
        enabled = false;
        pending = null;
        if (sequence != null) sequence.death();
        tracePhase();
    }

    public void respawn() {
        pending = null;
        if (sequence != null) sequence.respawn();
    }

    /** Connection close behaves like disable for hazards/risk: nothing is cleaned up or released. */
    public void connectionClosed() {
        enabled = false;
        pending = null;
        if (sequence != null) sequence.disable();
    }

    /** Explicit reconciliation releases the single-trap slot; hazards and risk stay with the ledger. */
    public void reconcile() {
        if (sequence != null) {
            // Release the plan's physical locks/leases. The scheduler keeps any SENT risk as debt; only
            // unsent (never transmitted) work is retired. Hazards remain with the ledger.
            scheduler.release(sequence.plan().planId());
            sequence.reconcile();
        }
        slot.release(sequence);
        sequence = null;
        ticket = null;
        supportIndex = 0;
        lastServerTick = -1;
        crystalDispatched = false;
        powerDispatched = false;
        attackChecked = false;
        attackAttempted = false;
        spawnRejected = false;
        earlyPowerObserved = false;
        wrongFacingObserved = false;
        crystalSentTick = -1;
        hazard = null;
        placement = null;
        crystalRisk = null;
        crystalBaselineUuids = Set.of();
        crystalBaselineEntities = Set.of();
        lastTargetPosition = null;
        pending = null;
        blockObservationSerial = 0;
        reconciled = true;
        spawns.clear();
        lastFailure = "";
        observedPhase = null;
        positionChecked = false;
        planner.reset();
    }

    // ------------------------------------------------------------------ observations (record only)

    public void onCrystalSpawn(EntityKey entity, String entityType, UUID uuid, Cell cell) {
        if (!enabled || entity == null || entityType == null || uuid == null || cell == null) return;
        spawns.add(new PistonSequence.Spawn(entity, entityType, uuid, cell));
    }

    public void onCrystalRemoved(EntityKey entity) {
        if (!enabled || sequence == null || entity == null) return;
        if (sequence.bound().map(PistonSequence.Spawn::entity).filter(entity::equals).isPresent()) {
            sequence.removalObserved(lastServerTick);
        }
    }

    public void onPistonUpdate(Cell cell) {
        onBlockUpdate(cell);
    }

    /**
     * A server-authored block event. This is the only way a structural placement is confirmed: the
     * pending cell must match and the event must be newer than the send. The callback never dispatches.
     */
    public void onBlockUpdate(Cell cell) {
        if (!enabled || cell == null) return;
        blockObservationSerial++;
        observePistonState(cell);
        confirmBlock(cell);
    }

    public void onTargetChanged(EntityKey target) {
        // Movement/health facts are read from the world on the next tick; never dispatch from an event.
    }

    // ------------------------------------------------------------------ tick

    public void tick(int serverTick, TickBudget budget) {
        if (!enabled || serverTick < 0 || budget == null) return;
        try {
            tickInternal(serverTick, budget);
        } finally {
            tracePhase();
        }
    }

    private void tickInternal(int serverTick, TickBudget budget) {
        lastServerTick = serverTick;
        world.onServerTick(serverTick);
        if (sequence != null) {
            Optional<String> tickReason = sequence.tick(serverTick);
            if (tickReason.isPresent()) {
                boolean awaiting = pending != null;
                String reason = tickReason.get();
                debug("stage-tick reason=" + reason + " stage=" + sequence.phase()
                    + " awaiting-block=" + awaiting);
                if (awaiting && reason.startsWith("timeout")) {
                    incident("block-confirmation-timeout stage=" + pending.stage()
                        + " cell=" + pending.cell() + " reason=" + reason);
                }
                // A timeout/retry invalidates any in-flight confirmation: a late event for the old
                // attempt must never advance the retried stage.
                pending = null;
                return;
            }
        }
        if (sequence == null) {
            scan(serverTick, budget);
            return;
        }
        if (terminal()) {
            pending = null;
            return;
        }
        observeTarget(serverTick);
        observePower(serverTick);
        observePiston(serverTick);
        switch (sequence.phase()) {
            case RESERVE -> sequence.reservationAccepted(serverTick);
            case SUPPORTS -> dispatchSupports(serverTick);
            case PISTON -> dispatchPiston(serverTick);
            case CRYSTAL -> crystalStage(serverTick);
            case POWER -> dispatchPower(serverTick);
            case WAIT_PUSH -> waitPush(serverTick);
            case ATTACK -> attack(serverTick);
            case WAIT_REMOVE, CLEANUP, COOLDOWN -> {
                // Automatic cleanup/mining is out of scope; removal and manual cleanup only mark state.
            }
            default -> {
            }
        }
    }

    // ------------------------------------------------------------------ scan

    private void scan(int serverTick, TickBudget budget) {
        if (!reconciled || !session.isOpen()) return;
        PistonPlanner.Plan plan = preview;
        preview = null;
        if (plan == null) {
            if (!budget.spend(TickBudget.Phase.PLANNING)) return;
            PistonPlanner.Result result = planner.tick(world, world.targets(), 8);
            if (result.plan().isEmpty()) return;
            plan = result.plan().orElseThrow();
        }
        beginPlan(plan, serverTick);
    }

    private boolean beginPlan(PistonPlanner.Plan plan, int serverTick) {
        PistonSequence candidate = new PistonSequence(plan, PistonSequence.Config.defaults());
        if (!slot.begin(candidate, serverTick)) return false;
        sequence = candidate;
        observedPhase = candidate.phase();
        positionChecked = false;
        debug("trap-plan id=" + plan.planId() + " layout=" + plan.push().scenario()
            + " power=" + plan.push().powerKind() + " source=" + plan.push().source()
            + " piston=" + plan.push().piston() + " power-cell=" + plan.push().power()
            + " computed-final=" + plan.expectedPushed() + " target-damage="
            + plan.targetDamage());
        ticket = session.ticket(plan.planId());
        reconciled = false;
        supportIndex = 0;
        spawns.clear();
        crystalBaselineUuids = Set.of();
        crystalBaselineEntities = Set.of();
        lastTargetPosition = world.position(plan.target());
        candidate.reservationAccepted(serverTick);
        return true;
    }

    // ------------------------------------------------------------------ structural dispatch

    private void dispatchSupports(int serverTick) {
        if (pending != null && pending.stage() == PistonSequence.PistonPhase.SUPPORTS) return;
        List<Intent> supports = sequence.plan().intents().stream()
            .filter(intent -> intent.action() == Action.PLACE_SUPPORT).toList();
        if (supportIndex >= supports.size()) {
            sequence.stageSettled(PistonSequence.PistonPhase.SUPPORTS, serverTick);
            return;
        }
        Intent intent = supports.get(supportIndex);
        if (!dispatchStructural(intent, SUPPORT_ITEM, null, serverTick)) return;
        awaitBlock(PistonSequence.PistonPhase.SUPPORTS, intent.cell(), SUPPORT_ITEM, null);
    }

    private void dispatchPiston(int serverTick) {
        if (pending != null && pending.stage() == PistonSequence.PistonPhase.PISTON) return;
        Intent intent = sequence.plan().pistonIntent().orElseThrow();
        PistonFacing facing = sequence.plan().push().facing();
        if (!dispatchStructural(intent, PISTON_ITEM, facing, serverTick)) return;
        awaitBlock(PistonSequence.PistonPhase.PISTON, intent.cell(), PISTON_ITEM, facing);
    }

    private void dispatchPower(int serverTick) {
        if (pending != null && pending.stage() == PistonSequence.PistonPhase.POWER) return;
        Intent intent = sequence.plan().powerIntent().orElseThrow();
        String itemId = sequence.plan().push().powerKind().itemId();
        if (!dispatchStructural(intent, itemId, null, serverTick)) return;
        awaitBlock(PistonSequence.PistonPhase.POWER, intent.cell(), itemId, null);
    }

    private boolean dispatchStructural(Intent intent, String itemId, PistonFacing facing, int serverTick) {
        int hotbarSlot = hotbarSlot(itemId);
        boolean needsSlot = hotbarSlot >= 0 && hotbarSlot != inventory.selectedSlot();
        int expectedSlot = hotbarSlot >= 0 ? hotbarSlot : inventory.selectedSlot();
        SafetyState state = safetyState();
        PlanContext context = new PlanContext(state, zeroBound(), targetEstimate(), targetHealth(),
            true, expectedSlot, itemId, inventory.selectedSlot(), inventory.offhandItem(), false, false,
            false, facing, true);
        RiskRequest request = new RiskRequest(zeroBound(), arrival(serverTick), state, null, null,
            PACKET_COST, serverTick, needsSlot, true, false, sequence.plan().footprint());
        if (!scheduler.reserve(intent, ticket, request)) {
            // No packet was sent; a transient lease loss retries next tick. The deadline handles a stall.
            return false;
        }
        runtime.plan(intent, context);
        CombatRuntime.DispatchResult result = runtime.dispatch(intent, ticket);
        if (result.status() == CombatRuntime.Status.SENT) return true;
        if (result.status() == CombatRuntime.Status.FAILED) {
            fail(sequence.phase(), result.reason().name().toLowerCase(java.util.Locale.ROOT), serverTick);
        }
        return false;
    }

    /** Arms the block-confirmation gate for the structural intent just accepted by the server. */
    private void awaitBlock(PistonSequence.PistonPhase stage, Cell cell, String blockId,
                            PistonFacing facing) {
        pending = new Pending(stage, cell, blockId, facing, blockObservationSerial);
    }

    private boolean terminal() {
        return sequence == null
            || sequence.phase() == PistonSequence.PistonPhase.MANUAL_CLEANUP
            || sequence.phase() == PistonSequence.PistonPhase.ABORTED;
    }

    /**
     * Confirms only the exact applied block for the cell that is pending, from a fresh observation.
     * Anything else is an abort: the trap's structural assumptions no longer hold, so no retry may reuse
     * the same layout and the hazard/risk stay in the ledger.
     */
    private void confirmBlock(Cell cell) {
        Pending expected = pending;
        if (expected == null || !expected.cell().equals(cell) || sequence == null) return;
        if (sequence.phase() != expected.stage()) return;
        if (blockObservationSerial <= expected.sentSerial()) return;
        String block = world.blockId(cell);
        switch (expected.stage()) {
            case SUPPORTS -> {
                if (!expected.blockId().equals(block) || world.replaceable(cell)) {
                    rejectBlock("wrong-block");
                    return;
                }
            }
            case PISTON -> {
                if (!PISTON_ITEM.equals(block)) {
                    rejectBlock("wrong-block");
                    return;
                }
                PistonFacing observed = world.pistonFacing(cell);
                if (observed == null || !observed.equals(expected.facing())) {
                    wrongFacingObserved = true;
                    rejectBlock("wrong-facing");
                    return;
                }
                if (world.pistonExtended(cell)) {
                    rejectBlock("piston-extended");
                    return;
                }
            }
            case POWER -> {
                if (!expected.blockId().equals(block)) {
                    rejectBlock("wrong-block");
                    return;
                }
            }
            default -> {
                return;
            }
        }
        pending = null;
        debug("block-confirmed stage=" + expected.stage() + " cell=" + expected.cell()
            + " block=" + block);
        switch (expected.stage()) {
            case SUPPORTS -> {
                supportIndex++;
                if (supportIndex >= supportCount()) {
                    sequence.stageSettled(PistonSequence.PistonPhase.SUPPORTS, lastServerTick);
                }
            }
            case PISTON -> sequence.stageSettled(PistonSequence.PistonPhase.PISTON, lastServerTick);
            case POWER -> {
                powerDispatched = true;
                sequence.stageSettled(PistonSequence.PistonPhase.POWER, lastServerTick);
            }
            default -> {
            }
        }
    }

    private void rejectBlock(String reason) {
        Pending rejected = pending;
        pending = null;
        lastFailure = reason;
        String stage = rejected == null ? String.valueOf(phase()) : rejected.stage().name();
        String cell = rejected == null ? "-" : rejected.cell().toString();
        debug("block-rejected stage=" + stage + " cell=" + cell + " reason=" + reason);
        incident("block-confirmation-wrong-block stage=" + stage + " cell=" + cell
            + " reason=" + reason);
        if (sequence != null) sequence.blockRejected(reason, lastServerTick);
    }

    private int supportCount() {
        return (int) sequence.plan().intents().stream()
            .filter(intent -> intent.action() == Action.PLACE_SUPPORT).count();
    }

    // ------------------------------------------------------------------ crystal

    private void crystalStage(int serverTick) {
        if (!crystalDispatched) {
            dispatchCrystal(serverTick);
            return;
        }
        PistonSequence.BindResult bind = sequence.bindSpawn(spawns, crystalBaselineUuids,
            crystalBaselineEntities, serverTick);
        if (bind == PistonSequence.BindResult.UNIQUE) {
            PistonSequence.Spawn spawn = sequence.bound().orElseThrow();
            if (crystalRisk == null || !risk.bindCrystal(crystalRisk, spawn.entity(), spawn.cell().center())) {
                spawnRejected = true;
                fail(PistonSequence.PistonPhase.CRYSTAL, "spawn-unbound", serverTick);
                return;
            }
            sequence.stageSettled(PistonSequence.PistonPhase.CRYSTAL, serverTick);
            return;
        }
        if (bind == PistonSequence.BindResult.ALREADY_BOUND) {
            sequence.stageSettled(PistonSequence.PistonPhase.CRYSTAL, serverTick);
            return;
        }
        if (!spawns.isEmpty()) {
            spawnRejected = true;
            fail(PistonSequence.PistonPhase.CRYSTAL, "spawn-rejected", serverTick);
        }
    }

    private void dispatchCrystal(int serverTick) {
        Intent intent = sequence.plan().crystalIntent().orElseThrow();
        Cell source = sequence.plan().source();
        int hotbarSlot = hotbarSlot(CRYSTAL_ITEM);
        boolean needsSlot = hotbarSlot >= 0 && hotbarSlot != inventory.selectedSlot();
        int expectedSlot = hotbarSlot >= 0 ? hotbarSlot : inventory.selectedSlot();
        SafetyState state = safetyState();
        ExposureBound bound = sequence.plan().exposure(new MitigationEnvelope(world.mitigationRevision()));
        PlanContext context = new PlanContext(state, bound, sequence.plan().targetEstimate(),
            targetHealth(), true, expectedSlot, CRYSTAL_ITEM, inventory.selectedSlot(),
            inventory.offhandItem(), false, false, false, null, false);
        // Snapshot before the send: anything that appears afterwards is a candidate for our spawn, and
        // anything already here can never be mistaken for it.
        Set<UUID> baselineUuids = Set.copyOf(world.crystalUuids());
        Set<EntityKey> baselineEntities = Set.copyOf(world.crystalEntities());
        crystalBaselineUuids = baselineUuids;
        crystalBaselineEntities = baselineEntities;
        HazardId reservedHazard = ledger.reserveSpawn(ticket.conn(), Box.fromCell(source),
            Box.fromCell(source).expand(1.0), baselineEntities);
        PlacementId reservedPlacement = ledger.reservePlacement(ticket.conn(), source, CRYSTAL_ITEM);
        RiskRequest request = new RiskRequest(bound, arrival(serverTick), state, null, null,
            PACKET_COST, serverTick, needsSlot, true, false, sequence.plan().footprint());
        if (!scheduler.reserve(intent, ticket, request)) {
            ledger.cancelUnsent(reservedHazard);
            ledger.cancelUnsentPlacement(reservedPlacement);
            fail(PistonSequence.PistonPhase.CRYSTAL, "reserve-rejected", serverTick);
            return;
        }
        Optional<RiskId> reservedRisk = scheduler.riskId(intent.plan(), intent.id());
        if (reservedRisk.isEmpty()) {
            ledger.cancelUnsent(reservedHazard);
            ledger.cancelUnsentPlacement(reservedPlacement);
            fail(PistonSequence.PistonPhase.CRYSTAL, "risk-missing", serverTick);
            return;
        }
        runtime.plan(intent, context);
        CombatRuntime.DispatchResult result = runtime.dispatch(intent, ticket);
        if (result.status() != CombatRuntime.Status.SENT) {
            ledger.cancelUnsent(reservedHazard);
            ledger.cancelUnsentPlacement(reservedPlacement);
            if (result.status() == CombatRuntime.Status.FAILED) {
                fail(PistonSequence.PistonPhase.CRYSTAL,
                    result.reason().name().toLowerCase(java.util.Locale.ROOT), serverTick);
            }
            return;
        }
        ledger.markSent(reservedHazard, serverTick);
        ledger.markPlacementSent(reservedPlacement, serverTick);
        hazard = reservedHazard;
        placement = reservedPlacement;
        crystalRisk = reservedRisk.orElseThrow();
        crystalSentTick = serverTick;
        crystalDispatched = true;
    }

    // ------------------------------------------------------------------ observations

    private void observeTarget(int serverTick) {
        EntityKey target = sequence.plan().target();
        Vec position = world.position(target);
        if (lastTargetPosition != null && position.distance(lastTargetPosition) > 1.0e-6) {
            sequence.targetMoved(position, world.withinAttackEnvelope(target, sequence.computedPushed()),
                serverTick);
            lastTargetPosition = position;
        }
        if (!world.alive(target)) {
            sequence.targetMoved(position, false, serverTick);
        }
    }

    private void observePower(int serverTick) {
        if (sequence.phase().ordinal() >= PistonSequence.PistonPhase.POWER.ordinal()) return;
        Cell piston = sequence.plan().push().piston();
        Cell power = sequence.plan().push().power();
        boolean early = world.pistonPowered(piston) || !isAir(world.blockId(power));
        if (early) {
            earlyPowerObserved = true;
            pending = null;
            sequence.blockRejected("early-power", serverTick);
            lastFailure = "early-power";
        }
    }

    private void observePiston(int serverTick) {
        PistonFacing expected = sequence.plan().push().facing();
        Cell piston = sequence.plan().push().piston();
        PistonFacing observed = world.pistonFacing(piston);
        if (observed != null && !observed.equals(expected)) {
            wrongFacingObserved = true;
            pending = null;
            sequence.blockRejected("wrong-facing", serverTick);
            lastFailure = "wrong-facing";
            return;
        }
        if (sequence.phase() == PistonSequence.PistonPhase.ATTACK
            && !world.pistonExtended(piston)) {
            // No extension observation yet; the stage deadline stays authoritative.
            return;
        }
    }

    private void observePistonState(Cell cell) {
        if (sequence == null || cell == null) return;
        if (cell.equals(sequence.plan().push().piston()) && world.pistonPowered(cell)
            && sequence.phase().ordinal() < PistonSequence.PistonPhase.POWER.ordinal()) {
            earlyPowerObserved = true;
        }
    }

    // ------------------------------------------------------------------ push / attack

    private void waitPush(int serverTick) {
        crossCheckClientPosition();
        Cell piston = sequence.plan().push().piston();
        // A placed piston block is not progress: only an authoritative powered/extended state (or the
        // client's own push cross-check) proves the extension happened.
        if (world.pistonPowered(piston) || world.pistonExtended(piston)) {
            sequence.pistonProgress(1.0, serverTick);
        }
    }

    private void attack(int serverTick) {
        PistonSequence.Spawn bound = sequence.bound().orElse(null);
        if (bound == null || !crystalDispatched || spawnRejected || wrongFacingObserved
            || earlyPowerObserved || attackAttempted) {
            fail(PistonSequence.PistonPhase.ATTACK, "unverified-attack", serverTick);
            return;
        }
        UUID uuid = world.uuid(bound.entity());
        if (uuid == null) {
            // No authoritative UUID means the identity is unverified; never attack it.
            fail(PistonSequence.PistonPhase.ATTACK, "uuid-unverified", serverTick);
            return;
        }
        if (!uuid.equals(bound.uuid())) {
            fail(PistonSequence.PistonPhase.ATTACK, "uuid-mismatch", serverTick);
            return;
        }
        if (!attackChecked) {
            attackChecked = true;
            if (!sequence.damageAtComputedPosition(sequence.plan().targetDamage(),
                world.minimumTargetDamage(), serverTick)) {
                lastFailure = "damage-too-low";
                return;
            }
        }
        if (sequence.phase() != PistonSequence.PistonPhase.ATTACK) return;
        Intent intent = new Intent(sequence.plan().planId() * 16L + ATTACK_ID_OFFSET,
            sequence.plan().planId(), Strategy.PISTON, Action.ATTACK_CRYSTAL, null, bound.entity(),
            Set.of(), Set.of(bound.entity()), Set.of(sequence.plan().powerIntent().orElseThrow()),
            serverTick + PistonPlanner.INTENT_LIFETIME_TICKS);
        PlanContext context = new PlanContext(safetyState(), crystalBound(), targetEstimate(),
            targetHealth(), true, inventory.selectedSlot(), "", inventory.selectedSlot(),
            inventory.offhandItem(), false, false, false, null, false);
        attackAttempted = true;
        if (crystalRisk == null || !scheduler.attach(intent, ticket, crystalRisk, PACKET_COST,
            serverTick, false, true, false, sequence.plan().footprint())) {
            fail(PistonSequence.PistonPhase.ATTACK, "risk-not-held", serverTick);
            return;
        }
        runtime.plan(intent, context);
        CombatRuntime.DispatchResult result = runtime.dispatch(intent, ticket);
        if (result.status() == CombatRuntime.Status.SENT) {
            debug("attack-sent plan=" + sequence.plan().planId() + " computed-final="
                + sequence.computedPushed() + " uuid=" + bound.uuid());
            if (!world.crystalEntities().contains(bound.entity())) {
                incident("attack-computed-unobserved computed=" + sequence.computedPushed()
                    + " crystal=" + bound.entity() + " observed=false");
            }
            sequence.attackSent(serverTick);
        } else {
            lastFailure = result.reason().name().toLowerCase(java.util.Locale.ROOT);
        }
    }

    // ------------------------------------------------------------------ helpers

    private ExposureBound crystalBound() {
        return sequence.plan().exposure(new MitigationEnvelope(world.mitigationRevision()));
    }

    private ExposureBound zeroBound() {
        return new ExposureBound(new MitigatedDamage(0.0), Map.of(),
            new MitigationEnvelope(world.mitigationRevision()));
    }

    private TargetEstimate targetEstimate() {
        return sequence.plan().targetEstimate();
    }

    private EffectiveHealth targetHealth() {
        return world.health(sequence.plan().target());
    }

    private SafetyState safetyState() {
        Map<EntityKey, EffectiveHealth> friends = new LinkedHashMap<>();
        for (EntityKey friend : sequence.plan().friendDamages().keySet()) {
            friends.put(friend, world.health(friend));
        }
        return new SafetyState(ticket.conn(), ticket.gen(), world.self(), world.health(world.self()),
            friends, world.safety());
    }

    private ServerTickInterval arrival(int serverTick) {
        return new ServerTickInterval(serverTick + 1L, serverTick + 3L);
    }

    private int hotbarSlot(String itemId) {
        for (int slot = 0; slot < 9; slot++) {
            if (itemId.equals(inventory.hotbarItem(slot))) return slot;
        }
        return -1;
    }

    private void fail(PistonSequence.PistonPhase stage, String reason, int serverTick) {
        lastFailure = reason;
        crystalBaselineUuids = Set.of();
        crystalBaselineEntities = Set.of();
        boolean wasTerminal = terminal();
        sequence.stageFailed(stage, reason, serverTick);
        debug("abort stage=" + stage + " reason=" + reason + " phase=" + sequence.phase());
        if (!wasTerminal && stage.ordinal() >= PistonSequence.PistonPhase.POWER.ordinal()) {
            incident("trap-aborted-after-power stage=" + stage + " reason=" + reason);
        }
        if (sequence.phase() == PistonSequence.PistonPhase.MANUAL_CLEANUP
            || sequence.phase() == PistonSequence.PistonPhase.ABORTED) {
            // Risk, hazard and the trap slot are retained until explicit reconciliation.
            lastFailure = reason;
        }
    }

    // ------------------------------------------------------------------ diagnostics (read-only)

    /**
     * Compares the trace-computed final crystal position with the client's own observation when one
     * exists. C21 suppresses crystal position sync, so an absent or unchanged client position is only a
     * diagnostic: a disagreement incident is emitted only when a client position update actually exists
     * and differs by more than {@link #POSITION_TOLERANCE}. This method never sends or blocks a stage.
     */
    private void crossCheckClientPosition() {
        if (positionChecked || sequence == null) return;
        PistonSequence.Spawn bound = sequence.bound().orElse(null);
        if (bound == null) return;
        Vec computed = sequence.computedPushed();
        Optional<Vec> observed = world.crystalClientPosition(bound.entity());
        if (observed.isEmpty()) {
            positionChecked = true;
            debug("position-cross-check computed=" + computed + " client=unobserved c21-suppressed=true");
            return;
        }
        Vec client = observed.orElseThrow();
        double fromSpawn = client.distance(bound.cell().center());
        if (fromSpawn <= POSITION_TOLERANCE) {
            positionChecked = true;
            debug("position-cross-check computed=" + computed + " client=" + client
                + " client-position=stale no-update=true");
            return;
        }
        positionChecked = true;
        double delta = client.distance(computed);
        debug("position-cross-check computed=" + computed + " client=" + client
            + " delta=" + String.format(java.util.Locale.ROOT, "%.4f", delta)
            + " tolerance=" + POSITION_TOLERANCE);
        if (delta > POSITION_TOLERANCE) {
            incident("position-disagreement computed=" + computed + " client=" + client
                + " delta=" + String.format(java.util.Locale.ROOT, "%.4f", delta)
                + " tolerance=" + POSITION_TOLERANCE);
        }
    }

    /** Emits one debug record per phase change; entering MANUAL_CLEANUP is an incident. */
    private void tracePhase() {
        if (sequence == null) return;
        PistonSequence.PistonPhase phase = sequence.phase();
        if (phase == observedPhase) return;
        debug("stage " + (observedPhase == null ? "-" : observedPhase.name()) + " -> " + phase);
        if (phase == PistonSequence.PistonPhase.MANUAL_CLEANUP
            && observedPhase != PistonSequence.PistonPhase.MANUAL_CLEANUP) {
            incident("manual-cleanup-required previous="
                + (observedPhase == null ? "-" : observedPhase.name())
                + " reason=" + (lastFailure.isEmpty() ? "unspecified" : lastFailure));
        }
        observedPhase = phase;
    }

    private void debug(String line) {
        diagnostics.debug("piston: " + line);
    }

    private void incident(String line) {
        diagnostics.incident(line);
    }

    private static boolean isAir(String blockId) {
        return blockId == null || blockId.equals("minecraft:air") || blockId.equals("minecraft:cave_air");
    }
}
