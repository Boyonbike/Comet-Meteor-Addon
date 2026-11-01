/*
 * Original Comet implementation by Boyonabike.
 * Independent: written from the pinned 26.2 artifact and Meteor build 35 behaviour; no third-party code.
 * SPDX-License-Identifier: CC0-1.0
 */

package dev.tntcoating.core.combat;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/**
 * Pure TNT-risk budget. It owns the reservation lifetime
 * Proposed -> Reserved -> Dispatching -> Sent -> Debt -> Retired:
 *
 * <ul>
 *   <li>Proposed exists only in the planner; the first message the budget sees is a reserve call.</li>
 *   <li>Reserved was priced against a {@link SafetyState}; only the risk budget is acquired here because
 *       {@link CombatScheduler} owns the action/slot/rotation locks and the packet budget.</li>
 *   <li>Dispatching is entered before the first causative packet leaves.</li>
 *   <li>Sent means at least one causative packet may have reached the server. From that moment the risk
 *       can never be released except through the retirement rules.</li>
 *   <li>Debt is not a separate storage state: it is the query status of an outstanding sent risk that has
 *       no terminal evidence yet. Sent-without-evidence and Debt therefore both count in the bound; the
 *       single stored state stays Sent.</li>
 * </ul>
 *
 * <p>Aggregate bound = sum of self and per-friend components. Absorption appears only inside
 * {@link EffectiveHealth}; the bound is the raw {@link MitigatedDamage#beforeAbsorption()} upper bound and
 * is never reduced by absorption. Friends are independent: each has its own cumulative ceiling and, when
 * known, its own survival check.
 *
 * <p>Retirement rules implemented here: rule 1 (explosion + causally later coherent effective-health
 * snapshot), rule 2 coherence, rule 3 never satisfied ({@link ProcessingBarrier}), rule 4 debt counting,
 * rule 5 respawn/connection close/tracking-removal semantics. Rule 3 deliberately has no path.
 */
public final class CombatRiskBudget {
    /** Stored lifetime states; Proposed and Debt are documented projections, not storage states. */
    public enum State {
        RESERVED,
        DISPATCHING,
        SENT,
        RETIRED
    }

    /**
     * Tight risk-arming tolerance. The Phase 1 ledger uses 1.25 for observation bookkeeping; arming a
     * reservation is stricter because an adjacent crystal is a different hazard, so only an explosion at
     * the bound position (and attributed to the bound crystal when the observation names one) counts.
     */
    static final double EXPLOSION_POSITION_TOLERANCE = 0.5;
    /**
     * Upper bound on unresolved health/absorption pairs per connection. Each pair is a suspension the
     * budget refuses to clear by assertion, so an unbounded map is an unbounded suspension: once the cap is
     * reached a new entity is treated as missing evidence rather than silently growing the map.
     */
    static final int MAX_PENDING_HEALTH_PAIRS_PER_CONNECTION = 64;
    private static final double EPSILON = 1e-9;
    private static final int MAX_TERMINAL_HISTORY = 4096;
    private static final int TOMBSTONE_LIFETIME_TICKS = 400;

    private final MitigationWidener widener;
    private final GroupedBound groupedBound;
    private final AnarchyQuietWindow quietWindow;
    private final Map<RiskId, Risk> risks = new LinkedHashMap<>();
    private final Map<RiskId, State> terminalHistory = new LinkedHashMap<>();
    private final Set<SuspensionCause> suspensions = EnumSet.noneOf(SuspensionCause.class);
    private final Map<HealthKey, PendingHealth> pendingHealth = new HashMap<>();
    private final Map<HealthKey, Long> lastAppliedRevisions = new HashMap<>();
    private final List<QuietTombstone> tombstones = new ArrayList<>();
    private long nextRiskId = 1;
    private long lastEnvelopeRevision = Long.MIN_VALUE;
    private int serverTick = -1;

    /**
     * Conservative production constructor: grouped bounds disabled, statistical quiet window disabled.
     * A missing widener is allowed; a newer envelope then suspends instead of keeping stale bounds.
     */
    public CombatRiskBudget(MitigationWidener widener) {
        this(widener, new GroupedBound(GroupedBound.DEFAULT_ENABLED), AnarchyQuietWindow.disabled());
    }

    /** Test/profile hook. Neither the grouped bound nor the quiet window is wired into a default path. */
    public CombatRiskBudget(MitigationWidener widener, GroupedBound groupedBound,
                            AnarchyQuietWindow quietWindow) {
        this.widener = widener;
        this.groupedBound = Objects.requireNonNull(groupedBound, "groupedBound");
        this.quietWindow = Objects.requireNonNull(quietWindow, "quietWindow");
    }

