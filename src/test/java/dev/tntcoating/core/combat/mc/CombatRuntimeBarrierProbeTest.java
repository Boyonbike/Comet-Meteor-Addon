/*
 * Original Comet implementation by Boyonabike.
 * Independent: tests written against the project's own interfaces; no third-party code.
 * SPDX-License-Identifier: CC0-1.0
 */

package dev.tntcoating.core.combat.mc;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.tntcoating.core.combat.CombatRiskBudget;
import dev.tntcoating.core.combat.EntityKey;
import dev.tntcoating.core.combat.Intent;
import dev.tntcoating.core.combat.Observation;
import dev.tntcoating.core.combat.ObservationKind;
import dev.tntcoating.core.combat.ObservationPayload;
import dev.tntcoating.core.combat.RiskId;
import dev.tntcoating.core.combat.RiskModel;
import net.minecraft.core.BlockPos;
import org.junit.jupiter.api.Test;

/** Runtime ownership of the BARRIER probe: one sequenced, packet-only probe from the tick path. */
final class CombatRuntimeBarrierProbeTest {
    private static CombatRuntimeFixture enabled(RiskModel model) {
        CombatRuntimeFixture fixture = new CombatRuntimeFixture();
        fixture.risk.setRiskModel(model);
        fixture.runtime.onEnabled();
        return fixture;
    }

    private static RiskId sentPlace(CombatRuntimeFixture fixture) {
        Intent intent = fixture.place();
        CombatRuntime.PlanContext context = fixture.exposure();
        assertTrue(fixture.reserve(intent, context));
        CombatRuntime.DispatchResult result = fixture.dispatch(intent, context);
        assertEquals(CombatRuntime.Status.SENT, result.status(), result.reason().name());
        return fixture.scheduler.riskId(CombatRuntimeFixture.PLAN, intent.id()).orElseThrow();
    }

    private static EntityKey bind(CombatRuntimeFixture fixture, RiskId risk) {
        EntityKey crystal = fixture.crystal();
        assertTrue(fixture.risk.bindCrystal(risk, crystal,
            CombatRuntimeFixture.CRYSTAL_POSITION));
        return crystal;
    }

    private static void explosion(CombatRuntimeFixture fixture, EntityKey crystal) {
        fixture.risk.observe(new Observation(fixture.session.connection(), 0L, 0L, 0L, 0,
            ObservationKind.EXPLOSION, crystal, null, CombatRuntimeFixture.CRYSTAL_POSITION, null,
            new ObservationPayload.Explosion(6.0)));
    }

    @Test
    void barrierSendsOneProbeAndRetiresOnlyAfterAckPlusOneTick() {
        CombatRuntimeFixture fixture = enabled(RiskModel.BARRIER);
        RiskId risk = sentPlace(fixture);
        EntityKey crystal = bind(fixture, risk);
        explosion(fixture, crystal);
        fixture.sink.probeSequence = 7;

        fixture.runtime.tick(3);
        assertEquals(1, fixture.sink.probes.size());
        assertEquals(new BlockPos(0, 66, 0), fixture.sink.probes.get(0));
        assertTrue(fixture.risk.barrierProbeInFlight());

        fixture.runtime.tick(4);
        assertEquals(1, fixture.sink.probes.size());
        assertEquals(CombatRiskBudget.State.SENT, fixture.risk.state(risk));

        fixture.runtime.onBlockChangedAck(7);
        assertEquals(CombatRiskBudget.State.SENT, fixture.risk.state(risk));
        fixture.runtime.tick(5);
        assertEquals(CombatRiskBudget.State.RETIRED, fixture.risk.state(risk));
        assertEquals(0, fixture.risk.outstanding());
        assertFalse(fixture.risk.barrierProbeInFlight());
    }

    @Test
    void wrongAckSequenceNeverRetiresDebt() {
        CombatRuntimeFixture fixture = enabled(RiskModel.BARRIER);
        RiskId risk = sentPlace(fixture);
        EntityKey crystal = bind(fixture, risk);
        explosion(fixture, crystal);
        fixture.sink.probeSequence = 7;
        fixture.runtime.tick(3);
        fixture.runtime.onBlockChangedAck(8);
        fixture.runtime.tick(4);
        assertEquals(CombatRiskBudget.State.SENT, fixture.risk.state(risk));
        assertEquals(1, fixture.risk.debt(fixture.self()).count());
    }

    @Test
    void strictAndMeteorModelsNeverProbe() {
        CombatRuntimeFixture strict = enabled(RiskModel.STRICT);
        RiskId strictRisk = sentPlace(strict);
        EntityKey strictCrystal = bind(strict, strictRisk);
        explosion(strict, strictCrystal);
        int attempts = strict.sink.attempts;
        strict.runtime.tick(3);
        assertEquals(0, strict.sink.probes.size());
        assertEquals(attempts, strict.sink.attempts);
        assertEquals(CombatRiskBudget.State.SENT, strict.risk.state(strictRisk));

        CombatRuntimeFixture meteor = enabled(RiskModel.METEOR_STYLE);
        RiskId meteorRisk = sentPlace(meteor);
        EntityKey meteorCrystal = bind(meteor, meteorRisk);
        explosion(meteor, meteorCrystal);
        assertEquals(CombatRiskBudget.State.RETIRED, meteor.risk.state(meteorRisk));
        meteor.runtime.tick(3);
        assertEquals(0, meteor.sink.probes.size());
        assertEquals(0, meteor.risk.outstanding());
    }

    @Test
    void noProbeWithoutExplosionEvidenceOrWithoutEnablement() {
        CombatRuntimeFixture quiet = enabled(RiskModel.BARRIER);
        RiskId quietRisk = sentPlace(quiet);
        bind(quiet, quietRisk);
        int attempts = quiet.sink.attempts;
        quiet.runtime.tick(3);
        assertEquals(0, quiet.sink.probes.size());
        assertEquals(attempts, quiet.sink.attempts);

        CombatRuntimeFixture disabled = new CombatRuntimeFixture();
        disabled.risk.setRiskModel(RiskModel.BARRIER);
        RiskId disabledRisk = sentPlace(disabled);
        EntityKey disabledCrystal = bind(disabled, disabledRisk);
        explosion(disabled, disabledCrystal);
        disabled.runtime.tick(3);
        assertEquals(0, disabled.sink.probes.size());
    }

    @Test
    void probeOnlyFromTheClientThreadTickPath() {
        CombatRuntimeFixture fixture = enabled(RiskModel.BARRIER);
        RiskId risk = sentPlace(fixture);
        EntityKey crystal = bind(fixture, risk);
        explosion(fixture, crystal);
        fixture.sink.probeSequence = 7;
        fixture.client.onClientThread = false;
        fixture.runtime.tick(3);
        assertEquals(0, fixture.sink.probes.size());
        fixture.client.onClientThread = true;
        fixture.runtime.tick(3);
        assertEquals(1, fixture.sink.probes.size());
    }

    @Test
    void probeRefusedWhenThePredictionSequenceIsMissing() {
        CombatRuntimeFixture fixture = enabled(RiskModel.BARRIER);
        RiskId risk = sentPlace(fixture);
        EntityKey crystal = bind(fixture, risk);
        explosion(fixture, crystal);
        fixture.sink.probeSequence = -1;
        fixture.runtime.tick(3);
        assertEquals(1, fixture.sink.probes.size());
        assertFalse(fixture.risk.barrierProbeInFlight());
        fixture.runtime.tick(4);
        assertEquals(CombatRiskBudget.State.SENT, fixture.risk.state(risk));
    }
}
