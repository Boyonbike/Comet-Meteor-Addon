/*
 * Original Comet implementation by Boyonabike.
 * Independent: written from the pinned 26.2 artifact and Meteor build 35 behaviour; no third-party code.
 * SPDX-License-Identifier: CC0-1.0
 */

package dev.tntcoating.core.combat;

import java.util.Objects;

/**
 * A three-dimensional, collision-aware motion predictor for a player-shaped entity, capped at four ticks.
 *
 * <p>Each tick gravity and the sampled velocity are applied axis by axis against a {@link CollisionView}.
 * A blocked axis stops that component (sliding along the other axis) and a landing stops downward motion. A
 * predicted reversal of the motion (the vertical arc apex, where knockback ends and gravity takes over) also
 * lowers confidence, because its exact tick depends on the knockback and tick phase beyond this sample.
 * Teleports and setbacks invalidate prediction entirely. Predictions beyond the cap, and predictions for
 * invalidated samples, are {@link Confidence#LOST}.</p>
 *
 * <p>This is a shape-limited predictor, not a movement simulation: it predicts the feet hull, not exact
 * jump arcs.</p>
 */
public final class CombatMotionModel {
    /** Hard prediction cap; beyond this the model reports lost confidence. */
    public static final int MAX_TICKS = 4;

    /** Entity half-width used for collisions (vanilla player width 0.6). */
    static final double HALF_WIDTH = 0.3;
    /** Entity height used for collisions (vanilla player height 1.8). */
    static final double HEIGHT = 1.8;

    private static final double GRAVITY = 0.08;
    private static final double TERMINAL_VELOCITY = 3.92;
    private static final double EPSILON = 1.0e-7;

    public PredictedBox predict(MotionSample sample, int ticks, CollisionView collision) {
        Objects.requireNonNull(sample, "sample");
        Objects.requireNonNull(collision, "collision");
        if (ticks < 0) throw new IllegalArgumentException("ticks must be non-negative: " + ticks);
        if (ticks == 0) return PredictedBox.stationarity(sample.position());
        if (ticks > MAX_TICKS || sample.teleportOrSetback()) return PredictedBox.lost(sample.position());

        double x = sample.x();
        double y = sample.y();
        double z = sample.z();
        double vx = sample.vx();
        double vy = sample.vy();
        double vz = sample.vz();
        boolean grounded = sample.onGround();

        double minX = x;
        double minY = y;
        double minZ = z;
        double maxX = x;
        double maxY = y;
        double maxZ = z;
        int collisions = 0;
        boolean reversed = false;

        for (int tick = 0; tick < ticks; tick++) {
            double previousVy = vy;
            vy = Math.max(vy - GRAVITY, -TERMINAL_VELOCITY);
            if (previousVy > 0.0 && vy < 0.0) reversed = true;

            double stepX = vx;
            if (stepX != 0.0 && blocked(x + stepX, y, z, collision)) {
                vx = 0.0;
                stepX = 0.0;
                collisions++;
            }
            double stepZ = vz;
            if (stepZ != 0.0 && blocked(x + stepX, y, z + stepZ, collision)) {
                vz = 0.0;
                stepZ = 0.0;
                collisions++;
            }
            double stepY = vy;
            if (stepY != 0.0 && blocked(x + stepX, y + stepY, z + stepZ, collision)) {
                if (stepY < 0.0) grounded = true;
                vy = 0.0;
                stepY = 0.0;
                collisions++;
            } else {
                grounded = false;
            }

            x += stepX;
            z += stepZ;
            y += stepY;

            minX = Math.min(minX, x);
            minY = Math.min(minY, y);
            minZ = Math.min(minZ, z);
            maxX = Math.max(maxX, x);
            maxY = Math.max(maxY, y);
            maxZ = Math.max(maxZ, z);
        }

        Confidence confidence = collisions == 0 ? Confidence.STRONG
            : collisions <= 2 ? Confidence.WEAK : Confidence.LOST;
        if (reversed) confidence = confidence.lowerBy();
        if (grounded) confidence = confidence.lowerBy();
        return new PredictedBox(new Box(new Vec(minX, minY, minZ), new Vec(maxX, maxY, maxZ)),
            confidence);
    }

    /** True when a player-shaped box at feet position (x, y, z) intersects any blocking cell. */
    static boolean blocked(double x, double y, double z, CollisionView collision) {
        int minCellX = floor(x - HALF_WIDTH + EPSILON);
        int maxCellX = floor(x + HALF_WIDTH - EPSILON);
        int minCellY = floor(y + EPSILON);
        int maxCellY = floor(y + HEIGHT - EPSILON);
        int minCellZ = floor(z - HALF_WIDTH + EPSILON);
        int maxCellZ = floor(z + HALF_WIDTH - EPSILON);
        for (int cellX = minCellX; cellX <= maxCellX; cellX++) {
            for (int cellY = minCellY; cellY <= maxCellY; cellY++) {
                for (int cellZ = minCellZ; cellZ <= maxCellZ; cellZ++) {
                    if (collision.blocks(new Cell(cellX, cellY, cellZ))) return true;
                }
            }
        }
        return false;
    }

    private static int floor(double value) {
        return (int) Math.floor(value);
    }
}
