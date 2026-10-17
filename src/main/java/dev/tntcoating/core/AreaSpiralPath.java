package dev.tntcoating.core;

/**
 * An outward Archimedean spiral in the horizontal plane. The radius grows by
 * {@code spacing} blocks each revolution, beginning at the supplied center.
 * Progress follows nearby, ordered pieces of the route; looking farther ahead
 * or flying across a later revolution cannot skip the intervening route.
 */
public final class AreaSpiralPath {
    private static final double TAU = Math.PI * 2;
    private static final int MAX_PROGRESS_STEPS = 4_096;

    public record Point(double x, double z) {
        public Point {
            requireFinite(x, "x");
            requireFinite(z, "z");
        }
    }

    private final double centerX;
    private final double centerZ;
    private final double startAngle;
    private final double direction;
    private final double spacing;
    private final double maxRadius;
    private final double radialGrowth;
    private final double endAngle;
    private final double totalLength;
    private final double corridor;
    private final double chordLength;
    private final double completionDistance;
    private final Point center;
    private final Point endpoint;
    private boolean hasPrevious;
    private boolean enteredCenter;
    private boolean finished;
    private double previousX;
    private double previousZ;
    private double travelled;
    private Point rejoinCheckpoint;

    /**
     * Yaw uses Minecraft's convention: 0 faces south (+z), -90 faces east (+x).
     * Clockwise, viewed on a north-up map, turns from south towards west.
     */
    public AreaSpiralPath(double centerX, double centerZ, double startYaw,
                          boolean clockwise, double spacing, double maxRadius) {
        requireFinite(centerX, "center x");
        requireFinite(centerZ, "center z");
        requireFinite(startYaw, "start yaw");
        requirePositive(spacing, "spacing");
        requirePositive(maxRadius, "maximum radius");
        this.centerX = centerX;
        this.centerZ = centerZ;
        this.startAngle = Math.toRadians(startYaw % 360);
        this.direction = clockwise ? 1 : -1;
        this.spacing = spacing;
        this.maxRadius = maxRadius;
        radialGrowth = spacing / TAU;
        endAngle = maxRadius / radialGrowth;
        if (radialGrowth == 0 || endAngle == 0 || !Double.isFinite(endAngle)
            || !Double.isFinite(Math.abs(centerX) + maxRadius)
            || !Double.isFinite(Math.abs(centerZ) + maxRadius)) {
            throw new IllegalArgumentException("spiral geometry exceeds finite coordinate precision");
        }
        totalLength = arcLength(endAngle);
        corridor = Math.min(32, Math.min(spacing / 8, maxRadius / 8));
        chordLength = Math.min(4, corridor * .5);
        completionDistance = Math.min(6, Math.min(maxRadius / 4, spacing / 16));
        center = new Point(centerX, centerZ);
        endpoint = pointAtAngle(endAngle);
    }

    /**
     * Observe the player's position and return a point {@code lookAhead} blocks
     * along the remaining route. Calls at a stationary position never advance
     * the route. A player starting elsewhere is first guided to the center.
     * Center arrival allows six blocks of flight-controller clearance (less
     * for very small spirals).
     *
     * The final six blocks (less for very small spirals) count as arrival when
     * physically near the endpoint, allowing flight controllers to stop short.
     * Work and memory per observation stay bounded, even for enormous areas.
     */
    public Point target(double x, double z, double lookAhead) {
        requireFinite(x, "player x");
        requireFinite(z, "player z");
        requireFinite(lookAhead, "look-ahead");
        if (lookAhead < 0) throw new IllegalArgumentException("look-ahead must not be negative");
        if (finished) return endpoint;

        double fromX = hasPrevious ? previousX : x;
        double fromZ = hasPrevious ? previousZ : z;
        boolean moved = hasPrevious && (x != previousX || z != previousZ);
        previousX = x;
        previousZ = z;
        hasPrevious = true;

        if (!enteredCenter) {
            double centerTolerance = Math.min(6, corridor);
            double fraction = projection(centerX, centerZ, fromX, fromZ, x, z);
            double nearestX = lerp(fromX, x, fraction);
            double nearestZ = lerp(fromZ, z, fraction);
            if (Math.hypot(nearestX - centerX, nearestZ - centerZ) > centerTolerance) return center;
            enteredCenter = true;
            fromX = nearestX;
            fromZ = nearestZ;
            // Arriving at the center starts the route, without counting the
            // connector from the initial position as spiral travel.
            moved = moved && fraction < 1;
        }

        if (rejoinCheckpoint != null) {
            double fraction = projection(rejoinCheckpoint.x, rejoinCheckpoint.z, fromX, fromZ, x, z);
            double nearestX = lerp(fromX, x, fraction);
            double nearestZ = lerp(fromZ, z, fraction);
            if (Math.hypot(nearestX - rejoinCheckpoint.x, nearestZ - rejoinCheckpoint.z) > Math.min(6, corridor)) {
                return rejoinCheckpoint;
            }
            rejoinCheckpoint = null;
            fromX = nearestX;
            fromZ = nearestZ;
            // Reaching the saved point from a detour is not progress along the spiral.
            moved = moved && fraction < 1;
        }

        if (moved) advance(fromX, fromZ, x, z);
        return finished ? endpoint : pointAtDistance(Math.min(totalLength, travelled + lookAhead));
    }

