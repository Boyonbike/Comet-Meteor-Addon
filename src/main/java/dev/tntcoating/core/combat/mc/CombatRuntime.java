/*
 * Original Comet implementation by Boyonabike.
 * Independent: written from the pinned 26.1.2 artifact and Meteor build 42 behaviour; no third-party code.
 * SPDX-License-Identifier: CC0-1.0
 */

package dev.tntcoating.core.combat.mc;

import dev.tntcoating.core.combat.Action;
import dev.tntcoating.core.combat.ActionGeneration;
import dev.tntcoating.core.combat.Box;
import dev.tntcoating.core.combat.Capability;
import dev.tntcoating.core.combat.Cell;
import dev.tntcoating.core.combat.CombatDamagePolicy;
import dev.tntcoating.core.combat.CombatProfiles;
import dev.tntcoating.core.combat.CombatRiskBudget;
import dev.tntcoating.core.combat.CombatScheduler;
import dev.tntcoating.core.combat.CombatSession;
import dev.tntcoating.core.combat.ConnectionKey;
import dev.tntcoating.core.combat.EffectiveHealth;
import dev.tntcoating.core.combat.EntityKey;
import dev.tntcoating.core.combat.ExposureBound;
import dev.tntcoating.core.combat.Intent;
import dev.tntcoating.core.combat.Observation;
import dev.tntcoating.core.combat.ObservationKind;
import dev.tntcoating.core.combat.ObservationPayload;
import dev.tntcoating.core.combat.PistonFacing;
import dev.tntcoating.core.combat.RiskModel;
import dev.tntcoating.core.combat.Safety;
import dev.tntcoating.core.combat.SafetyState;
import dev.tntcoating.core.combat.Snapshot;
import dev.tntcoating.core.combat.Strategy;
import dev.tntcoating.core.combat.SuspensionCause;
import dev.tntcoating.core.combat.TargetEstimate;
import dev.tntcoating.core.combat.TickBudget;
import dev.tntcoating.core.combat.Ticket;
import dev.tntcoating.core.combat.Vec;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.function.Consumer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;

/**
 * The single sender for combat actions.
 *
 * <p>{@link #dispatch(Intent, Ticket)} revalidates the whole action against the newest applied snapshot and
 * the current client state, enters Dispatching, sends packet by packet and records each packet's progress as
 * a separate fact. A failure before the causative packet releases every unsent reservation; a failure after
 * the causative packet was accepted never rolls the risk back, because the server may have seen it.
 *
 * <p>Risk retirement is never performed here: observations go to the budget, whose rules decide. The runtime
 * only wires lifecycle events to the scheduler and budget so no unsent lease or reservation leaks across
 * death, respawn, dimension change, connection close, disable or configuration change.</p>
 */
public final class CombatRuntime implements AutoCloseable {
    private static final String END_CRYSTAL_ID = MinecraftCombatClient.endCrystalId();
    private static final int MAX_TRACES = 32;
    /**
     * Packet-only ids for the BARRIER probe. They never reach the planner, never own a cell, entity or slot,
     * and never create risk, debt or a hazard: the lease exists only to book one packet and hand one
     * prediction-sequenced action to the sink.
     */
    private static final long BARRIER_PROBE_PLAN = Long.MIN_VALUE + 1;
    private static final long BARRIER_PROBE_INTENT = Long.MIN_VALUE + 2;
    private static final int BARRIER_PROBE_MAX_RISE = 4;

    public enum Status {
        SENT,
        BLOCKED,
        FAILED
    }

    /** Typed dispatch outcome. {@code NONE} is the success reason. */
    public enum Reason {
        NONE,
        NOT_CLIENT_THREAD,
        TICKET_INVALID,
        PLAN_MISMATCH,
        CONNECTION_CLOSED,
        NO_PLAN,
        NOT_READY,
        PACKET_BUDGET,
        UNSUPPORTED_ACTION,
        UNKNOWN_ENTITY,
        ENTITY_MISMATCH,
        NO_LEASE,
        SUSPENDED,
        POLICY_REJECTED,
        OUT_OF_REACH,
        NOT_VISIBLE,
        INVENTORY_OWNED_ELSEWHERE,
        SCREEN_OPEN,
        MANUAL_SLOT_CHANGE,
        OFFHAND_CHANGED,
        ITEM_MISSING,
        ROTATION_NOT_READY,
        RISK_NOT_HELD,
        SLOT_SWITCH_FAILED,
        CAUSATIVE_NOT_ACCEPTED,
        SUPPORTING_PACKET_FAILED,
        NO_SUPPORT,
        OCCUPIED,
        WRONG_FACING,
        BLOCK_CHANGED
    }

