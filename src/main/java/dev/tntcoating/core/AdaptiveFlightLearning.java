package dev.tntcoating.core;

import dev.tntcoating.core.AdaptiveFlightController.Motion;
import dev.tntcoating.core.AdaptiveFlightMemory.Limits;
import java.util.ArrayDeque;
import java.util.Objects;

/** Directional flight learning from movement actually used, with a delay for server feedback. */
public final class AdaptiveFlightLearning {
    public static final int FEEDBACK_TICKS = 20;
    private record Sample(long tick, Motion motion, int input, boolean proof, double fraction) {}

    private final ArrayDeque<Sample> history = new ArrayDeque<>();
    private Limits rejected = Limits.UNKNOWN;
    private double initialVertical = .75, configuredAcceleration = .05;
    private double horizontal = 64, ascent = .75, descent = .75, verticalAcceleration = .05;
    private double provenAscent, provenDescent;
    private long tick, lastObservedTick = Long.MIN_VALUE;
    private int ascentStreak, descentStreak, recoveryDelay = 60;
    private double recoveryRate = .0025;

    public void configure(double initialVerticalSpeed, double acceleration) {
        if (!Double.isFinite(initialVerticalSpeed) || initialVerticalSpeed <= 0 || initialVerticalSpeed > 64
            || !Double.isFinite(acceleration) || acceleration <= 0 || acceleration > 2)
            throw new IllegalArgumentException("Vertical learning settings must be finite and positive.");
        if (ascent == initialVertical && rejected.ascent() == 64) ascent = initialVerticalSpeed;
        if (descent == initialVertical && rejected.descent() == 64) descent = initialVerticalSpeed;
        if (verticalAcceleration == configuredAcceleration && rejected.verticalAcceleration() == 2)
            verticalAcceleration = acceleration;
        initialVertical = initialVerticalSpeed;
        configuredAcceleration = acceleration;
        verticalAcceleration = Math.min(verticalAcceleration, Math.min(acceleration, rejected.verticalAcceleration()));
    }

    /** Restoring a profile starts at its proven ceilings, rather than testing previously rejected speeds again. */
    public void restore(Limits limits) {
        restore(limits, limits);
    }

    /** Clean reconnect hints start exploration; rejected ceilings remain a separate hard boundary. */
    public void restore(Limits limits, Limits ceilings) {
        Objects.requireNonNull(limits, "limits");
        rejected = Objects.requireNonNull(ceilings, "ceilings");
        horizontal = Math.min(limits.horizontal(), ceilings.horizontal());
        ascent = Math.min(limits.ascent() == 64 ? initialVertical : limits.ascent(), ceilings.ascent());
        descent = Math.min(limits.descent() == 64 ? initialVertical : limits.descent(), ceilings.descent());
        verticalAcceleration = Math.min(configuredAcceleration,
            Math.min(limits.verticalAcceleration(), ceilings.verticalAcceleration()));
        provenAscent = limits.ascent() == 64 ? 0 : ascent;
        provenDescent = limits.descent() == 64 ? 0 : descent;
        resetHistory();
    }

    public Limits limits() {
        return new Limits(Math.min(horizontal, rejected.horizontal()), Math.min(ascent, rejected.ascent()),
            Math.min(descent, rejected.descent()), verticalAcceleration);
    }

    public Limits ceilings() { return rejected; }

    /** Only delayed clean evidence or a correction is saved, never a just-started speed probe. */
    public Limits remembered() {
        boolean verticalKnown = rejected.ascent() < 64 || rejected.descent() < 64
            || provenAscent > 0 || provenDescent > 0;
        return new Limits(Math.min(horizontal, rejected.horizontal()), Math.min(rejected.ascent(), provenAscent > 0 ? provenAscent : 64),
            Math.min(rejected.descent(), provenDescent > 0 ? provenDescent : 64),
            verticalKnown ? verticalAcceleration : rejected.verticalAcceleration());
    }

