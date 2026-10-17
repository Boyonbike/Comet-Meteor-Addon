package dev.tntcoating.mixin;

import dev.tntcoating.core.ScaffoldPlacementFeedback;
import meteordevelopment.meteorclient.systems.modules.Modules;
import meteordevelopment.meteorclient.systems.modules.movement.Scaffold;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.network.protocol.game.ClientboundBlockUpdatePacket;
import net.minecraft.network.protocol.game.ClientboundSectionBlocksUpdatePacket;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** TAIL runs on the client thread after server updates, unlike receive events or local prediction events. */
@Mixin(ClientPacketListener.class)
public abstract class ScaffoldBlockUpdatesMixin {
    @Inject(method = "handleBlockUpdate", at = @At("TAIL"))
    private void comet$blockUpdate(ClientboundBlockUpdatePacket packet, CallbackInfo ci) {
        if (Modules.get() == null) return;
        Scaffold scaffold = Modules.get().get(Scaffold.class);
        if (scaffold != null && scaffold.isActive()) {
            ((ScaffoldPlacementFeedback) scaffold).comet$serverBlockUpdate(packet.getPos(), packet.getBlockState());
        }
    }

    @Inject(method = "handleChunkBlocksUpdate", at = @At("TAIL"))
    private void comet$sectionUpdate(ClientboundSectionBlocksUpdatePacket packet, CallbackInfo ci) {
        if (Modules.get() == null) return;
        Scaffold scaffold = Modules.get().get(Scaffold.class);
        if (scaffold != null && scaffold.isActive()) {
            ScaffoldPlacementFeedback feedback = (ScaffoldPlacementFeedback) scaffold;
            packet.runUpdates(feedback::comet$serverBlockUpdate);
        }
    }
}
