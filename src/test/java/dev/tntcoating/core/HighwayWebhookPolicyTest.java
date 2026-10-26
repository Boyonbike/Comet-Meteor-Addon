package dev.tntcoating.core;

import java.util.EnumSet;
import java.util.Map;
import org.junit.jupiter.api.Test;

import static dev.tntcoating.core.HighwayWebhook.Event.*;
import static dev.tntcoating.core.HighwayWebhookPolicy.Category.*;
import static org.junit.jupiter.api.Assertions.*;

class HighwayWebhookPolicyTest {
    @Test
    void groupsEveryEventUnderItsOwnNotificationSetting() {
        Map<HighwayWebhook.Event, HighwayWebhookPolicy.Category> categories = Map.ofEntries(
            Map.entry(STARTED, TRIP),
            Map.entry(HOME_REPLACED, HOME),
            Map.entry(RESUMED, TRIP),
            Map.entry(FINISHED, TRIP),
            Map.entry(STOPPED, TRIP),
            Map.entry(HighwayWebhook.Event.STATUS, HighwayWebhookPolicy.Category.STATUS),
            Map.entry(SAFETY_LOGOUT, SAFETY),
            Map.entry(HighwayWebhook.Event.DEATH, HighwayWebhookPolicy.Category.DEATH),
            Map.entry(LAVA_AVOIDANCE, LAVA),
            Map.entry(DISCONNECTED, CONNECTION),
            Map.entry(RECOVERY, CONNECTION),
            Map.entry(DIMENSION, CONNECTION),
            Map.entry(LOGIN_FAILED, CONNECTION),
            Map.entry(HighwayWebhook.Event.PORTAL, HighwayWebhookPolicy.Category.PORTAL),
            Map.entry(NEEDS_HELP, ATTENTION),
            Map.entry(HighwayWebhook.Event.TEST, ATTENTION)
        );

        assertEquals(EnumSet.allOf(HighwayWebhook.Event.class), categories.keySet());
        categories.forEach((event, category) -> assertEquals(category, HighwayWebhookPolicy.category(event), event.name()));
    }

    @Test void confirmedHomesAreNeverSuppressedByGenericRepeatCooldown() {
        var policy = new HighwayWebhookPolicy();
        assertTrue(policy.allow(HOME_REPLACED, 100, 1_000));
        assertTrue(policy.allow(HOME_REPLACED, 101, 1_000));
    }

    @Test
    void repeatsOfOneEventWaitForTheCooldownBoundary() {
        HighwayWebhookPolicy policy = new HighwayWebhookPolicy();

        assertTrue(policy.allow(NEEDS_HELP, 100, 10));
        assertFalse(policy.allow(NEEDS_HELP, 100, 10));
        assertFalse(policy.allow(NEEDS_HELP, 109, 10));
        assertTrue(policy.allow(NEEDS_HELP, 110, 10));
        assertFalse(policy.allow(NEEDS_HELP, 119, 10));
        assertTrue(policy.allow(NEEDS_HELP, 120, 10));
    }

    @Test
    void suppressedAttemptsDoNotExtendTheCooldown() {
        HighwayWebhookPolicy policy = new HighwayWebhookPolicy();
        assertTrue(policy.allow(NEEDS_HELP, 0, 100));

        assertFalse(policy.allow(NEEDS_HELP, 99, 100));
        assertTrue(policy.allow(NEEDS_HELP, 100, 100));
    }

    @Test
    void distinctEventsKeepIndependentCooldownsEvenInTheSameCategory() {
        HighwayWebhookPolicy policy = new HighwayWebhookPolicy();

        assertTrue(policy.allow(DISCONNECTED, 0, 100));
        assertTrue(policy.allow(RECOVERY, 0, 100));
        assertTrue(policy.allow(HighwayWebhook.Event.PORTAL, 0, 100));
        assertFalse(policy.allow(DISCONNECTED, 99, 100));
        assertFalse(policy.allow(RECOVERY, 99, 100));
    }

    @Test
    void deathAndSafetyLogoutAlwaysBypassTheCooldown() {
        HighwayWebhookPolicy policy = new HighwayWebhookPolicy();

        for (HighwayWebhook.Event event : new HighwayWebhook.Event[] {HighwayWebhook.Event.DEATH, SAFETY_LOGOUT}) {
            assertTrue(policy.allow(event, 0, 100));
            assertTrue(policy.allow(event, 0, 100));
            assertTrue(policy.allow(event, 1, 100));
        }
    }

    @Test
    void zeroCooldownAllowsEveryAttempt() {
        HighwayWebhookPolicy policy = new HighwayWebhookPolicy();

        assertTrue(policy.allow(NEEDS_HELP, 0, 100));
        assertTrue(policy.allow(NEEDS_HELP, 0, 0));
        assertTrue(policy.allow(NEEDS_HELP, 0, 0));
    }

    @Test
    void resetLetsANewTripSendItsFirstEventImmediately() {
        HighwayWebhookPolicy policy = new HighwayWebhookPolicy();
        assertTrue(policy.allow(NEEDS_HELP, 0, 100));
        assertTrue(policy.allow(HighwayWebhook.Event.PORTAL, 0, 100));
        policy.reset();

        assertTrue(policy.allow(NEEDS_HELP, 1, 100));
        assertTrue(policy.allow(HighwayWebhook.Event.PORTAL, 1, 100));
    }

    @Test
    void cooldownSupportsNegativeOriginsAndClockWraparound() {
        for (long start : new long[] {-100, Long.MAX_VALUE - 5}) {
            HighwayWebhookPolicy policy = new HighwayWebhookPolicy();
            assertTrue(policy.allow(NEEDS_HELP, start, 10));

            assertFalse(policy.allow(NEEDS_HELP, start + 9, 10));
            assertTrue(policy.allow(NEEDS_HELP, start + 10, 10));
        }
    }

    @Test
    void rejectsNegativeCooldownsWithoutConsumingTheEvent() {
        HighwayWebhookPolicy policy = new HighwayWebhookPolicy();

        assertThrows(IllegalArgumentException.class, () -> policy.allow(NEEDS_HELP, 0, -1));
        assertTrue(policy.allow(NEEDS_HELP, 0, 100));
    }
}
