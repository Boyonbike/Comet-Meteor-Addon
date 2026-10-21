package dev.tntcoating.mixin;

import dev.tntcoating.core.ScaffoldPlacementFeedback;
import meteordevelopment.meteorclient.systems.modules.Modules;
import meteordevelopment.meteorclient.systems.modules.movement.Scaffold;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.network.protocol.game.ClientboundBlockUpdatePacket;
import net.minecraft.network.protocol.game.ClientboundPlayerPositionPacket;
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
        // Not a ScaffoldPlacementFeedback if Comet's Scaffold upgrade was skipped for this Meteor build.
        if (Modules.get().get(Scaffold.class) instanceof ScaffoldPlacementFeedback feedback && ((Scaffold) feedback).isActive()) {
            feedback.comet$serverBlockUpdate(packet.getPos(), packet.getBlockState());
        }
    }

    @Inject(method = "handleChunkBlocksUpdate", at = @At("TAIL"))
    private void comet$sectionUpdate(ClientboundSectionBlocksUpdatePacket packet, CallbackInfo ci) {
        if (Modules.get() == null) return;
        if (Modules.get().get(Scaffold.class) instanceof ScaffoldPlacementFeedback feedback && ((Scaffold) feedback).isActive()) {
            packet.runUpdates(feedback::comet$serverBlockUpdate);
        }
    }

    // The client never updates its last reported position here, so at TAIL both the target and the rejected position are known.
    @Inject(method = "handleMovePlayer", at = @At("TAIL"))
    private void comet$setback(ClientboundPlayerPositionPacket packet, CallbackInfo ci) {
        if (Modules.get() == null) return;
        if (Modules.get().get(Scaffold.class) instanceof ScaffoldPlacementFeedback feedback && ((Scaffold) feedback).isActive()) {
            feedback.comet$serverSetback();
        }
    }
}
