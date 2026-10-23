package dev.tntcoating.modules;

import dev.tntcoating.mixin.GameModeInvoker;
import meteordevelopment.meteorclient.utils.world.BlockUtils;
import net.minecraft.client.Minecraft;
import net.minecraft.network.protocol.game.ServerboundPlayerInputPacket;
import net.minecraft.network.protocol.game.ServerboundUseItemOnPacket;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.player.Input;
import net.minecraft.world.phys.BlockHitResult;

/** Block Coat's placement interactions, including the packet sequence that sneak-uses containers. */
final class BlockCoatInteraction {
    private BlockCoatInteraction() {}

    /** Whether the support is a container (sneak-use) rather than a plain block. */
    static boolean clickable(BlockHitResult hit) {
        Minecraft mc = Minecraft.getInstance();
        return BlockUtils.isClickable(mc.level.getBlockState(hit.getBlockPos()).getBlock());
    }

    /**
     * Sneak-use containers so they act as support instead of opening their screen. In 26.1.2 the
     * shift state is carried by the ordinary player input packet.
     */
    static void use(BlockHitResult hit, InteractionHand hand, boolean containerSupports) {
        if (!clickable(hit)) {
            BlockUtils.interact(hit, hand, true);
            return;
        }
        if (!containerSupports) return;
        Minecraft mc = Minecraft.getInstance();
        Input original = mc.player.input.keyPresses;
        var connection = mc.getConnection();
        if (connection == null) return;
        Input sneaking = new Input(original.forward(), original.backward(), original.left(), original.right(),
            original.jump(), true, original.sprint());
        connection.send(new ServerboundPlayerInputPacket(sneaking));
        try {
            // Send the sequenced interaction without client-side container prediction or local crouching.
            ((GameModeInvoker) mc.gameMode).comet$startPrediction(mc.level, sequence -> new ServerboundUseItemOnPacket(hand, hit, sequence));
            mc.player.swing(hand);
        } finally {
            connection.send(new ServerboundPlayerInputPacket(original));
        }
    }
}
