/*
 * Original Comet implementation by Boyonabike.
 * Independent: written from the pinned 26.2 artifact and Meteor build 35 behaviour; no third-party code.
 * SPDX-License-Identifier: CC0-1.0
 */

package dev.tntcoating.core.combat;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/**
 * Phase 3.1-3.3 DirectOnly planner. Pure: it reads one {@link DirectWorld} snapshot and returns at most one
 * verified action for the current target; it never sends, reserves, or mutates the ledger.
 *
 * <p>Target selection is sticky while the current target still offers an opportunity within 20% of the
 * best alternative, then deterministic by expected damage, distance and entity id (at most three targets
 * are considered per pass). Break opportunities require a ledger {@code OBSERVED} crystal that passes
 * range/walls, age, attempt and hazard checks, and the damage policy including self/friend upper bounds.
 * Placement opportunities apply the vanilla placement rule (first cell above an obsidian/bedrock base strict
 * air, a free 1x2x1 entity volume, no crystal present until removal is observed), an optional stricter
 * two-air profile, reachable/visible face and the same damage policy; faceplace only relaxes the minimum
 * target damage when the target is under the health cap or under armour pressure. At most one support block
 * is proposed per pass and only when no direct base candidate passes. Phase 4.5 adds an opt-in gap
 * preference: when {@link DirectSettings#surroundGap()} is on and the target sits in a one-column hole, the
 * normal placement path is tried first restricted to a base whose crystal cell fills that hole's open
 * column; every damage/reach/visibility guard still runs unchanged and there is no gap support recursion.</p>
 */
public final class DirectPlanner {
    /** Sticky target hysteresis: the current target must be within 20% of the best alternative. */
    public static final double TARGET_STICKY_FRACTION = 0.8;
    /** Phase 3.1: evaluate at most three targets per pass; the rest are skipped, never queued. */
    public static final int MAX_TARGETS_PER_PASS = 3;
    /** Intent lifetime in server ticks. */
    public static final int INTENT_LIFETIME_TICKS = 20;
    /** Entity volume above a crystal base: one block wide/deep, two blocks tall. */
    public static final double CRYSTAL_BOX_RADIUS = 1.0;

    public static final String END_CRYSTAL = "minecraft:end_crystal";
    public static final String OBSIDIAN = "minecraft:obsidian";

    private static final double EPSILON = 1.0e-9;
    private static final Comparator<TargetChoice> TARGET_ORDER = Comparator
        .comparingDouble(TargetChoice::score).reversed()
        .thenComparingDouble(TargetChoice::distance)
        .thenComparingInt(choice -> choice.entity().id())
        .thenComparing(choice -> choice.entity().uuid() == null ? "" : choice.entity().uuid().toString());

    private final DirectSettings settings;

    public DirectPlanner(DirectSettings settings) {
        this.settings = Objects.requireNonNull(settings, "settings");
    }

    public DirectSettings settings() {
        return settings;
    }

    /**
     * Runs one planning pass and returns the chosen action, if any. The returned plan is fully priced and
     * policy-checked for the chosen target; the caller still has to reserve and dispatch it.
     */
    public Optional<DirectPlan> plan(DirectWorld world, DirectPlanState state) {
        if (world == null || state == null) return Optional.empty();
        SafetyState safetyState = world.safetyState();
        if (safetyState == null || !Objects.equals(world.connection(), safetyState.conn())) {
            return Optional.empty();
        }
        List<TargetChoice> ranked = rank(world, state);
        if (ranked.isEmpty()) return Optional.empty();
        int limit = Math.min(MAX_TARGETS_PER_PASS, ranked.size());
        List<TargetChoice> shortlist = ranked.subList(0, limit);
        TargetChoice chosen = sticky(shortlist, state.preferredTarget());
        return chosen == null ? Optional.empty() : Optional.of(chosen.plan());
    }

    /**
     * Deterministic shortlist for tests and diagnostics: entity ids in score/distance/id order, at most
     * {@link #MAX_TARGETS_PER_PASS}. Targets without an acceptable opportunity are omitted.
     */
    public List<EntityKey> selectTargets(DirectWorld world, DirectPlanState state) {
        if (world == null || state == null) return List.of();
        List<TargetChoice> ranked = rank(world, state);
        int limit = Math.min(MAX_TARGETS_PER_PASS, ranked.size());
        List<EntityKey> keys = new ArrayList<>(limit);
        for (int i = 0; i < limit; i++) {
            keys.add(ranked.get(i).entity());
        }
        return List.copyOf(keys);
    }