    /** Caller-supplied facts dispatch revalidates against. Set when the planner reserves. */
    public record PlanContext(SafetyState safetyState, ExposureBound exposure, TargetEstimate estimate,
                              EffectiveHealth targetHealth, boolean rotationsOn, int expectedHotbarSlot,
                              String requiredItemId, int selectionAtReserve, String offhandItemBefore,
                              boolean targetOverride, boolean faceplace, boolean defensive,
                              PistonFacing pistonFacing, boolean structural, String expectedBlockId) {
        /** Placement/attack contexts: no block-id claim is revalidated. */
        public PlanContext(SafetyState safetyState, ExposureBound exposure, TargetEstimate estimate,
                           EffectiveHealth targetHealth, boolean rotationsOn, int expectedHotbarSlot,
                           String requiredItemId, int selectionAtReserve, String offhandItemBefore,
                           boolean targetOverride, boolean faceplace, boolean defensive,
                           PistonFacing pistonFacing, boolean structural) {
            this(safetyState, exposure, estimate, targetHealth, rotationsOn, expectedHotbarSlot,
                requiredItemId, selectionAtReserve, offhandItemBefore, targetOverride, faceplace,
                defensive, pistonFacing, structural, null);
        }

        /** Pre-faceplace contexts: the policy revalidation uses the normal minimum target damage. */
        public PlanContext(SafetyState safetyState, ExposureBound exposure, TargetEstimate estimate,
                           EffectiveHealth targetHealth, boolean rotationsOn, int expectedHotbarSlot,
                           String requiredItemId, int selectionAtReserve, String offhandItemBefore,
                           boolean targetOverride) {
            this(safetyState, exposure, estimate, targetHealth, rotationsOn, expectedHotbarSlot,
                requiredItemId, selectionAtReserve, offhandItemBefore, targetOverride, false, false,
                null, false);
        }

        /** Faceplace-aware offence context; never defensive, never structural. */
        public PlanContext(SafetyState safetyState, ExposureBound exposure, TargetEstimate estimate,
                           EffectiveHealth targetHealth, boolean rotationsOn, int expectedHotbarSlot,
                           String requiredItemId, int selectionAtReserve, String offhandItemBefore,
                           boolean targetOverride, boolean faceplace) {
            this(safetyState, exposure, estimate, targetHealth, rotationsOn, expectedHotbarSlot,
                requiredItemId, selectionAtReserve, offhandItemBefore, targetOverride, faceplace, false,
                null, false);
        }

        /** Defensive contexts; never structural and never a target-damage claim. */
        public PlanContext(SafetyState safetyState, ExposureBound exposure, TargetEstimate estimate,
                           EffectiveHealth targetHealth, boolean rotationsOn, int expectedHotbarSlot,
                           String requiredItemId, int selectionAtReserve, String offhandItemBefore,
                           boolean targetOverride, boolean faceplace, boolean defensive) {
            this(safetyState, exposure, estimate, targetHealth, rotationsOn, expectedHotbarSlot,
                requiredItemId, selectionAtReserve, offhandItemBefore, targetOverride, faceplace, defensive,
                null, false);
        }

        public PlanContext {
            Objects.requireNonNull(safetyState, "safetyState");
            Objects.requireNonNull(exposure, "exposure");
            Objects.requireNonNull(estimate, "estimate");
            Objects.requireNonNull(requiredItemId, "requiredItemId");
            Objects.requireNonNull(offhandItemBefore, "offhandItemBefore");
            if (expectedHotbarSlot < 0 || expectedHotbarSlot > 8) {
                throw new IllegalArgumentException("expectedHotbarSlot must be 0-8");
            }
            if (selectionAtReserve < 0 || selectionAtReserve > 8) {
                throw new IllegalArgumentException("selectionAtReserve must be 0-8");
            }
            CombatProfiles.productionSafety(safetyState.safety());
        }
    }

    /** Packet-level progress: local enqueue, send-method completion and later server ack are separate. */
    public static final class DispatchTrace {
        public enum Stage {
            SLOT_SWITCH,
            CAUSATIVE,
            SWING,
            RESTORE
        }

        /** One send attempt. {@code acknowledged} is set only by a real ack sequence. */
        public static final class PacketProgress {
            private final Stage stage;
            private final boolean enqueued;
            private final boolean sendMethodCompleted;
            private final int ackSequence;
            private boolean acknowledged;

            PacketProgress(Stage stage, boolean enqueued, boolean sendMethodCompleted, int ackSequence) {
                this.stage = stage;
                this.enqueued = enqueued;
                this.sendMethodCompleted = sendMethodCompleted;
                this.ackSequence = ackSequence;
            }

            public Stage stage() {
                return stage;
            }

            public boolean enqueued() {
                return enqueued;
            }

            public boolean sendMethodCompleted() {
                return sendMethodCompleted;
            }

            public int ackSequence() {
                return ackSequence;
            }

            public boolean acknowledged() {
                return acknowledged;
            }

            void markAcknowledged(int sequence) {
                if (ackSequence >= 0 && sequence == ackSequence) acknowledged = true;
            }
        }

        private final List<PacketProgress> packets = new ArrayList<>();

        void record(Stage stage, CombatPacketSink.SendResult result) {
            if (result == null) {
                packets.add(new PacketProgress(stage, false, false, -1));
                return;
            }
            packets.add(new PacketProgress(stage, result.enqueued(), result.sendMethodCompleted(),
                result.ackSequence()));
        }

        public List<PacketProgress> packets() {
            return List.copyOf(packets);
        }

        public Optional<PacketProgress> packet(Stage stage) {
            return packets.stream().filter(packet -> packet.stage == stage).findFirst();
        }

        public boolean causativeAccepted() {
            return packet(Stage.CAUSATIVE).map(PacketProgress::enqueued).orElse(false);
        }

        public boolean anySent() {
            return packets.stream().anyMatch(PacketProgress::enqueued);
        }

        public int acknowledgedCount() {
            return (int) packets.stream().filter(PacketProgress::acknowledged).count();
        }

        void acknowledge(int sequence) {
            for (PacketProgress packet : packets) packet.markAcknowledged(sequence);
        }
    }

    public record DispatchResult(Status status, Reason reason, DispatchTrace trace) {
        public DispatchResult {
            Objects.requireNonNull(status, "status");
            Objects.requireNonNull(reason, "reason");
            Objects.requireNonNull(trace, "trace");
        }

        public boolean sent() {
            return status == Status.SENT;
        }
    }

    private record PlanKey(long plan, long intentId) {
        static PlanKey of(Intent intent) {
            return new PlanKey(intent.plan(), intent.id());
        }
    }

