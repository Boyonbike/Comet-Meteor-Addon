/*
 * Original Comet implementation by Boyonabike.
 * Independent: written from the pinned 26.2 artifact behaviour and the project's own Phase 0 traces;
 * no third-party code. Geometry/order ideas were reimplemented only from the public CrystalCombat v2.1
 * plan and audit. No BlackOut code is ported by this file.
 * SPDX-License-Identifier: CC0-1.0
 */

package dev.tntcoating.core.combat;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/**
 * Phase 5.1 pure piston geometry for the single-push trap.
 *
 * <p>Every layout is derived from measured evidence, never from a formula. The Phase 0 trace report measured
 * all sixteen piston layouts (four horizontal directions x two heights x two power kinds) at exactly
 * {@link #MEASURED_DISPLACEMENT} blocks of crystal displacement over {@link #EXTENSION_TICKS} extension
 * ticks, i.e. {@link #MEASURED_PER_TICK} blocks per tick. The plan's literal {@code 0.51 + 0.01} per tick
 * model predicts {@link #LITERAL_PLAN_DISPLACEMENT}; it is recorded here only so code and tests can prove it
 * is <em>not</em> used. A layout without a trace entry is unsupported rather than guessed.</p>
 *
 * <p>The trap shape: the source crystal sits in {@code source}, the piston directly behind it at
 * {@code source - facing}, and the power source one cell further behind at {@code source - 2*facing}. The
 * piston head extends into {@code source}, pushing the crystal {@link #MEASURED_DISPLACEMENT} along
 * {@code facing} into {@code source + facing} (the destination cell the target occupies). Power is placed
 * last and only after the crystal exists; a power source already present, a piston already powered, or a
 * power cell that would also drive a foreign piston, door or redstone component invalidates the layout.</p>
 *
 * <p>This class is pure: all world knowledge arrives through {@link World}, so tests need no client and the
 * planner can run inside the shared tick budget.</p>
 */
public final class PistonGeometry {
    /** Measured crystal displacement over the whole extension (Phase 0 trace table). */
    public static final double MEASURED_DISPLACEMENT = 1.02;
    /** Measured per-tick displacement while extending ({@code 0.51} x 2). */
    public static final double MEASURED_PER_TICK = 0.51;
    /** Extension ticks until the crystal holds its final position. */
    public static final int EXTENSION_TICKS = 2;
    /** The plan's literal model ({@code 0.51 + 0.01} per tick); authoritative traces disagree. */
    public static final double LITERAL_PLAN_DISPLACEMENT = 1.04;
    /** Tolerance for comparing a computed/observed push position with the measured rule. */
    public static final double POSITION_TOLERANCE = 0.05;
    /** Support blocks may be needed below a placed block; two is the supported maximum. */
    public static final int MAX_SUPPORT_DEPTH = 2;
    /** Maximum new structural blocks for one trap (piston + power + supports). */
    public static final int MAX_STRUCTURAL = 6;

    /** Horizontal facings in the trace order (east, north, south, west). */
    public static final PistonFacing EAST = new PistonFacing(1, 0, 0);
    public static final PistonFacing WEST = new PistonFacing(-1, 0, 0);
    public static final PistonFacing NORTH = new PistonFacing(0, 0, -1);
    public static final PistonFacing SOUTH = new PistonFacing(0, 0, 1);

    public enum PowerKind {
        REDSTONE_TORCH("minecraft:redstone_torch", "torch", false),
        REDSTONE_BLOCK("minecraft:redstone_block", "redstone", true);

        private final String itemId;
        private final String traceName;
        private final boolean solidPowerSource;

        PowerKind(String itemId, String traceName, boolean solidPowerSource) {
            this.itemId = itemId;
            this.traceName = traceName;
            this.solidPowerSource = solidPowerSource;
        }

        public String itemId() {
            return itemId;
        }

        public String traceName() {
            return traceName;
        }

        /** A block power source is a full block; a torch is attached and is not a full block. */
        public boolean solidPowerSource() {
            return solidPowerSource;
        }
    }

    /** One of the eight base layouts: a horizontal facing and a crystal height relative to the pit floor. */
    public record Layout(PistonFacing facing, int vertical) {
        public Layout {
            Objects.requireNonNull(facing, "facing");
            if (facing.vertical()) throw new IllegalArgumentException("only horizontal pushes are supported");
            if (vertical != 0 && vertical != 1) throw new IllegalArgumentException("vertical must be 0 or 1");
        }