    /** Convenience overload with no bound crystal; such a risk can never retire by rule 1. */
    public Optional<RiskId> reserve(long intent, ExposureBound upper, ServerTickInterval arrival,
                                    SafetyState state) {
        return reserve(intent, upper, arrival, state, null, null);
    }

    /**
     * Atomically validates and reserves one risk. A risk without both a bound crystal and a bound
     * position is accepted but can never retire by rule 1, so it remains debt.
     */
    public Optional<RiskId> reserve(long intent, ExposureBound upper, ServerTickInterval arrival,
                                    SafetyState state, EntityKey boundCrystal, Vec boundPosition) {
        if (suspended() || upper == null || arrival == null || state == null) return Optional.empty();
        if (arrival.earliest() < 0) return Optional.empty();
        if (upper.envelope() == null) return Optional.empty();
        long envelopeRevision = upper.envelope().revision();
        if (envelopeRevision < 0 || envelopeRevision < lastEnvelopeRevision) return Optional.empty();

        Safety safety = state.safety();
        if (!validSafety(safety) || state.conn() == null || state.gen() == null
            || state.self() == null || state.selfHealth() == null) {
            return Optional.empty();
        }
        if (boundCrystal != null && !boundCrystal.conn().equals(state.conn())) {
            return Optional.empty();
        }

        double newSelf = upper.selfUpper().beforeAbsorption();
        if (!validBound(newSelf) || newSelf > safety.maxSelf()) return Optional.empty();
        double selfLower = healthLower(state.selfHealth());
        if (Double.isNaN(selfLower)) return Optional.empty();
        double selfCumulative = current(state.self()).value() + newSelf;
        if (!(selfLower - selfCumulative > safety.minRemaining())) return Optional.empty();

        Map<EntityKey, Double> friends = new LinkedHashMap<>();
        for (Map.Entry<EntityKey, MitigatedDamage> entry : upper.friendUpper().entrySet()) {
            EntityKey friend = entry.getKey();
            if (friend == null || entry.getValue() == null || friend.equals(state.self())) {
                return Optional.empty();
            }
            double friendBound = entry.getValue().beforeAbsorption();
            if (!validBound(friendBound)) return Optional.empty();
            double cumulative = current(friend).value() + friendBound;
            if (cumulative > safety.maxFriend()) return Optional.empty();
            EffectiveHealth friendHealth = state.friends().get(friend);
            if (friendHealth != null && friendHealth.cap() == Capability.KNOWN) {
                double lower = healthLower(friendHealth);
                if (Double.isNaN(lower) || !(lower - cumulative > 0.0)) return Optional.empty();
            }
            friends.put(friend, friendBound);
        }

        RiskId id = new RiskId(nextRiskId++);
        Risk risk = new Risk(id, intent, state.conn(), state.gen(), state.self(), newSelf, friends,
            knownValue(state.selfHealth()), friendBaselines(state.friends(), friends.keySet()),
            arrival, boundCrystal, boundPosition, envelopeRevision);
        risks.put(id, risk);
        lastEnvelopeRevision = envelopeRevision;
        return Optional.of(id);
    }

    /**
     * Binds the crystal that a placement risk will be responsible for, after the spawn was observed.
     *
     * <p>A placement risk is reserved before the crystal exists, so it carries neither a bound entity nor a
     * bound position. Once exactly one spawn binds in the ledger, the engine calls this once to attach the
     * pair. This is not a retirement and not a widening: it never changes the risk state, never retires a
     * component and never accepts a second binding. Until a matching explosion and a later coherent health
     * snapshot arrive, the risk is still debt. Risks that were reserved with a bound crystal already (or
     * that migrated dimensions) are refused.</p>
     *
     * @return true when the risk exists, was unbound, and the crystal belongs to the risk's connection
     */
    public boolean bindCrystal(RiskId id, EntityKey crystal, Vec position) {
        if (id == null || crystal == null || position == null) return false;
        Risk risk = risks.get(id);
        if (risk == null || risk.state == State.RETIRED) return false;
        if (risk.boundCrystal != null || risk.boundPosition != null) return false;
        if (!crystal.conn().equals(risk.conn)) return false;
        if (!Double.isFinite(position.x()) || !Double.isFinite(position.y())
            || !Double.isFinite(position.z())) {
            return false;
        }
        risk.boundCrystal = crystal;
        risk.boundPosition = position;
        return true;
    }

    /** Read-only view of the bound crystal, for diagnostics and tests; never changes the risk. */
    public Optional<EntityKey> boundCrystal(RiskId id) {
        Risk risk = risks.get(id);
        return risk == null ? Optional.empty() : Optional.ofNullable(risk.boundCrystal);
    }

