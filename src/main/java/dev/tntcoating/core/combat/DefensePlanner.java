/*
 * Original Comet implementation by Boyonabike.
 * Independent: written from the pinned 26.2 artifact and Meteor build 35 behaviour; no third-party code.
 * SPDX-License-Identifier: CC0-1.0
 */

package dev.tntcoating.core.combat;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/**
 * Phase 4.6 defensive planning. Pure: it reads a {@link DirectWorld} and returns at most one plan; it never
 * sends, never touches Meteor state and never uses Minecraft types.
 *
 * <p>Two independently gated opportunities share the offence identity, reach, item and safety rules:</p>
 * <ul>
 *   <li><b>Dangerous crystal break</b> ({@code dangerousCrystalBreak}): an observed, non-owned crystal in
 *       attack range whose whole-hull upper bound on self damage would breach the margin if somebody else
 *       detonated it. Breaking is itself a detonation, so the plan carries the ordinary self/friend
 *       exposure (cover-aware, weakest mitigation envelope) and the engine reserves risk through the normal
 *       {@code CombatRiskBudget}/{@code CombatScheduler} path. A defensive plan is marked
 *       {@link DirectContext#defensive()} so dispatch revalidates the self/friend ceilings without the
 *       offence minimum-target floor.</li>
 *   <li><b>Anti-piston blocker</b> ({@code antiPiston}): a piston within two blocks of the self feet or head
 *       cell, facing toward self. The push-path cell is the cell the piston head extends into:
 *       {@code piston cell + facing}. If an observed foreign crystal occupies that cell, the only legal
 *       response is a defensive break of that crystal; if it cannot be broken safely the opportunity is
 *       dropped, because obsidian cannot be placed into the crystal's cell. An empty, replaceable,
 *       supported, entity-free, reachable and visible front cell becomes an {@link Action#PLACE_BLOCKER}.
 *       Mining the power source is deliberately out of scope: CrystalCombat has no owner-scoped Speedmine
 *       contract and never calls {@code Speedmine.requestBreak} (audit C12/C17).</li>
 * </ul>
 *
 * <p>Own and ledger-protected crystals are never broken: the engine exposes no trap-invalidation API, so a
 * crystal the ledger protects for our own active trap is skipped. Anti-piston has no piston-ownership API;
 * only cells the ledger blocks are skipped.</p>
 *
 * <p>Priority: an imminent opportunity (arrival at most {@link #IMMINENT_TICKS}) is returned in preference
 * to non-imminent work; breaks sort before blockers. The engine decides whether defence outranks offence
 * this tick. Nothing here bypasses a self/friend ceiling or the risk budget.</p>
 */
public final class DefensePlanner {
    /** Arrival at or below this many ticks counts as imminent and outranks offence. */
    public static final int IMMINENT_TICKS = 4;
    /** Defensive opportunities are discarded by the engine after the same lifetime as offence intents. */
    static final int INTENT_LIFETIME_TICKS = 20;
    static final String OBSIDIAN = "minecraft:obsidian";
    private static final double EPSILON = 1.0e-9;
    private static final int PISTON_SCAN_RADIUS = 2;

    private final DirectSettings settings;

    public DefensePlanner(DirectSettings settings) {
        this.settings = Objects.requireNonNull(settings, "settings");
    }

    /**
     * Plans the best defensive opportunity, or empty when both gates are off, no threat is present, or no
     * legal safe response exists.
     *
     * @param currentSelfBound the risk budget's current outstanding self bound for this connection. It is
     *                         supplied by the engine, never inferred here.
     */
    public Optional<DirectPlan> plan(DirectWorld world, DirectPlanState state, double currentSelfBound) {
        return choose(world, state, currentSelfBound).map(Choice::plan);
    }

    /**
     * The opportunity together with the estimated arrival tick, so the engine can tell an imminent
     * defensive action (which outranks offence) from a non-imminent one (which only runs when offence has
     * nothing to send).
     */
    public Optional<Choice> choose(DirectWorld world, DirectPlanState state, double currentSelfBound) {
        if (world == null || state == null) return Optional.empty();
        if (!settings.dangerousCrystalBreak() && !settings.antiPiston()) return Optional.empty();
        SafetyState safetyState = world.safetyState();
        if (safetyState == null || !safetyState.conn().equals(world.connection())) {
            return Optional.empty();
        }

        List<Opportunity> found = new ArrayList<>();
        if (settings.dangerousCrystalBreak()) {
            dangerousCrystals(world, state, currentSelfBound, safetyState, found);
        }
        if (settings.antiPiston()) {
            pistonThreats(world, state, safetyState, found);
        }
        if (found.isEmpty()) return Optional.empty();

        boolean imminent = found.stream().anyMatch(o -> o.arrivalTicks() <= IMMINENT_TICKS);
        return found.stream()
            .filter(o -> !imminent || o.arrivalTicks() <= IMMINENT_TICKS)
            .sorted(COMPARATOR)
            .findFirst()
            .map(o -> new Choice(o.plan(), o.arrivalTicks()));
    }