        public String heightName() {
            return vertical == 0 ? "feet" : "eye";
        }
    }

    /** One measured trace entry: the authoritative displacement facts for a scenario. */
    public record PushTrace(String scenario, double displacement, double perTick, int ticks) {
        public PushTrace {
            Objects.requireNonNull(scenario, "scenario");
            if (!(displacement > 0.0) || !(perTick > 0.0) || ticks < 1) {
                throw new IllegalArgumentException("trace values");
            }
        }
    }

    /** One concrete push: cells, power kind, swept path and the trace-derived final crystal position. */
    public record PistonPush(Layout layout, Cell source, Cell piston, Cell extension, Cell power,
                             PowerKind powerKind, Box swept, Vec expectedPushed) {
        public PistonPush {
            Objects.requireNonNull(layout, "layout");
            Objects.requireNonNull(source, "source");
            Objects.requireNonNull(piston, "piston");
            Objects.requireNonNull(extension, "extension");
            Objects.requireNonNull(power, "power");
            Objects.requireNonNull(powerKind, "powerKind");
            Objects.requireNonNull(swept, "swept");
            Objects.requireNonNull(expectedPushed, "expectedPushed");
        }

        public PistonFacing facing() {
            return layout.facing();
        }

        public int vertical() {
            return layout.vertical();
        }

        /** The cell the crystal ends in: the pushed position is inside {@code source + facing}. */
        public Cell destination() {
            return offset(source, layout.facing());
        }

        /** Crystal centre positions for the initial tick and each measured extension tick. */
        public List<Vec> positions() {
            List<Vec> positions = new ArrayList<>(EXTENSION_TICKS + 1);
            Vec start = source.center();
            positions.add(start);
            for (int tick = 1; tick <= EXTENSION_TICKS; tick++) {
                positions.add(start.add(layout.facing().dx() * MEASURED_PER_TICK * tick,
                    layout.facing().dy() * MEASURED_PER_TICK * tick,
                    layout.facing().dz() * MEASURED_PER_TICK * tick));
            }
            return List.copyOf(positions);
        }

        /** Crystal bounding boxes along the swept path; conservative 1x1 cells. */
        public List<Box> frames() {
            List<Box> frames = new ArrayList<>();
            for (Vec position : positions()) {
                // The swept frame is centred on the measured crystal position, not on its rounded cell:
                // rounding would turn the measured 0.51/tick displacement into 1.0 per tick.
                frames.add(Box.around(position, 0.5));
            }
            return List.copyOf(frames);
        }

        public String scenario() {
            return PistonGeometry.scenario(layout, powerKind);
        }
    }

    public enum CheckStatus {
        OK,
        UNSUPPORTED_LAYOUT,
        SOURCE_CRYSTAL_MISSING,
        OCCUPIED,
        NO_PLACEMENT_FACE,
        SUPPORT_TOO_DEEP,
        STRUCTURAL_BUDGET,
        POWER_PRESENT_EARLY,
        PISTON_POWERED_EARLY,
        POWER_AFFECTS_COMPONENT,
        NO_CRYSTAL_BASE,
        WRONG_FACING,
        PATH_BLOCKED
    }

    /** Placement legality result: status plus the exact new cells (structural budget) and support chain. */
    public record Check(CheckStatus status, PistonPush push, List<Cell> structural, List<Cell> supports,
                        int supportBlocks, int maxSupportDepth) {
        public Check {
            Objects.requireNonNull(status, "status");
            Objects.requireNonNull(push, "push");
            structural = List.copyOf(structural);
            supports = List.copyOf(supports);
        }

        public boolean ok() {
            return status == CheckStatus.OK;
        }
    }

    /**
     * Read-only world view needed for placement legality. The production adapter is expected to read these
     * facts from the client's applied world snapshot; tests provide a pure fake.
     */
    public interface World {
        /** Block id at the cell, or {@code null}/"minecraft:air" when empty. */
        String blockId(Cell cell);

        /** True when a block may be placed into the cell (air, water, grass and similar). */
        boolean replaceable(Cell cell);

        /** True when the block has a sturdy full top face (a vanilla placement face). */
        boolean fullSupport(Cell cell);

        /** True when no entity bounding box overlaps the cell. */
        boolean entityFree(Cell cell);

