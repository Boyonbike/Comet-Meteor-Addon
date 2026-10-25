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

    public record Path(List<Vec3> points, Reason reason, int progress) {
        public Path {
            points = List.copyOf(points);
        }

        public boolean found() {
            return reason == Reason.FOUND && points.size() > 1;
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

        int width = config.width();
        int height = config.height();
        int forwardSteps = config.forwardSteps();
        int centreJ = width / 2;
        int centreK = height / 2;
        double altitudeBand = Math.max(config.verticalStep(),
            (height - 1) * config.verticalStep() * .5);
        int count = (forwardSteps + 1) * width * height;

        double[] cost = new double[count];
        Arrays.fill(cost, INFINITY);
        int[] parent = new int[count];
        Arrays.fill(parent, -1);
        boolean[] closed = new boolean[count];
        Boolean[] valid = new Boolean[count];

        int start = index(0, centreJ, centreK, width, height);
        FlightCorridor.Result startResult = FlightCorridor.inspectStart(body, probe);
        if (startResult.reason() != FlightCorridor.Reason.CLEAR) {
            return new Path(List.of(), toReason(startResult.reason()), 0);
        }

        PriorityQueue<QueueNode> open = new PriorityQueue<>((a, b) -> Double.compare(a.score, b.score));
        cost[start] = 0;
        open.add(new QueueNode(start, heuristic(0, forwardSteps, config.forwardStep())));
        boolean sawChunks = false;
        int best = start;
        int bestI = 0;

        while (!open.isEmpty()) {
            QueueNode current = open.poll();
            int index = current.index;
            if (closed[index]) continue;
            closed[index] = true;

            int i = iOf(index, width, height);
            if (i > bestI) {
                bestI = i;
                best = index;
            }
            if (i >= forwardSteps) break;

            Vec3 fromPoint = point(index, position, forward, right, centreJ, centreK, config);
            AABB fromBody = body.move(fromPoint.subtract(position));
            for (int dj = -1; dj <= 1; dj++) {
                for (int dk = -1; dk <= 1; dk++) {
                    int j = jOf(index, width);
                    int k = kOf(index, width, height);
                    int nj = j + dj;
                    int nk = k + dk;
                    int ni = i + 1;
                    if (nj < 0 || nj >= width || nk < 0 || nk >= height || ni > forwardSteps) continue;

                    int next = index(ni, nj, nk, width, height);
                    if (closed[next]) continue;
                    Vec3 toPoint = point(next, position, forward, right, centreJ, centreK, config);
                    if (toPoint.y < config.minY() - EPSILON || toPoint.y > config.maxY() + EPSILON) continue;
                    // Keep the local path near the chosen cruise altitude. Without this the
                    // grid can chain a descent under the highway and keep going there.
                    if (Math.abs(toPoint.y - preferredY) > altitudeBand + EPSILON) continue;

                    if (valid[next] == null) {
                        valid[next] = FlightCorridor.inspectStart(body.move(toPoint.subtract(position)), probe)
                            .reason() == FlightCorridor.Reason.CLEAR;
                    }
                    if (!valid[next]) continue;

                    Vec3 delta = toPoint.subtract(fromPoint);
                    FlightCorridor.Result edge = FlightCorridor.inspect(fromBody, delta, delta.length(), probe);
                    if (edge.reason() != FlightCorridor.Reason.CLEAR) {
                        if (edge.reason() == FlightCorridor.Reason.CHUNKS) sawChunks = true;
                        continue;
                    }

                    AABB nextBody = body.move(toPoint.subtract(position));
                    boolean tight = !probe.clear(nextBody.expandTowards(0, CLEARANCE_UP, 0)
                        .inflate(CLEARANCE_SIDE, 0, CLEARANCE_SIDE));
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

        if (best == start || bestI == 0) {
            return new Path(List.of(), sawChunks ? Reason.CHUNKS : Reason.BLOCKED, 0);
        }

        List<Vec3> reversed = new ArrayList<>();
        for (int cursor = best; cursor != -1; cursor = parent[cursor]) {
            reversed.add(point(cursor, position, forward, right, centreJ, centreK, config));
        }
        Collections.reverse(reversed);
        return new Path(reversed, Reason.FOUND, bestI);
    }

    private static Vec3 point(int index, Vec3 position, Vec3 forward, Vec3 right,
                              int centreJ, int centreK, Config config) {
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
