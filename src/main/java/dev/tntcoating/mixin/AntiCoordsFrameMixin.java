package dev.tntcoating.mixin;

import dev.tntcoating.modules.AntiCoords;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.renderer.GameRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(GameRenderer.class)
public abstract class AntiCoordsFrameMixin {
    @Inject(method = "render", at = @At("HEAD"))
    private void comet$hideHudForScreenshot(DeltaTracker deltaTracker, boolean renderLevel, CallbackInfo ci) {
        AntiCoords.beforeFrame();
    }

    // Before any GUI is built: Meteor draws its 2D layer, and flushes vanilla's HUD, while the HUD is built.
    @Inject(method = "render", at = @At(value = "INVOKE",
        target = "Lnet/minecraft/client/gui/render/state/GuiRenderState;reset()V"))
    private void comet$captureWorld(DeltaTracker deltaTracker, boolean renderLevel, CallbackInfo ci) {
        AntiCoords.captureWorld();
    }
}
