package dev.tntcoating.core;

import java.util.Objects;

/**
 * Tick-based flight momentum and correction recovery, independent of Minecraft.
 * Distances and acceleration are measured in blocks per client tick.
 */
public final class AdaptiveFlightController {
    public static final double MIN_SPEED_FACTOR = 0.1;
    /** Effective heading assistance never exceeds a right angle per tick. */
    public static final double MAX_TURN_RATE = 90;

    public enum Status { READY, RECOVERING, PAUSED, TERRAIN_LIMITED }

    public record Motion(double x, double y, double z) {
        public static final Motion ZERO = new Motion(0, 0, 0);

        public Motion {
            if (!Double.isFinite(x) || !Double.isFinite(y) || !Double.isFinite(z)
                || !Double.isFinite(Math.hypot(Math.hypot(x, y), z))) {
                throw new IllegalArgumentException("Motion must have finite components and length.");
            }
        }

        public double length() {
            return Math.hypot(Math.hypot(x, y), z);
        }

        public double horizontalLength() {
            return Math.hypot(x, z);
        }

        public Motion scaled(double factor) {
            return factor == 0 ? ZERO : new Motion(x * factor, y * factor, z * factor);
        }
    }

    /**
     * {@code braking} is the gentlest braking. With a finite {@code brakingHorizon} and a {@code brakingTop} speed,
     * braking rises with speed so that a stop from that top speed fits the horizon; see {@link Braking}.
     */
    public record Settings(double acceleration, double braking, double correctionMultiplier,
                           int correctionPauseTicks, int recoveryDelayTicks, double recoveryPerTick,
                           double turnRate, double brakingHorizon, double brakingTop) {
        /** A zero turn rate retains the original momentum-based steering. Rates are degrees per tick. */
        public Settings(double acceleration, double braking, double correctionMultiplier,
                        int correctionPauseTicks, int recoveryDelayTicks, double recoveryPerTick) {
            this(acceleration, braking, correctionMultiplier, correctionPauseTicks,
                recoveryDelayTicks, recoveryPerTick, 0);
        }

        /** Constant braking, as before speed-scaled braking existed. */
        public Settings(double acceleration, double braking, double correctionMultiplier,
                        int correctionPauseTicks, int recoveryDelayTicks, double recoveryPerTick, double turnRate) {
            this(acceleration, braking, correctionMultiplier, correctionPauseTicks,
                recoveryDelayTicks, recoveryPerTick, turnRate, Double.POSITIVE_INFINITY, 0);
        }

        public Settings {
            if (!Double.isFinite(acceleration) || acceleration <= 0) {
                throw new IllegalArgumentException("Acceleration must be finite and positive.");
            }
            if (!Double.isFinite(braking) || braking <= 0) {
                throw new IllegalArgumentException("Braking must be finite and positive.");
            }
            if (!Double.isFinite(correctionMultiplier)
                || correctionMultiplier <= 0 || correctionMultiplier > 1) {
                throw new IllegalArgumentException("Correction multiplier must be in (0, 1].");
            }
            if (correctionPauseTicks < 0 || recoveryDelayTicks < 0) {
                throw new IllegalArgumentException("Recovery and pause ticks cannot be negative.");
            }
            if (!Double.isFinite(recoveryPerTick) || recoveryPerTick < 0 || recoveryPerTick > 1) {
                throw new IllegalArgumentException("Recovery per tick must be in [0, 1].");
            }
            if (!Double.isFinite(turnRate) || turnRate < 0 || turnRate > 90) {
                throw new IllegalArgumentException("Turn rate must be in [0, 90] degrees per tick.");
            }
            if (Double.isNaN(brakingHorizon) || brakingHorizon < 0) {
                throw new IllegalArgumentException("Braking horizon cannot be negative.");
            }
            if (!Double.isFinite(brakingTop) || brakingTop < 0) {
                throw new IllegalArgumentException("Braking top speed must be finite and nonnegative.");
            }
        }

        /** The unified braking policy these settings describe. */
        public Braking brakingModel() {
            return new Braking(braking, brakingHorizon, brakingTop);
        }

        /** The speed braking sheds in one tick from {@code speed} when no obstacle demands more. */
        public double brakingAt(double speed) {
            return brakingModel().capacity(speed);
        }
    }

