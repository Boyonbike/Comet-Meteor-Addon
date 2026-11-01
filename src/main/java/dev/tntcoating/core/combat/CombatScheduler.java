/*
 * Original Comet implementation by Boyonabike.
 * Independent: written from the pinned 26.2 artifact and Meteor build 35 behaviour; no third-party code.
 * SPDX-License-Identifier: CC0-1.0
 */

package dev.tntcoating.core.combat;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/**
 * Atomic reservation of one dispatch action plus its risk. Every path, including event-triggered cleanup
 * and defence actions, goes through the same {@link #reserve(Intent, Ticket, RiskRequest)}: there is no
 * bypass that can skip a lock, the packet budget or the risk budget. {@link #attach} is the one deliberate
 * exception: it must name an existing risk that already left (the crystal risk a break shares) and it still
 * acquires every lock and the packet budget atomically; it cannot create risk or skip the policy.
 *
 * <p>The scheduler owns the action's block cells, entity locks, one hotbar-slot token, one rotation token,
 * one mining-slot token and one rolling packet reservation. Acquisition is all-or-nothing: when any
 * component is refused, nothing is retained. Sent leases keep their block/entity footprint until the plan
 * is released so {@link #holder(Cell)} remains truthful, but their slot, rotation and mining tokens are
 * returned as soon as the action has sent (those resources are only needed to build and dispatch one
 * action). Packet capacity is acquired at the effective reservation tick and returned if the action never
 * sends; it is not re-acquired at the send tick.
 */
public final class CombatScheduler {
    private final CombatSession session;
    private final CombatRiskBudget risk;
    private final PacketLedger packets;
    private final EventListener riskMayHaveLeft = this::onRiskMayHaveLeft;

    private final Map<LeaseKey, Held> leases = new LinkedHashMap<>();
    private final Map<LeaseKey, RiskId> sentRisks = new HashMap<>();
    private final Map<Cell, LeaseKey> cellOwners = new HashMap<>();
    private final Map<EntityKey, LeaseKey> entityOwners = new HashMap<>();
    private final Set<LeaseKey> sentIntents = new LinkedHashSet<>();
    private LeaseKey slotOwner;
    private LeaseKey rotationOwner;
    private LeaseKey miningOwner;
    private int clock = -1;

    public CombatScheduler(CombatSession session, CombatRiskBudget risk, PacketLedger packets) {
        this.session = Objects.requireNonNull(session, "session");
        this.risk = Objects.requireNonNull(risk, "risk");
        this.packets = Objects.requireNonNull(packets, "packets");
    }

    /**
     * Advances the scheduler clock and releases unsent leases whose intent expired, together with their
     * risk (never transmitted) and packet capacity. Sent leases and sent risk are untouched.
     */
    public void tick(int serverTick) {
        if (serverTick < 0) return;
        clock = Math.max(clock, serverTick);
        for (Map.Entry<LeaseKey, Held> entry : new ArrayList<>(leases.entrySet())) {
            Held held = entry.getValue();
            if (!held.sent && clock >= held.intent.expiresTick()) {
                removeUnsent(entry.getKey(), held);
            }
        }
    }

