package dev.tntcoating.core;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.PriorityQueue;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

/**
 * Plans a short path through a highway cross-section.
 *
 * <p>The grid is aligned with the flight direction: a few lateral cells across the highway, a few
 * vertical levels, and a bounded forward horizon. Every grid point is checked with the player's
 * real collision box and every edge is swept through {@link FlightCorridor}. The search is only
 * called when the direct line is blocked, and it never loads chunks.</p>
 */
public final class HighwayPathfinder {
    // Shared by both passes. Exhaustion fails closed: it never certifies a chunk frontier.
    private static final int MAX_PROBE_CALLS = 120_000;
    private static final int MAX_EXPANDED_NODES = 8_000;
    private static final double EPSILON = 1e-7;
    private static final double INFINITY = Double.MAX_VALUE;
    private static final double VERTICAL_PENALTY = 3.0;
    private static final double TIGHT_PENALTY = 5.0;
    private static final double CLEARANCE_UP = .75;
    private static final double CLEARANCE_SIDE = .2;

    private HighwayPathfinder() {}

    public record Config(int width, int height, int forwardSteps, double forwardStep,
                         double lateralStep, double verticalStep, double minY, double maxY) {
        public Config {
            if (width < 3 || width > 9) throw new IllegalArgumentException("Width must be in [3, 9].");
            if (height < 2 || height > 5) throw new IllegalArgumentException("Height must be in [2, 5].");
            if (forwardSteps < 4 || forwardSteps > 64) {
                throw new IllegalArgumentException("Forward steps must be in [4, 64].");
            }
            requirePositive(forwardStep, "Forward step");
            requirePositive(lateralStep, "Lateral step");
            requirePositive(verticalStep, "Vertical step");
            if (!Double.isFinite(minY) || !Double.isFinite(maxY) || minY > maxY) {
                throw new IllegalArgumentException("Y range is invalid.");
            }
        }
    }

    public enum Reason { FOUND, BLOCKED, CHUNKS, INVALID }

    public record Path(List<Vec3> points, Reason reason, int progress, boolean complete) {
        /** Manually supplied FOUND paths remain complete for existing callers. */
        public Path(List<Vec3> points, Reason reason, int progress) {
            this(points, reason, progress, reason == Reason.FOUND);
        }

        public Path {
            points = List.copyOf(points);
        }

        public boolean found() {
            return (reason == Reason.FOUND || reason == Reason.CHUNKS) && points.size() > 1;
        }
    }

    private record QueueNode(int index, double score) {}

    public static Path find(AABB body, Vec3 position, Vec3 direction, double preferredY, Config config,
                            FlightCorridor.Probe probe) {
        Objects.requireNonNull(body, "body");
        Objects.requireNonNull(position, "position");
        Objects.requireNonNull(direction, "direction");
        Objects.requireNonNull(config, "config");
        Objects.requireNonNull(probe, "probe");
        if (!finite(position) || !finite(direction) || !Double.isFinite(preferredY)) {
            return new Path(List.of(), Reason.INVALID, 0);
        }

        double horizontal = Math.hypot(direction.x, direction.z);
        if (horizontal < EPSILON) return new Path(List.of(), Reason.INVALID, 0);
        Vec3 forward = new Vec3(direction.x / horizontal, 0, direction.z / horizontal);
        Vec3 right = new Vec3(-forward.z, 0, forward.x);

        BudgetProbe bounded = new BudgetProbe(probe);
        Grid coarse = Grid.of(config, 1, 1);
        Path first = search(body, position, forward, right, preferredY, coarse, bounded);
        if (first.complete() || first.reason() == Reason.INVALID || bounded.exhausted) return first;
        int lateral = subdivisions(config.lateralStep());
        int vertical = subdivisions(config.verticalStep());
        if (lateral == 1 && vertical == 1) return first;
        Path refined = search(body, position, forward, right, preferredY,
            Grid.of(config, lateral, vertical), bounded);
        if (refined.complete() || refined.progress() >= first.progress()) return refined;
        // The finer search can exhaust its budget before matching the coarse progress.
        // Its incomplete result cannot validate the old path as chunk limited.
        return new Path(first.points(), Reason.FOUND, first.progress(), false);
    }