    /** Read-only view of the bound position, for diagnostics and tests; never changes the risk. */
    public Optional<Vec> boundPosition(RiskId id) {
        Risk risk = risks.get(id);
        return risk == null ? Optional.empty() : Optional.ofNullable(risk.boundPosition);
    }

    /** Marks that packets are about to leave; must precede the first causative packet. */
    public void dispatching(RiskId id) {
        Risk risk = risks.get(id);
        if (risk != null && risk.state == State.RESERVED) risk.state = State.DISPATCHING;
    }

    /**
     * Marks that any causative packet may have reached the server. Irreversible: sent risk can only leave
     * this budget through a retirement rule or connection close.
     */
    public void mayHaveLeft(RiskId id) {
        mayHaveLeft(id, -1L);
    }

    /**
     * Marks that causative packets may have reached the server, remembering the applied-state revision the
     * dispatch decision was made against. Health evidence applied at or before that revision predates the
     * risk and must never retire it.
     */
    public void mayHaveLeft(RiskId id, long appliedRevision) {
        Risk risk = risks.get(id);
        if (risk != null && (risk.state == State.RESERVED || risk.state == State.DISPATCHING)) {
            risk.state = State.SENT;
            risk.sentRevision = Math.max(risk.sentRevision, appliedRevision);
        }
    }

    /**
     * Releases a risk that provably never transmitted. Refused (no-op) once {@link #mayHaveLeft} was seen;
     * the caller must not silently release sent risk.
     */
    public void notTransmitted(RiskId id) {
        Risk risk = risks.get(id);
        if (risk == null) return;
        if (risk.state == State.RESERVED || risk.state == State.DISPATCHING) terminate(risk);
    }

    /** Applies one observation. Never retires on elapsed time, tracking removal, or a death marker. */
    public void observe(Observation observation) {
        if (observation == null) {
            suspend(SuspensionCause.MISSING_DAMAGE_DATA);
            return;
        }
        switch (observation.kind()) {
            case EXPLOSION -> observeExplosion(observation);
            case HEALTH -> observeHealth(observation);
            case ENTITY_DATA -> observeEntityData(observation);
            case SETBACK -> suspend(SuspensionCause.SETBACK);
            case DEATH -> suspend(SuspensionCause.DEATH);
            case RESPAWN -> {
                clearSelfOnRespawn(observation.conn());
                suspend(SuspensionCause.RESPAWN);
            }
            case CONNECTION_CHANGE -> suspend(SuspensionCause.CONNECTION_CHANGE);
            default -> {
                // Tracking removal, totem pop, equipment and effect packets are not detonation evidence.
            }
        }
    }

    /**
     * Re-prices every outstanding nonzero component against a newer envelope. The revision is the only
     * fact on {@link MitigationEnvelope}, so the injected {@link MitigationWidener} performs the
     * recalculation. Bounds only widen; a missing, throwing or invalid widener suspends.
     */
    public void widen(MitigationEnvelope envelope) {
        if (envelope == null) {
            suspend(SuspensionCause.WIDENING_UNAVAILABLE);
            return;
        }
        if (envelope.revision() <= lastEnvelopeRevision) return;
        if (widener == null) {
            suspend(SuspensionCause.WIDENING_UNAVAILABLE);
            return;
        }

        Map<RiskId, Double> selfUpdates = new HashMap<>();
        Map<RiskId, Map<EntityKey, Double>> friendUpdates = new HashMap<>();
        try {
            for (Risk risk : risks.values()) {
                if (risk.envelopeRevision >= envelope.revision()) continue;
                if (!risk.selfRetired && risk.selfBound > 0.0) {
                    MitigatedDamage result = widener.widen(envelope, new MitigatedDamage(risk.selfBound));
                    if (!validWidened(result)) {
                        suspend(SuspensionCause.WIDENING_UNAVAILABLE);
                        return;
                    }
                    selfUpdates.put(risk.id, Math.max(risk.selfBound, result.beforeAbsorption()));
                }
                Map<EntityKey, Double> friends = new HashMap<>();
                for (Map.Entry<EntityKey, Double> entry : risk.friendBounds.entrySet()) {
                    if (risk.retiredFriends.contains(entry.getKey()) || entry.getValue() <= 0.0) continue;
                    MitigatedDamage result = widener.widen(envelope,
                        new MitigatedDamage(entry.getValue()));
                    if (!validWidened(result)) {
                        suspend(SuspensionCause.WIDENING_UNAVAILABLE);
                        return;
                    }
                    friends.put(entry.getKey(), Math.max(entry.getValue(),
                        result.beforeAbsorption()));
                }
                if (!friends.isEmpty()) friendUpdates.put(risk.id, friends);
            }
        } catch (RuntimeException unexpected) {
            suspend(SuspensionCause.WIDENING_UNAVAILABLE);
            return;
        }

        for (Map.Entry<RiskId, Double> entry : selfUpdates.entrySet()) {
            risks.get(entry.getKey()).selfBound = entry.getValue();
        }
        for (Map.Entry<RiskId, Map<EntityKey, Double>> entry : friendUpdates.entrySet()) {
            Risk risk = risks.get(entry.getKey());
            risk.friendBounds.putAll(entry.getValue());
        }
        for (Risk risk : risks.values()) risk.envelopeRevision = envelope.revision();
        lastEnvelopeRevision = envelope.revision();
        // The failed-widening hold ends only here, when every outstanding bound and the watermark have
        // actually been re-priced against the newer envelope.
        suspensions.remove(SuspensionCause.WIDENING_UNAVAILABLE);
    }

