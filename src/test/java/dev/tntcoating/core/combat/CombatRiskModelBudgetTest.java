/*
 * Original Comet implementation by Boyonabike.
 * Independent: tests written against the project's own interfaces; no third-party code.
 * SPDX-License-Identifier: CC0-1.0
 */

package dev.tntcoating.core.combat;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * Stage 6 retirement models: STRICT is the default and never retires without rule-1 health evidence,
 * BARRIER retires only through a sequenced probe acknowledgement plus one further server tick, and
 * METEOR_STYLE retires on the observed event while still summing crystals that were sent but never seen
 * exploding.
 */
class CombatRiskModelBudgetTest {
    private static CombatRiskBudget budget(RiskModel model) {
        CombatRiskBudget budget = RiskTestSupport.conservative();
        budget.setRiskModel(model);
        return budget;
    }

    @Test
    void modelEnumCarriesTheRequiredOptionStrings() {
        assertEquals("Strict", RiskModel.STRICT.label());
        assertEquals("Barrier", RiskModel.BARRIER.label());
        assertEquals("Meteor-style", RiskModel.METEOR_STYLE.label());
        assertEquals("strict", RiskModel.STRICT.key());
        assertEquals("barrier", RiskModel.BARRIER.key());
        assertEquals("meteor-style", RiskModel.METEOR_STYLE.key());
        assertEquals("Strict", RiskModel.STRICT.toString());
        assertEquals("Barrier", RiskModel.BARRIER.toString());
        assertEquals("Meteor-style", RiskModel.METEOR_STYLE.toString());
        assertEquals(RiskModel.STRICT, RiskModel.defaultModel());
        for (RiskModel model : RiskModel.values()) {
            assertFalse(model.description().isBlank(), model + " must explain itself");
        }
    }

    @Test
    void strictIsTheDefaultForEveryBudgetFactory() {
        assertEquals(RiskModel.STRICT, RiskModel.defaultModel());
        assertEquals(RiskModel.STRICT,
            CombatProfiles.conservativeBudget((envelope, current) -> current).riskModel());
        CombatRiskBudget profiled = CombatProfiles.profileBudget(
            (envelope, current) -> current, new GroupedBound(false),
            AnarchyQuietWindow.disabled());
        assertEquals(RiskModel.STRICT, profiled.riskModel());
        assertEquals(RiskModel.STRICT, RiskTestSupport.conservative().riskModel());
    }

    @Test
    void strictKeepsDebtAfterAZeroDamageExplosionWithoutHealthEvidence() {
        CombatRiskBudget budget = budget(RiskModel.STRICT);
        RiskId id = RiskTestSupport.sentWithExplosion(budget, 1L, 3L);

        budget.tick(10);
        budget.tick(80);

        assertEquals(CombatRiskBudget.State.SENT, budget.state(id));
        assertEquals(1, budget.outstanding());
        assertEquals(1, budget.debt(RiskTestSupport.SELF).count());
        assertFalse(budget.barrierProbeDue());
    }

    @Test
    void barrierRetiresOnlyAfterTheAckAndOneFurtherServerTick() {
        CombatRiskBudget budget = budget(RiskModel.BARRIER);
        RiskId id = RiskTestSupport.sentWithExplosion(budget, 1L, 3L);
        budget.tick(10);
        assertTrue(budget.barrierProbeDue());

        budget.barrierProbeSent(17, 10, 3L, RiskTestSupport.CONN);
        assertTrue(budget.barrierProbeInFlight());
        assertTrue(budget.blockAck(17, 10));

        // The acknowledgement alone is not terminal evidence, and neither is the ack's own tick.
        assertEquals(CombatRiskBudget.State.SENT, budget.state(id));
        budget.tick(10);
        assertEquals(CombatRiskBudget.State.SENT, budget.state(id));
        assertEquals(1, budget.debt(RiskTestSupport.SELF).count());

        budget.tick(11);
        assertEquals(CombatRiskBudget.State.RETIRED, budget.state(id));
        assertEquals(0, budget.outstanding());
        assertEquals(Debt.zero(), budget.debt(RiskTestSupport.SELF));
        assertFalse(budget.barrierProbeInFlight());
    }