    private record ActionGenerationHolder(ActionGeneration generation, ConnectionKey connection) {
    }

    private final CombatSession session;
    private final CombatScheduler scheduler;
    private final CombatRiskBudget risk;
    private final ObservationIntake intake;
    private final CombatInventoryAdapter inventory;
    private final CombatClient client;
    private final CombatPacketSink sink;
    private final Safety safety;

    private final Map<PlanKey, PlanContext> contexts = new HashMap<>();
    private final Set<Long> knownPlans = new HashSet<>();
    private final Deque<DispatchTrace> traces = new ArrayDeque<>();
    private int lastTickedTick = -1;
    private int lastServerTick = -1;
    /** One server tick may not be advanced twice, and every send needs a tick to book its packet in. */
    private boolean enabled;
    private int tickCount;
    private boolean closed;
    /**
     * The one cell whose vanilla destruction this runtime started through the sink and has not cancelled.
     * Tracked so cleanup can always stop it, even after the trap stops ticking; never a combat action.
     */
    private Cell destructionCell;
    /** Respawn reconciliation, installed by the controller; runs after the budget observed RESPAWN. */
    private volatile Runnable respawnReconciler = () -> { };
    /** Passive dispatch observer; runs after every decision and must never send or change state. */
    private volatile Consumer<DispatchResult> dispatchObserver = result -> { };
    /** Passive explosion observer; receives each applied explosion centre on the client thread. */
    private volatile Consumer<Vec> explosionObserver = position -> { };
    /** Shared per-tick budget installed by the controller; exhaustion only stops optional planning. */
    private volatile TickBudget activeBudget = TickBudget.unlimited();

    public CombatRuntime(CombatSession session, CombatScheduler scheduler, CombatRiskBudget risk,
                         ObservationIntake intake, CombatInventoryAdapter inventory,
                         CombatClient client, CombatPacketSink sink, Safety safety) {
        this.session = Objects.requireNonNull(session, "session");
        this.scheduler = Objects.requireNonNull(scheduler, "scheduler");
        this.risk = Objects.requireNonNull(risk, "risk");
        this.intake = Objects.requireNonNull(intake, "intake");
        this.inventory = Objects.requireNonNull(inventory, "inventory");
        this.client = Objects.requireNonNull(client, "client");
        this.sink = Objects.requireNonNull(sink, "sink");
        // Production boundary: a profile permissive enough to bypass the safety model is refused here.
        this.safety = CombatProfiles.productionSafety(safety);
        intake.setBlockAckListener(this::onBlockChangedAck);
    }

    /** Fresh applied-world snapshot stamped with the revision the intake has applied. */
    public Snapshot capture() {
        activeBudget.spend(TickBudget.Phase.SNAPSHOT_CAPTURE);
        return new Snapshot(session.connection(), intake.appliedRevision(), client.crystals());
    }

    /** Installs the shared per-tick budget for this tick. */
    public void activateBudget(TickBudget budget) {
        this.activeBudget = budget == null ? TickBudget.unlimited() : budget;
    }

    public TickBudget activeBudget() {
        return activeBudget;
    }

    /** Observations already applied by the hooks, in receipt order. */
    public List<Observation> drain() {
        return intake.drain();
    }

    /** Binds the planner-supplied revalidation data to one reserved intent. */
    public void plan(Intent intent, PlanContext context) {
        Objects.requireNonNull(intent, "intent");
        Objects.requireNonNull(context, "context");
        knownPlans.add(intent.plan());
        contexts.put(PlanKey.of(intent), context);
    }

    public void forgetPlan(long plan, long intentId) {
        contexts.remove(new PlanKey(plan, intentId));
    }

    public Optional<DispatchTrace> lastTrace() {
        return Optional.ofNullable(traces.peekLast());
    }

    public Safety safety() {
        return safety;
    }

    /** The only combat send path. Client thread only; never from a render or network callback. */
    public DispatchResult dispatch(Intent intent, Ticket ticket) {
        DispatchResult result = dispatchInternal(intent, ticket);
        dispatchObserver.accept(result);
        return result;
    }

