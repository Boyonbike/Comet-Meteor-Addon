/*
 * Original Comet implementation by Boyonabike.
 * Independent: tests written against the project's own interfaces; no third-party code.
 * SPDX-License-Identifier: CC0-1.0
 */

package dev.tntcoating.core.combat.mc;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.tntcoating.core.combat.CrystalCombatGate;
import dev.tntcoating.core.combat.Intent;
import dev.tntcoating.core.combat.RiskDebtSnapshot;
import dev.tntcoating.core.combat.SuspensionCause;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/**
 * Stage 5 acceptance: the render thread reads only the volatile snapshot the client tick publishes.
 * These tests drive the real controller with the fake runtime and assert the snapshot's lifecycle.
 */
final class CrystalCombatRiskSnapshotTest {

    @BeforeAll
    static void bootstrap() {
        AdapterTestSupport.bootstrap();
    }

    @AfterEach
    void clearGate() {
        CrystalCombatGate.deactivate();
        CrystalCombatGate.setProtector(null);
    }

    private static CrystalCombatController controller(CombatRuntimeFixture fixture) {
        return new CrystalCombatController(() -> null, fixture::controllerStack);
    }

    @Test
    void publishesSentDebtAndSuspensionsThenClearsOnDisable() {
        CombatRuntimeFixture fixture = new CombatRuntimeFixture();
        CrystalCombatController controller = controller(fixture);
        assertTrue(controller.enable());
        assertEquals(RiskDebtSnapshot.empty(), controller.riskSnapshot(),
            "nothing is published before the first client tick samples risk");

        int attempts = fixture.sink.attempts;
        Intent place = fixture.place();
        fixture.reserve(place);
        assertEquals(CombatRuntime.Status.SENT,
            fixture.runtime.dispatch(place, fixture.ticket).status());
        assertTrue(fixture.sink.attempts > attempts,
            "the fixture dispatch is the known starting send point");
        controller.tick(1);

        RiskDebtSnapshot snapshot = controller.riskSnapshot();
        assertTrue(snapshot.visible(), "a sent action with outstanding risk must be displayed");
        assertTrue(snapshot.count() >= 1, "outstanding risk counts as debt");
        assertTrue(snapshot.bound() > 0.0, "outstanding risk carries a positive bound");
        assertTrue(snapshot.displayLines().get(0).startsWith("risk debt: "));
        assertEquals(1, snapshot.displayLines().size(), "no suspension yet");

        fixture.budget.suspend(SuspensionCause.SERVER_STALL);
        controller.tick(2);
        assertTrue(controller.riskSnapshot().causes().contains(SuspensionCause.SERVER_STALL));
        assertEquals(List.of("suspended: server stall"),
            controller.riskSnapshot().displayLines().subList(1, 2),
            "the source's suspension is published on its own line");
        assertEquals(2, controller.riskSnapshot().displayLines().size());

        controller.disable();
        assertFalse(controller.riskSnapshot().visible(), "a disabled module shows nothing");
        assertEquals(RiskDebtSnapshot.empty(), controller.riskSnapshot());
    }

    @Test
    void leavingTheWorldClearsThePublishedSnapshotAndJoiningDoesNotBringItBack() {
        CombatRuntimeFixture fixture = new CombatRuntimeFixture();
        CrystalCombatController controller = controller(fixture);
        assertTrue(controller.enable());
        Intent place = fixture.place();
        fixture.reserve(place);
        assertEquals(CombatRuntime.Status.SENT,
            fixture.runtime.dispatch(place, fixture.ticket).status());
        controller.tick(1);
        assertTrue(controller.riskSnapshot().visible(),
            "the sent risk is displayed before the connection is torn down");

        int attempts = fixture.sink.attempts;
        controller.onLeft();
        assertEquals(RiskDebtSnapshot.empty(), controller.riskSnapshot(),
            "leaving the world must clear the published snapshot");
        assertEquals(attempts, fixture.sink.attempts, "leaving the world never sends");

        controller.onJoined();
        assertEquals(RiskDebtSnapshot.empty(), controller.riskSnapshot(),
            "joining a new world must not resurrect the old snapshot");
        assertEquals(attempts, fixture.sink.attempts, "joining a new world never sends");

        controller.tick(2);
        assertEquals(RiskDebtSnapshot.empty(), controller.riskSnapshot(),
            "the first enabled tick in the new world publishes a fresh empty snapshot");
        assertEquals(attempts, fixture.sink.attempts,
            "the new world's first enabled tick never sends the retired intent");
    }

    @Test
    void aSuspensionWithoutSentDebtIsStillVisible() {
        CombatRuntimeFixture fixture = new CombatRuntimeFixture();
        CrystalCombatController controller = controller(fixture);
        assertTrue(controller.enable());
        fixture.budget.suspend(SuspensionCause.SETBACK);
        controller.tick(1);

        RiskDebtSnapshot snapshot = controller.riskSnapshot();
        assertEquals(0, snapshot.count());
        assertEquals(0.0, snapshot.bound());
        assertTrue(snapshot.visible(), "the suspension is the reason players need to see");
        assertEquals(List.of("suspended: setback"), snapshot.displayLines());
        assertEquals(0, fixture.sink.attempts, "sampling never sends");
    }

    @Test
    void noStackPublishesNothing() {
        CrystalCombatController controller = new CrystalCombatController(() -> "refused", () -> {
            throw new AssertionError("disabled readiness must not build a stack");
        });
        assertFalse(controller.enable());
        controller.tick(1);
        assertEquals(RiskDebtSnapshot.empty(), controller.riskSnapshot());
    }
}
