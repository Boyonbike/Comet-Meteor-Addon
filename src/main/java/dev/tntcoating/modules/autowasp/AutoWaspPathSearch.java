/*
 * Adapted from DEVILS by ThianYong-hub and DEVILS contributors.
 * Upstream: https://github.com/ThianYong-hub/DEVILS
 * Revision: 2440a168c3ba6f051f2adf8249a8e6fb5f3a5b02
 * Auto Wasp is also based on Meteor Client, Copyright (c) Meteor Development.
 * SPDX-License-Identifier: GPL-3.0-only
 *
 * Modified for Comet by Boyonabike on 2026-10-22: Minecraft 26.1.2 mappings,
 * local inventory integration, lifecycle/navigation guards and settings credits;
 * per-search node memos and a time budget that returns the best partial route.
 * Distributed under GNU GPL v3; see LICENSE-DEVILS and THIRD_PARTY_NOTICES.md.
 * This program is distributed WITHOUT ANY WARRANTY; without even the implied
 * warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
 */

package dev.tntcoating.modules.autowasp;

import dev.tntcoating.modules.AutoWasp;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.util.Mth;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.PriorityQueue;
import java.util.Set;

final class AutoWaspPathSearch {
    static final Direction[] CARDINAL_DIRECTIONS = {
        Direction.NORTH, Direction.SOUTH, Direction.WEST, Direction.EAST
    };
    static final int PATH_UPDATE_TICKS = 14;
    static final int PATH_FORCE_REFRESH_TICKS = 32;

    private static final NeighborStep[] NEIGHBOR_STEPS = createNeighborSteps();
    private static final int PATH_MAX_NODES = 3500;
    private static final int PATH_RESOLUTION = 1;
    private static final double FLOOR_CLEARANCE = 2.2;
    private static final double CEILING_CLEARANCE = 1.5;
    private static final int SAFETY_SCAN = 14;
    private static final double NETHER_MAX_FLIGHT_Y = 123.0;
    // A search runs on the client thread; past this it returns its best partial route, so a long tick cannot
    // bunch movement packets together for the server.
    static final long SEARCH_BUDGET_NANOS = 4_000_000L;
    private static final int BUDGET_CHECK_NODES = 32;

    private final AutoWasp module;
    private final AutoWaspPathfinder pathfinder;
    // Within one search the world cannot change, so each node is judged once.
    private final Map<Long, Boolean> flyableNodes = new HashMap<>();
    private final Map<Long, Double> nodePenalties = new HashMap<>();
    private long deadline;

    AutoWaspPathSearch(AutoWasp module, AutoWaspPathfinder pathfinder) {
        this.module = module;
        this.pathfinder = pathfinder;
    }

    List<Vec3> computePath(Vec3 start, Vec3 goal) {
        flyableNodes.clear();
        nodePenalties.clear();
        deadline = System.nanoTime() + SEARCH_BUDGET_NANOS;
        try {
            return search(start, goal);
        } finally {
            flyableNodes.clear();
            nodePenalties.clear();
        }
    }

    private List<Vec3> search(Vec3 start, Vec3 goal) {
        Vec3 clampedStart = pathfinder.clampToDimensionFlightCap(start);
        Vec3 clampedGoal = pathfinder.clampToDimensionFlightCap(goal);
        BlockPos startBlock = pathfinder.clampToDimensionFlightCap(toGrid(BlockPos.containing(clampedStart), PATH_RESOLUTION));
        BlockPos goalBlock = pathfinder.clampToDimensionFlightCap(toGrid(BlockPos.containing(clampedGoal), PATH_RESOLUTION));

        startBlock = findNearestPassable(startBlock, PATH_RESOLUTION, 10);
        goalBlock = findNearestPassable(goalBlock, PATH_RESOLUTION, 10);
        if (startBlock == null || goalBlock == null) return new ArrayList<>();

        Vec3 safeGoal = pathfinder.adjustToSafeCorridor(clampedGoal, clampedStart);
        if (pathfinder.isDirectPathClear(clampedStart, safeGoal)) {
            ArrayList<Vec3> direct = new ArrayList<>();
            direct.add(safeGoal);
            return direct;
        }

        SearchResult bestResult = null;
        for (int attempt = 0; attempt < 2; attempt++) {
            if (attempt > 0 && outOfTime()) break;
            SearchResult result = searchPath(startBlock, goalBlock, safeGoal, createSearchBounds(startBlock, goalBlock, attempt));
            if (result.path != null && result.complete) return result.path;
            if (bestResult == null || result.bestGoalDistance < bestResult.bestGoalDistance) bestResult = result;
        }

        return bestResult != null && bestResult.path != null ? bestResult.path : new ArrayList<>();
    }