    /**
     * Reserves cells, entities, slot, rotation, mining slot, packet capacity and risk atomically. Returns
     * {@code false} without leaving any acquisition behind when any part is refused.
     */
    public boolean reserve(Intent intent, Ticket ticket, RiskRequest request) {
        if (intent == null || ticket == null || request == null) return false;
        if (!session.isOpen() || !session.valid(ticket)) return false;
        if (ticket.plan() != intent.plan()) return false;
        if (request.tick() < 0 || request.packetCost() < 0) return false;
        if (request.safety() == null
            || !ticket.conn().equals(request.safety().conn())
            || !ticket.gen().equals(request.safety().gen())) {
            return false;
        }
        clock = Math.max(clock, request.tick());
        if (clock >= intent.expiresTick()) return false;
        if (risk.suspended()) return false;

        LeaseKey key = new LeaseKey(intent.plan(), intent.id());
        if (leases.containsKey(key)) return false;
        for (Cell cell : intent.blockLocks()) {
            if (cellOwners.containsKey(cell)) return false;
        }
        for (EntityKey entity : intent.entityLocks()) {
            if (entityOwners.containsKey(entity)) return false;
        }
        if (request.needsSlot() && slotOwner != null) return false;
        if (request.needsRotation() && rotationOwner != null) return false;
        if (request.needsMiningSlot() && miningOwner != null) return false;

        int at = clock;
        if (!packets.tryAcquire(at, request.packetCost())) return false;
        Optional<RiskId> reserved = risk.reserve(intent.id(), request.upper(), request.arrival(),
            request.safety(), request.boundCrystal(), request.boundPosition());
        if (reserved.isEmpty()) {
            packets.release(at, request.packetCost());
            return false;
        }

        Held held = new Held(intent, ticket, reserved.get(), at, request.packetCost(),
            request.needsSlot(), request.needsRotation(), request.needsMiningSlot(),
            request.footprint());
        leases.put(key, held);
        for (Cell cell : intent.blockLocks()) cellOwners.put(cell, key);
        for (EntityKey entity : intent.entityLocks()) entityOwners.put(entity, key);
        if (request.needsSlot()) slotOwner = key;
        if (request.needsRotation()) rotationOwner = key;
        if (request.needsMiningSlot()) miningOwner = key;
        return true;
    }

    /**
     * Attaches one action to an existing sent risk instead of reserving a new one. Used when a break shares
     * the crystal risk its placement already reserved: the packet, locks and capacity are still acquired
     * atomically, but the risk state and its components are untouched. The risk must exist and have been
     * marked may-have-left (SENT); a still-reserved risk is refused so the placement's single send is never
     * conflated with an attack that has not happened yet.
     *
     * @return true when the lease now owns the packet capacity, locks and the existing risk
     */
    public boolean attach(Intent intent, Ticket ticket, RiskId riskId, int packetCost, int tick,
                          boolean needsSlot, boolean needsRotation, boolean needsMiningSlot,
                          Footprint footprint) {
        if (intent == null || ticket == null || riskId == null) return false;
        if (!session.isOpen() || !session.valid(ticket)) return false;
        if (ticket.plan() != intent.plan()) return false;
        if (tick < 0 || packetCost < 0) return false;
        clock = Math.max(clock, tick);
        if (clock >= intent.expiresTick()) return false;
        if (risk.suspended()) return false;
        if (risk.state(riskId) != CombatRiskBudget.State.SENT) return false;

        LeaseKey key = new LeaseKey(intent.plan(), intent.id());
        if (leases.containsKey(key)) return false;
        for (Cell cell : intent.blockLocks()) {
            if (cellOwners.containsKey(cell)) return false;
        }
        for (EntityKey entity : intent.entityLocks()) {
            if (entityOwners.containsKey(entity)) return false;
        }
        if (needsSlot && slotOwner != null) return false;
        if (needsRotation && rotationOwner != null) return false;
        if (needsMiningSlot && miningOwner != null) return false;

        int at = clock;
        if (!packets.tryAcquire(at, packetCost)) return false;

        Held held = new Held(intent, ticket, riskId, at, packetCost, needsSlot, needsRotation,
            needsMiningSlot, footprint);
        leases.put(key, held);
        for (Cell cell : intent.blockLocks()) cellOwners.put(cell, key);
        for (EntityKey entity : intent.entityLocks()) entityOwners.put(entity, key);
        if (needsSlot) slotOwner = key;
        if (needsRotation) rotationOwner = key;
        if (needsMiningSlot) miningOwner = key;
        return true;
    }

