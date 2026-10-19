package dev.tntcoating.core;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.function.IntPredicate;

/** Small, game-independent geometry helpers for preserving a walkable lane. */
public final class PassageGeometry {
    public record Cell(int x, int z) {}

    private PassageGeometry() {}

    /** Samples every quarter block so diagonal block-boundary crossings cannot be skipped. */
    public static List<Cell> lane(double x, double z, double dx, double dz, double length) {
        if (length < 0) throw new IllegalArgumentException("length");
        double magnitude = Math.sqrt(dx * dx + dz * dz);
        LinkedHashSet<Cell> cells = new LinkedHashSet<>();
        if (magnitude < .0001) {
            cells.add(new Cell((int) Math.floor(x), (int) Math.floor(z)));
            return List.copyOf(cells);
        }
        dx /= magnitude;
        dz /= magnitude;
        int samples = (int) Math.ceil(length * 4);
        for (int step = 0; step <= samples; step++) {
            double distance = Math.min(length, step * .25);
            cells.add(new Cell((int) Math.floor(x + dx * distance), (int) Math.floor(z + dz * distance)));
        }
        return new ArrayList<>(cells);
    }

    /**
     * The centre is the proposed walking lane. Offsets run across the passage; true means that a
     * two-block-high player column is open. A passage is narrow only when at most {@code maxWidth}
     * contiguous columns are open and a closed column bounds both sides.
     */
    public static boolean narrowCorridor(IntPredicate openAtLateralOffset, int maxWidth) {
        if (maxWidth < 1) throw new IllegalArgumentException("maxWidth");
        if (!openAtLateralOffset.test(0)) return false;
        int negative = 0;
        while (negative < maxWidth && openAtLateralOffset.test(-negative - 1)) negative++;
        boolean negativeBounded = !openAtLateralOffset.test(-negative - 1);
        int positive = 0;
        while (positive < maxWidth && openAtLateralOffset.test(positive + 1)) positive++;
        boolean positiveBounded = !openAtLateralOffset.test(positive + 1);
        return negativeBounded && positiveBounded && negative + 1 + positive <= maxWidth;
    }
}
