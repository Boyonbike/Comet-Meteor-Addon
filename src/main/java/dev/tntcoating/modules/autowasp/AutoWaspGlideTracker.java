/*
 * Part of Comet's Auto Wasp port; see AutoWasp.java and THIRD_PARTY_NOTICES.md for DEVILS provenance.
 * Written for Comet by Boyonabike on 2026-10-22, independent of Adaptive Elytra Fly's classes.
 * SPDX-License-Identifier: GPL-3.0-only
 */

package dev.tntcoating.modules.autowasp;

import dev.tntcoating.mixin.AutoWaspFireworkAccessor;
import dev.tntcoating.modules.AutoWasp;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.component.DataComponents;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.game.ClientboundExplodePacket;
import net.minecraft.network.protocol.game.ClientboundPlayerPositionPacket;
import net.minecraft.network.protocol.game.ClientboundSetEntityMotionPacket;
import net.minecraft.network.protocol.game.ServerboundMovePlayerPacket;
import net.minecraft.network.protocol.game.ServerboundUseItemPacket;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.PositionMoveRotation;
import net.minecraft.world.entity.projectile.FireworkRocketEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.component.Fireworks;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.VoxelShape;

/**
 * Follows the glide velocity a vanilla server keeps for this player, which client movement never changes: it starts
 * from the takeoff velocity and is ticked from each reported position with the rotation the server received, boosted
 * by attached rockets and replaced by corrections, motion and explosion packets. Also the mailbox for server position
 * corrections, which pursuit reads to rewind its speed ramp.
 */
public final class AutoWaspGlideTracker {
    private final AutoWasp module;
    // Receive events run on Netty. Only these atomic mailboxes are touched there; all flight work is client-thread work.
    private final AtomicInteger corrections = new AtomicInteger();
    private final AtomicReference<ClientboundPlayerPositionPacket> setback = new AtomicReference<>();
    private final AtomicReference<ClientboundSetEntityMotionPacket> motion = new AtomicReference<>();
    private final AtomicReference<Vec3> knockback = new AtomicReference<>(Vec3.ZERO);
    private volatile int playerId = Integer.MIN_VALUE;
    // The server resolves its own glide against blocks the same way, minus step-up.
    private final AutoWaspServerGlide.Collider collider;
    // The velocity the server simulates for this player's glide; null while not gliding.
    private Vec3 serverGlide;
    private boolean groundedSinceReset, sentRotation;
    private float sentPitch, sentYaw;
    private int rockets, pendingRocketTicks;

    public AutoWaspGlideTracker(AutoWasp module) {
        this.module = module;
        collider = (box, movement) -> {
            var mc = module.client();
            List<VoxelShape> entities = mc.level.getEntityCollisions(mc.player, box.expandTowards(movement));
            return Entity.collideBoundingBox(mc.player, movement, box, mc.level, entities);
        };
    }

    /** Netty thread. */
    public void onReceive(Packet<?> packet) {
        if (packet instanceof ClientboundPlayerPositionPacket position) {
            corrections.updateAndGet(n -> n < 1024 ? n + 1 : n);
            setback.set(position);
        } else if (packet instanceof ClientboundSetEntityMotionPacket push && push.id() == playerId) {
            motion.set(push);
        } else if (packet instanceof ClientboundExplodePacket explosion) {
            explosion.playerKnockback().ifPresent(push -> knockback.accumulateAndGet(push, Vec3::add));
        }
    }

    /** Movement and item-use packets are sent from the client thread. */
    public void onSend(Packet<?> packet) {
        LocalPlayer p = module.client().player;
        if (packet instanceof ServerboundMovePlayerPacket move && move.hasRotation()) {
            sentPitch = move.getXRot(0);
            sentYaw = move.getYRot(0);
            sentRotation = Float.isFinite(sentPitch) && Float.isFinite(sentYaw);
        } else if (packet instanceof ServerboundUseItemPacket use && p != null && p.isFallFlying()) {
            ItemStack stack = p.getItemInHand(use.getHand());
            if (stack.is(Items.FIREWORK_ROCKET)) {
                // The server boosts for at least this long; the rocket entity, once it arrives, covers the rest.
                Fireworks fireworks = stack.get(DataComponents.FIREWORKS);
                pendingRocketTicks = Math.max(pendingRocketTicks,
                    10 * (1 + (fireworks == null ? 0 : fireworks.flightDuration())));
            }
        }
    }