    // ------------------------------------------------------------------ Phase 4.1 cache seams

    /*
     * These seams expose the exact DirectOnly candidate rules to CandidateCache. The cache decides only
     * whether a candidate is recomputed; the computation itself stays here so cache hits and misses cannot
     * diverge. Package-private: the public Phase 3 API is unchanged.
     */

    List<DirectTarget> eligibleTargets(DirectWorld world) {
        List<DirectTarget> eligible = new ArrayList<>();
        for (DirectTarget target : world.targets()) {
            if (!eligibleTarget(world, target)) continue;
            if (world.selfPosition().distance(target.position()) > settings.targetRange() + EPSILON) {
                continue;
            }
            eligible.add(target);
        }
        return eligible;
    }

    Optional<DirectPlan> bestBreakFor(DirectWorld world, DirectPlanState state, DirectTarget target) {
        return bestBreak(world, state, target);
    }

    List<BaseCandidate> baseCandidates(DirectWorld world) {
        return scanBases(world);
    }

    DirectPlan placeAt(DirectWorld world, DirectPlanState state, DirectTarget target, BaseCandidate base) {
        return placePlan(world, state, target, base, base.support());
    }

    DirectPlan choosePlan(Optional<DirectPlan> breakPlan, Optional<DirectPlan> placePlan) {
        return choose(breakPlan, placePlan);
    }

    DirectPlan refresh(DirectPlan plan, DirectPlanState state) {
        Intent old = plan.intent();
        Intent intent = new Intent(state.intentId(), state.plan(), old.strategy(), old.action(),
            old.cell(), old.entity(), old.blockLocks(), old.entityLocks(), old.dependsOn(),
            state.tick() + INTENT_LIFETIME_TICKS);
        return new DirectPlan(plan.action(), plan.target(), plan.cell(), plan.entity(), intent,
            plan.context());
    }

    /**
     * Break plan for exactly one ledger-observed crystal, used by the spawn event path so a newly bound
     * crystal is attacked in the same client-thread task regardless of what the overall planner would rank
     * first. Every normal break guard (observed/quarantine/hazard/age/attempts/reach/Weakness/policy) still
     * applies; the best valid target for that explosion is chosen deterministically and empty means the
     * caller must leave the sequence alone rather than fall through to a placement.
     */
    public Optional<DirectPlan> planBreak(DirectWorld world, DirectPlanState state,
                                          EntityKey crystalEntity) {
        if (world == null || state == null || crystalEntity == null) return Optional.empty();
        SafetyState safetyState = world.safetyState();
        if (safetyState == null || !Objects.equals(world.connection(), safetyState.conn())) {
            return Optional.empty();
        }
        DirectCrystal crystal = null;
        for (DirectCrystal candidate : world.crystals()) {
            if (candidate != null && crystalEntity.equals(candidate.entity())) {
                crystal = candidate;
                break;
            }
        }
        if (crystal == null || !eligibleBreak(world, state, crystal)) return Optional.empty();
        ToolChoice tool = toolFor(world, state, crystal);
        if (tool == null) return Optional.empty();

        DirectPlan best = null;
        double bestScore = -1.0;
        double bestDistance = Double.MAX_VALUE;
        int bestTargetId = Integer.MAX_VALUE;
        for (DirectTarget target : world.targets()) {
            if (!eligibleTarget(world, target)) continue;
            double targetDistance = world.selfPosition().distance(target.position());
            if (targetDistance > settings.targetRange() + EPSILON) continue;
            Optional<DirectPlan> candidate = breakPlan(world, state, target, crystal, tool);
            if (candidate.isEmpty()) continue;
            DirectPlan plan = candidate.get();
            double score = score(plan.context());
            double distance = world.eye().distance(crystal.position());
            int targetId = target.entity().id();
            boolean better = best == null
                || score > bestScore + EPSILON
                || (Math.abs(score - bestScore) <= EPSILON
                    && (distance < bestDistance - EPSILON
                        || (Math.abs(distance - bestDistance) <= EPSILON && targetId < bestTargetId)));
            if (better) {
                best = plan;
                bestScore = score;
                bestDistance = distance;
                bestTargetId = targetId;
            }
        }
        return Optional.ofNullable(best);
    }