    @Test
    void barrierDoesNotRetireWhenAHealthSnapshotSharesTheExplosionBundle() {
        CombatRiskBudget budget = budget(RiskModel.BARRIER);
        RiskId id = RiskTestSupport.sentWithExplosion(budget, 1L, 3L);

        // ObservationIntake stamps a whole ClientboundBundlePacket with one applied revision, so an
        // explosion and its coherent post-explosion health snapshot can both be revision 3. The snapshot
        // retires nothing under rule 1, but it disproves the "no health change" premise the barrier needs.
        budget.observe(RiskTestSupport.health(RiskTestSupport.SELF, 14.0, 0.0, 3L, 4L));
        budget.tick(10);
        assertFalse(budget.barrierProbeDue(),
            "a risk that saw post-explosion health evidence is not probe-eligible");

        // Even a probe already in flight must not retire this risk on its ack.
        budget.barrierProbeSent(17, 10, 3L, RiskTestSupport.CONN);
        assertTrue(budget.blockAck(17, 10));
        budget.tick(11);

        assertEquals(CombatRiskBudget.State.SENT, budget.state(id));
        assertEquals(1, budget.outstanding());
        assertEquals(1, budget.debt(RiskTestSupport.SELF).count());
    }

    @Test
    void barrierHealthAppliedBeforeTheExplosionAtTheSameRevisionBlocksTheProbe() {
        CombatRiskBudget budget = budget(RiskModel.BARRIER);
        RiskId id = RiskTestSupport.sent(budget, 1L);

        // Packet/handler order inside one bundle is not fixed: the coherent health snapshot can be applied
        // before the explosion observation, both at the bundle's single revision. It still disproves the
        // "no health change" premise the barrier needs.
        budget.observe(RiskTestSupport.health(RiskTestSupport.SELF, 14.0, 0.0, 3L, 4L));
        budget.observe(RiskTestSupport.explosion(RiskTestSupport.CRYSTAL_POSITION, 3L, 3L));
        budget.tick(10);
        assertFalse(budget.barrierProbeDue(),
            "pre-explosion health evidence at the explosion revision is still health evidence");

        budget.barrierProbeSent(17, 10, 3L, RiskTestSupport.CONN);
        assertTrue(budget.blockAck(17, 10));
        budget.tick(11);

        assertEquals(CombatRiskBudget.State.SENT, budget.state(id));
        assertEquals(1, budget.outstanding());
        assertEquals(1, budget.debt(RiskTestSupport.SELF).count());
    }

    @Test
    void unrelatedHealthSnapshotDoesNotBlockTheBarrier() {
        CombatRiskBudget budget = budget(RiskModel.BARRIER);
        RiskId id = RiskTestSupport.sentWithExplosion(budget, 1L, 3L);
        EntityKey unrelated = new EntityKey(RiskTestSupport.CONN, 99,
            RiskTestSupport.SELF.uuid());

        budget.observe(RiskTestSupport.health(unrelated, 14.0, 0.0, 3L, 4L));
        budget.tick(10);
        assertTrue(budget.barrierProbeDue(),
            "health evidence must come from an entity this reservation bounds");

        budget.barrierProbeSent(17, 10, 3L, RiskTestSupport.CONN);
        assertTrue(budget.blockAck(17, 10));
        budget.tick(11);
        assertEquals(CombatRiskBudget.State.RETIRED, budget.state(id));
    }

    @Test
    void newerExplosionClearsEarlierHealthEvidence() {
        CombatRiskBudget budget = budget(RiskModel.BARRIER);
        RiskId id = RiskTestSupport.sentWithExplosion(budget, 1L, 3L);
        budget.observe(RiskTestSupport.health(RiskTestSupport.SELF, 14.0, 0.0, 3L, 4L));
        budget.tick(10);
        assertFalse(budget.barrierProbeDue());

        budget.observe(RiskTestSupport.explosion(RiskTestSupport.CRYSTAL_POSITION, 5L, 5L));
        budget.tick(11);
        assertTrue(budget.barrierProbeDue(),
            "a newer explosion resets the no-health-change premise");
        budget.barrierProbeSent(17, 11, 5L, RiskTestSupport.CONN);
        assertTrue(budget.blockAck(17, 11));
        budget.tick(12);

        assertEquals(CombatRiskBudget.State.RETIRED, budget.state(id));
        assertEquals(0, budget.outstanding());
    }

