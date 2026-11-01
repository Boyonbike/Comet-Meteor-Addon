/*
 * Original Comet implementation by Boyonabike.
 * Independent: written from the pinned 26.2 artifact and Meteor build 35 behaviour; no third-party code.
 * SPDX-License-Identifier: CC0-1.0
 */

package dev.tntcoating.core.combat;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;

/**
 * Phase 4.2 motion prediction, in one pure place. The planner asks for a prediction only when the tactic
 * setting is on; every method here falls back to the exact Phase 3 baseline when motion is unknown, the
 * model reports anything other than {@link Confidence#STRONG}, or the horizon is not positive.
 *
 * <p>Semantics, all deliberately one-sided:
 *
 * <ul>
 *   <li>Target: the reachable-position set (predicted hull corners plus the current position) is priced at
 *       the expected arrival. {@code expected} averages that set and {@code optimistic} takes the maximum,
 *       so ranking can move with the evidence. {@code guaranteedLower} is {@code 0.0}: the model exposes a
 *       hull, not an exhaustive reachable set, and an interior reachable point can be less exposed than
 *       every sampled corner, so no sampled minimum is a proven lower bound. Zero is the only honest
 *       guarantee, which also means a lethal override can never become eligible because prediction fired.</li>
 *   <li>Self and friends: the upper bound (one tick, including vertical movement) covers the whole hull.
 *       The worst point is the hull point closest to the crystal, because with cover ignored the raw blast
 *       falls monotonically with distance; the production reader prices the true closest point of the
 *       translated body box through the weakest mitigation envelope. The result is
 *       {@code max(baseline, worst)}, so it can only rise.</li>
 *   <li>Low confidence never changes a bound: the baseline is returned untouched. {@link #confidence}
 *       reports a ranking multiplier (STRONG 1.0, WEAK 0.5) that can only lower opportunity scores.</li>
 * </ul>
 */
final class MotionPrediction {
    /** Self and friend prediction horizon in ticks, independent of the target horizon. */
    static final int SELF_FRIEND_TICKS = 1;
    /** Ranking multiplier when the model's own confidence is not usable. */
    static final double BASELINE_CONFIDENCE = 1.0;
    /** Ranking multiplier for a weak but non-usable prediction; never used on a safety bound. */
    static final double WEAK_CONFIDENCE = 0.5;
    private static final CombatMotionModel MODEL = new CombatMotionModel();

    private MotionPrediction() {
    }

    /**
     * Motion-aware target estimate for one crystal centre. Returns {@code baseline} unchanged when the
     * tactic cannot be trusted; otherwise the guaranteed lower is zero because the hull cannot prove the
     * least-exposed point, and only the expected ranking value moves.
     */
    static TargetEstimate targetEstimate(DirectWorld world, DirectTarget target, Vec crystalCentre,
                                         Collection<Cell> overlays, TargetEstimate baseline, int ticks) {
        List<Vec> positions = reachablePositions(world, target.entity(), ticks);
        if (positions.isEmpty()) return baseline;

        double expected = 0.0;
        double optimistic = baseline.optimistic();
        for (Vec position : positions) {
            TargetEstimate estimate = world.damage().targetEstimate(shifted(target, position),
                crystalCentre, overlays);
            expected += estimate.expected();
            optimistic = Math.max(optimistic, estimate.optimistic());
        }
        expected = expected / positions.size();
        optimistic = Math.max(optimistic, expected);
        return new TargetEstimate(0.0, expected, optimistic);
    }

    /**
     * Opportunity-confidence multiplier for ranking only. {@code 1.0} when motion is unknown, lost or too
     * imprecise to replace a bound; {@code 0.5} when the model reports {@link Confidence#WEAK}. Never used
     * to price self or friend safety.
     */
    static double confidence(DirectWorld world, EntityKey entity, int ticks) {
        if (ticks <= 0 || entity == null) return BASELINE_CONFIDENCE;
        MotionSample sample = world.motion(entity);
        if (sample == null) return BASELINE_CONFIDENCE;
        Confidence confidence = MODEL.predict(sample, ticks, world.collision()).confidence();
        if (confidence == Confidence.STRONG) return BASELINE_CONFIDENCE;
        if (confidence == Confidence.WEAK) return WEAK_CONFIDENCE;
        return BASELINE_CONFIDENCE;
    }