    private boolean outOfTime() {
        return System.nanoTime() - deadline > 0;
    }

    private double nodePenalty(BlockPos node) {
        Double known = nodePenalties.get(node.asLong());
        if (known != null) return known;
        double penalty = computeNodePenalty(node);
        nodePenalties.put(node.asLong(), penalty);
        return penalty;
    }

    private double computeNodePenalty(BlockPos node) {
        Vec3 center = Vec3.atCenterOf(node);
        double floorDist = pathfinder.distanceToSolidBelow(center, SAFETY_SCAN);
        double ceilingDist = pathfinder.distanceToSolidAbove(center, SAFETY_SCAN);
        int nearOpen = pathfinder.countLateralOpenings(center, 0.9);
        int farOpen = pathfinder.countLateralOpenings(center, 1.6);

        double penalty = 0;
        if (floorDist < FLOOR_CLEARANCE) penalty += (FLOOR_CLEARANCE - floorDist) * 2.8;
        if (ceilingDist < CEILING_CLEARANCE) penalty += (CEILING_CLEARANCE - ceilingDist) * 2.8;
        if (floorDist > 10) penalty += 0.35;
        if (nearOpen <= 1) penalty += 2.2;
        if (farOpen <= 1) penalty += 1.4;
        return penalty + narrowSpacePenalty(center);
    }

    private double narrowSpacePenalty(Vec3 center) {
        int openSides = pathfinder.countLateralOpenings(center, 1.0);
        return Math.max(0, 4 - openSides) * 0.5;
    }

    private double turnPenalty(AStarNode current, BlockPos neighbor) {
        if (current.parent == null) return 0;

        int ax = current.pos.getX() - current.parent.pos.getX();
        int ay = current.pos.getY() - current.parent.pos.getY();
        int az = current.pos.getZ() - current.parent.pos.getZ();
        int bx = neighbor.getX() - current.pos.getX();
        int by = neighbor.getY() - current.pos.getY();
        int bz = neighbor.getZ() - current.pos.getZ();

        double aLen = Math.sqrt(ax * ax + ay * ay + az * az);
        double bLen = Math.sqrt(bx * bx + by * by + bz * bz);
        if (aLen < 1.0E-6 || bLen < 1.0E-6) return 0;

        double dot = (ax * bx + ay * by + az * bz) / (aLen * bLen);
        return (1.0 - dot) * 0.2;
    }

    private List<Vec3> reconstructPath(AStarNode endNode, Vec3 finalGoal) {
        List<Vec3> path = new ArrayList<>();
        AStarNode node = endNode;
        while (node != null) {
            path.add(pathfinder.adjustToSafeCorridor(Vec3.atCenterOf(node.pos), module.client().player != null ? module.client().player.position() : null));
            node = node.parent;
        }

        java.util.Collections.reverse(path);
        if (!path.isEmpty() && module.client().player != null) {
            double reachSq = 4.0;
            if (module.client().player.position().distanceToSqr(path.get(0)) <= reachSq) path.remove(0);
        }

        Vec3 safeGoal = pathfinder.adjustToSafeCorridor(finalGoal, module.client().player != null ? module.client().player.position() : null);
        appendReachableGoal(path, safeGoal, pathfinder::isDirectPathClear);
        return simplifyPath(path);
    }

    /** A partial A* route must stop at its reachable endpoint instead of crossing a wall to the goal. */
    static void appendReachableGoal(List<Vec3> path, Vec3 goal,
            java.util.function.BiPredicate<Vec3, Vec3> clear) {
        if (path.isEmpty()) return;
        Vec3 last = path.get(path.size() - 1);
        if (last.distanceToSqr(goal) > 0.5 && clear.test(last, goal)) path.add(goal);
    }

