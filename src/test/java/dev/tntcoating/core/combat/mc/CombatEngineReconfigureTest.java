/*
 * Original Comet implementation by Boyonabike.
 * Independent: tests written against the project's own interfaces; no third-party code.
 * SPDX-License-Identifier: CC0-1.0
 */

package dev.tntcoating.core.combat.mc;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;

import dev.tntcoating.core.combat.DirectSettings;
import dev.tntcoating.core.combat.Vec;
import org.junit.jupiter.api.Test;

/** Reconfiguration swaps the settings and candidate cache without sending anything. */
final class CombatEngineReconfigureTest {
    private static final Vec SELF = new Vec(0.5, -60.0, 0.5);

    @Test
    void reconfigureSwapsSettingsAndCacheWithoutSending() {
        EngineHarness harness = EngineHarness.create(SELF);
        Object cacheBefore = harness.engine.cache();
        DirectSettings next = harness.settings.withMotion(true, 2);

        harness.engine.reconfigure(next);

        assertSame(next, harness.engine.settings());
        assertNotSame(cacheBefore, harness.engine.cache());
        assertEquals(0, harness.sink.attempts);
    }

    @Test
    void reconfigureBeforeTheFirstTickKeepsTheEngineIdle() {
        EngineHarness harness = EngineHarness.create(SELF);
        harness.setBase(EngineHarness.cellOf(new Vec(2.0, -61.0, 0.0)));
        harness.addTarget(new Vec(2.5, -60.0, 3.0));
        harness.engine.reconfigure(harness.settings.withHurtSync(true));
        harness.tick();
        assertEquals(1, harness.placeCount());
        assertEquals(0, harness.attackCount());
    }

    @Test
    void switchingBackRestoresTheOriginalConfiguration() {
        EngineHarness harness = EngineHarness.create(SELF);
        DirectSettings original = harness.engine.settings();
        harness.engine.reconfigure(original.withSurroundGap(true));
        harness.engine.reconfigure(original);
        assertSame(original, harness.engine.settings());
        assertEquals(0, harness.sink.attempts);
    }
}