    /**
     * An action generation ended. Risks still Reserved/Dispatching are released without transmission;
     * may-have-left risks become unreleasable debt.
     */
    public void actionGenerationEnded(ActionGeneration generation) {
        if (generation == null) return;
        for (Risk risk : new ArrayList<>(risks.values())) {
            if (!risk.gen.equals(generation)) continue;
            if (risk.state == State.RESERVED || risk.state == State.DISPATCHING) terminate(risk);
        }
    }

    /** Connection close clears exactly that connection's risk, pending pairs and quiet tombstones. */
    public void connectionClosed(ConnectionKey conn) {
        if (conn == null) return;
        for (Risk risk : new ArrayList<>(risks.values())) {
            if (risk.conn.equals(conn)) terminate(risk);
        }
        pendingHealth.keySet().removeIf(key -> key.conn().equals(conn));
        lastAppliedRevisions.keySet().removeIf(key -> key.conn().equals(conn));
        tombstones.removeIf(tombstone -> tombstone.conn.equals(conn));
        if (pendingHealth.isEmpty()) suspensions.remove(SuspensionCause.INCOHERENT_HEALTH);
    }

    /**
     * Moves outstanding risk onto a new connection epoch after a dimension change. A dimension change is not
     * a respawn: self debt keeps counting and friend debt is preserved, because the damage the sent packets
     * may have caused is still unresolved. The old dimension's hazards stop being usable as rule-1 evidence
     * (the entity identity epoch changed and positions are incomparable), so migrated risk can never retire
     * by rule 1 or the statistical window and stays counted until the next connection close.
     */
    public void dimensionChanged(ConnectionKey previous, ConnectionKey next) {
        if (previous == null || next == null) return;
        for (Risk risk : risks.values()) {
            if (!risk.conn.equals(previous)) continue;
            risk.conn = next;
            risk.selfEntity = migrate(risk.selfEntity, next);
            Map<EntityKey, Double> friends = new LinkedHashMap<>();
            for (Map.Entry<EntityKey, Double> entry : risk.friendBounds.entrySet()) {
                friends.put(migrate(entry.getKey(), next), entry.getValue());
            }
            Map<EntityKey, Double> known = new LinkedHashMap<>();
            for (Map.Entry<EntityKey, Double> entry : risk.friendKnownValues.entrySet()) {
                known.put(migrate(entry.getKey(), next), entry.getValue());
            }
            risk.friendBounds.clear();
            risk.friendBounds.putAll(friends);
            risk.friendKnownValues.clear();
            risk.friendKnownValues.putAll(known);
            Set<EntityKey> retired = new HashSet<>();
            for (EntityKey friend : risk.retiredFriends) retired.add(migrate(friend, next));
            risk.retiredFriends.clear();
            risk.retiredFriends.addAll(retired);
            risk.healthAfterExplosion.clear();
            risk.explosionRevision = -1;
            risk.explosionAnchorTick = -1;
            risk.dimensionMigrated = true;
        }
        pendingHealth.keySet().removeIf(key -> key.conn().equals(previous));
        lastAppliedRevisions.keySet().removeIf(key -> key.conn().equals(previous));
        tombstones.removeIf(tombstone -> tombstone.conn.equals(previous));
        if (pendingHealth.isEmpty()) suspensions.remove(SuspensionCause.INCOHERENT_HEALTH);
    }

