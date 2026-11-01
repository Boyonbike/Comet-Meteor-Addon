/*
 * Original Comet implementation by Boyonabike.
 * Independent: written from the pinned 26.2 artifact behaviour, the CrystalCombat v2.1 plan/audit and the
 * project's own Phase 0 traces; no third-party code. Geometry/order ideas are reimplemented ideas only.
 * SPDX-License-Identifier: CC0-1.0
 */

package dev.tntcoating.core.combat;

import dev.tntcoating.core.combat.PistonGeometry.PistonPush;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * Phase 5.2 pure piston trap planner. One active trap, one push, stationary holed targets only.
 *
 * <p>Worst-case, never best-case: for each feasible crystal position (the eight measured base layouts) the
 * damage is evaluated across the whole swept path against every feasible target position, the self position
 * and every nearby friend. Self/friend damage is bounded at the most exposed point with the weakest
 * mitigation envelope, and the trap is rejected unless target damage is enough. Push offsets come from
 * {@link PistonGeometry} (measured traces, {@code 1.02} blocks), never from the plan's literal {@code 1.04}
 * formula. Candidates are capped and the scan is resumable inside the caller's shared tick budget.</p>
 *
 * <p>The planner is pure: every world fact arrives through {@link World}. It reserves nothing and sends
 * nothing. The returned {@link Plan} lists the ordered intents; risk is reserved at the {@code PLACE_CRYSTAL}
 * intent, exactly as the sequence requires.</p>
 */
public final class PistonPlanner {
    /** Hard candidate cap per pass; the scan resumes next tick instead of overrunning the budget. */
    public static final int CANDIDATE_CAP = 64;
    /** A trap target must be slower than this (blocks/tick) for the whole settlement window. */
    public static final double STATIONARY_SPEED = 0.05;
    /** Consecutive ticks the target must have stayed slow before planning a trap. */
    public static final int STATIONARY_TICKS = 10;
    /** Intent lifetime, matching the direct planner's reserve-to-send window. */
    public static final int INTENT_LIFETIME_TICKS = 20;

    public enum Reject {
        NONE,
        NOT_STATIONARY,
        NOT_HOLED,
        NO_SOURCE_CRYSTAL,
        STRUCTURAL,
        POWERED_EARLY,
        WRONG_FACING,
        SELF_BOUNDS,
        FRIEND_BOUNDS,
        DAMAGE_TOO_LOW,
        MOTION_UNKNOWN,
        UNSUPPORTED,
        /** The tester layout filter disabled every combination; the PistonOnly mode stays inert. */
        LAYOUT_DISABLED
    }

    /**
     * One evaluated source-crystal position and its worst-case damage. {@code friendDamages} keeps the
     * per-friend bound the planner actually computed so a reservation can reproduce it without inventing
     * a tighter number; {@code friendDamage} remains the maximum for ranking/tests.
     */
    public record Candidate(EntityKey target, PistonPush push, List<Cell> structural,
                            double targetDamage, double selfDamage, double friendDamage,
                            Map<EntityKey, Double> friendDamages) {
        public Candidate(EntityKey target, PistonPush push, List<Cell> structural,
                         double targetDamage, double selfDamage, double friendDamage) {
            this(target, push, structural, targetDamage, selfDamage, friendDamage, Map.of());
        }

        public Candidate {
            Objects.requireNonNull(target, "target");
            Objects.requireNonNull(push, "push");
            structural = List.copyOf(structural);
            friendDamages = Map.copyOf(friendDamages);
        }
    }

