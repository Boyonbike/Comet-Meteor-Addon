package dev.tntcoating.core.scaffold;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.LinkedHashMap;
import java.util.function.Predicate;

/** Unit and ownership boundaries between Minecraft's tick-based motion and the pure planner. */
public final class ScaffoldAdapter {
    private ScaffoldAdapter() {}
    public record DispatchContext(long generation, long tick, Object world, Object player, int slot, String settings) {
        public boolean current(long generation, long tick, Object world, Object player, int slot, String settings) {
            return this.generation == generation && this.tick == tick && this.world == world && this.player == player
                && this.slot == slot && this.settings.equals(settings);
        }
    }
    public static ScaffoldPlanner.Snapshot snapshot(double x, double z, double eyeY, int floorY,
        double velocityXPerTick, double velocityZPerTick, double directionX, double directionZ,
        double halfWidth, double reach, long now, long horizon) {
        return new ScaffoldPlanner.Snapshot(x, z, eyeY, floorY, velocityXPerTick * 20, velocityZPerTick * 20,
            directionX, directionZ, halfWidth, reach, now, horizon);
    }
    public static List<Integer> scanOrder(int count, int start) {
        List<Integer> order = new ArrayList<>(count);
        for (int i = 0; i < count; i++) order.add(Math.floorMod(start + i, count));
        return List.copyOf(order);
    }
    public static int searchBudget(int phase, int used) {
        return Math.max(0, Math.min(phase == 2 ? 126 : 48, 126 - used));
    }
    public static final class Layers {
        private final Map<ScaffoldPlanner.Cell, ScaffoldPlanner.Candidate> bases = new LinkedHashMap<>();
        public void reset() { bases.clear(); }
        public void replace(List<ScaffoldPlanner.Candidate> desired, int floorY, Predicate<ScaffoldPlanner.Cell> inEnvelope) {
            reset();
            remember(desired, floorY, inEnvelope);
        }
        public void remember(List<ScaffoldPlanner.Candidate> desired, int floorY, Predicate<ScaffoldPlanner.Cell> inEnvelope) {
            bases.entrySet().removeIf(e -> e.getKey().y() != floorY || !inEnvelope.test(e.getKey()));
            for (var base : desired) if (base.cell().y() == floorY && inEnvelope.test(base.cell())) bases.put(base.cell(), base);
        }
        public List<ScaffoldPlanner.Candidate> expand(List<ScaffoldPlanner.Candidate> fresh, int floorY,
            int layers, boolean upward, Predicate<ScaffoldPlanner.Cell> inEnvelope) {
            if (layers <= 1) { reset(); return fresh; }
            if (layers > 3) throw new IllegalArgumentException("layers");
            remember(fresh, floorY, inEnvelope);
            List<ScaffoldPlanner.Candidate> result = new ArrayList<>(fresh);
            for (var base : bases.values()) for (int layer = 1; layer < layers; layer++) {
                var cell = base.cell();
                result.add(new ScaffoldPlanner.Candidate(new ScaffoldPlanner.Cell(cell.x(), cell.y() + layer * (upward ? 1 : -1), cell.z()),
                    2, Long.MAX_VALUE, base.lateral(), base.firstSeenNanos()));
            }
            return List.copyOf(result);
        }
    }
}
