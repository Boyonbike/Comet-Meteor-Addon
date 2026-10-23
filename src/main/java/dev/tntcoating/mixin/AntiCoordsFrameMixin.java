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
    @Inject(method = "extract", at = @At("HEAD"))
    private void comet$hideHudForScreenshot(DeltaTracker deltaTracker, boolean advanceGameTime, CallbackInfo ci) {
        AntiCoords.beforeFrame();
    }

    // Vanilla's HUD, F3, chat, menus and toasts, and Meteor's 2D layer, are all drawn by this call.
    @Inject(method = "render", at = @At(value = "INVOKE",
        target = "Lnet/minecraft/client/gui/render/GuiRenderer;render()V"))
    private void comet$captureWorld(DeltaTracker deltaTracker, boolean advanceGameTime, CallbackInfo ci) {
        AntiCoords.captureWorld();
    }
}
