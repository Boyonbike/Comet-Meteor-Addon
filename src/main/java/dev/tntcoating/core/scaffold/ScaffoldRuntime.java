package dev.tntcoating.core.scaffold;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.LongSupplier;

/** Pure connection-scoped placement accounting. All public operations are serialized so an
 * actual packet replaces its reservation atomically. Feed every connection Sent event, even
 * while inactive. Only authoritative server block updates may clear prediction quarantine.
 * The adapter separately enforces at most one interaction per client tick and generation checks.
 */
public final class ScaffoldRuntime {
    public enum PacketKind { USE_ITEM, SWING, SLOT, MOVEMENT, OTHER }
    public enum EventType { RESERVED, DISPATCH_STARTED, PACKET, SENT, CANCELED, OCCUPANCY_USABLE,
        REJECTED, TIMEOUT, CORRECTION_LOSS, CORRECTION, ROLLBACK, QUARANTINED, CAPACITY, WORLD_RESET }
    public record Attempt(long id, long position, boolean urgent, long generation) {}
    public record Event(long timeNanos, EventType type, long attemptId, long position, String detail) {}
    public record Stats(int actualPackets, int reservedPackets, int pending, int quarantined, int trackedPositions,
        long sent, long confirmed, long failures, long correctionLosses, long rollbacks, long timeouts,
        long retries, long cancellations, long droppedEvents, double latencyMillis, long timeoutNanos,
        long nextEligibleNanos) {}

    private static final int MAX_PENDING = 6, MAX_POSITIONS = 256, MAX_EVENTS = 512;
    private static final long UNSENT_TTL = 100_000_000L, WATCH = 10_000_000_000L, EVENT_TTL = 2_000_000_000L;
    private enum Phase { RESERVED, DISPATCHING, SENT }
    private static final class Work {
        final Attempt attempt;
        final long reservedAt;
        Phase phase = Phase.RESERVED;
        int credits;
        long sentAt;
        Work(Attempt attempt, long reservedAt, int credits) { this.attempt = attempt; this.reservedAt = reservedAt; this.credits = credits; }
    }
    private static final class Position {
        Work work;
        boolean quarantine, ownedPrediction, failureRecorded, urgent, watching, retrying;
        int failureStreak;
        long retryAt, watchAt;
    }
    private final LongSupplier clock;
    private final ScaffoldPacketWindow packets;
    private final ScaffoldPacer pacer;
    private final Map<Long, Position> positions = new LinkedHashMap<>();
    private final ArrayDeque<Event> events = new ArrayDeque<>();
    private long nextId, sent, confirmed, failures, correctionLosses, rollbacks, timeouts, retries, cancellations, droppedEvents;

    public ScaffoldRuntime(LongSupplier clock) {
        this.clock = Objects.requireNonNull(clock);
        packets = new ScaffoldPacketWindow(clock); pacer = new ScaffoldPacer(clock);
    }
    public synchronized void setPacketCap(int cap) { packets.setCap(cap); }
    public synchronized void setMinIntervalNanos(long nanos) { pacer.setMinIntervalNanos(nanos); }