    @Test
    void barrierAckBeforeTheProbeWasSentIsRejected() {
        CombatRiskBudget budget = budget(RiskModel.BARRIER);
        RiskId id = RiskTestSupport.sentWithExplosion(budget, 1L, 3L);
        budget.tick(10);
        budget.barrierProbeSent(17, 10, 3L, RiskTestSupport.CONN);

        assertFalse(budget.blockAck(17, 9));
        assertTrue(budget.blockAck(17, 11));
        budget.tick(11);
        assertEquals(CombatRiskBudget.State.SENT, budget.state(id));
        budget.tick(12);
        assertEquals(CombatRiskBudget.State.RETIRED, budget.state(id));
    }

    @Test
    void barrierWrongOrRepeatedAckKeepsDebt() {
        CombatRiskBudget budget = budget(RiskModel.BARRIER);
        RiskId id = RiskTestSupport.sentWithExplosion(budget, 1L, 3L);
        budget.tick(10);
        budget.barrierProbeSent(17, 10, 3L, RiskTestSupport.CONN);

        assertFalse(budget.blockAck(18, 11), "a different sequence is not this probe");
        assertTrue(budget.blockAck(17, 11));
        assertFalse(budget.blockAck(17, 12), "the ack is applied only once");
        budget.tick(11);
        assertEquals(CombatRiskBudget.State.SENT, budget.state(id));
        budget.tick(12);
        assertEquals(CombatRiskBudget.State.RETIRED, budget.state(id));
    }

    @Test
    void barrierLostAckKeepsDebtPastTheProbeWindow() {
        CombatRiskBudget budget = budget(RiskModel.BARRIER);
        RiskId id = RiskTestSupport.sentWithExplosion(budget, 1L, 3L);
        budget.tick(10);
        budget.barrierProbeSent(17, 10, 3L, RiskTestSupport.CONN);

        budget.tick(50);
        assertTrue(budget.barrierProbeInFlight(), "exactly at the window edge the probe is still armed");

        budget.tick(51);
        assertFalse(budget.barrierProbeInFlight(), "the bounded window expires the probe");
        assertEquals(CombatRiskBudget.State.SENT, budget.state(id));
        assertEquals(1, budget.outstanding());
        assertEquals(1, budget.debt(RiskTestSupport.SELF).count(),
            "a lost ack and its timeout never retire debt");
    }

    @Test
    void barrierRetiresOnlyRisksWhoseExplosionPrecedesTheProbe() {
        CombatRiskBudget budget = budget(RiskModel.BARRIER);
        RiskId before = RiskTestSupport.sentWithExplosion(budget, 1L, 3L);
        budget.tick(10);
        budget.barrierProbeSent(17, 10, 3L, RiskTestSupport.CONN);
        assertTrue(budget.blockAck(17, 11));

        RiskId after = RiskTestSupport.reserveAt(budget, 2L, RiskTestSupport.exposure(6.0),
            RiskTestSupport.healthySelf(), RiskTestSupport.CRYSTAL_TWO,
            RiskTestSupport.CRYSTAL_TWO_POSITION);
        budget.dispatching(after);
        budget.mayHaveLeft(after);
        budget.observe(RiskTestSupport.explosionAt(RiskTestSupport.CONN,
            RiskTestSupport.CRYSTAL_TWO, RiskTestSupport.CRYSTAL_TWO_POSITION, 4L, 2L));

        budget.tick(12);
        assertEquals(CombatRiskBudget.State.RETIRED, budget.state(before));
        assertEquals(CombatRiskBudget.State.SENT, budget.state(after),
            "an explosion after the probe is not covered by that probe");
        assertEquals(1, budget.outstanding());
    }

