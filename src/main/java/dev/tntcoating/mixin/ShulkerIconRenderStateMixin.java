package dev.tntcoating.mixin;

import dev.tntcoating.render.ShulkerIconRenderState;
import net.minecraft.client.gui.render.state.GuiItemRenderState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;

@Mixin(GuiItemRenderState.class)
public abstract class ShulkerIconRenderStateMixin implements ShulkerIconRenderState {
    @Unique private Integer comet$colour;
    @Override public Integer comet$iconColour() { return comet$colour; }
    @Override public void comet$iconColour(Integer colour) { comet$colour = colour; }
}
