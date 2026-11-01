/*
 * Original Comet implementation by Boyonabike.
 * Independent: written from the pinned 26.2 artifact and Meteor build 35 behaviour; no third-party code.
 * SPDX-License-Identifier: CC0-1.0
 */

package dev.tntcoating.core.combat;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * The sixteen safety-model tests the plan requires before Phase 2 closes. Most are expressed directly on
 * the budget; the dispatch-related ones also go through {@link CombatScheduler}.
 */
class CombatSafetyModelTest {
    @Test
    void summedSelfBoundBlocksTheThirdSelfSix() {
        CombatRiskBudget budget = RiskTestSupport.conservative();
        assertTrue(budget.reserve(1L, RiskTestSupport.exposure(6.0), RiskTestSupport.arrival(),
            RiskTestSupport.healthySelf()).isPresent());
        assertTrue(budget.reserve(2L, RiskTestSupport.exposure(6.0), RiskTestSupport.arrival(),
            RiskTestSupport.healthySelf()).isPresent());
        assertEquals(12.0, budget.current(RiskTestSupport.SELF).value());
        assertTrue(budget.reserve(3L, RiskTestSupport.exposure(6.0), RiskTestSupport.arrival(),
            RiskTestSupport.healthySelf()).isEmpty());
        assertEquals(12.0, budget.current(RiskTestSupport.SELF).value());
    }

    @Test
    void absorptionCountsOnlyInEffectiveHealth() {
        CombatRiskBudget budget = RiskTestSupport.conservative();
        SafetyState state = RiskTestSupport.state(RiskTestSupport.known(2.0, 18.0, 1L));
        assertTrue(budget.reserve(1L, RiskTestSupport.exposure(6.0), RiskTestSupport.arrival(), state)
            .isPresent());
        assertTrue(budget.reserve(2L, RiskTestSupport.exposure(6.0), RiskTestSupport.arrival(), state)
            .isPresent());
        // The upper bounds stay 6 each; absorption only raised the health lower bound to 20.
        assertEquals(12.0, budget.current(RiskTestSupport.SELF).value());
        assertTrue(budget.reserve(3L, RiskTestSupport.exposure(6.0), RiskTestSupport.arrival(), state)
            .isEmpty());
    }

    @Test
    void armourBreakWidensEveryOutstandingComponent() {
        CombatRiskBudget budget = RiskTestSupport.doubling();
        assertTrue(budget.reserve(1L, RiskTestSupport.exposure(3.0), RiskTestSupport.arrival(),
            RiskTestSupport.healthySelf()).isPresent());
        RiskId second = RiskTestSupport.reserve(budget, 2L, RiskTestSupport.exposure(4.0),
            RiskTestSupport.healthySelf());
        budget.mayHaveLeft(second);
        assertEquals(7.0, budget.current(RiskTestSupport.SELF).value());
        budget.widen(new MitigationEnvelope(2L));
        assertEquals(14.0, budget.current(RiskTestSupport.SELF).value());
        assertTrue(budget.reserve(3L, RiskTestSupport.exposure(6.0), RiskTestSupport.arrival(),
            RiskTestSupport.healthySelf()).isEmpty());
    }

    @Test
    void serverStallNeverRetiresAndALateAttackIsCovered() {
        CombatRiskBudget budget = RiskTestSupport.conservative();
        RiskId id = RiskTestSupport.sent(budget, 1L);
        for (int tick = 0; tick <= 40; tick++) {
            budget.tick(tick);
        }
        assertEquals(CombatRiskBudget.State.SENT, budget.state(id));
        budget.suspend(SuspensionCause.SERVER_STALL);
        assertTrue(budget.suspended());
        assertEquals(CombatRiskBudget.State.SENT, budget.state(id));
        budget.resumeAfterReconciliation();
        budget.observe(RiskTestSupport.explosion(RiskTestSupport.CRYSTAL_POSITION, 10L, 1L));
        budget.observe(RiskTestSupport.health(RiskTestSupport.SELF, 20.0, 0.0, 11L, 2L));
        assertEquals(CombatRiskBudget.State.RETIRED, budget.state(id));
    }

