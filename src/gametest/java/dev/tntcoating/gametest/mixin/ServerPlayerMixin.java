/*
 * Original Comet test harness code by Boyonabike.
 * Independent: written for this repository against the pinned Minecraft 26.2
 * server classes; no third-party client code.
 * SPDX-License-Identifier: CC0-1.0
 */

package dev.tntcoating.gametest.mixin;

import dev.tntcoating.gametest.server.ServerGroundTruth;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.damagesource.DamageSource;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(ServerPlayer.class)
public abstract class ServerPlayerMixin {
    @Inject(method = "hurtServer", at = @At("HEAD"))
    private void comet$hurtAttempt(ServerLevel level, DamageSource source, float amount,
                                   CallbackInfoReturnable<Boolean> cir) {
        ServerPlayer self = (ServerPlayer) (Object) this;
        ServerGroundTruth.event("player_hurt_attempt", line -> {
            line.addProperty("entity", self.getId());
            line.addProperty("uuid", self.getUUID().toString());
            line.addProperty("name", self.getGameProfile().name());
            line.addProperty("amount", amount);
            line.addProperty("health", self.getHealth());
            line.addProperty("absorption", self.getAbsorptionAmount());
            line.addProperty("source", source.getMsgId());
        });
    }

    @Inject(method = "hurtServer", at = @At("RETURN"))
    private void comet$hurtResult(ServerLevel level, DamageSource source, float amount,
                                  CallbackInfoReturnable<Boolean> cir) {
        ServerPlayer self = (ServerPlayer) (Object) this;
        ServerGroundTruth.event("player_hurt_result", line -> {
            line.addProperty("entity", self.getId());
            line.addProperty("accepted", cir.getReturnValue());
            line.addProperty("amount", amount);
            line.addProperty("health", self.getHealth());
            line.addProperty("absorption", self.getAbsorptionAmount());
            line.addProperty("source", source.getMsgId());
        });
    }
}
