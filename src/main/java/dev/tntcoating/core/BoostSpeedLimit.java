package dev.tntcoating.core;

import dev.tntcoating.core.AdaptiveFlightController.Motion;
import java.util.ArrayDeque;
import java.util.Objects;

/**
 * Learns a shared boost displacement budget from delayed server corrections. A rejected speed is distinct
 * from the slower recovery that follows it: arrivals in the same feedback episode never replace that
 * boundary. Clean movement can eventually probe above it, one small step at a time.
 */
public final class BoostSpeedLimit {
    public static final int FEEDBACK_TICKS = AdaptiveFlightLearning.FEEDBACK_TICKS;
    public static final int HOLD_TICKS = 200;
    public static final int PROBE_TICKS = 40;
    public static final double BACKOFF = .95;
    public static final double PROBE_STEP = .01;
    private static final double EPSILON = 1e-9;
    private static final long NEVER = Long.MIN_VALUE;
    private record Sample(long tick, double speed) {}

    private final ArrayDeque<Sample> history = new ArrayDeque<>();
    private boolean enabled = true;
    private double ceiling = Double.POSITIVE_INFINITY, provenCeiling;
    private long tick, lastObserved = NEVER, lastCorrection = NEVER;
    private long ignoreThroughTick = NEVER;
    private int holdTicks, cleanTicks;

    /** Disabling bypasses the remembered limit; enabling it again keeps the learned boundary. */
    public void configure(boolean enabled) {
        if (this.enabled == enabled) return;
        this.enabled = enabled;
        invalidateEvidence();
    }

    /** Advances once per client tick, even while the boost key is released. */
    public void tick() {
        if (lastObserved != tick) cleanTicks = 0;
        tick++;
        if (holdTicks > 0) holdTicks--;
        while (!history.isEmpty() && tick - history.getFirst().tick() > FEEDBACK_TICKS)
            history.removeFirst();
    }

    /**
     * The final controlled boost motion, after terrain and glide guards. Only clean motion provides speed
     * evidence. A shared-limit clip is clean; clipping by terrain, chunks, glide or a collision is not.
     */
    public void observe(Motion actual, boolean clean) {
        Objects.requireNonNull(actual, "actual");
        if (!enabled || lastObserved == tick) return;
        lastObserved = tick;
        if (tick <= ignoreThroughTick) return;
        double speed = actual.length();
        if (clean && speed > .01) history.addLast(new Sample(tick, speed));
        if (clean && holdTicks == 0 && Double.isFinite(ceiling)
            && speed + EPSILON >= ceiling * .999) {
            if (++cleanTicks >= PROBE_TICKS) {
                provenCeiling = ceiling;
                ceiling += PROBE_STEP;
                cleanTicks = 0;
            }
        } else cleanTicks = 0;
    }

    /**
     * Uses the recent peak of actual clean 3D motion, including a correction arriving after key release.
     * Further arrivals need a complete feedback window of quiet before they can teach a new boundary.
     * Returns whether this arrival supplied usable boundary evidence.
     */
    public boolean corrected() {
        if (!enabled || tick <= ignoreThroughTick) return false;
        boolean sameEpisode = lastCorrection != NEVER && tick - lastCorrection <= FEEDBACK_TICKS;
        cleanTicks = 0;
        double rejected = 0;
        for (Sample sample : history) rejected = Math.max(rejected, sample.speed());
        history.clear();
        // An arrival with no boost evidence must not prevent the next real rejection from
        // teaching the first boundary, including just after a manual reset's quiet window.
        if (rejected > .01 || Double.isFinite(ceiling)) lastCorrection = tick;
        if (Double.isFinite(ceiling)) holdTicks = HOLD_TICKS;
        if (sameEpisode || rejected <= .01) return false;
        // A rejected small probe does not discredit the slower ceiling that already survived a full
        // feedback window. Returning there avoids cutting another 5% on every later boundary test.
        boolean failedProbe = provenCeiling > 0 && rejected > provenCeiling + EPSILON
            && rejected <= provenCeiling + PROBE_STEP + EPSILON;
        ceiling = Math.min(ceiling, failedProbe ? provenCeiling : rejected * BACKOFF);
        if (!failedProbe) provenCeiling = 0;
        holdTicks = HOLD_TICKS;
        return true;
    }

    /** Forgets the boundary when the player/world/session is reset. */
    public void reset() {
        invalidateEvidence();
        ceiling = Double.POSITIVE_INFINITY;
        provenCeiling = 0;
        tick = 0;
        ignoreThroughTick = NEVER;
        holdTicks = cleanTicks = 0;
    }

    /** Clears the cap for a manual retry, allowing old in-flight feedback to drain before learning again. */
    public void forget() {
        reset();
        ignoreThroughTick = FEEDBACK_TICKS;
    }

    /** A teleport, stall or equipment handoff invalidates recent evidence, while retaining the known cap. */
    public void invalidateEvidence() {
        history.clear();
        cleanTicks = 0;
        lastObserved = lastCorrection = NEVER;
    }

    public double ceiling() { return enabled ? ceiling : Double.POSITIVE_INFINITY; }
    public boolean limited() { return Double.isFinite(ceiling()); }
    public int holdTicksRemaining() { return enabled ? holdTicks : 0; }
}
