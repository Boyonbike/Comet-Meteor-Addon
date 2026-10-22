/*
 * Adapted from DEVILS by ThianYong-hub and DEVILS contributors.
 * Upstream: https://github.com/ThianYong-hub/DEVILS
 * Revision: 2440a168c3ba6f051f2adf8249a8e6fb5f3a5b02
 * Auto Wasp is also based on Meteor Client, Copyright (c) Meteor Development.
 * SPDX-License-Identifier: GPL-3.0-only
 *
 * Modified for Comet by Boyonabike on 2026-10-22: Minecraft 26.1.2 mappings,
 * local inventory integration, lifecycle/navigation guards and settings credits.
 * Distributed under GNU GPL v3; see LICENSE-DEVILS and THIRD_PARTY_NOTICES.md.
 * This program is distributed WITHOUT ANY WARRANTY; without even the implied
 * warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
 */

package dev.tntcoating.modules.autowasp;

import dev.tntcoating.modules.AutoWasp;
import meteordevelopment.meteorclient.events.entity.player.PlayerMoveEvent;
import meteordevelopment.meteorclient.mixininterface.IVec3;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.network.protocol.game.ServerboundPlayerCommandPacket;
import net.minecraft.core.BlockPos;
import net.minecraft.world.phys.AABB;
import net.minecraft.util.Mth;
import net.minecraft.world.phys.Vec3;

public final class AutoWaspFlightController {
    public static final int STUCK_REPATH_TICKS = 12;

    private static final double FLOOR_CLEARANCE = 2.2;
    private static final double CEILING_CLEARANCE = 1.5;
    private static final int SAFETY_SCAN = 14;
    private static final double OBSTACLE_LOOK_AHEAD = 7.5;
    private static final double AVOIDANCE_STRENGTH = 0.85;
    private static final double COLLISION_STEP = 0.55;
    private static final double NETHER_MAX_FLIGHT_Y = 123.0;

    private final AutoWasp module;
    private final AutoWaspPathfinder pathfinder;

    private int jumpTimer;
    private boolean incrementJumpTimer;
    private Vec3 lastPlayerPos;
    private int stuckTicks;
    private double adaptiveHorizontalCap = -1;
    private int antiCheatSlowTicks;

    public AutoWaspFlightController(AutoWasp module, AutoWaspPathfinder pathfinder) {
        this.module = module;
        this.pathfinder = pathfinder;
    }

    public void onDeactivate() {
        jumpTimer = 0;
        incrementJumpTimer = false;
        lastPlayerPos = null;
        stuckTicks = 0;
        adaptiveHorizontalCap = -1;
        antiCheatSlowTicks = 0;
    }

    public void resetNavigationState() {
        jumpTimer = 0;
        incrementJumpTimer = false;
        lastPlayerPos = module.client().player != null ? module.client().player.position() : null;
        stuckTicks = 0;
        adaptiveHorizontalCap = -1;
        antiCheatSlowTicks = 0;
    }

    public void resetFlightTracking() {
        jumpTimer = 0;
        incrementJumpTimer = false;
        pathfinder.resetPathState();
        stuckTicks = 0;
        adaptiveHorizontalCap = -1;
        antiCheatSlowTicks = 0;
        lastPlayerPos = module.client().player != null ? module.client().player.position() : null;
    }

    public void clearStuckState() {
        stuckTicks = 0;
        lastPlayerPos = module.client().player != null ? module.client().player.position() : null;
    }