    /**
     * Sent components that can never satisfy rule 1: no bound crystal, no bound position, or migrated to a
     * new dimension. These are deliberately not garbage-collected - they stay counted as debt until the
     * connection closes, because dropping them would silently forget possible damage. This metric makes the
     * accumulation visible instead.
     */
    public int neverRetirableComponents() {
        int count = 0;
        for (Risk risk : risks.values()) {
            if (risk.state != State.SENT) continue;
            boolean noEvidence = risk.dimensionMigrated || risk.boundCrystal == null
                || risk.boundPosition == null;
            if (!noEvidence) continue;
            if (!risk.selfRetired && risk.selfBound > 0.0) count++;
            for (Map.Entry<EntityKey, Double> entry : risk.friendBounds.entrySet()) {
                if (!risk.retiredFriends.contains(entry.getKey()) && entry.getValue() > 0.0) count++;
            }
        }
        return count;
    }

    /** Unresolved per-connection health/absorption pairs held for rule-2 coherence. */
    public int pendingHealthPairs() {
        return pendingHealth.size();
    }

    /** Whether risk from a prior dimension epoch was migrated onto {@code conn}. */
    public boolean migratedFromPriorDimension(ConnectionKey conn) {
        if (conn == null) return false;
        for (Risk risk : risks.values()) {
            if (risk.conn.equals(conn) && risk.dimensionMigrated) return true;
        }
        return false;
    }

    public boolean suspended() {
        return !suspensions.isEmpty();
    }

    /** Read-only snapshot of the active causes, in enum order; never mutates the budget. */
    public Set<SuspensionCause> suspensionCauses() {
        return Set.copyOf(suspensions);
    }

    /** Holds new detonations until reconciliation. Suspension is not retirement. */
    public void suspend(SuspensionCause cause) {
        if (cause != null) suspensions.add(cause);
    }

    /**
     * Clears suspension after evidence is reconciled. Incoherent health pairs are the one cause the
     * budget refuses to clear by assertion: while any pair is still unresolved the budget stays suspended.
     */
    public void resumeAfterReconciliation() {
        boolean wideningUnavailable = suspensions.contains(SuspensionCause.WIDENING_UNAVAILABLE);
        suspensions.clear();
        if (wideningUnavailable) suspensions.add(SuspensionCause.WIDENING_UNAVAILABLE);
        if (!pendingHealth.isEmpty()) suspensions.add(SuspensionCause.INCOHERENT_HEALTH);
    }

    /** Server-tick clock for the Anarchy-only statistical window; no Conservative retirement uses it. */
    public void tick(int serverTick) {
        if (serverTick < 0) return;
        this.serverTick = Math.max(this.serverTick, serverTick);
        retireQuietly();
        pruneTombstones();
    }

    /** Outstanding sent-without-terminal-evidence status for one entity. */
    public Debt debt(EntityKey who) {
        if (who == null) return Debt.zero();
        int count = 0;
        double sum = 0.0;
        for (Risk risk : risks.values()) {
            if (risk.state != State.SENT) continue;
            if (risk.selfEntity.equals(who) && !risk.selfRetired) {
                count++;
                sum += risk.selfBound;
            }
            if (risk.friendBounds.containsKey(who) && !risk.retiredFriends.contains(who)) {
                count++;
                sum += risk.friendBounds.get(who);
            }
        }
        return new Debt(count, sum);
    }

    /**
     * Summed outstanding bound for one entity. The grouped path receives no proof data from this class, so
     * even an enabled {@link GroupedBound} always falls back to the plain sum here.
     */
    public Bound current(EntityKey who) {
        if (who == null) return Bound.zero();
        double sum = 0.0;
        int components = 0;
        List<GroupedBound.Member> members = new ArrayList<>();
        for (Risk risk : risks.values()) {
            if (risk.selfEntity.equals(who) && !risk.selfRetired) {
                sum += risk.selfBound;
                components++;
                members.add(groupedMember(risk, risk.selfBound));
            }
            if (risk.friendBounds.containsKey(who) && !risk.retiredFriends.contains(who)) {
                double bound = risk.friendBounds.get(who);
                sum += bound;
                components++;
                members.add(groupedMember(risk, bound));
            }
        }
        if (groupedBound.enabled() && !members.isEmpty()) return groupedBound.bound(members);
        return new Bound(sum, components);
    }

    /** Stored state for one risk id, {@link State#RETIRED} for retained terminal ids, or {@code null}. */
    public State state(RiskId id) {
        if (id == null) return null;
        Risk risk = risks.get(id);
        return risk != null ? risk.state : terminalHistory.get(id);
    }

    /** Number of outstanding risk reservations. */
    public int outstanding() {
        return risks.size();
    }