    private DispatchResult dispatchInternal(Intent intent, Ticket ticket) {
        DispatchTrace trace = new DispatchTrace();
        // Validation is mandatory even when the budget is gone: a cache hit may never skip it.
        activeBudget.spend(TickBudget.Phase.VALIDATION);
        if (!client.onClientThread()) {
            return new DispatchResult(Status.FAILED, Reason.NOT_CLIENT_THREAD, trace);
        }
        if (intent == null || ticket == null) {
            return new DispatchResult(Status.FAILED, Reason.TICKET_INVALID, trace);
        }
        if (!session.isOpen() || !session.valid(ticket)) {
            scheduler.invalidateGeneration(ticket.gen());
            risk.actionGenerationEnded(ticket.gen());
            forget(PlanKey.of(intent));
            return new DispatchResult(Status.FAILED, Reason.TICKET_INVALID, trace);
        }
        if (ticket.plan() != intent.plan()) {
            return new DispatchResult(Status.FAILED, Reason.PLAN_MISMATCH, trace);
        }
        if (!sink.open() || !client.connectionOpen()) {
            return new DispatchResult(Status.FAILED, Reason.CONNECTION_CLOSED, trace);
        }
        PlanKey key = PlanKey.of(intent);
        PlanContext context = contexts.get(key);
        if (context == null && intent.strategy() != Strategy.PROBE) {
            scheduler.releaseIntent(intent.plan(), intent.id());
            return new DispatchResult(Status.FAILED, Reason.NO_PLAN, trace);
        }
        if (lastServerTick < 0) {
            // Packet capacity is booked per tick; without a tick there is no honest tick to book in.
            release(intent);
            return new DispatchResult(Status.BLOCKED, Reason.PACKET_BUDGET, trace);
        }
        if (intake.overflowed()) {
            risk.suspend(SuspensionCause.QUEUE_OVERFLOW);
        }
        if (risk.suspended()) {
            release(intent);
            return new DispatchResult(Status.BLOCKED, Reason.SUSPENDED, trace);
        }
        Snapshot snapshot = capture();
        if (!snapshot.conn().equals(ticket.conn())) {
            release(intent);
            return new DispatchResult(Status.FAILED, Reason.TICKET_INVALID, trace);
        }
        if (!scheduler.ready(intent, snapshot)) {
            release(intent);
            return new DispatchResult(Status.BLOCKED, Reason.NOT_READY, trace);
        }
        if (!scheduler.reacquirePackets(intent.plan(), intent.id(), lastServerTick)) {
            forget(key);
            return new DispatchResult(Status.BLOCKED, Reason.PACKET_BUDGET, trace);
        }
        if (intent.strategy() == Strategy.PROBE) {
            // The probe has no planner context, target or risk lease. It touches no block, entity or item;
            // it only needs a real prediction sequence and the server's sequence acknowledgement.
            return dispatchProbe(intent, trace);
        }

        boolean attack = intent.action() == Action.ATTACK_CRYSTAL;
        boolean support = intent.action() == Action.PLACE_SUPPORT;
        boolean blocker = intent.action() == Action.PLACE_BLOCKER;
        boolean piston = intent.action() == Action.PLACE_PISTON;
        boolean power = intent.action() == Action.PLACE_POWER;
        boolean mine = intent.action() == Action.MINE_POWER
            || intent.action() == Action.MINE_PISTON
            || intent.action() == Action.MINE_SUPPORT;
        boolean structural = support || blocker || piston || power;
        boolean place = intent.action() == Action.PLACE_CRYSTAL || structural;
        if (!attack && !mine && !place) {
            release(intent);
            return new DispatchResult(Status.FAILED, Reason.UNSUPPORTED_ACTION, trace);
        }
        EntityKey targetEntity = null;
        Vec targetPosition;
        Box targetBox;
        if (attack) {
            targetEntity = intent.entity();
            if (targetEntity == null || !client.entityPresent(targetEntity)) {
                release(intent);
                return new DispatchResult(Status.BLOCKED, Reason.UNKNOWN_ENTITY, trace);
            }
            if (!END_CRYSTAL_ID.equals(client.entityType(targetEntity))) {
                release(intent);
                return new DispatchResult(Status.FAILED, Reason.ENTITY_MISMATCH, trace);
            }
            targetPosition = client.entityPosition(targetEntity);
            targetBox = client.entityBox(targetEntity);
        } else {
            Cell cell = intent.cell();
            if (cell == null) {
                release(intent);
                return new DispatchResult(Status.FAILED, Reason.UNSUPPORTED_ACTION, trace);
            }
            if (mine) {
                // Cleanup revalidates the exact block identity: the cell must still hold the block this
                // trap placed. A changed cell is skipped, never mined speculatively.
                String expected = context.expectedBlockId();
                if (expected == null || expected.isEmpty() || !expected.equals(client.blockId(cell))) {
                    release(intent);
                    return new DispatchResult(Status.FAILED, Reason.BLOCK_CHANGED, trace);
                }
            } else {
                if (blocker && !client.supportBelow(cell.below())) {
                    // Fail closed: a blocker with no sturdy top face is never sent.
                    release(intent);
                    return new DispatchResult(Status.FAILED, Reason.NO_SUPPORT, trace);
                }
                // Piston structural placements revalidate geometry at dispatch: the cell must still be
                // empty/replaceable, free of entities and sitting on a sturdy face, and no piston may
                // already be there (its facing could not be guaranteed).
                if (context.structural() && structural) {
                    if (!client.replaceable(cell) || !client.entityFree(cell)) {
                        release(intent);
                        return new DispatchResult(Status.FAILED, Reason.OCCUPIED, trace);
                    }
                    if (!client.supportBelow(cell.below())) {
                        release(intent);
                        return new DispatchResult(Status.FAILED, Reason.NO_SUPPORT, trace);
                    }
                    if (piston && (client.pistonFacing(cell) != null || context.pistonFacing() == null)) {
                        release(intent);
                        return new DispatchResult(Status.FAILED, Reason.WRONG_FACING, trace);
                    }
                }
            }
            targetPosition = cell.center();
            targetBox = Box.fromCell(cell);
        }
        if (targetPosition == null || targetBox == null) {
            release(intent);
            return new DispatchResult(Status.BLOCKED, Reason.UNKNOWN_ENTITY, trace);
        }
        boolean packetOnlyCleanup = intent.strategy() == Strategy.CLEANUP;
        if (scheduler.riskId(intent.plan(), intent.id()).isEmpty() && !packetOnlyCleanup) {
            release(intent);
            return new DispatchResult(Status.FAILED, Reason.NO_LEASE, trace);
        }
        if (risk.suspended()) {
            release(intent);
            return new DispatchResult(Status.BLOCKED, Reason.SUSPENDED, trace);
        }
        SafetyState state = context.safetyState();
        EffectiveHealth targetHealth = context.targetHealth() != null
            ? context.targetHealth()
            : new EffectiveHealth(0.0, 0.0, Capability.UNKNOWN, 0L);
        CombatDamagePolicy policy = new CombatDamagePolicy(state.selfHealth(), targetHealth);
        // Structural placement and cleanup mining make no target-damage claim: they still revalidate the
        // planner's worst-case self/friend exposure (zero for supports/piston/power and cleanup).
        boolean allowed = context.structural() || mine
            ? policy.allowsDefensive(context.exposure(), state.safety())
            : context.defensive()
                ? policy.allowsDefensive(context.exposure(), state.safety())
                : policy.allows(context.estimate(), context.exposure(), state.safety(),
                    context.targetOverride(), context.faceplace());
        if (!allowed) {
            release(intent);
            return new DispatchResult(Status.FAILED, Reason.POLICY_REJECTED, trace);
        }
        Vec eye = client.eyePosition();
        if (eye == null) {
            release(intent);
            return new DispatchResult(Status.BLOCKED, Reason.NOT_READY, trace);
        }
        double range = attack ? client.entityInteractionRange() : client.blockInteractionRange();
        if (eye.distance(targetPosition) > range + 1.0e-9) {
            release(intent);
            return new DispatchResult(Status.FAILED, Reason.OUT_OF_REACH, trace);
        }
        if (!client.visible(eye, targetBox)) {
            release(intent);
            return new DispatchResult(Status.FAILED, Reason.NOT_VISIBLE, trace);
        }
        if (inventory.ownedElsewhere()) {
            release(intent);
            return new DispatchResult(Status.BLOCKED, Reason.INVENTORY_OWNED_ELSEWHERE, trace);
        }
        if (inventory.screenOpen()) {
            release(intent);
            return new DispatchResult(Status.BLOCKED, Reason.SCREEN_OPEN, trace);
        }
        if (inventory.selectedSlot() != context.selectionAtReserve()) {
            release(intent);
            return new DispatchResult(Status.FAILED, Reason.MANUAL_SLOT_CHANGE, trace);
        }
        if (!inventory.offhandItem().equals(context.offhandItemBefore())) {
            release(intent);
            return new DispatchResult(Status.FAILED, Reason.OFFHAND_CHANGED, trace);
        }
        if (!context.requiredItemId().isEmpty()
            && !context.requiredItemId().equals(inventory.hotbarItem(context.expectedHotbarSlot()))) {
            release(intent);
            return new DispatchResult(Status.BLOCKED, Reason.ITEM_MISSING, trace);
        }
        if (context.rotationsOn() && !mine) {
            double[] aim = piston ? aimForPiston(eye, targetPosition, context.pistonFacing())
                : aim(eye, targetPosition);
            if (!client.rotationSatisfied(aim[0], aim[1])) {
                client.requestRotation(aim[0], aim[1], null);
                release(intent);
                return new DispatchResult(Status.BLOCKED, Reason.ROTATION_NOT_READY, trace);
            }
        }
        if (!scheduler.dispatchStarted(intent.plan(), intent.id())) {
            release(intent);
            return new DispatchResult(Status.FAILED, Reason.RISK_NOT_HELD, trace);
        }

        int previousSlot = inventory.selectedSlot();
        int ownedSlot = -1;
        if (context.expectedHotbarSlot() != previousSlot) {
            CombatInventoryAdapter.SlotSwitch switched =
                inventory.switchTo(context.expectedHotbarSlot(), previousSlot, sink);
            trace.record(DispatchTrace.Stage.SLOT_SWITCH, new CombatPacketSink.SendResult(
                switched.enqueued(), switched.completed(), -1));
            if (switched.failed()) {
                release(intent);
                return new DispatchResult(Status.FAILED, Reason.SLOT_SWITCH_FAILED, trace);
            }
            if (switched.attempted()) ownedSlot = context.expectedHotbarSlot();
        }

        // Switching cleanup targets cancels the previous block's vanilla destruction first, so the server
        // cannot finish it after this module moved on.
        if (mine && destructionCell != null && !destructionCell.equals(intent.cell())) {
            stopDestruction();
        }

        CombatPacketSink.SendResult causative = attack
            ? sink.attack(targetEntity.id())
            : mine ? sink.breakBlock(breakHit(intent.cell()))
            : piston ? sink.placePiston(placementHit(intent.cell()))
            : power ? sink.placePower(placementHit(intent.cell()))
            : support || blocker ? sink.placeSupport(placementHit(intent.cell()))
            : sink.placeCrystal(placementHit(intent.cell()));
        trace.record(DispatchTrace.Stage.CAUSATIVE, causative);
        if (!causative.enqueued()) {
            if (ownedSlot >= 0) {
                CombatInventoryAdapter.SlotSwitch restored =
                    inventory.restore(previousSlot, ownedSlot, sink);
                trace.record(DispatchTrace.Stage.RESTORE, new CombatPacketSink.SendResult(
                    restored.enqueued(), restored.completed(), -1));
            }
            release(intent);
            traces.addLast(trace);
            trimTraces();
            return new DispatchResult(Status.FAILED, Reason.CAUSATIVE_NOT_ACCEPTED, trace);
        }
        if (mine) destructionCell = intent.cell();

        // Irreversible from here: the causative packet may have reached the server.
        scheduler.sent(intent, lastServerTick, snapshot.appliedRevision());
        contexts.remove(key);
        Reason reason = Reason.NONE;
        CombatPacketSink.SendResult swing = sink.swing();
        trace.record(DispatchTrace.Stage.SWING, swing);
        if (!swing.enqueued()) reason = Reason.SUPPORTING_PACKET_FAILED;
        if (ownedSlot >= 0) {
            CombatInventoryAdapter.SlotSwitch restored = inventory.restore(previousSlot, ownedSlot, sink);
            trace.record(DispatchTrace.Stage.RESTORE, new CombatPacketSink.SendResult(
                restored.enqueued(), restored.completed(), -1));
            if (restored.failed()) reason = Reason.SUPPORTING_PACKET_FAILED;
        }
        traces.addLast(trace);
        trimTraces();
        return new DispatchResult(Status.SENT, reason, trace);
    }

