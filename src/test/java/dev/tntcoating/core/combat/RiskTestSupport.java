/*
 * Original Comet implementation by Boyonabike.
 * Independent: written from the pinned 26.2 artifact and Meteor build 35 behaviour; no third-party code.
 * SPDX-License-Identifier: CC0-1.0
 */

package dev.tntcoating.core.combat;

import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/** Shared deterministic fixtures for the Phase 2a risk tests. */
final class RiskTestSupport {
    static final ConnectionKey CONN = new ConnectionKey(41);
    static final ConnectionKey CONN_TWO = new ConnectionKey(42);
    static final ActionGeneration GEN = new ActionGeneration(9);
    static final EntityKey SELF = entity(1, "self");
    static final EntityKey FRIEND = entity(2, "friend");
    static final EntityKey CRYSTAL = entity(3, "crystal");
    static final EntityKey FRIEND_TWO = entity(4, "friend-two");
    static final EntityKey CRYSTAL_TWO = entity(5, "crystal-two");
    static final Vec CRYSTAL_POSITION = new Vec(0.5, 64.0, 0.5);
    static final Vec CRYSTAL_TWO_POSITION = new Vec(20.5, 64.0, 20.5);
    static final Vec OTHER_POSITION = new Vec(10.5, 64.0, 0.5);
    static final Safety SAFETY = new Safety(4.0, 6.0, 2.0, 2.0);
    static final double SELF_BOUND = 6.0;

    private RiskTestSupport() {
    }

    static EntityKey entity(int id, String name) {
        return new EntityKey(CONN, id,
            UUID.nameUUIDFromBytes(name.getBytes(StandardCharsets.UTF_8)));
    }

    static EffectiveHealth known(double health, double absorption, long revision) {
        return new EffectiveHealth(health, absorption, Capability.KNOWN, revision);
    }

    static EffectiveHealth unknown() {
        return new EffectiveHealth(0.0, 0.0, Capability.UNKNOWN, 0L);
    }

    static EffectiveHealth censored() {
        return new EffectiveHealth(0.0, 0.0, Capability.CENSORED, 0L);
    }

    static ExposureBound exposure(double selfUpper) {
        return exposure(selfUpper, Map.of());
    }

    static ExposureBound exposure(double selfUpper, Map<EntityKey, Double> friends) {
        return exposure(selfUpper, friends, 1L);
    }

    static ExposureBound exposure(double selfUpper, long envelopeRevision) {
        return exposure(selfUpper, Map.of(), envelopeRevision);
    }

    static ExposureBound exposure(double selfUpper, Map<EntityKey, Double> friends,
                                  long envelopeRevision) {
        Map<EntityKey, MitigatedDamage> bounds = new LinkedHashMap<>();
        for (Map.Entry<EntityKey, Double> entry : friends.entrySet()) {
            bounds.put(entry.getKey(), new MitigatedDamage(entry.getValue()));
        }
        return new ExposureBound(new MitigatedDamage(selfUpper), bounds,
            new MitigationEnvelope(envelopeRevision));
    }

    static SafetyState state(EffectiveHealth self) {
        return new SafetyState(CONN, GEN, SELF, self, Map.of(), SAFETY);
    }

    static SafetyState state(EffectiveHealth self, Map<EntityKey, EffectiveHealth> friends) {
        return new SafetyState(CONN, GEN, SELF, self, friends, SAFETY);
    }

    static SafetyState healthySelf() {
        return state(known(20.0, 0.0, 0L));
    }

    static ServerTickInterval arrival() {
        return new ServerTickInterval(10L, 12L);
    }

    static Observation explosion(Vec position, long appliedRevision, long receiptSeq) {
        return explosionAt(CONN, CRYSTAL, position, appliedRevision, receiptSeq);
    }

    static Observation explosionAt(ConnectionKey conn, EntityKey crystal, Vec position,
                                   long appliedRevision, long receiptSeq) {
        return new Observation(conn, receiptSeq, appliedRevision, receiptSeq, 0,
            ObservationKind.EXPLOSION, crystal, null, position, null,
            new ObservationPayload.Explosion(6.0));
    }

    static Observation health(EntityKey who, double health, double absorption,
                              long appliedRevision, long receiptSeq) {
        return healthAt(CONN, who, health, absorption, appliedRevision, receiptSeq);
    }

    static Observation healthAt(ConnectionKey conn, EntityKey who, double health,
                                double absorption, long appliedRevision, long receiptSeq) {
        return new Observation(conn, receiptSeq, appliedRevision, receiptSeq, 0,
            ObservationKind.HEALTH, who, null, null, null,
            new ObservationPayload.Health(health, absorption, 20, 5.0));
    }

