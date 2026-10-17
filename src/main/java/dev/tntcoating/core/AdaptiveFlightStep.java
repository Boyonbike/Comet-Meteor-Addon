package dev.tntcoating.core;

import dev.tntcoating.core.AdaptiveFlightController.Motion;
import java.util.Objects;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

/** Runs one controlled movement through the stock speed limits and swept terrain guard. */
public final class AdaptiveFlightStep {
    private AdaptiveFlightStep() {}

    /** A stationary or correction-paused step has no corridor and performs no terrain probes. */
    public record Result(Motion motion, FlightCorridor.Result corridor,
                         boolean lookaheadLimited, boolean terrainLimited) {}

    public static Result move(AdaptiveFlightController controller, Motion requested,
                              double horizontalCeiling, double verticalCeiling, AABB box,
                              FlightCorridor.Probe probe, double lookahead, double margin) {
        return move(controller, requested, horizontalCeiling, verticalCeiling, box, probe, lookahead, margin, false);
    }

    public static Result move(AdaptiveFlightController controller, Motion requested,
                              double horizontalCeiling, double verticalCeiling, AABB box,
                              FlightCorridor.Probe probe, double lookahead, double margin, boolean sprintBoost) {
        Objects.requireNonNull(controller, "controller");
        Objects.requireNonNull(requested, "requested");
        nonnegativeFinite(horizontalCeiling, "Horizontal ceiling");
        nonnegativeFinite(verticalCeiling, "Vertical ceiling");
        nonnegativeFinite(lookahead, "Lookahead");
        nonnegativeFinite(margin, "Margin");

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
            return new Result(Motion.ZERO, null, false, false);
        }

        // Inspect the actual blended trajectory once. Separate old/requested rays
        // can miss an obstacle between them and multiply the per-tick probe budget.
        FlightCorridor.Result corridor = FlightCorridor.inspect(box,
            new Vec3(limited.x(), limited.y(), limited.z()), lookahead, probe);
        double clearance = Math.max(0, corridor.clearDistance() - margin);
        double allowed = Math.min(clearance, brakingSpeed(controller.settings().braking(), clearance));
        boolean clipped = speed > allowed;
        Motion actual = clipped ? scale(limited, allowed / speed) : limited;
        controller.accept(actual);
        return new Result(actual, corridor, clipped && corridor.reason() == FlightCorridor.Reason.CLEAR, clipped);
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