    /**
     * Hands one prediction-sequenced {@code ABORT_DESTROY_BLOCK} to the sink at an inert air cell above the
     * player, then releases the packet-only lease. The abort branch clears only destroy state and always
     * acknowledges the sequence. A sink refusal, missing position or negative sequence fails the probe and
     * keeps the debt: no ack is invented and no risk retires here.
     */
    private DispatchResult dispatchProbe(Intent intent, DispatchTrace trace) {
        if (intent.action() != Action.PROBE) {
            release(intent);
            return new DispatchResult(Status.FAILED, Reason.UNSUPPORTED_ACTION, trace);
        }
        Cell inert = probeCell();
        if (inert == null) {
            release(intent);
            return new DispatchResult(Status.BLOCKED, Reason.NOT_READY, trace);
        }
        CombatPacketSink.SendResult probe = sink.probeBlockChange(
            new BlockPos(inert.x(), inert.y(), inert.z()));
        trace.record(DispatchTrace.Stage.CAUSATIVE, probe);
        if (!probe.enqueued() || probe.ackSequence() < 0) {
            release(intent);
            return new DispatchResult(Status.FAILED, Reason.CAUSATIVE_NOT_ACCEPTED, trace);
        }
        scheduler.sent(intent, lastServerTick, intake.appliedRevision());
        return new DispatchResult(Status.SENT, Reason.NONE, trace);
    }

