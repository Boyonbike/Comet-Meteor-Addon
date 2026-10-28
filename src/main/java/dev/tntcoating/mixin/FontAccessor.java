package dev.tntcoating.mixin;

import net.minecraft.client.gui.Font;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/** Lets Chat Tools build a font that shares Minecraft's glyph cache but draws in its own typeface. */
@Mixin(Font.class)
public interface FontAccessor {
    @Accessor("provider")
    Font.Provider comet$provider();
}