    /**
     * Motion-aware self/friend upper bounds over each predicted hull. Each bound is
     * {@code max(baseline, worst over the hull)}, so prediction can only raise it; when no usable
     * prediction exists the baseline exposure is returned unchanged.
     */
    static ExposureBound exposure(DirectWorld world, Vec crystalCentre, Collection<Cell> overlays,
                                  ExposureBound baseline) {
        MitigatedDamage self = baseline.selfUpper();
        Prediction selfPrediction = prediction(world, world.safetyState().self(), SELF_FRIEND_TICKS);
        if (selfPrediction != null) {
            MitigatedDamage worst = world.damage().selfUpperWorst(
                closestToCrystal(selfPrediction.box().hull(), crystalCentre), crystalCentre, overlays);
            self = worseOf(self, worst);
        }

        Map<EntityKey, MitigatedDamage> friends = new LinkedHashMap<>();
        for (Map.Entry<EntityKey, MitigatedDamage> entry : baseline.friendUpper().entrySet()) {
            EntityKey friend = entry.getKey();
            MitigatedDamage upper = entry.getValue();
            Prediction prediction = prediction(world, friend, SELF_FRIEND_TICKS);
            if (prediction != null) {
                MitigatedDamage worst = world.damage().friendUpperWorst(friend,
                    closestToCrystal(prediction.box().hull(), crystalCentre), crystalCentre, overlays);
                upper = worseOf(upper, worst);
            }
            friends.put(friend, upper);
        }
        return new ExposureBound(self, friends, baseline.envelope());
    }

    private static MitigatedDamage worseOf(MitigatedDamage baseline, MitigatedDamage candidate) {
        if (candidate == null) return baseline;
        return candidate.beforeAbsorption() > baseline.beforeAbsorption() ? candidate : baseline;
    }

    /**
     * The hull point closest to the crystal. With cover ignored the raw blast falls monotonically with
     * distance, so this is the most damaging feet position any point in the hull can occupy.
     */
    private static Vec closestToCrystal(Box hull, Vec crystalCentre) {
        return new Vec(clamp(crystalCentre.x(), hull.min().x(), hull.max().x()),
            clamp(crystalCentre.y(), hull.min().y(), hull.max().y()),
            clamp(crystalCentre.z(), hull.min().z(), hull.max().z()));
    }

    private static double clamp(double value, double min, double max) {
        return Math.max(min, Math.min(value, max));
    }

    private record Prediction(MotionSample sample, PredictedBox box) {
    }

    private static Prediction prediction(DirectWorld world, EntityKey entity, int ticks) {
        if (ticks <= 0 || entity == null) return null;
        MotionSample sample = world.motion(entity);
        if (sample == null) return null;
        PredictedBox box = MODEL.predict(sample, ticks, world.collision());
        return box.confidence() == Confidence.STRONG ? new Prediction(sample, box) : null;
    }

    /**
     * Feet positions the entity can occupy after {@code ticks} server ticks. Empty when the tactic must not
     * be used: motion unknown, horizon not positive, or the model not {@link Confidence#STRONG}.
     */
    private static List<Vec> reachablePositions(DirectWorld world, EntityKey entity, int ticks) {
        Prediction prediction = prediction(world, entity, ticks);
        if (prediction == null) return List.of();

        Box hull = prediction.box().hull();
        LinkedHashSet<Vec> positions = new LinkedHashSet<>();
        positions.add(prediction.sample().position());
        double[] xs = {hull.min().x(), hull.max().x()};
        double[] ys = {hull.min().y(), hull.max().y()};
        double[] zs = {hull.min().z(), hull.max().z()};
        for (double x : xs) {
            for (double y : ys) {
                for (double z : zs) {
                    positions.add(new Vec(x, y, z));
                }
            }
        }
        return new ArrayList<>(positions);
    }

    /** Same target, feet moved to {@code feet}; the box translates with the feet. */
    private static DirectTarget shifted(DirectTarget target, Vec feet) {
        if (feet.equals(target.position())) return target;
        Box box = target.box();
        double dx = feet.x() - target.position().x();
        double dy = feet.y() - target.position().y();
        double dz = feet.z() - target.position().z();
        Box moved = new Box(box.min().add(dx, dy, dz), box.max().add(dx, dy, dz));
        return new DirectTarget(target.entity(), feet, moved, target.health(), target.mitigation(),
            target.dead(), target.creative(), target.spectator(), target.friend(),
            target.armourPressure());
    }
}
