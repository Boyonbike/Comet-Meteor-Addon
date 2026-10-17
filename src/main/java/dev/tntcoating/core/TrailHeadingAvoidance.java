package dev.tntcoating.core;

import java.util.ArrayList;
import java.util.List;

/** Keeps a local route retry from repeatedly requesting headings whose actual path was unsafe. */
public final class TrailHeadingAvoidance {
    private static final double REJECT_HALF_WIDTH = 20;
    private static final double ATTEMPT_RADIUS = 128;

    private record Sector(double start, double end) {}

    // Store the union of rejected sectors, rather than a queue which could forget an old failure
    // after repeated noisy rejections. Forty-degree sectors permit at most nine disjoint spans
    // on a circle (plus a split at zero), so storage is bounded without eviction.
    private final List<Sector> sectors = new ArrayList<>();
    private boolean hasOrigin;
    private double originX;
    private double originZ;

    public void reject(double x, double z, float yaw) {
        validate(x, z, yaw);
        expire(x, z);
        if (!hasOrigin) {
            hasOrigin = true;
            originX = x;
            originZ = z;
        }
        double center = normalize(yaw);
        double start = center - REJECT_HALF_WIDTH;
        double end = center + REJECT_HALF_WIDTH;
        if (start < 0) {
            add(0, end);
            add(start + 360, 360);
        } else if (end >= 360) {
            add(start, 360);
            add(0, end - 360);
        } else add(start, end);
    }

    /** Attempts expire by displacement from the first rejection, never by waiting or retrying. */
    public boolean blocked(double x, double z, float candidateYaw) {
        validate(x, z, candidateYaw);
        expire(x, z);
        double yaw = normalize(candidateYaw);
        for (Sector sector : sectors)
            if (yaw >= sector.start() && yaw <= sector.end()) return true;
        return false;
    }

    public void clear() {
        sectors.clear();
        hasOrigin = false;
    }

    private void expire(double x, double z) {
        if (hasOrigin && Math.hypot(x - originX, z - originZ) >= ATTEMPT_RADIUS) clear();
    }

    private void add(double start, double end) {
        int insert = 0;
        while (insert < sectors.size() && sectors.get(insert).end() < start) insert++;
        while (insert < sectors.size() && sectors.get(insert).start() <= end) {
            Sector previous = sectors.remove(insert);
            start = Math.min(start, previous.start());
            end = Math.max(end, previous.end());
        }
        sectors.add(insert, new Sector(start, end));
    }

    private static double normalize(double yaw) {
        double result = yaw % 360;
        return result < 0 ? result + 360 : result;
    }

    private static void validate(double x, double z, float yaw) {
        if (!Double.isFinite(x) || !Double.isFinite(z) || !Float.isFinite(yaw))
            throw new IllegalArgumentException("position and yaw must be finite");
    }
}
