package dev.tntcoating.mixin;

import com.llamalad7.mixinextras.sugar.Local;
import dev.tntcoating.render.ShulkerIconRenderer;
import net.minecraft.client.gui.render.GuiRenderer;
import net.minecraft.client.renderer.state.gui.BlitRenderState;
import net.minecraft.client.renderer.state.gui.GuiItemRenderState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyArg;

@Mixin(GuiRenderer.class)
public abstract class ShulkerIconAtlasMixin {
    @ModifyArg(method = "submitBlitFromItemAtlas", at = @At(value = "INVOKE", target = "Lnet/minecraft/client/renderer/state/gui/GuiRenderState;addBlitToCurrentLayer(Lnet/minecraft/client/renderer/state/gui/BlitRenderState;)V"))
    private BlitRenderState comet$solidIcon(BlitRenderState blit, @Local(argsOnly = true) GuiItemRenderState item) {
        return ShulkerIconRenderer.colourBlit(blit, item);
    }
}