    private Settings settings;
    private Motion output = Motion.ZERO;
    private double speedFactor = 1;
    private int correctionCount;
    private int ticksSinceCorrection = Integer.MAX_VALUE;
    private int futurePauseTicks;
    private boolean pausedThisTick;
    private boolean pauseStarted;
    private boolean terrainLimited;
    private boolean boosting;
    private boolean transientRecovery;

    public AdaptiveFlightController(Settings settings) {
        configure(settings);
    }

    /** Changes tuning without discarding momentum or an active correction backoff. */
    public void configure(Settings settings) {
        this.settings = Objects.requireNonNull(settings, "settings");
    }

    public void reset() {
        output = Motion.ZERO;
        speedFactor = 1;
        correctionCount = 0;
        ticksSinceCorrection = Integer.MAX_VALUE;
        futurePauseTicks = 0;
        pausedThisTick = false;
        pauseStarted = false;
        terrainLimited = false;
        boosting = false;
        transientRecovery = false;
    }

    /**
     * Starts a client tick. A correction halts the rest of its current tick and
     * the next configured number of complete ticks. Recovery never advances
     * more than once per tick, regardless of how many movement events occur.
     */
    public void tick() {
        boosting = false;
        pausedThisTick = futurePauseTicks > 0;
        if (pausedThisTick) futurePauseTicks--;
        pauseStarted = true;
        terrainLimited = false;
        if (ticksSinceCorrection < Integer.MAX_VALUE) ticksSinceCorrection++;
        int recoveryDelay = transientRecovery ? Math.min(settings.recoveryDelayTicks(), 20) : settings.recoveryDelayTicks();
        double recoveryRate = transientRecovery ? Math.min(1, settings.recoveryPerTick() * 4) : settings.recoveryPerTick();
        if (!pausedThisTick && correctionCount > 0 && ticksSinceCorrection > recoveryDelay) {
            speedFactor = Math.min(1, speedFactor + recoveryRate);
            if (speedFactor == 1) transientRecovery = false;
        }
    }

    /** Records one or more server corrections without overflowing counters. */
    public void corrected(int count) {
        corrected(count, false);
    }

    /** A stall's correction burst shares one temporary backoff and recovers promptly after feedback settles. */
    public void corrected(int count, boolean transientFeedback) {
        if (count < 0) throw new IllegalArgumentException("Correction count cannot be negative.");
        if (count == 0) return;
        correctionCount = (int) Math.min(Integer.MAX_VALUE, (long) correctionCount + count);
        if (transientFeedback) {
            if (!transientRecovery)
                speedFactor = Math.max(MIN_SPEED_FACTOR, speedFactor * settings.correctionMultiplier());
            transientRecovery = speedFactor < 1;
        } else {
            transientRecovery = false;
            speedFactor = Math.max(MIN_SPEED_FACTOR,
                speedFactor * Math.pow(settings.correctionMultiplier(), count));
        }
        ticksSinceCorrection = 0;
        futurePauseTicks = settings.correctionPauseTicks();
        pausedThisTick = futurePauseTicks > 0;
        pauseStarted = false;
        output = Motion.ZERO;
        terrainLimited = false;
        boosting = false;
    }

    /** Counts corrections for diagnostics only: no backoff, pause or momentum loss. Unlimited boost owns its own recovery. */
    public void recordCorrection(int count) {
        if (count < 0) throw new IllegalArgumentException("Correction count cannot be negative.");
        correctionCount = (int) Math.min(Integer.MAX_VALUE, (long) correctionCount + count);
    }

    /**
     * Approaches the complete requested velocity vector, including vertical
     * movement. Releasing input, reducing speed, and reversing use the braking
     * rate. The terrain displacement limit always takes effect immediately.
     * Positive infinity means there is no terrain limit.
     */
    public Motion step(Motion requested, double safeDistance) {
        return step(requested, safeDistance, false);
    }