    /**
     * The accepted trap. {@code intents} are in placement order; {@code crystalIntent} is the risk
     * reservation point. {@link #expectedPushed()} is the trace-derived final crystal position.
     */
    public record Plan(long planId, EntityKey target, PistonPush push, List<Intent> intents,
                       List<Cell> structural, double targetDamage, double selfDamage,
                       double friendDamage, Map<EntityKey, Double> friendDamages, Vec expectedPushed) {
        /** Source-compatible constructor: no risk bound was computed, so damage bounds are zero. */
        public Plan(long planId, EntityKey target, PistonPush push, List<Intent> intents,
                    List<Cell> structural, double targetDamage, Vec expectedPushed) {
            this(planId, target, push, intents, structural, targetDamage, 0.0, 0.0, Map.of(),
                expectedPushed);
        }

        public Plan {
            Objects.requireNonNull(target, "target");
            Objects.requireNonNull(push, "push");
            intents = List.copyOf(intents);
            structural = List.copyOf(structural);
            friendDamages = Map.copyOf(friendDamages);
            Objects.requireNonNull(expectedPushed, "expectedPushed");
        }

        /**
         * Worst-case exposure computed by the planner, ready for a {@code RiskRequest}. Nothing here is
         * rounded down: self/friend values are the planner's own upper bounds.
         */
        public ExposureBound exposure(MitigationEnvelope envelope) {
            Objects.requireNonNull(envelope, "envelope");
            Map<EntityKey, MitigatedDamage> friends = new java.util.LinkedHashMap<>();
            for (Map.Entry<EntityKey, Double> entry : friendDamages.entrySet()) {
                friends.put(entry.getKey(), new MitigatedDamage(entry.getValue()));
            }
            return new ExposureBound(new MitigatedDamage(selfDamage), friends, envelope);
        }

        /** The planner claims only its guaranteed lower bound; expected/optimistic never exceed it. */
        public TargetEstimate targetEstimate() {
            return new TargetEstimate(targetDamage, targetDamage, targetDamage);
        }

        public Cell source() {
            return push.source();
        }

        public Optional<Intent> crystalIntent() {
            return intents.stream().filter(intent -> intent.action() == Action.PLACE_CRYSTAL).findFirst();
        }

        public Optional<Intent> powerIntent() {
            return intents.stream().filter(intent -> intent.action() == Action.PLACE_POWER).findFirst();
        }

        public Optional<Intent> pistonIntent() {
            return intents.stream().filter(intent -> intent.action() == Action.PLACE_PISTON).findFirst();
        }

        /** Phase 5.1 stage contract, derived from the single measured push. */
        public List<PistonStage> pushes() {
            return List.of(new PistonStage(push.piston(), push.facing(), push.power(), push.powerKind(),
                push.swept()));
        }

        /** The planner reserves the swept volume, the structural cells and the bound target. */
        public Footprint footprint() {
            return new Footprint(Set.copyOf(structural), push.swept(), push.swept().expand(2.0),
                Set.of(target));
        }

        /**
         * Engine-facing contract. {@code opportunity} is empty here: a pure planner has no TargetEstimate
         * or ExposureBound inputs, and this phase does not fabricate one (engine wiring is deferred).
         */
        public PistonPlan pistonPlan() {
            return new PistonPlan(planId, target, push.source(), expectedPushed(), pushes(), intents,
                footprint(), Optional.empty(), targetDamage, selfDamage, friendDamage, friendDamages);
        }
    }

    /** Phase 5.1 stage contract: one push, with its power cell and swept path. */
    public record PistonStage(Cell piston, PistonFacing facing, Cell power, PistonGeometry.PowerKind kind,
                              Box swept) {
        public PistonStage {
            Objects.requireNonNull(piston, "piston");
            Objects.requireNonNull(facing, "facing");
            Objects.requireNonNull(power, "power");
            Objects.requireNonNull(kind, "kind");
            Objects.requireNonNull(swept, "swept");
        }
    }

    /**
     * Phase 5.1 plan contract. {@code opportunity} stays empty until a runtime host supplies a target
     * estimate; risk is reserved at the {@code PLACE_CRYSTAL} intent.
     */
    public record PistonPlan(long id, EntityKey target, Cell source, Vec expectedPushed,
                             List<PistonStage> pushes, List<Intent> dependencies, Footprint footprint,
                             Optional<Opportunity> opportunity, double targetDamage, double selfDamage,
                             double friendDamage, Map<EntityKey, Double> friendDamages) {
        public PistonPlan {
            Objects.requireNonNull(target, "target");
            Objects.requireNonNull(source, "source");
            Objects.requireNonNull(expectedPushed, "expectedPushed");
            pushes = List.copyOf(pushes);
            dependencies = List.copyOf(dependencies);
            Objects.requireNonNull(footprint, "footprint");
            Objects.requireNonNull(opportunity, "opportunity");
            friendDamages = Map.copyOf(friendDamages);
            if (pushes.size() != 1) throw new IllegalArgumentException("one push only");
        }

        /** Worst-case exposure carried from the pure planner, for the runtime risk request. */
        public ExposureBound exposure(MitigationEnvelope envelope) {
            Objects.requireNonNull(envelope, "envelope");
            Map<EntityKey, MitigatedDamage> friends = new java.util.LinkedHashMap<>();
            for (Map.Entry<EntityKey, Double> entry : friendDamages.entrySet()) {
                friends.put(entry.getKey(), new MitigatedDamage(entry.getValue()));
            }
            return new ExposureBound(new MitigatedDamage(selfDamage), friends, envelope);
        }

        public TargetEstimate targetEstimate() {
            return new TargetEstimate(targetDamage, targetDamage, targetDamage);
        }
    }

