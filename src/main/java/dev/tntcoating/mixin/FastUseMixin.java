package dev.tntcoating.mixin;

import com.llamalad7.mixinextras.sugar.Local;
import dev.tntcoating.modules.SafeFastUse;
import meteordevelopment.meteorclient.systems.modules.Modules;
import net.minecraft.client.Minecraft;
import net.minecraft.world.item.ItemStack;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(Minecraft.class)
public abstract class FastUseMixin {
    @Shadow private int rightClickDelay;

    // Meteor's Fast Use injects BEFORE this call. AFTER gives Comet's active module precedence.
    @Inject(method = "startUseItem", at = @At(value = "INVOKE",
        target = "Lnet/minecraft/world/item/ItemStack;isItemEnabled(Lnet/minecraft/world/flag/FeatureFlagSet;)Z",
        shift = At.Shift.AFTER))
    private void comet$fastUseCooldown(CallbackInfo ci, @Local(name = "heldItem") ItemStack heldItem) {
        SafeFastUse fastUse = Modules.get().get(SafeFastUse.class);
        if (fastUse != null && fastUse.isActive()) rightClickDelay = fastUse.getItemUseCooldown(heldItem);
    }
}
