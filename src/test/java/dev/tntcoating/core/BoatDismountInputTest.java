package dev.tntcoating.core;

import net.minecraft.world.entity.player.Input;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class BoatDismountInputTest {
    private final Input sneaking = new Input(true, false, true, false, true, true, true);
    @Test void unboundSettingPreservesMinecraftSneak() {
        assertSame(sneaking, BoatDismountInput.apply(sneaking, true, false, false));
    }
    @Test void customBindingSuppressesOrdinarySneakWhileRidingBoat() {
        Input result = BoatDismountInput.apply(sneaking, true, true, false);
        assertFalse(result.shift());
        assertTrue(result.forward()); assertTrue(result.left()); assertTrue(result.jump()); assertTrue(result.sprint());
        assertFalse(result.backward()); assertFalse(result.right());
    }
    @Test void pressingCustomKeySendsNormalDismountInput() {
        Input original = new Input(false, false, false, false, false, false, false);
        assertTrue(BoatDismountInput.apply(original, true, true, true).shift());
    }
    @Test void customBindingDoesNotChangeOnFootOrOtherVehicleInput() {
        assertSame(sneaking, BoatDismountInput.apply(sneaking, false, true, false));
    }
}
