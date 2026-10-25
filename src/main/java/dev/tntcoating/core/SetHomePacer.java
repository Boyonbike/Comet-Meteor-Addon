/*
 * Part of Comet's Auto Set Home module.
 * SPDX-License-Identifier: GPL-3.0-only
 */

package dev.tntcoating.core;

/**
 * Schedules detected cooldowns independently of polling.
 *
 * <p>In auto-detect mode the configured interval is only a fallback: a newly detected cooldown, or that cooldown
 * ending, allows the next attempt immediately after the buffer. In fixed-timer mode the configured interval is the
 * schedule and detected cooldowns are ignored.</p>
 */
public final class SetHomePacer {
    /** Default wait after a detected server cooldown, so the next command is not sent on the exact boundary. */
    public static final long DEFAULT_BUFFER_MILLIS = 10_000L;

    private boolean attempted;
    private long lastAttemptAt;
    private long checkedRevision;
    private long consumedRevision;

    /**
     * Returns true when the next command may be sent now. Call {@link #sent(long)} after an attempt, whether or not
     * the command could actually be sent.
     */
    public boolean ready(long now, long intervalMillis, SetHomeCooldown cooldown) {
        return ready(now, intervalMillis, cooldown, DEFAULT_BUFFER_MILLIS, false);
    }

    /**
     * Returns true when an attempt is due in the selected timing mode.
     *
     * @param fixedTimer when true the detected cooldown is ignored and only the configured interval matters
     */
    public boolean ready(long now, long intervalMillis, SetHomeCooldown cooldown, long bufferMillis, boolean fixedTimer) {
        checkedRevision = cooldown.revision();
        if (fixedTimer) return !attempted || now - lastAttemptAt >= Math.max(0, intervalMillis);
        if (!cooldown.readyAfter(now, bufferMillis)) return false;
        if (checkedRevision != consumedRevision) return true;
        return !attempted || now - lastAttemptAt >= Math.max(0, intervalMillis);
    }

    /**
     * Time until the next attempt, without changing scheduler state. In auto-detect mode this accounts for the
     * detected cooldown and buffer; in fixed-timer mode it only reflects the configured interval.
     */
    public long waitMillis(long now, long intervalMillis, SetHomeCooldown cooldown, long bufferMillis,
        boolean fixedTimer) {
        long intervalLeft = !attempted
            ? 0
            : Math.max(0, intervalMillis - Math.max(0, now - lastAttemptAt));
        if (fixedTimer) return intervalLeft;
        long cooldownLeft = cooldown.waitMillis(now, bufferMillis);
        if (cooldownLeft > 0) {
            // A new server cooldown outranks the fallback interval; otherwise both must elapse.
            return cooldown.revision() != consumedRevision ? cooldownLeft : Math.max(cooldownLeft, intervalLeft);
        }
        if (cooldown.revision() != consumedRevision) return 0;
        return intervalLeft;
    }

    public void sent(long now) {
        attempted = true;
        lastAttemptAt = now;
        consumedRevision = checkedRevision;
    }

    public void reset() {
        attempted = false;
        lastAttemptAt = 0;
        checkedRevision = 0;
        consumedRevision = 0;
    }
}
