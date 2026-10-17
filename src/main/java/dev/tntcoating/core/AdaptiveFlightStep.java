package dev.tntcoating.core;

import dev.tntcoating.core.AdaptiveFlightController.Motion;
import java.util.Objects;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

/** Runs one controlled movement through the configured speed limits and swept terrain guard. */
public final class AdaptiveFlightStep {
    public static final double MAX_HORIZONTAL_SPEED = 64;
    // Vanilla wall damage is (lost horizontal speed * 10) - 3, positive only above .3.
    // Use the full vector and a small buffer so a harmless crawl also stays slow vertically.
    public static final double SAFE_MANEUVER_SPEED = 0.3 - 1e-7;

    private AdaptiveFlightStep() {}

    /** A stationary or correction-paused step has no corridor and performs no terrain probes. */
    public record Result(Motion motion, FlightCorridor.Result corridor,
                         boolean lookaheadLimited, boolean terrainLimited,
                         double adaptiveMaximum, boolean safeManeuvering) {}

    /** Percentages are fractions of the attainable engine output, not its raw 64-block request. */
    public record Controls(boolean sprintBoost, double maximumFraction, int verticalInput,
                           boolean releaseSafeCollisions) {
        public Controls {
            if (!Double.isFinite(maximumFraction) || maximumFraction < 0 || maximumFraction > 1) {
                throw new IllegalArgumentException("Maximum fraction must be in [0, 1].");
            }
            if (verticalInput < -1 || verticalInput > 1) {
                throw new IllegalArgumentException("Vertical input must be -1, 0 or 1.");
            }
        }
    }

    public static boolean harmless(Motion motion) { return motion.length() <= SAFE_MANEUVER_SPEED; }

    /** Maximum output in a clear loaded horizon, before a nearer obstacle or chunk reduces it. */
    public static double maximumSpeed(double braking, double lookahead, double margin) {
        nonnegativeFinite(braking, "Braking");
        nonnegativeFinite(lookahead, "Lookahead");
        nonnegativeFinite(margin, "Margin");
        double clearance = Math.max(0, Math.min(lookahead, FlightCorridor.MAX_DISTANCE) - margin);
        return braking == 0 ? 0 : Math.min(clearance, brakingSpeed(braking, clearance));
    }

    /** Manual controls with an engine-scaled maximum and intentional vertical Sprint input. */
    public static Result move(AdaptiveFlightController controller, Motion requested,
                              double horizontalCeiling, double verticalCeiling, AABB box,
                              FlightCorridor.Probe probe, double lookahead, double margin, Controls controls) {
        Objects.requireNonNull(controls, "controls");
        return move(controller, requested, horizontalCeiling, verticalCeiling, box, probe,
            lookahead, margin, controls.sprintBoost(), controls);
    }

    public static Result move(AdaptiveFlightController controller, Motion requested,
                              double horizontalCeiling, double verticalCeiling, AABB box,
                              FlightCorridor.Probe probe, double lookahead, double margin) {
        return move(controller, requested, horizontalCeiling, verticalCeiling, box, probe, lookahead, margin, false);
    }

    /** Sprint requests the engine's maximum horizontal speed, retaining acceleration and all adaptive limits. */
    public static Result move(AdaptiveFlightController controller, Motion requested,
                              double horizontalCeiling, double verticalCeiling, AABB box,
                              FlightCorridor.Probe probe, double lookahead, double margin, boolean sprintBoost) {
        return move(controller, requested, horizontalCeiling, verticalCeiling, box, probe,
            lookahead, margin, sprintBoost, null);
    }