    /** First air cell in the short column above the eye, or null when none is safe. */
    private Cell probeCell() {
        Vec eye = client.eyePosition();
        if (eye == null) return null;
        int x = (int) Math.floor(eye.x());
        int z = (int) Math.floor(eye.z());
        int baseY = (int) Math.floor(eye.y());
        for (int rise = 1; rise <= BARRIER_PROBE_MAX_RISE; rise++) {
            Cell candidate = new Cell(x, baseY + rise, z);
            if (client.blockId(candidate) == null) return candidate;
        }
        return null;
    }

    private BlockHitResult placementHit(Cell cell) {
        // Crystal placement targets the top face of the supporting block one cell below.
        return new BlockHitResult(new Vec3(cell.x() + 0.5, cell.y(), cell.z() + 0.5),
            Direction.UP, new BlockPos(cell.x(), cell.y() - 1, cell.z()), false);
    }

    private BlockHitResult breakHit(Cell cell) {
        // Vanilla block breaking targets the cell itself; the face is only the interaction normal.
        return new BlockHitResult(new Vec3(cell.x() + 0.5, cell.y() + 0.5, cell.z() + 0.5),
            Direction.UP, new BlockPos(cell.x(), cell.y(), cell.z()), false);
    }

    /**
     * Piston placement must look horizontally along the negative facing: vanilla derives a placed piston's
     * facing from the nearest looking direction, so looking at the cell from a diagonal could set a
     * neighbouring facing. Pitch is clamped to keep that horizontal direction dominant.
     */
    private static double[] aimForPiston(Vec eye, Vec target, PistonFacing facing) {
        double[] aimed = aim(eye, target);
        if (facing != null) {
            double dx = -facing.dx();
            double dz = -facing.dz();
            aimed[0] = Math.toDegrees(Math.atan2(dz, dx)) - 90.0;
        }
        aimed[1] = Math.max(-20.0, Math.min(20.0, aimed[1]));
        return aimed;
    }

    private static double[] aim(Vec eye, Vec target) {
        double dx = target.x() - eye.x();
        double dy = target.y() - eye.y();
        double dz = target.z() - eye.z();
        double yaw = Math.toDegrees(Math.atan2(dz, dx)) - 90.0;
        double horizontal = Math.sqrt(dx * dx + dz * dz);
        double pitch = -Math.toDegrees(Math.atan2(dy, horizontal));
        return new double[] {yaw, pitch};
    }

    /**
     * Advances the combat clock exactly once per server tick: applies drained observations to the budget,
     * resolves lifecycle transitions they carry, then ticks scheduler and budget once each.
     */
    public void tick(int serverTick) {
        tick(serverTick, observation -> { });
    }

    /**
     * Same as {@link #tick(int)} but hands every connection-matching observation to {@code observationSink}
     * before it is applied to the risk budget. The sink is passive: it may read, must not send, must not
     * reorder observations and must not influence risk behaviour.
     */
    public void tick(int serverTick, Consumer<Observation> observationSink) {
        tick(serverTick, observationSink, observation -> { });
    }

    /**
     * Same as {@link #tick(int, Consumer)} plus {@code afterApply}, which observes each observation after
     * the risk budget applied it. It runs on the client thread and must not send or mutate risk.
     */
    public void tick(int serverTick, Consumer<Observation> observationSink,
                     Consumer<Observation> afterApply) {
        tick(serverTick, observationSink, afterApply, TickBudget.unlimited());
    }