    /** Corrections received since the last call. */
    public int takeCorrections() { return corrections.getAndSet(0); }

    /** The latest correction, until {@link #preTick} consumes it. */
    public ClientboundPlayerPositionPacket latestSetback() { return setback.get(); }

    /** Start of each client tick, after pursuit has read the corrections. */
    public void preTick() {
        var mc = module.client();
        LocalPlayer p = mc.player;
        playerId = p == null ? Integer.MIN_VALUE : p.getId();
        ClientboundPlayerPositionPacket correction = setback.getAndSet(null);
        ClientboundSetEntityMotionPacket push = motion.getAndSet(null);
        Vec3 blast = knockback.getAndSet(Vec3.ZERO);
        if (p == null || mc.level == null || !p.isFallFlying()) {
            serverGlide = null;
            groundedSinceReset = true;
            rockets = pendingRocketTicks = 0;
            return;
        }
        // Both sides start a takeoff from the same vanilla velocity. A flight already under way when tracking
        // started is unknown, so assume a long glide.
        if (serverGlide == null) serverGlide = groundedSinceReset ? finite(p.getDeltaMovement())
            : AutoWaspServerGlide.steady(p.getXRot(), p.getYRot(), p.getGravity(), p.hasEffect(MobEffects.SLOW_FALLING));
        if (correction != null) serverGlide = finite(PositionMoveRotation.calculateAbsolute(
            new PositionMoveRotation(p.position(), serverGlide, p.getYRot(), p.getXRot()),
            correction.change(), correction.relatives()).deltaMovement());
        if (push != null) serverGlide = finite(push.movement());
        if (!blast.equals(Vec3.ZERO)) serverGlide = finite(serverGlide.add(blast));
        rockets = Math.max(attachedRockets(p), pendingRocketTicks > 0 ? 1 : 0);
    }

    /** End of each client tick: the server ticks its glide from the position this tick reported. */
    public void postTick() {
        var mc = module.client();
        LocalPlayer p = mc.player;
        if (serverGlide == null || p == null || mc.level == null || !p.isFallFlying()) return;
        AutoWaspServerGlide.Tick tick = AutoWaspServerGlide.tick(context(p,
            sentRotation ? sentPitch : p.getXRot(), sentRotation ? sentYaw : p.getYRot()), p.getBoundingBox());
        serverGlide = tick.velocity();
        if (pendingRocketTicks > 0) pendingRocketTicks--;
    }

    /** The server's glide as it would tick with this rotation, or null while it is not being followed. */
    public AutoWaspServerGlide.Context context(float pitch, float yaw) {
        LocalPlayer p = module.client().player;
        return serverGlide == null || p == null ? null : context(p, pitch, yaw);
    }

    /** Non-loading chunk lookup. */
    public boolean chunkLoaded(int chunkX, int chunkZ) {
        var level = module.client().level;
        return level != null && level.getChunkSource().hasChunk(chunkX, chunkZ);
    }

    public void reset() {
        corrections.set(0);
        setback.set(null);
        motion.set(null);
        knockback.set(Vec3.ZERO);
        playerId = Integer.MIN_VALUE;
        serverGlide = null;
        groundedSinceReset = sentRotation = false;
        rockets = pendingRocketTicks = 0;
    }

    private AutoWaspServerGlide.Context context(LocalPlayer p, float pitch, float yaw) {
        return new AutoWaspServerGlide.Context(serverGlide, rockets, pitch, yaw, p.getGravity(),
            p.hasEffect(MobEffects.SLOW_FALLING), collider);
    }

    private int attachedRockets(LocalPlayer p) {
        int count = 0;
        for (FireworkRocketEntity rocket : module.client().level.getEntitiesOfClass(FireworkRocketEntity.class,
            p.getBoundingBox().inflate(16))) {
            if (rocket.getEntityData().get(AutoWaspFireworkAccessor.autowasp$attachedTarget())
                .orElse(Integer.MIN_VALUE) == p.getId()) count++;
        }
        return count;
    }

    private static Vec3 finite(Vec3 vec) {
        return Double.isFinite(vec.x) && Double.isFinite(vec.y) && Double.isFinite(vec.z) ? vec : Vec3.ZERO;
    }
}
