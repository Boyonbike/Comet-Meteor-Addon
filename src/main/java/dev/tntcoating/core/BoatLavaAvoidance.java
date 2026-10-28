package dev.tntcoating.core;

import java.util.List;
import java.util.function.Predicate;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

/** Selectable lava clearance, independent of solid-block phasing. */
public final class BoatLavaAvoidance {
    public enum Mode { BoatAndRiders, BoatOnly }
    private BoatLavaAvoidance() {}

    public static boolean blocked(AABB boat, List<AABB> riders, Vec3 movement, Mode mode,
                                  Predicate<AABB> containsLava) {
        if (containsLava.test(boat.expandTowards(movement))) return true;
        return mode == Mode.BoatAndRiders
            && riders.stream().anyMatch(rider -> containsLava.test(rider.expandTowards(movement)));
    }
}