    public void handleNotGliding() {
        if (module.client().player == null || module.client().level == null) return;

        if (incrementJumpTimer) jumpTimer++;
        if (!incrementJumpTimer) incrementJumpTimer = true;

        boolean inLiquid = module.client().player.isInWater() || module.client().player.isInLava();
        if (module.client().player.onGround() && incrementJumpTimer) {
            module.client().player.jumpFromGround();
            return;
        }

        int reopenDelay = inLiquid ? 1 : 4;
        if (jumpTimer >= reopenDelay) {
            jumpTimer = 0;
            module.client().player.setJumping(false);
            module.client().player.setSprinting(true);
            if (module.client().getConnection() != null) {
                module.client().getConnection().send(new ServerboundPlayerCommandPacket(module.client().player, ServerboundPlayerCommandPacket.Action.START_FALL_FLYING));
            }
        }

        stuckTicks = 0;
        lastPlayerPos = module.client().player.position();
        adaptiveHorizontalCap = -1;
        antiCheatSlowTicks = 0;
    }

    public void onGlidingTick() {
        incrementJumpTimer = false;
        jumpTimer = 0;
        updateStuckState();
    }

    public int getStuckTicks() {
        return stuckTicks;
    }

    public void onMove(PlayerMoveEvent event, Vec3 steerTarget) {
        if (module.client().player == null || module.client().level == null || steerTarget == null) return;

        Vec3 safeTarget = pathfinder.adjustToSafeCorridor(steerTarget, module.client().player.position());
        Vec3 toTarget = safeTarget.subtract(module.client().player.position());
        double horizontal = Math.sqrt(toTarget.x * toTarget.x + toTarget.z * toTarget.z);

        double maxHorizontal = resolveHorizontalCap();
        double maxVertical = resolveVerticalCap();
        double xVel = 0;
        double zVel = 0;
        if (horizontal > 1.0E-5) {
            double speed = Math.min(maxHorizontal, horizontal);
            xVel = (toTarget.x / horizontal) * speed;
            zVel = (toTarget.z / horizontal) * speed;
        }

        double yVel = Mth.clamp(toTarget.y, -maxVertical, maxVertical);
        yVel = applyVerticalSafety(yVel, module.client().player.position());

        Vec3 base = new Vec3(xVel, yVel, zVel);
        Vec3 avoid = computeLocalAvoidance(safeTarget, base, maxHorizontal);
        Vec3 velocity = applyDimensionFlightLimit(applyEmergencyBraking(base.add(avoid)), module.client().player.position());

        double horizontalVelocity = Math.sqrt(velocity.x * velocity.x + velocity.z * velocity.z);
        if (horizontalVelocity > maxHorizontal && horizontalVelocity > 1.0E-6) {
            double scale = maxHorizontal / horizontalVelocity;
            velocity = new Vec3(velocity.x * scale, velocity.y, velocity.z * scale);
        }

        double actualHoriz = Math.sqrt(
            module.client().player.getDeltaMovement().x * module.client().player.getDeltaMovement().x
                + module.client().player.getDeltaMovement().z * module.client().player.getDeltaMovement().z
        );
        boolean drag = isInDragState();
        boolean serverRejected = horizontalVelocity > 0.30 && actualHoriz < horizontalVelocity * 0.45;
        if (drag && serverRejected) antiCheatSlowTicks = Math.min(20, antiCheatSlowTicks + 1);
        else antiCheatSlowTicks = Math.max(0, antiCheatSlowTicks - 2);

        velocity = new Vec3(velocity.x, Mth.clamp(velocity.y, -maxVertical, maxVertical), velocity.z);
        ((IVec3) event.movement).meteor$set(velocity.x, velocity.y, velocity.z);
    }

    private void updateStuckState() {
        if (module.client().player == null) return;

        Vec3 now = module.client().player.position();
        if (lastPlayerPos == null) {
            lastPlayerPos = now;
            stuckTicks = 0;
            return;
        }

        if (now.distanceToSqr(lastPlayerPos) < 0.03) stuckTicks++;
        else stuckTicks = 0;
        lastPlayerPos = now;
    }