    /** A selected defensive plan and its estimated arrival tick (never below 1). */
    public record Choice(DirectPlan plan, int arrivalTicks) {
        public Choice {
            if (plan == null) throw new IllegalArgumentException("plan");
            if (arrivalTicks < 1) throw new IllegalArgumentException("arrivalTicks");
        }
    }

    /**
     * Revalidates a previously selected defensive plan against the current world. Unlike the offence path
     * there is no target, so the plan is rebuilt from the same opportunity family and must keep the same
     * action and identity: the same crystal entity for a break, the same cell for a blocker.
     */
    public Optional<DirectPlan> reprice(DirectPlan planned, DirectWorld world, DirectPlanState state,
                                        double currentSelfBound) {
        if (planned == null) return Optional.empty();
        Optional<DirectPlan> fresh = plan(world, state, currentSelfBound);
        if (fresh.isEmpty()) return Optional.empty();
        DirectPlan plan = fresh.get();
        if (plan.action() != planned.action()) return Optional.empty();
        if (plan.action() == Action.ATTACK_CRYSTAL) {
            return plan.entity() != null && plan.entity().equals(planned.entity())
                ? Optional.of(plan) : Optional.empty();
        }
        Cell cell = plan.intent().cell();
        return cell != null && cell.equals(planned.intent().cell())
            ? Optional.of(plan) : Optional.empty();
    }

    // ------------------------------------------------------------------ dangerous crystals

    private void dangerousCrystals(DirectWorld world, DirectPlanState state, double currentSelfBound,
                                   SafetyState safetyState, List<Opportunity> out) {
        // A margin breach is only provable from a known lower health bound; unknown health is no evidence.
        if (safetyState.selfHealth() == null
            || safetyState.selfHealth().cap() != Capability.KNOWN) {
            return;
        }
        Safety safety = safetyState.safety();
        double healthLower = healthLowerBound(safetyState.selfHealth());
        for (DirectCrystal crystal : world.crystals()) {
            if (!defensiveBreakEligible(world, state, crystal)) continue;
            MitigatedDamage worstSelf = world.damage().selfUpperWorst(world.selfPosition(),
                crystal.position(), Set.of());
            if (worstSelf == null || !finite(worstSelf.beforeAbsorption())) continue;
            double worst = worstSelf.beforeAbsorption();
            boolean breachesMargin = healthLower - currentSelfBound - worst <= safety.minRemaining();
            boolean breachesCeiling = worst > safety.maxSelf();
            if (!breachesMargin && !breachesCeiling) continue;
            int arrival = enemyWithin(world, crystal.position(), settings.attackRange()) ? 1 : 5;
            breakPlan(world, state, safetyState, crystal, arrival).ifPresent(plan ->
                out.add(new Opportunity(arrival, 0, plan)));
        }
    }

    // ------------------------------------------------------------------ anti-piston

    private void pistonThreats(DirectWorld world, DirectPlanState state, SafetyState safetyState,
                               List<Opportunity> out) {
        Cell feet = cellOf(world.selfPosition());
        Cell head = feet.above();
        Set<Cell> pistons = new LinkedHashSet<>();
        for (Cell anchor : List.of(feet, head)) {
            for (int dx = -PISTON_SCAN_RADIUS; dx <= PISTON_SCAN_RADIUS; dx++) {
                for (int dy = -PISTON_SCAN_RADIUS; dy <= PISTON_SCAN_RADIUS; dy++) {
                    for (int dz = -PISTON_SCAN_RADIUS; dz <= PISTON_SCAN_RADIUS; dz++) {
                        if (dx == 0 && dy == 0 && dz == 0) continue;
                        Cell candidate = new Cell(anchor.x() + dx, anchor.y() + dy, anchor.z() + dz);
                        if (world.blocks().isPiston(candidate)) pistons.add(candidate);
                    }
                }
            }
        }
        for (Cell piston : pistons) {
            PistonFacing facing = world.blocks().pistonFacing(piston);
            if (facing == null || world.blocks().pistonExtended(piston)) continue;
            if (!aimsAt(piston, facing, feet, head)) continue;
            Cell front = facing.front(piston);
            if (front.equals(feet) || front.equals(head)) continue;
            if (world.blocks().blocked(front)) continue;

            DirectCrystal crystal = crystalAt(world, front);
            if (crystal != null) {
                if (!defensiveBreakEligible(world, state, crystal)) continue;
                int arrival = world.blocks().pistonPowered(piston) ? 2 : 3;
                breakPlan(world, state, safetyState, crystal, arrival).ifPresent(plan ->
                    out.add(new Opportunity(arrival, 0, plan)));
                continue;
            }
            blockerPlan(world, state, safetyState, piston, front).ifPresent(plan ->
                out.add(new Opportunity(world.blocks().pistonPowered(piston) ? 3 : 4, 1, plan)));
        }
    }

