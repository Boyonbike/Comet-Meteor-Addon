package dev.tntcoating.core;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Function;

/** Predicts terrain removal using the weakest vanilla TNT explosion rays. */
public final class TntBlastPlanner {
    public static final int RADIUS = 4;

    private static final float INITIAL_STRENGTH = RADIUS * 0.7f;
    private static final double STEP = (double) 0.3f;
    private static final float TRAVEL_ATTENUATION = 0.22500001f;
    private static final Ray[] RAYS = rays();

    private TntBlastPlanner() {}

    public record Cell(int x, int y, int z) {}

    /** Negative resistance represents air; terrain marks blocks eligible for removal. */
    public record Material(float resistance, boolean terrain) {
        public static final Material AIR = new Material(-1, false);
        public static final Material BLOCKED = new Material(Float.POSITIVE_INFINITY, false);
    }

    private record Ray(double x, double y, double z) {}

    /**
     * Returns terrain reached from the center of a TNT cell, accounting for shielding.
     * The TNT itself has disappeared before the blast, so its cell is treated as air.
     * Materials are sampled once per cell for a consistent, inexpensive prediction.
     */
    public static Set<Cell> coverage(Cell origin, Function<Cell, Material> sample) {
        Objects.requireNonNull(origin, "origin");
        Objects.requireNonNull(sample, "sample");
        Set<Cell> covered = new HashSet<>();
        Map<Cell, Material> materials = new HashMap<>();
        materials.put(origin, Material.AIR);

        for (Ray ray : RAYS) {
            float strength = INITIAL_STRENGTH;
            double x = origin.x() + 0.5;
            double y = origin.y() + 0.5;
            double z = origin.z() + 0.5;

            while (strength > 0) {
                Cell cell = new Cell((int) Math.floor(x), (int) Math.floor(y), (int) Math.floor(z));
                Material material = materials.computeIfAbsent(cell,
                    position -> Objects.requireNonNull(sample.apply(position), "sample material"));
                if (material.resistance() >= 0) {
                    strength -= (material.resistance() + 0.3f) * 0.3f;
                }
                if (strength > 0 && material.terrain()) covered.add(cell);

                x += ray.x() * STEP;
                y += ray.y() * STEP;
                z += ray.z() * STEP;
                strength -= TRAVEL_ATTENUATION;
            }
        }
        return covered;
    }

    /** True when other active or pending TNT sources cover every terrain face at a target. */
    public static boolean coveredByOthers(Cell target, Collection<Cell> required, Collection<Cell> sources,
                                         Function<Cell, Set<Cell>> coverage) {
        Set<Cell> remaining = new HashSet<>(required);
        if (remaining.isEmpty()) return false;
        for (Cell source : sources) {
            // A deferred placement must not cancel itself because of its own reservation.
            if (source.equals(target)) continue;
            remaining.removeAll(coverage.apply(source));
            if (remaining.isEmpty()) return true;
        }
        return false;
    }

    private static Ray[] rays() {
        ArrayList<Ray> rays = new ArrayList<>(1352);
        for (int x = 0; x < 16; x++) {
            for (int y = 0; y < 16; y++) {
                for (int z = 0; z < 16; z++) {
                    if (x != 0 && x != 15 && y != 0 && y != 15 && z != 0 && z != 15) continue;
                    // Vanilla calculates these coordinates as floats before normalizing in double precision.
                    double dx = x / 15f * 2f - 1f;
                    double dy = y / 15f * 2f - 1f;
                    double dz = z / 15f * 2f - 1f;
                    double length = Math.sqrt(dx * dx + dy * dy + dz * dz);
                    rays.add(new Ray(dx / length, dy / length, dz / length));
                }
            }
        }
        return rays.toArray(Ray[]::new);
    }
}
