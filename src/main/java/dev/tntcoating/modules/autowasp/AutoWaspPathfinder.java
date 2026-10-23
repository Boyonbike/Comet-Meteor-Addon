/*
 * Adapted from DEVILS by ThianYong-hub and DEVILS contributors.
 * Upstream: https://github.com/ThianYong-hub/DEVILS
 * Revision: 2440a168c3ba6f051f2adf8249a8e6fb5f3a5b02
 * Auto Wasp is also based on Meteor Client, Copyright (c) Meteor Development.
 * SPDX-License-Identifier: GPL-3.0-only
 *
 * Modified for Comet by Boyonabike on 2026-10-22: Minecraft 26.1.2 mappings,
 * local inventory integration, lifecycle/navigation guards and settings credits;
 * block-keyed clearance and floor/ceiling caches shared by nearby samples, and
 * chasing the live target whenever it is in plain sight.
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
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public final class AutoWaspPathfinder {
    private static final int CACHE_SWEEP_TICKS = 1;
    private static final int MAX_CACHE_ENTRIES = 20000;
    private static final double WAYPOINT_REACH = 2.0;
    private static final double TARGET_REPATH_DISTANCE_SQ = 6.25;
    private static final double FLOOR_CLEARANCE = 2.2;
    private static final double CEILING_CLEARANCE = 1.5;
    private static final int SAFETY_SCAN = 14;
    private static final double COLLISION_STEP = 0.55;
    private static final double NETHER_MAX_FLIGHT_Y = 123.0;

    private final AutoWasp module;
    private final AutoWaspPathSearch search;
    // Per-tick caches keyed by blocks, so every sample covering the same blocks shares one lookup.
    private final Map<ClearanceKey, Boolean> clearanceCache = new HashMap<>();
    private final Map<ColumnKey, Double> floorCache = new HashMap<>();
    private final Map<ColumnKey, Double> ceilingCache = new HashMap<>();

    private List<Vec3> currentPath = new ArrayList<>();
    private int currentWaypointIndex;
    private int pathTimer;
    private Vec3 lastPathTarget;
    private int cacheSweepTicks;

    public AutoWaspPathfinder(AutoWasp module) {
        this.module = module;
        this.search = new AutoWaspPathSearch(module, this);
    }

    public void onDeactivate() {
        currentPath.clear();
        currentWaypointIndex = 0;
        pathTimer = 0;
        lastPathTarget = null;
        cacheSweepTicks = 0;
        clearCaches();
    }

    public void resetNavigationState() {
        currentPath.clear();
        currentWaypointIndex = 0;
        pathTimer = 0;
        lastPathTarget = null;
        cacheSweepTicks = 0;
        clearCaches();
    }

    public void resetPathState() {
        currentPath.clear();
        currentWaypointIndex = 0;
        pathTimer = 0;
        lastPathTarget = null;
    }

    public void clearTargetState() {
        resetPathState();
    }

    public void tickCaches() {
        if (++cacheSweepTicks >= CACHE_SWEEP_TICKS || shouldTrimCaches()) {
            cacheSweepTicks = 0;
            clearCaches();
        }
    }

    public void tickPathing(Vec3 playerPos, Vec3 targetPos, int stuckTicks) {
        if (playerPos == null || targetPos == null) return;

        pathTimer++;
        boolean targetMovedFar = lastPathTarget == null || lastPathTarget.distanceToSqr(targetPos) > TARGET_REPATH_DISTANCE_SQ;
        boolean pathBlocked = currentWaypointIndex < currentPath.size() && !isDirectPathClear(playerPos, currentPath.get(currentWaypointIndex));
        boolean pathDepleted = currentPath.isEmpty() || currentWaypointIndex >= currentPath.size();
        boolean needsRepath = (pathDepleted && (lastPathTarget == null || pathTimer >= AutoWaspPathSearch.PATH_UPDATE_TICKS))
            || (targetMovedFar && pathTimer >= AutoWaspPathSearch.PATH_UPDATE_TICKS)
            || pathTimer >= AutoWaspPathSearch.PATH_FORCE_REFRESH_TICKS
            || (stuckTicks >= AutoWaspFlightController.STUCK_REPATH_TICKS && pathTimer >= AutoWaspPathSearch.PATH_UPDATE_TICKS)
            || (pathBlocked && pathTimer >= AutoWaspPathSearch.PATH_UPDATE_TICKS);

        if (needsRepath) {
            pathTimer = 0;
            lastPathTarget = targetPos;
            currentPath = search.computePath(playerPos, targetPos);
            currentWaypointIndex = 0;
        }

        advanceWaypoints(playerPos);
    }

    public Vec3 steerTarget(Vec3 fallback) {
        Vec3 playerPos = module.client().player.position();
        // In plain sight, chase the live target: a waypoint can be a whole path refresh old, which at boost speed is
        // far behind a moving target.
        if (isDirectPathClear(playerPos, fallback)) return fallback;
        if (currentWaypointIndex < currentPath.size()) {
            Vec3 waypoint = currentPath.get(currentWaypointIndex);
            if (isDirectPathClear(playerPos, waypoint)) return waypoint;
        }
        return playerPos;
    }

    public Vec3 adjustToSafeCorridor(Vec3 desired, Vec3 fallbackRef) {
        desired = clampToDimensionFlightCap(desired);
        double floorY = nearestSolidBelow(desired, SAFETY_SCAN);
        double ceilingY = nearestSolidAbove(desired, SAFETY_SCAN);

        double minY = floorY + FLOOR_CLEARANCE;
        double maxY = ceilingY - playerHeight() - CEILING_CLEARANCE;
        if (hasNetherFlightCap()) maxY = Math.min(maxY, NETHER_MAX_FLIGHT_Y);
        double y = desired.y;

        if (minY <= maxY) {
            y = Mth.clamp(y, minY, maxY);
        } else {
            double fallback = fallbackRef != null ? fallbackRef.y : y;
            y = Math.max(floorY + 1.0, Math.min(ceilingY - playerHeight() - 0.25, fallback));
        }

        Vec3 candidate = new Vec3(desired.x, y, desired.z);
        if (hasPlayerClearance(candidate)) return candidate;

        for (int i = 1; i <= 12; i++) {
            double shift = i * 0.35;
            Vec3 up = candidate.add(0, shift, 0);
            if (hasPlayerClearance(up)) return up;

            Vec3 down = candidate.add(0, -shift, 0);
            if (hasPlayerClearance(down)) return down;
        }

        return candidate;
    }

    public boolean isDirectPathClear(Vec3 start, Vec3 goal) {
        return isDirectPathClear(start, goal, this::hasPlayerClearance);
    }

    /** Check both endpoints, including a last partial step and very short segments. */
    static boolean isDirectPathClear(Vec3 start, Vec3 goal, java.util.function.Predicate<Vec3> clear) {
        Vec3 diff = goal.subtract(start);
        double distance = diff.length();
        if (!Double.isFinite(distance)) return false;
        int steps = Math.max(1, (int) Math.ceil(distance / COLLISION_STEP));
        for (int step = 0; step <= steps; step++) {
            if (!clear.test(start.add(diff.scale((double) step / steps)))) return false;
        }
        return true;
    }

    public boolean hasPlayerClearance(Vec3 center) {
        if (module.client().level == null) return false;
        if (hasNetherFlightCap() && center.y > NETHER_MAX_FLIGHT_Y) return false;

        double halfWidth = 0.35;
        double height = playerHeight();
        int minX = Mth.floor(center.x - halfWidth);
        int maxX = Mth.floor(center.x + halfWidth);
        int minY = Mth.floor(center.y);
        int maxY = Mth.floor(center.y + height);
        int minZ = Mth.floor(center.z - halfWidth);
        int maxZ = Mth.floor(center.z + halfWidth);
        // The answer depends only on the blocks the body covers.
        ClearanceKey key = new ClearanceKey(minX, minY, minZ, maxX, maxY, maxZ);
        Boolean cached = clearanceCache.get(key);
        if (cached != null) return cached;
        BlockPos.MutableBlockPos mutable = new BlockPos.MutableBlockPos();

        for (int x = minX; x <= maxX; x++) {
            for (int y = minY; y <= maxY; y++) {
                if (module.client().level.isOutsideBuildHeight(y)) {
                    clearanceCache.put(key, false);
                    return false;
                }
                for (int z = minZ; z <= maxZ; z++) {
                    mutable.set(x, y, z);
                    if (!module.client().level.hasChunkAt(mutable)
                        || !module.client().level.getBlockState(mutable).getCollisionShape(module.client().level, mutable).isEmpty()) {
                        clearanceCache.put(key, false);
                        return false;
                    }
                }
            }
        }

        clearanceCache.put(key, true);
        return true;
    }

    public boolean isSolid(BlockPos pos) {
        if (module.client().level == null || module.client().level.isOutsideBuildHeight(pos.getY())
            || !module.client().level.hasChunkAt(pos)) return true;
        return !module.client().level.getBlockState(pos).getCollisionShape(module.client().level, pos).isEmpty();
    }

    public double distanceToSolidBelow(Vec3 pos, int maxDepth) {
        return pos.y - nearestSolidBelow(pos, maxDepth);
    }

    public double distanceToSolidAbove(Vec3 pos, int maxHeight) {
        return nearestSolidAbove(pos, maxHeight) - (pos.y + playerHeight());
    }

    public int countLateralOpenings(Vec3 center, double distance) {
        int open = 0;
        for (Direction direction : AutoWaspPathSearch.CARDINAL_DIRECTIONS) {
            Vec3 sample = center.add(direction.getStepX() * distance, 0, direction.getStepZ() * distance);
            if (hasPlayerClearance(sample)) open++;
        }
        return open;
    }

    public boolean hasNetherFlightCap() {
        return module.client().level != null && module.client().level.dimension() == net.minecraft.world.level.Level.NETHER;
    }

    public Vec3 clampToDimensionFlightCap(Vec3 pos) {
        if (!hasNetherFlightCap() || pos.y <= NETHER_MAX_FLIGHT_Y) return pos;
        return new Vec3(pos.x, NETHER_MAX_FLIGHT_Y, pos.z);
    }

    BlockPos clampToDimensionFlightCap(BlockPos pos) {
        if (!hasNetherFlightCap() || pos.getY() <= (int) NETHER_MAX_FLIGHT_Y) return pos;
        return new BlockPos(pos.getX(), (int) NETHER_MAX_FLIGHT_Y, pos.getZ());
    }

    double playerHeight() {
        return 1.8;
    }

    private void advanceWaypoints(Vec3 playerPos) {
        double reachSq = WAYPOINT_REACH * WAYPOINT_REACH;
        while (currentWaypointIndex < currentPath.size()) {
            if (playerPos.distanceToSqr(currentPath.get(currentWaypointIndex)) > reachSq) break;
            int next = currentWaypointIndex + 1;
            if (next < currentPath.size() && !isDirectPathClear(playerPos, currentPath.get(next))) break;
            currentWaypointIndex++;
        }
    }

    private void clearCaches() {
        clearanceCache.clear();
        floorCache.clear();
        ceilingCache.clear();
    }

    private record ClearanceKey(int minX, int minY, int minZ, int maxX, int maxY, int maxZ) {}

    /** A column scan from block {@code y} over {@code range} blocks. */
    private record ColumnKey(int x, int y, int z, int range) {}

    private boolean shouldTrimCaches() {
        return clearanceCache.size() > MAX_CACHE_ENTRIES
            || floorCache.size() > MAX_CACHE_ENTRIES
            || ceilingCache.size() > MAX_CACHE_ENTRIES;
    }

    private double nearestSolidBelow(Vec3 pos, int maxDepth) {
        int x = Mth.floor(pos.x);
        int z = Mth.floor(pos.z);
        int yStart = Mth.floor(pos.y);
        // The scan depends only on its starting block; NaN means nothing solid within range.
        Double surface = floorCache.get(new ColumnKey(x, yStart, z, maxDepth));
        if (surface == null) {
            surface = scanBelow(x, yStart, z, maxDepth);
            floorCache.put(new ColumnKey(x, yStart, z, maxDepth), surface);
        }
        return Double.isNaN(surface) ? pos.y - maxDepth : surface;
    }

    private double scanBelow(int x, int yStart, int z, int maxDepth) {
        BlockPos.MutableBlockPos mutable = new BlockPos.MutableBlockPos(x, yStart, z);
        for (int i = 0; i <= maxDepth; i++) {
            int y = yStart - i;
            if (module.client().level.isOutsideBuildHeight(y)) return y;
            mutable.set(x, y, z);
            if (isSolid(mutable)) return y + 1.0;
        }
        return Double.NaN;
    }

    private double nearestSolidAbove(Vec3 pos, int maxHeight) {
        int x = Mth.floor(pos.x);
        int z = Mth.floor(pos.z);
        int yStart = Mth.floor(pos.y + playerHeight());
        Double surface = ceilingCache.get(new ColumnKey(x, yStart, z, maxHeight));
        if (surface == null) {
            surface = scanAbove(x, yStart, z, maxHeight);
            ceilingCache.put(new ColumnKey(x, yStart, z, maxHeight), surface);
        }
        return Double.isNaN(surface) ? pos.y + maxHeight : surface;
    }

    private double scanAbove(int x, int yStart, int z, int maxHeight) {
        BlockPos.MutableBlockPos mutable = new BlockPos.MutableBlockPos(x, yStart, z);
        for (int i = 0; i <= maxHeight; i++) {
            int y = yStart + i;
            if (module.client().level.isOutsideBuildHeight(y)) return y;
            mutable.set(x, y, z);
            if (isSolid(mutable)) return y;
        }
        return Double.NaN;
    }
}