    private void observeExplosion(Observation observation) {
        Vec at = observation.position();
        if (at == null && observation.cell() != null) at = observation.cell().center();
        if (at == null) return;
        for (Risk risk : risks.values()) {
            if (risk.state != State.SENT) continue;
            if (!risk.conn.equals(observation.conn())) continue;
            if (risk.boundCrystal == null || risk.boundPosition == null) continue;
            if (risk.dimensionMigrated) continue;
            // Rule 1 needs an explosion attributed to the bound crystal. An unattributed explosion at the
            // same coordinates is not that crystal's explosion (a new crystal could have been placed there),
            // so it is ignored unless the runtime resolved it to an entity before observing.
            if (observation.entity() == null) continue;
            if (!observation.entity().equals(risk.boundCrystal)) continue;
            if (risk.boundPosition.distance(at) > EXPLOSION_POSITION_TOLERANCE) continue;
            if (observation.appliedRevision() <= risk.explosionRevision) continue;
            risk.explosionRevision = observation.appliedRevision();
            risk.explosionAnchorTick = serverTick;
            risk.healthAfterExplosion.clear();
        }
    }

    private void observeHealth(Observation observation) {
        if (observation.entity() == null) {
            suspend(SuspensionCause.MISSING_DAMAGE_DATA);
            return;
        }
        if (!(observation.payload() instanceof ObservationPayload.Health health)) {
            suspend(SuspensionCause.MISSING_DAMAGE_DATA);
            return;
        }
        if (!validHealthValue(health.health()) || !validHealthValue(health.absorption())) {
            suspend(SuspensionCause.MISSING_DAMAGE_DATA);
            return;
        }
        HealthKey key = new HealthKey(observation.conn(), observation.entity());
        pendingHealth.remove(key);
        if (pendingHealth.isEmpty()) suspensions.remove(SuspensionCause.INCOHERENT_HEALTH);
        applySnapshot(key.conn(), key.entity(), observation.appliedRevision(),
            health.health() + health.absorption());
    }

    private void observeEntityData(Observation observation) {
        if (observation.entity() == null) {
            suspend(SuspensionCause.MISSING_DAMAGE_DATA);
            return;
        }
        if (!(observation.payload() instanceof ObservationPayload.EntityData data)) {
            suspend(SuspensionCause.MISSING_DAMAGE_DATA);
            return;
        }
        boolean healthKnown = data.healthKnown();
        boolean absorptionKnown = data.absorptionKnown();
        if (!healthKnown && !absorptionKnown) {
            suspend(SuspensionCause.MISSING_DAMAGE_DATA);
            return;
        }
        if ((healthKnown && !validHealthValue(data.health()))
            || (absorptionKnown && !validHealthValue(data.absorption()))) {
            suspend(SuspensionCause.MISSING_DAMAGE_DATA);
            return;
        }

        HealthKey key = new HealthKey(observation.conn(), observation.entity());
        if (!pendingHealth.containsKey(key)
            && pendingHealthCount(observation.conn()) >= MAX_PENDING_HEALTH_PAIRS_PER_CONNECTION) {
            suspend(SuspensionCause.INCOHERENT_HEALTH);
            return;
        }
        PendingHealth pending = pendingHealth.computeIfAbsent(key, ignored -> new PendingHealth());
        if (healthKnown) {
            pending.healthKnown = true;
            pending.health = data.health();
            pending.healthRevision = observation.appliedRevision();
        }
        if (absorptionKnown) {
            pending.absorptionKnown = true;
            pending.absorption = data.absorption();
            pending.absorptionRevision = observation.appliedRevision();
        }
        if (!pending.healthKnown || !pending.absorptionKnown) {
            suspend(SuspensionCause.INCOHERENT_HEALTH);
            return;
        }
        // Rule 2: a health value and an absorption value from different applied revisions may belong to
        // different events. Hold them instead of merging them under one made-up revision.
        if (pending.healthRevision != pending.absorptionRevision) {
            suspend(SuspensionCause.INCOHERENT_HEALTH);
            return;
        }
        pending.revision = pending.healthRevision;

        pendingHealth.remove(key);
        if (pendingHealth.isEmpty()) suspensions.remove(SuspensionCause.INCOHERENT_HEALTH);
        applySnapshot(key.conn(), key.entity(), pending.revision,
            pending.health + pending.absorption);
    }

