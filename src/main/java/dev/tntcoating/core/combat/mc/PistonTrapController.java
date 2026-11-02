/*
 * Original Comet implementation by Boyonabike.
 * Independent: written from the pinned 1.21.11 artifact behaviour, the CrystalCombat v2.1 plan and the
 * project's own Phase 5 contracts; no third-party code.
 * SPDX-License-Identifier: CC0-1.0
 */

package dev.tntcoating.core.combat.mc;

import dev.tntcoating.core.combat.Action;
import dev.tntcoating.core.combat.Box;
import dev.tntcoating.core.combat.Capability;
import dev.tntcoating.core.combat.Cell;
import dev.tntcoating.core.combat.CleanupPriority;
import dev.tntcoating.core.combat.CombatDamagePolicy;
import dev.tntcoating.core.combat.CombatRiskBudget;
import dev.tntcoating.core.combat.CombatScheduler;
import dev.tntcoating.core.combat.CombatSession;
import dev.tntcoating.core.combat.CrystalLedger;
import dev.tntcoating.core.combat.DirectPause;
import dev.tntcoating.core.combat.DirectSettings;
import dev.tntcoating.core.combat.EffectiveHealth;
import dev.tntcoating.core.combat.EntityKey;
import dev.tntcoating.core.combat.ExposureBound;
import dev.tntcoating.core.combat.FootprintConflict;
import dev.tntcoating.core.combat.HazardId;
import dev.tntcoating.core.combat.Intent;
import dev.tntcoating.core.combat.MitigatedDamage;
import dev.tntcoating.core.combat.MitigationEnvelope;
import dev.tntcoating.core.combat.Opportunity;
import dev.tntcoating.core.combat.Ownership;
import dev.tntcoating.core.combat.PistonFacing;
import dev.tntcoating.core.combat.PistonGeometry;
import dev.tntcoating.core.combat.PistonLayoutSelection;
import dev.tntcoating.core.combat.PistonOpportunity;
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
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.Supplier;

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
    /** Vanilla item ids used by the structural intents; the planner owns the single source of truth. */
    public static final String SUPPORT_ITEM = PistonPlanner.SUPPORT_ITEM;
    public static final String PISTON_ITEM = PistonPlanner.PISTON_ITEM;
    public static final String CRYSTAL_ITEM = PistonPlanner.CRYSTAL_ITEM;
    private static final int ATTACK_ID_OFFSET = 1000;
    /** Documented computed/client position tolerance for the diagnostic cross-check. */
    public static final double POSITION_TOLERANCE = PistonSequence.CROSS_CHECK_TOLERANCE;
    /**
     * A per-tick rise above this is an explosion impulse, not a normal jump or step. A vanilla jump peaks at
     * about 0.42 blocks per tick; an explosion impulse is larger, and the comparison is strict, so a
     * sustained 0.5-block-per-tick lift is evidence.
     */
    private static final double VERTICAL_PUSH_DELTA = 0.45;
    /** An external block change this close to the target can be the explosion that lifted it. */
    private static final double DISTURBANCE_RADIUS = 4.0;
    /** An explosion centre this close to the target can have lifted it out of the hole. */
    private static final double EXPLOSION_RADIUS = 8.0;
    /** How many server ticks recorded explosion or disturbance evidence remains usable. */
    private static final int DISTURBANCE_RECENCY_TICKS = 3;

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

        /** Hotbar slot (0-8) holding a suitable tool for breaking {@code blockId}, or -1. */
        int toolSlot(String blockId);
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

        /** Live settings change; a world that derives nothing from the settings ignores it. */
        default void reconfigure(DirectSettings settings) {
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

        /**
         * Read-side refresh of the live world view at {@code serverTick}, without advancing the stationary
         * motion counters. Production's {@link #onServerTick(int)} calls this first; callers that need the
         * sample contract use {@code onServerTick} instead. Fakes may no-op.
         */
        default void refresh(int serverTick) {
        }

        /**
         * Observational sample point: refresh the read view and advance the stationary motion counters.
         * Production deduplicates per {@code serverTick}, so a Hybrid preview and the trap tick for the same
         * tick sample the world exactly once. It never scans, reserves or sends.
         */
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
     * Cells whose structural placement packet this trap actually sent, in send order. Manual cleanup is
     * the default, so the info string and the debug log list exactly these blocks (plus the plan's
     * power/piston and the crystal cell) for the player to remove by hand.
     */
    private final Set<Cell> sentStructural = new LinkedHashSet<>();

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
    /** Server tick of the last target observation, so the vertical impulse stays a per-tick test. */
    private int lastTargetTick = -1;
    /**
     * Last server-authored block change outside this trap's own plan and within
     * {@link #DISTURBANCE_RADIUS} of the target; a secondary signal next to the explosion observation.
     */
    private Cell lastDisturbanceCell;
    private int lastDisturbanceTick = -1;
    /** Server tick of the last explosion observation near the target; primary vertical-push evidence. */
    private int lastExplosionTick = -1;
    /** True once the trap observed the piston push itself; manual cleanup then lists the pushed cell. */
    private boolean pushObserved;
    /** True once the sent crystal is known gone (attack sent or removal observed); cleanup stops listing it. */
    private boolean crystalRemovedObserved;
    private String lastFailure = "";
    private String lastMaterialReport = "";
    /** Last plan-specific shortfall report; separate from the module-wide one above. */
    private String lastPlanMaterialReport = "";
    private PistonLayoutSelection layouts = PistonLayoutSelection.all();
    private Diagnostics diagnostics = Diagnostics.NONE;
    /** Last phase the debug tap saw; only the diagnostic tap compares it. */
    private PistonSequence.PistonPhase observedPhase;
    private boolean positionChecked;
    /**
     * Beta auto-cleanup setting. Off by default: the trap then stays in the legacy manual-cleanup mode and
     * never emits a break packet. Toggling it is live through {@link #setAutoCleanup(boolean)}.
     */
    private boolean autoCleanup;
    /**
     * Shared cleanup-starvation signal owned by the combat engine. Cleanup asks for its next free packet
     * slot here instead of building a second scheduler; {@code null} in stacks that do not wire it.
     */
    private CleanupPriority cleanupPriority;
    /**
     * The module's Pauses settings (eating, health floor, lag and the rest), shared with the direct engine.
     * Null means nothing pauses, which is also the default for stacks that do not wire it.
     */
    private Supplier<DirectPause> pauseSource = () -> null;
    /** The pause that stopped the last tick, or null; read by the visible wait reason in PistonOnly. */
    private DirectPause pause;
    /** Per-trap cleanup intent id source; distinct from the structural and attack ids. */
    private long cleanupIntentSequence;

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
        // The runtime is constructed before the trap in every production stack; this trap is the only
        // explosion observer consumer and the setter is idempotent.
        this.runtime.setExplosionObserver(this::onExplosion);
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

            @Override
            public int toolSlot(String blockId) {
                return adapter.toolSlot(blockId);
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
     *
     * <p>Hybrid previews before the engine tick, and the trap is only ticked once selected, so a preview
     * must perform the observational sample itself: it refreshes the view and advances the stationary
     * trackers for {@code serverTick}. The world deduplicates the sample per server tick, so a tick that
     * previews and then starts/ticks the trap advances the trackers exactly once. PistonOnly never
     * previews and keeps {@link #tick(int, TickBudget)} as its only sample point.</p>
     */
    public Optional<Opportunity> previewPlan(int serverTick, TickBudget budget) {
        preview = null;
        if (serverTick < 0 || !enabled || sequence != null || !reconciled || !session.isOpen()
            || budget == null) {
            return Optional.empty();
        }
        world.onServerTick(serverTick);
        reportMissingMaterials();
        if (!missingMaterials().isEmpty()) return Optional.empty();
        if (!budget.spend(TickBudget.Phase.PLANNING)) return Optional.empty();
        Optional<PistonPlanner.Plan> plan = planner.tick(world, world.targets(), 8).plan();
        if (plan.isEmpty()) return Optional.empty();
        // The planner already rejects a candidate whose own power item is gone; keep the offering
        // contract explicit here so no stale/mis-gated plan can ever leave as a preview.
        PistonPlanner.Plan candidate = plan.orElseThrow();
        if (!planMaterialsPresent(candidate)) {
            // The sticky plan lost its own power item (e.g. torch swapped for a block): rescan next tick so
            // another enabled layout can be offered instead of idling until an explicit reconcile.
            planner.reset();
            return Optional.empty();
        }
        preview = candidate;
        return PistonOpportunity.of(preview, new MitigationEnvelope(world.mitigationRevision()));
    }

    /** Phase 6.4: starts exactly the previewed plan after Hybrid selected it; never scans again. */
    public boolean startPreviewed(int serverTick) {
        PistonPlanner.Plan plan = preview;
        preview = null;
        if (plan == null || !enabled || !session.isOpen()) return false;
        pause = pauseSource.get();
        if (pause != null) return false;
        return beginPlan(plan, serverTick);
    }

    /** Installs the exact sixteen-combination beta layout filter; empty disables planning entirely. */
    public void setLayouts(PistonLayoutSelection layouts) {
        this.layouts = layouts == null ? PistonLayoutSelection.all() : layouts;
        this.lastMaterialReport = "";
        this.lastPlanMaterialReport = "";
        planner.setSelection(this.layouts);
        // comet-sticky-reset: a completed scan is sticky, so without this a plan for a layout that was just
        // disabled could still be previewed or started. An active trap keeps its plan until reconcile.
        if (sequence == null) planner.reset();
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

    /**
     * Materials the enabled trap layouts need but the hotbar does not hold, in deterministic order:
     * piston, end crystal, obsidian, then the power item(s) for the enabled layouts. Only slots 0-8 are
     * consulted; the offhand is never part of a trap.
     */
    public List<String> missingMaterials() {
        List<String> missing = new ArrayList<>();
        if (!hasHotbarItem(PISTON_ITEM)) missing.add(PISTON_ITEM);
        if (!hasHotbarItem(CRYSTAL_ITEM)) missing.add(CRYSTAL_ITEM);
        if (!hasHotbarItem(SUPPORT_ITEM)) missing.add(SUPPORT_ITEM);
        boolean anyPowerKind = false;
        for (PistonLayoutSelection.Combination combination : layouts.enabledCombinations()) {
            if (hasHotbarItem(combination.kind().itemId())) {
                anyPowerKind = true;
                break;
            }
        }
        if (!anyPowerKind) {
            for (PistonGeometry.PowerKind kind : PistonGeometry.PowerKind.values()) {
                if (!enabledKind(kind)) continue;
                if (!hasHotbarItem(kind.itemId())) missing.add(kind.itemId());
            }
        }
        return List.copyOf(missing);
    }

    /**
     * Materials the given plan needs but the hotbar does not hold, in deterministic order: piston, end
     * crystal, obsidian, then exactly that plan's own power item. The module-wide
     * {@link #missingMaterials()} accepts any enabled layout's power item; starting one specific plan
     * cannot.
     */
    public List<String> missingPlanMaterials(PistonPlanner.Plan plan) {
        Objects.requireNonNull(plan, "plan");
        List<String> missing = new ArrayList<>();
        if (!hasHotbarItem(PISTON_ITEM)) missing.add(PISTON_ITEM);
        if (!hasHotbarItem(CRYSTAL_ITEM)) missing.add(CRYSTAL_ITEM);
        if (!hasHotbarItem(SUPPORT_ITEM)) missing.add(SUPPORT_ITEM);
        String power = plan.push().powerKind().itemId();
        if (!hasHotbarItem(power)) missing.add(power);
        return List.copyOf(missing);
    }

    /** Human-readable form of {@link #missingMaterials()}, e.g. {@code piston, end crystal, redstone torch}. */
    public String missingMaterialsText() {
        return materialText(missingMaterials());
    }

    private static String materialText(List<String> itemIds) {
        List<String> names = new ArrayList<>();
        for (String itemId : itemIds) names.add(displayItem(itemId));
        return String.join(", ", names);
    }

    private boolean enabledKind(PistonGeometry.PowerKind kind) {
        for (PistonLayoutSelection.Combination combination : layouts.enabledCombinations()) {
            if (combination.kind() == kind) return true;
        }
        return false;
    }

    private boolean hasHotbarItem(String itemId) {
        return hotbarSlot(itemId) >= 0;
    }

    private static String displayItem(String itemId) {
        if (PISTON_ITEM.equals(itemId)) return "piston";
        if (CRYSTAL_ITEM.equals(itemId)) return "end crystal";
        if (SUPPORT_ITEM.equals(itemId)) return "obsidian";
        String name = itemId.startsWith("minecraft:") ? itemId.substring("minecraft:".length()) : itemId;
        return name.replace('_', ' ');
    }

    /** Writes the material shortfall once per distinct missing set; never once per tick. */
    private void reportMissingMaterials() {
        String report = missingMaterials().isEmpty()
            ? ""
            : "missing materials: " + missingMaterialsText();
        if (report.equals(lastMaterialReport)) return;
        lastMaterialReport = report;
        if (!report.isEmpty()) debug(report);
    }

    /**
     * Plan-specific start gate: the plan's own power item and every shared material must still be in the
     * hotbar. Logs one change-triggered line naming the shortfall; never reserves, leases or sends.
     */
    private boolean planMaterialsPresent(PistonPlanner.Plan plan) {
        List<String> missing = missingPlanMaterials(plan);
        String report = missing.isEmpty()
            ? ""
            : "plan-missing-materials: " + materialText(missing)
                + " power=" + plan.push().powerKind();
        if (!report.equals(lastPlanMaterialReport)) {
            lastPlanMaterialReport = report;
            if (!report.isEmpty()) debug(report);
        }
        return missing.isEmpty();
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

    /** Beta auto-cleanup is on only while the live setting says so. */
    public boolean autoCleanup() {
        return autoCleanup;
    }

    /** Live settings change from the module config path; forwarded to the world's ranges and limits. */
    public void reconfigure(DirectSettings settings) {
        world.reconfigure(settings);
    }

    /**
     * Live setting change from the module config path. Turning it on lets the current trap use inferred
     * ownership; turning it off immediately stops cleanup admission and clears the starvation request.
     * Existing risk and hazard state is untouched either way.
     */
    public void setAutoCleanup(boolean value) {
        autoCleanup = value;
        if (sequence != null) sequence.allowInferredCleanup(value);
        if (!value) {
            // No cleanup packet may leave after the switch is turned off, including the vanilla
            // destruction a previous cleanup tick already started.
            stopCleanupDestruction();
            clearCleanupPriority();
        }
    }

    /** Wires the module's Pauses settings into this trap; null restores "never paused". */
    public void setPauseSource(Supplier<DirectPause> source) {
        pauseSource = source == null ? () -> null : source;
    }

    /** The pause that stopped the last trap tick, or null when it ran. */
    public DirectPause pause() {
        return pause;
    }

    /** Wires the engine-owned cleanup starvation signal into this trap. */
    public void setCleanupPriority(CleanupPriority priority) {
        if (cleanupPriority != null) cleanupPriority.feasibility(null);
        cleanupPriority = priority;
        if (priority == null) return;
        // The trap requests afresh on its next cleanup tick; nothing from a previous trap may linger.
        priority.clear();
        // The engine may only yield a tick cleanup can actually use. The probe is a pure query: it never
        // reserves, sends or touches risk.
        priority.feasibility(this::cleanupCanReserve);
    }

    /** True while a sent structural placement is waiting for its block observation. */
    public boolean awaitingBlockConfirmation() {
        return pending != null;
    }

    public Optional<Cell> pendingCell() {
        return pending == null ? Optional.empty() : Optional.of(pending.cell());
    }

    /**
     * Compact manual-cleanup instructions for the live trap: the power, the piston, the supports this trap
     * actually placed and, while it still exists, the crystal at its last known cell, with exact
     * coordinates. Manual cleanup is the default; the beta auto-cleanup setting removes only blocks the
     * ledger marks as inferred-ours, and contested blocks always stay listed here for the player. The trap
     * never detonates anything, and this string is the fallback whenever cleanup cannot proceed.
     */
    public String manualCleanupText() {
        if (sequence == null) return "";
        PistonGeometry.PistonPush push = sequence.plan().push();
        List<String> parts = new ArrayList<>();
        if (sentStructural.contains(push.power())) {
            parts.add("power " + coordinates(push.power()));
        }
        if (sentStructural.contains(push.piston())) {
            parts.add("piston " + coordinates(push.piston()));
        }
        List<String> supports = new ArrayList<>();
        for (Cell cell : sentStructural) {
            if (cell.equals(push.power()) || cell.equals(push.piston())) continue;
            supports.add(coordinates(cell));
        }
        if (!supports.isEmpty()) parts.add("supports [" + String.join(", ", supports) + "]");
        // The crystal only exists at the placement cell until the push moves it, and only until the
        // sent attack (or an observed removal) destroys it. List it only while it is still there.
        if (crystalDispatched && !crystalRemovedObserved) {
            parts.add("crystal " + coordinates(crystalCleanupCell()));
        }
        return String.join(", ", parts);
    }

    private Cell crystalCleanupCell() {
        if (!pushObserved) return sequence.plan().push().source();
        Vec pushed = sequence.computedPushed();
        return new Cell((int) Math.floor(pushed.x()), (int) Math.floor(pushed.y()),
            (int) Math.floor(pushed.z()));
    }

    private static String coordinates(Cell cell) {
        return "(" + cell.x() + ", " + cell.y() + ", " + cell.z() + ")";
    }

    /** Disable stops all further ticks/sends without releasing hazards, risk or the trap slot. */
    public void disable() {
        // Stop the vanilla destruction we started BEFORE the trap is marked disabled: the stop packet is
        // part of the halt, never a send after it. No cleanup packet follows this line.
        stopCleanupDestruction();
        enabled = false;
        pending = null;
        pause = null;
        clearCleanupPriority();
        cleanupIntentSequence = 0;
        resetDisturbance();
        if (sequence != null) sequence.disable();
        tracePhase();
    }

    /** Re-arm after a disable; the sequence (and its retained risk) still decides what may run. */
    public void enable() {
        enabled = true;
    }

    public void death() {
        stopCleanupDestruction();
        enabled = false;
        pending = null;
        clearCleanupPriority();
        cleanupIntentSequence = 0;
        resetDisturbance();
        if (sequence != null) sequence.death();
        tracePhase();
    }

    public void respawn() {
        stopCleanupDestruction();
        pending = null;
        clearCleanupPriority();
        cleanupIntentSequence = 0;
        resetDisturbance();
        if (sequence != null) sequence.respawn();
    }

    /** Connection close behaves like disable for hazards/risk: nothing is cleaned up or released. */
    public void connectionClosed() {
        // The connection is already gone: never attempt a stop packet, just forget the local destruction.
        runtime.forgetDestruction();
        enabled = false;
        pending = null;
        clearCleanupPriority();
        cleanupIntentSequence = 0;
        resetDisturbance();
        if (sequence != null) sequence.disable();
    }

    /** Explicit reconciliation releases the single-trap slot; hazards and risk stay with the ledger. */
    public void reconcile() {
        stopCleanupDestruction();
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
        clearCleanupPriority();
        cleanupIntentSequence = 0;
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
        lastTargetTick = -1;
        pending = null;
        blockObservationSerial = 0;
        reconciled = true;
        spawns.clear();
        sentStructural.clear();
        resetDisturbance();
        pushObserved = false;
        crystalRemovedObserved = false;
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
            crystalRemovedObserved = true;
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
        // A block update can turn a cell we are currently breaking contested (someone else replaced or
        // removed it). Cancel the vanilla destruction before the server can finish our contested block.
        if (sequence != null && ticket != null && sentStructural.contains(cell)
            && ledger.ownership(ticket.conn(), cell) != Ownership.INFERRED_OURS) {
            runtime.stopDestruction(cell);
        }
        pruneSentStructural(cell);
        recordDisturbance(cell);
    }

    /**
     * Drops a sent structural cell once the block that earned it is gone or different. The remaining
     * cells stay listed for the manual fallback; the cleanup path only ever breaks a cell whose exact
     * placed block is still present.
     */
    private void pruneSentStructural(Cell cell) {
        if (sequence == null || !sentStructural.contains(cell)) return;
        String expected = expectedCleanupBlock(cell);
        if (expected != null && !expected.equals(world.blockId(cell))) {
            // The exact block we were removing is gone or changed: stop any break we started here.
            runtime.stopDestruction(cell);
            sentStructural.remove(cell);
            debug("cleanup-forget cell=" + cell + " expected=" + expected
                + " observed=" + world.blockId(cell));
        }
    }

    /**
     * Remembers a server-authored block change outside the plan and close to the verified target. The trap's
     * own placements, its piston extension and its supports arrive through the same callback and are
     * excluded by cell, so they can never fake explosion evidence.
     */
    private void recordDisturbance(Cell cell) {
        if (sequence == null || lastServerTick < 0) return;
        PistonPlanner.Plan plan = sequence.plan();
        if (planCells(plan).contains(cell)) return;
        double distance = cell.center().distance(world.position(plan.target()));
        if (distance > DISTURBANCE_RADIUS) return;
        lastDisturbanceCell = cell;
        lastDisturbanceTick = lastServerTick;
        debug("external-disturbance cell=" + cell + " tick=" + lastServerTick
            + " distance=" + String.format(Locale.ROOT, "%.2f", distance));
    }

    /**
     * Passive explosion observation from the runtime. An explosion close to the verified target is
     * evidence that an outside blast may have lifted it out of the hole; this callback only records the
     * tick. It never sends, reserves or changes the phase, and it ignores explosions before POWER.
     */
    public void onExplosion(Vec centre) {
        if (!enabled || sequence == null || lastServerTick < 0 || centre == null) return;
        PistonSequence.PistonPhase phase = sequence.phase();
        if (phase != PistonSequence.PistonPhase.POWER
            && phase != PistonSequence.PistonPhase.WAIT_PUSH) {
            return;
        }
        double distance = centre.distance(world.position(sequence.plan().target()));
        if (distance > EXPLOSION_RADIUS) return;
        lastExplosionTick = lastServerTick;
        debug("external-explosion centre=" + centre + " tick=" + lastServerTick
            + " distance=" + String.format(Locale.ROOT, "%.2f", distance));
    }

    private static Set<Cell> planCells(PistonPlanner.Plan plan) {
        PistonGeometry.PistonPush push = plan.push();
        Set<Cell> cells = new LinkedHashSet<>();
        cells.add(push.source());
        cells.add(push.piston());
        cells.add(push.extension());
        cells.add(push.power());
        for (Intent intent : plan.intents()) cells.add(intent.cell());
        return cells;
    }

    private void resetDisturbance() {
        lastDisturbanceCell = null;
        lastDisturbanceTick = -1;
        lastExplosionTick = -1;
    }

    public void onTargetChanged(EntityKey target) {
        // Movement/health facts are read from the world on the next tick; never dispatch from an event.
    }

    // ------------------------------------------------------------------ tick

    public void tick(int serverTick, TickBudget budget) {
        if (!enabled || serverTick < 0 || budget == null) return;
        // A paused tick sends nothing and starts nothing. Stage timers keep running on server ticks, so a
        // long pause mid-trap ends in the sequence's own timeout handling, never a late send.
        pause = pauseSource.get();
        if (pause != null) {
            preview = null;
            return;
        }
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
            if (sequence.completed()) {
                // A clean finish: release the slot, footprint and per-trap state exactly as a confirmed
                // cleanup would, so the next scan may begin a new trap. Hazards and sent risk stay.
                debug("cooldown-complete tick=" + serverTick);
                reconcile();
                return;
            }
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
                if (terminal()) stopCleanupDestruction();
                return;
            }
        }
        if (sequence == null) {
            clearCleanupPriority();
            scan(serverTick, budget);
            return;
        }
        if (terminal()) {
            pending = null;
            clearCleanupPriority();
            stopCleanupDestruction();
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
            case WAIT_REMOVE, COOLDOWN -> {
                // Removal observation and the cooldown only mark state; cleanup starts at CLEANUP.
                clearCleanupPriority();
            }
            case CLEANUP -> tickCleanup(serverTick);
            default -> {
            }
        }
    }

    // ------------------------------------------------------------------ scan

    private void scan(int serverTick, TickBudget budget) {
        if (!reconciled || !session.isOpen()) return;
        reportMissingMaterials();
        if (!missingMaterials().isEmpty()) {
            // Drop a preview taken before the hotbar changed: never begin a trap that cannot be built.
            preview = null;
            return;
        }
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
        reportMissingMaterials();
        if (!missingMaterials().isEmpty()) return false;
        // Module-wide materials present is not enough: this exact plan needs its own power item.
        if (!planMaterialsPresent(plan)) {
            planner.reset();
            return false;
        }
        PistonSequence.Config config = new PistonSequence.Config(0,
            PistonSequence.TRAP_DEADLINE_TICKS, PistonSequence.CLEANUP_DEADLINE_TICKS, autoCleanup);
        PistonSequence candidate = new PistonSequence(plan, config);
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
        sentStructural.clear();
        clearCleanupPriority();
        cleanupIntentSequence = 0;
        crystalBaselineUuids = Set.of();
        crystalBaselineEntities = Set.of();
        lastTargetPosition = world.position(plan.target());
        lastTargetTick = serverTick;
        resetDisturbance();
        pushObserved = false;
        crystalRemovedObserved = false;
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
        // Ledger placement intent: only a block update matching this exact expected block after the send
        // can turn the cell into INFERRED_OURS for the beta cleanup ownership gate.
        PlacementId reservedPlacement =
            ledger.reservePlacement(ticket.conn(), intent.cell(), itemId);
        if (!scheduler.reserve(intent, ticket, request)) {
            // No packet was sent; a transient lease loss retries next tick. The deadline handles a stall.
            ledger.cancelUnsentPlacement(reservedPlacement);
            return false;
        }
        runtime.plan(intent, context);
        CombatRuntime.DispatchResult result = runtime.dispatch(intent, ticket);
        if (result.status() == CombatRuntime.Status.SENT) {
            ledger.markPlacementSent(reservedPlacement, serverTick);
            sentStructural.add(intent.cell());
            return true;
        }
        ledger.cancelUnsentPlacement(reservedPlacement);
        if (result.status() == CombatRuntime.Status.FAILED) {
            fail(sequence.phase(), result.reason().name().toLowerCase(Locale.ROOT), serverTick);
        }
        return false;
    }

    /** Arms the block-confirmation gate for the structural intent just accepted by the server. */
    private void awaitBlock(PistonSequence.PistonPhase stage, Cell cell, String blockId,
                            PistonFacing facing) {
        pending = new Pending(stage, cell, blockId, facing, blockObservationSerial);
    }

    // ------------------------------------------------------------------ beta auto-cleanup

    /**
     * One cleanup tick. Sends only through {@link CombatRuntime#dispatch}, only after the shared
     * starvation signal gives cleanup the next free packet slot, and only for cells that are still
     * INFERRED_OURS with their exact placed block present and a hotbar tool available. Contested cells,
     * changed cells, missing tools and an unresolved trap crystal all fall through to the deadline and the
     * manual text; none of them clears risk or a hazard.
     */
    private void tickCleanup(int serverTick) {
        PistonSequence active = sequence;
        if (active == null || ticket == null) {
            clearCleanupPriority();
            return;
        }
        if (!autoCleanup) {
            // Legacy mode: no cleanup packet may ever leave, even if a previous tick queued a wait.
            clearCleanupPriority();
            return;
        }
        if (crystalDispatched && !crystalRemovedObserved) {
            // Never clean up around a live trap crystal: the player removes it (or the attack does), and
            // the existing stage deadline escalates to manual cleanup if that never happens.
            clearCleanupPriority();
            return;
        }
        pruneSentStructural();
        if (sentStructural.isEmpty()) {
            stopCleanupDestruction();
            clearCleanupPriority();
            active.cleanupCompleted(serverTick);
            debug("cleanup-complete tick=" + serverTick);
            return;
        }
        Cell target = nextCleanupCell();
        if (target == null) {
            // Nothing can be sent this tick (contested, changed, or no hotbar tool). Do not hold the
            // engine's packet slots hostage; the cleanup deadline still ends in the manual fallback.
            clearCleanupPriority();
            return;
        }
        CleanupPriority priority = cleanupPriority;
        if (priority != null) {
            priority.request(serverTick, new CleanupPriority.Deadline(ticket.conn(), ticket.gen()),
                active.plan().target());
            priority.sample(serverTick);
            if (!priority.prioritise()) return;
        }
        dispatchCleanup(target, serverTick);
    }

    private Cell nextCleanupCell() {
        if (ticket == null) return null;
        for (Cell cell : cleanupOrder()) {
            if (!sentStructural.contains(cell)) continue;
            String expected = expectedCleanupBlock(cell);
            if (expected == null || !expected.equals(world.blockId(cell))) continue;
            if (ledger.ownership(ticket.conn(), cell) != Ownership.INFERRED_OURS) continue;
            if (inventory.toolSlot(expected) < 0) continue;
            return cell;
        }
        return null;
    }

    private boolean dispatchCleanup(Cell cell, int serverTick) {
        String expected = expectedCleanupBlock(cell);
        if (expected == null) return false;
        int toolSlot = inventory.toolSlot(expected);
        if (toolSlot < 0) return false;
        int selected = inventory.selectedSlot();
        boolean needsSlot = toolSlot != selected;
        String requiredItem = inventory.hotbarItem(toolSlot);
        if (requiredItem == null) requiredItem = "";
        SafetyState state = safetyState();
        PlanContext context = new PlanContext(state, zeroBound(), targetEstimate(), targetHealth(),
            false, toolSlot, requiredItem, selected, inventory.offhandItem(), false, false, false, null,
            false, expected);
        long intentId = sequence.plan().planId() * 1_000_000L + 500_000L + (cleanupIntentSequence++);
        Intent intent = new Intent(intentId, sequence.plan().planId(), Strategy.CLEANUP,
            mineAction(cell), cell, null, Set.of(), Set.of(), Set.of(),
            serverTick + PistonPlanner.INTENT_LIFETIME_TICKS);
        // Cleanup carries no risk and no explosion bound: breaking the trap's own debris cannot hurt the
        // target. It still consumes one packet slot through the same rolling budget as every other send;
        // the packet-only lease dies as soon as this send is reported (or when the dispatch fails).
        if (!scheduler.reservePackets(intent, ticket, serverTick, PACKET_COST, needsSlot)) {
            debug("cleanup-reserve-refused cell=" + cell + " tick=" + serverTick);
            return false;
        }
        runtime.plan(intent, context);
        CombatRuntime.DispatchResult result = runtime.dispatch(intent, ticket);
        if (result.status() == CombatRuntime.Status.SENT) {
            debug("cleanup-mine cell=" + cell + " block=" + expected + " slot=" + toolSlot
                + " tick=" + serverTick);
            return true;
        }
        // A refused dispatch may have left the packet-only lease behind; release it and cancel any break
        // this cell was still driving (the pre-send re-check saw the block change).
        scheduler.releaseIntent(intent.plan(), intent.id());
        if (result.reason() == CombatRuntime.Reason.BLOCK_CHANGED) runtime.stopDestruction(cell);
        debug("cleanup-mine-skipped cell=" + cell + " status=" + result.status()
            + " reason=" + result.reason());
        return false;
    }

    /** Power first, then the piston, then every support this trap placed, in placement order. */
    private List<Cell> cleanupOrder() {
        PistonGeometry.PistonPush push = sequence.plan().push();
        Set<Cell> order = new LinkedHashSet<>();
        if (push.power() != null) order.add(push.power());
        if (push.piston() != null) order.add(push.piston());
        for (Intent intent : sequence.plan().intents()) {
            if (intent.action() == Action.PLACE_SUPPORT && intent.cell() != null) {
                order.add(intent.cell());
            }
        }
        return new ArrayList<>(order);
    }

    private String expectedCleanupBlock(Cell cell) {
        if (sequence == null || cell == null) return null;
        PistonGeometry.PistonPush push = sequence.plan().push();
        if (push.power() != null && push.power().equals(cell)) return push.powerKind().itemId();
        if (push.piston() != null && push.piston().equals(cell)) return PISTON_ITEM;
        return SUPPORT_ITEM;
    }

    private Action mineAction(Cell cell) {
        PistonGeometry.PistonPush push = sequence.plan().push();
        if (push.power() != null && push.power().equals(cell)) return Action.MINE_POWER;
        if (push.piston() != null && push.piston().equals(cell)) return Action.MINE_PISTON;
        return Action.MINE_SUPPORT;
    }

    /** Drops sent cells whose placed block is gone or replaced; the manual list keeps the rest. */
    private void pruneSentStructural() {
        if (sequence == null) return;
        Iterator<Cell> iterator = sentStructural.iterator();
        while (iterator.hasNext()) {
            Cell cell = iterator.next();
            String expected = expectedCleanupBlock(cell);
            if (expected != null && !expected.equals(world.blockId(cell))) {
                runtime.stopDestruction(cell);
                iterator.remove();
                debug("cleanup-forget cell=" + cell + " expected=" + expected
                    + " observed=" + world.blockId(cell));
            }
        }
    }

    private void clearCleanupPriority() {
        if (cleanupPriority != null) cleanupPriority.clear();
    }

    /** Cancels the vanilla destruction this trap's cleanup started, if any; no-op otherwise. */
    private void stopCleanupDestruction() {
        runtime.stopDestruction();
    }

    /**
     * Pure feasibility probe installed on {@link CleanupPriority}: true only when the cleanup break the
     * trap would dispatch next could actually reserve its packet (and, when a switch is needed, the
     * hotbar slot) right now. The engine yields only on true, so direct offence is never starved by a
     * cleanup that cannot send. This method never sends, reserves or touches risk.
     */
    private boolean cleanupCanReserve() {
        if (!enabled || !autoCleanup || sequence == null || ticket == null) return false;
        if (sequence.phase() != PistonSequence.PistonPhase.CLEANUP) return false;
        if (crystalDispatched && !crystalRemovedObserved) return false;
        Cell target = nextCleanupCell();
        if (target == null) return false;
        String expected = expectedCleanupBlock(target);
        if (expected == null) return false;
        int toolSlot = inventory.toolSlot(expected);
        if (toolSlot < 0) return false;
        if (toolSlot != inventory.selectedSlot() && scheduler.hotbarSlotHeld()) return false;
        return scheduler.packetBudgetAvailable(scheduler.clock(), PACKET_COST);
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
                    result.reason().name().toLowerCase(Locale.ROOT), serverTick);
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
        if (lastTargetPosition == null) {
            lastTargetPosition = position;
            lastTargetTick = serverTick;
        } else {
            int elapsedTicks = lastTargetTick < 0 ? 1 : Math.max(1, serverTick - lastTargetTick);
            if (position.distance(lastTargetPosition) > 1.0e-6) {
                // POWER/WAIT_PUSH expect the piston's own horizontal push. An upward impulse that only an
                // external blast can explain means the target left the hole: abort instead of firing the
                // attack. A normal jump or step is smaller than VERTICAL_PUSH_DELTA per tick; downward
                // motion (holed_vertical) never matches either.
                PistonSequence.PistonPhase phase = sequence.phase();
                if (watchingForVerticalPush(phase)
                    && verticallyPushedOut(lastTargetPosition, position, serverTick, elapsedTicks)) {
                    lastTargetPosition = position;
                    lastTargetTick = serverTick;
                    fail(phase, "target-pushed-vertically", serverTick, false);
                    return;
                }
                sequence.targetMoved(position,
                    world.withinAttackEnvelope(target, sequence.computedPushed()), serverTick);
            }
            lastTargetPosition = position;
            lastTargetTick = serverTick;
        }
        if (!world.alive(target)) {
            sequence.targetMoved(position, false, serverTick);
        }
    }

    private static boolean watchingForVerticalPush(PistonSequence.PistonPhase phase) {
        return phase == PistonSequence.PistonPhase.POWER
            || phase == PistonSequence.PistonPhase.WAIT_PUSH;
    }

    /**
     * An explosion-like upward per-tick impulse plus recent evidence: either an explosion observation near
     * the target or a non-plan block change near it. Jumping (about 0.42 blocks per tick at most) and
     * stepping never clear the impulse threshold.
     */
    private boolean verticallyPushedOut(Vec previous, Vec current, int serverTick, int elapsedTicks) {
        double risePerTick = (current.y() - previous.y()) / Math.max(1, elapsedTicks);
        if (risePerTick <= VERTICAL_PUSH_DELTA) return false;
        boolean explosion = lastExplosionTick >= 0
            && serverTick - lastExplosionTick <= DISTURBANCE_RECENCY_TICKS;
        boolean disturbance = lastDisturbanceTick >= 0
            && serverTick - lastDisturbanceTick <= DISTURBANCE_RECENCY_TICKS;
        return explosion || disturbance;
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
            pushObserved = true;
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
            // The sent attack is the terminal evidence that the crystal is gone; manual cleanup must stop
            // listing it even before a separate removal observation arrives.
            crystalRemovedObserved = true;
            sequence.attackSent(serverTick);
        } else {
            lastFailure = result.reason().name().toLowerCase(Locale.ROOT);
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
        fail(stage, reason, serverTick, true);
    }

    /**
     * @param retryStructural true for a failed placement (the sequence may retry a structural stage);
     *     false for a whole-trap failure such as a vertical push, which must abort even in POWER
     */
    private void fail(PistonSequence.PistonPhase stage, String reason, int serverTick,
                      boolean retryStructural) {
        lastFailure = reason;
        crystalBaselineUuids = Set.of();
        crystalBaselineEntities = Set.of();
        boolean wasTerminal = terminal();
        if (sequence.phase() == PistonSequence.PistonPhase.CLEANUP) {
            stopCleanupDestruction();
        }
        if (retryStructural) sequence.stageFailed(stage, reason, serverTick);
        else sequence.abortTrap(reason, serverTick);
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
            + " delta=" + String.format(Locale.ROOT, "%.4f", delta)
            + " tolerance=" + POSITION_TOLERANCE);
        if (delta > POSITION_TOLERANCE) {
            incident("position-disagreement computed=" + computed + " client=" + client
                + " delta=" + String.format(Locale.ROOT, "%.4f", delta)
                + " tolerance=" + POSITION_TOLERANCE);
        }
    }

    /** Emits one debug record per phase change; entering MANUAL_CLEANUP is an incident. */
    private void tracePhase() {
        if (sequence == null) return;
        PistonSequence.PistonPhase phase = sequence.phase();
        if (phase == observedPhase) return;
        debug("stage " + (observedPhase == null ? "-" : observedPhase.name()) + " -> " + phase);
        if ((phase == PistonSequence.PistonPhase.MANUAL_CLEANUP
            || phase == PistonSequence.PistonPhase.ABORTED) && observedPhase != phase) {
            String blocks = manualCleanupText();
            debug("manual-cleanup-blocks reason=" + (lastFailure.isEmpty() ? "unspecified" : lastFailure)
                + " blocks=" + (blocks.isEmpty() ? "none" : blocks));
        }
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