    /**
     * Budgeted tick: queue drain, revision stamping and reconciliation record their work in the shared
     * budget. Reconciliation and observation application always run; only optional planning defers.
     */
    public void tick(int serverTick, Consumer<Observation> observationSink,
                     Consumer<Observation> afterApply, TickBudget budget) {
        Objects.requireNonNull(observationSink, "observationSink");
        Objects.requireNonNull(afterApply, "afterApply");
        if (budget == null) budget = TickBudget.unlimited();
        activateBudget(budget);
        if (!client.onClientThread()) return;
        if (serverTick < 0) return;
        if (serverTick == lastTickedTick) return;
        if (serverTick < lastTickedTick) {
            throw new IllegalArgumentException("server tick moved backwards: " + serverTick);
        }
        lastTickedTick = serverTick;
        lastServerTick = serverTick;
        tickCount++;
        intake.setClientTick(serverTick);
        budget.spend(TickBudget.Phase.REVISION_STAMPING);
        for (Observation observation : intake.drain()) {
            if (observation.conn() == null || !observation.conn().equals(session.connection())) {
                continue;
            }
            budget.spend(TickBudget.Phase.QUEUE_DRAIN);
            observationSink.accept(observation);
            applyObservation(observation);
            afterApply.accept(observation);
        }
        if (intake.overflowed()) {
            risk.suspend(SuspensionCause.QUEUE_OVERFLOW);
        }
        budget.spend(TickBudget.Phase.RECONCILIATION);
        scheduler.tick(serverTick);
        risk.tick(serverTick);
        maybeSendBarrierProbe(serverTick);
    }

    /**
     * Sends at most one BARRIER probe per tick, only after the tick's observations were applied and the
     * budget retired what its evidence allowed. The probe books one packet through the packet-only path (no
     * risk, debt, hazard, slot, cell or entity), so it can never displace debt or the engine's footprint.
     * Every failed attempt releases the lease; the debt stays until a later ack plus one full tick, a
     * timeout, respawn or connection close. No session, a disabled runtime, any suspension, a non-BARRIER
     * model, an in-flight probe or an in-progress break we started all keep it silent.
     */
    private void maybeSendBarrierProbe(int serverTick) {
        if (!enabled || closed) return;
        if (!session.isOpen() || !client.connectionOpen() || !sink.open()) return;
        if (risk.riskModel() != RiskModel.BARRIER) return;
        if (risk.suspended()) return;
        if (!risk.barrierProbeDue()) return;
        if (destructionCell != null) {
            // ABORT_DESTROY_BLOCK would cancel our own cleanup break. The probe waits; the 40-tick window
            // keeps running, so a long break can abandon the risk and keep its debt.
            return;
        }
        Ticket ticket = session.ticket(BARRIER_PROBE_PLAN);
        Intent probe = new Intent(BARRIER_PROBE_INTENT, BARRIER_PROBE_PLAN, Strategy.PROBE, Action.PROBE,
            null, null, Set.of(), Set.of(), Set.of(), serverTick + 1);
        if (!scheduler.reservePackets(probe, ticket, serverTick, 1, false)) return;
        DispatchResult result = dispatch(probe, ticket);
        if (result.sent()) {
            result.trace().packet(DispatchTrace.Stage.CAUSATIVE)
                .map(DispatchTrace.PacketProgress::ackSequence)
                .ifPresent(sequence -> risk.barrierProbeSent(sequence, serverTick,
                    intake.appliedRevision(), session.connection()));
        } else {
            // dispatch() released every path that owns the lease; this covers early rejections.
            scheduler.releaseIntent(BARRIER_PROBE_PLAN, BARRIER_PROBE_INTENT);
        }
    }

    /**
     * Installs the respawn reconciliation hook; {@code null} restores the no-op default. It runs on the
     * client thread inside the respawn transition, after the budget observed RESPAWN, and must not send.
     */
    public void setRespawnReconciler(Runnable reconciler) {
        this.respawnReconciler = reconciler == null ? () -> { } : reconciler;
    }

    /** Installs the passive dispatch observer; {@code null} restores the no-op default. */
    public void setDispatchObserver(Consumer<DispatchResult> observer) {
        this.dispatchObserver = observer == null ? result -> { } : observer;
    }

    Consumer<DispatchResult> dispatchObserver() {
        return dispatchObserver;
    }

    /** Installs the passive explosion observer; {@code null} restores the no-op default. */
    public void setExplosionObserver(Consumer<Vec> observer) {
        this.explosionObserver = observer == null ? position -> { } : observer;
    }

    Consumer<Vec> explosionObserver() {
        return explosionObserver;
    }

    Runnable respawnReconciler() {
        return respawnReconciler;
    }

    private void applyObservation(Observation observation) {
        ObservationKind kind = observation.kind();
        if (kind == ObservationKind.RESPAWN) {
            boolean changed = observation.payload() instanceof ObservationPayload.Respawn respawn
                && respawn.dimensionChanged();
            if (changed) {
                // A dimension change starts a new connection epoch and migrates sent self/friend debt.
                // Treating it as a respawn would clear migrated self debt.
                onDimensionChanged();
                return;
            }
            ActionGenerationHolder holder = beginGenerationTransition();
            session.respawn();
            finishGenerationTransition(holder);
            risk.observe(rebind(observation, session.connection()));
            // The suspended budget may clear only through reconciliation, and the ledger may rescan only
            // against a snapshot taken after the generation transitioned.
            respawnReconciler.run();
            return;
        }
        if (kind == ObservationKind.DEATH) {
            ActionGenerationHolder holder = beginGenerationTransition();
            session.death();
            finishGenerationTransition(holder);
            risk.observe(rebind(observation, session.connection()));
            return;
        }
        risk.observe(observation);
        if (kind == ObservationKind.EXPLOSION && observation.position() != null) {
            explosionObserver.accept(observation.position());
        }
    }

    private ActionGenerationHolder beginGenerationTransition() {
        return new ActionGenerationHolder(session.actionGeneration(), session.connection());
    }

    private void finishGenerationTransition(ActionGenerationHolder holder) {
        scheduler.invalidateGeneration(holder.generation());
        risk.actionGenerationEnded(holder.generation());
        releasePlanBookkeeping();
        contexts.clear();
    }