    @Test
    void probeDoesNotRetireRisksFromAnotherConnectionOrAfterDimensionMigration() {
        CombatRiskBudget otherConn = budget(RiskModel.BARRIER);
        EntityKey otherSelf = new EntityKey(RiskTestSupport.CONN_TWO, 1,
            RiskTestSupport.SELF.uuid());
        EntityKey otherCrystal = new EntityKey(RiskTestSupport.CONN_TWO, 3,
            RiskTestSupport.CRYSTAL.uuid());
        SafetyState otherState = RiskTestSupport.stateFor(RiskTestSupport.CONN_TWO,
            RiskTestSupport.GEN, otherSelf, RiskTestSupport.known(20.0, 0.0, 0L), Map.of());
        RiskId other = RiskTestSupport.reserveAt(otherConn, 1L, RiskTestSupport.exposure(6.0),
            otherState, otherCrystal, RiskTestSupport.CRYSTAL_POSITION);
        otherConn.dispatching(other);
        otherConn.mayHaveLeft(other);
        otherConn.observe(RiskTestSupport.explosionAt(RiskTestSupport.CONN_TWO, otherCrystal,
            RiskTestSupport.CRYSTAL_POSITION, 3L, 3L));

        otherConn.tick(10);
        assertTrue(otherConn.barrierProbeDue(),
            "the reservation is probe-eligible on its own connection");
        otherConn.barrierProbeSent(17, 10, 3L, RiskTestSupport.CONN);
        assertTrue(otherConn.blockAck(17, 10));
        otherConn.tick(11);

        assertEquals(CombatRiskBudget.State.SENT, otherConn.state(other),
            "a probe sent on a different connection covers no reservation");
        assertEquals(1, otherConn.outstanding());

        CombatRiskBudget migrated = budget(RiskModel.BARRIER);
        RiskId migratedId = RiskTestSupport.sentWithExplosion(migrated, 1L, 3L);
        migrated.tick(10);
        migrated.dimensionChanged(RiskTestSupport.CONN, RiskTestSupport.CONN_TWO);
        assertFalse(migrated.barrierProbeDue(),
            "a dimension-migrated reservation is never probe-eligible");
        migrated.barrierProbeSent(17, 10, 3L, RiskTestSupport.CONN_TWO);
        assertTrue(migrated.blockAck(17, 10));
        migrated.tick(11);

        assertEquals(CombatRiskBudget.State.SENT, migrated.state(migratedId));
        assertEquals(1, migrated.outstanding());
    }

    @Test
    void probesOnlyArmForBarrier() {
        CombatRiskBudget strict = budget(RiskModel.STRICT);
        RiskId strictRisk = RiskTestSupport.sentWithExplosion(strict, 1L, 3L);
        strict.tick(10);
        assertFalse(strict.barrierProbeDue());
        strict.barrierProbeSent(17, 10, 3L, RiskTestSupport.CONN);
        assertFalse(strict.barrierProbeInFlight());
        strict.tick(11);
        assertEquals(CombatRiskBudget.State.SENT, strict.state(strictRisk));

        CombatRiskBudget meteor = budget(RiskModel.METEOR_STYLE);
        RiskId meteorRisk = RiskTestSupport.sent(meteor, 1L);
        meteor.tick(10);
        assertFalse(meteor.barrierProbeDue(), "METEOR_STYLE retires on the observation instead");
        meteor.barrierProbeSent(17, 10, 3L, RiskTestSupport.CONN);
        assertFalse(meteor.barrierProbeInFlight());
        assertEquals(CombatRiskBudget.State.SENT, meteor.state(meteorRisk));
    }

