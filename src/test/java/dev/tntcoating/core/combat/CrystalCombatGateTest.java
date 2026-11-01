package dev.tntcoating.core.combat;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The gate is the only state Meteor's CrystalAura consults. These tests cover the two integration scenarios
 * from Phase 2c without a client: CrystalCombat enabled means no Meteor sends, and disabling CrystalCombat
 * while rotation callbacks are queued means those callbacks never send.
 */
class CrystalCombatGateTest {
    @AfterEach
    void reset() {
        CrystalCombatGate.deactivate();
        CrystalCombatGate.setProtector(null);
    }

    @Test
    void theGateStartsInactiveAndLeavesMeteorAlone() {
        assertFalse(CrystalCombatGate.suppressMeteor());

        AtomicBoolean ran = new AtomicBoolean();
        Runnable guarded = CrystalCombatGate.guard(() -> ran.set(true));

        assertNotNull(guarded);
        guarded.run();
        assertTrue(ran.get(), "a callback queued while CrystalCombat is disabled must still run");
    }

    @Test
    void activationSuppressesMeteorAndDeactivationRestoresIt() {
        CrystalCombatGate.activate();
        assertTrue(CrystalCombatGate.suppressMeteor());

        CrystalCombatGate.activate();
        assertTrue(CrystalCombatGate.suppressMeteor(), "activation is idempotent");

        CrystalCombatGate.deactivate();
        assertFalse(CrystalCombatGate.suppressMeteor());

        CrystalCombatGate.deactivate();
        assertFalse(CrystalCombatGate.suppressMeteor(), "deactivation is idempotent");
    }

    @Test
    void aCallbackQueuedWhileActiveNeverRuns() {
        CrystalCombatGate.activate();
        AtomicInteger sends = new AtomicInteger();
        Runnable queued = CrystalCombatGate.guard(sends::incrementAndGet);

        queued.run();
        CrystalCombatGate.deactivate();
        queued.run();
        CrystalCombatGate.activate();
        queued.run();

        assertEquals(0, sends.get());
    }

    @Test
    void aCallbackQueuedWhileInactiveRunsUntilTheGateActivates() {
        AtomicInteger sends = new AtomicInteger();
        Runnable queued = CrystalCombatGate.guard(sends::incrementAndGet);

        queued.run();
        assertEquals(1, sends.get());

        CrystalCombatGate.activate();
        queued.run();
        assertEquals(1, sends.get(), "activation must invalidate a callback queued before it");

        CrystalCombatGate.deactivate();
        queued.run();
        assertEquals(1, sends.get(), "a deactivate-reactivate pair is still a generation change");
    }

    @Test
    void aNoOpDeactivateDoesNotInvalidateCallbacksQueuedWhileInactive() {
        CrystalCombatGate.deactivate();
        AtomicInteger sends = new AtomicInteger();
        Runnable queued = CrystalCombatGate.guard(sends::incrementAndGet);

        CrystalCombatGate.deactivate();
        queued.run();

        assertEquals(1, sends.get(), "deactivating an inactive gate must not change its generation");
    }

    @Test
    void nullCallbacksAndUnboundProtectorsAreSafe() {
        UUID crystal = UUID.randomUUID();

        assertNull(CrystalCombatGate.guard(null));
        assertFalse(CrystalCombatGate.protects(null));
        assertFalse(CrystalCombatGate.protects(crystal));

        CrystalCombatGate.setProtector(null);
        assertFalse(CrystalCombatGate.protects(crystal));
    }

    @Test
    void theProtectorIsIndependentOfActivation() {
        UUID ledgerProtects = UUID.randomUUID();
        UUID other = UUID.randomUUID();
        CrystalCombatGate.setProtector(crystal -> crystal.equals(ledgerProtects));

        assertTrue(CrystalCombatGate.protects(ledgerProtects));
        assertFalse(CrystalCombatGate.protects(other));

        CrystalCombatGate.activate();
        assertTrue(CrystalCombatGate.suppressMeteor());
        assertTrue(CrystalCombatGate.protects(ledgerProtects), "activation must not hide ledger protection");
        assertFalse(CrystalCombatGate.protects(other));

        CrystalCombatGate.deactivate();
        assertFalse(CrystalCombatGate.suppressMeteor());
        assertTrue(CrystalCombatGate.protects(ledgerProtects),
            "a ledger-protected crystal stays protected while CrystalCombat is disabled");
        assertFalse(CrystalCombatGate.protects(other));
    }

    @Test
    void theProtectorCanBeReplacedAndCleared() {
        UUID first = UUID.randomUUID();
        UUID second = UUID.randomUUID();
        CrystalCombatGate.setProtector(crystal -> crystal.equals(first));
        assertTrue(CrystalCombatGate.protects(first));
        assertFalse(CrystalCombatGate.protects(second));

        CrystalCombatGate.setProtector(crystal -> crystal.equals(second));
        assertFalse(CrystalCombatGate.protects(first));
        assertTrue(CrystalCombatGate.protects(second));

        CrystalCombatGate.setProtector(null);
        assertFalse(CrystalCombatGate.protects(second));
    }

    @Test
    void bothModulesEnabledSendsNothingOverTwoHundredTicks() {
        CrystalCombatGate.activate();
        AtomicInteger sends = new AtomicInteger();

        for (int tick = 0; tick < 200; tick++) {
            // onPreTick/onPreTickLast/onEntityAdded/doBreak/doPlace check the gate before sending.
            if (!CrystalCombatGate.suppressMeteor()) sends.incrementAndGet();
            // The rotation queue delays attack/place; the callback re-checks the generation on execution.
            Runnable queued = CrystalCombatGate.guard(sends::incrementAndGet);
            queued.run();
            // A second in-tick path (for example the delayed rotation callback running later this tick).
            if (!CrystalCombatGate.suppressMeteor()) sends.incrementAndGet();
        }

        assertEquals(0, sends.get(), "Meteor must not send on any of 200 ticks while CrystalCombat is enabled");
    }

    @Test
    void callbacksQueuedWhileActiveSendNothingAfterDisable() {
        CrystalCombatGate.activate();
        AtomicInteger sends = new AtomicInteger();
        List<Runnable> queued = new ArrayList<>();
        for (int i = 0; i < 200; i++) queued.add(CrystalCombatGate.guard(sends::incrementAndGet));

        CrystalCombatGate.deactivate();
        for (Runnable callback : queued) callback.run();

        assertEquals(0, sends.get(), "a queued attack/place must not run after CrystalCombat is disabled");
    }

    @Test
    void aCallbackExecutedOnAnotherThreadIsSuppressedAfterActivation() throws InterruptedException {
        AtomicInteger sends = new AtomicInteger();
        Runnable queued = CrystalCombatGate.guard(sends::incrementAndGet);
        CrystalCombatGate.activate();

        Thread thread = new Thread(queued, "crystal-gate-test");
        thread.start();
        thread.join(5_000);

        assertFalse(thread.isAlive());
        assertEquals(0, sends.get());
    }
}
