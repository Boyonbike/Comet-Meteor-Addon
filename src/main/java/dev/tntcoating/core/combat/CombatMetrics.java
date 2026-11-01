/*
 * Original Comet implementation by Boyonabike.
 * Independent: written from the pinned 26.2 artifact and Meteor build 35 behaviour; no third-party code.
 * SPDX-License-Identifier: CC0-1.0
 */

package dev.tntcoating.core.combat;

import java.util.Arrays;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;

/**
 * Phase 4.7 combat measurements, recorded from the first usable milestone so the Phase 3 comparison has
 * data. Every counter is kept separate and is never merged into a composite score.
 *
 * <p>The table names are stable identifiers: {@link #COUNTERS} lists the whole Phase 4.7 set so a test can
 * prove the set exists from the start. Counters that no Phase 2d path can observe yet (place/attack
 * outcomes, target damage, totem pops, unknown outcomes, retirements, defence actions and the three
 * service-objective latencies) exist and record normally, but simply stay at zero until Phase 3 wires
 * them.</p>
 *
 * <p>Latencies use a bounded exact window: the newest {@link #LATENCY_WINDOW} samples are kept and sorted
 * on query, so p50/p95/p99 are deterministic nearest-rank values, not reservoir estimates. Recording
 * never throws for a missing or invalid sample: negative, NaN or infinite values are ignored, and
 * {@code null} map keys are dropped. The class has no Minecraft dependency and is safe from the client
 * tick and its send path.</p>
 */
public final class CombatMetrics {
    /** Sum of the target-damage estimates accepted at send time. */
    public static final String ESTIMATED_TARGET_DAMAGE = "estimated_target_damage";
    /** Placement sends attempted. */
    public static final String PLACE_ATTEMPTS = "place_attempts";
    /** Crystal spawns observed after a placement send. */
    public static final String PLACE_OBSERVED = "place_observed";
    /** Verified crystal attack sends. */
    public static final String ATTACK_ATTEMPTS = "attack_attempts";
    /** Explosions observed for a crystal this module is bound to. */
    public static final String EXPLOSION_OBSERVED = "explosion_observed";
    /** Target health drops attributed to our explosions. */
    public static final String TARGET_DAMAGE_OBSERVED = "target_damage_observed";
    /** Target totem pops attributed to our explosions. */
    public static final String TOTEM_POPS_OBSERVED = "totem_pops_observed";
    /** Sends with no attributable outcome. */
    public static final String UNKNOWN_OUTCOME = "unknown_outcome";
    /** Outstanding risk debt over time; see {@link #recordRiskDebt(double)} and {@link #recordRetirement}. */
    public static final String RISK_DEBT = "risk_debt";
    /** Suspensions, one bucket per {@link SuspensionCause}. */
    public static final String SUSPENSIONS = "suspensions";
    /** Defence actions, one bucket per action type. */
    public static final String DEFENCE_ACTIONS = "defence_actions";
    /** Queue age from receipt to application, in milliseconds. */
    public static final String QUEUE_AGE_MS = "queue_age_ms";
    /** Application to dispatch eligibility, in milliseconds. */
    public static final String OBSERVE_TO_APPLY_MS = "observe_to_apply_ms";
    /** Applied evidence to send, in milliseconds. */
    public static final String APPLY_TO_SEND_MS = "apply_to_send_ms";
    /** All combat work in one client tick, in milliseconds. */
    public static final String COMBAT_TICK_MS = "combat_tick_ms";

    /** Phase 6.1: chosen mode per Hybrid tick, one bucket per {@link Mode} name. */
    public static final String CHOICE_MODES = "choice_modes";
    /** Phase 6.1: why the winner won, one bucket per reason ({@code lethal}, {@code utility}, {@code none}). */
    public static final String CHOICE_WINNERS = "choice_winners";
    /** Phase 6.1: rejected candidates, one bucket per {@link Choice.Reason} name. */
    public static final String CHOICE_REJECTIONS = "choice_rejections";
    /** Phase 6.2: ticks a better-scoring plan was held back by hysteresis. */
    public static final String HYSTERESIS_HOLDS = "hysteresis_holds";
    /** Phase 6.2: plan switches, one bucket per reason ({@code score}, {@code invalidated}). */
    public static final String HYSTERESIS_SWITCHES = "hysteresis_switches";
    /** Phase 6.4: direct plans filtered because a trap plan was committed (serialised Hybrid). */
    public static final String TRAP_BLOCKED_DIRECT = "trap_blocked_direct";
    /** Phase 6.5: defensive actions dispatched while a trap plan was committed. */
    public static final String DEFENCE_DURING_TRAP = "defence_during_trap";
    /** Phase 6.5: server ticks a cleanup waited for a free slot. */
    public static final String CLEANUP_WAITS = "cleanup_waits";
    /** Phase 6.5: the highest single cleanup wait observed, in server ticks. */
    public static final String CLEANUP_MAX_WAIT_TICKS = "cleanup_max_wait_ticks";
    /** Phase 6.5: ticks a cleanup was already past {@link CleanupPriority#STARVATION_TICKS} and waited. */
    public static final String CLEANUP_STARVED = "cleanup_starved";

