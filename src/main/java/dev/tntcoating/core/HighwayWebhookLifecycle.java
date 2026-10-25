/*
 * Comet trip notification lifecycle.
 * SPDX-License-Identifier: GPL-3.0-only
 */
package dev.tntcoating.core;

/** Deduplicates lifecycle alerts independently of world-leave events. */
public final class HighwayWebhookLifecycle {
    public enum Disconnect { NONE, MANUAL, UNEXPECTED }
    private boolean ended;
    private boolean left;

    /** Called before clearing the trip; terminal notifications share one slot. */
    public boolean terminal() {
        if (ended) return false;
        ended = true;
        return true;
    }

    public Disconnect disconnected(boolean transferring, boolean unexpected) {
        if (transferring || left) return Disconnect.NONE;
        left = true;
        return unexpected ? Disconnect.UNEXPECTED : Disconnect.MANUAL;
    }

    public void joined() { left = false; }
    public void reset() { ended = false; left = false; }
}
