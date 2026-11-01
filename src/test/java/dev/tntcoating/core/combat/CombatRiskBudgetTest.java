/*
 * Original Comet implementation by Boyonabike.
 * Independent: written from the pinned 26.2 artifact and Meteor build 35 behaviour; no third-party code.
 * SPDX-License-Identifier: CC0-1.0
 */

package dev.tntcoating.core.combat;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class CombatRiskBudgetTest {
    @Test
    void acceptedReservationIsReservedAndCountsInCurrentBound() {
        CombatRiskBudget budget = RiskTestSupport.conservative();
        RiskId id = RiskTestSupport.reserveSimple(budget, 1L);
        assertEquals(CombatRiskBudget.State.RESERVED, budget.state(id));
        assertEquals(1, budget.outstanding());
        assertEquals(6.0, budget.current(RiskTestSupport.SELF).value());
        assertEquals(0, budget.debt(RiskTestSupport.SELF).count());
        assertFalse(budget.suspended());
    }

    @Test
    void refusesSelfBoundAboveMaxSelf() {
        CombatRiskBudget budget = RiskTestSupport.conservative();
        assertTrue(budget.reserve(1L, RiskTestSupport.exposure(6.5), RiskTestSupport.arrival(),
            RiskTestSupport.healthySelf()).isEmpty());
        assertEquals(0, budget.outstanding());
    }

    @Test
    void refusesThirdSelfSixThatWouldLeaveExactlyTheMargin() {
        CombatRiskBudget budget = RiskTestSupport.conservative();
        RiskTestSupport.reserveSimple(budget, 1L);
        RiskTestSupport.reserveSimple(budget, 2L);
        assertEquals(12.0, budget.current(RiskTestSupport.SELF).value());
        assertTrue(budget.reserve(3L, RiskTestSupport.exposure(6.0), RiskTestSupport.arrival(),
            RiskTestSupport.healthySelf()).isEmpty());
        assertEquals(12.0, budget.current(RiskTestSupport.SELF).value());
    }

    @Test
    void knownHealthLowerBoundAddsAbsorption() {
        CombatRiskBudget budget = RiskTestSupport.conservative();
        SafetyState state = RiskTestSupport.state(RiskTestSupport.known(2.0, 18.0, 1L));
        RiskTestSupport.reserve(budget, 1L, RiskTestSupport.exposure(6.0), state);
        RiskTestSupport.reserve(budget, 2L, RiskTestSupport.exposure(6.0), state);
        assertTrue(budget.reserve(3L, RiskTestSupport.exposure(6.0), RiskTestSupport.arrival(),
            state).isEmpty());
        assertEquals(12.0, budget.current(RiskTestSupport.SELF).value());
    }

    @Test
    void absorptionIsNeverSubtractedFromAnUpperBound() {
        CombatRiskBudget budget = RiskTestSupport.conservative();
        SafetyState state = RiskTestSupport.state(RiskTestSupport.known(20.0, 18.0, 1L));
        RiskTestSupport.reserve(budget, 1L, RiskTestSupport.exposure(6.0), state);
        assertEquals(6.0, budget.current(RiskTestSupport.SELF).value());
    }

    @Test
    void refusesUnknownSelfHealthBecauseTheLowerBoundIsZero() {
        CombatRiskBudget budget = RiskTestSupport.conservative();
        assertTrue(budget.reserve(1L, RiskTestSupport.exposure(0.0), RiskTestSupport.arrival(),
            RiskTestSupport.state(RiskTestSupport.unknown())).isEmpty());
        assertTrue(budget.reserve(2L, RiskTestSupport.exposure(0.0), RiskTestSupport.arrival(),
            RiskTestSupport.state(RiskTestSupport.censored())).isEmpty());
    }

    @Test
    void refusesNonFiniteAndNegativeSelfBounds() {
        CombatRiskBudget budget = RiskTestSupport.conservative();
        for (double bound : new double[] {Double.NaN, Double.POSITIVE_INFINITY, -1.0}) {
            assertTrue(budget.reserve(1L, RiskTestSupport.exposure(bound),
                RiskTestSupport.arrival(), RiskTestSupport.healthySelf()).isEmpty());
        }
        assertEquals(0, budget.outstanding());
    }

    @Test
    void refusesInvalidSelfHealthValues() {
        CombatRiskBudget budget = RiskTestSupport.conservative();
        SafetyState state = RiskTestSupport.state(RiskTestSupport.known(Double.NaN, 0.0, 1L));
        assertTrue(budget.reserve(1L, RiskTestSupport.exposure(0.0), RiskTestSupport.arrival(), state)
            .isEmpty());
    }

    @Test
    void refusesInvalidSafetyThresholds() {
        CombatRiskBudget budget = RiskTestSupport.conservative();
        for (Safety safety : new Safety[] {
            new Safety(4.0, Double.NaN, 2.0, 2.0),
            new Safety(4.0, 6.0, -2.0, 2.0),
            new Safety(-4.0, 6.0, 2.0, 2.0),
            new Safety(4.0, 6.0, 2.0, Double.POSITIVE_INFINITY)}) {
            SafetyState state = new SafetyState(RiskTestSupport.CONN, RiskTestSupport.GEN,
                RiskTestSupport.SELF, RiskTestSupport.known(20.0, 0.0, 0L), Map.of(), safety);
            assertTrue(budget.reserve(1L, RiskTestSupport.exposure(0.0),
                RiskTestSupport.arrival(), state).isEmpty());
        }
    }

    @Test
    void refusesNegativeArrivalEarliest() {
        CombatRiskBudget budget = RiskTestSupport.conservative();
        assertTrue(budget.reserve(1L, RiskTestSupport.exposure(0.0),
            new ServerTickInterval(-1L, 5L), RiskTestSupport.healthySelf()).isEmpty());
    }

    @Test
    void refusesNullInputs() {
        CombatRiskBudget budget = RiskTestSupport.conservative();
        assertTrue(budget.reserve(1L, null, RiskTestSupport.arrival(),
            RiskTestSupport.healthySelf()).isEmpty());
        assertTrue(budget.reserve(1L, RiskTestSupport.exposure(0.0), null,
            RiskTestSupport.healthySelf()).isEmpty());
        assertTrue(budget.reserve(1L, RiskTestSupport.exposure(0.0),
            RiskTestSupport.arrival(), null).isEmpty());
    }

    @Test
    void acceptsFriendBoundAtCeilingWhenTheFriendSurvives() {
        CombatRiskBudget budget = RiskTestSupport.conservative();
        SafetyState state = RiskTestSupport.state(RiskTestSupport.known(20.0, 0.0, 0L),
            Map.of(RiskTestSupport.FRIEND, RiskTestSupport.known(10.0, 0.0, 0L)));
        RiskTestSupport.reserve(budget, 1L,
            RiskTestSupport.exposure(0.0, Map.of(RiskTestSupport.FRIEND, 2.0)), state);
        assertEquals(2.0, budget.current(RiskTestSupport.FRIEND).value());
    }

    @Test
    void refusesFriendCumulativeAboveCeiling() {
        CombatRiskBudget budget = RiskTestSupport.conservative();
        SafetyState state = RiskTestSupport.state(RiskTestSupport.known(20.0, 0.0, 0L),
            Map.of(RiskTestSupport.FRIEND, RiskTestSupport.known(10.0, 0.0, 0L)));
        RiskTestSupport.reserve(budget, 1L,
            RiskTestSupport.exposure(0.0, Map.of(RiskTestSupport.FRIEND, 2.0)), state);
        assertTrue(budget.reserve(2L,
            RiskTestSupport.exposure(0.0, Map.of(RiskTestSupport.FRIEND, 1.0)),
            RiskTestSupport.arrival(), state).isEmpty());
        assertEquals(2.0, budget.current(RiskTestSupport.FRIEND).value());
    }

    @Test
    void appliesKnownFriendSurvivalCheck() {
        CombatRiskBudget budget = RiskTestSupport.conservative();
        SafetyState state = RiskTestSupport.state(RiskTestSupport.known(20.0, 0.0, 0L),
            Map.of(RiskTestSupport.FRIEND, RiskTestSupport.known(2.0, 0.0, 0L)));
        assertTrue(budget.reserve(1L,
            RiskTestSupport.exposure(0.0, Map.of(RiskTestSupport.FRIEND, 2.0)),
            RiskTestSupport.arrival(), state).isEmpty());
        assertEquals(0, budget.outstanding());
    }

    @Test
    void censoredFriendObeysCeilingButSkipsSurvival() {
        CombatRiskBudget budget = RiskTestSupport.conservative();
        SafetyState state = RiskTestSupport.state(RiskTestSupport.known(20.0, 0.0, 0L),
            Map.of(RiskTestSupport.FRIEND, RiskTestSupport.censored()));
        RiskTestSupport.reserve(budget, 1L,
            RiskTestSupport.exposure(0.0, Map.of(RiskTestSupport.FRIEND, 2.0)), state);
        assertTrue(budget.reserve(2L,
            RiskTestSupport.exposure(0.0, Map.of(RiskTestSupport.FRIEND, 1.0)),
            RiskTestSupport.arrival(), state).isEmpty());
    }

    @Test
    void unknownFriendObeysCeilingButSkipsSurvival() {
        CombatRiskBudget budget = RiskTestSupport.conservative();
        SafetyState state = RiskTestSupport.state(RiskTestSupport.known(20.0, 0.0, 0L),
            Map.of(RiskTestSupport.FRIEND, RiskTestSupport.unknown()));
        RiskTestSupport.reserve(budget, 1L,
            RiskTestSupport.exposure(0.0, Map.of(RiskTestSupport.FRIEND, 2.0)), state);
        assertTrue(budget.reserve(2L,
            RiskTestSupport.exposure(0.0, Map.of(RiskTestSupport.FRIEND, 0.5)),
            RiskTestSupport.arrival(), state).isEmpty());
    }

    @Test
    void friendCeilingsAreIndependent() {
        CombatRiskBudget budget = RiskTestSupport.conservative();
        SafetyState state = RiskTestSupport.state(RiskTestSupport.known(20.0, 0.0, 0L),
            Map.of(RiskTestSupport.FRIEND, RiskTestSupport.known(10.0, 0.0, 0L),
                RiskTestSupport.FRIEND_TWO, RiskTestSupport.known(10.0, 0.0, 0L)));
        RiskTestSupport.reserve(budget, 1L,
            RiskTestSupport.exposure(0.0, Map.of(RiskTestSupport.FRIEND, 2.0)), state);
        RiskTestSupport.reserve(budget, 2L,
            RiskTestSupport.exposure(0.0, Map.of(RiskTestSupport.FRIEND_TWO, 2.0)), state);
        assertEquals(2.0, budget.current(RiskTestSupport.FRIEND).value());
        assertEquals(2.0, budget.current(RiskTestSupport.FRIEND_TWO).value());
    }

    @Test
    void refusesFriendKeyEqualToSelf() {
        CombatRiskBudget budget = RiskTestSupport.conservative();
        assertTrue(budget.reserve(1L, RiskTestSupport.exposure(0.0,
                Map.of(RiskTestSupport.SELF, 1.0)), RiskTestSupport.arrival(),
            RiskTestSupport.healthySelf()).isEmpty());
    }

    @Test
    void currentSumsSelfAndFriendComponentsSeparately() {
        CombatRiskBudget budget = RiskTestSupport.conservative();
        SafetyState state = RiskTestSupport.state(RiskTestSupport.known(20.0, 0.0, 0L),
            Map.of(RiskTestSupport.FRIEND, RiskTestSupport.known(10.0, 0.0, 0L)));
        RiskTestSupport.reserve(budget, 1L,
            RiskTestSupport.exposure(6.0, Map.of(RiskTestSupport.FRIEND, 2.0)), state);
        assertEquals(6.0, budget.current(RiskTestSupport.SELF).value());
        assertEquals(2.0, budget.current(RiskTestSupport.FRIEND).value());
        assertEquals(0.0, budget.current(RiskTestSupport.FRIEND_TWO).value());
        assertEquals(Bound.zero(), budget.current(null));
        assertEquals(Debt.zero(), budget.debt(null));
    }

    @Test
    void debtOnlyCountsRisksThatMayHaveLeft() {
        CombatRiskBudget budget = RiskTestSupport.conservative();
        RiskTestSupport.reserveSimple(budget, 1L);
        RiskId sent = RiskTestSupport.sent(budget, 2L);
        assertEquals(CombatRiskBudget.State.SENT, budget.state(sent));
        assertEquals(1, budget.debt(RiskTestSupport.SELF).count());
        assertEquals(6.0, budget.debt(RiskTestSupport.SELF).bound());
        assertEquals(12.0, budget.current(RiskTestSupport.SELF).value());
    }

    @Test
    void zeroBoundSentRiskIsDebtAndCannotRetireByRuleOne() {
        CombatRiskBudget budget = RiskTestSupport.conservative();
        RiskId id = RiskTestSupport.reserve(budget, 1L, RiskTestSupport.exposure(0.0),
            RiskTestSupport.healthySelf());
        budget.dispatching(id);
        budget.mayHaveLeft(id);
        budget.observe(RiskTestSupport.explosion(RiskTestSupport.CRYSTAL_POSITION, 3L, 1L));
        budget.observe(RiskTestSupport.health(RiskTestSupport.SELF, 20.0, 0.0, 4L, 2L));
        assertEquals(CombatRiskBudget.State.SENT, budget.state(id));
        assertEquals(1, budget.debt(RiskTestSupport.SELF).count());
        assertEquals(0.0, budget.debt(RiskTestSupport.SELF).bound());
        assertEquals(0.0, budget.current(RiskTestSupport.SELF).value());
    }

    @Test
    void dispatchingOnlyMovesReservedRisk() {
        CombatRiskBudget budget = RiskTestSupport.conservative();
        RiskId id = RiskTestSupport.reserveSimple(budget, 1L);
        budget.dispatching(id);
        assertEquals(CombatRiskBudget.State.DISPATCHING, budget.state(id));
        budget.mayHaveLeft(id);
        budget.dispatching(id);
        assertEquals(CombatRiskBudget.State.SENT, budget.state(id));
    }

    @Test
    void notTransmittedBeforeMayHaveLeftReleasesRisk() {
        CombatRiskBudget budget = RiskTestSupport.conservative();
        RiskId id = RiskTestSupport.reserveSimple(budget, 1L);
        budget.notTransmitted(id);
        assertEquals(CombatRiskBudget.State.RETIRED, budget.state(id));
        assertEquals(0, budget.outstanding());
        assertEquals(0.0, budget.current(RiskTestSupport.SELF).value());
    }

    @Test
    void mayHaveLeftIsIrreversibleAndNotTransmittedIsRefusedAfterwards() {
        CombatRiskBudget budget = RiskTestSupport.conservative();
        RiskId id = RiskTestSupport.reserveSimple(budget, 1L);
        budget.mayHaveLeft(id);
        budget.notTransmitted(id);
        assertEquals(CombatRiskBudget.State.SENT, budget.state(id));
        assertEquals(1, budget.outstanding());
        assertEquals(6.0, budget.current(RiskTestSupport.SELF).value());
    }

    @Test
    void unknownAndTerminalIdsAreInert() {
        CombatRiskBudget budget = RiskTestSupport.conservative();
        RiskId id = RiskTestSupport.reserveSimple(budget, 1L);
        budget.notTransmitted(id);
        assertEquals(CombatRiskBudget.State.RETIRED, budget.state(id));
        assertNull(budget.state(new RiskId(999L)));
        assertNull(budget.state(null));
        budget.mayHaveLeft(new RiskId(999L));
        budget.dispatching(new RiskId(999L));
        budget.notTransmitted(new RiskId(999L));
        assertEquals(0, budget.outstanding());
    }

    @Test
    void actionGenerationEndReleasesUnsentAndKeepsSent() {
        CombatRiskBudget budget = RiskTestSupport.conservative();
        RiskId reserved = RiskTestSupport.reserveSimple(budget, 1L);
        RiskId sent = RiskTestSupport.sent(budget, 2L);
        RiskId otherGeneration = RiskTestSupport.reserve(budget, 3L,
            RiskTestSupport.exposure(1.0), new SafetyState(RiskTestSupport.CONN,
                new ActionGeneration(10L), RiskTestSupport.SELF,
                RiskTestSupport.known(20.0, 0.0, 0L), Map.of(), RiskTestSupport.SAFETY));
        budget.actionGenerationEnded(RiskTestSupport.GEN);
        assertEquals(CombatRiskBudget.State.RETIRED, budget.state(reserved));
        assertEquals(CombatRiskBudget.State.SENT, budget.state(sent));
        assertEquals(CombatRiskBudget.State.RESERVED, budget.state(otherGeneration));
    }

    @Test
    void actionGenerationEndIgnoresNull() {
        CombatRiskBudget budget = RiskTestSupport.conservative();
        RiskId id = RiskTestSupport.reserveSimple(budget, 1L);
        budget.actionGenerationEnded(null);
        assertEquals(CombatRiskBudget.State.RESERVED, budget.state(id));
    }

    @Test
    void connectionCloseClearsOnlyThatConnection() {
        CombatRiskBudget budget = RiskTestSupport.conservative();
        RiskTestSupport.reserveSimple(budget, 1L);
        EntityKey selfTwo = new EntityKey(RiskTestSupport.CONN_TWO, 1, null);
        SafetyState stateTwo = RiskTestSupport.stateFor(RiskTestSupport.CONN_TWO,
            RiskTestSupport.GEN, selfTwo, RiskTestSupport.known(20.0, 0.0, 0L), Map.of());
        assertTrue(budget.reserve(2L, RiskTestSupport.exposure(6.0), RiskTestSupport.arrival(),
            stateTwo).isPresent());
        budget.connectionClosed(RiskTestSupport.CONN);
        assertEquals(0.0, budget.current(RiskTestSupport.SELF).value());
        assertEquals(6.0, budget.current(selfTwo).value());
        assertEquals(1, budget.outstanding());
    }

    @Test
    void connectionCloseIgnoresNull() {
        CombatRiskBudget budget = RiskTestSupport.conservative();
        RiskTestSupport.reserveSimple(budget, 1L);
        budget.connectionClosed(null);
        assertEquals(1, budget.outstanding());
    }

    @Test
    void suspensionBlocksReservationsButDoesNotRetireSentRisk() {
        CombatRiskBudget budget = RiskTestSupport.conservative();
        RiskId sent = RiskTestSupport.sent(budget, 1L);
        budget.suspend(SuspensionCause.QUEUE_OVERFLOW);
        assertTrue(budget.suspended());
        assertTrue(budget.reserve(2L, RiskTestSupport.exposure(0.0), RiskTestSupport.arrival(),
            RiskTestSupport.healthySelf()).isEmpty());
        assertEquals(CombatRiskBudget.State.SENT, budget.state(sent));
        budget.resumeAfterReconciliation();
        assertFalse(budget.suspended());
        assertTrue(budget.reserve(2L, RiskTestSupport.exposure(0.0), RiskTestSupport.arrival(),
            RiskTestSupport.healthySelf()).isPresent());
    }

    @Test
    void resumeAfterReconciliationKeepsIncoherentPairSuspended() {
        CombatRiskBudget budget = RiskTestSupport.conservative();
        RiskId sent = RiskTestSupport.sentWithExplosion(budget, 1L, 3L);
        budget.observe(RiskTestSupport.entityDataHealth(RiskTestSupport.SELF, 20.0, 4L, 2L));
        assertTrue(budget.suspended());
        budget.resumeAfterReconciliation();
        assertTrue(budget.suspended());
        assertEquals(CombatRiskBudget.State.SENT, budget.state(sent));
        budget.observe(RiskTestSupport.entityDataAbsorption(RiskTestSupport.SELF, 0.0, 4L, 3L));
        assertFalse(budget.suspended());
        assertEquals(CombatRiskBudget.State.RETIRED, budget.state(sent));
    }

    @Test
    void staleEnvelopeIsRejectedAfterWidening() {
        CombatRiskBudget budget = RiskTestSupport.conservative();
        assertTrue(RiskTestSupport.reserve(budget, 1L,
            RiskTestSupport.exposure(3.0, 1L), RiskTestSupport.healthySelf()) != null);
        budget.widen(new MitigationEnvelope(2L));
        int outstanding = budget.outstanding();
        // An envelope priced against mitigation revision 1 is stale after revision 2 widened the bounds.
        assertTrue(budget.reserve(2L, RiskTestSupport.exposure(3.0, 1L), RiskTestSupport.arrival(),
            RiskTestSupport.healthySelf()).isEmpty());
        assertEquals(outstanding, budget.outstanding());
        assertEquals(3.0, budget.current(RiskTestSupport.SELF).value());
        assertTrue(budget.reserve(3L, RiskTestSupport.exposure(3.0, 2L), RiskTestSupport.arrival(),
            RiskTestSupport.healthySelf()).isPresent());
    }

    @Test
    void widenDoublesEveryOutstandingSelfAndFriendComponent() {
        CombatRiskBudget budget = RiskTestSupport.doubling();
        SafetyState state = RiskTestSupport.state(RiskTestSupport.known(20.0, 0.0, 0L),
            Map.of(RiskTestSupport.FRIEND, RiskTestSupport.known(10.0, 0.0, 0L)));
        RiskId first = RiskTestSupport.reserve(budget, 1L,
            RiskTestSupport.exposure(3.0, Map.of(RiskTestSupport.FRIEND, 1.0)), state);
        RiskTestSupport.reserve(budget, 2L, RiskTestSupport.exposure(4.0), state);
        budget.mayHaveLeft(first);
        assertEquals(7.0, budget.current(RiskTestSupport.SELF).value());
        assertEquals(1.0, budget.current(RiskTestSupport.FRIEND).value());
        budget.widen(new MitigationEnvelope(2L));
        assertEquals(14.0, budget.current(RiskTestSupport.SELF).value());
        assertEquals(2.0, budget.current(RiskTestSupport.FRIEND).value());
        assertFalse(budget.suspended());
    }

    @Test
    void widenNeverNarrows() {
        CombatRiskBudget budget = new CombatRiskBudget((envelope, current) ->
            new MitigatedDamage(1.0));
        RiskTestSupport.reserveSimple(budget, 1L);
        budget.widen(new MitigationEnvelope(2L));
        assertEquals(6.0, budget.current(RiskTestSupport.SELF).value());
    }

    @Test
    void widenLeavesZeroComponentsZero() {
        CombatRiskBudget budget = RiskTestSupport.doubling();
        RiskTestSupport.reserve(budget, 1L, RiskTestSupport.exposure(0.0),
            RiskTestSupport.healthySelf());
        budget.widen(new MitigationEnvelope(2L));
        assertEquals(0.0, budget.current(RiskTestSupport.SELF).value());
    }

    @Test
    void widenIgnoresOlderOrEqualRevision() {
        CombatRiskBudget budget = RiskTestSupport.doubling();
        RiskTestSupport.reserveSimple(budget, 1L);
        budget.widen(new MitigationEnvelope(1L));
        assertEquals(6.0, budget.current(RiskTestSupport.SELF).value());
        budget.widen(new MitigationEnvelope(0L));
        assertEquals(6.0, budget.current(RiskTestSupport.SELF).value());
    }

    @Test
    void widenAppliesOncePerEnvelopeRevision() {
        CombatRiskBudget budget = RiskTestSupport.doubling();
        RiskTestSupport.reserve(budget, 1L, RiskTestSupport.exposure(3.0),
            RiskTestSupport.healthySelf());
        budget.widen(new MitigationEnvelope(2L));
        assertEquals(6.0, budget.current(RiskTestSupport.SELF).value());
        budget.widen(new MitigationEnvelope(2L));
        assertEquals(6.0, budget.current(RiskTestSupport.SELF).value());
        budget.widen(new MitigationEnvelope(3L));
        assertEquals(12.0, budget.current(RiskTestSupport.SELF).value());
    }

    @Test
    void widenWithoutWidenerSuspends() {
        CombatRiskBudget budget = new CombatRiskBudget(null);
        RiskTestSupport.reserveSimple(budget, 1L);
        budget.widen(new MitigationEnvelope(2L));
        assertTrue(budget.suspended());
        assertEquals(6.0, budget.current(RiskTestSupport.SELF).value());
    }

    @Test
    void widenWithNullEnvelopeSuspends() {
        CombatRiskBudget budget = RiskTestSupport.doubling();
        RiskTestSupport.reserveSimple(budget, 1L);
        budget.widen(null);
        assertTrue(budget.suspended());
    }

    @Test
    void widenThrowingWidenerSuspends() {
        CombatRiskBudget budget = new CombatRiskBudget((envelope, current) -> {
            throw new IllegalStateException("boom");
        });
        RiskTestSupport.reserveSimple(budget, 1L);
        budget.widen(new MitigationEnvelope(2L));
        assertTrue(budget.suspended());
        assertEquals(6.0, budget.current(RiskTestSupport.SELF).value());
    }

    @Test
    void widenInvalidResultSuspends() {
        for (MitigationWidener widener : new MitigationWidener[] {
            (envelope, current) -> null,
            (envelope, current) -> new MitigatedDamage(-1.0),
            (envelope, current) -> new MitigatedDamage(Double.NaN)}) {
            CombatRiskBudget budget = new CombatRiskBudget(widener);
            RiskTestSupport.reserveSimple(budget, 1L);
            budget.widen(new MitigationEnvelope(2L));
            assertTrue(budget.suspended());
            assertEquals(6.0, budget.current(RiskTestSupport.SELF).value());
        }
    }

    @Test
    void widenSuspensionBlocksNewReservationsUntilResume() {
        java.util.concurrent.atomic.AtomicBoolean fail =
            new java.util.concurrent.atomic.AtomicBoolean(true);
        CombatRiskBudget budget = new CombatRiskBudget((envelope, current) -> {
            if (fail.get()) throw new IllegalStateException("boom");
            return current;
        });
        RiskTestSupport.reserveSimple(budget, 1L);
        budget.widen(new MitigationEnvelope(2L));
        assertTrue(budget.suspended());
        assertTrue(budget.reserve(2L, RiskTestSupport.exposure(0.0),
            RiskTestSupport.arrival(), RiskTestSupport.healthySelf()).isEmpty());
        // Reconciliation cannot clear a widening failure: the bounds were never re-priced.
        budget.resumeAfterReconciliation();
        assertTrue(budget.suspended());
        assertTrue(budget.reserve(3L, RiskTestSupport.exposure(0.0),
            RiskTestSupport.arrival(), RiskTestSupport.healthySelf()).isEmpty());

        fail.set(false);
        budget.widen(new MitigationEnvelope(3L));
        budget.resumeAfterReconciliation();
        assertFalse(budget.suspended());
        assertTrue(budget.reserve(4L, RiskTestSupport.exposure(0.0, 3L),
            RiskTestSupport.arrival(), RiskTestSupport.healthySelf()).isPresent());
    }

    @Test
    void resumeDoesNotClearFailedWidening() {
        CombatRiskBudget budget = new CombatRiskBudget(null);
        RiskTestSupport.reserveSimple(budget, 1L);
        budget.widen(new MitigationEnvelope(2L));
        assertTrue(budget.suspended());
        budget.resumeAfterReconciliation();
        assertTrue(budget.suspended());
        assertTrue(budget.reserve(2L, RiskTestSupport.exposure(0.0),
            RiskTestSupport.arrival(), RiskTestSupport.healthySelf()).isEmpty());
    }

    @Test
    void boundCrystalFromAnotherConnectionIsRejected() {
        CombatRiskBudget budget = RiskTestSupport.conservative();
        EntityKey foreignCrystal = new EntityKey(RiskTestSupport.CONN_TWO, 3, null);
        assertTrue(budget.reserve(1L, RiskTestSupport.exposure(6.0),
            RiskTestSupport.arrival(), RiskTestSupport.healthySelf(), foreignCrystal,
            RiskTestSupport.CRYSTAL_POSITION).isEmpty());
        assertEquals(0, budget.outstanding());
        assertEquals(0.0, budget.current(RiskTestSupport.SELF).value());
    }

    @Test
    void widenDoesNotReviveRetiredRisk() {
        CombatRiskBudget budget = RiskTestSupport.doubling();
        RiskId id = RiskTestSupport.reserve(budget, 1L, RiskTestSupport.exposure(3.0),
            RiskTestSupport.healthySelf());
        budget.mayHaveLeft(id);
        budget.observe(RiskTestSupport.explosion(RiskTestSupport.CRYSTAL_POSITION, 2L, 1L));
        budget.observe(RiskTestSupport.health(RiskTestSupport.SELF, 20.0, 0.0, 3L, 2L));
        assertEquals(CombatRiskBudget.State.RETIRED, budget.state(id));
        budget.widen(new MitigationEnvelope(4L));
        assertEquals(0.0, budget.current(RiskTestSupport.SELF).value());
        assertEquals(0, budget.outstanding());
    }

    @Test
    void groupedBoundEnabledStillFallsBackToPlainSum() {
        CombatRiskBudget budget = RiskTestSupport.budget(new GroupedBound(true),
            AnarchyQuietWindow.disabled());
        RiskTestSupport.reserveSimple(budget, 1L);
        RiskTestSupport.reserveSimple(budget, 2L);
        assertEquals(12.0, budget.current(RiskTestSupport.SELF).value());
        assertEquals(2, budget.current(RiskTestSupport.SELF).components());
    }

    @Test
    void conservativeBudgetNeverRetiresOnTickAlone() {
        CombatRiskBudget budget = RiskTestSupport.conservative();
        RiskId id = RiskTestSupport.sentWithExplosion(budget, 1L, 3L);
        budget.tick(10_000);
        assertEquals(CombatRiskBudget.State.SENT, budget.state(id));
        assertEquals(1, budget.debt(RiskTestSupport.SELF).count());
    }

    @Test
    void negativeTickIsIgnored() {
        CombatRiskBudget budget = RiskTestSupport.conservative();
        RiskId id = RiskTestSupport.sentWithExplosion(budget, 1L, 3L);
        budget.tick(-5);
        assertEquals(CombatRiskBudget.State.SENT, budget.state(id));
    }

    @Test
    void noBoundCrystalCanNeverRetireByRuleOne() {
        CombatRiskBudget budget = RiskTestSupport.conservative();
        RiskId id = RiskTestSupport.reserveNoCrystal(budget, 1L);
        budget.dispatching(id);
        budget.mayHaveLeft(id);
        budget.observe(RiskTestSupport.explosion(RiskTestSupport.CRYSTAL_POSITION, 3L, 1L));
        budget.observe(RiskTestSupport.health(RiskTestSupport.SELF, 20.0, 0.0, 4L, 2L));
        assertEquals(CombatRiskBudget.State.SENT, budget.state(id));
    }
}
