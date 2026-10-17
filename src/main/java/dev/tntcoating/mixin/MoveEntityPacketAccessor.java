package dev.tntcoating.mixin;

import net.minecraft.network.protocol.game.ClientboundMoveEntityPacket;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/** Read the packet's ID without accessing a client world from the network thread. */
@Mixin(ClientboundMoveEntityPacket.class)
public interface MoveEntityPacketAccessor {
    @Accessor("entityId")
    int comet$getEntityId();
}
