/*
 * Original Comet implementation by Boyonabike.
 * Independent: tests written against the project's own interfaces; no third-party code.
 * SPDX-License-Identifier: CC0-1.0
 */

package dev.tntcoating.core.combat.mc;

import static org.junit.jupiter.api.Assertions.assertEquals;

import dev.tntcoating.core.combat.CombatDamagePolicy;
import dev.tntcoating.core.combat.DirectSettings;
import org.junit.jupiter.api.Test;

/** Phase 4.2: the debug-log header states whether the experimental motion tactic is on and its horizon. */
class CombatDebugHostHeaderTest {
    private static DirectSettings settings(boolean motion, int ticks) {
        DirectSettings base = DirectSettings.conservative(CombatDamagePolicy.conservativeSafety());
        return new DirectSettings(base.targetRange(), base.placeRange(), base.attackRange(),
            base.wallsRange(), base.safety(), base.ownOnly(), base.minAgeTicks(), base.maxAttempts(),
            base.faceplaceEnabled(), base.faceplaceHealth(), base.faceplaceArmourFraction(),
            base.twoAirProfile(), base.supportEnabled(), base.switchDelayTicks(), base.rotationsOn(),
            base.pauseOnEating(), base.pauseOnDrinking(), base.pauseOnMining(), base.pauseOnScreen(),
            base.pauseOnBaritone(), base.healthFloor(), base.pauseOnLag(), base.minTps(),
            base.staleFeedbackNanos(), motion, ticks);
    }

    @Test
    void headerReportsOffAndTheConservativeHorizon() {
        assertEquals(" motion-prediction=off motion-prediction-ticks=2",
            CombatDebugHost.motionPredictionHeader(settings(false, 2)));
    }

    @Test
    void headerReportsOnAndTheConfiguredHorizon() {
        assertEquals(" motion-prediction=on motion-prediction-ticks=3",
            CombatDebugHost.motionPredictionHeader(settings(true, 3)));
    }

    @Test
    void missingSettingsProduceNoHeaderFragment() {
        assertEquals("", CombatDebugHost.motionPredictionHeader(null));
    }

    @Test
    void supportHeaderReportsOffByDefaultAndOnWhenEnabled() {
        DirectSettings base = DirectSettings.conservative(CombatDamagePolicy.conservativeSafety());
        assertEquals(" support=off", CombatDebugHost.supportHeader(base));
        DirectSettings on = new DirectSettings(base.targetRange(), base.placeRange(), base.attackRange(),
            base.wallsRange(), base.safety(), base.ownOnly(), base.minAgeTicks(), base.maxAttempts(),
            base.faceplaceEnabled(), base.faceplaceHealth(), base.faceplaceArmourFraction(),
            base.twoAirProfile(), true, base.switchDelayTicks(), base.rotationsOn(),
            base.pauseOnEating(), base.pauseOnDrinking(), base.pauseOnMining(), base.pauseOnScreen(),
            base.pauseOnBaritone(), base.healthFloor(), base.pauseOnLag(), base.minTps(),
            base.staleFeedbackNanos(), base.motionPrediction(), base.motionPredictionTicks());
        assertEquals(" support=on", CombatDebugHost.supportHeader(on));
        assertEquals("", CombatDebugHost.supportHeader(null));
    }

    @Test
    void defenceHeadersReportOffByDefaultAndOnWhenEnabled() {
        DirectSettings base = DirectSettings.conservative(CombatDamagePolicy.conservativeSafety());
        assertEquals(" dangerous-crystal-break=off", CombatDebugHost.dangerousCrystalHeader(base));
        assertEquals(" dangerous-crystal-break=on",
            CombatDebugHost.dangerousCrystalHeader(base.withDangerousCrystalBreak(true)));
        assertEquals(" anti-piston=off", CombatDebugHost.antiPistonHeader(base));
        assertEquals(" anti-piston=on", CombatDebugHost.antiPistonHeader(base.withAntiPiston(true)));
        assertEquals("", CombatDebugHost.dangerousCrystalHeader(null));
        assertEquals("", CombatDebugHost.antiPistonHeader(null));
    }

    @Test
    void surroundGapHeaderReportsOffByDefaultAndOnWhenEnabled() {
        DirectSettings base = DirectSettings.conservative(CombatDamagePolicy.conservativeSafety());
        assertEquals(" surround-gap=off", CombatDebugHost.surroundGapHeader(base));
        assertEquals(" surround-gap=on", CombatDebugHost.surroundGapHeader(base.withSurroundGap(true)));
        assertEquals("", CombatDebugHost.surroundGapHeader(null));
    }
}