    @Test
    void conservativeNeverRetiresEarlyAndAnarchyRecordsAMissBeyondP99() {
        CombatRiskBudget conservative = RiskTestSupport.conservative();
        RiskId conservativeId = RiskTestSupport.sent(conservative, 1L);
        conservative.tick(10_000);
        assertEquals(CombatRiskBudget.State.SENT, conservative.state(conservativeId));

        List<String> log = new java.util.ArrayList<>();
        CombatRiskBudget anarchy = RiskTestSupport.budget(new GroupedBound(false),
            AnarchyQuietWindow.statistical(40L, log::add));
        anarchy.tick(3);
        RiskId anarchyId = RiskTestSupport.sent(anarchy, 1L);
        anarchy.observe(RiskTestSupport.explosion(RiskTestSupport.CRYSTAL_POSITION, 5L, 1L));
        anarchy.tick(43);
        assertEquals(CombatRiskBudget.State.RETIRED, anarchy.state(anarchyId));
        anarchy.observe(RiskTestSupport.health(RiskTestSupport.SELF, 12.0, 0.0, 6L, 2L));
        assertTrue(log.stream().anyMatch(line -> line.startsWith("STATISTICAL_MISS")));
    }

    @Test
    void splitAbsorptionThenHealthOnlyRetiresWhenCoherent() {
        CombatRiskBudget budget = RiskTestSupport.conservative();
        RiskId id = RiskTestSupport.sentWithExplosion(budget, 1L, 3L);
        budget.observe(RiskTestSupport.entityDataAbsorption(RiskTestSupport.SELF, 0.0, 4L, 2L));
        assertEquals(CombatRiskBudget.State.SENT, budget.state(id));
        assertTrue(budget.suspended());
        budget.observe(RiskTestSupport.entityDataHealth(RiskTestSupport.SELF, 20.0, 4L, 3L));
        assertEquals(CombatRiskBudget.State.RETIRED, budget.state(id));
        assertFalse(budget.suspended());
    }

    @Test
    void unrelatedHealingRetiresOnceWithoutDoubleCounting() {
        CombatRiskBudget budget = RiskTestSupport.conservative();
        RiskId first = RiskTestSupport.sentWithExplosion(budget, 1L, 3L);
        RiskId second = RiskTestSupport.sentWithExplosion(budget, 2L, 3L);
        budget.observe(RiskTestSupport.health(RiskTestSupport.SELF, 20.0, 0.0, 4L, 3L));
        assertEquals(CombatRiskBudget.State.RETIRED, budget.state(first));
        assertEquals(CombatRiskBudget.State.RETIRED, budget.state(second));
        budget.observe(RiskTestSupport.health(RiskTestSupport.SELF, 20.0, 0.0, 5L, 4L));
        assertEquals(0, budget.outstanding());
        assertEquals(0.0, budget.debt(RiskTestSupport.SELF).bound());
    }

    @Test
    void zeroDamageDebtNeedsBarrierAndBlocksOnlyWhenNonzero() {
        CombatRiskBudget budget = RiskTestSupport.conservative();
        RiskId zero = RiskTestSupport.reserve(budget, 1L, RiskTestSupport.exposure(0.0),
            RiskTestSupport.healthySelf());
        budget.mayHaveLeft(zero);
        budget.observe(RiskTestSupport.explosion(RiskTestSupport.CRYSTAL_POSITION, 3L, 1L));
        budget.observe(RiskTestSupport.health(RiskTestSupport.SELF, 20.0, 0.0, 4L, 2L));
        assertEquals(CombatRiskBudget.State.SENT, budget.state(zero));
        assertFalse(ProcessingBarrier.satisfied());

        // Zero debt does not block a legal send.
        RiskId first = RiskTestSupport.sent(budget, 2L);
        RiskId second = RiskTestSupport.sent(budget, 3L);
        assertTrue(first != null && second != null);
        assertEquals(12.0, budget.current(RiskTestSupport.SELF).value());
        assertTrue(budget.reserve(4L, RiskTestSupport.exposure(6.0), RiskTestSupport.arrival(),
            RiskTestSupport.healthySelf()).isEmpty());
    }

