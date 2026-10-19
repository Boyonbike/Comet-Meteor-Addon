package dev.tntcoating.core;

import dev.tntcoating.core.AdaptiveFlightController.Motion;
import dev.tntcoating.core.AdaptiveFlightMemory.Limits;
import java.util.ArrayDeque;
import java.util.Objects;

/** Directional flight learning from movement actually used, with a delay for server feedback. */
public final class AdaptiveFlightLearning {
    public static final int FEEDBACK_TICKS = 20;
    private static final double EPSILON = 1e-9;
    private record Sample(long tick, Motion motion, int input, boolean clean,
                          boolean verticalProof, boolean horizontalProof, boolean totalProof) {}

    private final ArrayDeque<Sample> history = new ArrayDeque<>();
    /** Speeds previously associated with a correction. Guidance slows exploration but is not a hard ceiling. */
    private Limits guidance = Limits.UNKNOWN;
    private double initialVertical = .75, configuredAcceleration = .05;
    private double horizontal = 64, ascent = .75, descent = .75, verticalAcceleration = .05;
    private double total = 64, provenTotal, observedTotal;
    private double provenHorizontal, provenAscent, provenDescent;
    private double observedHorizontal, observedAscent, observedDescent;
    private double recoveryAscent, recoveryDescent, recoveryTotal;
    private long tick, lastObservedTick = Long.MIN_VALUE, ignoreThroughTick = Long.MIN_VALUE;
    private int horizontalStreak, ascentStreak, descentStreak, totalStreak, recoveryDelay = 60;
    private double recoveryRate = .0025;

    public void configure(double initialVerticalSpeed, double acceleration) {
        if (!Double.isFinite(initialVerticalSpeed) || initialVerticalSpeed <= 0 || initialVerticalSpeed > 64
            || !Double.isFinite(acceleration) || acceleration <= 0 || acceleration > 2)
            throw new IllegalArgumentException("Vertical learning settings must be finite and positive.");
        if (ascent == initialVertical && guidance.ascent() == 64) ascent = initialVerticalSpeed;
        if (descent == initialVertical && guidance.descent() == 64) descent = initialVerticalSpeed;
        if (verticalAcceleration == configuredAcceleration && guidance.verticalAcceleration() == 2)
            verticalAcceleration = acceleration;
        initialVertical = initialVerticalSpeed;
        configuredAcceleration = acceleration;
        verticalAcceleration = Math.min(verticalAcceleration, acceleration);
    }

    /** Restoring a profile starts at its last settled speeds. */
    public void restore(Limits limits) {
        restore(limits, limits);
    }

    /** Remembered correction guidance makes exploration more cautious without permanently capping it. */
    public void restore(Limits limits, Limits correctionGuidance) {
        Objects.requireNonNull(limits, "limits");
        guidance = Objects.requireNonNull(correctionGuidance, "correctionGuidance");
        horizontal = limits.horizontal();
        // A direction this server never corrected has no evidence of a limit below the configured start.
        ascent = limits.ascent() == 64 ? initialVertical
            : correctionGuidance.ascent() >= 64 ? Math.max(initialVertical, limits.ascent()) : limits.ascent();
        descent = limits.descent() == 64 ? initialVertical
            : correctionGuidance.descent() >= 64 ? Math.max(initialVertical, limits.descent()) : limits.descent();
        total = limits.total();
        verticalAcceleration = Math.min(configuredAcceleration, limits.verticalAcceleration());
        provenHorizontal = limits.horizontal() == 64 ? 0 : horizontal;
        provenAscent = limits.ascent() == 64 ? 0 : limits.ascent();
        provenDescent = limits.descent() == 64 ? 0 : limits.descent();
        provenTotal = total == 64 ? 0 : total;
        observedHorizontal = provenHorizontal;
        observedAscent = provenAscent;
        observedDescent = provenDescent;
        observedTotal = provenTotal;
        recoveryAscent = recoveryDescent = recoveryTotal = 0;
        ignoreThroughTick = Long.MIN_VALUE;
        resetHistory();
    }

