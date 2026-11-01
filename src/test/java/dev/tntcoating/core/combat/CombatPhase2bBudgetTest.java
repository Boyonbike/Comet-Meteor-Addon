/*
 * Original Comet implementation by Boyonabike.
 * Independent: written from the pinned 26.2 artifact and Meteor build 35 behaviour; no third-party code.
 * SPDX-License-Identifier: CC0-1.0
 */

package dev.tntcoating.core.combat;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** Phase 2b carry-overs D, G, 11, 13 and 14: retirement evidence hardening. */
class CombatPhase2bBudgetTest {
    private CombatRiskBudget budget;
    private RiskId id;

    @BeforeEach
    void setUp() {
        budget = RiskTestSupport.conservative();
    }

    private EntityKey entityOn(ConnectionKey conn, EntityKey entity) {
        return new EntityKey(conn, entity.id(), entity.uuid());
    }

    @Test
    void unattributedExplosionAtBoundPositionDoesNotArmRuleOne() {
        id = RiskTestSupport.sent(budget, 1L);
        Observation unattributed = new Observation(RiskTestSupport.CONN, 1L, 4L, 1L, 0,
            ObservationKind.EXPLOSION, null, null, RiskTestSupport.CRYSTAL_POSITION, null,
            new ObservationPayload.Explosion(6.0));
        budget.observe(unattributed);
        budget.observe(RiskTestSupport.health(RiskTestSupport.SELF, 10.0, 0.0, 5L, 2L));

        assertEquals(CombatRiskBudget.State.SENT, budget.state(id));
        assertEquals(1, budget.outstanding());
    }

    @Test
    void explosionAttributedToADifferentCrystalDoesNotArmRuleOne() {
        id = RiskTestSupport.sent(budget, 1L);
        Observation otherCrystal = RiskTestSupport.explosionAt(RiskTestSupport.CONN,
            RiskTestSupport.CRYSTAL_TWO, RiskTestSupport.CRYSTAL_POSITION, 4L, 1L);
        budget.observe(otherCrystal);
        budget.observe(RiskTestSupport.health(RiskTestSupport.SELF, 10.0, 0.0, 5L, 2L));

        assertEquals(CombatRiskBudget.State.SENT, budget.state(id));
    }

    @Test
    void entityAttributedExplosionRetiresOnlyWithCausallyLaterHealth() {
        id = RiskTestSupport.sent(budget, 1L);
        budget.observe(RiskTestSupport.explosion(RiskTestSupport.CRYSTAL_POSITION, 4L, 1L));
        budget.observe(RiskTestSupport.health(RiskTestSupport.SELF, 10.0, 0.0, 5L, 2L));

        assertEquals(CombatRiskBudget.State.RETIRED, budget.state(id));
        assertEquals(0, budget.outstanding());
    }

    @Test
    void dimensionChangeKeepsSentSelfDebtOnTheNewEpoch() {
        id = RiskTestSupport.sent(budget, 1L);
        budget.dimensionChanged(RiskTestSupport.CONN, RiskTestSupport.CONN_TWO);

        EntityKey newSelf = entityOn(RiskTestSupport.CONN_TWO, RiskTestSupport.SELF);
        assertEquals(1, budget.debt(newSelf).count());
        assertEquals(RiskTestSupport.SELF_BOUND, budget.debt(newSelf).bound(), 1e-9);
        assertEquals(0, budget.debt(RiskTestSupport.SELF).count());
        assertEquals(1, budget.outstanding());
        assertTrue(budget.migratedFromPriorDimension(RiskTestSupport.CONN_TWO));
        assertEquals(1, budget.neverRetirableComponents());
    }

    @Test
    void dimensionChangePreservesFriendDebt() {
        double friendBound = 2.0;
        id = RiskTestSupport.sentWithFriend(budget, 1L, friendBound,
            RiskTestSupport.known(18.0, 0.0, 0L));
        budget.dimensionChanged(RiskTestSupport.CONN, RiskTestSupport.CONN_TWO);

        EntityKey newFriend = entityOn(RiskTestSupport.CONN_TWO, RiskTestSupport.FRIEND);
        assertEquals(1, budget.debt(newFriend).count());
        assertEquals(friendBound, budget.debt(newFriend).bound(), 1e-9);
        assertEquals(0, budget.debt(RiskTestSupport.FRIEND).count());
    }

    @Test
    void oldDimensionHazardsCannotClearMigratedRisk() {
        id = RiskTestSupport.sent(budget, 1L);
        budget.dimensionChanged(RiskTestSupport.CONN, RiskTestSupport.CONN_TWO);

        // New-epoch explosion at the same coordinates, then coherent health: must not retire migrated debt.
        EntityKey newCrystal = entityOn(RiskTestSupport.CONN_TWO, RiskTestSupport.CRYSTAL);
        EntityKey newSelf = entityOn(RiskTestSupport.CONN_TWO, RiskTestSupport.SELF);
        budget.observe(new Observation(RiskTestSupport.CONN_TWO, 1L, 5L, 1L, 0,
            ObservationKind.EXPLOSION, newCrystal, null, RiskTestSupport.CRYSTAL_POSITION, null,
            new ObservationPayload.Explosion(6.0)));
        budget.observe(new Observation(RiskTestSupport.CONN_TWO, 2L, 6L, 2L, 0,
            ObservationKind.HEALTH, newSelf, null, null, null,
            new ObservationPayload.Health(10.0, 0.0, 20, 5.0)));

        assertEquals(CombatRiskBudget.State.SENT, budget.state(id));
        assertTrue(budget.debt(newSelf).bound() > 0.0);
    }

