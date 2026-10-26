/*
 * Comet Highway Fly notification controls.
 * SPDX-License-Identifier: GPL-3.0-only
 */
package dev.tntcoating.core;

import java.util.EnumMap;
import java.util.Objects;

/** Notification categories and per-event monotonic-clock cooldowns for one trip. */
public final class HighwayWebhookPolicy {
    public enum Category {
        TRIP, STATUS, HOME, SAFETY, DEATH, LAVA, CONNECTION, PORTAL, ATTENTION
    }

    private final EnumMap<HighwayWebhook.Event, Long> lastAllowedNanos = new EnumMap<>(HighwayWebhook.Event.class);

    /** Maps each event to the notification toggle that controls it. */
    public static Category category(HighwayWebhook.Event event) {
        return switch (event) {
            case STARTED, RESUMED, FINISHED, STOPPED -> Category.TRIP;
            case STATUS -> Category.STATUS;
            case HOME_REPLACED -> Category.HOME;
            case SAFETY_LOGOUT -> Category.SAFETY;
            case DEATH -> Category.DEATH;
            case LAVA_AVOIDANCE -> Category.LAVA;
            case DISCONNECTED, RECOVERY, DIMENSION, LOGIN_FAILED -> Category.CONNECTION;
            case PORTAL -> Category.PORTAL;
            case NEEDS_HELP, TEST -> Category.ATTENTION;
        };
    }

    /**
     * Consumes an allowed event's cooldown; suppressed attempts never extend it. Zero disables the cooldown,
     * and death or safety logout notifications always pass. The caller applies category toggles first.
     */
    public boolean allow(HighwayWebhook.Event event, long nowNanos, long cooldownNanos) {
        Objects.requireNonNull(event, "event");
        if (cooldownNanos < 0) throw new IllegalArgumentException("Webhook cooldown must not be negative.");
        if (event == HighwayWebhook.Event.HOME_REPLACED || event == HighwayWebhook.Event.DEATH || event == HighwayWebhook.Event.SAFETY_LOGOUT) return true;
        Long lastAllowed = lastAllowedNanos.get(event);
        if (cooldownNanos > 0 && lastAllowed != null && nowNanos - lastAllowed < cooldownNanos) return false;
        lastAllowedNanos.put(event, nowNanos);
        return true;
    }

    /** Starts a new trip with no event cooldowns carried over. */
    public void reset() {
        lastAllowedNanos.clear();
    }
}
