/*
 * Original Comet implementation by Boyonabike.
 * Independent: written from the pinned 26.2 artifact and Meteor build 35 behaviour; no third-party code.
 * SPDX-License-Identifier: CC0-1.0
 */

package dev.tntcoating.core.combat;

/**
 * DirectOnly acceptance thresholds and pause switches. Pure data: every value is a bound a planner or the
 * engine checks, never a probability. Ranges mirror the plan's defaults; {@link Safety} stays the single
 * authority for damage limits. Phase 4.2 motion prediction is opt-in: {@code motionPrediction} gates the
 * planner's {@link CombatMotionModel} use and {@code motionPredictionTicks} is the target horizon
 * ({@link #MOTION_TICKS_CONSERVATIVE} 2 ticks, {@link #MOTION_TICKS_ANARCHY} 3 ticks); self and friend
 * prediction always uses one tick. Phase 4.5 {@code surroundGap} only adds a placement preference inside
 * a target hole: it never relaxes a damage/reach/visibility bound and never mines. Phase 4.6 adds two
 * independently gated defensive tactics, both off by default: {@code dangerousCrystalBreak} breaks an
 * observed crystal whose detonation would breach the self margin, and {@code antiPiston} breaks a piston's
 * front crystal or places an obsidian {@code PLACE_BLOCKER} in the piston push path. Phase 6.3
 * {@code footprintConcurrency} is off by default in every construction path: the serialised Hybrid
 * behaviour is the shipped one, and the footprint-scoped path only runs when this explicit switch is on.
 */
