/*
 * Adapted from DEVILS by ThianYong-hub and DEVILS contributors.
 * Upstream: https://github.com/ThianYong-hub/DEVILS
 * Revision: 2440a168c3ba6f051f2adf8249a8e6fb5f3a5b02
 * Auto Wasp is also based on Meteor Client, Copyright (c) Meteor Development.
 * SPDX-License-Identifier: GPL-3.0-only
 *
 * Modified for Comet by Boyonabike on 2026-10-22: Minecraft 26.1.2 mappings,
 * local inventory integration, lifecycle/navigation guards and settings credits;
 * setback-aware speed ramp, pinned-glide recovery, swap settling, paced takeoff,
 * heading limit, arrival braking, server speed caps and the server-glide wall guard.
 * Distributed under GNU GPL v3; see LICENSE-DEVILS and THIRD_PARTY_NOTICES.md.
 * This program is distributed WITHOUT ANY WARRANTY; without even the implied
 * warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
 */

package dev.tntcoating.modules.autowasp;

import dev.tntcoating.modules.AutoWasp;
import meteordevelopment.meteorclient.events.entity.player.PlayerMoveEvent;
import meteordevelopment.meteorclient.mixininterface.IVec3;
import meteordevelopment.meteorclient.utils.player.Rotations;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.game.ClientboundPlayerPositionPacket;
import net.minecraft.world.entity.PositionMoveRotation;
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
    // The lowest priority is sent last, so the server's next glide tick uses this heading.
    private static final int GLIDE_STEER_PRIORITY = Integer.MIN_VALUE;

    private final AutoWasp module;
    private final AutoWaspPathfinder pathfinder;
    private final AutoWaspGlideTracker glide;
    private final AutoWaspRamp ramp = new AutoWaspRamp(new AutoWaspRamp.Settings(1, AutoWaspRamp.PLATEAU, .3, 15, 1, 1));
    private final AutoWaspSwapDelay swapDelay = new AutoWaspSwapDelay();
    private final AutoWaspTakeoff takeoff = new AutoWaspTakeoff();
    private final AutoWaspSetbacks setbacks = new AutoWaspSetbacks();
    private final AutoWaspSteering.Maneuver maneuver = new AutoWaspSteering.Maneuver();

    private Vec3 lastPlayerPos;
    private int stuckTicks;
    private double adaptiveHorizontalCap = -1;
    // Whether pursuit handled the latest movement while gliding, so a correction now answers one of its moves.
    private boolean controlling;
    private Vec3 lastControlledPosition;
    // The velocity pursuit flew last movement, or vanilla's while it left the glide to vanilla physics.
    private Vec3 lastOutput = Vec3.ZERO;
    private long clientTicks;
    private String state = "Waiting";

    public AutoWaspFlightController(AutoWasp module, AutoWaspPathfinder pathfinder) {
        this.module = module;
        this.pathfinder = pathfinder;
        this.glide = new AutoWaspGlideTracker(module);
    }

    public void onDeactivate() {
        lastPlayerPos = null;
        stuckTicks = 0;
        adaptiveHorizontalCap = -1;
        resetControl();
    }

    public void resetNavigationState() {
        lastPlayerPos = module.client().player != null ? module.client().player.position() : null;
        stuckTicks = 0;
        adaptiveHorizontalCap = -1;
        resetControl();
    }

    public void resetFlightTracking() {
        pathfinder.resetPathState();
        stuckTicks = 0;
        adaptiveHorizontalCap = -1;
        lastPlayerPos = module.client().player != null ? module.client().player.position() : null;
        releaseControl();
        state = "Waiting";
    }

    public void clearStuckState() {
        stuckTicks = 0;
        lastPlayerPos = module.client().player != null ? module.client().player.position() : null;
    }

    /** Netty thread. */
    public void onReceive(Packet<?> packet) {
        glide.onReceive(packet);
    }

    public void onSend(Packet<?> packet) {
        glide.onSend(packet);
    }

    /** An elytra was just put on: glide with vanilla physics until the server has settled it. */
    public void elytraSwapped() {
        swapDelay.swapped();
    }

    /** Start of every client tick, before any pursuit work. */
    public void preTick(boolean usableElytra) {
        LocalPlayer player = module.client().player;
        clientTicks++;
        setbacks.tick();
        consumeCorrections();
        glide.preTick();
        if (player != null) swapDelay.observe(clientTicks, usableElytra, player.isFallFlying());
        // Movement this tick decides afresh; a glide pursuit did not handle starts again from vanilla's velocity.
        if (!controlling) releaseControl();
        controlling = false;
    }

    /** End of every client tick, after this tick's position was reported. */
    public void postTick() {
        LocalPlayer player = module.client().player;
        if (controlling && player != null) lastControlledPosition = player.position();
        glide.postTick();
    }

    public void handleNotGliding() {
        LocalPlayer player = module.client().player;
        if (player == null || module.client().level == null) return;

        boolean inLiquid = player.isInWater() || player.isInLava();
        switch (takeoff.next(clientTicks, player.onGround(), player.isFallFlying(), player.getDeltaMovement().y,
            inLiquid)) {
            case JUMP -> player.jumpFromGround();
            case OPEN -> {
                player.setJumping(false);
                player.setSprinting(true);
                // What the client does for a second press of Jump in the air, so a refused open is not sent at all.
                if (player.tryToStartFallFlying() && module.client().getConnection() != null) {
                    module.client().getConnection().send(new ServerboundPlayerCommandPacket(player,
                        ServerboundPlayerCommandPacket.Action.START_FALL_FLYING));
                }
            }
            case WAIT -> {}
        }

        stuckTicks = 0;
        lastPlayerPos = player.position();
        adaptiveHorizontalCap = -1;
        releaseControl();
        state = "Taking off";
    }

    public void onGlidingTick() {
        updateStuckState();
        configureRamp();
        LocalPlayer player = module.client().player;
        if (setbacks.pinned() || !swapDelay.ready() || player == null) ramp.disengage();
        else if (ramp.engaged()) ramp.advance();
        else ramp.engage(player.getDeltaMovement().length());
    }

    public int getStuckTicks() {
        return stuckTicks;
    }

    /** What pursuit is doing, for the module list. */
    public String state() {
        return state;
    }

    /**
     * {@code targetVelocity} is the target's own movement per tick when steering straight at it, so pursuit matches
     * it instead of trailing behind; zero when steering at a waypoint.
     */
    public void onMove(PlayerMoveEvent event, Vec3 steerTarget, Vec3 targetVelocity) {
        LocalPlayer player = module.client().player;
        if (player == null || module.client().level == null || steerTarget == null) return;
        controlling = true;

        if (setbacks.pinned()) {
            // Minecraft's own glide, which a server always accepts, until it stops pinning pursuit in place.
            deferToVanilla(player, "Unpinning");
            return;
        }
        if (!swapDelay.ready()) {
            deferToVanilla(player, "Settling glide");
            return;
        }
        if (!ramp.engaged()) {
            configureRamp();
            ramp.engage(player.getDeltaMovement().length());
        }

        Vec3 position = player.position();
        Vec3 safeTarget = pathfinder.adjustToSafeCorridor(steerTarget, position);
        Vec3 toTarget = safeTarget.subtract(position);
        double horizontal = toTarget.horizontalDistance();
        double braking = module.brakingValue();
        double rampCap = ramp.speed();

        double maxHorizontal = Math.min(resolveHorizontalCap(), rampCap);
        double maxVertical = Math.min(module.verticalSpeedValue(), rampCap);
        double approach = AutoWaspSteering.arrivalSpeed(horizontal, braking);
        double xVel = 0;
        double zVel = 0;
        if (horizontal > 1.0E-5) {
            xVel = (toTarget.x / horizontal) * approach;
            zVel = (toTarget.z / horizontal) * approach;
        }
        double yVel = Math.copySign(AutoWaspSteering.arrivalSpeed(Math.abs(toTarget.y), braking), toTarget.y);

        Vec3 desired = new Vec3(xVel, yVel, zVel).add(targetVelocity == null ? Vec3.ZERO : targetVelocity);
        desired = AutoWaspSteering.capHorizontal(desired, maxHorizontal);
        desired = new Vec3(desired.x, Mth.clamp(desired.y, -maxVertical, maxVertical), desired.z);
        // Only pursuit's own heading is limited; avoidance and emergency braking below always act at once.
        desired = AutoWaspSteering.limitTurn(lastOutput, desired, AutoWaspSteering.turnRate(module.turnRateValue(),
            lastOutput.horizontalDistance(), module.horizontalSpeedValue()), braking);
        boolean maneuvering = maneuver.update(lastOutput, desired);

        Vec3 base = new Vec3(desired.x, applyVerticalSafety(desired.y, position), desired.z);
        Vec3 avoid = computeLocalAvoidance(safeTarget, base, maxHorizontal);
        Vec3 velocity = applyDimensionFlightLimit(applyEmergencyBraking(base.add(avoid)), position);
        velocity = AutoWaspSteering.capHorizontal(velocity, maxHorizontal);
        velocity = new Vec3(velocity.x, Mth.clamp(velocity.y, -maxVertical, maxVertical), velocity.z);

        double total = Math.min(rampCap, AutoWaspSteering.SERVER_MOVE_LIMIT);
        if (maneuvering) total = Math.min(total, module.maneuverMaxSpeedValue());
        velocity = AutoWaspSteering.capLength(velocity, total);
        velocity = guardServerGlide(player, velocity);

        ((IVec3) event.movement).meteor$set(velocity.x, velocity.y, velocity.z);
        lastOutput = velocity;
        ramp.follow(velocity.length());
    }

    /** Netty-free setback handling: rewinds the ramp, pins the flight to vanilla glide, or ends the route. */
    private void consumeCorrections() {
        int count = glide.takeCorrections();
        LocalPlayer player = module.client().player;
        if (count == 0 || !controlling || player == null || !player.isFallFlying()) return;
        ClientboundPlayerPositionPacket packet = glide.latestSetback();
        Vec3 target = packet == null || lastControlledPosition == null ? player.position()
            : PositionMoveRotation.calculateAbsolute(new PositionMoveRotation(lastControlledPosition,
                player.getDeltaMovement(), player.getYRot(), player.getXRot()),
                packet.change(), packet.relatives()).position();
        switch (setbacks.classify(clientTicks, target, lastControlledPosition)) {
            case TELEPORT -> {
                ramp.disengage();
                lastOutput = Vec3.ZERO;
                maneuver.reset();
                pathfinder.resetPathState();
                clearStuckState();
            }
            case PINNED -> {
                ramp.corrected();
                if (module.pinnedGlideTicksValue() > 0) {
                    setbacks.pin(module.pinnedGlideTicksValue());
                    ramp.disengage();
                }
            }
            case SETBACK -> ramp.corrected();
        }
        // A correction moved the player, which is not being stuck.
        lastPlayerPos = player.position();
    }

    /**
     * Steers the server's hidden glide away from walls and keeps walls outside its reach, so its own move never
     * deals fly-into-wall damage however slowly pursuit itself is moving.
     */
    private Vec3 guardServerGlide(LocalPlayer player, Vec3 velocity) {
        AutoWaspServerGlide.Context context = glide.context(player.getXRot(), player.getYRot());
        if (context == null) return velocity;
        AABB body = player.getBoundingBox();
        if (module.steerServerGlideValue()) {
            AutoWaspServerGlide.Rotation steer = AutoWaspServerGlide.steer(context, body, glide::chunkLoaded);
            if (steer != null) {
                Rotations.rotate(steer.yaw(), steer.pitch(), GLIDE_STEER_PRIORITY);
                context = glide.context(steer.pitch(), steer.yaw());
            }
        }
        if (!module.serverGlideGuardValue() || context == null) {
            state = "Pursuing";
            return velocity;
        }
        AutoWaspServerGlide.Clearance clearance = AutoWaspServerGlide.clear(context, body, velocity, glide::chunkLoaded);
        state = clearance.limited() ? "Glide clearance" : "Pursuing";
        return clearance.motion();
    }

    private void deferToVanilla(LocalPlayer player, String why) {
        ramp.disengage();
        maneuver.reset();
        lastOutput = player.getDeltaMovement();
        state = why;
    }

    private void releaseControl() {
        ramp.disengage();
        maneuver.reset();
        LocalPlayer player = module.client().player;
        lastOutput = player == null ? Vec3.ZERO : player.getDeltaMovement();
    }

    private void resetControl() {
        ramp.reset();
        swapDelay.reset();
        takeoff.reset();
        setbacks.reset();
        maneuver.reset();
        glide.reset();
        controlling = false;
        lastControlledPosition = null;
        lastOutput = Vec3.ZERO;
        clientTicks = 0;
        state = "Waiting";
    }

    private void configureRamp() {
        double limit = Math.min(AutoWaspSteering.SERVER_MOVE_LIMIT,
            Math.hypot(module.horizontalSpeedValue(), module.verticalSpeedValue()));
        ramp.configure(new AutoWaspRamp.Settings(module.rampStartSpeedValue(), AutoWaspRamp.PLATEAU,
            module.rampStepValue(), module.setbackRewindTicksValue(), module.setbackHoldTicksValue(), limit));
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

    /** Inside cobwebs and vines, ease the cap down towards what the drag actually lets through. */
    private double resolveHorizontalCap() {
        double baseCap = module.horizontalSpeedValue();
        boolean drag = isInDragState();
        if (!drag) {
            adaptiveHorizontalCap = baseCap;
            return baseCap;
        }

        double actual = module.client().player.getDeltaMovement().horizontalDistance();
        if (adaptiveHorizontalCap < 0 || adaptiveHorizontalCap > baseCap) adaptiveHorizontalCap = baseCap;

        double targetCap = Math.min(baseCap, Math.max(0.18, actual * 1.18 + 0.05));
        adaptiveHorizontalCap += (targetCap - adaptiveHorizontalCap) * 0.35;
        adaptiveHorizontalCap = Mth.clamp(adaptiveHorizontalCap, 0.14, baseCap);
        return adaptiveHorizontalCap;
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
