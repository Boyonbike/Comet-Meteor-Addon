/*
 * Original Comet test harness code by Boyonabike.
 * Independent: written for this repository against the pinned Minecraft 26.2
 * server classes; no third-party client code.
 * SPDX-License-Identifier: CC0-1.0
 */

package dev.tntcoating.gametest.mixin;

import dev.tntcoating.gametest.server.ServerGroundTruth;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(Player.class)
public abstract class PlayerMixin {
    @Inject(method = "attack", at = @At("HEAD"))
    private void comet$attackAccepted(Entity target, CallbackInfo ci) {
        if (!((Object) this instanceof ServerPlayer player)) return;
        ServerGroundTruth.event("attack_accepted", line -> {
            line.addProperty("player", player.getId());
            line.addProperty("target", target.getId());
            line.addProperty("targetUuid", target.getUUID().toString());
            line.addProperty("targetType", target.getType().toString());
            line.addProperty("distance", Math.sqrt(player.distanceToSqr(target)));
        });
    }
}