    /** Sprint changes the activity status; its requested velocity uses the same acceleration and braking. */
    public Motion step(Motion requested, double safeDistance, boolean sprintBoost) {
        return step(requested, safeDistance, sprintBoost, Double.POSITIVE_INFINITY);
    }

    /**
     * {@code turnReference} is the adaptive engine's attainable speed. Heading assistance rises with
     * actual speed to twice the configured rate at that speed; positive infinity keeps the configured rate.
     */
    public Motion step(Motion requested, double safeDistance, boolean sprintBoost, double turnReference) {
        return step(requested, safeDistance, sprintBoost, turnReference, false);
    }

    /**
     * {@code rampedHorizontal} is unlimited boost: an external speed ramp is the acceleration limit, so whenever
     * this step is not braking, horizontal speed takes the requested magnitude on the assisted heading at once.
     */
    public Motion step(Motion requested, double safeDistance, boolean sprintBoost, double turnReference,
                       boolean rampedHorizontal) {
        Objects.requireNonNull(requested, "requested");
        if (Double.isNaN(safeDistance) || safeDistance < 0) {
            throw new IllegalArgumentException("Safe distance must be nonnegative or positive infinity.");
        }
        if (Double.isNaN(turnReference) || turnReference < 0) {
            throw new IllegalArgumentException("Turn reference must be nonnegative or positive infinity.");
        }
        terrainLimited = false;
        boosting = false;
        if (pausedThisTick) {
            output = Motion.ZERO;
            return output;
        }

        // A ramped (unlimited boost) horizontal target is outside the correction ceiling; its ramp has its own recovery.
        Motion target = rampedHorizontal ? new Motion(requested.x(), requested.y() * speedFactor, requested.z())
            : requested.scaled(speedFactor);
        boosting = sprintBoost && target.length() > 0;
        boolean braking = target.length() < output.length() || oppositeDirection(output, target);
        output = steer(output, target, braking ? settings.brakingAt(output.length()) : settings.acceleration(),
            settings.turnRate(), settings.acceleration(), turnReference, rampedHorizontal);
        double targetHorizontal = target.horizontalLength();
        double horizontal = output.horizontalLength();
        if (rampedHorizontal && !braking && targetHorizontal > 0 && horizontal > 0) {
            double scale = targetHorizontal / horizontal;
            output = new Motion(output.x() * scale, output.y(), output.z() * scale);
        } else if (rampedHorizontal && !braking && targetHorizontal > 0) {
            output = new Motion(target.x(), output.y(), target.z());
        }
        double length = output.length();
        if (length > safeDistance) {
            output = output.scaled(safeDistance / length);
            terrainLimited = true;
        }
        return output;
    }

    /** Remembers the actual motion after an external swept collision/chunk guard. */
    public void accept(Motion actual) {
        Objects.requireNonNull(actual, "actual");
        if (pausedThisTick) {
            output = Motion.ZERO;
            boosting = false;
            return;
        }
        if (actual.length() < output.length()) terrainLimited = true;
        output = actual;
    }

    public Settings settings() { return settings; }
    public Motion output() { return output; }
    public double speedFactor() { return speedFactor; }
    public int correctionCount() { return correctionCount; }
    public int ticksSinceCorrection() { return ticksSinceCorrection; }
    public boolean isBoosting() { return boosting; }

    /** Includes the current tick while it is paused. */
    public int pauseTicksRemaining() {
        return futurePauseTicks + (pausedThisTick && pauseStarted ? 1 : 0);
    }

    public Status status() {
        if (pausedThisTick) return Status.PAUSED;
        if (terrainLimited) return Status.TERRAIN_LIMITED;
        return speedFactor < 1 ? Status.RECOVERING : Status.READY;
    }