    private double resolveHorizontalCap() {
        double baseCap = module.horizontalSpeedValue();
        boolean drag = isInDragState();
        if (!drag) {
            adaptiveHorizontalCap = baseCap;
            return baseCap;
        }

        double actual = Math.sqrt(
            module.client().player.getDeltaMovement().x * module.client().player.getDeltaMovement().x
                + module.client().player.getDeltaMovement().z * module.client().player.getDeltaMovement().z
        );
        if (adaptiveHorizontalCap < 0 || adaptiveHorizontalCap > baseCap) adaptiveHorizontalCap = baseCap;

        double targetCap = Math.min(baseCap, Math.max(0.18, actual * 1.18 + 0.05));
        if (antiCheatSlowTicks > 8) targetCap = Math.min(targetCap, Math.max(0.16, actual * 1.05 + 0.03));

        adaptiveHorizontalCap += (targetCap - adaptiveHorizontalCap) * 0.35;
        adaptiveHorizontalCap = Mth.clamp(adaptiveHorizontalCap, 0.14, baseCap);
        return adaptiveHorizontalCap;
    }

    private double resolveVerticalCap() {
        return module.verticalSpeedValue();
    }

    private boolean isInDragState() {
        return module.client().player != null && isInsideSoftDragBlock(module.client().player.getBoundingBox());
    }

    private boolean isInsideSoftDragBlock(AABB box) {
        int minX = Mth.floor(box.minX + 1.0E-4);
        int maxX = Mth.floor(box.maxX - 1.0E-4);
        int minY = Mth.floor(box.minY + 1.0E-4);
        int maxY = Mth.floor(box.maxY - 1.0E-4);
        int minZ = Mth.floor(box.minZ + 1.0E-4);
        int maxZ = Mth.floor(box.maxZ - 1.0E-4);

        for (int x = minX; x <= maxX; x++) {
            for (int y = minY; y <= maxY; y++) {
                for (int z = minZ; z <= maxZ; z++) {
                    if (isSoftDragBlock(new BlockPos(x, y, z))) return true;
                }
            }
        }

        return false;
    }

    private boolean isSoftDragBlock(BlockPos pos) {
        var state = module.client().level.getBlockState(pos);
        return state.is(Blocks.COBWEB)
            || state.is(Blocks.VINE)
            || state.is(Blocks.WEEPING_VINES)
            || state.is(Blocks.WEEPING_VINES_PLANT)
            || state.is(Blocks.TWISTING_VINES)
            || state.is(Blocks.TWISTING_VINES_PLANT)
            || state.is(Blocks.CAVE_VINES)
            || state.is(Blocks.CAVE_VINES_PLANT);
    }

    private double applyVerticalSafety(double yVel, Vec3 currentPos) {
        double floorDist = pathfinder.distanceToSolidBelow(currentPos, SAFETY_SCAN);
        double ceilingDist = pathfinder.distanceToSolidAbove(currentPos, SAFETY_SCAN);

        double downBudget = Math.max(0.0, floorDist - FLOOR_CLEARANCE - 0.35);
        double safeMaxDown = Math.min(module.verticalSpeedValue(), downBudget * 0.8);
        yVel = Math.max(yVel, -safeMaxDown);

        if (floorDist < FLOOR_CLEARANCE + 0.35) yVel = Math.max(yVel, 0.12);
        if (ceilingDist < CEILING_CLEARANCE + 0.25) yVel = Math.min(yVel, -0.08);
        return yVel;
    }

    private Vec3 applyDimensionFlightLimit(Vec3 velocity, Vec3 currentPos) {
        if (!pathfinder.hasNetherFlightCap()) return velocity;

        double y = currentPos.y;
        double vy = velocity.y;
        if (y >= NETHER_MAX_FLIGHT_Y) vy = Math.min(vy, -0.10);
        else if (y >= NETHER_MAX_FLIGHT_Y - 0.75) vy = Math.min(vy, 0.0);
        else if (y >= NETHER_MAX_FLIGHT_Y - 1.5) vy = Math.min(vy, 0.08);

        return new Vec3(velocity.x, vy, velocity.z);
    }