public record DirectSettings(
    double targetRange,
    double placeRange,
    double attackRange,
    double wallsRange,
    Safety safety,
    boolean ownOnly,
    int minAgeTicks,
    int maxAttempts,
    boolean faceplaceEnabled,
    double faceplaceHealth,
    double faceplaceArmourFraction,
    boolean twoAirProfile,
    boolean supportEnabled,
    int switchDelayTicks,
    boolean rotationsOn,
    boolean pauseOnEating,
    boolean pauseOnDrinking,
    boolean pauseOnMining,
    boolean pauseOnScreen,
    boolean pauseOnBaritone,
    double healthFloor,
    boolean pauseOnLag,
    double minTps,
    long staleFeedbackNanos,
    boolean motionPrediction,
    int motionPredictionTicks,
    boolean hurtSync,
    boolean surroundGap,
    boolean dangerousCrystalBreak,
    boolean antiPiston,
    boolean footprintConcurrency
) {
    public static final double DEFAULT_TARGET_RANGE = 6.0;
    public static final double DEFAULT_PLACE_RANGE = 4.5;
    public static final double DEFAULT_ATTACK_RANGE = 3.0;
    public static final double DEFAULT_WALLS_RANGE = 3.0;
    public static final int DEFAULT_MAX_ATTEMPTS = 2;
    public static final double DEFAULT_FACEPLACE_HEALTH = 8.0;
    public static final double DEFAULT_FACEPLACE_ARMOUR_FRACTION = 0.10;
    public static final int DEFAULT_SWITCH_DELAY_TICKS = 0;
    public static final double DEFAULT_HEALTH_FLOOR = 6.0;
    public static final double DEFAULT_MIN_TPS = 10.0;
    public static final long DEFAULT_STALE_FEEDBACK_NANOS = 1_000_000_000L;
    /** Phase 4.2 target prediction horizon for the Conservative profile. */
    public static final int MOTION_TICKS_CONSERVATIVE = 2;
    /** Phase 4.2 target prediction horizon for the Anarchy profile (not promoted in Phase 4.2). */
    public static final int MOTION_TICKS_ANARCHY = 3;

    /**
     * Phase 3-compatible constructor: {@code motionPrediction} off with the Conservative horizon, so every
     * existing call site keeps the exact Phase 3 behaviour.
     */
    public DirectSettings(
        double targetRange,
        double placeRange,
        double attackRange,
        double wallsRange,
        Safety safety,
        boolean ownOnly,
        int minAgeTicks,
        int maxAttempts,
        boolean faceplaceEnabled,
        double faceplaceHealth,
        double faceplaceArmourFraction,
        boolean twoAirProfile,
        boolean supportEnabled,
        int switchDelayTicks,
        boolean rotationsOn,
        boolean pauseOnEating,
        boolean pauseOnDrinking,
        boolean pauseOnMining,
        boolean pauseOnScreen,
        boolean pauseOnBaritone,
        double healthFloor,
        boolean pauseOnLag,
        double minTps,
        long staleFeedbackNanos
    ) {
        this(targetRange, placeRange, attackRange, wallsRange, safety, ownOnly, minAgeTicks,
            maxAttempts, faceplaceEnabled, faceplaceHealth, faceplaceArmourFraction, twoAirProfile,
            supportEnabled, switchDelayTicks, rotationsOn, pauseOnEating, pauseOnDrinking, pauseOnMining,
            pauseOnScreen, pauseOnBaritone, healthFloor, pauseOnLag, minTps, staleFeedbackNanos,
            false, MOTION_TICKS_CONSERVATIVE, false, false, false, false, false);
    }

    /**
     * Phase 4.2-compatible constructor: hurt-sync off, so every existing call site keeps the exact
     * Phase 3/4.2 behaviour.
     */
    public DirectSettings(
        double targetRange,
        double placeRange,
        double attackRange,
        double wallsRange,
        Safety safety,
        boolean ownOnly,
        int minAgeTicks,
        int maxAttempts,
        boolean faceplaceEnabled,
        double faceplaceHealth,
        double faceplaceArmourFraction,
        boolean twoAirProfile,
        boolean supportEnabled,
        int switchDelayTicks,
        boolean rotationsOn,
        boolean pauseOnEating,
        boolean pauseOnDrinking,
        boolean pauseOnMining,
        boolean pauseOnScreen,
        boolean pauseOnBaritone,
        double healthFloor,
        boolean pauseOnLag,
        double minTps,
        long staleFeedbackNanos,
        boolean motionPrediction,
        int motionPredictionTicks
    ) {
        this(targetRange, placeRange, attackRange, wallsRange, safety, ownOnly, minAgeTicks,
            maxAttempts, faceplaceEnabled, faceplaceHealth, faceplaceArmourFraction, twoAirProfile,
            supportEnabled, switchDelayTicks, rotationsOn, pauseOnEating, pauseOnDrinking, pauseOnMining,
            pauseOnScreen, pauseOnBaritone, healthFloor, pauseOnLag, minTps, staleFeedbackNanos,
            motionPrediction, motionPredictionTicks, false, false, false, false, false);
    }

    /** Phase 4.5-compatible constructor: both Phase 4.6 defensive tactics off. */
    public DirectSettings(
        double targetRange,
        double placeRange,
        double attackRange,
        double wallsRange,
        Safety safety,
        boolean ownOnly,
        int minAgeTicks,
        int maxAttempts,
        boolean faceplaceEnabled,
        double faceplaceHealth,
        double faceplaceArmourFraction,
        boolean twoAirProfile,
        boolean supportEnabled,
        int switchDelayTicks,
        boolean rotationsOn,
        boolean pauseOnEating,
        boolean pauseOnDrinking,
        boolean pauseOnMining,
        boolean pauseOnScreen,
        boolean pauseOnBaritone,
        double healthFloor,
        boolean pauseOnLag,
        double minTps,
        long staleFeedbackNanos,
        boolean motionPrediction,
        int motionPredictionTicks,
        boolean hurtSync,
        boolean surroundGap
    ) {
        this(targetRange, placeRange, attackRange, wallsRange, safety, ownOnly, minAgeTicks,
            maxAttempts, faceplaceEnabled, faceplaceHealth, faceplaceArmourFraction, twoAirProfile,
            supportEnabled, switchDelayTicks, rotationsOn, pauseOnEating, pauseOnDrinking, pauseOnMining,
            pauseOnScreen, pauseOnBaritone, healthFloor, pauseOnLag, minTps, staleFeedbackNanos,
            motionPrediction, motionPredictionTicks, hurtSync, surroundGap, false, false, false);
    }

    public DirectSettings {
        requireFinitePositive(targetRange, "targetRange");
        requireFinitePositive(placeRange, "placeRange");
        requireFinitePositive(attackRange, "attackRange");
        requireFinitePositive(wallsRange, "wallsRange");
        if (safety == null) throw new IllegalArgumentException("safety");
        if (minAgeTicks < 0) throw new IllegalArgumentException("minAgeTicks");
        if (maxAttempts < 1) throw new IllegalArgumentException("maxAttempts");
        if (!Double.isFinite(faceplaceHealth) || faceplaceHealth < 0.0) {
            throw new IllegalArgumentException("faceplaceHealth");
        }
        if (!Double.isFinite(faceplaceArmourFraction)
            || faceplaceArmourFraction < 0.0 || faceplaceArmourFraction > 1.0) {
            throw new IllegalArgumentException("faceplaceArmourFraction");
        }
        if (switchDelayTicks < 0) throw new IllegalArgumentException("switchDelayTicks");
        if (!Double.isFinite(healthFloor) || healthFloor < 0.0) {
            throw new IllegalArgumentException("healthFloor");
        }
        if (!Double.isFinite(minTps) || minTps <= 0.0) throw new IllegalArgumentException("minTps");
        if (staleFeedbackNanos < 1L) throw new IllegalArgumentException("staleFeedbackNanos");
        if (motionPredictionTicks < 1 || motionPredictionTicks > CombatMotionModel.MAX_TICKS) {
            throw new IllegalArgumentException("motionPredictionTicks");
        }
    }

    /** Conservative defaults for the plan's DirectOnly baseline. */
    public static DirectSettings conservative(Safety safety) {
        return new DirectSettings(DEFAULT_TARGET_RANGE, DEFAULT_PLACE_RANGE, DEFAULT_ATTACK_RANGE,
            DEFAULT_WALLS_RANGE, safety, false, 0, DEFAULT_MAX_ATTEMPTS, true,
            DEFAULT_FACEPLACE_HEALTH, DEFAULT_FACEPLACE_ARMOUR_FRACTION, false, false,
            DEFAULT_SWITCH_DELAY_TICKS, true, true, true, true, true, true,
            DEFAULT_HEALTH_FLOOR, true, DEFAULT_MIN_TPS, DEFAULT_STALE_FEEDBACK_NANOS,
            false, MOTION_TICKS_CONSERVATIVE, false, false, false, false, false);
    }

    /** Copy with the Phase 4.2 motion tactic toggled, preserving every other policy value. */
    public DirectSettings withMotion(boolean enabled, int ticks) {
        return new DirectSettings(targetRange, placeRange, attackRange, wallsRange, safety, ownOnly,
            minAgeTicks, maxAttempts, faceplaceEnabled, faceplaceHealth, faceplaceArmourFraction,
            twoAirProfile, supportEnabled, switchDelayTicks, rotationsOn, pauseOnEating, pauseOnDrinking,
            pauseOnMining, pauseOnScreen, pauseOnBaritone, healthFloor, pauseOnLag, minTps,
            staleFeedbackNanos, enabled, ticks, hurtSync, surroundGap, dangerousCrystalBreak,
            antiPiston, footprintConcurrency);
    }

    /** Copy with the Phase 4.3 hurt-sync tactic toggled, preserving every other policy value. */
    public DirectSettings withHurtSync(boolean enabled) {
        return new DirectSettings(targetRange, placeRange, attackRange, wallsRange, safety, ownOnly,
            minAgeTicks, maxAttempts, faceplaceEnabled, faceplaceHealth, faceplaceArmourFraction,
            twoAirProfile, supportEnabled, switchDelayTicks, rotationsOn, pauseOnEating, pauseOnDrinking,
            pauseOnMining, pauseOnScreen, pauseOnBaritone, healthFloor, pauseOnLag, minTps,
            staleFeedbackNanos, motionPrediction, motionPredictionTicks, enabled, surroundGap,
            dangerousCrystalBreak, antiPiston, footprintConcurrency);
    }

    /** Copy with the Phase 4.5 surround-gap tactic toggled, preserving every other policy value. */
    public DirectSettings withSurroundGap(boolean enabled) {
        return new DirectSettings(targetRange, placeRange, attackRange, wallsRange, safety, ownOnly,
            minAgeTicks, maxAttempts, faceplaceEnabled, faceplaceHealth, faceplaceArmourFraction,
            twoAirProfile, supportEnabled, switchDelayTicks, rotationsOn, pauseOnEating, pauseOnDrinking,
            pauseOnMining, pauseOnScreen, pauseOnBaritone, healthFloor, pauseOnLag, minTps,
            staleFeedbackNanos, motionPrediction, motionPredictionTicks, hurtSync, enabled,
            dangerousCrystalBreak, antiPiston, footprintConcurrency);
    }

    /** Copy with the Phase 4.6 dangerous-crystal break tactic toggled, preserving every other value. */
    public DirectSettings withDangerousCrystalBreak(boolean enabled) {
        return new DirectSettings(targetRange, placeRange, attackRange, wallsRange, safety, ownOnly,
            minAgeTicks, maxAttempts, faceplaceEnabled, faceplaceHealth, faceplaceArmourFraction,
            twoAirProfile, supportEnabled, switchDelayTicks, rotationsOn, pauseOnEating, pauseOnDrinking,
            pauseOnMining, pauseOnScreen, pauseOnBaritone, healthFloor, pauseOnLag, minTps,
            staleFeedbackNanos, motionPrediction, motionPredictionTicks, hurtSync, surroundGap, enabled,
            antiPiston, footprintConcurrency);
    }

    /** Copy with the Phase 4.6 anti-piston blocker tactic toggled, preserving every other value. */
    public DirectSettings withAntiPiston(boolean enabled) {
        return new DirectSettings(targetRange, placeRange, attackRange, wallsRange, safety, ownOnly,
            minAgeTicks, maxAttempts, faceplaceEnabled, faceplaceHealth, faceplaceArmourFraction,
            twoAirProfile, supportEnabled, switchDelayTicks, rotationsOn, pauseOnEating, pauseOnDrinking,
            pauseOnMining, pauseOnScreen, pauseOnBaritone, healthFloor, pauseOnLag, minTps,
            staleFeedbackNanos, motionPrediction, motionPredictionTicks, hurtSync, surroundGap,
            dangerousCrystalBreak, enabled, footprintConcurrency);
    }

    /**
     * Copy with Phase 6.3 footprint-scoped concurrency toggled. Off keeps the serialised Hybrid behaviour:
     * during a committed trap every direct plan waits. On admits a direct plan only through a positive
     * {@link FootprintConflict} result; the shipped default stays off.
     */
    public DirectSettings withFootprintConcurrency(boolean enabled) {
        return new DirectSettings(targetRange, placeRange, attackRange, wallsRange, safety, ownOnly,
            minAgeTicks, maxAttempts, faceplaceEnabled, faceplaceHealth, faceplaceArmourFraction,
            twoAirProfile, supportEnabled, switchDelayTicks, rotationsOn, pauseOnEating, pauseOnDrinking,
            pauseOnMining, pauseOnScreen, pauseOnBaritone, healthFloor, pauseOnLag, minTps,
            staleFeedbackNanos, motionPrediction, motionPredictionTicks, hurtSync, surroundGap,
            dangerousCrystalBreak, antiPiston, enabled);
    }

    private static void requireFinitePositive(double value, String name) {
        if (!Double.isFinite(value) || value <= 0.0) throw new IllegalArgumentException(name);
    }
}
