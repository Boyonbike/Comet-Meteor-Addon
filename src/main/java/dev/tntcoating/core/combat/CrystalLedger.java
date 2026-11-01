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
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * Crystal lifecycle, spawn hazards and quarantine. Pure: it observes immutable records and never acts.
 *
 * <p>A sent spawn hazard binds only when exactly one new end crystal appears inside its source box, is not
 * in the baseline, and appears no earlier than the send tick. Two plausible candidates quarantine the
 * region instead of guessing. Tracking removal is not removal; timeouts, ticket invalidation and death
 * never retire a sent hazard or erase its evidence.</p>
 *
 * <p>Block ownership is weaker than ownership in general: a matching block update after our send makes a
 * cell {@link Ownership#INFERRED_OURS}; any update that could also be explained by an earlier send or
 * another actor makes it {@link Ownership#CONTESTED}. {@link Ownership#CONFIRMED_OURS} needs causal
 * evidence from a cooperating server and is never produced from observation alone.</p>
 *
 * <p>Client-thread use only. Observations carry receipt order; duplicates are ignored and the dedupe window
 * is bounded. Evicting a key from that window is not observation loss: real loss is reported through
 * {@link #observationsLost(ConnectionKey)}, which sets {@link #stale()} until {@link #reconcile(Snapshot)}
 * supplies a fresh applied snapshot.</p>
 *
 * <p>Every query here reports a fact, never a risk decision. In particular {@link #unresolved(HazardId)},
 * {@link #noLongerExists(EntityKey)} and {@link #explosionObserved(EntityKey)} are not risk-retirement
 * evidence; the Phase 2 risk budget applies its own rule.</p>
 */
public final class CrystalLedger {
    public static final int DEFAULT_OBSERVATION_CAPACITY = 4_096;
    private static final String END_CRYSTAL = "minecraft:end_crystal";
    /**
     * How far an explosion centre may be from a crystal's last known position and still be attributed to
     * that crystal. Pushed positions are Phase 5's job (audit C21: computed position with tolerance); this
     * ledger associates only by proximity to the last observation it actually received.
     */
    private static final double EXPLOSION_ASSOCIATION_RANGE = 1.25;

    private record ReceiptKey(ConnectionKey conn, long receiptSeq) {}

    private static final class Hazard {
        private final HazardId id;
        private final ConnectionKey conn;
        private final Box source;
        private final Box swept;
        private final Set<EntityKey> baseline;
        private Integer sentTick;
        private EntityKey bound;
        private boolean quarantined;
        private boolean rescanNeeded;
        private boolean terminal;

        private Hazard(HazardId id, ConnectionKey conn, Box source, Box swept, Set<EntityKey> baseline) {
            this.id = id;
            this.conn = conn;
            this.source = source;
            this.swept = swept;
            this.baseline = Set.copyOf(baseline);
        }
    }

    private static final class CrystalRecord {
        private final EntityKey entity;
        private Vec position;
        private CrystalState state = CrystalState.OBSERVED;
        private HazardId hazard;
        private boolean exploded;

        private CrystalRecord(EntityKey entity, Vec position) {
            this.entity = entity;
            this.position = position;
        }
    }

    private static final class Placement {
        private final PlacementId id;
        private final Cell cell;
        private final String expectedBlock;
        private Integer sentTick;

        private Placement(PlacementId id, Cell cell, String expectedBlock) {
            this.id = id;
            this.cell = cell;
            this.expectedBlock = expectedBlock;
        }
    }

    private static final class ConnectionState {
        private final Map<HazardId, Hazard> hazards = new LinkedHashMap<>();
        private final Map<EntityKey, CrystalRecord> crystals = new LinkedHashMap<>();
        private final List<Box> quarantines = new ArrayList<>();
        private final Map<PlacementId, Placement> placements = new LinkedHashMap<>();
        private final Map<Cell, Ownership> ownership = new HashMap<>();
    }

    private final int observationCapacity;
    private final LinkedHashSet<ReceiptKey> receipts = new LinkedHashSet<>();
    private final Map<ConnectionKey, ConnectionState> connections = new HashMap<>();
    private boolean stale;
    private long nextHazard = 1L;
    private long nextPlacement = 1L;

    public CrystalLedger() {
        this(DEFAULT_OBSERVATION_CAPACITY);
    }

    public CrystalLedger(int observationCapacity) {
        if (observationCapacity <= 0) throw new IllegalArgumentException("capacity must be positive");
        this.observationCapacity = observationCapacity;
    }

    // ------------------------------------------------------------------ hazards

    /** Reserves a crystal spawn before the placement is sent. */
    public HazardId reserveSpawn(ConnectionKey conn, Box source, Box swept, Set<EntityKey> baseline) {
        Objects.requireNonNull(conn, "conn");
        Objects.requireNonNull(source, "source");
        Objects.requireNonNull(swept, "swept");
        Hazard hazard = new Hazard(new HazardId(nextHazard++), conn, source, swept,
            baseline == null ? Set.of() : baseline);
        state(conn).hazards.put(hazard.id, hazard);
        return hazard.id;
    }

    /** Releases a reservation that was never sent. A sent hazard is never released by this call. */
    public void cancelUnsent(HazardId hazardId) {
        Hazard hazard = hazard(hazardId);
        if (hazard == null || hazard.sentTick != null || hazard.bound != null || hazard.quarantined) return;
        state(hazard.conn).hazards.remove(hazardId);
    }

    /** Records that the placement packet may have left. Repeated calls keep the earliest send tick. */
    public void markSent(HazardId hazardId, int tick) {
        if (tick < 0) throw new IllegalArgumentException("tick must be non-negative");
        Hazard hazard = requireHazard(hazardId);
        if (hazard.sentTick == null && !hazard.terminal) hazard.sentTick = tick;
    }

    public Optional<EntityKey> bound(HazardId hazardId) {
        Hazard hazard = hazard(hazardId);
        return hazard == null ? Optional.empty() : Optional.ofNullable(hazard.bound);
    }

    /**
     * True while the crystal is bound to one of our sent spawn hazards. This is the ledger's own-crystal
     * fact for the own-only break option; it is not block ownership and never changes by itself.
     */
    public boolean own(EntityKey crystal) {
        if (crystal == null) return false;
        ConnectionState state = connections.get(crystal.conn());
        if (state == null) return false;
        for (Hazard hazard : state.hazards.values()) {
            if (crystal.equals(hazard.bound) && hazard.sentTick != null && !hazard.quarantined) return true;
        }
        return false;
    }

    /**
     * True while the hazard still needs accounting: it is quarantined or has no terminal evidence yet.
     * This is a ledger fact, not risk-retirement evidence; the Phase 2 risk budget decides retirement.
     */
    public boolean unresolved(HazardId hazardId) {
        return unresolved(hazard(hazardId));
    }

    private static boolean unresolved(Hazard hazard) {
        return hazard != null && (hazard.quarantined || !hazard.terminal);
    }

    /** True while a rescan after death or respawn is still owed for this hazard. */
    public boolean needsRescan(HazardId hazardId) {
        Hazard hazard = hazard(hazardId);
        return hazard != null && hazard.rescanNeeded;
    }

    /** Hazard-level state for diagnostics and tests; a bound hazard reports its crystal's state. */
    public CrystalState stateOf(HazardId hazardId) {
        Hazard hazard = requireHazard(hazardId);
        if (hazard.quarantined) return CrystalState.QUARANTINED;
        if (hazard.bound != null) {
            CrystalRecord record = state(hazard.conn).crystals.get(hazard.bound);
            if (record != null) return record.state;
            return CrystalState.OBSERVED;
        }
        return hazard.sentTick == null ? CrystalState.PROPOSED : CrystalState.SENT;
    }

    // ------------------------------------------------------------------ queries

    public CrystalState state(EntityKey entity) {
        if (entity == null) return null;
        ConnectionState state = connections.get(entity.conn());
        if (state == null) return null;
        CrystalRecord record = state.crystals.get(entity);
        return record == null ? null : record.state;
    }

    /** Blocked for a new placement while a hazard covers the cell or the region is quarantined. */
    public boolean blocksCell(Cell cell) {
        if (cell == null) return false;
        for (ConnectionState state : connections.values()) {
            for (Hazard hazard : state.hazards.values()) {
                if (!unresolved(hazard)) continue;
                if (hazard.source.contains(cell) || hazard.swept.contains(cell)) return true;
            }
            // A quarantined region stays blocked until the connection closes.
            for (Box quarantine : state.quarantines) {
                if (quarantine.contains(cell)) return true;
            }
        }
        return false;
    }

    /** True when an entity or its box lies inside an unresolved hazard's protected volume. */
    public boolean blocked(EntityKey entity, Box bounds) {
        for (ConnectionState state : connections.values()) {
            for (Hazard hazard : state.hazards.values()) {
                if (!unresolved(hazard)) continue;
                if (hazard.bound != null && hazard.bound.equals(entity)) return true;
                if (bounds != null && hazard.swept.intersects(bounds)) return true;
            }
            for (Box quarantine : state.quarantines) {
                if (bounds != null && quarantine.intersects(bounds)) return true;
            }
            if (entity != null) {
                CrystalRecord record = state.crystals.get(entity);
                if (record != null && record.state == CrystalState.QUARANTINED) return true;
            }
        }
        return false;
    }

    /** Most pessimistic ownership over every connection that has touched the cell. */
    public Ownership ownership(Cell cell) {
        Ownership result = Ownership.NOT_OURS;
        for (ConnectionState state : connections.values()) {
            Ownership candidate = ownership(state, cell);
            if (candidate == Ownership.CONTESTED) return Ownership.CONTESTED;
            if (candidate == Ownership.CONFIRMED_OURS) return Ownership.CONFIRMED_OURS;
            if (candidate == Ownership.INFERRED_OURS) result = Ownership.INFERRED_OURS;
        }
        return result;
    }

    public Ownership ownership(ConnectionKey conn, Cell cell) {
        ConnectionState state = connections.get(conn);
        return state == null ? Ownership.NOT_OURS : ownership(state, cell);
    }

    private static Ownership ownership(ConnectionState state, Cell cell) {
        return state.ownership.getOrDefault(cell, Ownership.NOT_OURS);
    }

    /**
     * True while observations may be missing. Level-triggered: set only by a real loss signal
     * ({@link #observationsLost(ConnectionKey)}) and cleared only by {@link #reconcile(Snapshot)} after a
     * fresh snapshot.
     */
    public boolean stale() {
        return stale;
    }

    /**
     * Read-only hazard exclusion for Meteor's own crystal paths: true while this crystal is bound to an
     * unresolved sent hazard, or while any query has quarantined it. It reports ledger state and never
     * changes it, so {@link CrystalCombatGate#protects(UUID)} can keep protecting a crystal after
     * CrystalCombat is disabled.
     */
    public boolean protects(UUID crystal) {
        if (crystal == null) return false;
        for (ConnectionState state : connections.values()) {
            for (Hazard hazard : state.hazards.values()) {
                if (!unresolved(hazard)) continue;
                if (hazard.bound != null && crystal.equals(hazard.bound.uuid())) return true;
            }
            for (CrystalRecord record : state.crystals.values()) {
                if (record.state == CrystalState.QUARANTINED
                    && crystal.equals(record.entity.uuid())) return true;
            }
        }
        return false;
    }

    public int observationCapacity() {
        return observationCapacity;
    }

    /**
     * Reports that observations were dropped before they could be applied (the Phase 2b intake queue
     * overflowed). This is a real loss signal, unlike evicting an old key from the bounded dedupe window.
     * Stale stays set until {@link #reconcile(Snapshot)} supplies a fresh applied snapshot.
     */
    public void observationsLost(ConnectionKey conn) {
        Objects.requireNonNull(conn, "conn");
        stale = true;
    }

    /**
     * Clears {@link #stale()} after the caller has replaced the lost observations with a fresh applied
     * snapshot, and applies that snapshot with {@link #rescan(Snapshot)}. The caller must capture the
     * snapshot after the loss; a snapshot taken earlier cannot cover it.
     */
    public void reconcile(Snapshot snapshot) {
        Objects.requireNonNull(snapshot, "snapshot");
        rescan(snapshot);
        stale = false;
    }

    /**
     * True when the ledger has evidence that the crystal no longer exists in the world: the server removed
     * it, or it exploded. This is a fact the ledger reports, not risk-retirement evidence; the Phase 2 risk
     * budget applies its own rule (terminal evidence plus a causally later coherent effective-health
     * snapshot).
     */
    public boolean noLongerExists(EntityKey entity) {
        CrystalState current = state(entity);
        return current == CrystalState.REMOVED || current == CrystalState.EXPLODED;
    }

    /**
     * True when an explosion was observed within association range of the crystal's last known position.
     * The observation says nothing about our reservation's ownership or the damage it caused, and it is
     * not risk-retirement evidence; the Phase 2 risk budget owns that decision.
     */
    public boolean explosionObserved(EntityKey entity) {
        return state(entity) == CrystalState.EXPLODED;
    }

    // ------------------------------------------------------------------ block placements

    /** Reserves a structural placement so its later block update can be attributed. */
    public PlacementId reservePlacement(ConnectionKey conn, Cell cell, String expectedBlock) {
        Objects.requireNonNull(conn, "conn");
        Objects.requireNonNull(cell, "cell");
        Objects.requireNonNull(expectedBlock, "expectedBlock");
        Placement placement = new Placement(new PlacementId(nextPlacement++), cell, expectedBlock);
        state(conn).placements.put(placement.id, placement);
        return placement.id;
    }

    public void cancelUnsentPlacement(PlacementId placementId) {
        Placement placement = placement(placementId);
        if (placement == null || placement.sentTick != null) return;
        ConnectionState owner = ownerOf(placementId);
        if (owner != null) owner.placements.remove(placementId);
    }

    public void markPlacementSent(PlacementId placementId, int tick) {
        if (tick < 0) throw new IllegalArgumentException("tick must be non-negative");
        Placement placement = placement(placementId);
        if (placement == null) throw new IllegalArgumentException("unknown placement " + placementId);
        if (placement.sentTick == null) placement.sentTick = tick;
    }

    // ------------------------------------------------------------------ observations

    /**
     * Applies one applied observation. The call is idempotent for a repeated receipt sequence and for a
     * repeated spawn/removal/explosion of the same entity.
     */
    public void observe(Observation observation) {
        Objects.requireNonNull(observation, "observation");
        if (!remember(observation)) return;
        ConnectionState state = state(observation.conn());
        switch (observation.kind()) {
            case SPAWN -> onSpawn(observation, state);
            case MOVE -> onMove(observation, state);
            case REMOVED, TRACKING_REMOVED -> onRemoval(observation, state);
            case EXPLOSION -> onExplosion(observation, state);
            case BLOCK_UPDATE -> onBlockUpdate(observation, state);
            case CONNECTION_CHANGE -> onConnectionChange(observation);
            default -> {
                // Health, entity data, effects, equipment, damage events, status, setbacks and respawns
                // are consumed by the hurt/risk models; the crystal ledger keeps only world identity and
                // lifecycle. A respawn is not removal evidence for crystals left in the world.
            }
        }
    }

    /**
     * Remembers a receipt so duplicate deliveries are ignored. Evicting the oldest key from this bounded
     * window is not observation loss: exactly one observation was applied for it, and the window only
     * bounds memory. Real loss is reported through {@link #observationsLost(ConnectionKey)}.
     */
    private boolean remember(Observation observation) {
        ReceiptKey key = new ReceiptKey(observation.conn(), observation.receiptSeq());
        if (!receipts.add(key)) return false;
        if (receipts.size() > observationCapacity) {
            var iterator = receipts.iterator();
            iterator.next();
            iterator.remove();
        }
        return true;
    }

    private void onSpawn(Observation observation, ConnectionState state) {
        EntityKey entity = observation.entity();
        if (entity == null) return;
        if (!(observation.payload() instanceof ObservationPayload.Spawn spawn)) return;
        if (!END_CRYSTAL.equals(spawn.entityType())) return;

        CrystalRecord existing = state.crystals.get(entity);
        if (existing != null) {
            if (observation.position() != null) existing.position = observation.position();
            return;
        }

        CrystalRecord record = new CrystalRecord(entity, observation.position());
        state.crystals.put(entity, record);
        bind(observation, state, record);
    }

    private void bind(Observation observation, ConnectionState state, CrystalRecord record) {
        Vec position = record.position;
        if (position == null) return;

        List<Hazard> candidates = state.hazards.values().stream()
            .filter(hazard -> hazard.sentTick != null
                && observation.clientTick() >= hazard.sentTick
                && !hazard.quarantined
                && (!hazard.terminal || hazard.bound != null))
            .filter(hazard -> !hazard.baseline.contains(record.entity))
            .filter(hazard -> hazard.source.contains(position))
            .toList();
        if (candidates.isEmpty()) return;

        Hazard alreadyBound = candidates.stream()
            .filter(hazard -> hazard.bound != null)
            .findFirst().orElse(null);
        if (alreadyBound != null) {
            CrystalRecord boundRecord = state.crystals.get(alreadyBound.bound);
            boolean gone = boundRecord != null && (boundRecord.state == CrystalState.REMOVED
                || boundRecord.state == CrystalState.EXPLODED);
            if (gone && candidates.size() == 1) {
                // Our crystal has already gone. A new crystal in the same cell is not ours to bind; claim
                // no ownership and leave the hazard bound to the crystal it actually placed.
                state.ownership.put(cellOf(position), Ownership.CONTESTED);
                return;
            }
            for (Hazard hazard : candidates) quarantine(state, hazard, record);
            return;
        }

        if (candidates.size() > 1) {
            for (Hazard hazard : candidates) quarantine(state, hazard, record);
            return;
        }

        Hazard hazard = candidates.getFirst();
        hazard.bound = record.entity;
        record.hazard = hazard.id;
        record.state = CrystalState.OBSERVED;
    }

    private void onMove(Observation observation, ConnectionState state) {
        if (observation.entity() == null || observation.position() == null) return;
        CrystalRecord record = state.crystals.get(observation.entity());
        if (record != null) record.position = observation.position();
    }

    private void onRemoval(Observation observation, ConnectionState state) {
        if (observation.entity() == null) return;
        CrystalRecord record = state.crystals.get(observation.entity());
        if (record == null) return;
        if (observation.position() != null) record.position = observation.position();

        boolean tracking = observation.kind() == ObservationKind.TRACKING_REMOVED
            || (observation.payload() instanceof ObservationPayload.Removed removed && removed.tracking());
        if (tracking) {
            record.state = CrystalState.TRACKING_LOST;
            return;
        }
        if (record.state == CrystalState.QUARANTINED) return;

        if (!record.exploded) record.state = CrystalState.REMOVED;
        if (record.hazard != null) {
            Hazard hazard = state.hazards.get(record.hazard);
            if (hazard != null && !hazard.quarantined) hazard.terminal = true;
        }
    }

    private void onExplosion(Observation observation, ConnectionState state) {
        Vec center = observation.position();
        if (center == null) return;

        CrystalRecord best = null;
        double bestDistance = Double.MAX_VALUE;
        boolean tie = false;
        for (CrystalRecord record : state.crystals.values()) {
            if (record.position == null || record.exploded) continue;
            double distance = record.position.distance(center);
            if (distance > EXPLOSION_ASSOCIATION_RANGE) continue;
            if (distance < bestDistance) {
                bestDistance = distance;
                best = record;
                tie = false;
            } else if (distance == bestDistance) {
                tie = true;
            }
        }
        if (best == null || tie) return;

        best.exploded = true;
        best.state = CrystalState.EXPLODED;
        if (best.hazard != null) {
            Hazard hazard = state.hazards.get(best.hazard);
            if (hazard != null) hazard.terminal = true;
        }
    }

    private void onBlockUpdate(Observation observation, ConnectionState state) {
        Cell resolved = observation.cell();
        if (resolved == null && observation.position() != null) resolved = cellOf(observation.position());
        if (resolved == null) return;
        final Cell cell = resolved;

        String oldBlock = null;
        String newBlock = null;
        if (observation.payload() instanceof ObservationPayload.BlockUpdate update) {
            oldBlock = update.oldBlock();
            newBlock = update.newBlock();
        }

        List<Placement> placements = state.placements.values().stream()
            .filter(placement -> placement.cell.equals(cell))
            .toList();
        Ownership previous = state.ownership.get(cell);

        boolean matched = placements.size() == 1;
        Placement placement = matched ? placements.getFirst() : null;
        boolean pendingStateOnly = false;
        if (matched) {
            boolean answered = placement.sentTick != null
                && observation.clientTick() >= placement.sentTick
                && placement.expectedBlock.equals(newBlock);
            // A state-only update cannot confirm or deny the pending placement: the block id is the
            // same before and after, so nothing was placed or replaced. Keep the placement pending
            // instead of contesting a cell that may simply have flipped a block-state property.
            pendingStateOnly = answered && oldBlock != null && oldBlock.equals(newBlock);
            matched = answered && !Objects.equals(oldBlock, newBlock);
        }

        if (matched) {
            // The placement is answered: record the ownership it earned and prune its bookkeeping so a
            // later placement at the same cell is not permanently blocked by this one.
            state.placements.remove(placement.id);
            state.ownership.put(cell, Ownership.INFERRED_OURS);
        } else if (pendingStateOnly) {
            // Leave the placement and the previous ownership exactly as they were.
        } else if (!placements.isEmpty()) {
            state.ownership.put(cell, Ownership.CONTESTED);
        } else if (previous != null) {
            // A state-only update cannot transfer block ownership: the block id is unchanged, so the
            // block this connection placed is still the same block (a piston flipping its extended
            // property is the common case). Any update that changes the block id stays pessimistic.
            boolean stateOnly = previous == Ownership.INFERRED_OURS
                && oldBlock != null && oldBlock.equals(newBlock);
            if (!stateOnly) state.ownership.put(cell, Ownership.CONTESTED);
        }
    }

    private void onConnectionChange(Observation observation) {
        if (!(observation.payload() instanceof ObservationPayload.ConnectionChange change)) return;
        String event = change.event();
        if (event == null) return;
        String lower = event.toLowerCase(java.util.Locale.ROOT);
        if (lower.contains("leave") || lower.contains("disconnect") || lower.contains("close")) {
            connectionClosed(observation.conn());
        }
    }

    private void quarantine(ConnectionState state, Hazard hazard, CrystalRecord ambiguous) {
        if (hazard.bound != null) {
            CrystalRecord boundRecord = state.crystals.get(hazard.bound);
            if (boundRecord != null && boundRecord.state != CrystalState.REMOVED
                && boundRecord.state != CrystalState.EXPLODED) {
                boundRecord.state = CrystalState.QUARANTINED;
            }
            hazard.bound = null;
        }
        hazard.quarantined = true;
        state.quarantines.add(hazard.source);
        state.quarantines.add(hazard.swept);
        ambiguous.state = CrystalState.QUARANTINED;
        if (ambiguous.position != null) {
            state.ownership.put(cellOf(ambiguous.position), Ownership.CONTESTED);
        }
    }

    // ------------------------------------------------------------------ lifecycle

    /**
     * Ends an action generation: unsent work is released, sent hazards are kept and every hazard is marked
     * for a rescan. Death, respawn, config change and disable all route here.
     */
    public void actionGenerationEnded(ActionGeneration generation) {
        Objects.requireNonNull(generation, "generation");
        for (ConnectionState state : connections.values()) {
            state.hazards.values().removeIf(hazard ->
                hazard.sentTick == null && hazard.bound == null && !hazard.quarantined);
            state.placements.values().removeIf(placement -> placement.sentTick == null);
            for (Hazard hazard : state.hazards.values()) hazard.rescanNeeded = true;
        }
    }

    /**
     * Rebinds hazards against the current applied snapshot as a whole-snapshot reconciliation. Every
     * snapshot crystal is recorded if it is not already known. A bound hazard claims its crystal and is
     * revived when the snapshot still lists it (absence is never removal evidence); an unbound hazard binds
     * only a unique candidate. A hazard with more than one candidate, a crystal that is a candidate for more
     * than one hazard, or a candidate crystal that is already QUARANTINED quarantines every hazard involved
     * instead of letting insertion order choose.
     */
    public void rescan(Snapshot snapshot) {
        Objects.requireNonNull(snapshot, "snapshot");
        ConnectionState state = connections.get(snapshot.conn());
        if (state == null) return;

        for (Snapshot.Crystal crystal : snapshot.crystals()) {
            state.crystals.computeIfAbsent(crystal.entity(), entity -> new CrystalRecord(entity, crystal.position()));
        }

        Set<EntityKey> claimed = new LinkedHashSet<>();
        for (Hazard hazard : state.hazards.values()) {
            hazard.rescanNeeded = false;
            if (hazard.quarantined) continue;

            if (hazard.bound != null) {
                claimed.add(hazard.bound);
                Snapshot.Crystal present = snapshot.crystals().stream()
                    .filter(crystal -> crystal.entity().equals(hazard.bound))
                    .findFirst().orElse(null);
                if (present != null) {
                    CrystalRecord record = state.crystals.computeIfAbsent(present.entity(),
                        entity -> new CrystalRecord(entity, present.position()));
                    record.position = present.position();
                    record.state = CrystalState.OBSERVED;
                    record.exploded = false;
                    hazard.terminal = false;
                }
            }
        }

        // Candidates are only meaningful for a hazard that may still own a crystal.
        Map<Hazard, List<Snapshot.Crystal>> candidatesByHazard = new LinkedHashMap<>();
        Map<EntityKey, Integer> candidateHazardCounts = new HashMap<>();
        Set<Hazard> ambiguous = new LinkedHashSet<>();
        for (Hazard hazard : state.hazards.values()) {
            if (hazard.bound != null || hazard.quarantined || hazard.terminal || hazard.sentTick == null) continue;
            List<Snapshot.Crystal> candidates = snapshot.crystals().stream()
                .filter(crystal -> !hazard.baseline.contains(crystal.entity()))
                .filter(crystal -> !claimed.contains(crystal.entity()))
                .filter(crystal -> hazard.source.contains(crystal.position()))
                .toList();
            candidatesByHazard.put(hazard, candidates);
            for (Snapshot.Crystal candidate : candidates) {
                candidateHazardCounts.merge(candidate.entity(), 1, Integer::sum);
                // A crystal already disputed by another hazard (or by an earlier reconciliation) is
                // never re-bound: presence in a snapshot cannot clear an existing quarantine.
                CrystalRecord record = state.crystals.get(candidate.entity());
                if (record != null && record.state == CrystalState.QUARANTINED) ambiguous.add(hazard);
            }
        }

        // Ambiguity is a property of the whole snapshot: more than one candidate for a hazard, or one
        // crystal claimed by more than one hazard, makes every hazard involved quarantine that crystal.
        for (Map.Entry<Hazard, List<Snapshot.Crystal>> entry : candidatesByHazard.entrySet()) {
            List<Snapshot.Crystal> candidates = entry.getValue();
            if (candidates.size() > 1) {
                ambiguous.add(entry.getKey());
                continue;
            }
            for (Snapshot.Crystal candidate : candidates) {
                if (candidateHazardCounts.getOrDefault(candidate.entity(), 0) > 1) {
                    ambiguous.add(entry.getKey());
                }
            }
        }
        for (Hazard hazard : ambiguous) {
            for (Snapshot.Crystal candidate : candidatesByHazard.get(hazard)) {
                CrystalRecord record = state.crystals.computeIfAbsent(candidate.entity(),
                    entity -> new CrystalRecord(entity, candidate.position()));
                quarantine(state, hazard, record);
            }
        }

        for (Map.Entry<Hazard, List<Snapshot.Crystal>> entry : candidatesByHazard.entrySet()) {
            Hazard hazard = entry.getKey();
            List<Snapshot.Crystal> candidates = entry.getValue();
            if (ambiguous.contains(hazard) || candidates.size() != 1) continue;
            Snapshot.Crystal candidate = candidates.getFirst();
            if (claimed.contains(candidate.entity())) continue;
            CrystalRecord record = state.crystals.computeIfAbsent(candidate.entity(),
                entity -> new CrystalRecord(entity, candidate.position()));
            record.position = candidate.position();
            record.state = CrystalState.OBSERVED;
            record.hazard = hazard.id;
            hazard.bound = candidate.entity();
            claimed.add(candidate.entity());
        }
    }

    /** Connection close clears that connection's hazards, crystals, quarantines and ownership. */
    public void connectionClosed(ConnectionKey conn) {
        connections.remove(conn);
    }

    // ------------------------------------------------------------------ internals

    private ConnectionState state(ConnectionKey conn) {
        return connections.computeIfAbsent(conn, key -> new ConnectionState());
    }

    private Hazard hazard(HazardId hazardId) {
        if (hazardId == null) return null;
        for (ConnectionState state : connections.values()) {
            Hazard hazard = state.hazards.get(hazardId);
            if (hazard != null) return hazard;
        }
        return null;
    }

    private Hazard requireHazard(HazardId hazardId) {
        Hazard hazard = hazard(hazardId);
        if (hazard == null) throw new IllegalArgumentException("unknown hazard " + hazardId);
        return hazard;
    }

    private Placement placement(PlacementId placementId) {
        if (placementId == null) return null;
        for (ConnectionState state : connections.values()) {
            Placement placement = state.placements.get(placementId);
            if (placement != null) return placement;
        }
        return null;
    }

    private ConnectionState ownerOf(PlacementId placementId) {
        for (ConnectionState state : connections.values()) {
            if (state.placements.containsKey(placementId)) return state;
        }
        return null;
    }

    private static Cell cellOf(Vec position) {
        return new Cell((int) Math.floor(position.x()), (int) Math.floor(position.y()),
            (int) Math.floor(position.z()));
    }
}
