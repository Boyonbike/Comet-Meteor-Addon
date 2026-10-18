package dev.tntcoating.mixin;

import dev.tntcoating.modules.ShulkerIcons;
import dev.tntcoating.render.ShulkerIconRenderer;
import dev.tntcoating.render.ShulkerIconRenderState;
import meteordevelopment.meteorclient.systems.modules.Modules;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.renderer.state.gui.GuiItemRenderState;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.ModifyArg;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(GuiGraphicsExtractor.class)
public abstract class ShulkerIconsMixin {
    @ModifyArg(method = "item(Lnet/minecraft/world/entity/LivingEntity;Lnet/minecraft/world/level/Level;Lnet/minecraft/world/item/ItemStack;III)V", at = @At(value = "INVOKE", target = "Lnet/minecraft/client/renderer/state/gui/GuiRenderState;addItem(Lnet/minecraft/client/renderer/state/gui/GuiItemRenderState;)V"))
    private GuiItemRenderState comet$iconStyle(GuiItemRenderState state) {
        ((ShulkerIconRenderState) (Object) state).comet$iconColour(ShulkerIconRenderer.extractingColour());
        return state;
    }

    // Shared by real and fake GUI items, covering container slots, creative inventory and carried stacks.
    @Inject(method = "item(Lnet/minecraft/world/entity/LivingEntity;Lnet/minecraft/world/level/Level;Lnet/minecraft/world/item/ItemStack;III)V", at = @At("TAIL"))
    private void comet$shulkerIcon(LivingEntity entity, Level level, ItemStack stack, int x, int y, int seed, CallbackInfo info) {
        Modules modules = Modules.get();
        if (modules == null) return;
        ShulkerIcons icons = modules.get(ShulkerIcons.class);
        if (icons != null) icons.drawOverlay((GuiGraphicsExtractor) (Object) this, stack, x, y);
    }
}