    /** Result of one bounded pass. {@code complete} means the scan finished (plan found or exhausted). */
    public record Result(Optional<Plan> plan, int evaluated, int rejected, boolean complete,
                         Reject lastReject) {
        public Result {
            Objects.requireNonNull(plan, "plan");
            Objects.requireNonNull(lastReject, "lastReject");
        }

        public boolean exhausted() {
            return complete && plan.isEmpty();
        }
    }

    /**
     * All world knowledge the planner needs. Damage is expected to be a worst-case estimate (weakest
     * mitigation envelope); the implementation never assumes the best outcome.
     */
    public interface World extends PistonGeometry.World {
        EntityKey self();

        Vec position(EntityKey entity);

        double speed(EntityKey entity);

        /** Consecutive server ticks the target moved slower than {@link #STATIONARY_SPEED}. */
        int stationaryTicks(EntityKey entity);

        /** Damage the crystal explosion at {@code crystalCentre} deals to a victim standing at the feet. */
        double crystalDamage(Vec crystalCentre, Vec victimFeet);

        List<EntityKey> nearbyFriends();

        boolean friendly(EntityKey entity);

        /** Weakest mitigation multiplier for the victim (1.0 = no mitigation); never below 0. */
        double mitigation(EntityKey entity);

        /** Production self-damage ceiling from the safety profile. */
        double selfDamageCeiling();

        /** Production friend-damage ceiling from the safety profile. */
        double friendDamageCeiling();

        /** Minimum target damage the caller accepts, normally the profile's lethal threshold. */
        double minimumTargetDamage();

        /** Plan id for this pass; deterministic in tests, monotonic in production. */
        long planId();

        int currentTick();

        /**
         * Full feasible next-tick position set (three-dimensional: vertical escape, knockback, step), or
         * {@code null} when motion is unknown. Unknown motion is rejected rather than sampled. The list path
         * stays authoritative for worlds without a predictor; {@link #feasibleHull(EntityKey)} adds the
         * continuous three-dimensional bound when one is available.
         */
        List<Vec> feasiblePositions(EntityKey entity);

        /**
         * Continuous reachable feet hull for the predicted ticks, or {@code null} when the world has no
         * motion predictor. When both this and {@link #feasiblePositions(EntityKey)} are null, motion is
         * unknown and the candidate is rejected.
         */
        default Box feasibleHull(EntityKey entity) {
            return null;
        }

        /**
         * Target-specific guaranteed lower bound for one crystal path point and victim feet. The default
         * delegates to {@link #crystalDamage} with the target's mitigation, preserving worlds that do not
         * price cover; production overrides this with the audited lower-bound exposure (cover included).
         */
        default double targetDamageLower(EntityKey target, Vec crystalCentre, Vec victimFeet) {
            return crystalDamage(crystalCentre, victimFeet)
                * Math.max(0.0, Math.min(1.0, mitigation(target)));
        }

        /**
         * Guaranteed lower bound over a whole victim set and, when present, a continuous motion hull. A
         * finite sample of a continuous hull is not a proof: an interior translated body box can be less
         * exposed than every sampled corner. Worlds that cannot prove a lower bound across the hull must
         * return zero here; only the discrete list path is a complete set and may be minimised.
         */
        default double targetDamageLower(EntityKey target, Vec crystalCentre, List<Vec> victims,
                                         Box hull) {
            if (hull != null || victims == null || victims.isEmpty()) return 0.0;
            double lowest = Double.MAX_VALUE;
            for (Vec victim : victims) {
                lowest = Math.min(lowest, targetDamageLower(target, crystalCentre, victim));
            }
            return lowest == Double.MAX_VALUE ? 0.0 : lowest;
        }

