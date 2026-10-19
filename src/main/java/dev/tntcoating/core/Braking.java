package dev.tntcoating.core;

/**
 * One braking policy for all flight, in blocks per tick. {@code comfort} is the gentlest braking, used around cruise
 * speed. Faster flight plans on braking that rises with the square of speed, just enough that a stop from {@code top}
 * fits within {@code horizon}, the clear distance the terrain guard can see. When something is closer than planned,
 * braking becomes as hard as needed to stop before it, up to an instant stop.
 */
public final class Braking {
    /** A stop from the top speed must leave this share of the horizon for braking. */
    private static final double MAX_TOP_SHARE = .9;

    private final double comfort, horizon, top, growth;

    public Braking(double comfort, double horizon, double top) {
        if (!Double.isFinite(comfort) || comfort <= 0) throw new IllegalArgumentException("Braking must be finite and positive.");
        if (Double.isNaN(horizon) || horizon < 0) throw new IllegalArgumentException("Horizon cannot be negative.");
        if (!Double.isFinite(top) || top < 0) throw new IllegalArgumentException("Top speed must be finite and nonnegative.");
        this.comfort = comfort;
        this.horizon = horizon;
        this.top = Double.isFinite(horizon) ? Math.min(top, horizon * MAX_TOP_SHARE) : 0;
        // capacity(top) = top² / 2(horizon − top) puts the planned stop from top exactly at the horizon.
        this.growth = this.top > 0 ? Math.max(0, 1 / (2 * (horizon - this.top)) - comfort / (this.top * this.top)) : 0;
    }

    /** Constant braking, as before braking scaled with speed. */
    public static Braking constant(double comfort) {
        return new Braking(comfort, Double.POSITIVE_INFINITY, 0);
    }

    public double comfort() { return comfort; }
    public double horizon() { return horizon; }

    /** Braking planned at {@code speed}: {@code comfort} near cruise speed, rising with the square of speed. */
    public double capacity(double speed) {
        return comfort + growth * speed * speed;
    }

    /** This tick's travel plus the planned stopping distance from {@code speed}. */
    public double reach(double speed) {
        return speed + speed * speed / (2 * capacity(speed));
    }

    /** The fastest speed whose planned stop fits within {@code clearance}; reach rises with speed, so it is unique. */
    public double envelope(double clearance) {
        if (!(clearance > 0)) return 0;
        if (growth == 0) return FlightCorridor.safeSpeed(comfort, clearance);
        double low = reach(top) <= clearance + 1e-9 ? top : 0;
        double high = clearance; // reach(speed) > speed
        for (int step = 0; step < 52 && high - low > 1e-12; step++) {
            double middle = (low + high) * .5;
            if (reach(middle) <= clearance) low = middle;
            else high = middle;
        }
        return low;
    }

    /**
     * The fastest speed for the next tick from {@code speed} with {@code clearance} known clear. Within the envelope,
     * that is the envelope. Already faster (something is closer than planned), it commits to the gentlest constant
     * braking that still stops in time, which keeps the hardest braking of the whole stop as low as possible: braking
     * by {@code a = speed − u} now and after, {@code u + u² / 2a} must fit the clearance. That is as hard as needed,
     * up to an instant stop, and never past the clearance.
     */
    public double allowed(double speed, double clearance) {
        if (!(clearance > 0)) return 0;
        double envelope = envelope(clearance);
        if (!(speed > envelope)) return envelope;
        return Math.min(clearance, speed + clearance - Math.hypot(speed, clearance));
    }
}
