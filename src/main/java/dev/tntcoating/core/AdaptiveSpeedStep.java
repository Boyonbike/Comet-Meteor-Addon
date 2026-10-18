package dev.tntcoating.core;

import dev.tntcoating.core.AdaptiveFlightController.Motion;
import java.util.Objects;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

/** Horizontal adaptive movement using the same momentum and correction recovery as flight. */
public final class AdaptiveSpeedStep {
    /** Slow block contact is left to vanilla movement, including steps and slabs. */
    public static final double SAFE_MANEUVER_SPEED = .3;

    private AdaptiveSpeedStep() {}

    /** The maximum includes correction backoff, before the selected speed fraction. */
    public record Result(Motion motion, double adaptiveMaximum, FlightCorridor.Result corridor,
                         boolean terrainLimited, boolean safeManeuvering) {}

    /**
     * Runs one horizontal movement. The input's length and vertical component do not change speed.
     * Braking overrides input; releasing input retains the controller's normal deceleration.
     * Both probes must use non-loading chunk lookups, and the second must ignore block collisions.
     * A missing chunks-only probe retains the full collision guard.
     */
    public static Result move(AdaptiveFlightController controller, Motion direction, double maximumSpeed,
                              double fraction, boolean sprinting, boolean braking, AABB body,
                              FlightCorridor.Probe probe, FlightCorridor.Probe chunksOnlyProbe,
                              double lookahead, double margin, boolean safeManeuvering) {
        Objects.requireNonNull(controller, "controller");
        Objects.requireNonNull(direction, "direction");
        nonnegativeFinite(maximumSpeed, "Maximum speed");
        nonnegativeFinite(lookahead, "Lookahead");
        nonnegativeFinite(margin, "Margin");
        if (!Double.isFinite(fraction) || fraction < 0 || fraction > 1) {
            throw new IllegalArgumentException("Speed fraction must be in [0, 1].");
        }

        // The controller belongs to this horizontal engine. Do not retain vertical momentum
        // if it was seeded from a player's physical velocity or a different movement mode.
        Motion previous = horizontal(controller.output());
        if (controller.output().y() != 0) controller.accept(previous);
        double horizonMaximum = Math.min(maximumSpeed,
            safeSpeed(controller.settings().braking(), Math.max(0,
                Math.min(lookahead, FlightCorridor.MAX_DISTANCE) - margin)));
        double adaptiveMaximum = horizonMaximum * controller.speedFactor();
        double inputLength = direction.horizontalLength();
        boolean moving = !braking && inputLength > 0;
        double requestSpeed = horizonMaximum * fraction;
        Motion target = moving && requestSpeed > 0
            ? new Motion(direction.x() / inputLength * requestSpeed, 0,
                direction.z() / inputLength * requestSpeed)
            : Motion.ZERO;

        // step() applies correction backoff to the request exactly once. A lower nonzero
        // percentage brakes smoothly; zero and physical/correction ceilings are immediate.
        Motion blended = horizontal(controller.step(target, fraction == 0 ? 0 : adaptiveMaximum,
            sprinting && moving, adaptiveMaximum));
        double speed = blended.horizontalLength();
        if (speed == 0) {
            controller.accept(Motion.ZERO);
            return new Result(Motion.ZERO, controller.pauseTicksRemaining() > 0 ? 0 : adaptiveMaximum,
                null, false, false);
        }

        // Probe the actual blended heading, including remaining momentum during a turn.
        boolean mayRelease = safeManeuvering && chunksOnlyProbe != null
            && previous.horizontalLength() <= SAFE_MANEUVER_SPEED;
        boolean released = mayRelease && speed <= SAFE_MANEUVER_SPEED;
        FlightCorridor.Result corridor = FlightCorridor.inspect(body,
            vector(blended), lookahead, released ? chunksOnlyProbe : probe);
        double clearance = Math.max(0, corridor.clearDistance() - margin);
        adaptiveMaximum = Math.min(maximumSpeed, safeSpeed(controller.settings().braking(), clearance))
            * controller.speedFactor();
        // The controller has already applied the selected cruise/Sprint percentage to its
        // target. Keep the terrain-safe speed absolute: scaling it by the percentage would
        // make a 35% cruise start braking at the full adaptive maximum's stopping distance.
        double allowed = adaptiveMaximum;
        boolean clipped = speed > allowed;
        Motion actual = clipped ? scale(blended, allowed / speed) : blended;

        // An acceleration step can jump above the crawl threshold while already touching
        // a step. Permit a bounded crawl once momentum is low enough, avoiding a permanent
        // accelerate/stop cycle. Its independent chunk sweep still guards missing terrain.
        if (mayRelease && !released && corridor.reason() == FlightCorridor.Reason.OBSTACLE
            && actual.horizontalLength() <= SAFE_MANEUVER_SPEED) {
            double crawlMaximum = Math.min(horizonMaximum, SAFE_MANEUVER_SPEED);
            FlightCorridor.Result chunks = FlightCorridor.inspect(body, vector(blended),
                crawlMaximum + margin, chunksOnlyProbe);
            double chunkClearance = Math.max(0, chunks.clearDistance() - margin);
            adaptiveMaximum = Math.min(crawlMaximum, chunkClearance) * controller.speedFactor();
            actual = scale(blended, Math.min(1, adaptiveMaximum * fraction / speed));
            corridor = new FlightCorridor.Result(corridor.clearDistance(),
                chunks.reason() == FlightCorridor.Reason.CLEAR ? corridor.reason() : chunks.reason(),
                corridor.segments() + chunks.segments(), corridor.chunkChecks() + chunks.chunkChecks(),
                corridor.collisionChecks() + chunks.collisionChecks());
            released = true;
        }
        controller.accept(actual);
        return new Result(actual, adaptiveMaximum, corridor, clipped, released);
    }

    /** Reserves this tick's travel as well as the subsequent braking distance. */
    private static double safeSpeed(double braking, double clearance) {
        double rootBraking = Math.sqrt(braking);
        double twiceClearance = 2 * clearance;
        // Equivalent to sqrt(b*b + 2*b*d) - b without cancellation or overflow.
        double brakingSpeed = rootBraking * (twiceClearance
            / (Math.hypot(rootBraking, Math.sqrt(twiceClearance)) + rootBraking));
        return Math.min(clearance, brakingSpeed);
    }

    private static Motion horizontal(Motion motion) {
        return motion.y() == 0 ? motion : new Motion(motion.x(), 0, motion.z());
    }

    private static Vec3 vector(Motion motion) { return new Vec3(motion.x(), 0, motion.z()); }

    private static Motion scale(Motion motion, double factor) {
        return factor == 0 ? Motion.ZERO : new Motion(motion.x() * factor, 0, motion.z() * factor);
    }

    private static void nonnegativeFinite(double value, String name) {
        if (!Double.isFinite(value) || value < 0) {
            throw new IllegalArgumentException(name + " must be finite and nonnegative.");
        }
    }
}
