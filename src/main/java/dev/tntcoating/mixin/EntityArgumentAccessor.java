package dev.tntcoating.mixin;

import net.minecraft.commands.arguments.EntityArgument;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

@Mixin(EntityArgument.class)
public interface EntityArgumentAccessor {
    @Accessor("playersOnly")
    boolean comet$playersOnly();
}