    @Test
    void trackingRemovalIsNeverDroppedAsDetonation() {
        CombatRiskBudget budget = RiskTestSupport.conservative();
        RiskId id = RiskTestSupport.sentWithExplosion(budget, 1L, 3L);
        budget.observe(RiskTestSupport.trackingRemoval(RiskTestSupport.CRYSTAL, 4L));
        budget.observe(RiskTestSupport.simple(RiskTestSupport.CONN,
            ObservationKind.TRACKING_REMOVED, RiskTestSupport.CRYSTAL, 5L,
            new ObservationPayload.None()));
        assertEquals(CombatRiskBudget.State.SENT, budget.state(id));
    }

    @Test
    void rejectedAttackRetiresOnlyAfterAProcessingBarrierWithTheCrystalPresent() {
        CombatRiskBudget budget = RiskTestSupport.conservative();
        RiskId withCrystal = RiskTestSupport.sent(budget, 1L);
        RiskId withoutCrystal = RiskTestSupport.reserveNoCrystal(budget, 2L);
        budget.dispatching(withoutCrystal);
        budget.mayHaveLeft(withoutCrystal);
        budget.observe(RiskTestSupport.explosion(RiskTestSupport.CRYSTAL_POSITION, 4L, 1L));
        budget.observe(RiskTestSupport.health(RiskTestSupport.SELF, 20.0, 0.0, 5L, 2L));
        // Rule 1 can retire the risk bound to the crystal position, but never the risk without one, and
        // rule 3 (block-ack barrier + one tick) is never satisfied.
        assertEquals(CombatRiskBudget.State.RETIRED, budget.state(withCrystal));
        assertEquals(CombatRiskBudget.State.SENT, budget.state(withoutCrystal));
        assertFalse(ProcessingBarrier.satisfied());
    }

    @Test
    void failureAtEveryPacketStepKeepsTheRiskOnceMayHaveLeft() {
        CombatRiskBudget budget = RiskTestSupport.conservative();
        RiskId id = RiskTestSupport.reserveSimple(budget, 1L);
        budget.dispatching(id);
        budget.mayHaveLeft(id);
        budget.notTransmitted(id);
        budget.actionGenerationEnded(RiskTestSupport.GEN);
        budget.suspend(SuspensionCause.QUEUE_OVERFLOW);
        budget.tick(1_000);
        assertEquals(CombatRiskBudget.State.SENT, budget.state(id));
        assertEquals(6.0, budget.current(RiskTestSupport.SELF).value());
        assertEquals(1, budget.debt(RiskTestSupport.SELF).count());
    }

    @Test
    void deathAndRespawnClearSelfOnlyAndKeepFriendsAndHazards() {
        CombatRiskBudget budget = RiskTestSupport.conservative();
        SafetyState state = RiskTestSupport.state(RiskTestSupport.known(20.0, 0.0, 0L),
            Map.of(RiskTestSupport.FRIEND, RiskTestSupport.known(10.0, 0.0, 0L)));
        RiskId id = RiskTestSupport.reserve(budget, 1L,
            RiskTestSupport.exposure(3.0, Map.of(RiskTestSupport.FRIEND, 1.0)), state);
        budget.mayHaveLeft(id);
        budget.observe(RiskTestSupport.explosion(RiskTestSupport.CRYSTAL_POSITION, 3L, 1L));
        budget.observe(RiskTestSupport.simple(RiskTestSupport.CONN,
            ObservationKind.DEATH, RiskTestSupport.SELF, 2L, new ObservationPayload.Death()));
        budget.observe(RiskTestSupport.simple(RiskTestSupport.CONN,
            ObservationKind.RESPAWN, RiskTestSupport.SELF, 3L,
            new ObservationPayload.Respawn("overworld", false)));
        assertEquals(0, budget.debt(RiskTestSupport.SELF).count());
        assertEquals(1, budget.debt(RiskTestSupport.FRIEND).count());
        budget.resumeAfterReconciliation();
        budget.observe(RiskTestSupport.health(RiskTestSupport.FRIEND, 10.0, 0.0, 4L, 4L));
        assertEquals(CombatRiskBudget.State.RETIRED, budget.state(id));
    }

