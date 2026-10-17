package dev.tntcoating.core;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * A spatial trail direction estimate. Chunk arrival order and the centre of a render-distance
 * rectangle are not directions: only a line supported over a useful distance can change the
 * committed axis. All positions are block coordinates and yaws use Minecraft's convention.
 */
public final class TrailLine {
    private static final int MAX_CANDIDATE_POINTS = 32;
    private static final double BIN_SIZE = 16;
    private static final double MIN_PROGRESS = 32;
    private static final double INITIAL_CAPTURE_ANGLE = 35;

    public record Config(int historySize, double corridorWidth, double lookAhead,
                         double maxCorrectionDegrees, double minSupportSpan,
                         double maxBendDegrees) {
        public Config {
            if (historySize < 4 || historySize > 4096)
                throw new IllegalArgumentException("historySize must be between 4 and 4096");
            positive(corridorWidth, "corridorWidth");
            positive(lookAhead, "lookAhead");
            positive(minSupportSpan, "minSupportSpan");
            positive(maxCorrectionDegrees, "maxCorrectionDegrees");
            positive(maxBendDegrees, "maxBendDegrees");
            if (maxCorrectionDegrees > 35 || maxBendDegrees > 35)
                throw new IllegalArgumentException("heading changes must be at most 35 degrees");
        }

        public static Config defaults() {
            return new Config(128, 24, 96, 12, 64, 12);
        }

        private static void positive(double value, String name) {
            if (!Double.isFinite(value) || value <= 0)
                throw new IllegalArgumentException(name + " must be finite and positive");
        }
    }

    /** Confidence describes current line evidence; low confidence preserves the last direction. */
    public record Estimate(float yaw, double targetX, double targetZ, float axisYaw,
                           double confidence, int supportingChunks) {}

    private record Point(double x, double z) {}
    private record Evidence(int chunks, int bins, int aheadBins, double span) {
        boolean reliable(double minimumSpan) {
            return bins >= 4 && aheadBins >= 2 && span >= minimumSpan;
        }
    }
    private record Candidate(double x, double z, double dx, double dz, Evidence evidence,
                             double score) {}

    private final Config config;
    private final ArrayDeque<Point> points = new ArrayDeque<>();
    private final Set<Point> unique = new HashSet<>();
    private boolean initialized;
    private boolean established;
    private double originX;
    private double originZ;
    private double axisX;
    private double axisZ;
    private double lastFitX;
    private double lastFitZ;
    private long observationRevision;
    private long attemptedRevision;
    private boolean hasAttempt;
    private double lastAttemptX;
    private double lastAttemptZ;

    public TrailLine() {
        this(Config.defaults());
    }

    public TrailLine(Config config) {
        this.config = java.util.Objects.requireNonNull(config);
    }

    public void reset() {
        points.clear();
        unique.clear();
        initialized = false;
        established = false;
        hasAttempt = false;
        observationRevision = 0;
    }

    public void observe(int chunkX, int chunkZ) {
        observePoint(chunkX * 16.0 + 8, chunkZ * 16.0 + 8);
    }

    /** Reports accepted trail evidence, including delayed/out-of-order chunk classifications. */
    public void observePoint(double x, double z) {
        finite(x, z);
        Point point = new Point(x, z);
        if (!unique.add(point)) return;
        points.addLast(point);
        observationRevision++;
        while (points.size() > config.historySize()) unique.remove(points.removeFirst());
    }

    public Estimate estimate(double playerX, double playerZ, float travelYaw) {
        finite(playerX, playerZ);
        if (!Float.isFinite(travelYaw)) throw new IllegalArgumentException("yaw must be finite");
        if (!initialized) {
            initialized = true;
            originX = lastFitX = playerX;
            originZ = lastFitZ = playerZ;
            axisX = -Math.sin(Math.toRadians(travelYaw));
            axisZ = Math.cos(Math.toRadians(travelYaw));
        }

        List<Point> nearby = nearbyPoints(playerX, playerZ);
        Evidence evidence = evidence(nearby, originX, originZ, axisX, axisZ, playerX, playerZ);
        double progress = (playerX - lastFitX) * axisX + (playerZ - lastFitZ) * axisZ;
        // A still-supported straight continuation wins over any busier branch. New chunks alone
        // cannot repeatedly rotate the axis while the player remains at the same place.
        double progressSinceAttempt = (playerX - lastAttemptX) * axisX + (playerZ - lastAttemptZ) * axisZ;
        boolean changed = !hasAttempt || attemptedRevision != observationRevision || progressSinceAttempt >= MIN_PROGRESS;
        if (changed && (!established || (!evidence.reliable(config.minSupportSpan()) && progress >= MIN_PROGRESS))) {
            hasAttempt = true;
            attemptedRevision = observationRevision;
            lastAttemptX = playerX;
            lastAttemptZ = playerZ;
            Candidate fit = fit(nearby, playerX, playerZ);
            if (fit != null && fit.evidence().reliable(config.minSupportSpan())) {
                originX = fit.x();
                originZ = fit.z();
                axisX = fit.dx();
                axisZ = fit.dz();
                evidence = fit.evidence();
                established = true;
                lastFitX = playerX;
                lastFitZ = playerZ;
            }
        }

        float axisYaw = yaw(axisX, axisZ);
        double correction = 0;
        if (established) {
            double crossTrack = (playerX - originX) * -axisZ + (playerZ - originZ) * axisX;
            correction = Math.toDegrees(Math.atan2(-crossTrack, config.lookAhead()));
            correction = clamp(correction, -config.maxCorrectionDegrees(), config.maxCorrectionDegrees());
        }
        // The target is a long look-ahead point along the committed line, never the centroid of
        // whichever chunks happened to arrive last. Lateral corrections cannot become a U-turn.
        float steerYaw = wrap(axisYaw + correction);
        double radians = Math.toRadians(steerYaw);
        double targetX = playerX - Math.sin(radians) * config.lookAhead();
        double targetZ = playerZ + Math.cos(radians) * config.lookAhead();
        double confidence = Math.min(1, evidence.span() / config.minSupportSpan())
            * Math.min(1, evidence.bins() / 6.0) * Math.min(1, evidence.aheadBins() / 3.0);
        return new Estimate(steerYaw, targetX, targetZ, axisYaw, confidence, evidence.chunks());
    }

