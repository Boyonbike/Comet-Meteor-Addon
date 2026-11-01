/*
 * Original Comet implementation by Boyonabike.
 * Independent: tests written against the project's own interfaces; no third-party code.
 * SPDX-License-Identifier: CC0-1.0
 */

package dev.tntcoating.core.combat.mc;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.tntcoating.core.combat.Box;
import dev.tntcoating.core.combat.CombatRiskBudget;
import dev.tntcoating.core.combat.CrystalCombatGate;
import dev.tntcoating.core.combat.HazardId;
import dev.tntcoating.core.combat.Intent;
import dev.tntcoating.core.combat.RiskId;
import dev.tntcoating.core.combat.SuspensionCause;
import dev.tntcoating.core.combat.Ticket;
import java.util.Set;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/**
 * Stage 5 acceptance: the profile switch path (module {@code configChanged} → controller
 * {@code onConfigChanged} → runtime {@code onConfigChanged} → session {@code reconfigure}) invalidates
 * queued unsent intents while sent risk, hazards and suspension evidence stay in place. The test drives
 * the real controller and runtime with the fake client/sink. The module-side wiring (profile setting →
 * {@code profileChanged} → {@code configChanged} → {@code controller.onConfigChanged}) is asserted by
 * {@code CrystalCombatPhase8SettingsTest}.
 */
final class CrystalCombatConfigChangeTest {

    @BeforeAll
    static void bootstrap() {
        AdapterTestSupport.bootstrap();
    }

    @AfterEach
    void clearGate() {
        CrystalCombatGate.deactivate();
        CrystalCombatGate.setProtector(null);
    }

    @Test
    void profileStyleConfigChangeDropsUnsentIntentsButKeepsSentRiskHazardsAndSuspension() {
        CombatRuntimeFixture fixture = new CombatRuntimeFixture();
        CrystalCombatController controller =
            new CrystalCombatController(() -> null, fixture::controllerStack);
        assertTrue(controller.enable());

        Intent place = fixture.place();
        RiskId sent = fixture.reserve(place);
        assertEquals(CombatRuntime.Status.SENT,
            fixture.runtime.dispatch(place, fixture.ticket).status(),
            "the fixture's sent action is the risk the switch must not retire");
        assertEquals(CombatRiskBudget.State.SENT, fixture.budget.state(sent));
        int outstandingBefore = fixture.budget.outstanding();

        HazardId hazard = fixture.crystals.reserveSpawn(fixture.session.connection(),
            Box.fromCell(CombatRuntimeFixture.CELL), Box.fromCell(CombatRuntimeFixture.CELL), Set.of());
        fixture.crystals.markSent(hazard, 0);
        fixture.budget.suspend(SuspensionCause.SETBACK);

        long configBefore = fixture.session.config();
        Ticket previous = fixture.session.ticket(CombatRuntimeFixture.PLAN);
        int attemptsBefore = fixture.sink.attempts;

        controller.onConfigChanged();

        assertEquals(configBefore + 1, fixture.session.config(),
            "the switch advances the config epoch");
        assertFalse(fixture.session.valid(previous),
            "tickets issued before the switch are invalid");
        assertNotEquals(previous.gen(), fixture.session.ticket(CombatRuntimeFixture.PLAN).gen(),
            "the action generation ended so queued unsent actions cannot dispatch");
        assertEquals(0, fixture.scheduler.leaseCount(),
            "the scheduler dropped every unsent lease");
        assertEquals(0, fixture.sink.attempts - attemptsBefore,
            "a config change never sends");
        assertEquals(CombatRiskBudget.State.SENT, fixture.budget.state(sent),
            "sent risk is never retired by a profile/config switch");
        assertEquals(outstandingBefore, fixture.budget.outstanding(),
            "outstanding sent risk stays counted");
        assertTrue(fixture.budget.suspensionCauses().contains(SuspensionCause.SETBACK),
            "a switch never clears a suspension hazard");
        assertTrue(fixture.crystals.unresolved(hazard),
            "a switch never clears a sent hazard");
        assertEquals(0, fixture.sink.attempts - attemptsBefore);
    }

    @Test
    void aQueuedUnsentLeaseIsDroppedByTheSameConfigChange() {
        CombatRuntimeFixture fixture = new CombatRuntimeFixture();
        CrystalCombatController controller =
            new CrystalCombatController(() -> null, fixture::controllerStack);
        assertTrue(controller.enable());
        Ticket previous = fixture.session.ticket(CombatRuntimeFixture.PLAN);
        Intent pending = fixture.attack();
        fixture.reserve(pending);
        assertTrue(fixture.scheduler.leaseCount() > 0, "the fixture has an unsent queued intent");
        int attempts = fixture.sink.attempts;

        controller.onConfigChanged();

        assertEquals(0, fixture.scheduler.leaseCount(),
            "the config switch drops the queued unsent lease");
        assertFalse(fixture.session.valid(previous), "the old ticket cannot dispatch");
        assertEquals(attempts, fixture.sink.attempts, "dropping the lease sends nothing");
    }

    @Test
    void switchingWhileDisabledChangesNothingAndSendsNothing() {
        CombatRuntimeFixture fixture = new CombatRuntimeFixture();
        CrystalCombatController controller =
            new CrystalCombatController(() -> null, fixture::controllerStack);
        long config = fixture.session.config();
        int attempts = fixture.sink.attempts;
        controller.onConfigChanged();
        assertEquals(config, fixture.session.config());
        assertEquals(attempts, fixture.sink.attempts);
    }
}
