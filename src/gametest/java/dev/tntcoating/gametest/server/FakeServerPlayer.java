/*
 * Original Comet test harness code by Boyonabike.
 * Independent: written for this repository against the pinned Minecraft 26.2
 * server classes; no third-party client code.
 * SPDX-License-Identifier: CC0-1.0
 */

package dev.tntcoating.gametest.server;

import com.mojang.authlib.GameProfile;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ClientInformation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.MoverType;
import net.minecraft.world.phys.Vec3;

/**
 * A server-side player used as the scripted target. Vanilla players receive movement
 * from a client; this dummy instead applies its server-side delta movement after the
 * normal player tick so explosion knockback is observable without a second client.
 */
public final class FakeServerPlayer extends ServerPlayer {
    public FakeServerPlayer(MinecraftServer server, ServerLevel level, GameProfile profile,
                            ClientInformation information) {
        super(server, level, profile, information);
    }

    @Override
    public void tick() {
        super.tick();
        if (!isAlive()) return;
        Vec3 movement = getDeltaMovement();
        if (movement.lengthSqr() < 1.0E-7) return;
        move(MoverType.SELF, movement);
        setDeltaMovement(movement.x * 0.98, movement.y * 0.98, movement.z * 0.98);
        if (onGround()) {
            setDeltaMovement(getDeltaMovement().x * 0.6, getDeltaMovement().y, getDeltaMovement().z * 0.6);
        }
    }
}