        /** True when the cell holds the source end crystal. */
        boolean crystalPresent(Cell cell);

        /** True when the piston at the cell is currently powered. */
        boolean pistonPowered(Cell cell);

        /**
         * True when the piston at the cell has visibly extended its head. Optional observation: pure
         * geometry never needs it, and a host without piston-state access reports false (the runtime then
         * waits for the stage deadline instead of inventing progress).
         */
        default boolean pistonExtended(Cell cell) {
            return false;
        }

        /** Observed piston facing at the cell, or {@code null} when no piston is there. */
        PistonFacing pistonFacing(Cell cell);

        /** True when the block is a redstone/piston/door component a power source could activate. */
        boolean redstoneComponent(Cell cell);
    }

    private static final List<Layout> BASE_LAYOUTS = List.of(
        new Layout(EAST, 0), new Layout(NORTH, 0), new Layout(SOUTH, 0), new Layout(WEST, 0),
        new Layout(EAST, 1), new Layout(NORTH, 1), new Layout(SOUTH, 1), new Layout(WEST, 1));

    private static final Map<String, PushTrace> TRACES = buildTraces();

    private PistonGeometry() {
    }

    /** The eight base layouts: four horizontal directions x two heights, independent of power kind. */
    public static List<Layout> baseLayouts() {
        return BASE_LAYOUTS;
    }

    /** The eight concrete pushes for one power kind, anchored at the feet-height source cell. */
    public static List<PistonPush> layouts(Cell anchor, PowerKind kind) {
        Objects.requireNonNull(anchor, "anchor");
        Objects.requireNonNull(kind, "kind");
        List<PistonPush> pushes = new ArrayList<>(BASE_LAYOUTS.size());
        for (Layout layout : BASE_LAYOUTS) {
            Cell source = layout.vertical() == 0 ? anchor : anchor.above();
            pushes.add(push(layout, source, kind));
        }
        return List.copyOf(pushes);
    }

    /** Builds a push whose source cell is explicit (planner anchors at the target cell). */
    public static PistonPush fromSource(Cell source, PistonFacing facing, int vertical, PowerKind kind) {
        Objects.requireNonNull(source, "source");
        Objects.requireNonNull(facing, "facing");
        Objects.requireNonNull(kind, "kind");
        for (Layout layout : BASE_LAYOUTS) {
            if (layout.facing().equals(facing) && layout.vertical() == vertical) {
                return push(layout, source, kind);
            }
        }
        throw new IllegalArgumentException("unsupported piston layout");
    }

    /** One layout by facing/height; unsupported combinations throw rather than guessing. */
    public static PistonPush layout(Cell anchor, PistonFacing facing, int vertical, PowerKind kind) {
        for (PistonPush push : layouts(anchor, kind)) {
            if (push.facing().equals(facing) && push.vertical() == vertical) return push;
        }
        throw new IllegalArgumentException("unsupported piston layout");
    }

    /** The fixture-name form: {@code piston_<dir>_<height>_<power>}. */
    public static String scenario(Layout layout, PowerKind kind) {
        String direction = layout.facing().equals(EAST) ? "east"
            : layout.facing().equals(WEST) ? "west"
            : layout.facing().equals(SOUTH) ? "south" : "north";
        return "piston_" + direction + "_" + layout.heightName() + "_" + kind.traceName();
    }

    /** Authoritative trace entry; empty means the layout is unsupported, never guessed. */
    public static Optional<PushTrace> trace(String scenario) {
        return Optional.ofNullable(TRACES.get(scenario));
    }

    public static boolean hasTraceEvidence(String scenario) {
        return TRACES.containsKey(scenario);
    }

    /** True when an observed displacement matches the measured rule inside the cross-check tolerance. */
    public static boolean matchesTrace(double observedDisplacement, double expectedDisplacement) {
        return Math.abs(observedDisplacement - expectedDisplacement) <= POSITION_TOLERANCE;
    }

    public static boolean withinStructuralBudget(int structuralBlocks) {
        return structuralBlocks >= 0 && structuralBlocks <= MAX_STRUCTURAL;
    }

    /** Full readiness legality for one concrete push that must already have its source crystal. */
    public static Check check(World world, PistonPush push) {
        return check(world, push, true);
    }

    /** Placement legality for a push whose crystal is about to be placed (source must be empty). */
    public static Check checkPlacement(World world, PistonPush push) {
        return check(world, push, false);
    }

