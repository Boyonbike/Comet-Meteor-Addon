/*
 * Original Comet test harness code by Boyonabike.
 * Independent: written for this repository against the pinned Minecraft 26.2
 * server classes; no third-party client code.
 * SPDX-License-Identifier: CC0-1.0
 */

package dev.tntcoating.gametest.mixin;

import dev.tntcoating.gametest.server.ServerGroundTruth;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(ServerLevel.class)
public abstract class ServerLevelMixin {
    @Inject(method = "broadcastEntityEvent", at = @At("HEAD"))
    private void comet$entityEvent(Entity entity, byte event, CallbackInfo ci) {
        ServerGroundTruth.event("entity_event", line -> {
            line.addProperty("entity", entity.getId());
            line.addProperty("uuid", entity.getUUID().toString());
            line.addProperty("event", event);
        });
    }
}
