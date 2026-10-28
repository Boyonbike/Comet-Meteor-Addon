package dev.tntcoating.compat;

import io.netty.buffer.Unpooled;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.protocol.game.ServerboundInteractPacket;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class BoatInteractionCompatibilityTest {
    @Test void attacksCannotArmABoardingRetry() throws Exception {
        assertFalse(isBoarding(packet(1, 0)));
    }

    @Test void bothInteractionFormsCanArmBoardingFromEitherHand() throws Exception {
        for (int action : new int[]{0, 2})
            for (int hand : new int[]{0, 1})
                assertTrue(isBoarding(packet(action, hand)));
    }

    private static boolean isBoarding(ServerboundInteractPacket packet) throws Exception {
        return BoatInteractionCompatibility.isBoarding(packet);
    }

    private static ServerboundInteractPacket packet(int action, int hand) {
        var buffer = new FriendlyByteBuf(Unpooled.buffer());
        try {
            buffer.writeVarInt(42);
            buffer.writeVarInt(action);
            if (action == 2) { buffer.writeDouble(0.5); buffer.writeDouble(0.25); buffer.writeDouble(0.5); }
            if (action != 1) buffer.writeVarInt(hand);
            buffer.writeBoolean(false);
            return ServerboundInteractPacket.STREAM_CODEC.decode(buffer);
        } finally { buffer.release(); }
    }
}