    static Observation entityDataHealth(EntityKey who, double health,
                                        long appliedRevision, long receiptSeq) {
        return new Observation(CONN, receiptSeq, appliedRevision, receiptSeq, 0,
            ObservationKind.ENTITY_DATA, who, null, null, null,
            new ObservationPayload.EntityData(true, health, false, 0.0, "player"));
    }

    static Observation entityDataAbsorption(EntityKey who, double absorption,
                                            long appliedRevision, long receiptSeq) {
        return new Observation(CONN, receiptSeq, appliedRevision, receiptSeq, 0,
            ObservationKind.ENTITY_DATA, who, null, null, null,
            new ObservationPayload.EntityData(false, 0.0, true, absorption, "player"));
    }

    static Observation simple(ConnectionKey conn, ObservationKind kind, EntityKey who, long receiptSeq,
                             ObservationPayload payload) {
        return new Observation(conn, receiptSeq, receiptSeq, receiptSeq, 0, kind, who, null, null,
            null, payload);
    }

    static Observation trackingRemoval(EntityKey who, long receiptSeq) {
        return new Observation(CONN, receiptSeq, receiptSeq, receiptSeq, 0,
            ObservationKind.REMOVED, who, null, null, null,
            new ObservationPayload.Removed("untracked", true));
    }

    static RiskId reserve(CombatRiskBudget budget, long intent, ExposureBound upper,
                          SafetyState state) {
        return reserveAt(budget, intent, upper, state, CRYSTAL, CRYSTAL_POSITION);
    }

    static RiskId reserveAt(CombatRiskBudget budget, long intent, ExposureBound upper,
                            SafetyState state, EntityKey crystal, Vec position) {
        return budget.reserve(intent, upper, arrival(), state, crystal, position)
            .orElseThrow(() -> new AssertionError("reservation unexpectedly refused"));
    }

    static SafetyState stateFor(ConnectionKey conn, ActionGeneration gen, EntityKey self,
                                EffectiveHealth selfHealth,
                                Map<EntityKey, EffectiveHealth> friends) {
        return new SafetyState(conn, gen, self, selfHealth, friends, SAFETY);
    }

    static RiskId reserveSimple(CombatRiskBudget budget, long intent) {
        return reserve(budget, intent, exposure(SELF_BOUND), healthySelf());
    }

    static RiskId reserveNoCrystal(CombatRiskBudget budget, long intent) {
        return budget.reserve(intent, exposure(SELF_BOUND), arrival(), healthySelf())
            .orElseThrow(() -> new AssertionError("reservation unexpectedly refused"));
    }

    static RiskId sent(CombatRiskBudget budget, long intent) {
        RiskId id = reserveSimple(budget, intent);
        budget.dispatching(id);
        budget.mayHaveLeft(id);
        return id;
    }

    static RiskId sentWithExplosion(CombatRiskBudget budget, long intent, long explosionRevision) {
        RiskId id = sent(budget, intent);
        budget.observe(explosion(CRYSTAL_POSITION, explosionRevision, explosionRevision));
        return id;
    }

    static RiskId sentWithFriend(CombatRiskBudget budget, long intent, double friendBound,
                                 EffectiveHealth friendHealth) {
        RiskId id = reserve(budget, intent, exposure(0.0, Map.of(FRIEND, friendBound)),
            state(known(20.0, 0.0, 0L), Map.of(FRIEND, friendHealth)));
        budget.dispatching(id);
        budget.mayHaveLeft(id);
        return id;
    }

    /** Identity widener: proves the plumbing without changing the bound. */
    static CombatRiskBudget conservative() {
        return new CombatRiskBudget((envelope, current) -> current);
    }

    static CombatRiskBudget doubling() {
        return new CombatRiskBudget((envelope, current) ->
            new MitigatedDamage(current.beforeAbsorption() * 2.0));
    }

    static CombatRiskBudget budget(GroupedBound grouped, AnarchyQuietWindow window) {
        return new CombatRiskBudget((envelope, current) -> current, grouped, window);
    }

    static Intent intent(long id, long plan, Cell cell, int expiresTick, Set<Cell> blockLocks,
                         Set<EntityKey> entityLocks, Set<Intent> dependsOn) {
        return new Intent(id, plan, Strategy.DIRECT, Action.PLACE_CRYSTAL, cell, null,
            blockLocks, entityLocks, dependsOn, expiresTick);
    }

    static RiskRequest request(ConnectionKey conn, ActionGeneration gen, int tick, int packetCost,
                               boolean needsSlot, boolean needsRotation, boolean needsMiningSlot) {
        return new RiskRequest(exposure(SELF_BOUND), arrival(),
            new SafetyState(conn, gen, new EntityKey(conn, 1, null), known(20.0, 0.0, 0L),
                Map.of(), SAFETY),
            CRYSTAL, CRYSTAL_POSITION, packetCost, tick, needsSlot, needsRotation, needsMiningSlot);
    }
}