    private List<Vec3> simplifyPath(List<Vec3> path) {
        if (path.size() <= 2) return path;

        List<Vec3> simplified = new ArrayList<>();
        int index = 0;
        simplified.add(path.get(0));
        while (index < path.size() - 1) {
            int farthest = index + 1;
            for (int j = path.size() - 1; j > index + 1; j--) {
                if (pathfinder.isDirectPathClear(path.get(index), path.get(j))) {
                    farthest = j;
                    break;
                }
            }
            simplified.add(path.get(farthest));
            index = farthest;
        }

        return simplified;
    }

    private BlockPos toGrid(BlockPos pos, int resolution) {
        if (resolution <= 1) return pos;
        return new BlockPos(
            Math.floorDiv(pos.getX(), resolution) * resolution,
            Math.floorDiv(pos.getY(), resolution) * resolution,
            Math.floorDiv(pos.getZ(), resolution) * resolution
        );
    }

    private double heuristic(BlockPos a, BlockPos b) {
        double dx = a.getX() - b.getX();
        double dy = a.getY() - b.getY();
        double dz = a.getZ() - b.getZ();
        return Math.sqrt(dx * dx + dy * dy + dz * dz);
    }

    private BlockPos findNearestPassable(BlockPos origin, int resolution, int maxRadius) {
        if (isNodeFlyable(origin)) return origin;

        for (int radius = 1; radius <= maxRadius; radius++) {
            for (int dx = -radius; dx <= radius; dx++) {
                for (int dy = -radius; dy <= radius; dy++) {
                    for (int dz = -radius; dz <= radius; dz++) {
                        if (Math.abs(dx) != radius && Math.abs(dy) != radius && Math.abs(dz) != radius) continue;

                        BlockPos candidate = origin.offset(dx * resolution, dy * resolution, dz * resolution);
                        if (isNodeFlyable(candidate)) return candidate;
                    }
                }
            }
        }

        return null;
    }

    private boolean isNodeFlyable(BlockPos center) {
        Boolean known = flyableNodes.get(center.asLong());
        if (known != null) return known;
        boolean flyable = computeNodeFlyable(center);
        flyableNodes.put(center.asLong(), flyable);
        return flyable;
    }

    private boolean computeNodeFlyable(BlockPos center) {
        if (pathfinder.hasNetherFlightCap() && center.getY() > (int) NETHER_MAX_FLIGHT_Y) return false;
        Vec3 sample = Vec3.atCenterOf(center);
        if (!pathfinder.hasPlayerClearance(sample)) return false;

        double floorDist = pathfinder.distanceToSolidBelow(sample, Math.max(6, SAFETY_SCAN / 2));
        double ceilingDist = pathfinder.distanceToSolidAbove(sample, Math.max(6, SAFETY_SCAN / 2));
        if (floorDist <= 0.1 || ceilingDist <= 0.1) return false;
        return pathfinder.countLateralOpenings(sample, 0.9) > 0;
    }

    private boolean isEdgeFlyable(BlockPos from, BlockPos to) {
        return pathfinder.isDirectPathClear(Vec3.atCenterOf(from), Vec3.atCenterOf(to));
    }

