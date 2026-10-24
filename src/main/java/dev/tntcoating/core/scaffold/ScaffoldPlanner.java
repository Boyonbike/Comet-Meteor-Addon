package dev.tntcoating.core.scaffold;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Predicate;

/**
 * Pure broad-phase support planning. A candidate says that a target's cube may be face-reachable;
 * the Minecraft adapter must still verify the actual neighbor face, shape, ray and placement context.
 * This class is confined to the client thread.
 */
public final class ScaffoldPlanner {
    public enum Preset { Wide, Narrow }
    public record Cell(int x, int y, int z) { }
    public record Snapshot(double x, double z, double eyeY, int floorY, double velocityX, double velocityZ,
                           double directionX, double directionZ, double halfWidth, double reach,
                           long nowNanos, long lookaheadNanos) { }
    public record Candidate(Cell cell, int tier, long deadlineNanos, double lateral, long firstSeenNanos) { }
    public record Plan(List<Candidate> candidates, int desiredWidth, int confirmedWidth, int holes,
                       int unfinishedRowsLeavingReach) {
        public Plan { candidates = List.copyOf(candidates); }
    }

    // Bound the input envelope itself; never silently truncate a valid envelope or its lateral sides.
    private static final double MAX_REACH = 64;
    private static final double MAX_COORDINATE = 30_000_000;
    private static final double MAX_SPEED = 128;
    private static final double EPS = 1e-9;
    private static final double PREDICTION_TICK_SECONDS = .05;
    // Airborne steering cannot instantly replace momentum. This modest prediction acceleration is
    // refreshed from actual observed velocity every plan; it is not a Minecraft physics simulation.
    private static final double STEERING_ACCELERATION = 8;
    private static final int MAX_HISTORY = 70_000;
    private static final long MAX_HORIZON = 1_000_000_000L;
    private static final Plan EMPTY = new Plan(List.of(), 0, 0, 0, 0);

    private final LinkedHashMap<Cell, Long> pending = new LinkedHashMap<>();
    private double headingX, headingZ = 1;
    private double originX, originZ;
    private boolean hasHeading;
    private int preferredSide = 1;
    private Preset previousPreset;

    private record Point(Cell cell, double along, double lateral, boolean confirmed) { }
    private record Sweep(double x, double z, double dx, double dz, double startSeconds, double duration) { }

    /**
     * Returns unconfirmed support targets. Width counts use current transverse rows: desired is the
     * widest row, confirmed is the minimum contiguous central run, and holes have support on both sides.
     * The selected floorY is authoritative, including when the eye is above a predicted landing plane.
     */
    public Plan plan(Preset preset, Snapshot snapshot, Predicate<Cell> confirmedSupport) {
        if (preset == null || confirmedSupport == null || !valid(snapshot)) return EMPTY;
        if (previousPreset != preset) {
            pending.clear();
            previousPreset = preset;
        }
        updateHeading(snapshot);
        originX = snapshot.x();
        originZ = snapshot.z();
        return preset == Preset.Wide ? wide(snapshot, confirmedSupport) : narrow(snapshot, confirmedSupport);
    }

    public void reset() {
        pending.clear();
        headingX = 0;
        headingZ = 1;
        originX = originZ = 0;
        hasHeading = false;
        preferredSide = 1;
        previousPreset = null;
    }

    /** Advance the preferred frontier only for a placement that actually reached the send boundary. */
    public void placed(Cell cell) {
        if (cell == null) return;
        double lateral = lateral(cell);
        if (Math.abs(lateral) > EPS) preferredSide = lateral > 0 ? -1 : 1;
    }

