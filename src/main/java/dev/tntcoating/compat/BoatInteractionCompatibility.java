package dev.tntcoating.compat;

import net.minecraft.network.protocol.game.ServerboundInteractPacket;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.phys.Vec3;

/** 1.21.11 combines attacks and boarding interactions in the same packet type. */
public final class BoatInteractionCompatibility {
    private BoatInteractionCompatibility() {}

    public static boolean isBoarding(ServerboundInteractPacket packet) {
        class Interaction implements ServerboundInteractPacket.Handler {
            boolean boarding;
            @Override public void onInteraction(InteractionHand hand) { boarding = true; }
            @Override public void onInteraction(InteractionHand hand, Vec3 location) { boarding = true; }
            @Override public void onAttack() {}
        }
        var interaction = new Interaction();
        packet.dispatch(interaction);
        return interaction.boarding;
    }
}