        /**
         * Upper bound for one entity's body box at a predicted feet position. The default prices the feet
         * point only; production overrides this with the translated body-box upper bound so head-height
         * exposure cannot be understated.
         */
        default double crystalDamageUpper(EntityKey entity, Vec crystalCentre, Vec victimFeet) {
            return crystalDamage(crystalCentre, victimFeet)
                * Math.max(0.0, Math.min(1.0, mitigation(entity)));
        }
    }

    private final int candidateCap;
    private boolean scanning;
    private boolean complete;
    private List<EntityKey> scanTargets = List.of();
    private int targetIndex;
    private int layoutIndex;
    private Candidate best;
    private int evaluated;
    private int rejected;
    private Reject lastReject = Reject.NONE;
    private long planId;
    private int scanTick;

    public PistonPlanner() {
        this(CANDIDATE_CAP);
    }

    /** Test seam: a smaller cap validates resumability without scanning all 64 candidates. */
    public PistonPlanner(int candidateCap) {
        if (candidateCap < 1) throw new IllegalArgumentException("candidateCap must be positive");
        this.candidateCap = candidateCap;
    }

    /** Beta tester filter: only enabled of the sixteen measured combinations are enumerated. */
    private PistonLayoutSelection selection = PistonLayoutSelection.all();

    /** Installs the tester layout filter; {@code null} means all layouts (the conservative default). */
    public void setSelection(PistonLayoutSelection selection) {
        this.selection = selection == null ? PistonLayoutSelection.all() : selection;
    }

    public PistonLayoutSelection selection() {
        return selection;
    }

    /** One bounded pass over eligible targets and their eight measured layouts. */
    public Result tick(World world, List<EntityKey> targets, int workBudget) {
        Objects.requireNonNull(world, "world");
        Objects.requireNonNull(targets, "targets");
        if (!scanning) resetScan(world, targets);
        if (complete) return result();
        if (selection.empty()) {
            // Disabling every beta layout leaves the mode inert: no plan, no fallback layout set.
            reset();
            return new Result(Optional.empty(), 0, 0, true, Reject.LAYOUT_DISABLED);
        }
        int remaining = Math.min(workBudget, candidateCap);
        if (remaining <= 0) return new Result(Optional.empty(), evaluated, rejected, false, lastReject);
        List<PistonGeometry.Layout> layouts = PistonGeometry.baseLayouts();
        int combos = layouts.size() * PistonGeometry.PowerKind.values().length;
        while (remaining > 0 && !complete) {
            remaining--;
            if (targetIndex >= scanTargets.size()) {
                complete = true;
                break;
            }
            EntityKey target = scanTargets.get(targetIndex);
            if (layoutIndex == 0) {
                Reject eligibility = eligible(world, target);
                if (eligibility != Reject.NONE) {
                    rejected++;
                    lastReject = eligibility;
                    targetIndex++;
                    continue;
                }
            }
            PistonGeometry.Layout layout = layouts.get(layoutIndex % layouts.size());
            PistonGeometry.PowerKind kind =
                PistonGeometry.PowerKind.values()[layoutIndex / layouts.size()];
            layoutIndex++;
            if (layoutIndex >= combos) {
                layoutIndex = 0;
                targetIndex++;
            }
            if (!selection.enabled(layout, kind)) continue;
            evaluated++;
            evaluate(world, target, layout, kind);
        }
        if (targetIndex >= scanTargets.size()) complete = true;
        return result();
    }

    /** Discards the current scan so a fresh target list is enumerated from the start. */
    public void reset() {
        scanning = false;
        complete = false;
        best = null;
        targetIndex = 0;
        layoutIndex = 0;
        evaluated = 0;
        rejected = 0;
        lastReject = Reject.NONE;
    }

    private void resetScan(World world, List<EntityKey> targets) {
        reset();
        scanning = true;
        scanTargets = List.copyOf(targets);
        planId = world.planId();
        scanTick = world.currentTick();
    }

    private Result result() {
        return new Result(Optional.ofNullable(best == null ? null : buildPlan(best)),
            evaluated, rejected, complete, lastReject);
    }

    // ------------------------------------------------------------------ eligibility