    /** Phase 6.3: direct plans admitted by a positive footprint-conflict check, bucketed by row. */
    public static final String CONCURRENCY_ADMITTED = "concurrency_admitted";
    /** Phase 6.3: direct plans that had to wait, bucketed by the row that serialised them. */
    public static final String CONCURRENCY_SERIALISED = "concurrency_serialised";
    /** Phase 6.3: the effective footprint-concurrency setting, bucketed as on/off. */
    public static final String CONCURRENCY_SETTING = "concurrency_setting";
    /** Phase 6.3: the active runner mode, bucketed by mode name. */
    public static final String CONCURRENCY_MODES = "concurrency_modes";

    /** The complete Phase 4.7 counter set, in table order. */
    public static final List<String> COUNTERS = List.of(
        ESTIMATED_TARGET_DAMAGE,
        PLACE_ATTEMPTS, PLACE_OBSERVED,
        ATTACK_ATTEMPTS, EXPLOSION_OBSERVED,
        TARGET_DAMAGE_OBSERVED, TOTEM_POPS_OBSERVED, UNKNOWN_OUTCOME,
        RISK_DEBT, SUSPENSIONS, DEFENCE_ACTIONS,
        QUEUE_AGE_MS, OBSERVE_TO_APPLY_MS, APPLY_TO_SEND_MS, COMBAT_TICK_MS);

    /** The counters with a p50/p95/p99 service objective. */
    public static final List<String> LATENCY_COUNTERS = List.of(
        QUEUE_AGE_MS, OBSERVE_TO_APPLY_MS, APPLY_TO_SEND_MS, COMBAT_TICK_MS);

    /**
     * The Phase 6 hybrid counter set. Kept separate from {@link #COUNTERS} so the Phase 4.7 table stays
     * exactly as it shipped and the new counters still prove their presence from the start.
     */
    public static final List<String> HYBRID_COUNTERS = List.of(
        CHOICE_MODES, CHOICE_WINNERS, CHOICE_REJECTIONS, HYSTERESIS_HOLDS, HYSTERESIS_SWITCHES,
        TRAP_BLOCKED_DIRECT, DEFENCE_DURING_TRAP, CLEANUP_WAITS, CLEANUP_MAX_WAIT_TICKS,
        CLEANUP_STARVED);

    /** The Phase 6.3 concurrency counter set; the admitted/serialised maps are bucketed by row. */
    public static final List<String> CONCURRENCY_COUNTERS = List.of(
        CONCURRENCY_ADMITTED, CONCURRENCY_SERIALISED, CONCURRENCY_SETTING, CONCURRENCY_MODES);

    /** Newest samples retained per latency counter. Older samples are dropped, never resampled. */
    public static final int LATENCY_WINDOW = 512;

    /** Deterministic nearest-rank percentile triple. {@code samples == 0} means no value was recorded. */
    public record Percentiles(double p50, double p95, double p99, int samples) {
        public Percentiles {
            if (samples < 0) throw new IllegalArgumentException("samples must be non-negative");
        }

        public static Percentiles empty() {
            return new Percentiles(0.0, 0.0, 0.0, 0);
        }
    }

