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

    /**
     * A stationary or correction-paused step has no corridor and performs no terrain probes, except
     * that a server glide guard may still step back from a wall. {@code glideDamage} is wall damage
     * the server's own glide tick will deal even after that guard.
     */
    public record Result(Motion motion, FlightCorridor.Result corridor,
                         boolean lookaheadLimited, boolean terrainLimited,
                         double adaptiveMaximum, boolean safeManeuvering,
                         boolean glideLimited, double glideDamage) {}

    /**
     * Horizontal and vertical percentages are independent fractions of their attainable outputs,
     * not the raw 64-block request.
     * A non-null {@code glide} keeps walls outside the server's hidden glide reach.
     */
    public record Controls(boolean sprintBoost, double maximumFraction, double verticalFraction, int verticalInput,
                           boolean releaseSafeCollisions, ServerGlide.Context glide,
                           AdaptiveFlightMemory.Limits limits, double collisionReleaseSpeed) {
        public Controls {
            if (!Double.isFinite(maximumFraction) || maximumFraction < 0 || maximumFraction > 1) {
                throw new IllegalArgumentException("Horizontal maximum fraction must be in [0, 1].");
            }
            if (!Double.isFinite(verticalFraction) || verticalFraction < 0 || verticalFraction > 1) {
                throw new IllegalArgumentException("Vertical maximum fraction must be in [0, 1].");
            }
            if (verticalInput < -1 || verticalInput > 1) {
                throw new IllegalArgumentException("Vertical input must be -1, 0 or 1.");
            }
            if (!Double.isFinite(collisionReleaseSpeed) || collisionReleaseSpeed <= 0) {
                throw new IllegalArgumentException("Collision release speed must be finite and positive.");
            }
        }
    }

    public static boolean harmless(Motion motion) { return motion.length() <= SAFE_MANEUVER_SPEED; }

    /** Maximum output in a clear loaded horizon, before a nearer obstacle or chunk reduces it. */
    public static double maximumSpeed(double braking, double lookahead, double margin) {
        Finite.requireNonnegative(braking, "Braking");
        Finite.requireNonnegative(lookahead, "Lookahead");
        Finite.requireNonnegative(margin, "Margin");
        double clearance = Math.max(0, Math.min(lookahead, FlightCorridor.MAX_DISTANCE) - margin);
        return braking == 0 ? 0 : FlightCorridor.safeSpeed(braking, clearance);
    }

    /** Manual controls with an engine-scaled maximum and intentional vertical Sprint input. */
    public static Result move(AdaptiveFlightController controller, Motion requested,
                              double horizontalCeiling, double verticalCeiling, AABB box,
                              FlightCorridor.Probe probe, double lookahead, double margin, Controls controls) {
        Objects.requireNonNull(controls, "controls");
        return move(controller, requested, horizontalCeiling, verticalCeiling, box, probe,
            lookahead, margin, controls.sprintBoost(), controls);
    }

    /** Legacy unscaled path, kept package-private for older regression tests; the module always passes Controls. */
    static Result move(AdaptiveFlightController controller, Motion requested,
                       double horizontalCeiling, double verticalCeiling, AABB box,
                       FlightCorridor.Probe probe, double lookahead, double margin) {
        return move(controller, requested, horizontalCeiling, verticalCeiling, box, probe, lookahead, margin, false);
    }

    /** Legacy test path: Sprint requests the engine's maximum horizontal speed with all adaptive limits. */
    static Result move(AdaptiveFlightController controller, Motion requested,
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
        Finite.requireNonnegative(horizontalCeiling, "Horizontal ceiling");
        Finite.requireNonnegative(verticalCeiling, "Vertical ceiling");
        Finite.requireNonnegative(lookahead, "Lookahead");
        Finite.requireNonnegative(margin, "Margin");
        double fraction = controls == null ? 1 : controls.maximumFraction();
        double verticalFraction = controls == null ? 1 : controls.verticalFraction();
        double horizonMaximum = maximumSpeed(controller.settings().braking(), lookahead, margin);
        AdaptiveFlightMemory.Limits learned = controls == null ? null : controls.limits();
        boolean horizontalEnabled = horizontalCeiling > 0;
        boolean verticalEnabled = verticalCeiling > 0;
        Motion previous = controller.output();
        if (controls != null) {
            double maximum = horizonMaximum * fraction;
            double horizontalMaximum = learned == null ? maximum
                : Math.min(horizonMaximum, Math.min(learned.horizontal(), learned.total())) * fraction;
            double horizontal = requested.horizontalLength();
            if (horizontalCeiling > 0) {
                horizontalCeiling = horizontalMaximum;
                if (horizontal > 0) requested = new Motion(requested.x() / horizontal * horizontalMaximum,
                    requested.y(), requested.z() / horizontal * horizontalMaximum);
            } else requested = new Motion(0, requested.y(), 0);
            double verticalMaximum = horizonMaximum * verticalFraction;
            if (controls.verticalInput() != 0 && verticalCeiling > 0 && (learned != null || sprintBoost)) {
                double directionalMaximum = learned == null ? verticalMaximum
                    : Math.min(horizonMaximum, Math.min(learned.total(),
                        controls.verticalInput() > 0 ? learned.ascent() : learned.descent()))
                        * verticalFraction;
                requested = new Motion(requested.x(), controls.verticalInput() * directionalMaximum, requested.z());
                verticalCeiling = directionalMaximum;
            }
            // A zero maximum stops every axis, including an existing vertical glide.
            verticalCeiling = Math.min(verticalCeiling, verticalMaximum);
            requested = new Motion(requested.x(), Math.max(-verticalCeiling,
                Math.min(verticalCeiling, requested.y())), requested.z());
            if (learned != null) requested = new Motion(requested.x(), Math.max(-learned.descent(),
                Math.min(learned.ascent(), requested.y())), requested.z());
            if (learned != null) {
                // A mixed flight request shares the server's displacement budget. Percentages
                // remain fractions of attainable speed, including when one axis is released.
                double activeFraction = Math.max(requested.horizontalLength() > 0 ? fraction : 0,
                    requested.y() != 0 ? verticalFraction : 0);
                double totalTarget = learned.total() * activeFraction;
                double requestedSpeed = requested.length();
                if (requestedSpeed > totalTarget) requested = requested.scaled(totalTarget / requestedSpeed);
            }
        } else if (sprintBoost && horizontalCeiling > 0) {
            double horizontal = requested.horizontalLength();
            if (horizontal > 0) {
                requested = new Motion(requested.x() / horizontal * MAX_HORIZONTAL_SPEED, requested.y(),
                    requested.z() / horizontal * MAX_HORIZONTAL_SPEED);
            }
            horizontalCeiling = MAX_HORIZONTAL_SPEED;
        }

        // Use the configured ceiling rather than the request's length so releasing
        // movement keys keeps the controller's normal braking behavior. Turns scale
        // against the attainable maximum that the speed percentages also use.
        // A released vertical key changes the target, not the hard safety ceiling. Keeping
        // existing Y momentum here allows ordinary braking instead of snapping it to stock speed.
        double steppingVertical = learned == null ? verticalCeiling
            : Math.max(verticalCeiling, Math.abs(previous.y()));
        double steppingHorizontal = learned == null ? horizontalCeiling
            : Math.max(horizontalCeiling, previous.horizontalLength());
        double attainableMaximum = Math.min(horizonMaximum, learned == null ? MAX_HORIZONTAL_SPEED : learned.total())
            * controller.speedFactor();
        Motion blended = controller.step(requested, Math.hypot(steppingHorizontal, steppingVertical), sprintBoost,
            attainableMaximum);
        double horizontal = blended.horizontalLength();
        double horizontalHardCeiling = learned == null ? horizontalCeiling
            : !horizontalEnabled || fraction == 0 ? 0
                : Math.min(horizonMaximum, Math.min(learned.horizontal(), learned.total())) * controller.speedFactor();
        double horizontalFactor = horizontal > horizontalHardCeiling ? horizontalHardCeiling / horizontal : 1;
        double y = Math.max(-verticalCeiling, Math.min(verticalCeiling, blended.y()));
        if (learned != null) {
            boolean slowing = Math.abs(blended.y()) < Math.abs(previous.y()) || blended.y() * previous.y() < 0;
            double delta = slowing ? Math.min(controller.settings().braking(), 2 * learned.verticalAcceleration())
                : Math.min(controller.settings().acceleration(), learned.verticalAcceleration());
            y = previous.y() + Math.max(-delta, Math.min(delta, blended.y() - previous.y()));
            double up = Math.min(horizonMaximum, learned.ascent()) * controller.speedFactor();
            double down = Math.min(horizonMaximum, learned.descent()) * controller.speedFactor();
            y = !verticalEnabled || verticalFraction == 0 ? 0 : Math.max(-down, Math.min(up, y));
        }
        Motion limited = new Motion(blended.x() * horizontalFactor,
            y,
            blended.z() * horizontalFactor);
        // Retain the shared hard ceiling during turns and existing momentum. Percentages
        // affect the target above so reducing cruise speed still uses normal braking.
        if (learned != null && limited.length() > learned.total() * controller.speedFactor())
            limited = limited.scaled(learned.total() * controller.speedFactor() / limited.length());
        if (learned != null) controller.accept(limited);
        double speed = limited.length();
        FlightCorridor.Probe chunksOnly = probe == null ? null : FlightCorridor.Probe.chunksOnly(probe);
        ServerGlide.Context glide = controls == null || controller.pauseTicksRemaining() > 0 ? null : controls.glide();
        if (speed == 0) {
            // Hovering still lets the server's glide grow into a nearby wall.
            ServerGlide.Clearance glideClearance = glide == null || probe == null ? null
                : ServerGlide.clear(glide, box, Motion.ZERO, chunksOnly);
            Motion retreat = glideClearance == null ? Motion.ZERO : glideClearance.motion();
            controller.accept(retreat);
            return new Result(retreat, null, false, false,
                controller.pauseTicksRemaining() > 0 ? 0 : attainableMaximum, false,
                glideClearance != null && glideClearance.limited(), glideClearance == null ? 0 : glideClearance.damage());
        }

        // Inspect the actual blended trajectory once. Separate old/requested rays
        // can miss an obstacle between them and multiply the per-tick probe budget.
        double releaseSpeed = controls == null ? SAFE_MANEUVER_SPEED : controls.collisionReleaseSpeed();
        boolean mayRelease = controls != null && controls.releaseSafeCollisions()
            && within(previous, releaseSpeed);
        boolean safeManeuvering = mayRelease && within(limited, releaseSpeed);
        FlightCorridor.Result corridor = FlightCorridor.inspect(box,
            new Vec3(limited.x(), limited.y(), limited.z()), lookahead, safeManeuvering ? chunksOnly : probe);
        // Brake early enough for a wall or chunk edge ahead that the server's own glide cannot reach it
        // either, so the guard below rarely has to cut a smooth stop short. The open horizon is unaffected.
        Motion reach = glide == null || corridor.reason() == FlightCorridor.Reason.CLEAR
            ? Motion.ZERO : ServerGlide.reach(glide);
        double glideMargin = Math.max(0, (reach.x() * limited.x() + reach.z() * limited.z()) / speed);
        double clearance = Math.max(0, corridor.clearDistance() - margin - glideMargin);
        double allowed = FlightCorridor.safeSpeed(controller.settings().braking(), clearance);
        // A maximum-speed request can exceed the finite terrain horizon even after
        // backoff. Reduce the attainable ceiling too, so sprint cannot mask corrections.
        if (sprintBoost || controls != null) allowed *= controller.speedFactor();
        if (learned != null) allowed = Math.min(allowed, learned.total() * controller.speedFactor());
        double adaptiveMaximum = allowed;
        // The requested vector and controller output already observe maximumFraction.
        // Applying it again to this physical cap makes fractional cruise speeds begin
        // braking at the adaptive maximum's stopping distance instead of their own.
        boolean clipped = speed > allowed;
        Motion actual = clipped ? limited.scaled(allowed / speed) : limited;
        // Once the guard has slowed flight to the configured close-object speed, allow movement even if
        // the next acceleration step would be too fast. Otherwise .2 -> .35 -> .2 can
        // remain stuck forever at a wall. The live module resolves this vector through Minecraft collision.
        if (mayRelease && !safeManeuvering && corridor.reason() == FlightCorridor.Reason.OBSTACLE
            && within(actual, releaseSpeed)) {
            adaptiveMaximum = Math.min(horizonMaximum, releaseSpeed) * controller.speedFactor();
            Motion crawl = limited.scaled(Math.min(1, adaptiveMaximum * fraction / speed));
            FlightCorridor.Result chunks = FlightCorridor.inspect(box,
                new Vec3(crawl.x(), crawl.y(), crawl.z()), crawl.length() + margin, chunksOnly);
            double distance = Math.max(0, chunks.clearDistance() - margin);
            actual = distance < crawl.length() ? crawl.scaled(distance / crawl.length()) : crawl;
            // Include the extra short chunk sweep in query counts. Its block predicate is a no-op.
            corridor = new FlightCorridor.Result(corridor.clearDistance(),
                chunks.reason() == FlightCorridor.Reason.CLEAR ? corridor.reason() : chunks.reason(),
                corridor.segments() + chunks.segments(), corridor.chunkChecks() + chunks.chunkChecks(),
                corridor.collisionChecks());
            safeManeuvering = true;
        }
        // Last, because harmless client contact can still leave the server's own glide too fast for a wall.
        ServerGlide.Clearance glideClearance = glide == null || corridor.reason() == FlightCorridor.Reason.INVALID
            ? null : ServerGlide.clear(glide, box, actual, chunksOnly);
        if (glideClearance != null) actual = glideClearance.motion();
        controller.accept(actual);
        return new Result(actual, corridor, clipped && corridor.reason() == FlightCorridor.Reason.CLEAR,
            clipped, adaptiveMaximum, safeManeuvering,
            glideClearance != null && glideClearance.limited(), glideClearance == null ? 0 : glideClearance.damage());
    }

    private static boolean within(Motion motion, double maximum) {
        return motion.length() <= maximum + 1e-9;
    }

}