    public void tick(int delay, double rate) {
        if (delay < 0 || !Double.isFinite(rate) || rate < 0 || rate > 1)
            throw new IllegalArgumentException("Invalid learning recovery settings.");
        if (lastObservedTick != tick) ascentStreak = descentStreak = 0;
        tick++;
        recoveryDelay = Math.max(40, delay);
        recoveryRate = rate;
        while (!history.isEmpty() && tick - history.getFirst().tick() > FEEDBACK_TICKS) {
            Sample sample = history.removeFirst();
            if (sample.proof()) {
                double speed = Math.abs(sample.motion().y());
                if (sample.input() > 0) provenAscent = Math.max(provenAscent, Math.min(ascent, speed));
                else if (sample.input() < 0) provenDescent = Math.max(provenDescent, Math.min(descent, speed));
            }
        }
    }

    /** Call once per controlled tick, with the final terrain/glide-clipped output. */
    public void observe(Motion actual, int verticalInput, boolean sprintBoost, boolean clean, double fraction) {
        Objects.requireNonNull(actual, "actual");
        if (verticalInput < -1 || verticalInput > 1 || !Double.isFinite(fraction) || fraction < 0 || fraction > 1)
            throw new IllegalArgumentException("Invalid flight learning input.");
        if (lastObservedTick == tick) return;
        lastObservedTick = tick;
        boolean matching = verticalInput > 0 && actual.y() > 0 || verticalInput < 0 && actual.y() < 0;
        double ceiling = verticalInput > 0 ? ascent : descent;
        // A clean 25% request does not prove the untested 100% speed. Partial
        // settings remain useful setback evidence, but cannot authorize a faster probe.
        boolean proof = clean && sprintBoost && matching && fraction >= .999
            && Math.abs(actual.y()) >= .85 * ceiling * fraction;
        history.addLast(new Sample(tick, actual, verticalInput, proof, fraction));
        if (proof && verticalInput > 0) { ascentStreak++; descentStreak = 0; }
        else if (proof && verticalInput < 0) { descentStreak++; ascentStreak = 0; }
        else ascentStreak = descentStreak = 0;
        if (recoveryRate > 0 && ascentStreak >= recoveryDelay) {
            ascent = Math.min(rejected.ascent(), ascent + Math.min(.05, recoveryRate * 20));
            ascentStreak = 0;
        }
        if (recoveryRate > 0 && descentStreak >= recoveryDelay) {
            descent = Math.min(rejected.descent(), descent + Math.min(.05, recoveryRate * 20));
            descentStreak = 0;
        }
    }

    /** Recent intent survives key release, allowing delayed setbacks to penalize the right direction. */
    public boolean corrected(double multiplier) {
        if (!Double.isFinite(multiplier) || multiplier <= 0 || multiplier >= 1)
            throw new IllegalArgumentException("Correction multiplier must be in (0, 1).");
        double horizontal = 0, up = 0, down = 0;
        for (Sample sample : history) {
            horizontal = Math.max(horizontal, sample.motion().horizontalLength());
            if (sample.input() > 0 && sample.motion().y() > .01) up = Math.max(up, sample.motion().y());
            if (sample.input() < 0 && sample.motion().y() < -.01) down = Math.max(down, -sample.motion().y());
        }
        if (horizontal <= .01 && up == 0 && down == 0) { resetHistory(); return false; }
        double h = rejected.horizontal(), a = rejected.ascent(), d = rejected.descent();
        if (up > 0) a = Math.min(a, Math.max(.01, up * multiplier));
        if (down > 0) d = Math.min(d, Math.max(.01, down * multiplier));
        if (up == 0 && down == 0) h = Math.min(h, Math.max(.01, horizontal * multiplier));
        else verticalAcceleration = Math.min(verticalAcceleration, Math.max(.005, verticalAcceleration * multiplier));
        rejected = new Limits(h, a, d, up > 0 || down > 0 ? verticalAcceleration : rejected.verticalAcceleration());
        this.horizontal = Math.min(this.horizontal, h);
        ascent = Math.min(ascent, a);
        descent = Math.min(descent, d);
        // A previously proven faster sample is no longer an acceptable reconnect hint.
        provenAscent = Math.min(provenAscent, a);
        provenDescent = Math.min(provenDescent, d);
        resetHistory();
        return true;
    }

    public void resetHistory() {
        history.clear();
        ascentStreak = descentStreak = 0;
        lastObservedTick = Long.MIN_VALUE;
    }

    public void forget() { restore(Limits.UNKNOWN); }
}
