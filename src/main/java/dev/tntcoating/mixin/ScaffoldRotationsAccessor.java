package dev.tntcoating.mixin;

import meteordevelopment.meteorclient.utils.player.Rotations;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;
import java.util.List;

/** Read-only inspection; the bridge never mutates Meteor's pooled queue or cursor. */
@Mixin(value = Rotations.class, remap = false)
public interface ScaffoldRotationsAccessor {
    @Accessor("rotations") static List<?> comet$queue() { throw new AssertionError("rotation accessor not applied"); }
}
