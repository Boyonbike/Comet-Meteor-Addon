package dev.tntcoating.mixin;

import net.minecraft.client.Minecraft;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/** Vanilla's right-click delay, so automated right-clicks share the player's own pace. */
@Mixin(Minecraft.class)
public interface MinecraftAccessor {
    @Accessor("rightClickDelay")
    int comet$getRightClickDelay();

    @Accessor("rightClickDelay")
    void comet$setRightClickDelay(int delay);
}