    @Test
    void modelSwitchKeepsSentRiskAndDropsOnlyProbeEvidence() {
        CombatRiskBudget budget = budget(RiskModel.BARRIER);
        RiskId id = RiskTestSupport.sentWithExplosion(budget, 1L, 3L);
        budget.tick(10);
        budget.barrierProbeSent(17, 10, 3L, RiskTestSupport.CONN);
        assertTrue(budget.barrierProbeInFlight());

        budget.setRiskModel(RiskModel.STRICT);

        assertEquals(CombatRiskBudget.State.SENT, budget.state(id));
        assertEquals(1, budget.outstanding());
        assertEquals(1, budget.debt(RiskTestSupport.SELF).count());
        assertFalse(budget.barrierProbeInFlight());

        budget.setRiskModel(RiskModel.BARRIER);
        assertTrue(budget.barrierProbeDue(), "the risk is still eligible once BARRIER returns");
    }

    @Test
    void meteorStyleRetiresAZeroDamageExplosionOnObservation() {
        CombatRiskBudget budget = budget(RiskModel.METEOR_STYLE);
        RiskId id = RiskTestSupport.sentWithExplosion(budget, 1L, 3L);

        assertEquals(CombatRiskBudget.State.RETIRED, budget.state(id));
        assertEquals(0, budget.outstanding());
        assertEquals(Debt.zero(), budget.debt(RiskTestSupport.SELF));
        assertFalse(budget.barrierProbeDue());

        // The engine keeps placing: the retired risk no longer consumes the remaining-health margin.
        RiskId next = RiskTestSupport.reserveSimple(budget, 2L);
        assertTrue(next.value() > id.value());
        assertEquals(CombatRiskBudget.State.RESERVED, budget.state(next));
    }

    @Test
    void meteorStyleSumsSentCrystalsThatWereNeverSeenExploding() {
        CombatRiskBudget budget = budget(RiskModel.METEOR_STYLE);
        RiskId first = RiskTestSupport.reserveAt(budget, 1L, RiskTestSupport.exposure(6.0),
            RiskTestSupport.healthySelf(), RiskTestSupport.CRYSTAL,
            RiskTestSupport.CRYSTAL_POSITION);
        budget.dispatching(first);
        budget.mayHaveLeft(first);
        RiskId second = RiskTestSupport.reserveAt(budget, 2L, RiskTestSupport.exposure(6.0),
            RiskTestSupport.healthySelf(), RiskTestSupport.CRYSTAL_TWO,
            RiskTestSupport.CRYSTAL_TWO_POSITION);
        budget.dispatching(second);
        budget.mayHaveLeft(second);

        // Two in-flight six-damage crystals already leave exactly the remaining-health margin.
        assertTrue(budget.reserve(3L, RiskTestSupport.exposure(6.0), RiskTestSupport.arrival(),
            RiskTestSupport.healthySelf()).isEmpty());
        assertEquals(2, budget.outstanding());

        // A health update cannot retire a crystal whose explosion was never observed.
        budget.observe(RiskTestSupport.health(RiskTestSupport.SELF, 20.0, 0.0, 1L, 1L));
        assertEquals(2, budget.outstanding());

        // Only the crystal whose explosion was observed retires; the other stays counted.
        budget.observe(RiskTestSupport.explosionAt(RiskTestSupport.CONN, RiskTestSupport.CRYSTAL,
            RiskTestSupport.CRYSTAL_POSITION, 3L, 2L));
        assertEquals(CombatRiskBudget.State.RETIRED, budget.state(first));
        assertEquals(CombatRiskBudget.State.SENT, budget.state(second));
        assertEquals(1, budget.outstanding());
        RiskId third = RiskTestSupport.reserveAt(budget, 3L, RiskTestSupport.exposure(6.0),
            RiskTestSupport.healthySelf(), RiskTestSupport.CRYSTAL_TWO,
            RiskTestSupport.CRYSTAL_TWO_POSITION);
        assertEquals(CombatRiskBudget.State.RESERVED, budget.state(third));
    }