    private Vec3 applyEmergencyBraking(Vec3 velocity) {
        if (module.client().player == null) return velocity;

        double horizontal = Math.sqrt(velocity.x * velocity.x + velocity.z * velocity.z);
        if (horizontal < 1.0E-4) return velocity;

        Vec3 pos = module.client().player.position();
        Vec3 dir = new Vec3(velocity.x / horizontal, 0, velocity.z / horizontal);
        Vec3 left = new Vec3(-dir.z, 0, dir.x);
        Vec3 right = left.scale(-1);

        double checkDistance = Math.max(1.5, Math.min(OBSTACLE_LOOK_AHEAD + 1.0, horizontal * 4.2));
        double clearAhead = probeClearDistance(pos, dir, checkDistance);
        if (clearAhead > checkDistance * 0.88) return velocity;

        double clearLeft = probeClearDistance(pos, left, checkDistance * 0.9);
        double clearRight = probeClearDistance(pos, right, checkDistance * 0.9);
        double clearUpLeft = probeClearDistance(pos, left.add(0, 0.42, 0).normalize(), checkDistance * 0.85);
        double clearUpRight = probeClearDistance(pos, right.add(0, 0.42, 0).normalize(), checkDistance * 0.85);

        Vec3 bestEscape = dir;
        double bestEscapeClear = clearAhead;
        if (clearLeft > bestEscapeClear) {
            bestEscape = left;
            bestEscapeClear = clearLeft;
        }
        if (clearRight > bestEscapeClear) {
            bestEscape = right;
            bestEscapeClear = clearRight;
        }
        if (clearUpLeft > bestEscapeClear) {
            bestEscape = left.add(0, 0.42, 0).normalize();
            bestEscapeClear = clearUpLeft;
        }
        if (clearUpRight > bestEscapeClear) {
            bestEscape = right.add(0, 0.42, 0).normalize();
            bestEscapeClear = clearUpRight;
        }

        double speedFactor = Mth.clamp(clearAhead / checkDistance, 0.35, 1.0);
        double desiredHorizontal = horizontal * speedFactor;
        if (bestEscape != dir && bestEscapeClear > clearAhead + 0.35) {
            double steer = Mth.clamp((checkDistance - clearAhead) / checkDistance, 0.2, 0.72);
            Vec3 blended = dir.scale(1.0 - steer).add(bestEscape.scale(steer)).normalize();
            desiredHorizontal = horizontal * Math.max(0.45, speedFactor);
            velocity = new Vec3(blended.x * desiredHorizontal, velocity.y, blended.z * desiredHorizontal);
        } else {
            double scale = Math.max(0.35, speedFactor);
            velocity = new Vec3(velocity.x * scale, velocity.y, velocity.z * scale);
        }

        double vx = velocity.x;
        double vz = velocity.z;
        double vy = velocity.y;
        double floorDist = pathfinder.distanceToSolidBelow(pos, SAFETY_SCAN);
        double ceilingDist = pathfinder.distanceToSolidAbove(pos, SAFETY_SCAN);

        if (clearAhead < 0.8) {
            vx *= 0.55;
            vz *= 0.55;
            vy = Math.max(vy, 0.24);
            stuckTicks = Math.max(stuckTicks, STUCK_REPATH_TICKS);
        } else if (clearAhead < 1.35) {
            vy = Math.max(vy, 0.14);
        }

        if (floorDist < FLOOR_CLEARANCE + 0.45) vy = Math.max(vy, 0.10);
        if (ceilingDist < CEILING_CLEARANCE + 0.2) vy = Math.min(vy, 0.05);
        return new Vec3(vx, vy, vz);
    }

