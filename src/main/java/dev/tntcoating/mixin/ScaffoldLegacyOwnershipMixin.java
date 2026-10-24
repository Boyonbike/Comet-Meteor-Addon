package dev.tntcoating.mixin;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import dev.tntcoating.core.scaffold.ScaffoldCoordinator;
import meteordevelopment.meteorclient.systems.modules.Module;
import meteordevelopment.meteorclient.systems.modules.movement.Scaffold;
import net.minecraft.core.BlockPos;
import org.spongepowered.asm.mixin.Mixin;

/** Minimal handoff guard is independent of the optional Comet upgrade to Meteor's Scaffold. */
@Mixin(value = Scaffold.class, remap = false)
public abstract class ScaffoldLegacyOwnershipMixin {
    @WrapMethod(method = "place(Lnet/minecraft/core/BlockPos;)Z")
    private boolean comet$scopeLegacyPlacement(BlockPos position, Operation<Boolean> original) {
        return ScaffoldCoordinator.legacyPlacement(position, ((Module) (Object) this)::isActive, () -> original.call(position));
    }
}