    public Limits limits() {
        return new Limits(horizontal, ascent, descent, verticalAcceleration, total);
    }

    /** Kept under the old accessor name for memory-format/API compatibility. Values are soft guidance. */
    public Limits ceilings() { return guidance; }

    /** Only delayed clean evidence or a correction is saved, never a just-started speed probe. */
    public Limits remembered() {
        boolean verticalKnown = guidance.ascent() < 64 || guidance.descent() < 64
            || provenAscent > 0 || provenDescent > 0;
        double rememberedHorizontal = provenHorizontal > 0 ? provenHorizontal
            : guidance.horizontal() < 64 ? guidance.horizontal() : 64;
        double rememberedAscent = provenAscent > 0 ? provenAscent
            : guidance.ascent() < 64 ? guidance.ascent() : 64;
        double rememberedDescent = provenDescent > 0 ? provenDescent
            : guidance.descent() < 64 ? guidance.descent() : 64;
        return new Limits(rememberedHorizontal, rememberedAscent, rememberedDescent,
            verticalKnown ? verticalAcceleration : guidance.verticalAcceleration(),
            provenTotal > 0 ? provenTotal : guidance.total());
    }

    public void tick(int delay, double rate) {
        if (delay < 0 || !Double.isFinite(rate) || rate < 0 || rate > 1)
            throw new IllegalArgumentException("Invalid learning recovery settings.");
        if (lastObservedTick != tick) horizontalStreak = ascentStreak = descentStreak = totalStreak = 0;
        tick++;
        recoveryDelay = Math.max(40, delay);
        recoveryRate = rate;
        while (!history.isEmpty() && tick - history.getFirst().tick() > FEEDBACK_TICKS) {
            Sample sample = history.removeFirst();
            if (sample.clean()) {
                observedHorizontal = Math.max(observedHorizontal,
                    sample.motion().horizontalLength());
                observedTotal = Math.max(observedTotal, sample.motion().length());
                if (sample.input() > 0 && sample.motion().y() > 0)
                    observedAscent = Math.max(observedAscent, sample.motion().y());
                else if (sample.input() < 0 && sample.motion().y() < 0)
                    observedDescent = Math.max(observedDescent, -sample.motion().y());
            }
            if (sample.horizontalProof()) {
                provenHorizontal = Math.max(provenHorizontal,
                    Math.min(horizontal, sample.motion().horizontalLength()));
            }
            if (sample.verticalProof()) {
                double speed = Math.abs(sample.motion().y());
                if (sample.input() > 0) provenAscent = Math.max(provenAscent, Math.min(ascent, speed));
                else if (sample.input() < 0) provenDescent = Math.max(provenDescent, Math.min(descent, speed));
            }
            if (sample.totalProof()) provenTotal = Math.max(provenTotal, Math.min(total, sample.motion().length()));
        }
    }

    /** Call once per controlled tick, with the final terrain/glide-clipped output. */
    public void observe(Motion actual, int verticalInput, boolean sprintBoost, boolean clean, double fraction) {
        observe(actual, verticalInput, sprintBoost, clean, fraction, fraction);
    }