    private Reject eligible(World world, EntityKey target) {
        if (world.friendly(target)) return Reject.NOT_HOLED; // never trap a friend
        if (world.speed(target) >= STATIONARY_SPEED) return Reject.NOT_STATIONARY;
        if (world.stationaryTicks(target) < STATIONARY_TICKS) return Reject.NOT_STATIONARY;
        return holed(world, world.position(target)) ? Reject.NONE : Reject.NOT_HOLED;
    }

    /** Hole/full-surround: the feet cell cannot be left horizontally and there is a floor below. */
    public static boolean holed(PistonGeometry.World world, Vec position) {
        Objects.requireNonNull(world, "world");
        Objects.requireNonNull(position, "position");
        Cell feet = cellOf(position);
        if (!world.fullSupport(feet.below())) return false;
        if (world.blockId(feet) != null && !world.replaceable(feet)) return false;
        Cell[] sides = {
            new Cell(feet.x() + 1, feet.y(), feet.z()), new Cell(feet.x() - 1, feet.y(), feet.z()),
            new Cell(feet.x(), feet.y(), feet.z() + 1), new Cell(feet.x(), feet.y(), feet.z() - 1)
        };
        int blocked = 0;
        for (Cell side : sides) {
            if (!world.replaceable(side) && world.blockId(side) != null) blocked++;
        }
        // Three blocked sides plus the floor is a hole: the fourth face is the crystal slot, and a placed
        // end crystal blocks movement out of that face (vanilla crystals have blocksBuilding set).
        return blocked >= 3;
    }

    /** True when the head level is also enclosed (a full surround rather than a one-block pit). */
    public static boolean fullySurrounded(PistonGeometry.World world, Vec position) {
        Cell feet = cellOf(position);
        Cell[] sides = {
            new Cell(feet.x() + 1, feet.y() + 1, feet.z()),
            new Cell(feet.x() - 1, feet.y() + 1, feet.z()),
            new Cell(feet.x(), feet.y() + 1, feet.z() + 1),
            new Cell(feet.x(), feet.y() + 1, feet.z() - 1)
        };
        for (Cell side : sides) {
            if (world.replaceable(side) || world.blockId(side) == null) return false;
        }
        return !world.replaceable(feet.above());
    }

    // ------------------------------------------------------------------ evaluation

    private void evaluate(World world, EntityKey target, PistonGeometry.Layout layout,
                          PistonGeometry.PowerKind kind) {
        Cell anchor = cellOf(world.position(target));
        // The crystal is pushed INTO the target cell: the source is one cell behind it. For the eye-height
        // layout the final cell is the target's head cell and the source sits at head height too.
        Cell destination = layout.vertical() == 0 ? anchor : anchor.above();
        Cell source = PistonGeometry.offset(destination, layout.facing(), -1);
        PistonPush push = PistonGeometry.fromSource(source, layout.facing(), layout.vertical(), kind);
        PistonGeometry.Check check = PistonGeometry.checkPlacement(world, push);
        if (!check.ok()) {
            rejected++;
            noteReject(map(check.status()));
            return;
        }
        List<Vec> targetFeasible = world.feasiblePositions(target);
        List<Vec> selfFeasible = world.feasiblePositions(world.self());
        Box targetHull = world.feasibleHull(target);
        Box selfHull = world.feasibleHull(world.self());
        if ((targetFeasible == null && targetHull == null)
            || (selfFeasible == null && selfHull == null)) {
            // Unknown motion is never sampled down to the current point.
            rejected++;
            noteReject(Reject.MOTION_UNKNOWN);
            return;
        }
        double targetDamage = guaranteedTargetDamage(world, target, push, targetFeasible, targetHull);
        if (targetDamage < world.minimumTargetDamage()) {
            rejected++;
            noteReject(Reject.DAMAGE_TOO_LOW);
            return;
        }
        double selfDamage = exposedDamage(world, world.self(), push, selfFeasible, selfHull);
        double friendDamage = 0.0;
        Map<EntityKey, Double> friendDamages = new java.util.LinkedHashMap<>();
        for (EntityKey friend : world.nearbyFriends()) {
            if (friend.equals(target) || !world.friendly(friend)) continue;
            List<Vec> friendFeasible = world.feasiblePositions(friend);
            Box friendHull = world.feasibleHull(friend);
            if (friendFeasible == null && friendHull == null) {
                rejected++;
                noteReject(Reject.MOTION_UNKNOWN);
                return;
            }
            double bound = exposedDamage(world, friend, push, friendFeasible, friendHull);
            friendDamages.put(friend, bound);
            friendDamage = Math.max(friendDamage, bound);
        }
        if (selfDamage > world.selfDamageCeiling()) {
            rejected++;
            noteReject(Reject.SELF_BOUNDS);
            return;
        }
        if (friendDamage > world.friendDamageCeiling()) {
            rejected++;
            noteReject(Reject.FRIEND_BOUNDS);
            return;
        }
        Candidate candidate = new Candidate(target, push, check.structural(), targetDamage, selfDamage,
            friendDamage, friendDamages);
        if (best == null || compare(candidate, best) > 0) best = candidate;
    }