    private List<TargetChoice> rank(DirectWorld world, DirectPlanState state) {
        List<TargetChoice> choices = new ArrayList<>();
        for (DirectTarget target : world.targets()) {
            if (!eligibleTarget(world, target)) continue;
            double distance = world.selfPosition().distance(target.position());
            if (distance > settings.targetRange() + EPSILON) continue;

            Optional<DirectPlan> breakPlan = bestBreak(world, state, target);
            Optional<DirectPlan> placePlan = bestPlace(world, state, target);
            DirectPlan chosen = choose(breakPlan, placePlan);
            if (chosen == null) continue;
            choices.add(new TargetChoice(target.entity(), distance, chosen));
        }
        choices.sort(TARGET_ORDER);
        return choices;
    }

    private boolean eligibleTarget(DirectWorld world, DirectTarget target) {
        if (target == null) return false;
        if (target.dead() || target.creative() || target.spectator() || target.friend()) return false;
        return !Objects.equals(target.entity(), world.self());
    }

    private DirectPlan choose(Optional<DirectPlan> breakPlan, Optional<DirectPlan> placePlan) {
        if (breakPlan.isEmpty() && placePlan.isEmpty()) return null;
        if (breakPlan.isEmpty()) return placePlan.get();
        if (placePlan.isEmpty()) return breakPlan.get();
        double breakScore = score(breakPlan.get().context());
        double placeScore = score(placePlan.get().context());
        // Tie: a verified break is cheaper and cannot be blocked by a base; prefer it.
        return breakScore + EPSILON >= placeScore ? breakPlan.get() : placePlan.get();
    }

    private TargetChoice sticky(List<TargetChoice> shortlist, EntityKey preferred) {
        TargetChoice best = shortlist.get(0);
        if (preferred == null) return best;
        TargetChoice sticky = null;
        TargetChoice bestAlternative = null;
        for (TargetChoice choice : shortlist) {
            if (Objects.equals(choice.entity(), preferred)) {
                sticky = choice;
            } else if (bestAlternative == null) {
                bestAlternative = choice;
            }
        }
        if (sticky == null) return best;
        if (bestAlternative == null) return sticky;
        return sticky.score() + EPSILON >= TARGET_STICKY_FRACTION * bestAlternative.score()
            ? sticky : best;
    }

    // ------------------------------------------------------------------ break (3.2)

    private Optional<DirectPlan> bestBreak(DirectWorld world, DirectPlanState state, DirectTarget target) {
        DirectPlan best = null;
        double bestScore = -1.0;
        double bestDistance = Double.MAX_VALUE;
        int bestId = Integer.MAX_VALUE;
        for (DirectCrystal crystal : world.crystals()) {
            if (!eligibleBreak(world, state, crystal)) continue;
            ToolChoice tool = toolFor(world, state, crystal);
            if (tool == null) continue;
            Optional<DirectPlan> candidate = breakPlan(world, state, target, crystal, tool);
            if (candidate.isEmpty()) continue;
            DirectPlan plan = candidate.get();

            double score = score(plan.context());
            double distance = world.eye().distance(crystal.position());
            int id = crystal.entity().id();
            boolean better = best == null
                || score > bestScore + EPSILON
                || (Math.abs(score - bestScore) <= EPSILON
                    && (distance < bestDistance - EPSILON
                        || (Math.abs(distance - bestDistance) <= EPSILON && id < bestId)));
            if (better) {
                best = plan;
                bestScore = score;
                bestDistance = distance;
                bestId = id;
            }
        }
        return Optional.ofNullable(best);
    }