    private double estimatedTargetDamage;
    private long placeAttempts;
    private long placeObserved;
    private long attackAttempts;
    private long explosionObserved;
    private double targetDamageObserved;
    private long totemPopsObserved;
    private long unknownOutcome;
    private double riskDebtIntegral;
    private long riskDebtSamples;
    private long totalSuspensions;
    private long totalDefenceActions;
    private final EnumMap<SuspensionCause, Long> suspensions = new EnumMap<>(SuspensionCause.class);
    private final TreeMap<String, Long> retirements = new TreeMap<>();
    private final TreeMap<String, Long> defenceActions = new TreeMap<>();
    private final PercentileWindow queueAge = new PercentileWindow(LATENCY_WINDOW);
    private final PercentileWindow observeToApply = new PercentileWindow(LATENCY_WINDOW);
    private final PercentileWindow applyToSend = new PercentileWindow(LATENCY_WINDOW);
    private final PercentileWindow combatTick = new PercentileWindow(LATENCY_WINDOW);
    private final TreeMap<String, Long> choiceModes = new TreeMap<>();
    private final TreeMap<String, Long> choiceWinners = new TreeMap<>();
    private final TreeMap<String, Long> choiceRejections = new TreeMap<>();
    private final TreeMap<String, Long> hysteresisSwitches = new TreeMap<>();
    private long hysteresisHolds;
    private long trapBlockedDirect;
    private long defenceDuringTrap;
    private long cleanupWaitTicks;
    private double cleanupMaxWaitTicks;
    private long cleanupStarved;
    private final TreeMap<String, Long> concurrencyAdmitted = new TreeMap<>();
    private final TreeMap<String, Long> concurrencySerialised = new TreeMap<>();
    private final TreeMap<String, Long> concurrencySetting = new TreeMap<>();
    private final TreeMap<String, Long> concurrencyModes = new TreeMap<>();

    // ------------------------------------------------------------------ recording

    /** Adds one accepted estimate. Invalid values are dropped rather than corrupting the sum. */
    public synchronized void recordEstimatedTargetDamage(double damage) {
        if (!validSample(damage)) return;
        estimatedTargetDamage += damage;
    }

    public synchronized void recordPlaceAttempt() {
        placeAttempts++;
    }

    public synchronized void recordPlaceObserved() {
        placeObserved++;
    }

    public synchronized void recordAttackAttempt() {
        attackAttempts++;
    }

    public synchronized void recordExplosionObserved() {
        explosionObserved++;
    }

    /** Adds an observed target health drop. A crystal removal is never a damage observation. */
    public synchronized void recordTargetDamageObserved(double damage) {
        if (!validSample(damage)) return;
        targetDamageObserved += damage;
    }

    public synchronized void recordTotemPopObserved() {
        totemPopsObserved++;
    }

    /** A send whose outcome cannot be attributed to our bound crystal. */
    public synchronized void recordUnknownOutcome() {
        unknownOutcome++;
    }

    /**
     * Adds one outstanding-debt sample (call once per combat tick). The integral is the debt bound summed
     * across samples, in bound-ticks; {@link #riskDebtSamples()} is the denominator for a time average.
     */
    public synchronized void recordRiskDebt(double outstanding) {
        if (!validSample(outstanding)) return;
        riskDebtIntegral += outstanding;
        riskDebtSamples++;
    }

    /** One retirement, bucketed by the safety-model rule that retired it. Blank rules are ignored. */
    public synchronized void recordRetirement(String rule) {
        String key = normalize(rule);
        if (key == null) return;
        retirements.merge(key, 1L, Long::sum);
    }

    /** One suspension entered, bucketed by cause. Re-entering the same cause twice records twice. */
    public synchronized void recordSuspension(SuspensionCause cause) {
        if (cause == null) return;
        totalSuspensions++;
        suspensions.merge(cause, 1L, Long::sum);
    }

    /** One defence action, bucketed by type (for example {@code "break_crystal"}). */
    public synchronized void recordDefenceAction(String type) {
        String key = normalize(type);
        if (key == null) return;
        totalDefenceActions++;
        defenceActions.merge(key, 1L, Long::sum);
    }

    /** Records one {@link #QUEUE_AGE_MS} sample. Negative or non-finite values are ignored. */
    public synchronized void recordQueueAgeMs(double milliseconds) {
        queueAge.record(milliseconds);
    }

    /** Records one {@link #OBSERVE_TO_APPLY_MS} sample. Negative or non-finite values are ignored. */
    public synchronized void recordObserveToApplyMs(double milliseconds) {
        observeToApply.record(milliseconds);
    }

