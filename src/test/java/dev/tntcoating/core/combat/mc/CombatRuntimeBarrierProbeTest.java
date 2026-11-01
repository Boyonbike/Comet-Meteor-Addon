/*
 * Original Comet implementation by Boyonabike.
 * Independent: runtime tests written against the project's own interfaces; no third-party code.
 * SPDX-License-Identifier: CC0-1.0
 */

package dev.tntcoating.core.combat.mc;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.tntcoating.core.combat.Cell;
import dev.tntcoating.core.combat.CombatRiskBudget;
import dev.tntcoating.core.combat.Intent;
import dev.tntcoating.core.combat.Observation;
import dev.tntcoating.core.combat.ObservationKind;
import dev.tntcoating.core.combat.ObservationPayload;
import dev.tntcoating.core.combat.RiskId;
import dev.tntcoating.core.combat.RiskModel;
import dev.tntcoating.core.combat.Vec;
import net.minecraft.core.BlockPos;
import org.junit.jupiter.api.Test;

/**
 * Runtime ownership of the BARRIER probe: exactly one packet-only, sequenced probe per tick, only on the
 * client-thread tick path, only after an applied explosion observation left eligible debt, and never once
 * the session, the runtime or a suspension says stop.
 */
final class CombatRuntimeBarrierProbeTest {
    private static final CombatTestFakes.FakeSink.Kind PROBE =
        CombatTestFakes.FakeSink.Kind.PROBE;

    private static CombatRuntimeFixture enabled(RiskModel model) {
        CombatRuntimeFixture fixture = new CombatRuntimeFixture();
        fixture.budget.setRiskModel(model);
        fixture.runtime.onEnabled();
        return fixture;
    }

    private static RiskId sentPlace(CombatRuntimeFixture fixture) {
        Intent intent = fixture.place();
        RiskId id = fixture.reserve(intent);
        assertEquals(CombatRuntime.Status.SENT, fixture.runtime.dispatch(intent, fixture.ticket).status());
        return id;
    }

    private static Observation explosion(CombatRuntimeFixture fixture, long appliedRevision) {
        return new Observation(fixture.ticket.conn(), appliedRevision, appliedRevision,
            appliedRevision, 0, ObservationKind.EXPLOSION, fixture.crystal(), null,
            new Vec(0.5, 64.5, 0.5), null, new ObservationPayload.Explosion(6.0));
    }

    private static RiskId arm(CombatRuntimeFixture fixture) {
        RiskId id = sentPlace(fixture);
        fixture.budget.observe(explosion(fixture, 0L));
        return id;
    }

    @Test
    void barrierSendsOneSequencedProbeAndRetiresOnlyAfterAckPlusOneTick() {
        CombatRuntimeFixture fixture = enabled(RiskModel.BARRIER);
        RiskId id = sentPlace(fixture);
        fixture.budget.observe(explosion(fixture, 0L));
        fixture.sink.probeSequence = 7;

        fixture.runtime.tick(3);

        assertEquals(1, fixture.sink.probes.size());
        assertEquals(new BlockPos(0, 66, 0), fixture.sink.probes.get(0),
            "the probe uses the first air block above the player's eye");
        assertEquals(1, fixture.sink.count(PROBE));
        assertTrue(fixture.budget.barrierProbeInFlight());

        // One probe per tick, and none while the first is in flight.
        fixture.runtime.tick(4);
        assertEquals(1, fixture.sink.probes.size());
        assertEquals(CombatRiskBudget.State.SENT, fixture.budget.state(id));

        fixture.runtime.onBlockChangedAck(7);
        assertEquals(CombatRiskBudget.State.SENT, fixture.budget.state(id),
            "the ack alone is not terminal evidence");

        fixture.runtime.tick(5);
        assertEquals(CombatRiskBudget.State.RETIRED, fixture.budget.state(id));
        assertEquals(0, fixture.budget.outstanding());
        assertFalse(fixture.budget.barrierProbeInFlight());
        assertEquals(1, fixture.sink.count(PROBE));
    }

    @Test
    void oneInFlightProbeSuppressesFurtherProbesInsideItsWindow() {
        CombatRuntimeFixture fixture = enabled(RiskModel.BARRIER);
        sentPlace(fixture);
        fixture.budget.observe(explosion(fixture, 0L));
        fixture.sink.probeSequence = 7;

        fixture.runtime.tick(3);
        fixture.runtime.tick(4);
        fixture.runtime.tick(30);

        assertEquals(1, fixture.sink.probes.size(),
            "a due risk gets no second probe while the first is still in flight");
        assertEquals(1, fixture.sink.count(PROBE));
        assertTrue(fixture.budget.barrierProbeInFlight());
    }