    /**
     * Guaranteed lower bound of target damage: the minimum over the whole feasible target set (current,
     * pushed, midpoint and the destination hull) and the whole swept crystal path, scaled by the target's
     * mitigation envelope. A trap is only proposed when even the best-case target still takes lethal damage.
     */
    private static double guaranteedTargetDamage(World world, EntityKey target, PistonPush push,
                                                  List<Vec> feasible, Box hull) {
        Vec actual = world.position(target);
        Vec destination = new Vec(push.destination().x() + 0.5, push.destination().y(),
            push.destination().z() + 0.5);
        Vec midpoint = new Vec((actual.x() + destination.x()) / 2.0,
            (actual.y() + destination.y()) / 2.0, (actual.z() + destination.z()) / 2.0);
        List<Vec> victims = new ArrayList<>();
        if (feasible != null) victims.addAll(feasible);
        victims.add(actual);
        victims.add(destination);
        victims.add(midpoint);
        Box lowerHull = hull;
        if (hull != null) {
            // A zero-volume hull is one exact position. A tight hull is exact enough only when the
            // target is genuinely stationary; then the current, pushed and midpoint positions are the
            // complete victim set and the discrete minimum is a real guarantee, not a corner sample.
            boolean stationary = world.stationaryTicks(target) >= STATIONARY_TICKS
                && world.speed(target) < STATIONARY_SPEED;
            if (degenerate(hull)) {
                lowerHull = null;
            } else if (stationary && tight(hull)) {
                lowerHull = null;
                victims = new ArrayList<>(3);
                victims.add(actual);
                victims.add(destination);
                victims.add(midpoint);
            }
        }
        double lowest = Double.MAX_VALUE;
        for (Vec frame : push.positions()) {
            // Loose continuous hulls return zero: sampled corners cannot prove an interior lower bound.
            lowest = Math.min(lowest,
                world.targetDamageLower(target, frame, victims, lowerHull));
        }
        if (lowest == Double.MAX_VALUE) lowest = 0.0;
        return Math.max(0.0, lowest);
    }

    /**
     * Upper bound at the most exposed feasible point, scaled by the weakest mitigation envelope. The list
     * path is combined with the continuous hull: for every crystal tick the hull point closest to that
     * frame is the highest-damage point, and the world prices the entity's whole body box there so a
     * vertically extended hull or head-height exposure cannot be understated.
     */
    private static double exposedDamage(World world, EntityKey entity, PistonPush push,
                                         List<Vec> feasible, Box hull) {
        double worst = 0.0;
        for (Vec frame : push.positions()) {
            if (feasible != null) {
                for (Vec point : feasible) {
                    worst = Math.max(worst, world.crystalDamageUpper(entity, frame, point));
                }
            }
            if (hull != null) {
                worst = Math.max(worst,
                    world.crystalDamageUpper(entity, frame, closest(hull, frame)));
            }
        }
        return worst;
    }

    private static boolean tight(Box hull) {
        double tolerance = PistonGeometry.POSITION_TOLERANCE;
        return hull.max().x() - hull.min().x() <= tolerance
            && hull.max().y() - hull.min().y() <= tolerance
            && hull.max().z() - hull.min().z() <= tolerance;
    }

    private static boolean degenerate(Box hull) {
        return hull.max().x() - hull.min().x() <= 1.0e-6
            && hull.max().y() - hull.min().y() <= 1.0e-6
            && hull.max().z() - hull.min().z() <= 1.0e-6;
    }

