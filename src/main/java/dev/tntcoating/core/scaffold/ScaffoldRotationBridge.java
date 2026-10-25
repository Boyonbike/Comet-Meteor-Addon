package dev.tntcoating.core.scaffold;

import dev.tntcoating.compat.MeteorUpgrades;
import dev.tntcoating.mixin.ScaffoldRotationsAccessor;
import meteordevelopment.meteorclient.utils.player.Rotations;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.game.ServerboundMovePlayerPacket;
import java.util.function.BooleanSupplier;
import static meteordevelopment.meteorclient.MeteorClient.mc;

/** Uses only the ordinary vanilla sendPosition slot, after all SendMovementPacketsEvent.Pre listeners. */
public final class ScaffoldRotationBridge {
    public static final ScaffoldRotationBridge INSTANCE = new ScaffoldRotationBridge();
    private final OrdinaryRotationSlot slot = new OrdinaryRotationSlot();
    private LocalPlayer appliedPlayer;
    private float visualYaw, visualPitch, appliedYaw, appliedPitch;
    private ScaffoldRotationBridge() {}

    public boolean available() {
        return MeteorUpgrades.applied("ScaffoldMovementMixin") && MeteorUpgrades.applied("ScaffoldRotationsAccessor")
            && MeteorUpgrades.applied("ScaffoldLegacyOwnershipMixin") && MeteorUpgrades.applied("ScaffoldLegacyDispatchMixin");
    }
    public boolean prepare(float yaw, float pitch, BooleanSupplier valid, Runnable callback) {
        return available() && slot.prepare(yaw, pitch, valid, callback);
    }
    public void cancel() { slot.cancel(); restore(); }
    public boolean orientationMatches(float yaw, float pitch) { return slot.orientationMatches(yaw, pitch); }
    public void clear() { cancel(); slot.clear(); }

    /** Injected immediately before vanilla's first yaw read, after Meteor's complete synchronous Pre event. */
    public void begin(LocalPlayer player) {
        if (!available() || appliedPlayer != null) return;
        boolean busy = mc.player != player || mc.getCameraEntity() != player || player.isPassenger()
            || Rotations.rotating || !ScaffoldRotationsAccessor.comet$queue().isEmpty();
        OrdinaryRotationSlot.Intent intent = slot.begin(busy);
        if (intent == null) return;
        appliedPlayer = player;
        visualYaw = player.getYRot(); visualPitch = player.getXRot();
        appliedYaw = intent.yaw(); appliedPitch = intent.pitch();
        player.setYRot(appliedYaw); player.setXRot(appliedPitch);
    }
    public void observePacket(Packet<?> packet) {
        if (packet instanceof ServerboundMovePlayerPacket move) {
            slot.observeMovement(move.hasRotation(), move.getYRot(0), move.getXRot(0));
            // Meteor's public facing metadata is updated only by an actually sent own rotation.
            if (appliedPlayer != null && move.hasRotation() && slot.orientationMatches(appliedYaw, appliedPitch))
                Rotations.setCamRotation(move.getYRot(0), move.getXRot(0));
        }
    }
    /** WrapMethod finally handles vanilla exceptions and injected cancellations as well as normal returns. */
    public void finish(boolean successful) {
        restore();
        Runnable callback = slot.finish(successful);
        if (callback != null) callback.run();
    }
    private void restore() {
        LocalPlayer player = appliedPlayer;
        appliedPlayer = null;
        if (player != null) { player.setYRot(visualYaw); player.setXRot(visualPitch); }
    }
}