    private Vec3 computeLocalAvoidance(Vec3 steerTarget, Vec3 baseVelocity, double horizontalCap) {
        if (module.client().player == null) return Vec3.ZERO;

        Vec3 pos = module.client().player.position();
        Vec3 avoid = Vec3.ZERO;
        double floorDist = pathfinder.distanceToSolidBelow(pos, SAFETY_SCAN);
        double ceilingDist = pathfinder.distanceToSolidAbove(pos, SAFETY_SCAN);

        if (floorDist < FLOOR_CLEARANCE) {
            double push = (FLOOR_CLEARANCE - floorDist) * 0.75;
            avoid = avoid.add(0, Math.min(module.verticalSpeedValue(), push), 0);
        }
        if (ceilingDist < CEILING_CLEARANCE) {
            double push = (CEILING_CLEARANCE - ceilingDist) * 0.75;
            avoid = avoid.add(0, -Math.min(module.verticalSpeedValue(), push), 0);
        }

        Vec3 heading = steerTarget.subtract(pos);
        double headingLen = Math.sqrt(heading.x * heading.x + heading.z * heading.z);
        if (headingLen < 1.0E-4) {
            heading = new Vec3(baseVelocity.x, 0, baseVelocity.z);
            headingLen = Math.sqrt(heading.x * heading.x + heading.z * heading.z);
        }
        if (headingLen <= 1.0E-4) return avoid;

        Vec3 forward = new Vec3(heading.x / headingLen, 0, heading.z / headingLen);
        double lookAhead = OBSTACLE_LOOK_AHEAD;
        double frontClear = probeClearDistance(pos, forward, lookAhead);
        if (frontClear >= lookAhead - 0.15) return avoid;

        Vec3 left = new Vec3(-forward.z, 0, forward.x);
        Vec3 right = left.scale(-1);
        Vec3 targetDir = steerTarget.subtract(pos).normalize();
        Vec3[] candidates = {
            left,
            right,
            left.add(forward.scale(0.45)).normalize(),
            right.add(forward.scale(0.45)).normalize(),
            left.add(0, 0.7, 0).normalize(),
            right.add(0, 0.7, 0).normalize(),
            new Vec3(0, 1, 0),
            left.add(forward.scale(-0.35)).normalize(),
            right.add(forward.scale(-0.35)).normalize()
        };

        Vec3 bestDir = left;
        double bestScore = Double.NEGATIVE_INFINITY;
        double bestSpace = 0;
        for (Vec3 candidate : candidates) {
            double space = probeClearDistance(pos, candidate, lookAhead);
            double align = candidate.dot(targetDir);
            Vec3 sample = pos.add(candidate.scale(Math.max(0.9, space * 0.65)));
            double sampleFloor = pathfinder.distanceToSolidBelow(sample, Math.max(6, SAFETY_SCAN / 2));
            double sampleCeiling = pathfinder.distanceToSolidAbove(sample, Math.max(6, SAFETY_SCAN / 2));
            int sideOpenings = pathfinder.countLateralOpenings(sample, 0.9);
            double score = space * 1.55 + align * 1.05 + sideOpenings * 0.3;
            if (sampleFloor < FLOOR_CLEARANCE) score -= (FLOOR_CLEARANCE - sampleFloor) * 1.4;
            if (sampleCeiling < CEILING_CLEARANCE) score -= (CEILING_CLEARANCE - sampleCeiling) * 1.4;
            if (candidate.y > 0 && floorDist < FLOOR_CLEARANCE + 0.7) score += 1.0;
            if (candidate.y > 0 && ceilingDist < CEILING_CLEARANCE + 0.7) score -= 0.8;
            if (score > bestScore) {
                bestScore = score;
                bestDir = candidate;
                bestSpace = space;
            }
        }

        double urgency = Math.max(0.0, 1.0 - bestSpace / lookAhead);
        double sideSpeed = horizontalCap * (0.45 + urgency) * AVOIDANCE_STRENGTH;
        return avoid.add(bestDir.scale(sideSpeed));
    }

    private double probeClearDistance(Vec3 start, Vec3 dir, double maxDistance) {
        double step = Math.max(0.25, COLLISION_STEP);
        for (double distance = step; distance <= maxDistance; distance += step) {
            if (!pathfinder.hasPlayerClearance(start.add(dir.scale(distance)))) return distance - step;
        }
        return maxDistance;
    }
}