    private Plan wide(Snapshot s, Predicate<Cell> support) {
        Map<Integer, List<Point>> rows = new HashMap<>();
        Map<Cell, Point> current = new HashMap<>();
        double projection = Math.abs(headingX) + Math.abs(headingZ);
        double longitudinalRadius = .5 * projection;
        double footprintRadius = s.halfWidth() * projection;
        for (int x = min(s.x(), s.reach()); x <= max(s.x(), s.reach()); x++) {
            for (int z = min(s.z(), s.reach()); z <= max(s.z(), s.reach()); z++) {
                Cell cell = new Cell(x, s.floorY(), z);
                if (!reachable(cell, s, s.x(), s.z())) continue;
                double along = along(cell);
                if (along + longitudinalRadius < -footprintRadius - EPS
                    || along - longitudinalRadius > 2 + footprintRadius + EPS) continue;
                Point point = new Point(cell, along, lateral(cell), support.test(cell));
                current.put(cell, point);
                rows.computeIfAbsent(row(along), unused -> new ArrayList<>()).add(point);
            }
        }

        Set<Cell> holes = new HashSet<>();
        int desiredWidth = 0, confirmedWidth = Integer.MAX_VALUE;
        for (List<Point> points : rows.values()) {
            points.sort(Comparator.comparingDouble(Point::lateral));
            desiredWidth = Math.max(desiredWidth, points.size());
            int centre = 0;
            for (int i = 1; i < points.size(); i++) {
                if (Math.abs(points.get(i).lateral()) < Math.abs(points.get(centre).lateral())) centre = i;
            }
            int contiguous = 0;
            if (points.get(centre).confirmed()) {
                contiguous = 1;
                for (int i = centre - 1; i >= 0 && points.get(i).confirmed(); i--) contiguous++;
                for (int i = centre + 1; i < points.size() && points.get(i).confirmed(); i++) contiguous++;
            }
            confirmedWidth = Math.min(confirmedWidth, contiguous);
            int first = -1, last = -1;
            for (int i = 0; i < points.size(); i++) if (points.get(i).confirmed()) {
                if (first < 0) first = i;
                last = i;
            }
            for (int i = first + 1; first >= 0 && i < last; i++) {
                if (!points.get(i).confirmed()) holes.add(points.get(i).cell());
            }
        }

        List<Candidate> candidates = new ArrayList<>();
        double speed = Math.hypot(s.velocityX(), s.velocityZ());
        double distance = Math.max(2, speed * horizon(s));
        double sweepX = headingX * distance, sweepZ = headingZ * distance;
        for (Point point : current.values()) {
            if (point.confirmed()) {
                pending.remove(point.cell());
                continue;
            }
            long firstSeen = pending.computeIfAbsent(point.cell(), unused -> s.nowNanos());
            double entry = entry(point.cell(), s, sweepX, sweepZ);
            int tier = Double.isFinite(entry) ? 0 : holes.contains(point.cell()) ? 1 : 3;
            long deadline = tier == 0 ? addTime(s.nowNanos(), entry * (speed > .02 ? distance / speed : 1)) : Long.MAX_VALUE;
            candidates.add(new Candidate(point.cell(), tier, deadline, point.lateral(), firstSeen));
        }

        Set<Integer> leavingRows = new HashSet<>();
        // Previously desired rows stay pending while they remain face-reachable. An old unsupported
        // target is never treated as an anchor merely because placed() was called for it.
        for (Iterator<Map.Entry<Cell, Long>> iterator = pending.entrySet().iterator(); iterator.hasNext();) {
            Map.Entry<Cell, Long> old = iterator.next();
            Cell cell = old.getKey();
            if (cell.y() != s.floorY() || !reachable(cell, s, s.x(), s.z())) {
                iterator.remove();
                continue;
            }
            if (current.containsKey(cell)) continue;
            if (support.test(cell)) {
                iterator.remove();
                continue;
            }
            double entry = entry(cell, s, sweepX, sweepZ);
            int tier = Double.isFinite(entry) ? 0 : 2;
            candidates.add(new Candidate(cell, tier,
                tier == 0 ? addTime(s.nowNanos(), entry * (speed > .02 ? distance / speed : 1)) : Long.MAX_VALUE,
                lateral(cell), old.getValue()));
            double prediction = Math.max(.05, horizon(s));
            if (!reachable(cell, s, s.x() + s.velocityX() * prediction, s.z() + s.velocityZ() * prediction))
                leavingRows.add(row(along(cell)));
        }
        trimHistory();
        candidates.sort(wideOrder());
        return new Plan(candidates, desiredWidth, confirmedWidth == Integer.MAX_VALUE ? 0 : confirmedWidth,
            holes.size(), leavingRows.size());
    }