    private void applySnapshot(ConnectionKey conn, EntityKey who, long revision, double value) {
        HealthKey key = new HealthKey(conn, who);
        Long lastRevision = lastAppliedRevisions.get(key);
        // An applied-state revision that is older than one already applied for this entity is stale
        // evidence, whatever order the packets arrived in. It may not retire anything.
        if (lastRevision != null && revision < lastRevision) return;
        lastAppliedRevisions.put(key, lastRevision == null ? revision : Math.max(lastRevision, revision));
        recordMisses(conn, who, revision, value);
        for (Risk risk : new ArrayList<>(risks.values())) {
            if (risk.state != State.SENT) continue;
            if (!risk.conn.equals(conn)) continue;
            if (risk.dimensionMigrated) continue;
            if (risk.explosionRevision < 0 || revision <= risk.explosionRevision) continue;
            // Coherence with the placement act itself: the snapshot must have been applied after the
            // revision the dispatch decision was based on, never before the crystal was placed.
            if (risk.sentRevision >= 0 && revision <= risk.sentRevision) continue;

            boolean changed = false;
            if (risk.selfEntity.equals(who) && !risk.selfRetired && risk.selfBound > 0.0) {
                risk.selfRetired = true;
                changed = true;
            }
            if (risk.friendBounds.containsKey(who) && !risk.retiredFriends.contains(who)
                && risk.friendBounds.get(who) > 0.0) {
                risk.retiredFriends.add(who);
                changed = true;
            }
            if (!changed) continue;
            risk.healthAfterExplosion.add(who);
            if (allComponentsRetired(risk)) terminate(risk);
        }
    }

    private void recordMisses(ConnectionKey conn, EntityKey who, long revision, double value) {
        Iterator<QuietTombstone> iterator = tombstones.iterator();
        while (iterator.hasNext()) {
            QuietTombstone tombstone = iterator.next();
            if (!tombstone.conn.equals(conn) || !tombstone.entity.equals(who)) continue;
            if (revision <= tombstone.explosionRevision) continue;
            if (tombstone.baselineKnown() && value + EPSILON < tombstone.baseline) {
                quietWindow.recordMiss("STATISTICAL_MISS entity=" + who.id() + " value=" + value
                    + " baseline=" + tombstone.baseline + " revision=" + revision);
            }
            iterator.remove();
        }
    }

    private void clearSelfOnRespawn(ConnectionKey conn) {
        for (Risk risk : new ArrayList<>(risks.values())) {
            if (!risk.conn.equals(conn)) continue;
            risk.selfRetired = true;
            if (allComponentsRetired(risk)) terminate(risk);
        }
    }

    private void retireQuietly() {
        if (!quietWindow.enabled()) return;
        // Suspension means the evidence is unreliable; statistical retirement must wait for reconciliation.
        if (suspended()) return;
        for (Risk risk : new ArrayList<>(risks.values())) {
            if (risk.state != State.SENT) continue;
            if (risk.explosionRevision < 0 || risk.explosionAnchorTick < 0) continue;
            if (serverTick - risk.explosionAnchorTick < quietWindow.quietTicks()) continue;
            if (awaitsHealthEvidence(risk)) continue;

            addTombstone(risk, risk.selfEntity, risk.selfBound, risk.selfKnownValue, risk.selfRetired);
            for (Map.Entry<EntityKey, Double> entry : risk.friendBounds.entrySet()) {
                addTombstone(risk, entry.getKey(), entry.getValue(),
                    risk.friendKnownValues.getOrDefault(entry.getKey(), Double.NaN),
                    risk.retiredFriends.contains(entry.getKey()));
            }
            quietWindow.recordRetirement("STATISTICAL_RETIREMENT risk=" + risk.id.value()
                + " intent=" + risk.intent + " anchor=" + risk.explosionAnchorTick
                + " quietTicks=" + quietWindow.quietTicks());
            terminate(risk);
        }
    }

    private void addTombstone(Risk risk, EntityKey entity, double bound, double baseline,
                              boolean retired) {
        if (retired || Double.isNaN(baseline)) return;
        tombstones.add(new QuietTombstone(risk.conn, entity, risk.explosionRevision, baseline,
            serverTick));
    }

    private boolean awaitsHealthEvidence(Risk risk) {
        if (!risk.selfRetired && risk.selfBound > 0.0
            && risk.healthAfterExplosion.contains(risk.selfEntity)) {
            return true;
        }
        for (Map.Entry<EntityKey, Double> entry : risk.friendBounds.entrySet()) {
            if (!risk.retiredFriends.contains(entry.getKey()) && entry.getValue() > 0.0
                && risk.healthAfterExplosion.contains(entry.getKey())) {
                return true;
            }
        }
        return false;
    }

    private void pruneTombstones() {
        tombstones.removeIf(tombstone ->
            serverTick - tombstone.retireTick > TOMBSTONE_LIFETIME_TICKS);
    }

    private void terminate(Risk risk) {
        risks.remove(risk.id);
        terminalHistory.put(risk.id, State.RETIRED);
        while (terminalHistory.size() > MAX_TERMINAL_HISTORY) {
            Iterator<RiskId> iterator = terminalHistory.keySet().iterator();
            iterator.next();
            iterator.remove();
        }
    }

