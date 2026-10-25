/*
 * Tests Comet's sethome cooldown parsing and estimate.
 * SPDX-License-Identifier: GPL-3.0-only
 */

package dev.tntcoating.core;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SetHomeCooldownTest {
    private static final long T0 = 1_000_000L;
    private static final long SECOND = 1_000L;
    private static final long MINUTE = 60_000L;

    @Test void waitsUntilAStatedCooldownEnds() {
        SetHomeCooldown cooldown = new SetHomeCooldown();
        cooldown.sent(T0);

        assertTrue(cooldown.message("You have to wait 9m 35s to set a home again.", T0 + 50));
        assertEquals(575 * SECOND, cooldown.remaining(T0 + 50));
        assertFalse(cooldown.ready(T0 + 50 + 575 * SECOND - 1));
        assertTrue(cooldown.ready(T0 + 50 + 575 * SECOND));
        assertEquals("", cooldown.status(T0 + 50 + 575 * SECOND));
    }

    @Test void mergesCompoundDurations() {
        SetHomeCooldown cooldown = new SetHomeCooldown();
        cooldown.sent(T0);

        assertTrue(cooldown.message("You must wait 1 minute and 5 seconds before setting another home.", T0 + 1));
        assertEquals(65 * SECOND, cooldown.remaining(T0 + 1));
    }

    @Test void readsCooldownFromARefusal() {
        SetHomeCooldown cooldown = new SetHomeCooldown();
        cooldown.sent(T0);

        assertTrue(cooldown.message("You can set another home in 4m.", T0 + 1));
        assertEquals(4 * MINUTE, cooldown.remaining(T0 + 1));
        assertEquals("4m", cooldown.status(T0 + 1));
    }

    @Test void readsCooldownFromAnAnnouncement() {
        SetHomeCooldown cooldown = new SetHomeCooldown();
        cooldown.sent(T0);

        assertTrue(cooldown.message("Home cooldown: 1h 2m", T0 + 1));
        assertEquals(62 * MINUTE, cooldown.remaining(T0 + 1));
        assertFalse(cooldown.ready(T0 + 1 + 62 * MINUTE - 1));
        assertTrue(cooldown.ready(T0 + 1 + 62 * MINUTE));
    }

    @Test void readsAGenericCommandDelayRightAfterSending() {
        SetHomeCooldown cooldown = new SetHomeCooldown();
        cooldown.sent(T0);

        assertTrue(cooldown.message("You must wait 5 minutes before using this command.", T0 + 1));
        assertEquals(5 * MINUTE, cooldown.remaining(T0 + 1));
    }

    @Test void ignoresHomeTeleportCooldowns() {
        SetHomeCooldown cooldown = new SetHomeCooldown();
        cooldown.sent(T0);

        assertFalse(cooldown.message("You have to wait 9m 35s to teleport again.", T0 + 10));
        assertTrue(cooldown.ready(T0 + 20));
        assertFalse(cooldown.message("You have to wait 9m 35s to use /home again.", T0 + 30));
        assertTrue(cooldown.ready(T0 + 40));
    }

    @Test void keepsAnExistingCooldownWhenATeleportLineArrives() {
        SetHomeCooldown cooldown = new SetHomeCooldown();
        cooldown.sent(T0);
        assertTrue(cooldown.message("You must wait 4 minutes to set a home again.", T0 + 1));

        assertFalse(cooldown.message("You have to wait 9m 35s to teleport again.", T0 + 2));
        assertEquals(4 * MINUTE - 1, cooldown.remaining(T0 + 2));
    }

    @Test void acceptsASuccessLineWithoutStartingACooldown() {
        SetHomeCooldown cooldown = new SetHomeCooldown();
        cooldown.sent(T0);

        assertFalse(cooldown.message("Home Base has been set.", T0 + SECOND));
        assertTrue(cooldown.ready(T0 + SECOND));
        assertEquals("", cooldown.status(T0 + SECOND));
    }

    @Test void learnsTheFullLengthFromARefusalAfterASuccess() {
        SetHomeCooldown cooldown = new SetHomeCooldown();
        cooldown.sent(T0 - SECOND);
        assertFalse(cooldown.message("Home Base has been set.", T0));

        long retry = T0 + MINUTE;
        cooldown.sent(retry);
        assertTrue(cooldown.message("You must wait 4 minutes to set a home again.", retry));
        assertEquals(4 * MINUTE, cooldown.remaining(retry));
        assertFalse(cooldown.ready(retry + 4 * MINUTE - 1));
        assertTrue(cooldown.ready(retry + 4 * MINUTE));
        assertEquals(5 * MINUTE, cooldown.learned());
    }

    @Test void estimatesTheNextCooldownFromTheLearnedLength() {
        SetHomeCooldown cooldown = learnedFiveMinutes();

        long retry = T0 + MINUTE;
        cooldown.sent(retry);
        assertTrue(cooldown.message("You must wait 4 minutes to set a home again.", retry));
        long nextSuccess = retry + 4 * MINUTE;
        cooldown.ready(nextSuccess);
        assertTrue(cooldown.ready(nextSuccess));

        cooldown.sent(nextSuccess - SECOND);
        assertFalse(cooldown.message("Home Base has been set.", nextSuccess));
        assertEquals(5 * MINUTE, cooldown.remaining(nextSuccess));
        assertTrue(cooldown.status(nextSuccess).startsWith("~"));
    }

    @Test void doesNotExtendTheEstimateForDuplicateSuccessLines() {
        SetHomeCooldown cooldown = learnedFiveMinutes();

        cooldown.sent(T0 - SECOND);
        assertFalse(cooldown.message("Home Base has been set.", T0));
        assertEquals(5 * MINUTE, cooldown.remaining(T0));
        assertFalse(cooldown.message("Home Base has been set.", T0 + SECOND));
        assertEquals(5 * MINUTE - SECOND, cooldown.remaining(T0 + SECOND));
    }

    @Test void ignoresFailedSuccessLines() {
        SetHomeCooldown cooldown = new SetHomeCooldown();
        cooldown.sent(T0);

        assertFalse(cooldown.message("Home could not be set.", T0 + SECOND));
        assertTrue(cooldown.ready(T0 + SECOND));
        assertFalse(cooldown.message("Error: home already exists.", T0 + 2 * SECOND));
        assertTrue(cooldown.ready(T0 + 2 * SECOND));
        assertFalse(cooldown.message("Home Base has been renamed.", T0 + 3 * SECOND));
        assertTrue(cooldown.ready(T0 + 3 * SECOND));
    }

    @Test void ignoresTheHomeLimitInsteadOfWaiting() {
        SetHomeCooldown cooldown = new SetHomeCooldown();
        cooldown.sent(T0);

        assertFalse(cooldown.message("You cannot set any more homes.", T0 + SECOND));
        assertTrue(cooldown.ready(T0 + SECOND));
    }

    @Test void clearsTheWaitWhenTheServerSaysTheCooldownIsOver() {
        SetHomeCooldown cooldown = new SetHomeCooldown();
        cooldown.sent(T0);
        assertTrue(cooldown.message("You must wait 5 minutes to set a home again.", T0 + SECOND));
        assertEquals(5 * MINUTE, cooldown.remaining(T0 + SECOND));

        assertFalse(cooldown.message("Your sethome cooldown has expired.", T0 + 2 * SECOND));
        assertTrue(cooldown.ready(T0 + 2 * SECOND));
        assertEquals("", cooldown.status(T0 + 2 * SECOND));
    }

    @Test void readsASuccessAndCooldownFromOneLine() {
        SetHomeCooldown cooldown = new SetHomeCooldown();
        cooldown.sent(T0);

        assertTrue(cooldown.message("Home set. Cooldown: 5 minutes.", T0 + SECOND));
        assertEquals(5 * MINUTE, cooldown.remaining(T0 + SECOND));
        assertFalse(cooldown.message("Home set. Cooldown: 5 minutes.", T0 + 2 * SECOND));
        assertEquals(5 * MINUTE - SECOND, cooldown.remaining(T0 + 2 * SECOND));
    }

    @Test void resetForgetsEverything() {
        SetHomeCooldown cooldown = learnedFiveMinutes();
        cooldown.confirmed(T0);
        assertFalse(cooldown.ready(T0 + MINUTE));

        cooldown.reset();
        assertTrue(cooldown.ready(T0 + MINUTE));
        assertEquals(0, cooldown.learned());
        assertEquals("", cooldown.status(T0 + MINUTE));
    }

    @Test void waitsTenSecondsAfterTheReportedCooldownEvenIfReadyConsumedIt() {
        SetHomeCooldown cooldown = new SetHomeCooldown();
        cooldown.message("You must wait 1 minute to set a home again.", T0);

        assertTrue(cooldown.ready(T0 + MINUTE));
        assertFalse(cooldown.readyAfter(T0 + MINUTE, 10 * SECOND));
        assertFalse(cooldown.readyAfter(T0 + MINUTE + 9_999, 10 * SECOND));
        assertEquals("Buffer 10s", cooldown.statusAfter(T0 + MINUTE, 10 * SECOND));
        assertTrue(cooldown.readyAfter(T0 + MINUTE + 10 * SECOND, 10 * SECOND));
        assertEquals("", cooldown.statusAfter(T0 + MINUTE + 10 * SECOND, 10 * SECOND));
    }

    @Test void buffersTheEstimatedCooldownAfterConfirmation() {
        SetHomeCooldown cooldown = learnedFiveMinutes();
        long nextSuccess = T0 + 10 * MINUTE;
        cooldown.confirmed(nextSuccess);

        assertFalse(cooldown.readyAfter(nextSuccess + 5 * MINUTE, 10 * SECOND));
        assertFalse(cooldown.readyAfter(nextSuccess + 5 * MINUTE + 9_999, 10 * SECOND));
        assertTrue(cooldown.readyAfter(nextSuccess + 5 * MINUTE + 10 * SECOND, 10 * SECOND));
    }

    @Test void startsTheBufferWhenAnExplicitFinishedMessageEndsTheCooldownEarly() {
        SetHomeCooldown cooldown = new SetHomeCooldown();
        cooldown.message("You must wait 5 minutes to set a home again.", T0);
        long finished = T0 + SECOND;
        cooldown.message("Your sethome cooldown has expired.", finished);

        assertTrue(cooldown.ready(finished));
        assertFalse(cooldown.readyAfter(finished, 10 * SECOND));
        assertFalse(cooldown.readyAfter(finished + 9_999, 10 * SECOND));
        assertTrue(cooldown.readyAfter(finished + 10 * SECOND, 10 * SECOND));
    }

    @Test void repeatedZeroAndFinishedMessagesDoNotRestartTheBuffer() {
        SetHomeCooldown cooldown = new SetHomeCooldown();
        cooldown.message("You must wait 5 minutes to set a home again.", T0);
        long finished = T0 + SECOND;
        cooldown.message("Your sethome cooldown: 0s remaining.", finished);
        assertTrue(cooldown.ready(finished));
        assertFalse(cooldown.readyAfter(finished, 10 * SECOND));
        cooldown.message("Your sethome cooldown has expired.", finished + 5 * SECOND);
        cooldown.message("Your sethome cooldown: 0s remaining.", finished + 9 * SECOND);

        assertFalse(cooldown.readyAfter(finished + 9_999, 10 * SECOND));
        assertTrue(cooldown.readyAfter(finished + 10 * SECOND, 10 * SECOND));
    }

    @Test void lateFinishedMessagesPreserveTheKnownCountdownEnd() {
        SetHomeCooldown cooldown = new SetHomeCooldown();
        cooldown.message("You must wait 1 second to set a home again.", T0);
        assertTrue(cooldown.ready(T0 + SECOND));
        cooldown.message("Your sethome cooldown has expired.", T0 + 5 * SECOND);
        assertTrue(cooldown.readyAfter(T0 + 11 * SECOND, 10 * SECOND));
    }

    @Test void anUnsolicitedFinishedMessageStartsOneBuffer() {
        SetHomeCooldown cooldown = new SetHomeCooldown();
        cooldown.message("Your sethome cooldown has expired.", T0);
        assertFalse(cooldown.readyAfter(T0, 10 * SECOND));
        cooldown.message("Your sethome cooldown has expired.", T0 + SECOND);
        assertTrue(cooldown.readyAfter(T0 + 10 * SECOND, 10 * SECOND));
    }

    @Test void aNewSessionAllowsTheFirstCommandImmediately() {
        SetHomeCooldown cooldown = new SetHomeCooldown();
        assertTrue(cooldown.readyAfter(T0, 10 * SECOND));
        cooldown.message("You must wait 5 minutes to set a home again.", T0);
        cooldown.reset();
        assertTrue(cooldown.readyAfter(T0 + SECOND, 10 * SECOND));
        assertEquals("", cooldown.statusAfter(T0 + SECOND, 10 * SECOND));
    }

    @Test void homeTeleportCooldownsDoNotStartABuffer() {
        SetHomeCooldown cooldown = new SetHomeCooldown();
        cooldown.sent(T0);
        cooldown.message("You must wait 5 minutes to use /home again.", T0);
        cooldown.message("Your home teleport cooldown has expired.", T0 + SECOND);
        cooldown.message("You must wait 0s to use /home again.", T0 + 2 * SECOND);
        assertTrue(cooldown.readyAfter(T0 + 2 * SECOND, 10 * SECOND));
    }

    private static SetHomeCooldown learnedFiveMinutes() {
        SetHomeCooldown cooldown = new SetHomeCooldown();
        cooldown.sent(T0 - SECOND);
        cooldown.message("Home Base has been set.", T0);
        long retry = T0 + MINUTE;
        cooldown.sent(retry);
        cooldown.message("You must wait 4 minutes to set a home again.", retry);
        assertTrue(cooldown.ready(retry + 4 * MINUTE));
        return cooldown;
    }
    @Test void literalZeroSecToGoStillWaitsTenMoreSeconds() {
        var cooldown = new SetHomeCooldown();
        cooldown.message("Your sethome cooldown: 0sec to go", T0);
        assertFalse(cooldown.readyAfter(T0 + 9_999, 10_000));
        assertTrue(cooldown.readyAfter(T0 + 10_000, 10_000));
    }

    @Test void aZeroSecondRefusalWaitsTheConfiguredRetryDelay() {
        SetHomeCooldown cooldown = new SetHomeCooldown();
        cooldown.sent(T0);

        assertFalse(cooldown.message(
            "You have to wait 0m 0sec to set a home again. Lower your cooldown - https://6b6t.org/shop",
            T0 + 100, 45 * SECOND));
        assertEquals("Retry 45s", cooldown.statusAfter(T0 + 100, 10 * SECOND));
        assertEquals(45 * SECOND, cooldown.waitMillis(T0 + 100, 10 * SECOND));
        assertFalse(cooldown.readyAfter(T0 + 100 + 45 * SECOND - 1, 10 * SECOND));
        assertTrue(cooldown.readyAfter(T0 + 100 + 45 * SECOND, 10 * SECOND));
        assertEquals("", cooldown.statusAfter(T0 + 100 + 45 * SECOND, 10 * SECOND));
    }

    @Test void theBufferStillAppliesWhenItIsLongerThanTheRetryDelay() {
        SetHomeCooldown cooldown = new SetHomeCooldown();
        cooldown.sent(T0);

        assertFalse(cooldown.message("You must wait 0 seconds to set a home again.", T0, 5 * SECOND));
        assertEquals("Retry 10s", cooldown.statusAfter(T0, 10 * SECOND));
        assertFalse(cooldown.readyAfter(T0 + 5 * SECOND, 10 * SECOND));
        assertFalse(cooldown.readyAfter(T0 + 10 * SECOND - 1, 10 * SECOND));
        assertTrue(cooldown.readyAfter(T0 + 10 * SECOND, 10 * SECOND));
        assertEquals("Buffer 4s", cooldown.statusAfter(T0 + 6 * SECOND, 10 * SECOND));
    }

    @Test void aSecondZeroSecondRefusalRestartsTheRetryDelay() {
        SetHomeCooldown cooldown = new SetHomeCooldown();
        cooldown.sent(T0);
        assertFalse(cooldown.message("You have to wait 0m 0sec to set a home again.", T0, 30 * SECOND));

        cooldown.sent(T0 + 30 * SECOND);
        assertFalse(cooldown.message(
            "You have to wait 0m 0sec to set a home again.", T0 + 30 * SECOND, 30 * SECOND));
        assertFalse(cooldown.readyAfter(T0 + 60 * SECOND - 1, 10 * SECOND));
        assertTrue(cooldown.readyAfter(T0 + 60 * SECOND, 10 * SECOND));
    }

    @Test void aStatedCountdownReplacesTheRetryDelay() {
        SetHomeCooldown cooldown = new SetHomeCooldown();
        cooldown.sent(T0);
        assertFalse(cooldown.message("You have to wait 0m 0sec to set a home again.", T0, 5 * MINUTE));

        assertTrue(cooldown.message("You must wait 4 minutes to set a home again.", T0 + SECOND));
        assertEquals(4 * MINUTE, cooldown.remaining(T0 + SECOND));
        assertFalse(cooldown.readyAfter(T0 + SECOND + 4 * MINUTE, 10 * SECOND));
        assertEquals("Buffer 10s", cooldown.statusAfter(T0 + SECOND + 4 * MINUTE, 10 * SECOND));
        assertTrue(cooldown.readyAfter(T0 + SECOND + 4 * MINUTE + 10 * SECOND, 10 * SECOND));
    }

    @Test void aRetryDelayOfZeroKeepsTheBufferOnlyBehaviour() {
        SetHomeCooldown cooldown = new SetHomeCooldown();
        cooldown.sent(T0);

        assertFalse(cooldown.message("You have to wait 0m 0sec to set a home again.", T0, 0));
        assertEquals("Buffer 10s", cooldown.statusAfter(T0, 10 * SECOND));
        assertFalse(cooldown.readyAfter(T0 + 10 * SECOND - 1, 10 * SECOND));
        assertTrue(cooldown.readyAfter(T0 + 10 * SECOND, 10 * SECOND));
    }
}
