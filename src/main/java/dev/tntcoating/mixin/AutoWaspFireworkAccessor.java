package dev.tntcoating.mixin;

import java.util.OptionalInt;
import net.minecraft.network.syncher.EntityDataAccessor;
import net.minecraft.world.entity.projectile.FireworkRocketEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/** Auto Wasp's own view of which entity a rocket boosts, available as soon as its synched data arrives. */
@Mixin(FireworkRocketEntity.class)
public interface AutoWaspFireworkAccessor {
    @Accessor("DATA_ATTACHED_TO_TARGET")
    static EntityDataAccessor<OptionalInt> autowasp$attachedTarget() {
        throw new AssertionError();
    }
}