    /**
     * Drops the physical footprint of an already-sent lease. The risk (debt) and the sent-intent record stay
     * intact; this exists so a second verified break on the same crystal can be reserved within the
     * per-crystal attempt cap instead of being blocked by the first attack's entity lock.
     *
     * @return true when a sent lease was released; false for unknown or still-unsent leases
     */
    public boolean releaseSent(long plan, long intentId) {
        LeaseKey key = new LeaseKey(plan, intentId);
        Held held = leases.get(key);
        if (held == null || !held.sent) return false;
        leases.remove(key);
        cellOwners.entrySet().removeIf(entry -> entry.getValue().equals(key));
        entityOwners.entrySet().removeIf(entry -> entry.getValue().equals(key));
        if (key.equals(slotOwner)) slotOwner = null;
        if (key.equals(rotationOwner)) rotationOwner = null;
        if (key.equals(miningOwner)) miningOwner = null;
        return true;
    }

    /**
     * Whether this held intent may dispatch now: lease current, ticket and generation valid, not expired,
     * risk still Reserved or already Sent (attached to an existing crystal risk), every dependency already
     * sent, and the snapshot belongs to this connection.
     */
    public boolean ready(Intent intent, Snapshot snapshot) {
        if (intent == null || snapshot == null) return false;
        LeaseKey key = new LeaseKey(intent.plan(), intent.id());
        Held held = leases.get(key);
        if (held == null || held.sent) return false;
        // Expiry and dependencies are properties of the held lease, not of the caller's Intent: a caller
        // that supplies a different Intent with the same (plan, id) must not be able to extend the lease.
        if (clock >= held.intent.expiresTick()) return false;
        if (!session.isOpen() || !session.valid(held.ticket)) return false;
        if (!held.ticket.conn().equals(snapshot.conn())) return false;
        CombatRiskBudget.State riskState = risk.state(held.riskId);
        if (riskState != CombatRiskBudget.State.RESERVED
            && riskState != CombatRiskBudget.State.SENT) {
            return false;
        }
        if (risk.suspended()) return false;
        for (Intent dependency : held.intent.dependsOn()) {
            if (dependency == null) return false;
            if (!sentIntents.contains(new LeaseKey(dependency.plan(), dependency.id()))) return false;
        }
        return true;
    }

    /**
     * Records that the causative packets may have left. The risk becomes irreversible debt; transient slot,
     * rotation and mining tokens are returned, while the physical footprint stays until plan release.
     */
    public void sent(Intent intent, int tick) {
        sent(intent, tick, 0L);
    }

    /**
     * Records that the causative packets may have left, together with the applied-state revision the
     * dispatch decision was based on. That revision becomes the low-water mark: health evidence applied at
     * or before it can never retire the risk (it predates the crystal placement).
     */
    public void sent(Intent intent, int tick, long appliedRevision) {
        Objects.requireNonNull(intent, "intent");
        if (tick < 0) throw new IllegalArgumentException("tick must be non-negative");
        LeaseKey key = new LeaseKey(intent.plan(), intent.id());
        Held held = leases.get(key);
        if (held == null) throw new IllegalStateException("no lease for sent intent " + key);
        clock = Math.max(clock, tick);
        risk.mayHaveLeft(held.riskId, appliedRevision);
        sentIntents.add(key);
        sentRisks.put(key, held.riskId);
        if (!held.sent) {
            held.sent = true;
            releaseTransientResources(key);
        }
    }

    /**
     * Moves one unsent lease's packet reservation from its reservation tick to the tick the action is now
     * expected to send on. The rolling window is counted per tick, so an action that could not dispatch in
     * its reservation tick must re-acquire capacity in the send tick instead of being charged to the old
     * tick, where it could silently fall out of the window. A refused re-acquisition releases the unsent
     * lease, its risk and its scheduling resources.
     *
     * @return true when the lease now holds capacity for {@code tick}
     */
    public boolean reacquirePackets(long plan, long intentId, int tick) {
        if (tick < 0) return false;
        LeaseKey key = new LeaseKey(plan, intentId);
        Held held = leases.get(key);
        if (held == null || held.sent) return false;
        if (held.reserveTick == tick) return true;
        // The send is late: move the whole reservation into the current bookkeeping tick instead of
        // double counting it. If the window has no room the intent is proven untransmitted and dies.
        int previous = held.reserveTick;
        packets.release(previous, held.packetCost);
        if (!packets.tryAcquire(tick, held.packetCost)) {
            removeUnsent(key, held);
            return false;
        }
        held.reserveTick = tick;
        return true;
    }