    /** Horizontal and vertical percentages are independent evidence for cautious speed probes. */
    public void observe(Motion actual, int verticalInput, boolean sprintBoost, boolean clean,
                        double horizontalFraction, double verticalFraction) {
        Objects.requireNonNull(actual, "actual");
        if (verticalInput < -1 || verticalInput > 1
            || !validFraction(horizontalFraction) || !validFraction(verticalFraction))
            throw new IllegalArgumentException("Invalid flight learning input.");
        if (lastObservedTick == tick) return;
        lastObservedTick = tick;
        if (tick <= ignoreThroughTick) return;
        boolean matching = verticalInput > 0 && actual.y() > 0 || verticalInput < 0 && actual.y() < 0;
        double ceiling = verticalInput > 0 ? ascent : descent;
        // A clean 25% request does not prove the untested 100% speed. Partial
        // settings remain useful setback evidence, but cannot authorize a faster probe.
        boolean verticalProof = clean && sprintBoost && matching && verticalFraction >= .999
            && Math.abs(actual.y()) >= .85 * ceiling * verticalFraction;
        boolean horizontalProof = clean && sprintBoost && horizontalFraction >= .999
            && actual.horizontalLength() >= .85 * horizontal * horizontalFraction;
        boolean totalProof = clean && sprintBoost && total < 64 && actual.length() >= .85 * total
            && (horizontalFraction >= .999 && actual.horizontalLength() > .01
                || verticalFraction >= .999 && matching);
        history.addLast(new Sample(tick, actual, verticalInput, clean, verticalProof, horizontalProof, totalProof));
        horizontalStreak = horizontalProof ? horizontalStreak + 1 : 0;
        // A clean reversal pauses the other direction's evidence instead of discarding it.
        if (!clean || !sprintBoost || verticalFraction < .999 || verticalInput == 0)
            ascentStreak = descentStreak = 0;
        else if (verticalProof && verticalInput > 0) ascentStreak++;
        else if (verticalProof && verticalInput < 0) descentStreak++;
        totalStreak = totalProof ? totalStreak + 1 : 0;
        if (recoveryRate > 0 && horizontalStreak >= probeDelay(horizontal, guidance.horizontal())) {
            horizontal = Math.min(64, horizontal + probeStep(horizontal, guidance.horizontal()));
            horizontalStreak = 0;
        }
        if (recoveryRate > 0 && ascentStreak >= fastProbeDelay(ascent, guidance.ascent(), recoveryAscent)) {
            ascent = Math.min(64, ascent + fastProbeStep(ascent, guidance.ascent(), recoveryAscent));
            recoverVerticalAcceleration();
            ascentStreak = 0;
        }
        if (recoveryRate > 0 && descentStreak >= fastProbeDelay(descent, guidance.descent(), recoveryDescent)) {
            descent = Math.min(64, descent + fastProbeStep(descent, guidance.descent(), recoveryDescent));
            recoverVerticalAcceleration();
            descentStreak = 0;
        }
        if (recoveryRate > 0 && totalStreak >= fastProbeDelay(total, guidance.total(), recoveryTotal)) {
            total = Math.min(64, total + fastProbeStep(total, guidance.total(), recoveryTotal));
            totalStreak = 0;
        }
    }

    /** Recent intent survives key release, allowing delayed setbacks to penalize the right direction. */
    public boolean corrected(double multiplier) {
        if (!Double.isFinite(multiplier) || multiplier <= 0 || multiplier >= 1)
            throw new IllegalArgumentException("Correction multiplier must be in (0, 1).");
        double horizontal = 0, up = 0, down = 0, combined = 0, recentTotal = 0;
        for (Sample sample : history) {
            recentTotal = Math.max(recentTotal, sample.motion().length());
            horizontal = Math.max(horizontal, sample.motion().horizontalLength());
            if (sample.input() > 0 && sample.motion().y() > .01) up = Math.max(up, sample.motion().y());
            if (sample.input() < 0 && sample.motion().y() < -.01) down = Math.max(down, -sample.motion().y());
            if (sample.motion().horizontalLength() > .01 && Math.abs(sample.motion().y()) > .01
                && sample.input() != 0) combined = Math.max(combined, sample.motion().length());
        }
        // Feedback cannot identify which recent tick failed. A small diagonal earlier
        // in the window must not set the budget for a later, faster level movement.
        if (combined > 0) combined = recentTotal;
        if (horizontal <= .01 && up == 0 && down == 0) { resetHistory(); return false; }
        // A correction far below movement that already survived the feedback window is
        // probably unrelated jitter, a teleport plugin, or another transient condition.
        // Keep the immediate controller backoff, but do not teach it as a speed boundary.
        double observed = combined > 0 ? observedTotal
            : up > 0 ? observedAscent : down > 0 ? observedDescent : observedHorizontal;
        double correctedSpeed = combined > 0 ? combined : up > 0 ? up : down > 0 ? down : horizontal;
        if (observed > .01 && correctedSpeed < observed * .6) {
            resetHistory();
            return false;
        }
        double h = guidance.horizontal(), a = guidance.ascent(), d = guidance.descent();
        double t = guidance.total();
        if (combined > 0) {
            // A diagonal rejection is evidence about total displacement, not either component alone.
            recoveryTotal = Math.max(recoveryTotal, observedTotal);
            t = Math.max(.01, combined * multiplier);
            total = Math.min(total, t);
            provenTotal = Math.min(provenTotal, t);
        } else {
            if (up > 0) {
                recoveryAscent = Math.max(recoveryAscent, observedAscent);
                a = Math.max(.01, up * multiplier);
            }
            if (down > 0) {
                recoveryDescent = Math.max(recoveryDescent, observedDescent);
                d = Math.max(.01, down * multiplier);
            }
            if (up == 0 && down == 0) h = Math.max(.01, horizontal * multiplier);
            else verticalAcceleration = Math.min(verticalAcceleration, Math.max(.005, verticalAcceleration * multiplier));
        }
        guidance = new Limits(h, a, d,
            combined == 0 && (up > 0 || down > 0) ? verticalAcceleration : guidance.verticalAcceleration(), t);
        this.horizontal = Math.min(this.horizontal, h);
        ascent = Math.min(ascent, a);
        descent = Math.min(descent, d);
        // A previously proven faster sample is no longer an acceptable reconnect hint.
        provenAscent = Math.min(provenAscent, a);
        provenDescent = Math.min(provenDescent, d);
        provenHorizontal = Math.min(provenHorizontal, h);
        resetHistory();
        return true;
    }