    /** Records one {@link #APPLY_TO_SEND_MS} sample. Negative or non-finite values are ignored. */
    public synchronized void recordApplyToSendMs(double milliseconds) {
        applyToSend.record(milliseconds);
    }

    /** Records the whole combat slice of one client tick, in milliseconds. */
    public synchronized void recordCombatTickMs(double milliseconds) {
        combatTick.record(milliseconds);
    }

    // ------------------------------------------------------------------ Phase 6 hybrid

    /** One Hybrid tick in the given mode; {@code null} is ignored rather than bucketed. */
    public synchronized void recordChoiceMode(String mode) {
        String key = normalize(mode);
        if (key == null) return;
        choiceModes.merge(key, 1L, Long::sum);
    }

    /** Why the chosen plan won: {@code lethal}, {@code utility} or {@code none}. */
    public synchronized void recordChoiceWinner(String reason) {
        String key = normalize(reason);
        if (key == null) return;
        choiceWinners.merge(key, 1L, Long::sum);
    }

    /** One rejected candidate, bucketed by {@link Choice.Reason} name (for example {@code COMMITTED}). */
    public synchronized void recordChoiceRejection(String reason) {
        String key = normalize(reason);
        if (key == null) return;
        choiceRejections.merge(key, 1L, Long::sum);
    }

    /** One tick a better-scoring plan was held back by hysteresis. */
    public synchronized void recordHysteresisHold() {
        hysteresisHolds++;
    }

    /** One plan switch, bucketed by why it happened ({@code score} or {@code invalidated}). */
    public synchronized void recordHysteresisSwitch(String reason) {
        String key = normalize(reason);
        if (key == null) return;
        hysteresisSwitches.merge(key, 1L, Long::sum);
    }

    /** One direct plan the committed trap blocked this tick. */
    public synchronized void recordTrapBlockedDirect() {
        trapBlockedDirect++;
    }

    /** One defensive action dispatched while a trap plan was committed. */
    public synchronized void recordDefenceDuringTrap() {
        defenceDuringTrap++;
    }

    /**
     * One server tick a cleanup waited for a slot. The wait is measured in server ticks, never in wall
     * clock: the maximum is kept so a starved cleanup is visible even when the counter is huge.
     */
    public synchronized void recordCleanupWait(int waitedTicks) {
        if (waitedTicks < 0) return;
        cleanupWaitTicks++;
        if (waitedTicks > cleanupMaxWaitTicks) cleanupMaxWaitTicks = waitedTicks;
    }

    /** One tick a cleanup was already past {@link CleanupPriority#STARVATION_TICKS} and still waited. */
    public synchronized void recordCleanupStarved() {
        cleanupStarved++;
    }

    /** One direct plan admitted by a positive conflict check, bucketed by the row that proved it. */
    public synchronized void recordConcurrencyAdmitted(String row) {
        String key = normalize(row);
        if (key == null) return;
        concurrencyAdmitted.merge(key, 1L, Long::sum);
    }

    /** One direct plan that waited, bucketed by the conflicting row (or {@code setting} when off). */
    public synchronized void recordConcurrencySerialised(String row) {
        String key = normalize(row);
        if (key == null) return;
        concurrencySerialised.merge(key, 1L, Long::sum);
    }

    /** Records the effective footprint-concurrency setting as {@code on} or {@code off}. */
    public synchronized void recordConcurrencySetting(boolean enabled) {
        concurrencySetting.merge(enabled ? "on" : "off", 1L, Long::sum);
    }

    /** Records the active runner mode for the concurrency evidence table. */
    public synchronized void recordConcurrencyMode(String mode) {
        String key = normalize(mode);
        if (key == null) return;
        concurrencyModes.merge(key, 1L, Long::sum);
    }

    /** Clears every counter and every latency window; used when a new combat session starts. */
    public synchronized void reset() {
        estimatedTargetDamage = 0.0;
        placeAttempts = 0;
        placeObserved = 0;
        attackAttempts = 0;
        explosionObserved = 0;
        targetDamageObserved = 0.0;
        totemPopsObserved = 0;
        unknownOutcome = 0;
        riskDebtIntegral = 0.0;
        riskDebtSamples = 0;
        totalSuspensions = 0;
        totalDefenceActions = 0;
        suspensions.clear();
        retirements.clear();
        defenceActions.clear();
        queueAge.clear();
        observeToApply.clear();
        applyToSend.clear();
        combatTick.clear();
        choiceModes.clear();
        choiceWinners.clear();
        choiceRejections.clear();
        hysteresisSwitches.clear();
        hysteresisHolds = 0;
        trapBlockedDirect = 0;
        defenceDuringTrap = 0;
        cleanupWaitTicks = 0;
        cleanupMaxWaitTicks = 0.0;
        cleanupStarved = 0;
        concurrencyAdmitted.clear();
        concurrencySerialised.clear();
        concurrencySetting.clear();
        concurrencyModes.clear();
    }