    /**
     * Whether one held intent's risk may enter Dispatching. Returns false when the lease is missing or
     * already sent; otherwise moves Reserved to Dispatching and reports whether the risk is still held.
     */
    public boolean dispatchStarted(long plan, long intentId) {
        Held held = leases.get(new LeaseKey(plan, intentId));
        if (held == null || held.sent) return false;
        CombatRiskBudget.State state = risk.state(held.riskId);
        if (state == CombatRiskBudget.State.RESERVED) risk.dispatching(held.riskId);
        CombatRiskBudget.State current = risk.state(held.riskId);
        return current == CombatRiskBudget.State.DISPATCHING
            || current == CombatRiskBudget.State.SENT;
    }

    /** The risk reservation held by this intent, for dispatch revalidation. */
    public Optional<RiskId> riskId(long plan, long intentId) {
        LeaseKey key = new LeaseKey(plan, intentId);
        Held held = leases.get(key);
        if (held != null) return Optional.of(held.riskId);
        return Optional.ofNullable(sentRisks.get(key));
    }

    /**
     * Releases one unsent lease after a failed pre-causative dispatch step: its risk was never transmitted,
     * so it is retired through the budget's not-transmitted path and its packet capacity is returned.
     *
     * @return true when an unsent lease was released
     */
    public boolean releaseIntent(long plan, long intentId) {
        LeaseKey key = new LeaseKey(plan, intentId);
        Held held = leases.get(key);
        if (held == null || held.sent) return false;
        removeUnsent(key, held);
        return true;
    }

    /**
     * Drops every unsent lease belonging to an ended action generation, freeing its risk and resources.
     * Sent leases are kept because their risk is already debt.
     */
    public void invalidateGeneration(ActionGeneration generation) {
        if (generation == null) return;
        sentRisks.clear();
        for (Map.Entry<LeaseKey, Held> entry : new ArrayList<>(leases.entrySet())) {
            Held held = entry.getValue();
            if (held.sent || !held.ticket.gen().equals(generation)) continue;
            removeUnsent(entry.getKey(), held);
        }
    }

    /**
     * Drops every unsent lease for this ticket, releasing its risk through {@code notTransmitted}, packet
     * capacity and scheduling resources. Sent leases are kept because their risk is already debt.
     */
    public void invalidate(Ticket ticket) {
        if (ticket == null) return;
        sentRisks.entrySet().removeIf(entry -> leases.get(entry.getKey()) == null
            || !leases.get(entry.getKey()).ticket.equals(ticket));
        for (Map.Entry<LeaseKey, Held> entry : new ArrayList<>(leases.entrySet())) {
            Held held = entry.getValue();
            if (!held.ticket.equals(ticket) || held.sent) continue;
            removeUnsent(entry.getKey(), held);
        }
    }

    /**
     * Releases one plan's remaining scheduling resources. Unsent leases also release their risk and packet
     * capacity; sent leases keep the risk as debt but stop holding cells/entities.
     */
    public void release(long plan) {
        for (Map.Entry<LeaseKey, Held> entry : new ArrayList<>(leases.entrySet())) {
            LeaseKey key = entry.getKey();
            if (key.plan() != plan) continue;
            Held held = entry.getValue();
            if (!held.sent) {
                risk.notTransmitted(held.riskId);
                packets.release(held.reserveTick, held.packetCost);
            }
            releaseAllResources(key);
            leases.remove(key);
            sentIntents.remove(key);
            sentRisks.remove(key);
        }
    }

