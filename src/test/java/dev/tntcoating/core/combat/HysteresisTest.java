/*
 * Original Comet test written against the project's own interfaces; no third-party code.
 * SPDX-License-Identifier: CC0-1.0
 */

package dev.tntcoating.core.combat;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * Phase 6.2: a better score alone never switches a plan. It needs a 20% advantage for three consecutive
 * server ticks, safety invalidation overrides immediately, and live or irreversible work is never
 * abandoned for a score.
 */
final class HysteresisTest {
    private static final Opportunity A = ChoiceFixtures.opportunity(1, Strategy.DIRECT, 1, 10.0, 1.0);
    private static final Opportunity B25 = ChoiceFixtures.opportunity(2, Strategy.PISTON, 1, 12.5, 1.0);
    private static final Opportunity B10 = ChoiceFixtures.opportunity(3, Strategy.PISTON, 1, 11.0, 1.0);
    private static final Opportunity A160 = ChoiceFixtures.opportunity(4, Strategy.DIRECT, 1, 16.0, 1.0);
    /** Same 25% margin, same target, different strategy/signature: its own challenge counter. */
    private static final Opportunity C25 = ChoiceFixtures.opportunity(5, Strategy.DEFEND, 1, 12.5, 1.0);

    @Test
    void firstPlanIsAdoptedImmediately() {
        Hysteresis hysteresis = new Hysteresis();

        Hysteresis.Decision decision = hysteresis.advance(1, ChoiceFixtures.ranked(A), false);

        assertTrue(decision.adopted());
        assertFalse(decision.held());
        assertEquals(A, decision.plan().orElseThrow());
        assertEquals(A, hysteresis.current().orElseThrow());
    }

    @Test
    void aTenPercentAdvantageNeverSwitches() {
        Hysteresis hysteresis = new Hysteresis();
        hysteresis.advance(1, ChoiceFixtures.ranked(A), false);

        for (int tick = 2; tick < 10; tick++) {
            Hysteresis.Decision decision = hysteresis.advance(tick,
                ChoiceFixtures.ranked(B10, A), false);
            assertFalse(decision.adopted(), "10% is under the 20% margin at tick " + tick);
            assertTrue(decision.held());
            assertEquals(A, decision.plan().orElseThrow());
        }
    }

    @Test
    void aTwentyFivePercentAdvantageSwitchesOnTheThirdConsecutiveTick() {
        Hysteresis hysteresis = new Hysteresis();
        hysteresis.advance(1, ChoiceFixtures.ranked(A), false);

        assertEquals(1, hysteresis.advance(2, ChoiceFixtures.ranked(B25, A), false).challengeTicks());
        assertEquals(2, hysteresis.advance(3, ChoiceFixtures.ranked(B25, A), false).challengeTicks());
        Hysteresis.Decision third = hysteresis.advance(4, ChoiceFixtures.ranked(B25, A), false);

        assertTrue(third.adopted());
        assertEquals(B25, hysteresis.current().orElseThrow());
    }

    @Test
    void oscillatingScoresNeverThrashBecauseTheAdvantageMustBeConsecutive() {
        Hysteresis hysteresis = new Hysteresis();
        hysteresis.advance(1, ChoiceFixtures.ranked(A), false);
        int tick = 1;

        for (int round = 0; round < 20; round++) {
            Hysteresis.Decision challenger = hysteresis.advance(++tick,
                ChoiceFixtures.ranked(B25, A), false);
            assertFalse(challenger.adopted(), "one challenge tick must not switch");
            Hysteresis.Decision incumbent = hysteresis.advance(++tick, ChoiceFixtures.ranked(A), false);
            assertEquals(0, incumbent.challengeTicks(), "the incumbent resets the challenge count");
            assertEquals(A, hysteresis.current().orElseThrow());
        }
    }

    @Test
    void aDifferentChallengerRestartsTheCountAndATargetChangeResetsTheState() {
        Hysteresis hysteresis = new Hysteresis();
        hysteresis.advance(1, ChoiceFixtures.ranked(A), false);

        hysteresis.advance(2, ChoiceFixtures.ranked(B25, A), false);
        hysteresis.advance(3, ChoiceFixtures.ranked(B25, A), false);
        // A different plan with the same margin starts its own counter instead of inheriting B25's.
        assertEquals(1, hysteresis.advance(4, ChoiceFixtures.ranked(C25, A), false).challengeTicks());
        assertEquals(2, hysteresis.advance(5, ChoiceFixtures.ranked(C25, A), false).challengeTicks());
        assertEquals(1, hysteresis.advance(6, ChoiceFixtures.ranked(B25, A), false).challengeTicks());
        assertEquals(2, hysteresis.advance(7, ChoiceFixtures.ranked(B25, A), false).challengeTicks());
        assertTrue(hysteresis.advance(8, ChoiceFixtures.ranked(B25, A), false).adopted(),
            "three consecutive ticks of the same challenger switch, the earlier C25 ticks do not count");
        assertEquals(B25, hysteresis.current().orElseThrow());
    }

