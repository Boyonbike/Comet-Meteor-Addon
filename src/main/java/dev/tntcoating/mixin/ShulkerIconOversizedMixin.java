package dev.tntcoating.mixin;

import com.llamalad7.mixinextras.sugar.Local;
import dev.tntcoating.render.ShulkerIconRenderer;
import net.minecraft.client.gui.render.pip.PictureInPictureRenderer;
import net.minecraft.client.gui.render.state.BlitRenderState;
import net.minecraft.client.gui.render.state.pip.OversizedItemRenderState;
import net.minecraft.client.gui.render.state.pip.PictureInPictureRenderState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyArg;

@Mixin(PictureInPictureRenderer.class)
public abstract class ShulkerIconOversizedMixin {
    @ModifyArg(method = "blitTexture", at = @At(value = "INVOKE", target = "Lnet/minecraft/client/gui/render/state/GuiRenderState;submitBlitToCurrentLayer(Lnet/minecraft/client/gui/render/state/BlitRenderState;)V"))
    private BlitRenderState comet$solidOversizedIcon(BlitRenderState blit, @Local(argsOnly = true) PictureInPictureRenderState state) {
        return state instanceof OversizedItemRenderState item ? ShulkerIconRenderer.colourBlit(blit, item.guiItemRenderState()) : blit;
    }
}