    // ------------------------------------------------------------------ queries

    public synchronized double estimatedTargetDamage() {
        return estimatedTargetDamage;
    }

    public synchronized long placeAttempts() {
        return placeAttempts;
    }

    public synchronized long placeObserved() {
        return placeObserved;
    }

    public synchronized long attackAttempts() {
        return attackAttempts;
    }

    public synchronized long explosionObserved() {
        return explosionObserved;
    }

    public synchronized double targetDamageObserved() {
        return targetDamageObserved;
    }

    public synchronized long totemPopsObserved() {
        return totemPopsObserved;
    }

    public synchronized long unknownOutcome() {
        return unknownOutcome;
    }

    public synchronized double riskDebtIntegral() {
        return riskDebtIntegral;
    }

    public synchronized long riskDebtSamples() {
        return riskDebtSamples;
    }

    /** Time average outstanding debt over the recorded samples, or zero when no sample was recorded. */
    public synchronized double meanRiskDebt() {
        return riskDebtSamples == 0 ? 0.0 : riskDebtIntegral / riskDebtSamples;
    }

    public synchronized long suspensions(SuspensionCause cause) {
        if (cause == null) return 0;
        return suspensions.getOrDefault(cause, 0L);
    }

    public synchronized long totalSuspensions() {
        return totalSuspensions;
    }

    /** Immutable copy of the per-cause suspension buckets, in enum order. */
    public synchronized Map<SuspensionCause, Long> suspensionsByCause() {
        EnumMap<SuspensionCause, Long> copy = new EnumMap<>(SuspensionCause.class);
        copy.putAll(suspensions);
        return Map.copyOf(copy);
    }

    public synchronized long retirements(String rule) {
        String key = normalize(rule);
        return key == null ? 0 : retirements.getOrDefault(key, 0L);
    }

    /** Immutable copy of the retirement buckets, sorted by rule name. */
    public synchronized Map<String, Long> retirementsByRule() {
        return Map.copyOf(retirements);
    }

    public synchronized long defenceActions(String type) {
        String key = normalize(type);
        return key == null ? 0 : defenceActions.getOrDefault(key, 0L);
    }

    public synchronized long totalDefenceActions() {
        return totalDefenceActions;
    }

    /** Immutable copy of the defence buckets, sorted by action type. */
    public synchronized Map<String, Long> defenceActionsByType() {
        return Map.copyOf(defenceActions);
    }

    // ------------------------------------------------------------------ Phase 6 hybrid queries

    public synchronized long choiceModes(String mode) {
        String key = normalize(mode);
        return key == null ? 0 : choiceModes.getOrDefault(key, 0L);
    }

    /** Immutable copy of the per-mode choice buckets, sorted by mode name. */
    public synchronized Map<String, Long> choiceModesByMode() {
        return Map.copyOf(choiceModes);
    }

    public synchronized long choiceWinner(String reason) {
        String key = normalize(reason);
        return key == null ? 0 : choiceWinners.getOrDefault(key, 0L);
    }

    /** Immutable copy of the winner-reason buckets, sorted by reason name. */
    public synchronized Map<String, Long> choiceWinnersByReason() {
        return Map.copyOf(choiceWinners);
    }

    public synchronized long choiceRejection(String reason) {
        String key = normalize(reason);
        return key == null ? 0 : choiceRejections.getOrDefault(key, 0L);
    }

    /** Immutable copy of the rejection buckets, one per {@link Choice.Reason} name. */
    public synchronized Map<String, Long> choiceRejectionsByReason() {
        return Map.copyOf(choiceRejections);
    }

    public synchronized long hysteresisHolds() {
        return hysteresisHolds;
    }

    public synchronized long hysteresisSwitch(String reason) {
        String key = normalize(reason);
        return key == null ? 0 : hysteresisSwitches.getOrDefault(key, 0L);
    }