    private static boolean allComponentsRetired(Risk risk) {
        return risk.selfRetired && risk.retiredFriends.containsAll(risk.friendBounds.keySet());
    }

    private static GroupedBound.Member groupedMember(Risk risk, double bound) {
        // No proof data is owned by the budget, so every obligation flag stays false and grouping falls
        // back to the plain sum even when the feature is enabled.
        return new GroupedBound.Member(bound, risk.arrival, risk.envelopeRevision,
            false, false, false, false, false);
    }

    private static Map<EntityKey, Double> friendBaselines(Map<EntityKey, EffectiveHealth> health,
                                                          Set<EntityKey> friends) {
        Map<EntityKey, Double> baselines = new LinkedHashMap<>();
        for (EntityKey friend : friends) {
            EffectiveHealth effectiveHealth = health.get(friend);
            baselines.put(friend, effectiveHealth == null ? Double.NaN : knownValue(effectiveHealth));
        }
        return baselines;
    }

    private static double knownValue(EffectiveHealth health) {
        if (health == null || health.cap() != Capability.KNOWN) return Double.NaN;
        double value = health.value();
        return Double.isFinite(value) && value >= 0.0 ? value : Double.NaN;
    }

    private static double healthLower(EffectiveHealth health) {
        if (health == null) return Double.NaN;
        if (health.cap() != Capability.KNOWN) return 0.0;
        double value = health.value();
        if (!validHealthValue(health.health()) || !validHealthValue(health.absorption())) {
            return Double.NaN;
        }
        return value;
    }

    private static boolean validHealthValue(double value) {
        return Double.isFinite(value) && value >= 0.0;
    }

    private static boolean validBound(double value) {
        return Double.isFinite(value) && value >= 0.0;
    }

    private static boolean validWidened(MitigatedDamage damage) {
        return damage != null && validBound(damage.beforeAbsorption());
    }

    private static boolean validSafety(Safety safety) {
        return safety != null
            && validBound(safety.minTarget())
            && validBound(safety.maxSelf())
            && validBound(safety.minRemaining())
            && validBound(safety.maxFriend());
    }

    private record HealthKey(ConnectionKey conn, EntityKey entity) {
    }

    private int pendingHealthCount(ConnectionKey conn) {
        if (conn == null) return 0;
        int count = 0;
        for (HealthKey key : pendingHealth.keySet()) {
            if (key.conn().equals(conn)) count++;
        }
        return count;
    }

    private static EntityKey migrate(EntityKey entity, ConnectionKey next) {
        if (entity == null) return null;
        return new EntityKey(next, entity.id(), entity.uuid());
    }

    private static final class PendingHealth {
        boolean healthKnown;
        boolean absorptionKnown;
        double health;
        double absorption;
        long healthRevision;
        long absorptionRevision;
        long revision;
    }

    private record QuietTombstone(ConnectionKey conn, EntityKey entity, long explosionRevision,
                                  double baseline, int retireTick) {
        boolean baselineKnown() {
            return !Double.isNaN(baseline);
        }
    }

    private static final class Risk {
        final RiskId id;
        final long intent;
        ConnectionKey conn;
        final ActionGeneration gen;
        EntityKey selfEntity;
        final Map<EntityKey, Double> friendBounds;
        final double selfKnownValue;
        final Map<EntityKey, Double> friendKnownValues;
        final ServerTickInterval arrival;
        EntityKey boundCrystal;
        Vec boundPosition;
        final Set<EntityKey> retiredFriends = new HashSet<>();
        final Set<EntityKey> healthAfterExplosion = new HashSet<>();
        State state = State.RESERVED;
        double selfBound;
        boolean selfRetired;
        boolean dimensionMigrated;
        long envelopeRevision;
        long sentRevision = -1;
        long explosionRevision = -1;
        int explosionAnchorTick = -1;

        Risk(RiskId id, long intent, ConnectionKey conn, ActionGeneration gen, EntityKey selfEntity,
             double selfBound, Map<EntityKey, Double> friendBounds, double selfKnownValue,
             Map<EntityKey, Double> friendKnownValues, ServerTickInterval arrival,
             EntityKey boundCrystal, Vec boundPosition, long envelopeRevision) {
            this.id = id;
            this.intent = intent;
            this.conn = conn;
            this.gen = gen;
            this.selfEntity = selfEntity;
            this.selfBound = selfBound;
            this.friendBounds = new LinkedHashMap<>(friendBounds);
            this.selfKnownValue = selfKnownValue;
            this.friendKnownValues = new LinkedHashMap<>(friendKnownValues);
            this.arrival = arrival;
            this.boundCrystal = boundCrystal;
            this.boundPosition = boundPosition;
            this.envelopeRevision = envelopeRevision;
        }
    }
}