    /** Priced, policy-checked break plan for one target and one crystal, or empty. */
    private Optional<DirectPlan> breakPlan(DirectWorld world, DirectPlanState state, DirectTarget target,
                                           DirectCrystal crystal, ToolChoice tool) {
        Set<Cell> overlays = Set.of();
        Pricing pricing = pricing(world, target, crystal.position(), overlays, state.tick(), true);
        TargetEstimate estimate = pricing.policy();
        ExposureBound exposure = exposure(world, crystal.position(), overlays);
        boolean faceplace = faceplace(world, target, estimate);
        CombatDamagePolicy policy = new CombatDamagePolicy(world.safetyState().selfHealth(),
            target.health());
        if (!policy.allows(estimate, exposure, world.safetyState().safety(), false, faceplace)) {
            return Optional.empty();
        }
        Intent intent = new Intent(state.intentId(), state.plan(), Strategy.DIRECT,
            Action.ATTACK_CRYSTAL, null, crystal.entity(), Set.of(), Set.of(crystal.entity()),
            Set.of(), state.tick() + INTENT_LIFETIME_TICKS);
        DirectContext context = new DirectContext(estimate, exposure, target.health(), false, faceplace,
            tool.slot(), tool.requiredItemId(), motionConfidence(world, target.entity()),
            pricing.hurt(), pricing.deferUntil());
        return Optional.of(new DirectPlan(Action.ATTACK_CRYSTAL, target.entity(), null,
            crystal.entity(), intent, context));
    }

    private boolean eligibleBreak(DirectWorld world, DirectPlanState state, DirectCrystal crystal) {
        if (crystal == null || crystal.state() != CrystalState.OBSERVED) return false;
        if (crystal.quarantined()) return false;
        if (settings.ownOnly() && !crystal.own()) return false;
        Cell cell = cellOf(crystal.position());
        // Foreign crystals protected by a live hazard/quarantine are not ours to touch. Our own unresolved
        // hazard is expected while the sequence runs, so it must not block the break.
        if (!crystal.own() && world.blocks().blocked(cell)) return false;
        if (crystal.ageTicks() < settings.minAgeTicks()) return false;
        int attempts = state.breakAttempts().getOrDefault(crystal.entity(), 0);
        if (attempts >= settings.maxAttempts()) return false;
        return world.crystalReachable(crystal.position(), crystal.box(), settings.attackRange(),
            settings.wallsRange());
    }

    /**
     * Tool for one break. Under Weakness the main hand must actually damage the crystal; otherwise the
     * planner proposes the first usable hotbar tool (or skips) and respects the configured switch delay.
     */
    private ToolChoice toolFor(DirectWorld world, DirectPlanState state, DirectCrystal crystal) {
        DirectItem selected = itemAt(world.hotbar(), world.selectedSlot());
        if (!world.weakness()) {
            return new ToolChoice(world.selectedSlot(), selected == null ? "" : selected.id());
        }
        if (selected != null && selected.breaksUnderWeakness()) {
            return new ToolChoice(world.selectedSlot(), selected.id());
        }
        for (DirectItem item : world.hotbar()) {
            if (item == null || item.slot() == world.selectedSlot()) continue;
            if (!item.breaksUnderWeakness()) continue;
            if (switchingTooSoon(state)) return null;
            return new ToolChoice(item.slot(), item.id());
        }
        return null;
    }

    private boolean switchingTooSoon(DirectPlanState state) {
        if (settings.switchDelayTicks() <= 0) return false;
        if (state.lastToolSwitchTick() < 0) return false;
        return state.tick() - state.lastToolSwitchTick() < settings.switchDelayTicks();
    }

    // ------------------------------------------------------------------ place (3.3)

    private Optional<DirectPlan> bestPlace(DirectWorld world, DirectPlanState state, DirectTarget target) {
        List<BaseCandidate> bases = scanBases(world);
        if (bases.isEmpty()) return Optional.empty();

        SurroundGap detectedGap = null;
        if (settings.surroundGap()) {
            Optional<SurroundGap> gap = SurroundGap.detect(world, target);
            if (gap.isPresent()) {
                detectedGap = gap.get();
                DirectPlan gapPlan = bestGapPlace(world, state, target, bases, detectedGap);
                if (gapPlan != null) return Optional.of(gapPlan);
            }
        }

        DirectPlan direct = null;
        for (BaseCandidate base : bases) {
            if (base.support()) continue;
            DirectPlan plan = placePlan(world, state, target, base, false);
            if (plan == null) continue;
            if (direct == null || betterPlace(plan, direct)) direct = plan;
        }
        if (direct != null) return Optional.of(direct);

        if (!settings.supportEnabled()) return Optional.empty();
        DirectPlan support = null;
        for (BaseCandidate base : bases) {
            if (!base.support()) continue;
            if (detectedGap != null && inGapColumn(detectedGap, base.cell())) continue;
            DirectPlan plan = placePlan(world, state, target, base, true);
            if (plan == null) continue;
            if (support == null || betterPlace(plan, support)) support = plan;
        }
        return Optional.ofNullable(support);
    }

