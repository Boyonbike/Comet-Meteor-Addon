package dev.tntcoating.mixin;

import dev.tntcoating.core.TrajectoryCollisionRevision;
import net.minecraft.client.multiplayer.ClientPacketListener;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Invalidate after the client thread applies changes that can alter any simulated collision. */
@Mixin(ClientPacketListener.class)
public abstract class TrajectoriesWorldUpdatesMixin {
    @Inject(method = {
        "handleMoveEntity", "handleTeleportEntity", "handleEntityPositionSync", "handleSetEntityMotion",
        "handleSetEntityData", "handleSetEquipment", "handleEntityEvent", "handleUpdateAttributes",
        "handleUpdateMobEffect", "handleRemoveMobEffect", "handleSetEntityPassengersPacket",
        "handleMoveVehicle", "handleMinecartAlongTrack", "handleForgetLevelChunk",
        "handleSetChunkCacheRadius", "handleSetChunkCacheCenter", "handleBlockChangedAck",
        "handleInitializeBorder", "handleSetBorderCenter", "handleSetBorderSize", "handleSetBorderLerpSize",
        "handlePlayerInfoUpdate", "handleGameEvent", "handleBlockEvent"
    }, at = @At("TAIL"))
    private void comet$appliedCollisionUpdate(CallbackInfo ci) {
        TrajectoryCollisionRevision.invalidate();
    }
}