    public void resetHistory() {
        history.clear();
        horizontalStreak = ascentStreak = descentStreak = totalStreak = 0;
        lastObservedTick = Long.MIN_VALUE;
    }

    /** A client stall makes the following feedback window unreliable as speed-limit evidence. */
    public void pauseForLag() {
        resetHistory();
        ignoreThroughTick = tick + FEEDBACK_TICKS;
    }

    public boolean isLagPaused() { return tick <= ignoreThroughTick; }

    public void forget() { restore(Limits.UNKNOWN); }

    private int probeDelay(double current, double rememberedGuidance) {
        if (!nearGuidance(current, rememberedGuidance)) return recoveryDelay;
        return recoveryDelay > Integer.MAX_VALUE / 2 ? Integer.MAX_VALUE : recoveryDelay * 2;
    }

    private double probeStep(double current, double rememberedGuidance) {
        double ordinary = Math.min(.05, recoveryRate * 20);
        return nearGuidance(current, rememberedGuidance) ? Math.min(.01, recoveryRate * 4) : ordinary;
    }

    private boolean nearGuidance(double current, double rememberedGuidance) {
        if (rememberedGuidance >= 64) return false;
        double ordinaryStep = Math.min(.05, recoveryRate * 20);
        return current + ordinaryStep + EPSILON >= rememberedGuidance
            && current <= rememberedGuidance + .005 + EPSILON;
    }

    private void recoverVerticalAcceleration() {
        verticalAcceleration = Math.min(configuredAcceleration,
            verticalAcceleration + Math.min(.01, recoveryRate * 4));
    }

    private int fastProbeDelay(double current, double rememberedGuidance, double recoveryTarget) {
        int ordinary = Math.max(FEEDBACK_TICKS + 2, recoveryDelay / 3);
        if (current + EPSILON < recoveryTarget || !nearGuidance(current, rememberedGuidance)) return ordinary;
        return ordinary > Integer.MAX_VALUE / 2 ? Integer.MAX_VALUE : ordinary * 2;
    }

    private double fastProbeStep(double current, double rememberedGuidance, double recoveryTarget) {
        double ordinary = Math.min(.25, Math.max(.05, current * .15) * recoveryRate / .0025);
        // Return quickly to demonstrated movement, then resume bounded exploration.
        if (current + EPSILON < recoveryTarget) return Math.min(ordinary, recoveryTarget - current);
        return nearGuidance(current, rememberedGuidance) ? Math.min(.05, ordinary / 2) : ordinary;
    }

    private static boolean validFraction(double value) {
        return Double.isFinite(value) && value >= 0 && value <= 1;
    }
}