    private static Result move(AdaptiveFlightController controller, Motion requested,
                               double horizontalCeiling, double verticalCeiling, AABB box,
                               FlightCorridor.Probe probe, double lookahead, double margin,
                               boolean sprintBoost, Controls controls) {
        Objects.requireNonNull(controller, "controller");
        Objects.requireNonNull(requested, "requested");
        nonnegativeFinite(horizontalCeiling, "Horizontal ceiling");
        nonnegativeFinite(verticalCeiling, "Vertical ceiling");
        nonnegativeFinite(lookahead, "Lookahead");
        nonnegativeFinite(margin, "Margin");
        double fraction = controls == null ? 1 : controls.maximumFraction();
        double horizonMaximum = maximumSpeed(controller.settings().braking(), lookahead, margin);
        Motion previous = controller.output();
        if (controls != null) {
            double maximum = horizonMaximum * fraction;
            double horizontal = requested.horizontalLength();
            if (horizontalCeiling > 0) {
                horizontalCeiling = maximum;
                if (horizontal > 0) requested = new Motion(requested.x() / horizontal * maximum,
                    requested.y(), requested.z() / horizontal * maximum);
            } else requested = new Motion(0, requested.y(), 0);
            if (sprintBoost && controls.verticalInput() != 0 && verticalCeiling > 0) {
                requested = new Motion(requested.x(), controls.verticalInput() * maximum, requested.z());
                verticalCeiling = maximum;
            }
            // A zero maximum stops every axis, including an existing vertical glide.
            verticalCeiling = Math.min(verticalCeiling, maximum);
            requested = new Motion(requested.x(), Math.max(-verticalCeiling,
                Math.min(verticalCeiling, requested.y())), requested.z());
        } else if (sprintBoost && horizontalCeiling > 0) {
            double horizontal = requested.horizontalLength();
            if (horizontal > 0) {
                requested = new Motion(requested.x() / horizontal * MAX_HORIZONTAL_SPEED, requested.y(),
                    requested.z() / horizontal * MAX_HORIZONTAL_SPEED);
            }
            horizontalCeiling = MAX_HORIZONTAL_SPEED;
        }

        // Use the configured ceiling rather than the request's length so releasing
        // movement keys keeps the controller's normal braking behavior.
        Motion blended = controller.step(requested, Math.hypot(horizontalCeiling, verticalCeiling), sprintBoost);
        double horizontal = blended.horizontalLength();
        double horizontalFactor = horizontal > horizontalCeiling ? horizontalCeiling / horizontal : 1;
        Motion limited = new Motion(blended.x() * horizontalFactor,
            Math.max(-verticalCeiling, Math.min(verticalCeiling, blended.y())),
            blended.z() * horizontalFactor);
        double speed = limited.length();
        if (speed == 0) {
            controller.accept(Motion.ZERO);
            return new Result(Motion.ZERO, null, false, false,
                controller.pauseTicksRemaining() > 0 ? 0 : horizonMaximum * controller.speedFactor(), false);
        }

        // Inspect the actual blended trajectory once. Separate old/requested rays
        // can miss an obstacle between them and multiply the per-tick probe budget.
        boolean mayRelease = controls != null && controls.releaseSafeCollisions() && harmless(previous);
        boolean safeManeuvering = mayRelease && harmless(limited);
        FlightCorridor.Probe chunksOnly = probe == null ? null : new FlightCorridor.Probe() {
            @Override public boolean loaded(int x, int z) { return probe.loaded(x, z); }
            @Override public boolean clear(AABB swept) { return true; }
        };
        FlightCorridor.Result corridor = FlightCorridor.inspect(box,
            new Vec3(limited.x(), limited.y(), limited.z()), lookahead, safeManeuvering ? chunksOnly : probe);
        double clearance = Math.max(0, corridor.clearDistance() - margin);
        double allowed = Math.min(clearance, brakingSpeed(controller.settings().braking(), clearance));
        // A maximum-speed request can exceed the finite terrain horizon even after
        // backoff. Reduce the attainable ceiling too, so sprint cannot mask corrections.
        if (sprintBoost || controls != null) allowed *= controller.speedFactor();
        double adaptiveMaximum = allowed;
        allowed *= fraction;
        boolean clipped = speed > allowed;
        Motion actual = clipped ? scale(limited, allowed / speed) : limited;
        // Once the guard has slowed flight to harmless contact, allow a crawl even if
        // the next acceleration step would be too fast. Otherwise .2 -> .35 -> .2 can
        // remain stuck forever at a wall. Minecraft resolves the harmless block contact.
        if (mayRelease && !safeManeuvering && corridor.reason() == FlightCorridor.Reason.OBSTACLE
            && harmless(actual)) {
            adaptiveMaximum = Math.min(horizonMaximum, SAFE_MANEUVER_SPEED) * controller.speedFactor();
            Motion crawl = scale(limited, Math.min(1, adaptiveMaximum * fraction / speed));
            FlightCorridor.Result chunks = FlightCorridor.inspect(box,
                new Vec3(crawl.x(), crawl.y(), crawl.z()), crawl.length() + margin, chunksOnly);
            double distance = Math.max(0, chunks.clearDistance() - margin);
            actual = distance < crawl.length() ? scale(crawl, distance / crawl.length()) : crawl;
            // Include the extra short chunk sweep in query counts. Its block predicate is a no-op.
            corridor = new FlightCorridor.Result(corridor.clearDistance(),
                chunks.reason() == FlightCorridor.Reason.CLEAR ? corridor.reason() : chunks.reason(),
                corridor.segments() + chunks.segments(), corridor.chunkChecks() + chunks.chunkChecks(),
                corridor.collisionChecks());
            safeManeuvering = true;
        }
        controller.accept(actual);
        return new Result(actual, corridor, clipped && corridor.reason() == FlightCorridor.Reason.CLEAR,
            clipped, adaptiveMaximum, safeManeuvering);
    }

    private static double brakingSpeed(double braking, double clearance) {
        // sqrt(b*b + 2*b*d) - b reserves this tick's travel as well as stopping
        // distance. This equivalent form avoids cancellation and overflow.
        double rootBraking = Math.sqrt(braking);
        double twiceClearance = 2 * clearance;
        return rootBraking * (twiceClearance
            / (Math.hypot(rootBraking, Math.sqrt(twiceClearance)) + rootBraking));
    }

    private static Motion scale(Motion motion, double factor) {
        return factor == 0 ? Motion.ZERO
            : new Motion(motion.x() * factor, motion.y() * factor, motion.z() * factor);
    }

    private static void nonnegativeFinite(double value, String name) {
        if (!Double.isFinite(value) || value < 0) {
            throw new IllegalArgumentException(name + " must be finite and nonnegative.");
        }
    }
}