    private static Check check(World world, PistonPush push, boolean crystalReady) {
        Objects.requireNonNull(world, "world");
        Objects.requireNonNull(push, "push");
        if (push.facing().vertical()) return failed(CheckStatus.UNSUPPORTED_LAYOUT, push);
        String scenario = push.scenario();
        if (scenario == null || !TRACES.containsKey(scenario)) {
            return failed(CheckStatus.UNSUPPORTED_LAYOUT, push);
        }
        if (crystalReady && !world.crystalPresent(push.source())) {
            return failed(CheckStatus.SOURCE_CRYSTAL_MISSING, push);
        }
        if (!crystalReady && !world.replaceable(push.source())) {
            return failed(CheckStatus.OCCUPIED, push);
        }
        // The end crystal is a two-block-tall entity volume; both cells must be clear before placement.
        if (!world.entityFree(push.source()) || !world.entityFree(push.source().above())) {
            return failed(CheckStatus.OCCUPIED, push);
        }
        // Vanilla only accepts obsidian/bedrock as a crystal base. An existing crystal implies the base is
        // already there; a placement may put the base down inside the support-depth budget.
        Cell crystalBase = push.source().below();
        boolean baseExists = isCrystalBase(world.blockId(crystalBase));
        if (crystalReady && !baseExists) return failed(CheckStatus.NO_CRYSTAL_BASE, push);
        Cell destination = push.destination();
        if (!world.replaceable(destination)) return failed(CheckStatus.PATH_BLOCKED, push);
        // Retraction must never drag the pushed crystal back through the target: the destination cell is the
        // final resting place, and a solid block there would make vanilla resolve the push differently.
        String pistonBlock = world.blockId(push.piston());
        boolean pistonExists = "minecraft:piston".equals(pistonBlock)
            || "minecraft:sticky_piston".equals(pistonBlock);
        if (pistonExists) {
            PistonFacing observed = world.pistonFacing(push.piston());
            if (!push.facing().equals(observed)) return failed(CheckStatus.WRONG_FACING, push);
        } else {
            if (!world.replaceable(push.piston()) || !world.entityFree(push.piston())) {
                return failed(CheckStatus.OCCUPIED, push);
            }
        }
        String powerBlock = world.blockId(push.power());
        if (powerBlock != null && isPowerSource(powerBlock)) {
            return failed(CheckStatus.POWER_PRESENT_EARLY, push);
        }
        if (!world.replaceable(push.power()) || !world.entityFree(push.power())) {
            return failed(CheckStatus.OCCUPIED, push);
        }
        if (world.pistonPowered(push.piston())) {
            return failed(CheckStatus.PISTON_POWERED_EARLY, push);
        }
        // Power must drive only this piston: any other component adjacent to the power cell is rejected.
        if (world.pistonFacing(push.power()) != null && !push.power().equals(push.piston())) {
            return failed(CheckStatus.POWER_AFFECTS_COMPONENT, push);
        }
        for (Cell neighbor : neighbors(push.power())) {
            if (neighbor.equals(push.piston())) continue;
            if (world.redstoneComponent(neighbor)) {
                return failed(CheckStatus.POWER_AFFECTS_COMPONENT, push);
            }
        }
        List<Cell> supports = new ArrayList<>();
        int baseDepth = 0;
        if (!baseExists) {
            if (!world.replaceable(crystalBase) || !world.entityFree(crystalBase)) {
                return failed(CheckStatus.NO_CRYSTAL_BASE, push);
            }
            supports.add(crystalBase);
            int belowBase = supportDepth(world, crystalBase, supports, false);
            if (belowBase < 0) return failed(CheckStatus.NO_CRYSTAL_BASE, push);
            baseDepth = 1 + belowBase;
        }
        int pistonDepth = supportDepth(world, push.piston(), supports, pistonExists);
        if (pistonDepth < 0) return failed(CheckStatus.NO_PLACEMENT_FACE, push);
        int powerDepth = supportDepth(world, push.power(), supports, false);
        if (powerDepth < 0) return failed(CheckStatus.NO_PLACEMENT_FACE, push);
        int depth = Math.max(Math.max(pistonDepth, powerDepth), baseDepth);
        if (depth > MAX_SUPPORT_DEPTH) return failed(CheckStatus.SUPPORT_TOO_DEEP, push);
        for (Cell support : supports) {
            if (!world.replaceable(support) || !world.entityFree(support)) {
                return failed(CheckStatus.OCCUPIED, push);
            }
        }
        Set<Cell> structural = new LinkedHashSet<>();
        if (!pistonExists) structural.add(push.piston());
        structural.add(push.power());
        structural.addAll(supports);
        if (!withinStructuralBudget(structural.size())) {
            return failed(CheckStatus.STRUCTURAL_BUDGET, push);
        }
        int supportBlocks = supports.size();
        return new Check(CheckStatus.OK, push, List.copyOf(structural), List.copyOf(supports),
            supportBlocks, depth);
    }