    public boolean finished() { return finished; }

    /** Forget paused movement and approach the saved route position before observing further spiral progress. */
    public void resetObservation() {
        hasPrevious = false;
        rejoinCheckpoint = enteredCenter && !finished ? pointAtDistance(travelled) : null;
    }

    /** The initial centre or saved route point currently being approached, or null once following the spiral. */
    public Point rejoinTarget() { return !enteredCenter ? center : rejoinCheckpoint; }

    /** Radius of the observed route progress, rather than the preview target. */
    public double radius() { return finished ? maxRadius : radialGrowth * angleAtDistance(travelled); }

    public double turns() { return radius() / spacing; }

    /** Approximate route arc length observed so far, useful for progress timeouts. */
    public double distanceTravelled() { return travelled; }

    public Point center() { return center; }

    public Point endpoint() { return endpoint; }

    private void advance(double fromX, double fromZ, double x, double z) {
        double motionX = x - fromX;
        double motionZ = z - fromZ;
        double movement = Math.hypot(motionX, motionZ);
        if (!(movement > 0) || !Double.isFinite(movement)) return;
        double limit = Math.min(totalLength, travelled + movement + corridor * 2);
        double previousFraction = 0;
        Point start = pointAtDistance(travelled);
        for (int step = 0; step < MAX_PROGRESS_STEPS && travelled < limit; step++) {
            double nextDistance = Math.min(limit, travelled + chordLength);
            if (!(nextDistance > travelled)) break;
            Point next = pointAtDistance(nextDistance);
            double pathX = next.x - start.x;
            double pathZ = next.z - start.z;
            if (pathX * motionX + pathZ * motionZ <= 0) break;

            double fraction = projection(next.x, next.z, fromX, fromZ, x, z);
            double nearestX = lerp(fromX, x, fraction);
            double nearestZ = lerp(fromZ, z, fraction);
            if (fraction >= previousFraction
                && Math.hypot(next.x - nearestX, next.z - nearestZ) <= corridor) {
                travelled = nextDistance;
                start = next;
                previousFraction = fraction;
                continue;
            }

            double part = projection(x, z, start.x, start.z, next.x, next.z);
            if (part > 0 && Math.hypot(x - lerp(start.x, next.x, part),
                z - lerp(start.z, next.z, part)) <= corridor) {
                travelled += (nextDistance - travelled) * part;
            }
            break;
        }
        if (totalLength - travelled <= completionDistance
            && Math.hypot(x - endpoint.x, z - endpoint.z) <= completionDistance) {
            travelled = totalLength;
            finished = true;
        }
    }

    private Point pointAtDistance(double distance) { return pointAtAngle(angleAtDistance(distance)); }

    private Point pointAtAngle(double angle) {
        double radius = angle >= endAngle ? maxRadius : radialGrowth * angle;
        double bearing = startAngle + direction * (angle % TAU);
        return new Point(centerX - Math.sin(bearing) * radius, centerZ + Math.cos(bearing) * radius);
    }

    private double angleAtDistance(double distance) {
        if (distance <= 0) return 0;
        if (distance >= totalLength) return endAngle;
        double scaledDistance = distance / radialGrowth;
        double angle = scaledDistance <= 1 ? scaledDistance
            : Math.sqrt(distance) * Math.sqrt(2) / Math.sqrt(radialGrowth);
        angle = Math.min(endAngle, angle);
        double low = 0;
        double high = endAngle;
        for (int i = 0; i < 48; i++) {
            double length = arcLength(angle);
            if (Math.abs(length - distance) <= Math.max(1e-10, distance * 1e-13)) return angle;
            if (length < distance) low = angle;
            else high = angle;
            double next = angle - (length - distance) / Math.hypot(radialGrowth, radialGrowth * angle);
            angle = Double.isFinite(next) && next > low && next < high ? next : low + (high - low) / 2;
        }
        return angle;
    }

    private double arcLength(double angle) {
        double asinh = angle > 1e150 ? Math.log(angle) + Math.log(2)
            : Math.log1p(angle + angle * angle / (1 + Math.hypot(1, angle)));
        double result = .5 * (radialGrowth * angle) * Math.hypot(1, angle) + .5 * radialGrowth * asinh;
        return Double.isFinite(result) ? result : Double.MAX_VALUE;
    }

    private static double projection(double x, double z, double fromX, double fromZ, double toX, double toZ) {
        double dx = toX - fromX;
        double dz = toZ - fromZ;
        double length = Math.hypot(dx, dz);
        if (!(length > 0) || !Double.isFinite(length)) return 0;
        return Math.clamp(((x - fromX) * (dx / length) + (z - fromZ) * (dz / length)) / length, 0, 1);
    }

    private static double lerp(double from, double to, double fraction) { return from + (to - from) * fraction; }

    private static void requireFinite(double value, String name) {
        if (!Double.isFinite(value)) throw new IllegalArgumentException(name + " must be finite");
    }

    private static void requirePositive(double value, String name) {
        requireFinite(value, name);
        if (value <= 0) throw new IllegalArgumentException(name + " must be positive");
    }
}