    private static Path search(AABB body, Vec3 position, Vec3 forward, Vec3 right,
                               double preferredY, Grid config, BudgetProbe probe) {
        int width = config.width();
        int height = config.height();
        int forwardSteps = config.forwardSteps();
        int centreJ = config.centreJ();
        int centreK = config.centreK();
        double altitudeBand = config.altitudeBand();
        int count = (forwardSteps + 1) * width * height;

        double[] cost = new double[count];
        Arrays.fill(cost, INFINITY);
        int[] parent = new int[count];
        Arrays.fill(parent, -1);
        boolean[] closed = new boolean[count];
        FlightCorridor.Reason[] valid = new FlightCorridor.Reason[count];

        int start = index(0, centreJ, centreK, width, height);
        FlightCorridor.Result startResult = FlightCorridor.inspectStart(body, probe);
        if (startResult.reason() != FlightCorridor.Reason.CLEAR) {
            return new Path(List.of(), toReason(startResult.reason()), 0);
        }

        PriorityQueue<QueueNode> open = new PriorityQueue<>((a, b) -> Double.compare(a.score, b.score));
        cost[start] = 0;
        open.add(new QueueNode(start, heuristic(0, forwardSteps, config.forwardStep())));
        boolean frontierChunks = false;
        int expanded = 0;
        int best = start;
        int bestI = 0;

        while (!open.isEmpty() && !probe.exhausted && expanded < MAX_EXPANDED_NODES) {
            QueueNode current = open.poll();
            int index = current.index;
            if (closed[index]) continue;
            closed[index] = true;
            expanded++;

            int i = iOf(index, width, height);
            if (i > bestI) {
                bestI = i;
                best = index;
                frontierChunks = false;
            }
            if (i >= forwardSteps) break;

            Vec3 fromPoint = point(index, position, forward, right, centreJ, centreK, config);
            AABB fromBody = body.move(fromPoint.subtract(position));
            // Alignment moves within a slice let us approach nearby gaps from the side
            // or climb before crossing a low wall. Positive costs and closed nodes bound the search.
            for (int di = 0; di <= 1; di++) {
                for (int dj = -1; dj <= 1; dj++) {
                    for (int dk = -1; dk <= 1; dk++) {
                        int j = jOf(index, width);
                        int k = kOf(index, width, height);
                        int nj = j + dj;
                        int nk = k + dk;
                        int ni = i + di;
                        if (di == 0 && dj == 0 && dk == 0) continue;
                        if (nj < 0 || nj >= width || nk < 0 || nk >= height || ni > forwardSteps) continue;

                        int next = index(ni, nj, nk, width, height);
                        if (closed[next]) continue;
                        Vec3 toPoint = point(next, position, forward, right, centreJ, centreK, config);
                        if (toPoint.y < config.minY() - EPSILON || toPoint.y > config.maxY() + EPSILON) continue;
                        // Keep the local path near the chosen cruise altitude. Without this the
                        // grid can chain a descent under the highway and keep going there.
                        if (Math.abs(toPoint.y - preferredY) > altitudeBand + EPSILON) continue;

                        if (valid[next] == null) {
                            valid[next] = FlightCorridor.inspectStart(body.move(toPoint.subtract(position)), probe).reason();
                        }
                        if (valid[next] != FlightCorridor.Reason.CLEAR) {
                            if (di == 1 && i == bestI) {
                                if (valid[next] == FlightCorridor.Reason.CHUNKS) frontierChunks = true;
                            }
                            continue;
                        }

                        Vec3 delta = toPoint.subtract(fromPoint);
                        FlightCorridor.Result edge = FlightCorridor.inspect(fromBody, delta, delta.length(), probe);
                        if (edge.reason() != FlightCorridor.Reason.CLEAR) {
                            if (di == 1 && i == bestI) {
                                if (edge.reason() == FlightCorridor.Reason.CHUNKS) frontierChunks = true;
                            }
                            continue;
                        }

                        AABB nextBody = body.move(toPoint.subtract(position));
                        boolean tight = FlightCorridor.inspectStart(nextBody.expandTowards(0, CLEARANCE_UP, 0)
                            .inflate(CLEARANCE_SIDE, 0, CLEARANCE_SIDE), probe).reason() != FlightCorridor.Reason.CLEAR;
                        double step = delta.length()
                            + Math.abs(dj) * config.lateralStep() * .5
                            + Math.abs(dk) * config.verticalStep() * VERTICAL_PENALTY
                            + (tight ? TIGHT_PENALTY : 0);
                        double nextCost = cost[index] + step;
                        if (nextCost + EPSILON >= cost[next]) continue;

                        cost[next] = nextCost;
                        parent[next] = index;
                        open.add(new QueueNode(next,
                            nextCost + heuristic(ni, forwardSteps, config.forwardStep())));
                    }
                }
            }
        }

        boolean complete = bestI == forwardSteps;
        // A blocked alternative cannot invalidate a reachable unknown continuation.
        // Only forward edges on the furthest reached slice count, not earlier side branches.
        boolean chunkLimited = !complete && !probe.exhausted && open.isEmpty()
            && frontierChunks;
        if (best == start || bestI == 0) {
            return new Path(List.of(), chunkLimited ? Reason.CHUNKS : Reason.BLOCKED, 0, false);
        }

        List<Vec3> reversed = new ArrayList<>();
        for (int cursor = best; cursor != -1; cursor = parent[cursor]) {
            reversed.add(point(cursor, position, forward, right, centreJ, centreK, config));
        }
        Collections.reverse(reversed);
        return new Path(reversed, chunkLimited ? Reason.CHUNKS : Reason.FOUND, bestI, complete);
    }