    private static boolean oppositeDirection(Motion current, Motion target) {
        double currentScale = maxComponent(current);
        double targetScale = maxComponent(target);
        if (currentScale == 0 || targetScale == 0) return false;
        return (current.x() / currentScale) * (target.x() / targetScale)
            + (current.y() / currentScale) * (target.y() / targetScale)
            + (current.z() / currentScale) * (target.z() / targetScale) < 0;
    }

    private static Motion steer(Motion current, Motion target, double maxDelta, double turnRate,
                                double steeringAcceleration, double turnReference, boolean beyondReference) {
        double horizontal = current.horizontalLength();
        double targetHorizontal = target.horizontalLength();
        Motion ordinary = approach(current, target, maxDelta);
        if (turnRate == 0 || horizontal == 0 || targetHorizontal == 0) return ordinary;

        // Compare actual momentum with ordinary steering authority. Crawling
        // keeps the original precise vector control; faster flight gradually
        // gains heading control instead of taking dozens of ticks to shed drift.
        double start = Math.max(.3, 2 * steeringAcceleration);
        if (horizontal <= start) return ordinary;
        double full = Math.max(2 * start, 6 * steeringAcceleration);
        double blend = Math.min(1, (horizontal - start) / (full - start));
        blend = blend * blend * (3 - 2 * blend);

        double heading = Math.atan2(current.z(), current.x());
        double targetHeading = Math.atan2(target.z(), target.x());
        double difference = Math.IEEEremainder(targetHeading - heading, 2 * Math.PI);
        // A deliberate reversal must still shed momentum before accelerating
        // backwards. Ordinary corners can redirect speed without spending the
        // acceleration budget on several blocks of sideways velocity.
        if (Math.abs(difference) >= Math.PI * .75) return ordinary;
        // A constant rate would let sideways drift grow with speed, so faster
        // flight turns harder: twice the configured rate at the engine maximum.
        // Beyond it, unlimited boost keeps growing the rate with speed, so the
        // blocks of sideways drift per corner stay what they are at that maximum.
        double ratio = turnReference > 0 ? horizontal / turnReference : 1;
        double rate = Math.min(MAX_TURN_RATE,
            turnRate * (beyondReference && ratio > 1 ? 2 * ratio : 1 + Math.min(1, ratio)));
        double angle = heading + Math.copySign(
            Math.min(Math.abs(difference), Math.toRadians(rate)), difference);
        double x = Math.cos(angle);
        double z = Math.sin(angle);
        Motion steered = new Motion(x * horizontal, current.y(), z * horizontal);
        // Keep the speed/vertical target on the steered heading. Approaching the
        // original vector here would add another turn and lose speed in corners.
        Motion assisted = approach(steered,
            new Motion(x * targetHorizontal, target.y(), z * targetHorizontal), maxDelta);
        if (blend == 1) return assisted;
        return new Motion(ordinary.x() * (1 - blend) + assisted.x() * blend,
            ordinary.y() * (1 - blend) + assisted.y() * blend,
            ordinary.z() * (1 - blend) + assisted.z() * blend);
    }

    private static Motion approach(Motion current, Motion target, double maxDelta) {
        // Work in scaled coordinates so even a reversal between large finite
        // velocities cannot overflow when calculating the difference vector.
        double scale = Math.max(maxComponent(current), maxComponent(target));
        if (scale == 0) return Motion.ZERO;
        double dx = target.x() / scale - current.x() / scale;
        double dy = target.y() / scale - current.y() / scale;
        double dz = target.z() / scale - current.z() / scale;
        double distance = Math.hypot(Math.hypot(dx, dy), dz);
        if (distance == 0 || distance <= maxDelta / scale) return target;
        double fraction = (maxDelta / scale) / distance;
        double remaining = 1 - fraction;
        return new Motion(
            scale * (current.x() / scale * remaining + target.x() / scale * fraction),
            scale * (current.y() / scale * remaining + target.y() / scale * fraction),
            scale * (current.z() / scale * remaining + target.z() / scale * fraction)
        );
    }

    private static double maxComponent(Motion motion) {
        return Math.max(Math.abs(motion.x()), Math.max(Math.abs(motion.y()), Math.abs(motion.z())));
    }
}