    /**
     * The lease currently holding {@code cell}, if any (reserved or sent). {@link Intent#blockLocks()} is
     * the authoritative exclusive cell set; swept and blast footprint cells are advisory scan data and are
     * deliberately not reported here, so callers must not treat {@link Lease#footprint()} as a lock.
     */
    public Optional<Lease> holder(Cell cell) {
        if (cell == null) return Optional.empty();
        LeaseKey key = cellOwners.get(cell);
        if (key == null) return Optional.empty();
        Held held = leases.get(key);
        if (held == null) {
            cellOwners.remove(cell);
            return Optional.empty();
        }
        return Optional.of(held.lease());
    }

    /** Lease view for tests and planner inspection; empty when no such intent is held. */
    public Optional<Lease> lease(long plan, long intentId) {
        Held held = leases.get(new LeaseKey(plan, intentId));
        return held == null ? Optional.empty() : Optional.of(held.lease());
    }

    public int leaseCount() {
        return leases.size();
    }

    public Set<Cell> heldCells() {
        return Set.copyOf(cellOwners.keySet());
    }

    public Set<EntityKey> heldEntities() {
        return Set.copyOf(entityOwners.keySet());
    }

    public boolean hotbarSlotHeld() {
        return slotOwner != null;
    }

    public boolean rotationHeld() {
        return rotationOwner != null;
    }

    public boolean miningSlotHeld() {
        return miningOwner != null;
    }

    public int packetCapacity() {
        return packets.capacity();
    }

    public int packetUsed(int tick) {
        return packets.used(tick);
    }

    /**
     * Notification hook the runtime arms on its dead-man switch: when the server tick advances, the risk
     * may be marked may-have-left. Kept package-private so only the runtime can arm it.
     */
    EventListener riskMayHaveLeft() {
        return riskMayHaveLeft;
    }

    private void onRiskMayHaveLeft(long plan, long intentId, int tick, long appliedRevision) {
        Held held = leases.get(new LeaseKey(plan, intentId));
        if (held == null || held.sent) return;
        sent(held.intent, tick, appliedRevision);
    }

    private void removeUnsent(LeaseKey key, Held held) {
        risk.notTransmitted(held.riskId);
        packets.release(held.reserveTick, held.packetCost);
        releaseAllResources(key);
        leases.remove(key);
        sentIntents.remove(key);
    }

    private void releaseTransientResources(LeaseKey key) {
        if (key.equals(slotOwner)) slotOwner = null;
        if (key.equals(rotationOwner)) rotationOwner = null;
        if (key.equals(miningOwner)) miningOwner = null;
    }

    private void releaseAllResources(LeaseKey key) {
        cellOwners.values().removeIf(owner -> owner.equals(key));
        entityOwners.values().removeIf(owner -> owner.equals(key));
        releaseTransientResources(key);
    }

    /**
     * Armed dead-man switch callback: only the runtime may invoke it, from its once-per-server-tick hook.
     */
    @FunctionalInterface
    public interface EventListener {
        void onServerTickAdvanced(long plan, long intentId, int tick, long appliedRevision);
    }

    private record LeaseKey(long plan, long intentId) {
    }

    private static final class Held {
        final Intent intent;
        final Ticket ticket;
        final RiskId riskId;
        final int packetCost;
        final boolean needsSlot;
        final boolean needsRotation;
        final boolean needsMiningSlot;
        final Footprint footprint;
        int reserveTick;
        boolean sent;

        Held(Intent intent, Ticket ticket, RiskId riskId, int reserveTick, int packetCost,
             boolean needsSlot, boolean needsRotation, boolean needsMiningSlot,
             Footprint footprint) {
            this.intent = intent;
            this.ticket = ticket;
            this.riskId = riskId;
            this.reserveTick = reserveTick;
            this.packetCost = packetCost;
            this.needsSlot = needsSlot;
            this.needsRotation = needsRotation;
            this.needsMiningSlot = needsMiningSlot;
            this.footprint = footprint;
        }

        Lease lease() {
            Footprint planned = footprint != null ? footprint
                : new Footprint(intent.blockLocks(), null, null, intent.entityLocks());
            return new Lease(intent.plan(), intent.strategy(), intent.expiresTick(), planned,
                ticket, intent, sent);
        }
    }
}