    /**
     * The plan's anti-piston gate: the piston faces toward one of the two self cells, at an axial distance
     * of one or two, with no lateral offset. A piston whose front cell is the self cell is skipped by the
     * caller because that cell cannot hold the blocker.
     */
    static boolean aimsAt(Cell piston, PistonFacing facing, Cell feet, Cell head) {
        for (Cell self : List.of(feet, head)) {
            int ax = self.x() - piston.x();
            int ay = self.y() - piston.y();
            int az = self.z() - piston.z();
            int axial = ax * facing.dx() + ay * facing.dy() + az * facing.dz();
            if (axial < 1 || axial > PISTON_SCAN_RADIUS) continue;
            int lateralX = ax - facing.dx() * axial;
            int lateralY = ay - facing.dy() * axial;
            int lateralZ = az - facing.dz() * axial;
            if (lateralX == 0 && lateralY == 0 && lateralZ == 0) return true;
        }
        return false;
    }

    private Optional<DirectPlan> blockerPlan(DirectWorld world, DirectPlanState state,
                                              SafetyState safetyState, Cell piston, Cell front) {
        DirectBlocks blocks = world.blocks();
        if (!blocks.isAir(front) && !blocks.isReplaceable(front)) return Optional.empty();
        // A crystal entity does not change the block state; the ledger's up-to-removal presence bit must
        // veto a blocker placement into an unresolved crystal cell just as it vetoes a second crystal.
        if (blocks.crystalPresent(front)) return Optional.empty();
        if (!blocks.hasSupportBelow(front)) return Optional.empty();
        if (!blocks.entityFree(Box.fromCell(front))) return Optional.empty();
        if (!world.placementReachable(front, settings.placeRange())) return Optional.empty();
        if (!blocks.faceVisible(world.eye(), front.below())) return Optional.empty();
        DirectItem item = itemFor(world, OBSIDIAN);
        if (item == null) return Optional.empty();

        EffectiveHealth noTarget = new EffectiveHealth(0.0, 0.0, Capability.UNKNOWN, 0L);
        Intent intent = new Intent(state.intentId(), state.plan(), Strategy.DEFEND,
            Action.PLACE_BLOCKER, front, null, Set.of(front), Set.of(), Set.of(),
            state.tick() + INTENT_LIFETIME_TICKS);
        DirectContext context = new DirectContext(zeroEstimate(), zeroExposure(world), noTarget,
            false, false, item.slot(), OBSIDIAN, 1.0, null, -1L, true);
        return Optional.of(new DirectPlan(Action.PLACE_BLOCKER, null, front, null, intent, context));
    }

    // ------------------------------------------------------------------ shared break path

    private Optional<DirectPlan> breakPlan(DirectWorld world, DirectPlanState state,
                                            SafetyState safetyState, DirectCrystal crystal, int arrival) {
        DirectItem tool = toolFor(world, state);
        if (tool == null) return Optional.empty();
        Vec centre = crystal.position();
        MitigatedDamage selfUpper = world.damage().selfUpper(centre, Set.of());
        if (selfUpper == null) return Optional.empty();
        ExposureBound exposure = new ExposureBound(selfUpper, world.damage().friendUppers(centre, Set.of()),
            world.damage().envelope());
        EffectiveHealth noTarget = new EffectiveHealth(0.0, 0.0, Capability.UNKNOWN, 0L);
        CombatDamagePolicy policy = new CombatDamagePolicy(safetyState.selfHealth(), noTarget);
        if (!policy.allowsDefensive(exposure, safetyState.safety())) return Optional.empty();

        Intent intent = new Intent(state.intentId(), state.plan(), Strategy.DEFEND,
            Action.ATTACK_CRYSTAL, null, crystal.entity(), Set.of(), Set.of(crystal.entity()), Set.of(),
            state.tick() + INTENT_LIFETIME_TICKS);
        DirectContext context = new DirectContext(zeroEstimate(), exposure, noTarget, false, false,
            tool.slot(), tool.id(), 1.0, null, -1L, true);
        return Optional.of(new DirectPlan(Action.ATTACK_CRYSTAL, null, null, crystal.entity(), intent,
            context));
    }

