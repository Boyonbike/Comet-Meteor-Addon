package dev.tntcoating.mixin;

import net.minecraft.world.entity.animal.allay.Allay;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Invoker;

/** Whether an allay's duplication cooldown is over, from its synched data. */
@Mixin(Allay.class)
public interface AllayInvoker {
    @Invoker("canDuplicate")
    boolean comet$canDuplicate();
}
