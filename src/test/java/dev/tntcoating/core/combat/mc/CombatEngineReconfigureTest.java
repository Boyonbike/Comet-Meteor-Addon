/*
 * Original Comet implementation by Boyonabike.
 * Independent: written from the pinned 26.2 artifact and Meteor build 35 behaviour; no third-party code.
 * SPDX-License-Identifier: CC0-1.0
 */

package dev.tntcoating.core.combat.mc;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.tntcoating.core.combat.CandidateCache;
import dev.tntcoating.core.combat.DirectSettings;
import dev.tntcoating.core.combat.Vec;
import org.junit.jupiter.api.Test;

/** A live settings change must swap the planner/cache but never the runtime stack or its hazard state. */
class CombatEngineReconfigureTest {
    @Test
    void reconfigureSwapsSettingsPlannerAndCache() {
        EngineHarness harness = EngineHarness.create(new Vec(0, 64, 0));
        CandidateCache oldCache = harness.engine.cache();
        DirectSettings next = harness.settings.withMotion(true, 2);

        harness.engine.reconfigure(next);

        assertSame(next, harness.engine.settings());
        assertTrue(harness.engine.settings().motionPrediction());
        assertNotSame(oldCache, harness.engine.cache());
        assertTrue(harness.sink.attempts == 0, "a settings change alone must not send");
    }

    @Test
    void reconfigureBackOffRestoresPhaseThreeBehaviour() {
        EngineHarness harness = EngineHarness.create(new Vec(0, 64, 0));
        harness.engine.reconfigure(harness.settings.withMotion(true, 3));
        harness.engine.reconfigure(harness.settings.withMotion(false, 2));

        assertFalse(harness.engine.settings().motionPrediction());
        assertTrue(harness.sink.attempts == 0, "a settings change alone must not send");
    }
}