    /** Sent leases keep no scheduler resources after a lifecycle boundary; the budget keeps the debt. */
    private void releasePlanBookkeeping() {
        for (long plan : knownPlans) scheduler.release(plan);
        knownPlans.clear();
    }

    private static Observation rebind(Observation observation, ConnectionKey connection) {
        return new Observation(connection, observation.receiptSeq(), observation.appliedRevision(),
            observation.receiptNanos(), observation.clientTick(), observation.kind(),
            observation.entity(), observation.cell(), observation.position(), observation.bounds(),
            observation.payload());
    }

    /** Death: unsent work is invalidated, sent risk and crisis stay. */
    public void onDeath() {
        ActionGenerationHolder holder = beginGenerationTransition();
        session.death();
        finishGenerationTransition(holder);
        risk.suspend(SuspensionCause.DEATH);
        // The runtime death path does not fabricate a DEATH observation, so probe evidence is cleared
        // here. Sent debt stays; only the in-flight barrier claim is dropped.
        risk.clearProbeEvidence();
    }

    /** Respawn: self debt resets only through the observation path; friend debt and hazards stay. */
    public void onRespawn() {
        ActionGenerationHolder holder = beginGenerationTransition();
        session.respawn();
        finishGenerationTransition(holder);
        risk.observe(new Observation(holder.connection(), 0L, intake.appliedRevision(),
            System.nanoTime(), lastTickedTick, ObservationKind.RESPAWN, null, null, null, null,
            new ObservationPayload.Respawn("", false)));
        // Same order as the observation path: reconciliation after the budget observed RESPAWN.
        respawnReconciler.run();
    }

    /** Dimension change: new connection epoch, self and friend debt migrate, old hazards stop arming. */
    public ConnectionKey onDimensionChanged() {
        ActionGenerationHolder holder = beginGenerationTransition();
        ConnectionKey next = session.dimensionChanged();
        scheduler.invalidateGeneration(holder.generation());
        risk.actionGenerationEnded(holder.generation());
        risk.dimensionChanged(holder.connection(), next);
        risk.suspend(SuspensionCause.CONNECTION_CHANGE);
        releasePlanBookkeeping();
        contexts.clear();
        return next;
    }

    /** Connection close: that connection's leases, risk, pending pairs and receipts all clear. */
    public void onConnectionClosed() {
        forgetDestruction();
        ActionGenerationHolder holder = beginGenerationTransition();
        session.close();
        scheduler.invalidateGeneration(holder.generation());
        risk.actionGenerationEnded(holder.generation());
        risk.connectionClosed(holder.connection());
        intake.connectionClosed();
        releasePlanBookkeeping();
        contexts.clear();
    }

    /**
     * Cancels the vanilla block destruction this runtime started, and nothing else. This is the single
     * cancellation exception to the dispatch-only send rule: it hands the sink exactly one vanilla
     * STOP_DESTROY_BLOCK for a block this module began breaking, on the client thread, and only while the
     * session and sink are still open. It never retires risk, releases a lease or consumes packet budget.
     *
     * @return true when the stop packet was handed to the sink
     */
    public boolean stopDestruction() {
        if (!client.onClientThread()) return false;
        Cell cell = destructionCell;
        if (cell == null) return false;
        if (!session.isOpen() || !sink.open() || !client.connectionOpen()) return false;
        destructionCell = null;
        sink.stopBreak();
        return true;
    }

    /** Cancels the tracked destruction only when it is {@code cell}. */
    public boolean stopDestruction(Cell cell) {
        if (cell == null || !cell.equals(destructionCell)) return false;
        return stopDestruction();
    }

    /** Clears the tracked destruction without sending: connection-close paths. */
    public void forgetDestruction() {
        destructionCell = null;
    }

    /** Enables the BARRIER probe lifecycle; called by the controller after the module is enabled. */
    public void onEnabled() {
        this.enabled = true;
    }

    /** Whether the owning module is enabled. Probe sending is gated on this flag. */
    public boolean enabled() {
        return enabled;
    }

    /** Manual disable: stop sending immediately; risk and ledger stay observing. */
    public void onDisabled() {
        enabled = false;
        risk.clearProbeEvidence();
        ActionGenerationHolder holder = beginGenerationTransition();
        session.disable();
        finishGenerationTransition(holder);
    }

    /** Config change ends in-flight actions the same way a disable does. */
    public void onConfigChanged() {
        ActionGenerationHolder holder = beginGenerationTransition();
        session.reconfigure();
        finishGenerationTransition(holder);
    }

    /** Reconciliation: queued overflow and non-evidence suspensions may clear; unsent work stays locked. */
    public void onReconciled() {
        intake.clearOverflow();
        risk.resumeAfterReconciliation();
    }

    /** Marks matching packet progress as acknowledged; ack = -1 never matches. */
    public void onBlockChangedAck(int sequence) {
        for (DispatchTrace trace : traces) trace.acknowledge(sequence);
        // The probe is not a combat trace: its ack is the one terminal evidence the budget needs, and the
        // next tick (strictly later than the ack) decides whether to retire.
        risk.blockAck(sequence, lastServerTick);
    }

    public boolean closed() {
        return closed;
    }

    /** Number of distinct server ticks this runtime has advanced through. */
    public int tickCount() {
        return tickCount;
    }

    @Override
    public void close() {
        if (closed) return;
        closed = true;
        enabled = false;
        onConnectionClosed();
        contexts.clear();
    }

    private void release(Intent intent) {
        scheduler.releaseIntent(intent.plan(), intent.id());
        forget(PlanKey.of(intent));
    }

    private void forget(PlanKey key) {
        contexts.remove(key);
    }

    private void trimTraces() {
        while (traces.size() > MAX_TRACES) traces.removeFirst();
    }
}