    private List<Point> nearbyPoints(double playerX, double playerZ) {
        List<Point> result = new ArrayList<>();
        double reach = Math.max(256, config.lookAhead() * 4);
        for (Point point : points) {
            double dx = point.x() - playerX;
            double dz = point.z() - playerZ;
            double forward = dx * axisX + dz * axisZ;
            // Spatial selection, rather than arrival order, prevents a late batch behind the
            // player from turning the fit around or outweighing the leading edge of the trail.
            if (forward >= -32 && forward <= reach && Math.hypot(dx, dz) <= reach)
                result.add(point);
        }
        return result;
    }

    private Candidate fit(List<Point> nearby, double playerX, double playerZ) {
        if (nearby.size() < 4) return null;
        Evidence priorEvidence = evidence(nearby, originX, originZ, axisX, axisZ, playerX, playerZ);
        Candidate best = priorEvidence.reliable(config.minSupportSpan())
            ? new Candidate(originX, originZ, axisX, axisZ, priorEvidence, score(priorEvidence, 0, 0)) : null;
        // Evenly sample the bounded history, avoiding a cubic fit as render distance increases.
        List<Point> candidates = new ArrayList<>();
        int count = Math.min(MAX_CANDIDATE_POINTS, nearby.size());
        for (int i = 0; i < count; i++)
            candidates.add(nearby.get((int) ((long) i * (nearby.size() - 1) / (count - 1))));
        double maxAngle = established ? config.maxBendDegrees() : INITIAL_CAPTURE_ANGLE;
        for (int i = 0; i < candidates.size(); i++) {
            Point a = candidates.get(i);
            for (int j = i + 1; j < candidates.size(); j++) {
                Point b = candidates.get(j);
                double dx = b.x() - a.x();
                double dz = b.z() - a.z();
                double distance = Math.hypot(dx, dz);
                if (distance < config.minSupportSpan()) continue;
                dx /= distance;
                dz /= distance;
                double dot = dx * axisX + dz * axisZ;
                if (dot < 0) { dx = -dx; dz = -dz; dot = -dot; }
                double angle = Math.toDegrees(Math.acos(clamp(dot, -1, 1)));
                if (angle > maxAngle + 1e-6) continue;
                double offset = Math.abs((playerX - a.x()) * -dz + (playerZ - a.z()) * dx);
                if (offset > config.corridorWidth() * 2) continue;
                Evidence support = evidence(nearby, a.x(), a.z(), dx, dz, playerX, playerZ);
                if (!support.reliable(config.minSupportSpan())) continue;
                double score = score(support, angle, offset);
                if (best == null || score > best.score() + 1e-8)
                    best = new Candidate(a.x(), a.z(), dx, dz, support, score);
            }
        }
        return best;
    }

    private Evidence evidence(List<Point> source, double x, double z, double dx, double dz,
                              double playerX, double playerZ) {
        Set<Integer> bins = new HashSet<>();
        Set<Integer> ahead = new HashSet<>();
        double min = Double.POSITIVE_INFINITY;
        double max = Double.NEGATIVE_INFINITY;
        int chunks = 0;
        for (Point point : source) {
            double lateral = (point.x() - x) * -dz + (point.z() - z) * dx;
            if (Math.abs(lateral) > config.corridorWidth()) continue;
            double forward = (point.x() - playerX) * dx + (point.z() - playerZ) * dz;
            if (forward < -32) continue;
            int bin = (int) Math.floor(forward / BIN_SIZE);
            bins.add(bin);
            if (forward >= BIN_SIZE) ahead.add(bin);
            min = Math.min(min, forward);
            max = Math.max(max, forward);
            chunks++;
        }
        return new Evidence(chunks, bins.size(), ahead.size(), chunks == 0 ? 0 : max - min);
    }

    private double score(Evidence support, double angle, double offset) {
        // Longitudinal coverage, not the raw number of loaded chunks, is the evidence. A broad
        // patch, asymmetrically loaded render rectangle, or dense fork has no extra vote.
        return support.bins() + Math.min(support.span(), config.lookAhead() * 3) / BIN_SIZE * 0.1
            - angle * 0.035 - offset / config.corridorWidth() * 0.2;
    }

    private static float yaw(double dx, double dz) {
        return wrap(Math.toDegrees(Math.atan2(dz, dx)) - 90);
    }

    private static float wrap(double yaw) {
        double result = yaw % 360;
        if (result <= -180) result += 360;
        else if (result > 180) result -= 360;
        return (float) result;
    }

    private static double clamp(double value, double min, double max) {
        return Math.max(min, Math.min(max, value));
    }

    private static void finite(double x, double z) {
        if (!Double.isFinite(x) || !Double.isFinite(z))
            throw new IllegalArgumentException("position must be finite");
    }
}