    // ------------------------------------------------------------------ internals

    private static PistonPush push(Layout layout, Cell source, PowerKind kind) {
        PistonFacing facing = layout.facing();
        Cell piston = offset(source, facing, -1);
        Cell power = offset(source, facing, -2);
        Vec start = source.center();
        Vec end = start.add(facing.dx() * MEASURED_DISPLACEMENT,
            facing.dy() * MEASURED_DISPLACEMENT, facing.dz() * MEASURED_DISPLACEMENT);
        Box swept = Box.fromCell(source).expand(MEASURED_DISPLACEMENT + 0.5);
        return new PistonPush(layout, source, piston, source, power, kind, swept, end);
    }

    /** Walks down from a placed block; returns the needed support depth, or -1 when blocked. */
    private static int supportDepth(World world, Cell placed, List<Cell> supports, boolean alreadyPlaced) {
        if (alreadyPlaced) return 0;
        int depth = 0;
        Cell cursor = placed.below();
        while (true) {
            if (world.fullSupport(cursor)) return depth;
            if (!world.replaceable(cursor)) return -1;
            if (depth == MAX_SUPPORT_DEPTH) return MAX_SUPPORT_DEPTH + 1; // too deep, caller rejects
            supports.add(cursor);
            depth++;
            cursor = cursor.below();
        }
    }

    private static boolean isCrystalBase(String blockId) {
        return "minecraft:obsidian".equals(blockId) || "minecraft:bedrock".equals(blockId);
    }

    private static boolean isPowerSource(String blockId) {
        return "minecraft:redstone_torch".equals(blockId) || "minecraft:redstone_block".equals(blockId)
            || "minecraft:lever".equals(blockId) || "minecraft:redstone_wire".equals(blockId)
            || "minecraft:repeater".equals(blockId) || "minecraft:comparator".equals(blockId);
    }

    static Cell offset(Cell cell, PistonFacing facing) {
        return offset(cell, facing, 1);
    }

    static Cell offset(Cell cell, PistonFacing facing, int steps) {
        return new Cell(cell.x() + facing.dx() * steps, cell.y() + facing.dy() * steps,
            cell.z() + facing.dz() * steps);
    }

    private static List<Cell> neighbors(Cell cell) {
        return List.of(cell.above(), cell.below(), new Cell(cell.x() + 1, cell.y(), cell.z()),
            new Cell(cell.x() - 1, cell.y(), cell.z()), new Cell(cell.x(), cell.y(), cell.z() + 1),
            new Cell(cell.x(), cell.y(), cell.z() - 1));
    }

    private static Check failed(CheckStatus status, PistonPush push) {
        return new Check(status, push, List.of(), List.of(), 0, 0);
    }

    private static Map<String, PushTrace> buildTraces() {
        Map<String, PushTrace> traces = new java.util.HashMap<>();
        for (PowerKind kind : PowerKind.values()) {
            for (Layout layout : BASE_LAYOUTS) {
                String scenario = scenario(layout, kind);
                traces.put(scenario, new PushTrace(scenario, MEASURED_DISPLACEMENT, MEASURED_PER_TICK,
                    EXTENSION_TICKS));
            }
        }
        return Map.copyOf(traces);
    }

    /** Convenience for callers that only know a scenario name: the matching push at the given anchor. */
    public static Optional<PistonPush> pushForScenario(Cell anchor, String scenario) {
        for (PowerKind kind : PowerKind.values()) {
            for (PistonPush push : layouts(anchor, kind)) {
                if (push.scenario().equals(scenario)) return Optional.of(push);
            }
        }
        return Optional.empty();
    }

    /** The destination cell for a source and facing (the cell the pushed crystal occupies). */
    public static Cell destinationOf(Cell source, PistonFacing facing) {
        return offset(source, facing);
    }
}
