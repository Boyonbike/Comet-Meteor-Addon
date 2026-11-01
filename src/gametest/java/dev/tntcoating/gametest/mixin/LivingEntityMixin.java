/*
 * Original Comet test harness code by Boyonabike.
 * Independent: written for this repository against the pinned Minecraft 26.2
 * server classes; no third-party client code.
 * SPDX-License-Identifier: CC0-1.0
 */

package dev.tntcoating.gametest.mixin;

import dev.tntcoating.gametest.server.ServerGroundTruth;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(LivingEntity.class)
public abstract class LivingEntityMixin {
    @Shadow
    protected float lastHurt;

    @Inject(method = "hurtServer", at = @At("HEAD"))
    private void comet$hurtAttempt(ServerLevel level, DamageSource source, float amount,
                                   CallbackInfoReturnable<Boolean> cir) {
        LivingEntity self = (LivingEntity) (Object) this;
        ServerGroundTruth.event("hurt_attempt", line -> {
            line.addProperty("entity", self.getId());
            line.addProperty("uuid", self.getUUID().toString());
            line.addProperty("entityType", BuiltInRegistries.ENTITY_TYPE.getKey(self.getType()).toString());
            line.addProperty("amount", amount);
            line.addProperty("health", self.getHealth());
            line.addProperty("absorption", self.getAbsorptionAmount());
            line.addProperty("lastHurt", lastHurt);
            line.addProperty("source", source.getMsgId());
            Entity attacker = source.getEntity();
            line.addProperty("attacker", attacker == null ? -1 : attacker.getId());
        });
    }

    @Inject(method = "hurtServer", at = @At("RETURN"))
    private void comet$hurtResult(ServerLevel level, DamageSource source, float amount,
                                  CallbackInfoReturnable<Boolean> cir) {
        LivingEntity self = (LivingEntity) (Object) this;
        ServerGroundTruth.event("hurt_result", line -> {
            line.addProperty("entity", self.getId());
            line.addProperty("accepted", cir.getReturnValue());
            line.addProperty("amount", amount);
            line.addProperty("health", self.getHealth());
            line.addProperty("absorption", self.getAbsorptionAmount());
            line.addProperty("lastHurt", lastHurt);
            line.addProperty("source", source.getMsgId());
        });
    }

    @Inject(method = "actuallyHurt", at = @At("HEAD"))
    private void comet$actuallyHurtStart(ServerLevel level, DamageSource source, float amount, CallbackInfo ci) {
        LivingEntity self = (LivingEntity) (Object) this;
        ServerGroundTruth.event("damage_applied_start", line -> {
            line.addProperty("entity", self.getId());
            line.addProperty("uuid", self.getUUID().toString());
            line.addProperty("amount", amount);
            line.addProperty("health", self.getHealth());
            line.addProperty("absorption", self.getAbsorptionAmount());
            line.addProperty("lastHurt", lastHurt);
            line.addProperty("source", source.getMsgId());
        });
    }

    @Inject(method = "actuallyHurt", at = @At("RETURN"))
    private void comet$actuallyHurtEnd(ServerLevel level, DamageSource source, float amount, CallbackInfo ci) {
        LivingEntity self = (LivingEntity) (Object) this;
        ServerGroundTruth.event("damage_applied_end", line -> {
            line.addProperty("entity", self.getId());
            line.addProperty("uuid", self.getUUID().toString());
            line.addProperty("amount", amount);
            line.addProperty("health", self.getHealth());
            line.addProperty("absorption", self.getAbsorptionAmount());
            line.addProperty("lastHurt", lastHurt);
            line.addProperty("source", source.getMsgId());
        });
    }
}