    private Plan narrow(Snapshot s, Predicate<Cell> support) {
        List<Sweep> trajectory = narrowTrajectory(s);
        List<Candidate> candidates = new ArrayList<>();
        Set<Cell> stillDesired = new HashSet<>();
        int desired = 0, confirmed = 0;
        for (int x = min(s.x(), s.reach()); x <= max(s.x(), s.reach()); x++) {
            for (int z = min(s.z(), s.reach()); z <= max(s.z(), s.reach()); z++) {
                Cell cell = new Cell(x, s.floorY(), z);
                if (!reachable(cell, s, s.x(), s.z())) continue;
                double firstContact = Double.POSITIVE_INFINITY;
                for (Sweep segment : trajectory) {
                    double fraction = entry(cell, segment.x(), segment.z(), s.halfWidth(), segment.dx(), segment.dz());
                    if (Double.isFinite(fraction)) firstContact = Math.min(firstContact,
                        segment.startSeconds() + fraction * segment.duration());
                }
                if (!Double.isFinite(firstContact)) continue;
                desired++;
                if (support.test(cell)) {
                    confirmed++;
                    continue;
                }
                stillDesired.add(cell);
                long firstSeen = pending.computeIfAbsent(cell, unused -> s.nowNanos());
                candidates.add(new Candidate(cell, 0, addTime(s.nowNanos(), firstContact), lateral(cell), firstSeen));
            }
        }
        pending.keySet().retainAll(stillDesired);
        trimHistory();
        candidates.sort(Comparator.comparingLong(Candidate::deadlineNanos)
            .thenComparingDouble(c -> Math.abs(c.lateral()))
            .thenComparingInt(c -> c.cell().x()).thenComparingInt(c -> c.cell().z()));
        return new Plan(candidates, desired == 0 ? 0 : 1, desired != 0 && desired == confirmed ? 1 : 0, 0, 0);
    }

    /**
     * Keep the first observed tick verbatim, then approach desired velocity with bounded acceleration.
     * Exact segment sweeps include the whole footprint between prediction points. Only this freshly
     * predicted trajectory is retained; older plans never add lateral platform cells after steering.
     */
    private static List<Sweep> narrowTrajectory(Snapshot s) {
        double horizon = horizon(s);
        double vx = s.velocityX(), vz = s.velocityZ();
        double desiredX = vx, desiredZ = vz;
        double speed = Math.hypot(vx, vz), input = Math.hypot(s.directionX(), s.directionZ());
        if (input > .001 && speed > .02) {
            desiredX = s.directionX() / input * speed;
            desiredZ = s.directionZ() / input * speed;
        }
        int count = Math.max(1, (int) Math.ceil(horizon / PREDICTION_TICK_SECONDS));
        List<Sweep> result = new ArrayList<>(count);
        double x = s.x(), z = s.z(), elapsed = 0, previousDuration = 0;
        for (int tick = 0; tick < count; tick++) {
            if (tick > 0) {
                double changeX = desiredX - vx, changeZ = desiredZ - vz;
                double change = Math.hypot(changeX, changeZ);
                double fraction = change > EPS ? Math.min(1, STEERING_ACCELERATION * previousDuration / change) : 0;
                vx += changeX * fraction;
                vz += changeZ * fraction;
            }
            double duration = Math.max(0, Math.min(PREDICTION_TICK_SECONDS, horizon - elapsed));
            double dx = vx * duration, dz = vz * duration;
            result.add(new Sweep(x, z, dx, dz, elapsed, duration));
            x += dx;
            z += dz;
            elapsed += duration;
            previousDuration = duration;
        }
        return result;
    }

    private Comparator<Candidate> wideOrder() {
        return Comparator.comparingInt(Candidate::tier)
            .thenComparingLong(Candidate::deadlineNanos)
            .thenComparingLong(c -> c.tier() == 2 ? c.firstSeenNanos() : 0)
            .thenComparingInt(c -> Math.abs(c.lateral()) < EPS || Math.signum(c.lateral()) == preferredSide ? 0 : 1)
            .thenComparingDouble(c -> Math.abs(c.lateral()))
            .thenComparingDouble(c -> Math.abs(along(c.cell())))
            .thenComparingInt(c -> c.cell().x()).thenComparingInt(c -> c.cell().z());
    }

    private void updateHeading(Snapshot s) {
        double speed = Math.hypot(s.velocityX(), s.velocityZ());
        double input = Math.hypot(s.directionX(), s.directionZ());
        double x, z;
        if (speed > .02) {
            x = input > .001 ? s.directionX() : s.velocityX();
            z = input > .001 ? s.directionZ() : s.velocityZ();
        } else if (!hasHeading && input > .001) {
            x = s.directionX();
            z = s.directionZ();
        } else return;
        double length = Math.hypot(x, z);
        x /= length;
        z /= length;
        // Five degree deadband avoids tiny movement/input noise repeatedly rebuilding transverse rows.
        if (!hasHeading || x * headingX + z * headingZ < Math.cos(Math.toRadians(5))) {
            headingX = x;
            headingZ = z;
            hasHeading = true;
        }
    }

