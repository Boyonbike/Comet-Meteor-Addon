package dev.tntcoating.mixin;

import net.minecraft.client.player.LocalPlayer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/** The position this client last reported to the server. */
@Mixin(LocalPlayer.class)
public interface LocalPlayerAccessor {
    @Accessor("xLast") double comet$xLast();
    @Accessor("yLast") double comet$yLast();
    @Accessor("zLast") double comet$zLast();
}
