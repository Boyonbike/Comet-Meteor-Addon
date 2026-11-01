/*
 * Original Comet implementation by Boyonabike.
 * Independent: written from the pinned 26.2 artifact and Meteor build 35 behaviour; no third-party code.
 * SPDX-License-Identifier: CC0-1.0
 */

package dev.tntcoating.core.combat;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class CombatRiskBudgetRetirementTest {
    @Test
    void ruleOneExplosionThenCoherentHealthRetiresTheSelfComponent() {
        CombatRiskBudget budget = RiskTestSupport.conservative();
        RiskId id = RiskTestSupport.sentWithExplosion(budget, 1L, 3L);
        budget.observe(RiskTestSupport.health(RiskTestSupport.SELF, 20.0, 0.0, 4L, 2L));
        assertEquals(CombatRiskBudget.State.RETIRED, budget.state(id));
        assertEquals(0, budget.outstanding());
        assertEquals(Debt.zero(), budget.debt(RiskTestSupport.SELF));
    }

    @Test
    void healthBeforeTheExplosionDoesNotRetire() {
        CombatRiskBudget budget = RiskTestSupport.conservative();
        RiskId id = RiskTestSupport.sent(budget, 1L);
        budget.observe(RiskTestSupport.health(RiskTestSupport.SELF, 20.0, 0.0, 3L, 1L));
        budget.observe(RiskTestSupport.explosion(RiskTestSupport.CRYSTAL_POSITION, 3L, 2L));
        assertEquals(CombatRiskBudget.State.SENT, budget.state(id));
        assertEquals(1, budget.debt(RiskTestSupport.SELF).count());
    }

    @Test
    void healthAtTheSameAppliedRevisionDoesNotRetire() {
        CombatRiskBudget budget = RiskTestSupport.conservative();
        RiskId id = RiskTestSupport.sentWithExplosion(budget, 1L, 3L);
        budget.observe(RiskTestSupport.health(RiskTestSupport.SELF, 20.0, 0.0, 3L, 2L));
        assertEquals(CombatRiskBudget.State.SENT, budget.state(id));
    }

    @Test
    void explosionOutsideTheAssociationRangeDoesNotArmRetirement() {
        CombatRiskBudget budget = RiskTestSupport.conservative();
        RiskId id = RiskTestSupport.sent(budget, 1L);
        budget.observe(RiskTestSupport.explosion(RiskTestSupport.OTHER_POSITION, 3L, 1L));
        budget.observe(RiskTestSupport.health(RiskTestSupport.SELF, 20.0, 0.0, 4L, 2L));
        assertEquals(CombatRiskBudget.State.SENT, budget.state(id));
    }

    @Test
    void explosionRequiresTheBoundPosition() {
        CombatRiskBudget budget = RiskTestSupport.conservative();
        RiskId id = RiskTestSupport.reserve(budget, 1L, RiskTestSupport.exposure(6.0),
            RiskTestSupport.healthySelf());
        budget.dispatching(id);
        budget.mayHaveLeft(id);
        budget.observe(RiskTestSupport.explosion(RiskTestSupport.CRYSTAL_TWO_POSITION, 3L, 1L));
        budget.observe(RiskTestSupport.health(RiskTestSupport.SELF, 20.0, 0.0, 4L, 2L));
        // CRYSTAL_TWO is 20 blocks away from the bound CRYSTAL position, so no association.
        assertEquals(1, budget.outstanding());
    }

    @Test
    void explosionOnAnotherConnectionDoesNotAssociate() {
        CombatRiskBudget budget = RiskTestSupport.conservative();
        RiskId id = RiskTestSupport.sent(budget, 1L);
        budget.observe(RiskTestSupport.explosionAt(RiskTestSupport.CONN_TWO,
            RiskTestSupport.CRYSTAL, RiskTestSupport.CRYSTAL_POSITION, 3L, 1L));
        budget.observe(RiskTestSupport.health(RiskTestSupport.SELF, 20.0, 0.0, 4L, 2L));
        assertEquals(CombatRiskBudget.State.SENT, budget.state(id));
    }

    @Test
    void oneTransactionRetiresExactlyTheRisksWhoseExplosionPrecedesIt() {
        CombatRiskBudget budget = RiskTestSupport.conservative();
        SafetyState state = RiskTestSupport.healthySelf();
        RiskId first = RiskTestSupport.reserveAt(budget, 1L, RiskTestSupport.exposure(3.0), state,
            RiskTestSupport.CRYSTAL, RiskTestSupport.CRYSTAL_POSITION);
        budget.mayHaveLeft(first);
        RiskId second = RiskTestSupport.reserveAt(budget, 2L, RiskTestSupport.exposure(3.0), state,
            RiskTestSupport.CRYSTAL_TWO, RiskTestSupport.CRYSTAL_TWO_POSITION);
        budget.mayHaveLeft(second);

        budget.observe(RiskTestSupport.explosion(RiskTestSupport.CRYSTAL_POSITION, 3L, 1L));
        budget.observe(RiskTestSupport.explosionAt(RiskTestSupport.CONN,
            RiskTestSupport.CRYSTAL_TWO, RiskTestSupport.CRYSTAL_TWO_POSITION, 6L, 2L));
        budget.observe(RiskTestSupport.health(RiskTestSupport.SELF, 20.0, 0.0, 4L, 3L));
        assertEquals(CombatRiskBudget.State.RETIRED, budget.state(first));
        assertEquals(CombatRiskBudget.State.SENT, budget.state(second));

        budget.observe(RiskTestSupport.health(RiskTestSupport.SELF, 20.0, 0.0, 7L, 4L));
        assertEquals(CombatRiskBudget.State.RETIRED, budget.state(second));
        assertEquals(0, budget.outstanding());
    }

    @Test
    void unrelatedLaterHealingDoesNotDoubleCount() {
        CombatRiskBudget budget = RiskTestSupport.conservative();
        RiskId first = RiskTestSupport.sentWithExplosion(budget, 1L, 3L);
        RiskId second = RiskTestSupport.sentWithExplosion(budget, 2L, 3L);
        budget.observe(RiskTestSupport.health(RiskTestSupport.SELF, 20.0, 0.0, 4L, 3L));
        assertEquals(CombatRiskBudget.State.RETIRED, budget.state(first));
        assertEquals(CombatRiskBudget.State.RETIRED, budget.state(second));
        budget.observe(RiskTestSupport.health(RiskTestSupport.SELF, 20.0, 0.0, 5L, 4L));
        assertEquals(0, budget.outstanding());
        assertEquals(Debt.zero(), budget.debt(RiskTestSupport.SELF));
    }

    @Test
    void selfAndFriendComponentsRetireIndependently() {
        CombatRiskBudget budget = RiskTestSupport.conservative();
        SafetyState state = RiskTestSupport.state(RiskTestSupport.known(20.0, 0.0, 0L),
            Map.of(RiskTestSupport.FRIEND, RiskTestSupport.known(10.0, 0.0, 0L)));
        RiskId id = RiskTestSupport.reserve(budget, 1L,
            RiskTestSupport.exposure(3.0, Map.of(RiskTestSupport.FRIEND, 1.0)), state);
        budget.mayHaveLeft(id);
        budget.observe(RiskTestSupport.explosion(RiskTestSupport.CRYSTAL_POSITION, 3L, 1L));

        budget.observe(RiskTestSupport.health(RiskTestSupport.SELF, 20.0, 0.0, 4L, 2L));
        assertEquals(0, budget.debt(RiskTestSupport.SELF).count());
        assertEquals(1, budget.debt(RiskTestSupport.FRIEND).count());
        assertEquals(CombatRiskBudget.State.SENT, budget.state(id));

        budget.observe(RiskTestSupport.health(RiskTestSupport.FRIEND, 10.0, 0.0, 5L, 3L));
        assertEquals(CombatRiskBudget.State.RETIRED, budget.state(id));
    }

    @Test
    void zeroBoundComponentsNeverRetireByRuleOne() {
        CombatRiskBudget budget = RiskTestSupport.conservative();
        SafetyState state = RiskTestSupport.state(RiskTestSupport.known(20.0, 0.0, 0L),
            Map.of(RiskTestSupport.FRIEND, RiskTestSupport.known(10.0, 0.0, 0L)));
        RiskId id = RiskTestSupport.reserve(budget, 1L,
            RiskTestSupport.exposure(0.0, Map.of(RiskTestSupport.FRIEND, 1.0)), state);
        budget.mayHaveLeft(id);
        budget.observe(RiskTestSupport.explosion(RiskTestSupport.CRYSTAL_POSITION, 3L, 1L));
        budget.observe(RiskTestSupport.health(RiskTestSupport.SELF, 20.0, 0.0, 4L, 2L));
        assertEquals(CombatRiskBudget.State.SENT, budget.state(id));
        assertEquals(1, budget.debt(RiskTestSupport.SELF).count());
        assertEquals(0.0, budget.debt(RiskTestSupport.SELF).bound());

        budget.observe(RiskTestSupport.health(RiskTestSupport.FRIEND, 10.0, 0.0, 5L, 3L));
        // The friend component retired, but the zero self component never retires: the risk stays debt.
        assertEquals(CombatRiskBudget.State.SENT, budget.state(id));
        assertEquals(0, budget.debt(RiskTestSupport.FRIEND).count());
        // A zero-bound component is still counted as debt, but adds nothing to the bound.
        assertEquals(1, budget.debt(RiskTestSupport.SELF).count());
        assertEquals(0.0, budget.current(RiskTestSupport.SELF).value());
    }

    @Test
    void absorptionOnlyUpdateIsHeldAndNeverRetires() {
        CombatRiskBudget budget = RiskTestSupport.conservative();
        RiskId id = RiskTestSupport.sentWithExplosion(budget, 1L, 3L);
        budget.observe(RiskTestSupport.entityDataAbsorption(RiskTestSupport.SELF, 4.0, 4L, 2L));
        assertTrue(budget.suspended());
        assertEquals(CombatRiskBudget.State.SENT, budget.state(id));
    }

    @Test
    void splitHealthThenAbsorptionRetiresOnceCoherent() {
        CombatRiskBudget budget = RiskTestSupport.conservative();
        RiskId id = RiskTestSupport.sentWithExplosion(budget, 1L, 3L);
        budget.observe(RiskTestSupport.entityDataHealth(RiskTestSupport.SELF, 20.0, 4L, 2L));
        assertEquals(CombatRiskBudget.State.SENT, budget.state(id));
        assertTrue(budget.suspended());
        budget.observe(RiskTestSupport.entityDataAbsorption(RiskTestSupport.SELF, 0.0, 4L, 3L));
        assertEquals(CombatRiskBudget.State.RETIRED, budget.state(id));
        assertFalse(budget.suspended());
    }

    @Test
    void splitAbsorptionThenHealthRetiresOnceCoherent() {
        CombatRiskBudget budget = RiskTestSupport.conservative();
        RiskId id = RiskTestSupport.sentWithExplosion(budget, 1L, 3L);
        budget.observe(RiskTestSupport.entityDataAbsorption(RiskTestSupport.SELF, 0.0, 4L, 2L));
        assertEquals(CombatRiskBudget.State.SENT, budget.state(id));
        budget.observe(RiskTestSupport.entityDataHealth(RiskTestSupport.SELF, 20.0, 4L, 3L));
        assertEquals(CombatRiskBudget.State.RETIRED, budget.state(id));
    }

    @Test
    void splitUpdatesAtDifferentRevisionsDoNotRetire() {
        CombatRiskBudget budget = RiskTestSupport.conservative();
        RiskId id = RiskTestSupport.sentWithExplosion(budget, 1L, 3L);
        budget.observe(RiskTestSupport.entityDataAbsorption(RiskTestSupport.SELF, 0.0, 4L, 2L));
        assertEquals(CombatRiskBudget.State.SENT, budget.state(id));
        assertTrue(budget.suspended());
        // Different applied revisions may belong to different events, so they must not be merged.
        budget.observe(RiskTestSupport.entityDataHealth(RiskTestSupport.SELF, 20.0, 5L, 3L));
        assertEquals(CombatRiskBudget.State.SENT, budget.state(id));
        assertTrue(budget.suspended());
        budget.observe(RiskTestSupport.health(RiskTestSupport.SELF, 20.0, 0.0, 6L, 4L));
        assertEquals(CombatRiskBudget.State.RETIRED, budget.state(id));
        assertFalse(budget.suspended());
    }

    @Test
    void adjacentExplosionDoesNotAssociate() {
        CombatRiskBudget budget = RiskTestSupport.conservative();
        RiskId id = RiskTestSupport.sent(budget, 1L);
        budget.observe(RiskTestSupport.explosion(
            RiskTestSupport.CRYSTAL_POSITION.add(1.0, 0.0, 0.0), 4L, 2L));
        budget.observe(RiskTestSupport.health(RiskTestSupport.SELF, 20.0, 0.0, 5L, 3L));
        assertEquals(CombatRiskBudget.State.SENT, budget.state(id));
    }

    @Test
    void explosionAttributedToAnotherCrystalDoesNotAssociate() {
        CombatRiskBudget budget = RiskTestSupport.conservative();
        RiskId id = RiskTestSupport.sent(budget, 1L);
        budget.observe(RiskTestSupport.explosionAt(RiskTestSupport.CONN,
            RiskTestSupport.CRYSTAL_TWO, RiskTestSupport.CRYSTAL_POSITION, 4L, 2L));
        budget.observe(RiskTestSupport.health(RiskTestSupport.SELF, 20.0, 0.0, 5L, 3L));
        assertEquals(CombatRiskBudget.State.SENT, budget.state(id));
    }

    @Test
    void quietWindowDoesNotRetireWhileSuspended() {
        CombatRiskBudget budget = RiskTestSupport.budget(new GroupedBound(false),
            AnarchyQuietWindow.statistical(40L, ignored -> { }));
        budget.tick(3);
        RiskId id = RiskTestSupport.sent(budget, 1L);
        budget.observe(RiskTestSupport.explosion(RiskTestSupport.CRYSTAL_POSITION, 5L, 1L));
        budget.suspend(SuspensionCause.QUEUE_OVERFLOW);
        budget.tick(100);
        assertEquals(CombatRiskBudget.State.SENT, budget.state(id));
        budget.resumeAfterReconciliation();
        budget.tick(100);
        assertEquals(CombatRiskBudget.State.RETIRED, budget.state(id));
    }

    @Test
    void coherentHealthForAnotherEntityDoesNotRetireTheSelfComponent() {
        CombatRiskBudget budget = RiskTestSupport.conservative();
        RiskId id = RiskTestSupport.sentWithExplosion(budget, 1L, 3L);
        budget.observe(RiskTestSupport.health(RiskTestSupport.FRIEND, 10.0, 0.0, 4L, 2L));
        assertEquals(CombatRiskBudget.State.SENT, budget.state(id));
    }

    @Test
    void trackingRemovalIsNotDetonationEvidence() {
        CombatRiskBudget budget = RiskTestSupport.conservative();
        RiskId id = RiskTestSupport.sentWithExplosion(budget, 1L, 3L);
        budget.observe(RiskTestSupport.trackingRemoval(RiskTestSupport.CRYSTAL, 4L));
        budget.observe(RiskTestSupport.simple(RiskTestSupport.CONN,
            ObservationKind.REMOVED, RiskTestSupport.CRYSTAL, 5L,
            new ObservationPayload.Removed("despawn", false)));
        budget.observe(RiskTestSupport.simple(RiskTestSupport.CONN,
            ObservationKind.TRACKING_REMOVED, RiskTestSupport.CRYSTAL, 6L,
            new ObservationPayload.None()));
        assertEquals(CombatRiskBudget.State.SENT, budget.state(id));
        assertEquals(1, budget.debt(RiskTestSupport.SELF).count());
    }

    @Test
    void rejectedAttackWithoutTheProcessingBarrierRemainsDebt() {
        CombatRiskBudget budget = RiskTestSupport.conservative();
        RiskId id = RiskTestSupport.sentWithExplosion(budget, 1L, 3L);
        budget.observe(RiskTestSupport.simple(RiskTestSupport.CONN,
            ObservationKind.DAMAGE_EVENT, RiskTestSupport.SELF, 4L,
            new ObservationPayload.DamageEvent("explosion", 0, 0)));
        budget.observe(RiskTestSupport.simple(RiskTestSupport.CONN,
            ObservationKind.TOTEM_POP, RiskTestSupport.SELF, 5L,
            new ObservationPayload.TotemPop()));
        budget.tick(1_000);
        assertFalse(ProcessingBarrier.satisfied());
        assertEquals(CombatRiskBudget.State.SENT, budget.state(id));
        assertEquals(1, budget.debt(RiskTestSupport.SELF).count());
    }

    @Test
    void debtCountsTowardTheMarginButZeroDebtDoesNotBlock() {
        CombatRiskBudget budget = RiskTestSupport.conservative();
        RiskId first = RiskTestSupport.sent(budget, 1L);
        RiskId zero = RiskTestSupport.reserve(budget, 2L, RiskTestSupport.exposure(0.0),
            RiskTestSupport.healthySelf());
        budget.mayHaveLeft(zero);
        assertEquals(2, budget.debt(RiskTestSupport.SELF).count());
        assertEquals(6.0, budget.current(RiskTestSupport.SELF).value());

        RiskId second = RiskTestSupport.sent(budget, 3L);
        assertNotNull(second);
        assertEquals(12.0, budget.current(RiskTestSupport.SELF).value());
        assertTrue(budget.reserve(4L, RiskTestSupport.exposure(6.0), RiskTestSupport.arrival(),
            RiskTestSupport.healthySelf()).isEmpty());
        assertEquals(CombatRiskBudget.State.SENT, budget.state(first));
    }

    @Test
    void deathAndRespawnClearOnlyTheSelfComponent() {
        CombatRiskBudget budget = RiskTestSupport.conservative();
        SafetyState state = RiskTestSupport.state(RiskTestSupport.known(20.0, 0.0, 0L),
            Map.of(RiskTestSupport.FRIEND, RiskTestSupport.known(10.0, 0.0, 0L)));
        RiskId id = RiskTestSupport.reserve(budget, 1L,
            RiskTestSupport.exposure(3.0, Map.of(RiskTestSupport.FRIEND, 1.0)), state);
        budget.mayHaveLeft(id);
        budget.observe(RiskTestSupport.explosion(RiskTestSupport.CRYSTAL_POSITION, 3L, 1L));

        budget.observe(RiskTestSupport.simple(RiskTestSupport.CONN,
            ObservationKind.DEATH, RiskTestSupport.SELF, 2L, new ObservationPayload.Death()));
        assertTrue(budget.suspended());
        assertEquals(1, budget.debt(RiskTestSupport.SELF).count());

        budget.observe(RiskTestSupport.simple(RiskTestSupport.CONN,
            ObservationKind.RESPAWN, RiskTestSupport.SELF, 3L,
            new ObservationPayload.Respawn("overworld", false)));
        assertEquals(0, budget.debt(RiskTestSupport.SELF).count());
        assertEquals(1, budget.debt(RiskTestSupport.FRIEND).count());
        assertEquals(1.0, budget.debt(RiskTestSupport.FRIEND).bound());
        assertEquals(CombatRiskBudget.State.SENT, budget.state(id));

        budget.resumeAfterReconciliation();
        budget.observe(RiskTestSupport.health(RiskTestSupport.FRIEND, 10.0, 0.0, 4L, 4L));
        assertEquals(CombatRiskBudget.State.RETIRED, budget.state(id));
    }

    @Test
    void respawnKeepsTheCrystalHazardAssociation() {
        CombatRiskBudget budget = RiskTestSupport.conservative();
        SafetyState state = RiskTestSupport.state(RiskTestSupport.known(20.0, 0.0, 0L),
            Map.of(RiskTestSupport.FRIEND, RiskTestSupport.known(10.0, 0.0, 0L)));
        RiskId id = RiskTestSupport.reserve(budget, 1L,
            RiskTestSupport.exposure(3.0, Map.of(RiskTestSupport.FRIEND, 1.0)), state);
        budget.mayHaveLeft(id);
        budget.observe(RiskTestSupport.explosion(RiskTestSupport.CRYSTAL_POSITION, 3L, 1L));
        budget.observe(RiskTestSupport.simple(RiskTestSupport.CONN,
            ObservationKind.RESPAWN, RiskTestSupport.SELF, 2L,
            new ObservationPayload.Respawn("overworld", false)));
        budget.resumeAfterReconciliation();
        // The explosion revision recorded before respawn still retires the friend component afterwards.
        budget.observe(RiskTestSupport.health(RiskTestSupport.FRIEND, 10.0, 0.0, 4L, 3L));
        assertEquals(CombatRiskBudget.State.RETIRED, budget.state(id));
    }

    @Test
    void respawnDoesNotClearRisksOnAnotherConnection() {
        CombatRiskBudget budget = RiskTestSupport.conservative();
        EntityKey selfTwo = new EntityKey(RiskTestSupport.CONN_TWO, 1, null);
        SafetyState stateTwo = RiskTestSupport.stateFor(RiskTestSupport.CONN_TWO,
            RiskTestSupport.GEN, selfTwo, RiskTestSupport.known(20.0, 0.0, 0L), Map.of());
        RiskId other = budget.reserve(1L, RiskTestSupport.exposure(6.0),
            RiskTestSupport.arrival(), stateTwo).orElseThrow();
        budget.mayHaveLeft(other);
        budget.observe(RiskTestSupport.simple(RiskTestSupport.CONN,
            ObservationKind.RESPAWN, RiskTestSupport.SELF, 2L,
            new ObservationPayload.Respawn("overworld", false)));
        assertEquals(1, budget.debt(selfTwo).count());
        assertEquals(CombatRiskBudget.State.SENT, budget.state(other));
    }

    @Test
    void connectionCloseClearsSentRisksOfThatConnectionOnly() {
        CombatRiskBudget budget = RiskTestSupport.conservative();
        RiskId first = RiskTestSupport.sent(budget, 1L);
        EntityKey selfTwo = new EntityKey(RiskTestSupport.CONN_TWO, 1, null);
        SafetyState stateTwo = RiskTestSupport.stateFor(RiskTestSupport.CONN_TWO,
            RiskTestSupport.GEN, selfTwo, RiskTestSupport.known(20.0, 0.0, 0L), Map.of());
        RiskId second = budget.reserve(2L, RiskTestSupport.exposure(6.0),
            RiskTestSupport.arrival(), stateTwo).orElseThrow();
        budget.mayHaveLeft(second);
        budget.connectionClosed(RiskTestSupport.CONN);
        assertEquals(CombatRiskBudget.State.RETIRED, budget.state(first));
        assertEquals(CombatRiskBudget.State.SENT, budget.state(second));
        assertEquals(6.0, budget.debt(selfTwo).bound());
    }

    @Test
    void serverStallDoesNotRetireAndALateAttackIsStillCovered() {
        CombatRiskBudget budget = RiskTestSupport.conservative();
        RiskId id = RiskTestSupport.sent(budget, 1L);
        for (int tick = 0; tick <= 60; tick++) budget.tick(tick);
        budget.suspend(SuspensionCause.SERVER_STALL);
        assertEquals(CombatRiskBudget.State.SENT, budget.state(id));
        assertTrue(budget.reserve(2L, RiskTestSupport.exposure(0.0), RiskTestSupport.arrival(),
            RiskTestSupport.healthySelf()).isEmpty());
        budget.resumeAfterReconciliation();
        budget.observe(RiskTestSupport.explosion(RiskTestSupport.CRYSTAL_POSITION, 5L, 1L));
        budget.observe(RiskTestSupport.health(RiskTestSupport.SELF, 20.0, 0.0, 6L, 2L));
        assertEquals(CombatRiskBudget.State.RETIRED, budget.state(id));
    }

    @Test
    void queueOverflowSuspendsNewDetonationsWithoutRetiringOldOnes() {
        CombatRiskBudget budget = RiskTestSupport.conservative();
        RiskId id = RiskTestSupport.sent(budget, 1L);
        budget.suspend(SuspensionCause.QUEUE_OVERFLOW);
        budget.tick(500);
        assertEquals(CombatRiskBudget.State.SENT, budget.state(id));
        assertTrue(budget.suspended());
        assertTrue(budget.reserve(2L, RiskTestSupport.exposure(0.0), RiskTestSupport.arrival(),
            RiskTestSupport.healthySelf()).isEmpty());
        budget.resumeAfterReconciliation();
        assertFalse(budget.suspended());
        assertTrue(budget.reserve(2L, RiskTestSupport.exposure(0.0), RiskTestSupport.arrival(),
            RiskTestSupport.healthySelf()).isPresent());
    }

    @Test
    void anarchyQuietWindowRetiresOnlyAfterTheWindowAndLogsIt() {
        List<String> log = new ArrayList<>();
        CombatRiskBudget budget = RiskTestSupport.budget(new GroupedBound(false),
            AnarchyQuietWindow.statistical(40L, log::add));
        budget.tick(5);
        RiskId id = RiskTestSupport.sent(budget, 1L);
        budget.observe(RiskTestSupport.explosion(RiskTestSupport.CRYSTAL_POSITION, 3L, 1L));
        budget.tick(44);
        assertEquals(CombatRiskBudget.State.SENT, budget.state(id));
        budget.tick(45);
        assertEquals(CombatRiskBudget.State.RETIRED, budget.state(id));
        assertEquals(1, log.stream().filter(line -> line.startsWith("STATISTICAL_RETIREMENT"))
            .count());
    }

    @Test
    void anarchyQuietWindowRecordsAMissWhenLaterHealthIsLower() {
        List<String> log = new ArrayList<>();
        CombatRiskBudget budget = RiskTestSupport.budget(new GroupedBound(false),
            AnarchyQuietWindow.statistical(40L, log::add));
        budget.tick(5);
        RiskId id = RiskTestSupport.sent(budget, 1L);
        budget.observe(RiskTestSupport.explosion(RiskTestSupport.CRYSTAL_POSITION, 3L, 1L));
        budget.tick(45);
        assertEquals(CombatRiskBudget.State.RETIRED, budget.state(id));
        budget.observe(RiskTestSupport.health(RiskTestSupport.SELF, 15.0, 0.0, 4L, 2L));
        assertEquals(1, log.stream().filter(line -> line.startsWith("STATISTICAL_MISS")).count());
    }

    @Test
    void anarchyQuietWindowNeedsAnObservedExplosion() {
        CombatRiskBudget budget = RiskTestSupport.budget(new GroupedBound(false),
            AnarchyQuietWindow.statistical(10L, message -> { }));
        RiskId id = RiskTestSupport.sent(budget, 1L);
        budget.tick(1_000);
        assertEquals(CombatRiskBudget.State.SENT, budget.state(id));
    }

    @Test
    void conservativeBudgetIsNotAffectedByEnabledQuietWindowNumbers() {
        CombatRiskBudget budget = new CombatRiskBudget((envelope, current) -> current,
            new GroupedBound(false), AnarchyQuietWindow.disabled());
        RiskId id = RiskTestSupport.sentWithExplosion(budget, 1L, 3L);
        budget.tick(100_000);
        assertEquals(CombatRiskBudget.State.SENT, budget.state(id));
        assertFalse(budget.debt(RiskTestSupport.SELF).count() == 0);
    }
}