    /**
     * Phase 4.5 gap preference: only direct bases whose crystal cell is one of the hole's open gap cells,
     * priced and guarded by the unchanged {@link #placePlan}. Returns {@code null} so the caller can fall
     * back to the normal Phase 3/4 choice; there is no support recursion on the gap path.
     */
    private DirectPlan bestGapPlace(DirectWorld world, DirectPlanState state, DirectTarget target,
                                    List<BaseCandidate> bases, SurroundGap gap) {
        DirectPlan best = null;
        for (BaseCandidate base : bases) {
            if (base.support()) continue;
            if (!gap.gapCells().contains(base.cell().above())) continue;
            DirectPlan plan = placePlan(world, state, target, base, false);
            if (plan == null) continue;
            if (best == null || betterPlace(plan, best)) best = plan;
        }
        return best;
    }

    /**
     * The gap is the exploit we are trying to use; a support block placed in that same column would fill it
     * and defeat the tactic (and the Phase 4.5 checklist promises it never happens). Only the detected gap
     * column is excluded from the optional support scan; direct placements in a legal base are unchanged.
     */
    private static boolean inGapColumn(SurroundGap gap, Cell cell) {
        return cell.equals(gap.gapFeet()) || cell.equals(gap.gapFeet().above());
    }

    private boolean betterPlace(DirectPlan candidate, DirectPlan incumbent) {
        double candidateScore = score(candidate.context());
        double incumbentScore = score(incumbent.context());
        if (candidateScore > incumbentScore + EPSILON) return true;
        if (Math.abs(candidateScore - incumbentScore) > EPSILON) return false;
        // Deterministic: lower cell first (x, then y, then z).
        return compareCells(candidate.intent().cell(), incumbent.intent().cell()) < 0;
    }

    private int compareCells(Cell left, Cell right) {
        if (left == null && right == null) return 0;
        if (left == null) return 1;
        if (right == null) return -1;
        int byX = Integer.compare(left.x(), right.x());
        if (byX != 0) return byX;
        int byY = Integer.compare(left.y(), right.y());
        return byY != 0 ? byY : Integer.compare(left.z(), right.z());
    }

    private DirectPlan placePlan(DirectWorld world, DirectPlanState state, DirectTarget target,
                                 BaseCandidate base, boolean support) {
        Cell baseCell = base.cell();
        Cell crystalCell = baseCell.above();
        if (!placementCellValid(world, baseCell, crystalCell, support)) return null;

        String requiredItemId = support ? OBSIDIAN : END_CRYSTAL;
        DirectItem item = itemFor(world.hotbar(), requiredItemId);
        if (item == null) return null;

        Vec centre = new Vec(crystalCell.x() + 0.5, crystalCell.y(), crystalCell.z() + 0.5);
        Set<Cell> overlays = support ? Set.of(baseCell) : Set.<Cell>of();

        Pricing pricing = pricing(world, target, centre, overlays, state.tick(), false);
        TargetEstimate estimate = pricing.policy();
        ExposureBound exposure = exposure(world, centre, overlays);
        boolean faceplace = faceplace(world, target, estimate);
        CombatDamagePolicy policy = new CombatDamagePolicy(world.safetyState().selfHealth(),
            target.health());
        if (!policy.allows(estimate, exposure, world.safetyState().safety(), false, faceplace)) return null;

        Action action = support ? Action.PLACE_SUPPORT : Action.PLACE_CRYSTAL;
        Cell intentCell = support ? baseCell : crystalCell;
        Set<Cell> locks = Set.of(intentCell);
        Intent intent = new Intent(state.intentId(), state.plan(), Strategy.DIRECT, action, intentCell,
            null, locks, Set.of(), Set.of(), state.tick() + INTENT_LIFETIME_TICKS);
        DirectContext context = new DirectContext(estimate, exposure, target.health(), false, faceplace,
            item.slot(), requiredItemId, motionConfidence(world, target.entity()),
            pricing.hurt(), pricing.deferUntil());
        return new DirectPlan(action, target.entity(), intentCell, null, intent, context);
    }

