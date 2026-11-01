/*
 * Original Comet implementation by Boyonabike.
 * Independent: written from the pinned 26.2 artifact and Meteor build 35 behaviour; no third-party code.
 * SPDX-License-Identifier: CC0-1.0
 */

package dev.tntcoating.core.combat;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * Phase 6.3: footprint-scoped compatibility between a direct plan {@code A} and a committed trap plan
 * {@code B}. Pure: it takes only the facts the engine already priced and never sends, reads the world, or
 * touches Meteor.
 *
 * <p>Every row of the plan's 6.3 table is evaluated in order; the first non-compatible verdict wins. The
 * table's single rule is that a compatible verdict needs positive evidence for every row: when a row
 * cannot be established, the result is {@link Verdict#CONFLICT} and the direct plan waits. Distance on
 * its own is never treated as proof of compatibility.</p>
 *
 * <p>Audited 26.2 facts used here (see docs/superpowers/audit/2026-10-29-combat-api-26.2.md §3.5.2 and
 * §3.10.3): a power-6 crystal explosion seeds every block-destruction ray with at most
 * {@code 6 * (0.7 + 0.6)} = 7.8 intensity, decays by 0.225 every 0.3-block step (0.75 per block) and
 * subtracts {@code (resistance + 0.3) * 0.3} for every non-air cell; entity damage uses
 * {@code d = distance / 12} and skips entities beyond that, with three-dimensional knockback
 * {@code normalize(eye - centre) * (1 - d) * seenPercent} and no hole/feet-level exemption; an
 * explosion-tagged hit removes an end crystal without a second explosion, so destroying the trap crystal
 * never counts as "the trap will detonate anyway".</p>
 *
 * <p>Exposure is taken at its audited worst case ({@code seenPercent = 1.0}): Meteor exposes no
 * after-application seen-percent value (audit §3.10.2), so a smaller exposure cannot be established and
 * must not be assumed. Collision geometry is the caller's authoritative {@link CollisionView}; a missing
 * motion sample or collision view inside the search volume is unknown evidence and therefore conflicts.</p>
 */
public final class FootprintConflict {
    /** Radius of the crystal explosion the plan prices (EndCrystal detonation radius 6.0). */
    public static final double POWER_SIX_RADIUS = 6.0;
    /** Entity search radius for a power-6 explosion: {@code d = distance / (radius * 2) <= 1}. */
    public static final double ENTITY_SEARCH_RADIUS = POWER_SIX_RADIUS * 2.0;
    /** Worst-case ray intensity for power 6: {@code radius * (0.7 + 0.6)}. */
    public static final double MAX_RAY_INTENSITY = POWER_SIX_RADIUS * 1.3;
    /** Ray step in blocks. */
    public static final double RAY_STEP = 0.3;
    /** Unconditional intensity decay per ray step. */
    public static final double RAY_STEP_DECAY = 0.225;
    /** Player eye height used for the knockback direction; the audited value for the trap victim. */
    public static final double EYE_HEIGHT = 1.62;
    /** The audited knockback multiplier for a crystal explosion (base damage calculator default). */
    public static final double KNOCKBACK_MULTIPLIER = 1.0;
    /** Audited blast resistances of the four blocks a trap plan can own. */
    public static final double OBSIDIAN_RESISTANCE = 1200.0;
    public static final double PISTON_RESISTANCE = 1.5;
    public static final double REDSTONE_TORCH_RESISTANCE = 0.0;
    public static final double REDSTONE_BLOCK_RESISTANCE = 9.0;
    public static final String OBSIDIAN = "minecraft:obsidian";
    public static final String PISTON = "minecraft:piston";

    private static final double EPSILON = 1e-9;

    private FootprintConflict() {
    }

    /** One row of the plan's 6.3 compatibility table. */
    public enum Row {
        /** A's explosion could break one of B's structural, power or piston cells. */
        STRUCTURAL,
        /** A's explosion entity search reaches B's bound crystal, or A would attack it. */
        ENTITY,
        /** A's explosion could damage or displace something inside B's swept volume. */
        SWEPT,
        /** A's explosion could move the target in three dimensions out of B's priced envelope. */
        TARGET_MOTION,
        /** A and B need the same hotbar slot or rotation in one tick. */
        RESOURCES,
        /** The combined reservations fail the safety budget. */
        RISK
    }

    /** What the engine should do with the direct plan this tick. */
    public enum Verdict {
        /** Every row has positive evidence of compatibility. */
        COMPATIBLE,
        /** A shared resource is in use: the direct plan waits, it is not rejected. */
        SERIALISE,
        /** A row is conflicting or cannot be established: the direct plan waits. */
        CONFLICT
    }

    /** The first row that stopped compatibility, with a short auditable reason. */
    public record Result(Verdict verdict, Row row, String detail) {
        public Result {
            Objects.requireNonNull(verdict, "verdict");
            Objects.requireNonNull(row, "row");
            Objects.requireNonNull(detail, "detail");
        }

        public boolean compatible() {
            return verdict == Verdict.COMPATIBLE;
        }
    }

    /** One cell B owns, with the block B will place there. A null id means the block is unknown. */
    public record TrapBlock(Cell cell, String blockId) {
        public TrapBlock {
            Objects.requireNonNull(cell, "cell");
        }
    }

    /**
     * The direct plan's footprint as the engine already computed it. {@code explosion} is the point the
     * future detonation is priced around; it is null for a clean action (no explosion).
     */
    public record DirectView(Action action, Vec explosion, Footprint footprint, boolean needsSlot,
                             boolean needsRotation) {
        public DirectView {
            Objects.requireNonNull(action, "action");
            Objects.requireNonNull(footprint, "footprint");
        }
    }

    /**
     * The committed trap's owned geometry. A null field is unknown evidence, not "absent": the matching
     * row then conflicts. {@code targetEnvelope} is the window B priced the victim in
     * ({@link PistonGeometry#POSITION_TOLERANCE} around the planned feet position).
     */
    public record TrapView(EntityKey target, Vec targetFeet, Box targetEnvelope, List<TrapBlock> blocks,
                           Cell sourceCell, EntityKey crystalEntity, Vec crystalPosition, Box swept,
                           ExposureBound exposure, boolean needsSlot, boolean needsRotation) {
        public TrapView {
            blocks = blocks == null ? List.of() : List.copyOf(blocks);
        }
    }

    /**
     * Live facts the engine supplies for this tick. {@code hotbarSlotHeld}/{@code rotationHeld} include
     * both the scheduler's current owner and the trap's claim for this tick; {@code combinedRiskFits} is
     * the engine's audited budget decision for the combined direct+trap reservations.
     */
    public record Environment(boolean hotbarSlotHeld, boolean rotationHeld, boolean combinedRiskFits,
                              MotionSample targetMotion, CollisionView collision) {
    }

    /** True when the action detonates a crystal at its cell. */
    public static boolean explosive(Action action) {
        return action == Action.PLACE_CRYSTAL || action == Action.ATTACK_CRYSTAL;
    }

    /** Evaluates every 6.3 row in table order and returns the first decision that is not compatible. */
    public static Result check(DirectView direct, TrapView trap, Environment environment) {
        if (direct == null) return conflict(Row.STRUCTURAL, "direct footprint unknown");
        if (trap == null) return conflict(Row.STRUCTURAL, "committed trap footprint unknown");
        if (environment == null) return conflict(Row.STRUCTURAL, "conflict environment unknown");

        Result structural = structural(direct, trap);
        if (!structural.compatible()) return structural;
        Result entity = entity(direct, trap);
        if (!entity.compatible()) return entity;
        Result swept = swept(direct, trap);
        if (!swept.compatible()) return swept;
        Result motion = targetMotion(direct, trap, environment);
        if (!motion.compatible()) return motion;
        Result resources = resources(direct, environment);
        if (!resources.compatible()) return resources;
        return risk(environment);
    }

    /** Builds the trap view from the plan the trap already priced; empty when the plan is unavailable. */
    public static Optional<TrapView> trapView(PistonPlanner.Plan plan, EntityKey crystalEntity,
                                              Vec crystalPosition, Vec targetFeet,
                                              ExposureBound exposure, boolean needsSlot,
                                              boolean needsRotation) {
        if (plan == null || plan.push() == null) return Optional.empty();
        PistonGeometry.PistonPush push = plan.push();
        Map<Cell, String> blocks = new LinkedHashMap<>();
        for (Cell support : plan.structural()) {
            if (support != null) blocks.put(support, OBSIDIAN);
        }
        if (push.piston() != null) blocks.put(push.piston(), PISTON);
        if (push.power() != null) {
            String power = push.powerKind() == null ? null : push.powerKind().itemId();
            blocks.put(push.power(), power);
        }
        List<TrapBlock> trapBlocks = new ArrayList<>(blocks.size());
        blocks.forEach((cell, id) -> trapBlocks.add(new TrapBlock(cell, id)));
        Box envelope = targetFeet == null ? null
            : Box.around(targetFeet, PistonGeometry.POSITION_TOLERANCE);
        Box sweptBox = plan.footprint() == null ? null : plan.footprint().swept();
        return Optional.of(new TrapView(plan.target(), targetFeet, envelope, trapBlocks, push.source(),
            crystalEntity, crystalPosition, sweptBox, exposure, needsSlot, needsRotation));
    }

    // ------------------------------------------------------------------ rows

    /**
     * Structural: could a power-6 ray break one of B's block cells? A cell with a resistance the maximum
     * ray can pay for inside the audited reach is a conflict; an unknown block or an unowned cell is
     * unknown evidence and conflicts too. The trap's source crystal is an entity, so its destruction is
     * the {@link #ENTITY} row, not a block-resistance question.
     */
    private static Result structural(DirectView direct, TrapView trap) {
        if (!explosive(direct.action())) return compatible();
        Vec centre = direct.explosion();
        if (centre == null) return conflict(Row.STRUCTURAL, "explosion centre unknown");
        if (trap.blocks().isEmpty()) return conflict(Row.STRUCTURAL, "trap block cells unknown");
        for (TrapBlock block : trap.blocks()) {
            double resistance = resistance(block.blockId());
            if (Double.isNaN(resistance)) {
                return conflict(Row.STRUCTURAL, "unknown blast resistance at " + block.cell());
            }
            double distance = minDistance(centre, Box.fromCell(block.cell()));
            if (couldBreak(distance, resistance)) {
                return conflict(Row.STRUCTURAL, "power-6 ray could break " + block.blockId()
                    + " at " + block.cell() + " (" + format(distance) + " blocks)");
            }
        }
        return compatible();
    }

    /** Entity: A attacks B's crystal, or the audited power-6 entity search includes its position. */
    private static Result entity(DirectView direct, TrapView trap) {
        if (trap.crystalEntity() != null && direct.footprint().bound().contains(trap.crystalEntity())) {
            return conflict(Row.ENTITY, "direct plan attacks the trap crystal");
        }
        if (!explosive(direct.action())) return compatible();
        Vec centre = direct.explosion();
        if (centre == null) return conflict(Row.ENTITY, "explosion centre unknown");
        Vec crystal = trap.crystalPosition();
        if (crystal == null) return conflict(Row.ENTITY, "trap crystal position unknown");
        double distance = crystal.distance(centre);
        if (distance <= ENTITY_SEARCH_RADIUS + EPSILON) {
            return conflict(Row.ENTITY, "power-6 entity search reaches the trap crystal at "
                + format(distance) + " blocks; an explosion-tagged hit removes it without a second explosion");
        }
        return compatible();
    }

    /** Swept: A's explosion could damage or displace anything inside B's push sweep. */
    private static Result swept(DirectView direct, TrapView trap) {
        if (!explosive(direct.action())) return compatible();
        Vec centre = direct.explosion();
        if (centre == null) return conflict(Row.SWEPT, "explosion centre unknown");
        if (trap.swept() == null) return conflict(Row.SWEPT, "trap swept volume unknown");
        double distance = minDistance(centre, trap.swept());
        if (distance <= ENTITY_SEARCH_RADIUS + EPSILON) {
            return conflict(Row.SWEPT, "power-6 entity bound reaches the trap swept volume at "
                + format(distance) + " blocks");
        }
        return compatible();
    }

    /**
     * Target motion: the audited three-dimensional knockback, predicted through the real collision view,
     * must stay inside the window B priced. The window is deliberately the trap's stationarity window:
     * the planner only guarantees damage for a target that stays within
     * {@link PistonGeometry#POSITION_TOLERANCE} of the priced position, so any wider reachable set cannot
     * be established as compatible. No hole exemption: blocked sides are fine, blocked up is not.
     */
    private static Result targetMotion(DirectView direct, TrapView trap, Environment environment) {
        if (!explosive(direct.action())) return compatible();
        Vec centre = direct.explosion();
        if (centre == null) return conflict(Row.TARGET_MOTION, "explosion centre unknown");
        if (trap.target() == null || trap.targetFeet() == null || trap.targetEnvelope() == null) {
            return conflict(Row.TARGET_MOTION, "trap target envelope unknown");
        }
        Vec eye = trap.targetFeet().add(0.0, EYE_HEIGHT, 0.0);
        double distance = eye.distance(centre);
        double fraction = distance / ENTITY_SEARCH_RADIUS;
        if (fraction > 1.0 + EPSILON) return compatible();
        MotionSample sample = environment.targetMotion();
        if (sample == null) return conflict(Row.TARGET_MOTION, "target motion sample unknown");
        CollisionView collision = environment.collision();
        if (collision == null) return conflict(Row.TARGET_MOTION, "collision geometry unknown");
        double dx = eye.x() - centre.x();
        double dy = eye.y() - centre.y();
        double dz = eye.z() - centre.z();
        double length = Math.sqrt(dx * dx + dy * dy + dz * dz);
        if (!(length > EPSILON)) return conflict(Row.TARGET_MOTION, "knockback direction unknown");
        double scale = (1.0 - fraction) * KNOCKBACK_MULTIPLIER;
        MotionSample pushed = MotionSample.of(sample.x(), sample.y(), sample.z(),
            sample.vx() + dx / length * scale,
            sample.vy() + dy / length * scale,
            sample.vz() + dz / length * scale, sample.onGround());
        PredictedBox predicted = new CombatMotionModel().predict(pushed, CombatMotionModel.MAX_TICKS,
            collision);
        if (predicted.confidence() == Confidence.LOST) {
            return conflict(Row.TARGET_MOTION, "three-dimensional prediction lost");
        }
        if (!contains(trap.targetEnvelope(), predicted.hull())) {
            return conflict(Row.TARGET_MOTION, "reachable set leaves the priced target window");
        }
        return compatible();
    }

    /** Resources: a slot or rotation needed in the same tick is a wait, never a rejection. */
    private static Result resources(DirectView direct, Environment environment) {
        boolean slot = direct.needsSlot() && environment.hotbarSlotHeld();
        boolean rotation = direct.needsRotation() && environment.rotationHeld();
        if (slot || rotation) {
            return serialise(Row.RESOURCES, (slot ? "hotbar slot" : "")
                + (slot && rotation ? " and " : "") + (rotation ? "rotation" : "")
                + " needed in the same tick");
        }
        return compatible();
    }

    /** Risk: the combined direct+trap reservations must fit the safety budget. */
    private static Result risk(Environment environment) {
        if (!environment.combinedRiskFits()) {
            return conflict(Row.RISK, "combined reservations fail the safety budget");
        }
        return compatible();
    }

    // ------------------------------------------------------------------ audited blasting rules

    /** Audited blast resistance for the four blocks a trap plan can own; NaN means unknown. */
    static double resistance(String blockId) {
        if (blockId == null) return Double.NaN;
        return switch (blockId) {
            case OBSIDIAN -> OBSIDIAN_RESISTANCE;
            case PISTON -> PISTON_RESISTANCE;
            case "minecraft:redstone_torch" -> REDSTONE_TORCH_RESISTANCE;
            case "minecraft:redstone_block" -> REDSTONE_BLOCK_RESISTANCE;
            default -> Double.NaN;
        };
    }

    /**
     * Jar-exact worst case: intensity after travelling {@code distance} is
     * {@code 7.8 - 0.75 * distance}; the cell is destroyed when that still exceeds the block's own
     * {@code (resistance + 0.3) * 0.3} subtraction. Intervening blocks are not credited as shielding
     * because a curved ray path cannot be excluded without a full volume trace.
     */
    static boolean couldBreak(double distance, double resistance) {
        if (!(distance >= 0.0)) return false;
        double decayPerBlock = RAY_STEP_DECAY / RAY_STEP;
        double remaining = MAX_RAY_INTENSITY - decayPerBlock * distance;
        return remaining - (resistance + 0.3) * 0.3 > 0.0;
    }

    // ------------------------------------------------------------------ helpers

    private static Result compatible() {
        return new Result(Verdict.COMPATIBLE, Row.STRUCTURAL, "compatible");
    }

    private static Result serialise(Row row, String detail) {
        return new Result(Verdict.SERIALISE, row, detail);
    }

    private static Result conflict(Row row, String detail) {
        return new Result(Verdict.CONFLICT, row, detail);
    }

    /** Euclidean distance from a point to the nearest point of a box (zero when inside). */
    static double minDistance(Vec point, Box box) {
        double x = axisDistance(point.x(), box.min().x(), box.max().x());
        double y = axisDistance(point.y(), box.min().y(), box.max().y());
        double z = axisDistance(point.z(), box.min().z(), box.max().z());
        return Math.sqrt(x * x + y * y + z * z);
    }

    private static double axisDistance(double value, double min, double max) {
        if (value < min) return min - value;
        if (value > max) return value - max;
        return 0.0;
    }

    /** True when both corners of {@code inner} are inside {@code outer}; the model hull is axis-aligned. */
    static boolean contains(Box outer, Box inner) {
        return contains(outer, inner.min()) && contains(outer, inner.max());
    }

    private static boolean contains(Box box, Vec point) {
        return point.x() >= box.min().x() - EPSILON && point.x() <= box.max().x() + EPSILON
            && point.y() >= box.min().y() - EPSILON && point.y() <= box.max().y() + EPSILON
            && point.z() >= box.min().z() - EPSILON && point.z() <= box.max().z() + EPSILON;
    }

    private static String format(double value) {
        return String.format(java.util.Locale.ROOT, "%.2f", value);
    }
}