    @Test
    void explosionBoundaryIsTightAtPointFourNineMetres() {
        id = RiskTestSupport.sent(budget, 1L);
        budget.observe(RiskTestSupport.explosion(
            new Vec(0.5 + 0.49, 64.0, 0.5), 4L, 1L));
        budget.observe(RiskTestSupport.health(RiskTestSupport.SELF, 10.0, 0.0, 5L, 2L));
        assertEquals(CombatRiskBudget.State.RETIRED, budget.state(id));
    }

    @Test
    void explosionBoundaryRejectsPointFiveOneMetres() {
        id = RiskTestSupport.sent(budget, 1L);
        budget.observe(RiskTestSupport.explosion(
            new Vec(0.5 + 0.51, 64.0, 0.5), 4L, 1L));
        budget.observe(RiskTestSupport.health(RiskTestSupport.SELF, 10.0, 0.0, 5L, 2L));
        assertEquals(CombatRiskBudget.State.SENT, budget.state(id));
    }

    @Test
    void staleHealthRevisionCannotRetireAfterNewerSnapshot() {
        id = RiskTestSupport.sent(budget, 1L);
        // A newer self snapshot is applied first (no explosion yet, so nothing retires), then an older
        // explosion and an older health packet arrive out of order. The stale health must be ignored.
        budget.observe(RiskTestSupport.health(RiskTestSupport.SELF, 10.0, 0.0, 9L, 1L));
        budget.observe(RiskTestSupport.explosion(RiskTestSupport.CRYSTAL_POSITION, 5L, 2L));
        budget.observe(RiskTestSupport.health(RiskTestSupport.SELF, 10.0, 0.0, 7L, 3L));

        assertEquals(CombatRiskBudget.State.SENT, budget.state(id));
    }

    @Test
    void healthAppliedBeforeThePlacementRevisionCannotRetire() {
        id = RiskTestSupport.reserveSimple(budget, 1L);
        budget.dispatching(id);
        long placementRevision = 10L;
        budget.mayHaveLeft(id, placementRevision);
        // An explosion revision lower than the placement revision can only be old-dimension bookkeeping;
        // a health revision just above it still predates the placement and must not retire the risk.
        budget.observe(RiskTestSupport.explosion(RiskTestSupport.CRYSTAL_POSITION, 8L, 1L));
        budget.observe(RiskTestSupport.health(RiskTestSupport.SELF, 10.0, 0.0, 9L, 2L));

        assertEquals(CombatRiskBudget.State.SENT, budget.state(id));
    }

    @Test
    void pendingHealthPairsPerConnectionAreBoundedAndSuspend() {
        long entityId = 100;
        for (int i = 0; i < CombatRiskBudget.MAX_PENDING_HEALTH_PAIRS_PER_CONNECTION; i++) {
            EntityKey who = new EntityKey(RiskTestSupport.CONN, (int) entityId, uuid(entityId));
            budget.observe(RiskTestSupport.entityDataHealth(who, 20.0, 5L, entityId++));
        }
        assertEquals(CombatRiskBudget.MAX_PENDING_HEALTH_PAIRS_PER_CONNECTION,
            budget.pendingHealthPairs());
        assertTrue(budget.suspended());

        EntityKey overflow = new EntityKey(RiskTestSupport.CONN, (int) entityId, uuid(entityId));
        budget.observe(RiskTestSupport.entityDataHealth(overflow, 20.0, 5L, entityId));
        assertEquals(CombatRiskBudget.MAX_PENDING_HEALTH_PAIRS_PER_CONNECTION,
            budget.pendingHealthPairs());
        assertTrue(budget.suspended());
    }

    @Test
    void coherentPairReleasesOnePendingSlotButStaysSuspendedWhileOthersRemain() {
        EntityKey first = new EntityKey(RiskTestSupport.CONN, 200, uuid(200));
        budget.observe(RiskTestSupport.entityDataHealth(first, 20.0, 5L, 1L));
        budget.observe(RiskTestSupport.entityDataHealth(
            new EntityKey(RiskTestSupport.CONN, 201, uuid(201)), 20.0, 5L, 2L));
        assertEquals(2, budget.pendingHealthPairs());
        assertTrue(budget.suspended());

        budget.observe(RiskTestSupport.entityDataAbsorption(first, 0.0, 5L, 3L));
        assertEquals(1, budget.pendingHealthPairs());
        assertTrue(budget.suspended());

        budget.observe(RiskTestSupport.entityDataAbsorption(
            new EntityKey(RiskTestSupport.CONN, 201, uuid(201)), 0.0, 5L, 4L));
        assertEquals(0, budget.pendingHealthPairs());
        assertFalse(budget.suspended());
    }

    @Test
    void sentRiskWithoutBoundEvidenceIsCountedAndNeverGarbageCollected() {
        id = RiskTestSupport.reserveNoCrystal(budget, 1L);
        budget.dispatching(id);
        budget.mayHaveLeft(id);

        budget.observe(RiskTestSupport.health(RiskTestSupport.SELF, 10.0, 0.0, 5L, 1L));

        assertEquals(CombatRiskBudget.State.SENT, budget.state(id));
        assertEquals(1, budget.outstanding());
        assertEquals(1, budget.neverRetirableComponents());
        assertEquals(RiskTestSupport.SELF_BOUND,
            budget.debt(RiskTestSupport.SELF).bound(), 1e-9);
    }

    private static UUID uuid(long value) {
        return UUID.nameUUIDFromBytes(("phase2b-" + value).getBytes(StandardCharsets.UTF_8));
    }
}