    @Test
    void irreversibleWorkIsNeverAbandonedForAScore() {
        Hysteresis hysteresis = new Hysteresis();
        hysteresis.advance(1, ChoiceFixtures.ranked(A), false);
        hysteresis.irreversibleActionSent();

        for (int tick = 2; tick < 12; tick++) {
            Hysteresis.Decision decision = hysteresis.advance(tick,
                ChoiceFixtures.ranked(B25, A), false);
            assertFalse(decision.adopted(), "a live crystal action must not be scored away at tick " + tick);
            assertEquals(A, hysteresis.current().orElseThrow());
        }
        assertTrue(hysteresis.irreversible());

        hysteresis.actionFinished();
        assertFalse(hysteresis.irreversible());
        hysteresis.advance(12, ChoiceFixtures.ranked(B25, A), false);
        hysteresis.advance(13, ChoiceFixtures.ranked(B25, A), false);
        assertTrue(hysteresis.advance(14, ChoiceFixtures.ranked(B25, A), false).adopted(),
            "after the action finishes safely the plan may be switched again");
    }

    @Test
    void liveStructuresAreKeptWhileThePlanIsLive() {
        Hysteresis hysteresis = new Hysteresis();
        hysteresis.advance(1, ChoiceFixtures.ranked(A), false);
        hysteresis.markLive();

        for (int tick = 2; tick < 20; tick++) {
            Hysteresis.Decision decision = hysteresis.advance(tick,
                ChoiceFixtures.ranked(B25), false);
            assertFalse(decision.adopted(), "live structures are never abandoned for a score");
            assertEquals(A, hysteresis.current().orElseThrow());
        }
    }

    @Test
    void aProposalThatDisappearsIsToleratedForThreeTicksBeforeReplacing() {
        Hysteresis hysteresis = new Hysteresis();
        hysteresis.advance(1, ChoiceFixtures.ranked(A), false);

        Hysteresis.Decision first = hysteresis.advance(2, ChoiceFixtures.ranked(B25), false);
        assertFalse(first.adopted(), "one missing proposal must not flip a decision");
        assertTrue(first.held());
        assertEquals(1, first.challengeTicks());

        hysteresis.advance(3, ChoiceFixtures.ranked(B25), false);
        Hysteresis.Decision third = hysteresis.advance(4, ChoiceFixtures.ranked(B25), false);
        assertTrue(third.adopted(), "after three ticks the best admissible plan is adopted");
        assertEquals(B25, hysteresis.current().orElseThrow());
    }

    @Test
    void safetyInvalidationOverridesHysteresisInTheSameTick() {
        Hysteresis hysteresis = new Hysteresis();
        hysteresis.advance(1, ChoiceFixtures.ranked(A), false);
        hysteresis.irreversibleActionSent();

        Hysteresis.Decision decision = hysteresis.advance(2, ChoiceFixtures.ranked(B10, A), true);

        assertTrue(decision.adopted());
        assertTrue(decision.invalidated());
        assertFalse(decision.held());
        assertEquals(B10, hysteresis.current().orElseThrow());
        assertFalse(hysteresis.irreversible(), "invalidation clears the live plan state");
    }

    @Test
    void invalidationWithNoSafeAlternativeLeavesNoPlan() {
        Hysteresis hysteresis = new Hysteresis();
        hysteresis.advance(1, ChoiceFixtures.ranked(A), false);

        Hysteresis.Decision decision = hysteresis.advance(2, List.of(), true);

        assertTrue(decision.plan().isEmpty());
        assertTrue(decision.invalidated());
        assertTrue(hysteresis.current().isEmpty());
    }

    @Test
    void repeatedAndBackwardsTicksAreHandledWithoutInventingConsecutiveTicks() {
        Hysteresis hysteresis = new Hysteresis();
        hysteresis.advance(5, ChoiceFixtures.ranked(A), false);

        Hysteresis.Decision repeated = hysteresis.advance(5, ChoiceFixtures.ranked(B25, A), false);
        assertEquals(A, repeated.plan().orElseThrow(), "a repeated tick must not add a challenge tick");

        // A backwards tick means a new generation: the state resets and the next plan is adopted at once.
        Hysteresis.Decision backwards = hysteresis.advance(4, ChoiceFixtures.ranked(B25), false);
        assertTrue(backwards.adopted());
        assertSame(B25, hysteresis.current().orElseThrow());
    }

    @Test
    void resetEndsThePerPlanState() {
        Hysteresis hysteresis = new Hysteresis();
        hysteresis.advance(1, ChoiceFixtures.ranked(A), false);
        hysteresis.irreversibleActionSent();

        hysteresis.reset();

        assertTrue(hysteresis.current().isEmpty());
        assertFalse(hysteresis.irreversible());
        assertFalse(hysteresis.live());
        Hysteresis.Decision decision = hysteresis.advance(2, ChoiceFixtures.ranked(B25), false);
        assertTrue(decision.adopted());
    }

    @Test
    void utilityComparisonUsesTheSameRankingAsTheChoice() {
        Hysteresis hysteresis = new Hysteresis();
        hysteresis.advance(1, ChoiceFixtures.ranked(A), false);

        // A160 is 60% better than A: three consecutive ticks switch.
        hysteresis.advance(2, ChoiceFixtures.ranked(A160, A), false);
        hysteresis.advance(3, ChoiceFixtures.ranked(A160, A), false);
        assertTrue(hysteresis.advance(4, ChoiceFixtures.ranked(A160, A), false).adopted());
        assertEquals(A160, hysteresis.current().orElseThrow());
        assertTrue(hysteresis.describe().contains("DIRECT#4"), hysteresis.describe());
    }
}
