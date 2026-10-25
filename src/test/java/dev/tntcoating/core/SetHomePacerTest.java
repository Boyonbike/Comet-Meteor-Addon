package dev.tntcoating.core;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SetHomePacerTest {
    private static final long SECOND = 1_000L;
    private static final long MINUTE = 60 * SECOND;

    @Test
    void firstAttemptIsReadyImmediately() {
        SetHomePacer pacer = new SetHomePacer();
        assertTrue(pacer.ready(0, MINUTE, new SetHomeCooldown()));
    }

    @Test
    void waitsForTheConfiguredIntervalBetweenAttempts() {
        SetHomePacer pacer = new SetHomePacer();
        SetHomeCooldown cooldown = new SetHomeCooldown();

        assertTrue(pacer.ready(0, MINUTE, cooldown));
        pacer.sent(0);

        assertFalse(pacer.ready(MINUTE - 1, MINUTE, cooldown));
        assertTrue(pacer.ready(MINUTE, MINUTE, cooldown));
    }

    @Test
    void aStatedCooldownOverridesALongerInterval() {
        SetHomePacer pacer = new SetHomePacer();
        SetHomeCooldown cooldown = new SetHomeCooldown();
        pacer.sent(0);
        cooldown.message("You must wait 1 minute to set a home again.", 0);

        assertFalse(pacer.ready(0, 10 * MINUTE, cooldown));
        assertFalse(pacer.ready(MINUTE + 9 * SECOND, 10 * MINUTE, cooldown));
        assertTrue(pacer.ready(MINUTE + 10 * SECOND, 10 * MINUTE, cooldown));
    }

    @Test
    void waitsOutTheBufferAfterTheCooldownEnds() {
        SetHomePacer pacer = new SetHomePacer();
        SetHomeCooldown cooldown = new SetHomeCooldown();
        pacer.sent(0);
        cooldown.message("You must wait 5 minutes to set a home again.", 0);

        assertFalse(pacer.ready(0, MINUTE, cooldown));
        cooldown.message("Your sethome cooldown has expired.", 5 * SECOND);

        assertFalse(pacer.ready(5 * SECOND, MINUTE, cooldown));
        assertFalse(pacer.ready(15 * SECOND - 1, MINUTE, cooldown));
        assertTrue(pacer.ready(15 * SECOND, MINUTE, cooldown));
    }

    @Test
    void readyStaysSetUntilTheAttemptIsRecorded() {
        SetHomePacer pacer = new SetHomePacer();
        SetHomeCooldown cooldown = new SetHomeCooldown();
        pacer.sent(0);
        cooldown.message("You must wait 1 minute to set a home again.", 0);

        assertFalse(pacer.ready(0, 2 * MINUTE, cooldown));
        assertTrue(pacer.ready(MINUTE + 10 * SECOND, 2 * MINUTE, cooldown));
        assertTrue(pacer.ready(MINUTE + 20 * SECOND, 2 * MINUTE, cooldown));

        pacer.sent(MINUTE + 20 * SECOND);
        assertFalse(pacer.ready(2 * MINUTE, 2 * MINUTE, cooldown));
        assertTrue(pacer.ready(3 * MINUTE + 20 * SECOND, 2 * MINUTE, cooldown));
    }

    @Test
    void intervalAppliesAgainAfterTheCooldownAttempt() {
        SetHomePacer pacer = new SetHomePacer();
        SetHomeCooldown cooldown = new SetHomeCooldown();
        pacer.sent(0);
        cooldown.message("You must wait 1 minute to set a home again.", 0);
        assertFalse(pacer.ready(0, 2 * MINUTE, cooldown));
        assertTrue(pacer.ready(MINUTE + 10 * SECOND, 2 * MINUTE, cooldown));
        pacer.sent(MINUTE + 10 * SECOND);

        assertFalse(pacer.ready(3 * MINUTE, 2 * MINUTE, cooldown));
        assertTrue(pacer.ready(3 * MINUTE + 10 * SECOND, 2 * MINUTE, cooldown));
    }

    @Test
    void resetMakesTheNextAttemptImmediate() {
        SetHomePacer pacer = new SetHomePacer();
        SetHomeCooldown cooldown = new SetHomeCooldown();
        pacer.sent(0);
        pacer.reset();

        assertTrue(pacer.ready(SECOND, MINUTE, cooldown));
    }
    @Test
    void detectedExpiryOverridesIntervalEvenWhenNoTickObservedTheCountdown() {
        SetHomePacer pacer = new SetHomePacer();
        SetHomeCooldown cooldown = new SetHomeCooldown();
        pacer.sent(0);
        cooldown.message("You must wait 1 minute to set a home again.", 0);
        // A GUI can keep HomeAutomation from polling until after the whole cooldown and buffer.
        assertTrue(pacer.ready(MINUTE + 10 * SECOND, 10 * MINUTE, cooldown));
        pacer.sent(MINUTE + 10 * SECOND);
        assertFalse(pacer.ready(MINUTE + 11 * SECOND, 10 * MINUTE, cooldown));
    }

    @Test
    void detectedDelayControlsTheNextConfirmedCycleWithAShortFallback() {
        SetHomePacer pacer = new SetHomePacer();
        SetHomeCooldown cooldown = new SetHomeCooldown();
        long start = 1_000_000;
        pacer.sent(start);
        cooldown.sent(start);
        cooldown.message("You must wait 1 minute to set a home again.", start);
        assertTrue(pacer.ready(start + MINUTE + 10 * SECOND, 5 * SECOND, cooldown));
        long next = start + MINUTE + 10 * SECOND;
        pacer.sent(next);
        cooldown.sent(next);
        cooldown.confirmed(next);
        assertFalse(pacer.ready(next + 5 * SECOND, 5 * SECOND, cooldown));
        assertFalse(pacer.ready(next + MINUTE + 9_999, 5 * SECOND, cooldown));
        assertTrue(pacer.ready(next + MINUTE + 10 * SECOND, 5 * SECOND, cooldown));
    }
    @Test
    void fixedTimerUsesTheIntervalAndIgnoresDetectedCooldowns() {
        SetHomePacer pacer = new SetHomePacer();
        SetHomeCooldown cooldown = new SetHomeCooldown();
        cooldown.message("You must wait 10 minutes to set a home again.", 0);

        assertTrue(pacer.ready(0, MINUTE, cooldown, 10 * SECOND, true));
        pacer.sent(0);
        assertFalse(pacer.ready(MINUTE - 1, MINUTE, cooldown, 10 * SECOND, true));
        assertTrue(pacer.ready(MINUTE, MINUTE, cooldown, 10 * SECOND, true));
    }

    @Test
    void autoModeUsesTheConfiguredBuffer() {
        SetHomePacer pacer = new SetHomePacer();
        SetHomeCooldown cooldown = new SetHomeCooldown();
        pacer.sent(0);
        cooldown.message("You must wait 1 minute to set a home again.", 0);

        assertFalse(pacer.ready(MINUTE + 4 * SECOND, MINUTE, cooldown, 5 * SECOND, false));
        assertTrue(pacer.ready(MINUTE + 5 * SECOND, MINUTE, cooldown, 5 * SECOND, false));
    }

    @Test
    void waitMillisDescribesTheFixedTimer() {
        SetHomePacer pacer = new SetHomePacer();
        SetHomeCooldown cooldown = new SetHomeCooldown();
        cooldown.message("You must wait 10 minutes to set a home again.", 0);

        assertEquals(0, pacer.waitMillis(0, MINUTE, cooldown, 10 * SECOND, true));
        pacer.sent(0);
        assertEquals(MINUTE, pacer.waitMillis(0, MINUTE, cooldown, 10 * SECOND, true));
        assertEquals(30 * SECOND, pacer.waitMillis(30 * SECOND, MINUTE, cooldown, 10 * SECOND, true));
        assertEquals(0, pacer.waitMillis(MINUTE, MINUTE, cooldown, 10 * SECOND, true));
    }

    @Test
    void waitMillisDescribesCooldownAndBufferInAutoMode() {
        SetHomePacer pacer = new SetHomePacer();
        SetHomeCooldown cooldown = new SetHomeCooldown();
        pacer.sent(0);
        cooldown.message("You must wait 1 minute to set a home again.", 0);

        assertEquals(MINUTE + 10 * SECOND, pacer.waitMillis(0, MINUTE, cooldown, 10 * SECOND, false));
        assertEquals(15 * SECOND, pacer.waitMillis(MINUTE - 5 * SECOND, MINUTE, cooldown, 10 * SECOND, false));
        assertEquals(5 * SECOND, pacer.waitMillis(MINUTE + 5 * SECOND, MINUTE, cooldown, 10 * SECOND, false));
        assertEquals(0, pacer.waitMillis(MINUTE + 10 * SECOND, MINUTE, cooldown, 10 * SECOND, false));
    }

    @Test
    void aNewServerCooldownOutranksTheLongerFallbackInterval() {
        SetHomePacer pacer = new SetHomePacer();
        SetHomeCooldown cooldown = new SetHomeCooldown();
        pacer.sent(0);
        cooldown.message("You can set another home in 1 minute.", 0);
        assertTrue(pacer.ready(MINUTE + 10 * SECOND, MINUTE, cooldown, 10 * SECOND, false));
        pacer.sent(MINUTE + 10 * SECOND);

        cooldown.message("You can set another home in 10 seconds.", MINUTE + 15 * SECOND);

        assertEquals(20 * SECOND,
            pacer.waitMillis(MINUTE + 15 * SECOND, MINUTE, cooldown, 10 * SECOND, false));
    }

    @Test
    void duplicateFinishedMessagesDoNotAuthorizeAnotherAttempt() {
        SetHomePacer pacer = new SetHomePacer();
        SetHomeCooldown cooldown = new SetHomeCooldown();
        cooldown.message("Your sethome cooldown has expired.", 0);
        assertTrue(pacer.ready(10 * SECOND, MINUTE, cooldown));
        pacer.sent(10 * SECOND);
        cooldown.message("Your sethome cooldown has expired.", 11 * SECOND);
        assertFalse(pacer.ready(11 * SECOND, MINUTE, cooldown));
    }

    @Test
    void anUnusableRefusalWaitsTheRetryDelayAndThenTheFallbackInterval() {
        SetHomePacer pacer = new SetHomePacer();
        SetHomeCooldown cooldown = new SetHomeCooldown();
        pacer.sent(0);
        cooldown.sent(0);
        cooldown.message("You have to wait 0m 0sec to set a home again.", 0, 30 * SECOND);

        assertEquals(30 * SECOND, pacer.waitMillis(0, 5 * MINUTE, cooldown, 10 * SECOND, false));
        assertFalse(pacer.ready(30 * SECOND - 1, 5 * MINUTE, cooldown, 10 * SECOND, false));
        assertTrue(pacer.ready(30 * SECOND, 5 * MINUTE, cooldown, 10 * SECOND, false));
        pacer.sent(30 * SECOND);

        assertFalse(pacer.ready(60 * SECOND, 5 * MINUTE, cooldown, 10 * SECOND, false));
        assertTrue(pacer.ready(5 * MINUTE + 30 * SECOND, 5 * MINUTE, cooldown, 10 * SECOND, false));
    }
}