    private static Vec3 point(int index, Vec3 position, Vec3 forward, Vec3 right,
                              int centreJ, int centreK, Grid config) {
        int width = config.width();
        int height = config.height();
        int i = iOf(index, width, height);
        int j = jOf(index, width);
        int k = kOf(index, width, height);
        return position
            .add(forward.scale(i * config.forwardStep()))
            .add(right.scale((j - centreJ) * config.lateralStep()))
            .add(0, (k - centreK) * config.verticalStep(), 0);
    }

    private static int subdivisions(double step) {
        return Math.min(4, Math.max(1, (int) Math.ceil(step / .25)));
    }

    /** Subdivision preserves the original asymmetric bounds for even grid sizes. */
    private record Grid(int width, int height, int forwardSteps, double forwardStep,
                        double lateralStep, double verticalStep, double minY, double maxY,
                        int centreJ, int centreK, double altitudeBand) {
        static Grid of(Config source, int lateral, int vertical) {
            return new Grid((source.width() - 1) * lateral + 1,
                (source.height() - 1) * vertical + 1, source.forwardSteps(), source.forwardStep(),
                source.lateralStep() / lateral, source.verticalStep() / vertical,
                source.minY(), source.maxY(), (source.width() / 2) * lateral,
                (source.height() / 2) * vertical,
                Math.max(source.verticalStep(), (source.height() - 1) * source.verticalStep() * .5));
        }
    }

    private static final class BudgetProbe implements FlightCorridor.Probe {
        private final FlightCorridor.Probe delegate;
        private int calls;
        private boolean exhausted;

        BudgetProbe(FlightCorridor.Probe delegate) { this.delegate = delegate; }

        private boolean acquire() {
            if (calls >= MAX_PROBE_CALLS) {
                exhausted = true;
                return false;
            }
            calls++;
            return true;
        }

        @Override public boolean loaded(int x, int z) { return acquire() && delegate.loaded(x, z); }
        @Override public boolean clear(AABB box) { return acquire() && delegate.clear(box); }
    }

    private static int index(int i, int j, int k, int width, int height) {
        return (i * height + k) * width + j;
    }

    private static int iOf(int index, int width, int height) {
        return index / (width * height);
    }

    private static int kOf(int index, int width, int height) {
        return (index / width) % height;
    }

    private static int jOf(int index, int width) {
        return index % width;
    }

    private static double heuristic(int i, int forward, double step) {
        return (forward - i) * step;
    }

    private static Reason toReason(FlightCorridor.Reason reason) {
        return switch (reason) {
            case CLEAR -> Reason.FOUND;
            case CHUNKS -> Reason.CHUNKS;
            case OBSTACLE -> Reason.BLOCKED;
            case INVALID -> Reason.INVALID;
        };
    }

    private static boolean finite(Vec3 value) {
        return Double.isFinite(value.x) && Double.isFinite(value.y) && Double.isFinite(value.z);
    }

    private static void requirePositive(double value, String name) {
        if (!Double.isFinite(value) || value <= 0) {
            throw new IllegalArgumentException(name + " must be positive and finite.");
        }
    }
}
