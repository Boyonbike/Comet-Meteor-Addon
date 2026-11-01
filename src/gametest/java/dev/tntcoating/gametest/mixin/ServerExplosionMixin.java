/*
 * Original Comet test harness code by Boyonabike.
 * Independent: written for this repository against the pinned Minecraft 26.2
 * server classes; no third-party client code.
 * SPDX-License-Identifier: CC0-1.0
 */

package dev.tntcoating.gametest.mixin;

import dev.tntcoating.gametest.server.ServerGroundTruth;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.ServerExplosion;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(ServerExplosion.class)
public abstract class ServerExplosionMixin {
    @Inject(method = "explode", at = @At("HEAD"))
    private void comet$explodeStart(CallbackInfoReturnable<Integer> cir) {
        ServerExplosion self = (ServerExplosion) (Object) this;
        ServerGroundTruth.event("explosion_start", line -> {
            line.addProperty("x", self.center().x);
            line.addProperty("y", self.center().y);
            line.addProperty("z", self.center().z);
            line.addProperty("radius", self.radius());
            line.addProperty("source", self.getDirectSourceEntity() == null ? -1 : self.getDirectSourceEntity().getId());
            line.addProperty("damageSource", self.getDamageSource().getMsgId());
            line.addProperty("blockInteraction", self.getBlockInteraction().name());
        });
    }

    @Inject(method = "explode", at = @At("RETURN"))
    private void comet$explodeEnd(CallbackInfoReturnable<Integer> cir) {
        ServerExplosion self = (ServerExplosion) (Object) this;
        Entity source = self.getDirectSourceEntity();
        ServerGroundTruth.event("explosion_end", line -> {
            line.addProperty("x", self.center().x);
            line.addProperty("y", self.center().y);
            line.addProperty("z", self.center().z);
            line.addProperty("radius", self.radius());
            line.addProperty("source", source == null ? -1 : source.getId());
            line.addProperty("destroyedBlocks", cir.getReturnValue());
            line.addProperty("hitPlayers", self.getHitPlayers().size());
        });
    }
}
