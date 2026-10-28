/* SPDX-License-Identifier: GPL-3.0-only */
package dev.tntcoating.core;

import net.minecraft.world.entity.player.Input;

/** Replace only the boat dismount bit; vanilla movement and non-boat sneak stay unchanged. */
public final class BoatDismountInput {
    private BoatDismountInput() {}
    public static Input apply(Input original, boolean ridingBoat, boolean customBinding, boolean pressed) {
        if (!ridingBoat || !customBinding || original.shift() == pressed) return original;
        return new Input(original.forward(), original.backward(), original.left(), original.right(),
            original.jump(), pressed, original.sprint());
    }
}