    @Test
    void removalRetiresOnlyUnderMeteorStyle() {
        for (RiskModel model : RiskModel.values()) {
            CombatRiskBudget budget = budget(model);
            RiskId id = RiskTestSupport.sent(budget, 1L);
            budget.observe(RiskTestSupport.trackingRemoval(RiskTestSupport.CRYSTAL, 1L));
            CombatRiskBudget.State expected = model == RiskModel.METEOR_STYLE
                ? CombatRiskBudget.State.RETIRED
                : CombatRiskBudget.State.SENT;
            assertEquals(expected, budget.state(id), model + " removal behaviour");
            if (model != RiskModel.METEOR_STYLE) {
                assertEquals(1, budget.debt(RiskTestSupport.SELF).count());
            }
        }
    }

    @Test
    void modelChangesAndBarrierRetirementAreLoggedOnce() {
        CombatRiskBudget budget = budget(RiskModel.STRICT);
        List<String> lines = new ArrayList<>();
        budget.setEventSink(lines::add);

        budget.setRiskModel(RiskModel.BARRIER);
        budget.setRiskModel(RiskModel.BARRIER);
        assertEquals(List.of("risk-model=barrier outstanding=0"), lines,
            "a model change is announced once, and re-selecting the same model is silent");

        RiskId id = RiskTestSupport.sentWithExplosion(budget, 1L, 3L);
        budget.tick(10);
        assertTrue(budget.barrierProbeDue());
        budget.barrierProbeSent(17, 10, 3L, RiskTestSupport.CONN);
        assertTrue(budget.blockAck(17, 10));
        budget.tick(11);

        assertEquals(CombatRiskBudget.State.RETIRED, budget.state(id));
        assertEquals(1, lines.stream().filter(line -> line.startsWith("BARRIER_RETIRE risk=")).count());
        assertEquals(1, lines.stream().filter(line -> line.startsWith("BARRIER_RETIRE_TOTAL ")).count());
    }

    @Test
    void barrierPerRiskLogCountsOutstandingAfterEachRetirement() {
        CombatRiskBudget budget = budget(RiskModel.BARRIER);
        List<String> lines = new ArrayList<>();
        budget.setEventSink(lines::add);
        RiskId first = RiskTestSupport.sentWithExplosion(budget, 1L, 3L);
        RiskId second = RiskTestSupport.sentWithExplosion(budget, 2L, 3L);

        budget.tick(10);
        budget.barrierProbeSent(17, 10, 3L, RiskTestSupport.CONN);
        assertTrue(budget.blockAck(17, 10));
        budget.tick(11);

        assertEquals(CombatRiskBudget.State.RETIRED, budget.state(first));
        assertEquals(CombatRiskBudget.State.RETIRED, budget.state(second));
        List<String> retires = lines.stream()
            .filter(line -> line.startsWith("BARRIER_RETIRE risk="))
            .toList();
        assertEquals(2, retires.size());
        assertTrue(retires.get(0).endsWith("outstanding=1"), retires.get(0));
        assertTrue(retires.get(1).endsWith("outstanding=0"), retires.get(1));
        assertEquals(1, lines.stream().filter(line -> line.startsWith("BARRIER_RETIRE_TOTAL ")).count());
        assertTrue(lines.get(lines.size() - 1).startsWith("BARRIER_RETIRE_TOTAL retired=2 outstanding=0"));
    }

    @Test
    void meteorPerRiskLogCountsOutstandingAfterEachRetirement() {
        CombatRiskBudget budget = budget(RiskModel.METEOR_STYLE);
        List<String> lines = new ArrayList<>();
        budget.setEventSink(lines::add);
        RiskId first = RiskTestSupport.sent(budget, 1L);
        RiskId second = RiskTestSupport.sent(budget, 2L);

        budget.observe(RiskTestSupport.explosion(RiskTestSupport.CRYSTAL_POSITION, 3L, 3L));

        assertEquals(CombatRiskBudget.State.RETIRED, budget.state(first));
        assertEquals(CombatRiskBudget.State.RETIRED, budget.state(second));
        List<String> retires = lines.stream()
            .filter(line -> line.startsWith("METEOR_RETIRE risk="))
            .toList();
        assertEquals(2, retires.size());
        assertTrue(retires.get(0).endsWith("outstanding=1"), retires.get(0));
        assertTrue(retires.get(1).endsWith("outstanding=0"), retires.get(1));
    }
}
