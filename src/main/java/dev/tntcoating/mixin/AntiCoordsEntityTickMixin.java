package dev.tntcoating.mixin;

import dev.tntcoating.modules.AntiCoords;
import net.minecraft.client.multiplayer.ClientLevel;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Your movement collides during the entity tick, so it keeps the real offsets there and stays in sync with the server. */
@Mixin(ClientLevel.class)
public abstract class AntiCoordsEntityTickMixin {
    @Inject(method = "tickEntities", at = @At("HEAD"))
    private void comet$realOffsets(CallbackInfo ci) {
        AntiCoords.entityTick(true);
    }

    @Inject(method = "tickEntities", at = @At("TAIL"))
    private void comet$spoofedOffsets(CallbackInfo ci) {
        AntiCoords.entityTick(false);
    }
}