    private double along(Cell cell) {
        return (cell.x() + .5 - originX) * headingX + (cell.z() + .5 - originZ) * headingZ;
    }

    private double lateral(Cell cell) {
        return -(cell.x() + .5 - originX) * headingZ + (cell.z() + .5 - originZ) * headingX;
    }

    private static int row(double along) { return (int) Math.floor(along + .5); }
    private static int min(double coordinate, double reach) { return (int) Math.floor(coordinate - reach) - 1; }
    private static int max(double coordinate, double reach) { return (int) Math.floor(coordinate + reach); }
    private static double horizon(Snapshot s) { return Math.min(MAX_HORIZON, Math.max(0, s.lookaheadNanos())) / 1e9; }

    private static boolean reachable(Cell cell, Snapshot s, double eyeX, double eyeZ) {
        double dx = axisDistance(eyeX, cell.x(), cell.x() + 1.0);
        double dy = axisDistance(s.eyeY(), cell.y(), cell.y() + 1.0);
        double dz = axisDistance(eyeZ, cell.z(), cell.z() + 1.0);
        return dx * dx + dy * dy + dz * dz <= s.reach() * s.reach() + EPS;
    }

    private static double axisDistance(double value, double min, double max) {
        return value < min ? min - value : value > max ? value - max : 0;
    }

    /** Exact intersection of the centre segment and a target square expanded by the player half width. */
    private static double entry(Cell cell, Snapshot s, double dx, double dz) {
        return entry(cell, s.x(), s.z(), s.halfWidth(), dx, dz);
    }

    private static double entry(Cell cell, double x, double z, double halfWidth, double dx, double dz) {
        double enter = 0, exit = 1;
        double minX = cell.x() - halfWidth, maxX = cell.x() + 1 + halfWidth;
        double minZ = cell.z() - halfWidth, maxZ = cell.z() + 1 + halfWidth;
        if (Math.abs(dx) <= EPS) {
            if (x < minX - EPS || x > maxX + EPS) return Double.NaN;
        } else {
            double a = (minX - x) / dx, b = (maxX - x) / dx;
            enter = Math.max(enter, Math.min(a, b));
            exit = Math.min(exit, Math.max(a, b));
        }
        if (Math.abs(dz) <= EPS) {
            if (z < minZ - EPS || z > maxZ + EPS) return Double.NaN;
        } else {
            double a = (minZ - z) / dz, b = (maxZ - z) / dz;
            enter = Math.max(enter, Math.min(a, b));
            exit = Math.min(exit, Math.max(a, b));
        }
        return enter <= exit + EPS && exit >= -EPS && enter <= 1 + EPS ? Math.max(0, Math.min(1, enter)) : Double.NaN;
    }

    private static long addTime(long now, double seconds) {
        long delta = Math.round(Math.max(0, Math.min(MAX_HORIZON, seconds * 1e9)));
        return now > Long.MAX_VALUE - delta ? Long.MAX_VALUE : now + delta;
    }

    private void trimHistory() {
        while (pending.size() > MAX_HISTORY) pending.remove(pending.keySet().iterator().next());
    }

    private static boolean valid(Snapshot s) {
        return s != null && Double.isFinite(s.x()) && Double.isFinite(s.z()) && Double.isFinite(s.eyeY())
            && Double.isFinite(s.velocityX()) && Double.isFinite(s.velocityZ())
            && Double.isFinite(s.directionX()) && Double.isFinite(s.directionZ())
            && Double.isFinite(s.halfWidth()) && Double.isFinite(s.reach())
            && Math.abs(s.x()) <= MAX_COORDINATE && Math.abs(s.z()) <= MAX_COORDINATE
            && Math.abs(s.eyeY()) <= MAX_COORDINATE && Math.abs((long) s.floorY()) <= MAX_COORDINATE
            && Math.hypot(s.velocityX(), s.velocityZ()) <= MAX_SPEED
            && Math.hypot(s.directionX(), s.directionZ()) <= 1_000_000
            && s.halfWidth() >= 0 && s.halfWidth() <= 2 && s.reach() > 0 && s.reach() <= MAX_REACH;
    }
}