    private boolean defensiveBreakEligible(DirectWorld world, DirectPlanState state,
                                            DirectCrystal crystal) {
        if (crystal == null || crystal.state() != CrystalState.OBSERVED) return false;
        if (crystal.quarantined() || crystal.own()) return false;
        Cell cell = cellOf(crystal.position());
        // No engine trap-invalidation API exists: never break anything the ledger protects.
        if (world.blocks().blocked(cell)) return false;
        if (crystal.ageTicks() < settings.minAgeTicks()) return false;
        int attempts = state.breakAttempts().getOrDefault(crystal.entity(), 0);
        if (attempts >= settings.maxAttempts()) return false;
        return world.crystalReachable(crystal.position(), crystal.box(), settings.attackRange(),
            settings.wallsRange());
    }

    private DirectItem toolFor(DirectWorld world, DirectPlanState state) {
        DirectItem selected = itemAt(world, world.selectedSlot());
        if (!world.weakness()) return selected;
        if (selected != null && selected.breaksUnderWeakness()) return selected;
        for (DirectItem item : world.hotbar()) {
            if (!item.breaksUnderWeakness()) continue;
            if (item.slot() == world.selectedSlot()) return item;
            if (switchingTooSoon(state)) continue;
            return item;
        }
        return null;
    }

    private boolean switchingTooSoon(DirectPlanState state) {
        if (settings.switchDelayTicks() <= 0) return false;
        if (state.lastToolSwitchTick() < 0) return false;
        return state.tick() - state.lastToolSwitchTick() < settings.switchDelayTicks();
    }

    private static DirectItem itemAt(DirectWorld world, int slot) {
        for (DirectItem item : world.hotbar()) {
            if (item.slot() == slot) return item;
        }
        return null;
    }

    private static DirectItem itemFor(DirectWorld world, String id) {
        DirectItem selected = itemAt(world, world.selectedSlot());
        if (selected != null && id.equals(selected.id())) return selected;
        for (DirectItem item : world.hotbar()) {
            if (id.equals(item.id())) return item;
        }
        return null;
    }

    private static DirectCrystal crystalAt(DirectWorld world, Cell cell) {
        for (DirectCrystal crystal : world.crystals()) {
            if (cell.equals(cellOf(crystal.position()))) return crystal;
        }
        return null;
    }

    private static boolean enemyWithin(DirectWorld world, Vec centre, double range) {
        for (DirectTarget target : world.targets()) {
            if (target.dead() || target.creative() || target.spectator() || target.friend()) continue;
            if (centre.distance(target.position()) <= range + EPSILON) return true;
        }
        return false;
    }

    private static double healthLowerBound(EffectiveHealth health) {
        return health != null && health.cap() == Capability.KNOWN ? health.value() : 0.0;
    }

    private static ExposureBound zeroExposure(DirectWorld world) {
        return new ExposureBound(new MitigatedDamage(0.0), Map.of(), world.damage().envelope());
    }

    private static TargetEstimate zeroEstimate() {
        return new TargetEstimate(0.0, 0.0, 0.0);
    }

    private static Cell cellOf(Vec position) {
        return new Cell((int) Math.floor(position.x()), (int) Math.floor(position.y()),
            (int) Math.floor(position.z()));
    }

    private static boolean finite(double value) {
        return Double.isFinite(value) && value >= 0.0;
    }

    private record Opportunity(int arrivalTicks, int order, DirectPlan plan) {
    }

    private static final Comparator<Opportunity> COMPARATOR =
        Comparator.comparingInt(Opportunity::arrivalTicks)
            .thenComparingInt(Opportunity::order)
            .thenComparing(o -> o.plan().entity() == null ? "" : o.plan().entity().toString())
            .thenComparing(o -> o.plan().intent().cell() == null ? "" : o.plan().intent().cell().toString());
}