    private static Vec closest(Box hull, Vec point) {
        double x = Math.max(hull.min().x(), Math.min(hull.max().x(), point.x()));
        double y = Math.max(hull.min().y(), Math.min(hull.max().y(), point.y()));
        double z = Math.max(hull.min().z(), Math.min(hull.max().z(), point.z()));
        return new Vec(x, y, z);
    }

    private static int compare(Candidate left, Candidate right) {
        int damage = Double.compare(left.targetDamage(), right.targetDamage());
        if (damage != 0) return damage;
        int structural = Integer.compare(right.structural().size(), left.structural().size());
        if (structural != 0) return structural;
        return Integer.compare(left.push().layout().vertical(), right.push().layout().vertical());
    }

    /** Structure-only rejections never mask a policy rejection from a viable candidate. */
    private void noteReject(Reject reject) {
        if (lastReject == Reject.NONE || lastReject == Reject.STRUCTURAL || lastReject == Reject.UNSUPPORTED) {
            lastReject = reject;
        }
    }

    private static Reject map(PistonGeometry.CheckStatus status) {
        return switch (status) {
            case OK -> Reject.NONE;
            case UNSUPPORTED_LAYOUT -> Reject.UNSUPPORTED;
            case SOURCE_CRYSTAL_MISSING, NO_PLACEMENT_FACE -> Reject.NO_SOURCE_CRYSTAL;
            case SUPPORT_TOO_DEEP, STRUCTURAL_BUDGET, OCCUPIED, PATH_BLOCKED, NO_CRYSTAL_BASE ->
                Reject.STRUCTURAL;
            case POWER_PRESENT_EARLY, PISTON_POWERED_EARLY -> Reject.POWERED_EARLY;
            case POWER_AFFECTS_COMPONENT -> Reject.STRUCTURAL;
            case WRONG_FACING -> Reject.WRONG_FACING;
        };
    }

    // ------------------------------------------------------------------ plan construction

    private Plan buildPlan(Candidate candidate) {
        PistonPush push = candidate.push();
        List<Intent> intents = new ArrayList<>();
        List<Intent> dependencies = new ArrayList<>();
        long nextId = planId * 16L;
        int expires = scanTick + INTENT_LIFETIME_TICKS;
        for (Cell support : candidate.structural()) {
            if (support.equals(push.piston()) || support.equals(push.power())) continue;
            Intent intent = new Intent(++nextId, planId, Strategy.PISTON, Action.PLACE_SUPPORT, support,
                null, Set.of(support), Set.of(), Set.of(), expires);
            intents.add(intent);
            dependencies.add(intent);
        }
        Set<Cell> pistonLocks = new LinkedHashSet<>();
        pistonLocks.add(push.piston());
        Intent piston = new Intent(++nextId, planId, Strategy.PISTON, Action.PLACE_PISTON,
            push.piston(), null, pistonLocks, Set.of(), Set.copyOf(dependencies), expires);
        intents.add(piston);
        Intent crystal = new Intent(++nextId, planId, Strategy.PISTON, Action.PLACE_CRYSTAL,
            push.source(), null, Set.of(push.source()), Set.of(), Set.of(piston), expires);
        intents.add(crystal);
        intents.add(new Intent(++nextId, planId, Strategy.PISTON, Action.PLACE_POWER, push.power(),
            null, Set.of(push.power()), Set.of(), Set.of(crystal), expires));
        return new Plan(planId, candidate.target(), push, intents, candidate.structural(),
            candidate.targetDamage(), candidate.selfDamage(), candidate.friendDamage(),
            candidate.friendDamages(), push.expectedPushed());
    }

    // ------------------------------------------------------------------ helpers

    public static Cell cellOf(Vec position) {
        return new Cell((int) Math.floor(position.x()), (int) Math.floor(position.y()),
            (int) Math.floor(position.z()));
    }

    /** The expected pushed position for a source and facing. */
    public static Vec expectedPushed(Cell source, PistonFacing facing) {
        Vec centre = source.center();
        return centre.add(facing.dx() * PistonGeometry.MEASURED_DISPLACEMENT,
            facing.dy() * PistonGeometry.MEASURED_DISPLACEMENT,
            facing.dz() * PistonGeometry.MEASURED_DISPLACEMENT);
    }
}
