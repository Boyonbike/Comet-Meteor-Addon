/*
 * Original Comet test harness code by Boyonabike.
 * Independent: written for this repository against the pinned Minecraft 26.2
 * server classes; no third-party client code.
 * SPDX-License-Identifier: CC0-1.0
 */

package dev.tntcoating.gametest.mixin;

import dev.tntcoating.gametest.server.ServerGroundTruth;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.boss.enderdragon.EndCrystal;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(EndCrystal.class)
public abstract class EndCrystalMixin {
    @Inject(method = "hurtServer", at = @At("HEAD"))
    private void comet$crystalHurt(ServerLevel level, DamageSource source, float amount,
                                   CallbackInfoReturnable<Boolean> cir) {
        EndCrystal self = (EndCrystal) (Object) this;
        ServerGroundTruth.event("crystal_hurt", line -> {
            line.addProperty("entity", self.getId());
            line.addProperty("uuid", self.getUUID().toString());
            line.addProperty("amount", amount);
            line.addProperty("source", source.getMsgId());
            line.addProperty("explosion", source.is(net.minecraft.tags.DamageTypeTags.IS_EXPLOSION));
            line.addProperty("x", self.getX());
            line.addProperty("y", self.getY());
            line.addProperty("z", self.getZ());
        });
    }

    @Inject(method = "hurtServer", at = @At("RETURN"))
    private void comet$crystalHurtResult(ServerLevel level, DamageSource source, float amount,
                                         CallbackInfoReturnable<Boolean> cir) {
        EndCrystal self = (EndCrystal) (Object) this;
        ServerGroundTruth.event("crystal_hurt_result", line -> {
            line.addProperty("entity", self.getId());
            line.addProperty("removed", self.isRemoved());
            line.addProperty("returnValue", cir.getReturnValue());
            line.addProperty("source", source.getMsgId());
        });
    }
}