    @Test
    void wrongAckSequenceNeverRetiresDebt() {
        CombatRuntimeFixture fixture = enabled(RiskModel.BARRIER);
        RiskId id = sentPlace(fixture);
        fixture.budget.observe(explosion(fixture, 0L));
        fixture.sink.probeSequence = 7;
        fixture.runtime.tick(3);

        fixture.runtime.onBlockChangedAck(8);
        fixture.runtime.tick(4);

        assertEquals(CombatRiskBudget.State.SENT, fixture.budget.state(id));
        assertEquals(1, fixture.budget.debt(fixture.self()).count());
        assertEquals(1, fixture.budget.outstanding());
    }

    @Test
    void strictNeverSendsAProbe() {
        CombatRuntimeFixture fixture = enabled(RiskModel.STRICT);
        RiskId id = sentPlace(fixture);
        fixture.budget.observe(explosion(fixture, 0L));
        int attempts = fixture.sink.attempts;

        fixture.runtime.tick(3);

        assertEquals(0, fixture.sink.probes.size());
        assertEquals(attempts, fixture.sink.attempts);
        assertEquals(CombatRiskBudget.State.SENT, fixture.budget.state(id));
    }

    @Test
    void meteorStyleRetiresOnObservationAndKeepsPlacing() {
        CombatRuntimeFixture fixture = enabled(RiskModel.METEOR_STYLE);
        RiskId id = sentPlace(fixture);
        fixture.budget.observe(explosion(fixture, 0L));
        assertEquals(CombatRiskBudget.State.RETIRED, fixture.budget.state(id));

        fixture.runtime.tick(3);

        assertEquals(0, fixture.sink.probes.size());
        assertEquals(0, fixture.budget.outstanding());

        Intent next = fixture.placeAt(new Cell(2, 64, 0));
        fixture.reserve(next);
        assertEquals(CombatRuntime.Status.SENT, fixture.runtime.dispatch(next, fixture.ticket).status());
        assertEquals(1, fixture.budget.outstanding());
    }

    @Test
    void barrierNeverProbesWithoutExplosionEvidence() {
        CombatRuntimeFixture fixture = enabled(RiskModel.BARRIER);
        sentPlace(fixture);
        int attempts = fixture.sink.attempts;

        fixture.runtime.tick(3);

        assertEquals(0, fixture.sink.probes.size());
        assertEquals(attempts, fixture.sink.attempts);
    }

    @Test
    void probeIsSilentWithoutAnEnabledRuntime() {
        CombatRuntimeFixture fixture = new CombatRuntimeFixture();
        fixture.budget.setRiskModel(RiskModel.BARRIER);
        sentPlace(fixture);
        fixture.budget.observe(explosion(fixture, 0L));
        int attempts = fixture.sink.attempts;

        fixture.runtime.tick(3);

        assertEquals(0, fixture.sink.probes.size());
        assertEquals(attempts, fixture.sink.attempts);
    }

    @Test
    void probeIsSentOnlyFromTheClientThreadTickPath() {
        CombatRuntimeFixture fixture = enabled(RiskModel.BARRIER);
        sentPlace(fixture);
        fixture.budget.observe(explosion(fixture, 0L));
        fixture.sink.probeSequence = 7;

        fixture.client.onClientThread = false;
        fixture.runtime.tick(3);
        assertEquals(0, fixture.sink.probes.size());

        fixture.client.onClientThread = true;
        fixture.runtime.tick(3);
        assertEquals(1, fixture.sink.probes.size());
    }

    @Test
    void probeRespectsTheSharedPacketBudget() {
        CombatRuntimeFixture fixture = new CombatRuntimeFixture(2, false);
        fixture.budget.setRiskModel(RiskModel.BARRIER);
        fixture.runtime.onEnabled();
        RiskId id = sentPlace(fixture);
        fixture.budget.observe(explosion(fixture, 0L));
        assertFalse(fixture.scheduler.packetBudgetAvailable(3, 1));

        fixture.runtime.tick(3);

        assertEquals(0, fixture.sink.probes.size());
        assertEquals(CombatRiskBudget.State.SENT, fixture.budget.state(id));
        assertFalse(fixture.budget.barrierProbeInFlight());
    }

