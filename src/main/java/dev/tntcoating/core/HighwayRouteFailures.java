package dev.tntcoating.core;

import java.util.Arrays;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

/** Small world-local cache of failed approaches, cleared by the caller on world changes. */
public final class HighwayRouteFailures {
    private static final int LIFETIME = 200;
    private final Vec3[] positions = new Vec3[8];
    private final int[] ticks = new int[8];
    private int cursor;

    public void record(Vec3 position, int tick) {
        if (position == null || !Double.isFinite(position.x) || !Double.isFinite(position.y)
            || !Double.isFinite(position.z)) return;
        for (int i = 0; i < positions.length; i++) {
            if (positions[i] != null && positions[i].distanceToSqr(position) < .25) {
                ticks[i] = tick;
                return;
            }
        }
        positions[cursor] = position;
        ticks[cursor] = tick;
        cursor = (cursor + 1) % positions.length;
    }

    /** Reject only boxes touching the failed approach, allowing opposite-side and higher routes. */
    public boolean blocked(AABB box, int tick) {
        for (int i = 0; i < positions.length; i++) {
            Vec3 point = positions[i];
            if (point == null) continue;
            int age = tick - ticks[i];
            if (age < 0 || age >= LIFETIME) {
                positions[i] = null;
                continue;
            }
            if (box.maxX > point.x - .4 && box.minX < point.x + .4
                && box.maxY > point.y - .1 && box.minY < point.y + .5
                && box.maxZ > point.z - .4 && box.minZ < point.z + .4) return true;
        }
        return false;
    }

    public void clear() {
        Arrays.fill(positions, null);
        cursor = 0;
    }
}