    private SearchResult searchPath(BlockPos startBlock, BlockPos goalBlock, Vec3 safeGoal, SearchBounds bounds) {
        Map<Long, AStarNode> openMap = new HashMap<>();
        Set<Long> closedSet = new HashSet<>();
        PriorityQueue<AStarNode> openQueue = new PriorityQueue<>(Comparator.comparingDouble(node -> node.f));

        AStarNode startNode = new AStarNode(startBlock, null, 0, heuristic(startBlock, goalBlock));
        openMap.put(startBlock.asLong(), startNode);
        openQueue.add(startNode);

        AStarNode bestNode = startNode;
        double bestDistance = heuristic(startBlock, goalBlock);
        int nodesExplored = 0;

        while (!openQueue.isEmpty() && nodesExplored < PATH_MAX_NODES) {
            if (nodesExplored % BUDGET_CHECK_NODES == 0 && nodesExplored > 0 && outOfTime()) break;
            AStarNode current = openQueue.poll();
            long currentKey = current.pos.asLong();
            if (closedSet.contains(currentKey)) continue;

            closedSet.add(currentKey);
            openMap.remove(currentKey);
            nodesExplored++;

            double currentDistance = heuristic(current.pos, goalBlock);
            if (currentDistance < bestDistance) {
                bestDistance = currentDistance;
                bestNode = current;
            }

            if (current.pos.distManhattan(goalBlock) <= PATH_RESOLUTION) {
                return new SearchResult(reconstructPath(current, safeGoal), true, currentDistance);
            }

            for (NeighborStep step : NEIGHBOR_STEPS) {
                BlockPos neighbor = current.pos.offset(step.dx, step.dy, step.dz);
                if (!bounds.contains(neighbor)) continue;

                long neighborKey = neighbor.asLong();
                if (closedSet.contains(neighborKey)) continue;
                if (!isNodeFlyable(neighbor)) continue;
                if (!isEdgeFlyable(current.pos, neighbor)) continue;

                double g = current.g + step.cost + nodePenalty(neighbor) + turnPenalty(current, neighbor) + Math.abs(step.dy) * 0.18;
                AStarNode old = openMap.get(neighborKey);
                if (old != null && old.g <= g) continue;

                double h = heuristic(neighbor, goalBlock);
                AStarNode next = new AStarNode(neighbor, current, g, g + h);
                openMap.put(neighborKey, next);
                openQueue.add(next);
            }
        }

        List<Vec3> fallbackPath = bestNode != startNode ? reconstructPath(bestNode, safeGoal) : null;
        return new SearchResult(fallbackPath, false, bestDistance);
    }

    private SearchBounds createSearchBounds(BlockPos start, BlockPos goal, int attempt) {
        int dx = Math.abs(start.getX() - goal.getX());
        int dy = Math.abs(start.getY() - goal.getY());
        int dz = Math.abs(start.getZ() - goal.getZ());
        int horizontalSpan = Math.max(dx, dz);

        int horizontalMargin = attempt == 0
            ? Mth.clamp(horizontalSpan / 3 + 8, 8, 20)
            : Mth.clamp(horizontalSpan / 2 + 16, 18, 40);
        int verticalMargin = attempt == 0
            ? Mth.clamp(dy + 8, 8, 18)
            : Mth.clamp(dy + 14, 14, 32);

        int minX = Math.min(start.getX(), goal.getX()) - horizontalMargin;
        int maxX = Math.max(start.getX(), goal.getX()) + horizontalMargin;
        int minY = Math.min(start.getY(), goal.getY()) - verticalMargin;
        int maxY = Math.max(start.getY(), goal.getY()) + verticalMargin;
        int minZ = Math.min(start.getZ(), goal.getZ()) - horizontalMargin;
        int maxZ = Math.max(start.getZ(), goal.getZ()) + horizontalMargin;

        if (pathfinder.hasNetherFlightCap()) maxY = Math.min(maxY, (int) NETHER_MAX_FLIGHT_Y);
        return new SearchBounds(minX, maxX, minY, maxY, minZ, maxZ);
    }

    private static NeighborStep[] createNeighborSteps() {
        List<NeighborStep> steps = new ArrayList<>(26);
        for (int dx = -1; dx <= 1; dx++) {
            for (int dy = -1; dy <= 1; dy++) {
                for (int dz = -1; dz <= 1; dz++) {
                    if (dx == 0 && dy == 0 && dz == 0) continue;
                    steps.add(new NeighborStep(dx, dy, dz, Math.sqrt(dx * dx + dy * dy + dz * dz)));
                }
            }
        }
        return steps.toArray(new NeighborStep[0]);
    }

    private static final class AStarNode {
        final BlockPos pos;
        final AStarNode parent;
        final double g;
        final double f;

        AStarNode(BlockPos pos, AStarNode parent, double g, double f) {
            this.pos = pos;
            this.parent = parent;
            this.g = g;
            this.f = f;
        }
    }

    private record NeighborStep(int dx, int dy, int dz, double cost) {
    }

    private record SearchBounds(int minX, int maxX, int minY, int maxY, int minZ, int maxZ) {
        boolean contains(BlockPos pos) {
            return pos.getX() >= minX && pos.getX() <= maxX
                && pos.getY() >= minY && pos.getY() <= maxY
                && pos.getZ() >= minZ && pos.getZ() <= maxZ;
        }
    }

    private record SearchResult(List<Vec3> path, boolean complete, double bestGoalDistance) {
    }
}