    @Test
    void failedProbeKeepsDebtAndDoesNotBlockDirectSends() {
        CombatRuntimeFixture fixture = enabled(RiskModel.BARRIER);
        RiskId id = sentPlace(fixture);
        fixture.budget.observe(explosion(fixture, 0L));
        fixture.sink.failOnAttempt = fixture.sink.attempts + 1;

        fixture.runtime.tick(3);

        assertEquals(1, fixture.sink.probes.size(), "the attempt happened");
        assertFalse(fixture.budget.barrierProbeInFlight(), "a refused probe is never armed");
        assertEquals(CombatRiskBudget.State.SENT, fixture.budget.state(id));
        assertEquals(1, fixture.budget.debt(fixture.self()).count());
        assertEquals(1, fixture.scheduler.leaseCount(),
            "the failed probe releases its packet-only lease; only the sent placement lease remains");

        Intent direct = fixture.placeAt(new Cell(2, 64, 0));
        fixture.reserve(direct);
        assertEquals(CombatRuntime.Status.SENT,
            fixture.runtime.dispatch(direct, fixture.ticket).status(),
            "a failed probe leaves the packet path usable");
        assertEquals(2, fixture.budget.outstanding());
    }

    @Test
    void probeIsDelayedWhileOurOwnBlockBreakingIsInProgress() {
        CombatRuntimeFixture fixture = enabled(RiskModel.BARRIER);
        fixture.sink.probeSequence = 7;
        Cell cell = new Cell(2, 64, 0);
        fixture.client.blocks.put(cell, "minecraft:obsidian");
        Intent mine = fixture.mine(cell);
        fixture.reserve(mine, fixture.mineContext("minecraft:obsidian"));
        assertEquals(CombatRuntime.Status.SENT,
            fixture.runtime.dispatch(mine, fixture.ticket).status());
        fixture.budget.observe(explosion(fixture, 0L));

        fixture.runtime.tick(3);
        assertEquals(0, fixture.sink.probes.size(),
            "the abort would cancel the cleanup destroy this runtime just started");
        assertFalse(fixture.budget.barrierProbeInFlight());

        assertTrue(fixture.runtime.stopDestruction());
        fixture.runtime.tick(4);
        assertEquals(1, fixture.sink.probes.size(), "the probe resumes once the break has stopped");
        assertEquals(new BlockPos(0, 66, 0), fixture.sink.probes.get(0));
        assertTrue(fixture.budget.barrierProbeInFlight());
    }

    @Test
    void abandonedProbeWindowKeepsDebtAndNeverSendsASecondProbe() {
        CombatRuntimeFixture fixture = enabled(RiskModel.BARRIER);
        RiskId id = arm(fixture);
        fixture.sink.probeSequence = 7;

        fixture.runtime.tick(3);
        assertEquals(1, fixture.sink.probes.size());
        assertTrue(fixture.budget.barrierProbeInFlight());

        // No ack: the probe's own 40-tick window expires, and the reservation is older than its own
        // probe window, so it is abandoned rather than probed again.
        fixture.runtime.tick(44);
        assertFalse(fixture.budget.barrierProbeInFlight());
        assertEquals(CombatRiskBudget.State.SENT, fixture.budget.state(id));
        assertEquals(1, fixture.budget.outstanding());
        assertEquals(1, fixture.sink.probes.size());

        fixture.runtime.tick(45);
        fixture.runtime.tick(80);
        assertEquals(1, fixture.sink.probes.size(),
            "an abandoned reservation never gets a second probe");
        assertEquals(CombatRiskBudget.State.SENT, fixture.budget.state(id));
        assertEquals(1, fixture.budget.outstanding());
    }

    @Test
    void disableDeathAndConnectionCloseStopTheProbe() {
        CombatRuntimeFixture disabled = enabled(RiskModel.BARRIER);
        arm(disabled);
        int disabledAttempts = disabled.sink.attempts;
        disabled.runtime.onDisabled();
        disabled.runtime.tick(3);
        assertEquals(0, disabled.sink.probes.size());
        assertEquals(disabledAttempts, disabled.sink.attempts);

        CombatRuntimeFixture dead = enabled(RiskModel.BARRIER);
        RiskId deadRisk = arm(dead);
        int deadAttempts = dead.sink.attempts;
        dead.runtime.onDeath();
        dead.runtime.tick(3);
        assertEquals(0, dead.sink.probes.size());
        assertEquals(deadAttempts, dead.sink.attempts);
        assertEquals(CombatRiskBudget.State.SENT, dead.budget.state(deadRisk),
            "death keeps the debt it cannot prove");

        CombatRuntimeFixture closed = enabled(RiskModel.BARRIER);
        arm(closed);
        int closedAttempts = closed.sink.attempts;
        closed.runtime.onConnectionClosed();
        closed.runtime.tick(3);
        assertEquals(0, closed.sink.probes.size());
        assertEquals(closedAttempts, closed.sink.attempts);
    }
}