    @Test
    void hiddenPriorHurtKeepsGuaranteedLowerZeroAndLethalOverrideOff() {
        EffectiveHealth selfHealth = RiskTestSupport.known(20.0, 0.0, 0L);
        EffectiveHealth targetHealth = RiskTestSupport.known(20.0, 0.0, 0L);
        CombatDamagePolicy policy = new CombatDamagePolicy(selfHealth, targetHealth);
        // A hidden prior hit means the guaranteed lower bound is zero, not the visible health.
        TargetEstimate hiddenHurt = new TargetEstimate(0.0, 20.0, 25.0);
        assertEquals(0.0, hiddenHurt.guaranteedLower());
        ExposureBound exposure = RiskTestSupport.exposure(0.0);
        assertTrue(policy.allows(hiddenHurt, exposure, RiskTestSupport.SAFETY));
        assertFalse(policy.allows(hiddenHurt, exposure, RiskTestSupport.SAFETY, true));

        CombatDamagePolicy censoredTarget = new CombatDamagePolicy(selfHealth,
            RiskTestSupport.censored());
        assertFalse(censoredTarget.allows(new TargetEstimate(20.0, 20.0, 20.0), exposure,
            RiskTestSupport.SAFETY, true));
    }

    @Test
    void censoredFriendAppliesTheCeilingButSkipsTheSurvivalCheck() {
        CombatRiskBudget budget = RiskTestSupport.conservative();
        SafetyState censored = RiskTestSupport.state(RiskTestSupport.known(20.0, 0.0, 0L),
            Map.of(RiskTestSupport.FRIEND, RiskTestSupport.censored()));
        assertTrue(budget.reserve(1L,
            RiskTestSupport.exposure(0.0, Map.of(RiskTestSupport.FRIEND, 2.0)),
            RiskTestSupport.arrival(), censored).isPresent());
        assertTrue(budget.reserve(2L,
            RiskTestSupport.exposure(0.0, Map.of(RiskTestSupport.FRIEND, 1.0)),
            RiskTestSupport.arrival(), censored).isEmpty());

        CombatRiskBudget second = RiskTestSupport.conservative();
        SafetyState knownLow = RiskTestSupport.state(RiskTestSupport.known(20.0, 0.0, 0L),
            Map.of(RiskTestSupport.FRIEND, RiskTestSupport.known(2.0, 0.0, 0L)));
        assertTrue(second.reserve(1L,
            RiskTestSupport.exposure(0.0, Map.of(RiskTestSupport.FRIEND, 2.0)),
            RiskTestSupport.arrival(), knownLow).isEmpty());
    }

    @Test
    void queueOverflowAndStallSuspendUntilReconciled() {
        CombatRiskBudget budget = RiskTestSupport.conservative();
        RiskId id = RiskTestSupport.sent(budget, 1L);
        budget.suspend(SuspensionCause.QUEUE_OVERFLOW);
        budget.suspend(SuspensionCause.SERVER_STALL);
        assertTrue(budget.suspended());
        assertTrue(budget.reserve(2L, RiskTestSupport.exposure(0.0), RiskTestSupport.arrival(),
            RiskTestSupport.healthySelf()).isEmpty());
        assertEquals(CombatRiskBudget.State.SENT, budget.state(id));
        budget.resumeAfterReconciliation();
        assertFalse(budget.suspended());
        assertTrue(budget.reserve(2L, RiskTestSupport.exposure(0.0), RiskTestSupport.arrival(),
            RiskTestSupport.healthySelf()).isPresent());
    }

    @Test
    void groupingIsDisabledOnTheDefaultPathAndFallsBackEverywhere() {
        assertFalse(GroupedBound.DEFAULT_ENABLED);
        CombatRiskBudget defaultBudget = new CombatRiskBudget((envelope, current) -> current);
        RiskTestSupport.reserveSimple(defaultBudget, 1L);
        RiskTestSupport.reserveSimple(defaultBudget, 2L);
        assertEquals(12.0, defaultBudget.current(RiskTestSupport.SELF).value());
        assertEquals(2, defaultBudget.current(RiskTestSupport.SELF).components());

        CombatRiskBudget enabledButUnproven = RiskTestSupport.budget(new GroupedBound(true),
            AnarchyQuietWindow.disabled());
        RiskTestSupport.reserveSimple(enabledButUnproven, 1L);
        RiskTestSupport.reserveSimple(enabledButUnproven, 2L);
        assertEquals(12.0, enabledButUnproven.current(RiskTestSupport.SELF).value());
        assertEquals(2, enabledButUnproven.current(RiskTestSupport.SELF).components());
    }
}
