/*
 * Original Comet implementation by Boyonabike.
 * Independent: tests written against the project's own interfaces; no third-party code.
 * SPDX-License-Identifier: CC0-1.0
 */

package dev.tntcoating.core.combat.mc;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.tntcoating.core.combat.Action;
import dev.tntcoating.core.combat.Observation;
import dev.tntcoating.core.combat.ObservationKind;
import dev.tntcoating.core.combat.ObservationPayload;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

/** Runtime tick, generation and observer ownership. */
final class CombatRuntimeLifecycleTest {
    @Test
    void tickIsIdempotentPerServerTickAndRejectsBackwardsTime() {
        CombatRuntimeFixture fixture = new CombatRuntimeFixture();
        fixture.runtime.tick(5);
        fixture.runtime.tick(5);
        assertEquals(2, fixture.runtime.tickCount());
        assertThrows(IllegalArgumentException.class, () -> fixture.runtime.tick(4),
            "server tick moved backwards");
        fixture.runtime.tick(6);
        assertEquals(3, fixture.runtime.tickCount());
        fixture.runtime.tick(-1);
        assertEquals(3, fixture.runtime.tickCount());
    }

    @Test
    void tickDrainsInReceiptOrderAndRunsTheAfterApplyHook() {
        CombatRuntimeFixture fixture = new CombatRuntimeFixture();
        fixture.intake.enqueueForTest(observation(fixture, ObservationKind.SETBACK));
        fixture.intake.enqueueForTest(observation(fixture, ObservationKind.DEATH));
        List<ObservationKind> seen = new ArrayList<>();
        List<String> after = new ArrayList<>();
        fixture.serverTick++;
        fixture.runtime.tick(fixture.serverTick, o -> seen.add(o.kind()), o -> after.add("after"));

        assertEquals(List.of(ObservationKind.SETBACK, ObservationKind.DEATH), seen);
        assertEquals(List.of("after", "after"), after);
        assertEquals(0, fixture.intake.queued());
    }

    @Test
    void deathAndRespawnSuspendAndRestoreTheRiskBudget() {
        CombatRuntimeFixture fixture = new CombatRuntimeFixture();
        var generation = fixture.session.actionGeneration();
        fixture.runtime.onDeath();
        assertTrue(fixture.risk.suspended());
        assertFalse(fixture.session.actionGeneration().equals(generation));

        CombatRuntimeFixture respawn = new CombatRuntimeFixture();
        respawn.runtime.onRespawn();
        assertTrue(respawn.session.isOpen());
        respawn.intake.enqueueForTest(new Observation(respawn.session.connection(),
            respawn.intake.appliedRevision(), respawn.intake.appliedRevision(), 0L, 0,
            ObservationKind.RESPAWN, respawn.client.self, null, null, null,
            new ObservationPayload.Respawn("minecraft:overworld", false)));
        respawn.serverTick++;
        respawn.runtime.tick(respawn.serverTick);
        assertTrue(respawn.session.isOpen());
    }

    @Test
    void dimensionChangeStartsANewConnectionKeyAndSuspends() {
        CombatRuntimeFixture fixture = new CombatRuntimeFixture();
        var before = fixture.session.connection();
        fixture.intake.enqueueForTest(new Observation(before,
            fixture.intake.appliedRevision(), fixture.intake.appliedRevision(), 0L, 0,
            ObservationKind.RESPAWN, fixture.client.self, null, null, null,
            new ObservationPayload.Respawn("minecraft:the_nether", true)));
        fixture.serverTick++;
        fixture.runtime.tick(fixture.serverTick);
        assertFalse(before.equals(fixture.session.connection()));
        assertTrue(fixture.risk.suspended());
    }

    @Test
    void dispatchObserverSeesEveryResultAndExplosionObserverSeesPositions() {
        CombatRuntimeFixture fixture = new CombatRuntimeFixture();
        List<CombatRuntime.DispatchResult> results = new ArrayList<>();
        List<dev.tntcoating.core.combat.Vec> explosions = new ArrayList<>();
        fixture.runtime.setDispatchObserver(results::add);
        fixture.runtime.setExplosionObserver(explosions::add);

        fixture.dispatchPlace();
        assertEquals(1, results.size());
        assertEquals(CombatRuntime.Status.SENT, results.get(0).status());

        fixture.intake.enqueueForTest(new Observation(fixture.session.connection(),
            fixture.intake.appliedRevision(), fixture.intake.appliedRevision(), 0L, 0,
            ObservationKind.EXPLOSION, fixture.crystal(), null,
            new dev.tntcoating.core.combat.Vec(1.5, 64.5, 0.5), null,
            new ObservationPayload.Explosion(6.0)));
        fixture.serverTick++;
        fixture.runtime.tick(fixture.serverTick);
        assertEquals(1, explosions.size());
        assertEquals(1.5, explosions.get(0).x(), 1.0e-9);
    }

    @Test
    void closeIsIdempotentAndStopsSends() {
        CombatRuntimeFixture fixture = new CombatRuntimeFixture();
        fixture.runtime.close();
        fixture.runtime.close();
        assertTrue(fixture.runtime.closed());
        int attempts = fixture.sink.attempts;
        fixture.dispatchPlace();
        assertEquals(attempts, fixture.sink.attempts);
        assertEquals(1, fixture.runtime.drain().size() + 1);
    }

    @Test
    void snapshotCarriesTheSessionConnectionRevisionAndCrystals() {
        CombatRuntimeFixture fixture = new CombatRuntimeFixture();
        fixture.crystal();
        var snapshot = fixture.runtime.capture();
        assertEquals(fixture.session.connection(), snapshot.conn());
        assertEquals(fixture.intake.appliedRevision(), snapshot.appliedRevision());
        assertEquals(1, snapshot.crystals().size());
    }

    private static Observation observation(CombatRuntimeFixture fixture, ObservationKind kind) {
        return new Observation(fixture.session.connection(), fixture.intake.appliedRevision(),
            fixture.intake.appliedRevision(), 0L, 0, kind, fixture.client.self, null, null, null,
            switch (kind) {
                case SETBACK -> new ObservationPayload.Setback();
                case DEATH -> new ObservationPayload.Death();
                default -> new ObservationPayload.None();
            });
    }
}