    /**
     * Vanilla placement rule plus the plan's extra guards. A crystal cell must be strict air, free of the
     * 1x2x1 entity volume, not already holding a crystal (or an unobserved removal), and outside any live
     * ledger hazard/quarantine. The optional two-air profile also requires the cell above to be air.
     */
    private boolean placementCellValid(DirectWorld world, Cell baseCell, Cell crystalCell, boolean support) {
        DirectBlocks blocks = world.blocks();
        if (support) {
            if (blocks.isBaseBlock(baseCell)) return false;
            if (!blocks.isReplaceable(baseCell) || !blocks.hasSupportBelow(baseCell)) return false;
        } else if (!blocks.isBaseBlock(baseCell)) {
            return false;
        }
        if (!blocks.isAir(crystalCell)) return false;
        if (settings.twoAirProfile() && !blocks.isAir(crystalCell.above())) return false;
        if (blocks.crystalPresent(crystalCell)) return false;
        if (blocks.blocked(baseCell) || blocks.blocked(crystalCell)) return false;
        if (!blocks.entityFree(crystalVolume(crystalCell))) return false;
        if (support) {
            if (!blocks.faceVisible(world.eye(), baseCell.below())) return false;
            return world.placementReachable(baseCell, settings.placeRange());
        }
        if (!blocks.faceVisible(world.eye(), baseCell)) return false;
        return world.placementReachable(crystalCell, settings.placeRange());
    }

    /** Entity volume a crystal occupies: one block wide/deep, two blocks tall from the crystal cell. */
    public static Box crystalVolume(Cell crystalCell) {
        return new Box(new Vec(crystalCell.x(), crystalCell.y(), crystalCell.z()),
            new Vec(crystalCell.x() + 1.0, crystalCell.y() + 2.0, crystalCell.z() + 1.0));
    }

    private List<BaseCandidate> scanBases(DirectWorld world) {
        DirectBlocks blocks = world.blocks();
        DirectSettings settings = this.settings;
        Vec self = world.selfPosition();
        int radius = (int) Math.ceil(settings.placeRange()) + 1;
        int minX = (int) Math.floor(self.x()) - radius;
        int maxX = (int) Math.floor(self.x()) + radius;
        int minY = (int) Math.floor(self.y()) - radius;
        int maxY = (int) Math.floor(self.y()) + radius;
        int minZ = (int) Math.floor(self.z()) - radius;
        int maxZ = (int) Math.floor(self.z()) + radius;

        List<BaseCandidate> bases = new ArrayList<>();
        List<Cell> ordered = new ArrayList<>();
        for (int x = minX; x <= maxX; x++) {
            for (int y = minY; y <= maxY; y++) {
                for (int z = minZ; z <= maxZ; z++) {
                    Cell cell = new Cell(x, y, z);
                    boolean direct = blocks.isBaseBlock(cell);
                    boolean support = settings.supportEnabled() && !direct
                        && blocks.isReplaceable(cell) && blocks.hasSupportBelow(cell);
                    if (direct || support) {
                        ordered.add(cell);
                    }
                }
            }
        }
        ordered.sort((left, right) -> compareCells(left, right));
        for (Cell cell : ordered) {
            boolean direct = blocks.isBaseBlock(cell);
            bases.add(new BaseCandidate(cell, !direct));
        }
        return bases;
    }

    // ------------------------------------------------------------------ shared helpers

    private boolean faceplace(DirectWorld world, DirectTarget target, TargetEstimate estimate) {
        if (!settings.faceplaceEnabled()) return false;
        if (estimate.expected() + EPSILON >= settings.safety().minTarget()) return false;
        if (target.health().cap() == Capability.KNOWN
            && target.health().value() <= settings.faceplaceHealth()) {
            return true;
        }
        return target.armourPressure();
    }

    private ExposureBound exposure(DirectWorld world, Vec centre, Set<Cell> overlays) {
        ExposureBound baseline = new ExposureBound(world.damage().selfUpper(centre, overlays),
            world.damage().friendUppers(centre, overlays), world.damage().envelope());
        if (!settings.motionPrediction()) return baseline;
        return MotionPrediction.exposure(world, centre, overlays, baseline);
    }