    /** Returns null for pacing, capacity, per-position backoff, pending or packet-budget rejection. */
    public synchronized Attempt reserve(long position, boolean urgent, long generation, int estimatedPackets) {
        if (estimatedPackets < 1) throw new IllegalArgumentException("estimate must be positive");
        maintain();
        Position p = positions.get(position);
        if (!pacer.ready() || countPending() >= MAX_PENDING || p != null && (p.work != null || !retryReady(p))) return null;
        if (packets.remaining() < estimatedPackets) return null;
        if (p == null) { p = allocate(position); if (p == null) return null; }
        if (!packets.reserve(estimatedPackets)) return null;
        Attempt attempt = new Attempt(++nextId, position, urgent, generation);
        p.work = new Work(attempt, clock.getAsLong(), estimatedPackets);
        p.urgent = urgent; p.failureRecorded = false;
        if (p.failureStreak > 0) retries++;
        event(EventType.RESERVED, attempt, "estimate=" + estimatedPackets);
        return attempt;
    }
    /** Rechecks the global budget after intervening external traffic; marks potential prediction. */
    public synchronized boolean beginDispatch(Attempt attempt) {
        maintain(); Work w = current(attempt);
        if (w == null || w.phase != Phase.RESERVED || !pacer.ready() || !packets.withinCap()) return false;
        Position p = positions.get(attempt.position());
        p.quarantine = true; p.ownedPrediction = true;
        w.phase = Phase.DISPATCHING; pacer.dispatched();
        event(EventType.DISPATCH_STARTED, attempt, "prediction quarantined");
        return true;
    }
    /** Must be called after an actual connection send, never an attempted or canceled Send event. */
    public synchronized void observePacket(Attempt ownerOrNull, PacketKind kind) {
        Objects.requireNonNull(kind);
        maintain(); Work w = current(ownerOrNull);
        boolean attributed = w != null && w.phase != Phase.RESERVED;
        boolean consumes = attributed && w.credits > 0;
        packets.observe(consumes);
        if (consumes) w.credits--;
        event(EventType.PACKET, attributed ? ownerOrNull : null, kind.name());
        if (attributed && kind == PacketKind.USE_ITEM && w.phase == Phase.DISPATCHING) {
            w.phase = Phase.SENT; w.sentAt = clock.getAsLong(); sent++;
            event(EventType.SENT, w.attempt, "actual use-item packet");
        }
    }
    /** Releases the unused estimate; absent actual use-item send cancels but preserves quarantine. */
    public synchronized void finishDispatch(Attempt attempt) {
        Work w = current(attempt); if (w == null) return;
        refund(w);
        if (w.phase != Phase.SENT) cancelWork(positions.get(attempt.position()), "no actual use-item send");
    }
    /** Cancellation of a sent attempt refunds unused credits but never erases its pending result. */
    public synchronized void cancel(Attempt attempt, String reason) {
        Work w = current(attempt); if (w == null) return;
        refund(w);
        if (w.phase != Phase.SENT) cancelWork(positions.get(attempt.position()), Objects.requireNonNullElse(reason, "canceled"));
    }
    public synchronized void authoritative(long position, boolean usableSupport, boolean replaceable) {
        maintain();
        Position p = positions.get(position);
        Work w = p == null ? null : p.work;
        Attempt attempt = w == null ? null : w.attempt;
        if (usableSupport) {
            event(EventType.OCCUPANCY_USABLE, attempt, position, "observed usable occupancy");
            if (p == null) return;
            // Timeout/correction remove Work but preserve owned unresolved prediction.
            // Watch the newly observed occupancy without treating a late result as an acknowledgment.
            boolean resolvesOwnedPrediction = p.quarantine && p.ownedPrediction;
            p.quarantine = false;
            if (w != null && w.phase == Phase.SENT) {
                pacer.confirmed(clock.getAsLong() - w.sentAt); confirmed++;
            }
            if (resolvesOwnedPrediction) {
                p.watching = true; p.watchAt = clock.getAsLong();
            }
            if (w != null) { refund(w); p.work = null; }
            p.failureStreak = 0; p.retrying = false; p.ownedPrediction = false; p.failureRecorded = false;
        } else if (p != null) {
            boolean relevant = w != null || p.quarantine || p.watching;
            p.quarantine = false;
            if (p.watching) {
                rollbacks++; p.watching = false;
                event(EventType.ROLLBACK, attempt, position, "watched occupancy no longer usable");
                fail(p);
            } else if (relevant && (w != null && w.phase != Phase.RESERVED || p.ownedPrediction) && !p.failureRecorded) {
                fail(p);
            }
            if (w != null) { refund(w); p.work = null; }
            p.ownedPrediction = false;
            if (relevant) event(EventType.REJECTED, attempt, position, replaceable ? "observed replaceable" : "observed nonusable occupancy");
        }
    }
    public synchronized void maintain() {
        long now = clock.getAsLong();
        trimEvents(now);
        for (Map.Entry<Long, Position> entry : positions.entrySet()) {
            Position p = entry.getValue(); Work w = p.work;
            if (p.watching && now - p.watchAt >= WATCH) p.watching = false;
            if (w == null) continue;
            if (w.phase == Phase.SENT && now - w.sentAt >= pacer.timeoutNanos()) {
                timeouts++; event(EventType.TIMEOUT, w.attempt, "authoritative occupancy timeout");
                fail(p); refund(w); p.work = null;
            } else if (w.phase != Phase.SENT && now - w.reservedAt >= UNSENT_TTL) {
                cancelWork(p, "unsent reservation expired");
            }
        }
    }
    /** Disable/preset/handoff operation: sent work and all quarantine remain alive. */
    public synchronized void invalidateUnsent(String reason) {
        maintain();
        for (Position p : positions.values()) if (p.work != null && p.work.phase != Phase.SENT) cancelWork(p, reason);
    }
    public synchronized void correction() {
        // Classify still-tracked sent work as correction loss before ordinary timeout maintenance.
        for (Position p : positions.values()) {
            Work w = p.work;
            if (w == null) continue;
            if (w.phase == Phase.SENT) {
                correctionLosses++; p.failureRecorded = true;
                event(EventType.CORRECTION_LOSS, w.attempt, "correction invalidated sent attempt");
                refund(w); p.work = null;
            } else cancelWork(p, "correction");
        }
        pacer.correction(); event(EventType.CORRECTION, null, "pause 500ms");
    }
    /** Disconnect/new-world lifecycle reset. Connection traffic remains counted across world changes. */
    public synchronized void resetWorld() {
        for (Position p : positions.values()) if (p.work != null) refund(p.work);
        positions.clear(); pacer.reset(); events.clear();
        sent = confirmed = failures = correctionLosses = rollbacks = timeouts = retries = cancellations = droppedEvents = 0;
        event(EventType.WORLD_RESET, null, "world lifecycle reset");
    }
    /** Imports another builder's possible local prediction without silently evicting quarantine. */
    public synchronized boolean markQuarantined(long position) {
        Position p = positions.get(position);
        if (p == null) { p = allocate(position); if (p == null) return false; }
        p.quarantine = true; event(EventType.QUARANTINED, null, position, "external prediction"); return true;
    }
    public synchronized boolean quarantine(long position) { return knownUnconfirmed(position); }
    public synchronized boolean knownUnconfirmed(long position) {
        Position p = positions.get(position); return p != null && p.quarantine;
    }
    public synchronized boolean pending(long position) {
        maintain(); Position p = positions.get(position); return p != null && p.work != null;
    }
    public synchronized boolean retryReady(long position) {
        maintain(); Position p = positions.get(position); return p == null || p.work == null && retryReady(p);
    }
    public synchronized int pendingCount() { maintain(); return countPending(); }
    public synchronized int remainingPacketBudget() { maintain(); return packets.remaining(); }
    public synchronized int actualPackets() { return packets.actual(); }
    public synchronized int reservedPackets() { maintain(); return packets.reserved(); }
    public synchronized int trackedPositions() { return positions.size(); }
    public synchronized long nextEligibleNanos() { return pacer.nextEligibleNanos(); }
    public synchronized long timeoutNanos() { return pacer.timeoutNanos(); }
    public synchronized Stats stats() {
        maintain();
        int quarantine = 0; for (Position p : positions.values()) if (p.quarantine) quarantine++;
        return new Stats(packets.actual(), packets.reserved(), countPending(), quarantine, positions.size(), sent,
            confirmed, failures, correctionLosses, rollbacks, timeouts, retries, cancellations, droppedEvents,
            pacer.latencyMillis(), pacer.timeoutNanos(), pacer.nextEligibleNanos());
    }
    public synchronized List<Event> drainEvents() {
        trimEvents(clock.getAsLong()); List<Event> drained = new ArrayList<>(events); events.clear(); return List.copyOf(drained);
    }
    private Work current(Attempt attempt) {
        if (attempt == null) return null;
        Position p = positions.get(attempt.position());
        return p != null && p.work != null && p.work.attempt.equals(attempt) ? p.work : null;
    }
    private int countPending() { int n = 0; for (Position p : positions.values()) if (p.work != null) n++; return n; }
    private boolean retryReady(Position p) { return !p.retrying || clock.getAsLong() - p.retryAt >= 0; }
    private Position allocate(long position) {
        if (positions.size() == MAX_POSITIONS) {
            Iterator<Position> it = positions.values().iterator();
            while (it.hasNext()) {
                Position p = it.next();
                if (!p.quarantine && p.work == null && !p.watching && retryReady(p)) { it.remove(); break; }
            }
            if (positions.size() == MAX_POSITIONS) { event(EventType.CAPACITY, null, position, "tracked state full"); return null; }
        }
        Position p = new Position(); positions.put(position, p); return p;
    }
    private void refund(Work w) { packets.release(w.credits); w.credits = 0; }
    private void cancelWork(Position p, String reason) {
        Work w = p.work; refund(w); p.work = null; cancellations++;
        event(EventType.CANCELED, w.attempt, Objects.requireNonNullElse(reason, "canceled"));
    }
    private void fail(Position p) {
        if (p.failureRecorded) return;
        p.failureRecorded = true; failures++;
        if (p.failureStreak < Integer.MAX_VALUE) p.failureStreak++;
        p.retrying = true; p.retryAt = clock.getAsLong() + ScaffoldPacer.retryDelayNanos(p.urgent, p.failureStreak);
    }
    private void event(EventType type, Attempt attempt, String detail) {
        event(type, attempt, attempt == null ? 0 : attempt.position(), detail);
    }
    private void event(EventType type, Attempt attempt, long position, String detail) {
        long now = clock.getAsLong(); trimEvents(now);
        if (events.size() == MAX_EVENTS) { events.removeFirst(); droppedEvents++; }
        events.addLast(new Event(now, type, attempt == null ? 0 : attempt.id(), position, detail));
    }
    private void trimEvents(long now) {
        while (!events.isEmpty() && now - events.peekFirst().timeNanos() >= EVENT_TTL) events.removeFirst();
    }
}
