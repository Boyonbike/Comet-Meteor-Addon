package dev.tntcoating.mixin;

import net.minecraft.client.player.LocalPlayer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/** The position, rotation and ground flag this client last reported to the server. */
@Mixin(LocalPlayer.class)
public interface LocalPlayerAccessor {
    @Accessor("xLast") double comet$xLast();
    @Accessor("yLast") double comet$yLast();
    @Accessor("zLast") double comet$zLast();
    @Accessor("yRotLast") float comet$yRotLast();
    @Accessor("xRotLast") float comet$xRotLast();
    @Accessor("lastOnGround") boolean comet$lastOnGround();
}