    /**
     * Phase 4.2/4.3 pricing. Hurt-sync adjusts the target's expected/guaranteed estimates before the
     * existing min-target-damage policy check; it never changes a self or friend bound or the exposure
     * envelope. The hurt-adjusted estimate is also used for ranking. When the policy accepts the plan, the
     * runtime revalidates the same hurt-adjusted estimate at dispatch.
     */
    private Pricing pricing(DirectWorld world, DirectTarget target, Vec centre, Set<Cell> overlays,
                            int serverTick, boolean canDefer) {
        TargetEstimate baseline = world.damage().targetEstimate(target, centre, overlays);
        TargetEstimate policy = settings.motionPrediction()
            ? MotionPrediction.targetEstimate(world, target, centre, overlays, baseline,
                settings.motionPredictionTicks())
            : baseline;
        if (!settings.hurtSync()) return new Pricing(policy, null, HurtSync.NO_DEFER);
        Arrival arrival = HurtSync.immediateArrival(serverTick);
        TargetEstimate hurtBaseline = world.damage().targetEstimate(target, centre, overlays, arrival);
        TargetEstimate adjusted = settings.motionPrediction()
            ? scaleTargetByHurt(policy, baseline, hurtBaseline) : hurtBaseline;
        long deferUntil = canDefer
            ? world.damage().hurtSyncDeferUntil(target, centre, overlays, arrival, serverTick, adjusted)
            : HurtSync.NO_DEFER;
        return new Pricing(adjusted, adjusted, deferUntil);
    }

    /**
     * Applies the raw hurt ratio measured on the Phase 3 estimate to the motion-adjusted target estimate.
     * Mitigation is linear in raw damage for a fixed mitigation state, so the ratio is the same; every
     * factor is capped at 1 so hurt-sync can only lower the target estimate, never a self/friend bound.
     */
    private static TargetEstimate scaleTargetByHurt(TargetEstimate motion, TargetEstimate baseline,
                                                    TargetEstimate hurt) {
        double lower = ratio(hurt.guaranteedLower(), baseline.guaranteedLower());
        double expected = ratio(hurt.expected(), baseline.expected());
        double optimistic = ratio(hurt.optimistic(), baseline.optimistic());
        return new TargetEstimate(
            motion.guaranteedLower() * lower,
            motion.expected() * expected,
            motion.optimistic() * optimistic);
    }

    private static double ratio(double adjusted, double baseline) {
        if (!(baseline > 0.0)) return 0.0;
        return Math.min(1.0, Math.max(0.0, adjusted / baseline));
    }

    /**
     * Phase 4.2 ranking multiplier: low motion confidence only lowers opportunity scores, never a safety
     * bound and never a policy check. Off is exactly 1.0 so Phase 3 ranking is unchanged.
     */
    private double motionConfidence(DirectWorld world, EntityKey target) {
        return settings.motionPrediction()
            ? MotionPrediction.confidence(world, target, settings.motionPredictionTicks())
            : MotionPrediction.BASELINE_CONFIDENCE;
    }

    /**
     * Opportunity score: expected damage discounted by evidence confidence. Phase 4.3 ranks with the
     * hurt-adjusted expected damage when present; the policy estimate is untouched.
     */
    private static double score(DirectContext context) {
        TargetEstimate ranking = context.hurtEstimate() == null ? context.estimate()
            : context.hurtEstimate();
        return ranking.expected() * context.confidence();
    }

    private static DirectItem itemAt(List<DirectItem> hotbar, int slot) {
        for (DirectItem item : hotbar) {
            if (item != null && item.slot() == slot) return item;
        }
        return null;
    }

    private static DirectItem itemFor(List<DirectItem> hotbar, String id) {
        DirectItem best = null;
        for (DirectItem item : hotbar) {
            if (item == null || !id.equals(item.id())) continue;
            if (best == null || item.slot() < best.slot()) best = item;
        }
        return best;
    }

    public static Cell cellOf(Vec position) {
        return new Cell(floor(position.x()), floor(position.y()), floor(position.z()));
    }

    private static int floor(double value) {
        return (int) Math.floor(value);
    }

    /** Phase 4.3 pricing split: policy estimate, ranking-only hurt estimate, and wait tick. */
    private record Pricing(TargetEstimate policy, TargetEstimate hurt, long deferUntil) {
    }

    private record TargetChoice(EntityKey entity, double distance, DirectPlan plan) {
        double score() {
            TargetEstimate ranking = plan.context().hurtEstimate() == null
                ? plan.context().estimate() : plan.context().hurtEstimate();
            return ranking.expected() * plan.context().confidence();
        }
    }

    private record ToolChoice(int slot, String requiredItemId) {
    }

    record BaseCandidate(Cell cell, boolean support) {
    }
}