    /** Immutable copy of the switch buckets, sorted by reason name. */
    public synchronized Map<String, Long> hysteresisSwitchesByReason() {
        return Map.copyOf(hysteresisSwitches);
    }

    public synchronized long trapBlockedDirect() {
        return trapBlockedDirect;
    }

    public synchronized long defenceDuringTrap() {
        return defenceDuringTrap;
    }

    public synchronized long cleanupWaitTicks() {
        return cleanupWaitTicks;
    }

    public synchronized double cleanupMaxWaitTicks() {
        return cleanupMaxWaitTicks;
    }

    public synchronized long cleanupStarvedTicks() {
        return cleanupStarved;
    }

    public synchronized long concurrencyAdmitted(String row) {
        String key = normalize(row);
        return key == null ? 0 : concurrencyAdmitted.getOrDefault(key, 0L);
    }

    /** Immutable copy of the admitted buckets, one per conflict row. */
    public synchronized Map<String, Long> concurrencyAdmittedByRow() {
        return Map.copyOf(concurrencyAdmitted);
    }

    public synchronized long concurrencySerialised(String row) {
        String key = normalize(row);
        return key == null ? 0 : concurrencySerialised.getOrDefault(key, 0L);
    }

    /** Immutable copy of the serialised buckets, one per conflict row. */
    public synchronized Map<String, Long> concurrencySerialisedByRow() {
        return Map.copyOf(concurrencySerialised);
    }

    public synchronized long concurrencySetting(boolean enabled) {
        return concurrencySetting.getOrDefault(enabled ? "on" : "off", 0L);
    }

    /** Immutable copy of the setting buckets ({@code on}/{@code off}). */
    public synchronized Map<String, Long> concurrencySettingByValue() {
        return Map.copyOf(concurrencySetting);
    }

    public synchronized long concurrencyMode(String mode) {
        String key = normalize(mode);
        return key == null ? 0 : concurrencyModes.getOrDefault(key, 0L);
    }

    /** Immutable copy of the active-mode buckets. */
    public synchronized Map<String, Long> concurrencyModesByMode() {
        return Map.copyOf(concurrencyModes);
    }

    /**
     * p50/p95/p99 for one service objective. Unknown names are a programming error and throw rather than
     * silently returning an empty snapshot.
     */
    public synchronized Percentiles percentiles(String counter) {
        Objects.requireNonNull(counter, "counter");
        return switch (counter) {
            case QUEUE_AGE_MS -> queueAge.percentiles();
            case OBSERVE_TO_APPLY_MS -> observeToApply.percentiles();
            case APPLY_TO_SEND_MS -> applyToSend.percentiles();
            case COMBAT_TICK_MS -> combatTick.percentiles();
            default -> throw new IllegalArgumentException("not a latency counter: " + counter);
        };
    }

    private static boolean validSample(double value) {
        return Double.isFinite(value) && value >= 0.0;
    }

    private static String normalize(String value) {
        if (value == null) return null;
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }

    /** Bounded exact window; sorting happens on query so recorded values are never perturbed. */
    private static final class PercentileWindow {
        private final double[] values;
        private int next;
        private long recorded;

        private PercentileWindow(int capacity) {
            if (capacity <= 0) throw new IllegalArgumentException("capacity must be positive");
            this.values = new double[capacity];
        }

        private void record(double value) {
            if (!validSample(value)) return;
            values[next] = value;
            next = (next + 1) % values.length;
            recorded++;
        }

        private void clear() {
            Arrays.fill(values, 0.0);
            next = 0;
            recorded = 0;
        }

        private Percentiles percentiles() {
            int size = (int) Math.min(recorded, values.length);
            if (size == 0) return Percentiles.empty();
            double[] window = new double[size];
            int start = recorded <= values.length ? 0 : next;
            for (int i = 0; i < size; i++) {
                window[i] = values[(start + i) % values.length];
            }
            Arrays.sort(window);
            return new Percentiles(nearestRank(window, 50.0), nearestRank(window, 95.0),
                nearestRank(window, 99.0), size);
        }

        private static double nearestRank(double[] sorted, double percentile) {
            int index = (int) Math.ceil(percentile / 100.0 * sorted.length) - 1;
            index = Math.max(0, Math.min(sorted.length - 1, index));
            return sorted[index];
        }
    }
}
