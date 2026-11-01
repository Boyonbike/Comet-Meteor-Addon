/*
 * Original Comet implementation by Boyonabike.
 * Independent: test written against the project's own interfaces; no third-party code.
 * SPDX-License-Identifier: CC0-1.0
 */

package dev.tntcoating.core.combat;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Locale;

import org.junit.jupiter.api.Test;

/** Phase 6 metrics: every decision reason and every hybrid event has its own bucket, none is merged. */
final class HybridMetricsTest {
    @Test
    void hybridCountersStaySeparateFromThePhaseFourTable() {
        assertEquals(15, CombatMetrics.COUNTERS.size(), "the Phase 4 counter table is unchanged");
        assertEquals(10, CombatMetrics.HYBRID_COUNTERS.size());
        for (String name : CombatMetrics.HYBRID_COUNTERS) {
            assertEquals(name.toLowerCase(Locale.ROOT), name, "counter names are lower_snake_case");
            assertFalse(CombatMetrics.COUNTERS.contains(name),
                name + " must not be merged into the Phase 4 table");
        }
    }

    @Test
    void decisionReasonsAndHybridEventsAreCountedSeparately() {
        CombatMetrics metrics = new CombatMetrics();
        metrics.recordChoiceMode("HYBRID");
        metrics.recordChoiceMode("HYBRID");
        metrics.recordChoiceWinner("lethal");
        metrics.recordChoiceWinner("none");
        metrics.recordChoiceRejection("COMMITTED");
        metrics.recordChoiceRejection("LEDGER");
        metrics.recordChoiceRejection("COMMITTED");
        metrics.recordHysteresisHold();
        metrics.recordHysteresisSwitch("score");
        metrics.recordHysteresisSwitch("invalidated");
        metrics.recordTrapBlockedDirect();
        metrics.recordDefenceDuringTrap();
        metrics.recordCleanupWait(7);
        metrics.recordCleanupStarved();

        assertEquals(2, metrics.choiceModes("HYBRID"));
        assertEquals(1, metrics.choiceWinner("lethal"));
        assertEquals(1, metrics.choiceWinner("none"));
        assertEquals(2, metrics.choiceRejection("COMMITTED"));
        assertEquals(1, metrics.choiceRejection("LEDGER"));
        assertEquals(1, metrics.hysteresisHolds());
        assertEquals(1, metrics.hysteresisSwitch("score"));
        assertEquals(1, metrics.hysteresisSwitch("invalidated"));
        assertEquals(1, metrics.trapBlockedDirect());
        assertEquals(1, metrics.defenceDuringTrap());
        assertEquals(1, metrics.cleanupWaitTicks());
        assertEquals(7.0, metrics.cleanupMaxWaitTicks(), 1.0e-9);
        assertEquals(1, metrics.cleanupStarvedTicks());
    }

    @Test
    void resetClearsEveryHybridCounter() {
        CombatMetrics metrics = new CombatMetrics();
        metrics.recordChoiceMode("HYBRID");
        metrics.recordChoiceWinner("utility");
        metrics.recordChoiceRejection("RISK");
        metrics.recordHysteresisHold();
        metrics.recordHysteresisSwitch("score");
        metrics.recordTrapBlockedDirect();
        metrics.recordDefenceDuringTrap();
        metrics.recordCleanupWait(21);
        metrics.recordCleanupStarved();

        metrics.reset();

        assertEquals(0, metrics.choiceModes("HYBRID"));
        assertEquals(0, metrics.choiceWinner("utility"));
        assertEquals(0, metrics.choiceRejection("RISK"));
        assertEquals(0, metrics.hysteresisHolds());
        assertEquals(0, metrics.hysteresisSwitch("score"));
        assertEquals(0, metrics.trapBlockedDirect());
        assertEquals(0, metrics.defenceDuringTrap());
        assertEquals(0, metrics.cleanupWaitTicks());
        assertEquals(0.0, metrics.cleanupMaxWaitTicks(), 1.0e-9);
        assertEquals(0, metrics.cleanupStarvedTicks());
    }

    /** Blank keys are dropped, never bucketed under an empty name. */
    @Test
    void blankReasonsAreIgnored() {
        CombatMetrics metrics = new CombatMetrics();
        metrics.recordChoiceMode(" ");
        metrics.recordChoiceWinner(null);
        metrics.recordChoiceRejection("");
        metrics.recordHysteresisSwitch(null);

        assertEquals(0, metrics.choiceModes(" "));
        assertEquals(0, metrics.choiceWinner(""));
        assertEquals(0, metrics.choiceRejection(""));
        assertEquals(0, metrics.hysteresisSwitch(""));
    }
}
