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
    /** Descent speed at touchdown, in blocks per tick: VolytraFly's tested anti-slam floor. */
    public static final double LANDING_SPEED = .2;

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
                           AdaptiveFlightMemory.Limits limits, double collisionReleaseSpeed,
                           Blend blend, Boost boost) {
        /** Constant-shape vertical control without unlimited boost, as before the blended system. */
        public Controls(boolean sprintBoost, double maximumFraction, double verticalFraction, int verticalInput,
                        boolean releaseSafeCollisions, ServerGlide.Context glide,
                        AdaptiveFlightMemory.Limits limits, double collisionReleaseSpeed) {
            this(sprintBoost, maximumFraction, verticalFraction, verticalInput, releaseSafeCollisions, glide,
                limits, collisionReleaseSpeed, null, null);
        }

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

    /**
     * The blended vertical system and unified braking. Vertical percentages are of {@code verticalMaximum} (still
     * lowered by learned ascent/descent after setbacks); climbing and descending speed up along the boost curve with
     * {@code rampStep}, scaled by the learned share of the configured vertical acceleration, and slow down along its
     * mirror; momentum above a lowered ceiling (such as just after boost) brakes down to it instead of snapping.
     * Terrain braking follows the controller's {@link Braking} policy, and descent towards a floor
     * {@code floorClearance} blocks below slows to {@link #LANDING_SPEED} before touchdown.
     */
    public record Blend(double verticalMaximum, double rampStep, double accelerationScale, double floorClearance) {
        /** No known floor below. */
        public Blend(double verticalMaximum, double rampStep, double accelerationScale) {
            this(verticalMaximum, rampStep, accelerationScale, Double.POSITIVE_INFINITY);
        }

        public Blend {
            if (Double.isNaN(floorClearance) || floorClearance < 0) {
                throw new IllegalArgumentException("Floor clearance cannot be negative.");
            }
            if (!Double.isFinite(verticalMaximum) || verticalMaximum <= 0) {
                throw new IllegalArgumentException("Vertical maximum must be finite and positive.");
            }
            if (!Double.isFinite(rampStep) || rampStep < 0) {
                throw new IllegalArgumentException("Vertical ramp step must be finite and nonnegative.");
            }
            if (!Double.isFinite(accelerationScale) || accelerationScale < 0 || accelerationScale > 1) {
                throw new IllegalArgumentException("Vertical acceleration scale must be in [0, 1].");
            }
        }
    }

    /**
     * Unlimited boost ramp speeds in blocks per tick; zero leaves that axis on its adaptive limits. A boosted axis
     * ignores cruise limits, the correction ceiling and the adaptive engine maximum, but shares
     * {@code totalCeiling} with the other axes and keeps the terrain, chunk and server-glide guards.
     */
    public record Boost(double horizontal, double vertical, double totalCeiling) {
        public Boost(double horizontal, double vertical) {
            this(horizontal, vertical, Double.POSITIVE_INFINITY);
        }

        public Boost {
            if (!Double.isFinite(horizontal) || horizontal < 0 || !Double.isFinite(vertical) || vertical < 0) {
                throw new IllegalArgumentException("Boost speeds must be finite and nonnegative.");
            }
            if (Double.isNaN(totalCeiling) || totalCeiling < 0) {
                throw new IllegalArgumentException("Boost total ceiling must be nonnegative or positive infinity.");
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
        Blend blend = controls == null ? null : controls.blend();
        Boost boost = controls == null ? null : controls.boost();
        boolean horizontalEnabled = horizontalCeiling > 0;
        boolean verticalEnabled = verticalCeiling > 0;
        boolean boostHorizontal = boost != null && boost.horizontal() > 0 && horizontalEnabled
            && requested.horizontalLength() > 0;
        boolean boostVertical = boost != null && boost.vertical() > 0 && verticalEnabled
            && controls.verticalInput() != 0;
        boolean boosted = boostHorizontal || boostVertical;
        Motion previous = controller.output();
        if (controls != null) {
            double maximum = horizonMaximum * fraction;
            double horizontalMaximum = boostHorizontal ? boost.horizontal() : learned == null ? maximum
                : Math.min(horizonMaximum, Math.min(learned.horizontal(), learned.total())) * fraction;
            double horizontal = requested.horizontalLength();
            if (horizontalCeiling > 0) {
                horizontalCeiling = horizontalMaximum;
                if (horizontal > 0) requested = new Motion(requested.x() / horizontal * horizontalMaximum,
                    requested.y(), requested.z() / horizontal * horizontalMaximum);
            } else requested = new Motion(0, requested.y(), 0);
            double verticalBase = blend == null ? horizonMaximum : Math.min(horizonMaximum, blend.verticalMaximum());
            double verticalMaximum = verticalBase * verticalFraction;
            if (boostVertical) {
                requested = new Motion(requested.x(), controls.verticalInput() * boost.vertical(), requested.z());
                verticalCeiling = boost.vertical();
            } else {
                if (controls.verticalInput() != 0 && verticalCeiling > 0 && (learned != null || sprintBoost)) {
                    double directionalMaximum = learned == null ? verticalMaximum
                        : Math.min(verticalBase, Math.min(learned.total(),
                            controls.verticalInput() > 0 ? learned.ascent() : learned.descent()))
                            * verticalFraction;
                    requested = new Motion(requested.x(), controls.verticalInput() * directionalMaximum, requested.z());
                    verticalCeiling = directionalMaximum;
                }
                // A zero maximum stops every axis, including an existing vertical glide.
                verticalCeiling = Math.min(verticalCeiling, verticalMaximum);
            }
            requested = new Motion(requested.x(), Math.max(-verticalCeiling,
                Math.min(verticalCeiling, requested.y())), requested.z());
            if (learned != null && !boostVertical) requested = new Motion(requested.x(), Math.max(-learned.descent(),
                Math.min(learned.ascent(), requested.y())), requested.z());
            if (learned != null && !boosted) {
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
        // Boost turns against the adaptive engine maximum too, so its turn authority keeps growing with speed.
        double turnReference = boosted ? horizonMaximum : attainableMaximum;
        // The blended vertical is stepped separately below, so the controller's whole vector budget goes to
        // horizontal acceleration and steering instead of being shared with a climb.
        boolean separateVertical = learned != null && (blend != null || boostVertical);
        Motion forController = separateVertical ? new Motion(requested.x(), previous.y(), requested.z()) : requested;
        Motion blended = controller.step(forController, Math.hypot(steppingHorizontal, steppingVertical), sprintBoost,
            turnReference, boostHorizontal);
        double horizontal = blended.horizontalLength();
        double horizontalHardCeiling = boostHorizontal ? boost.horizontal() : learned == null ? horizontalCeiling
            : !horizontalEnabled || fraction == 0 ? 0
                : Math.min(horizonMaximum, Math.min(learned.horizontal(), learned.total())) * controller.speedFactor();
        if (blend != null && !boostHorizontal && horizontalEnabled && fraction > 0)
            horizontalHardCeiling = decaying(horizontalHardCeiling, horizontal, previous.horizontalLength());
        double horizontalFactor = horizontal > horizontalHardCeiling ? horizontalHardCeiling / horizontal : 1;
        double y = Math.max(-verticalCeiling, Math.min(verticalCeiling, blended.y()));
        if (learned != null) {
            double target = separateVertical ? requested.y() : blended.y();
            boolean slowing = Math.abs(target) < Math.abs(previous.y()) || target * previous.y() < 0;
            double delta;
            if (slowing) {
                delta = Math.min(controller.settings().braking(), 2 * learned.verticalAcceleration());
                // Slowing mirrors the vertical ramp: firm from fast climbs and dives, gentle near a stop.
                if (blend != null) delta = Math.max(delta,
                    blend.rampStep() * Math.abs(previous.y()) / blend.verticalMaximum());
            } else if (boostVertical) {
                delta = Double.POSITIVE_INFINITY; // The vertical boost ramp is the acceleration limit.
            } else {
                delta = Math.min(controller.settings().acceleration(), learned.verticalAcceleration());
                if (blend != null) delta = Math.max(delta, BoostRamp.gain(Math.abs(previous.y()), blend.rampStep(),
                    blend.verticalMaximum()) * blend.accelerationScale());
            }
            y = previous.y() + Math.max(-delta, Math.min(delta, target - previous.y()));
            double up, down;
            if (boostVertical) up = down = boost.vertical();
            else {
                double verticalBase = blend == null ? horizonMaximum : Math.min(horizonMaximum, blend.verticalMaximum());
                up = Math.min(verticalBase, learned.ascent()) * controller.speedFactor();
                down = Math.min(verticalBase, learned.descent()) * controller.speedFactor();
                if (blend != null) {
                    up = decaying(up, y, previous.y());
                    down = decaying(down, -y, -previous.y());
                }
            }
            y = !verticalEnabled || verticalFraction == 0 ? 0 : Math.max(-down, Math.min(up, y));
            // Soft landing: brake a descent as the floor approaches, as hard as needed, and touch down slowly.
            if (blend != null && y < 0 && Double.isFinite(blend.floorClearance())) {
                double descent = Math.max(LANDING_SPEED, controller.settings().brakingModel()
                    .allowed(Math.max(0, -previous.y()), blend.floorClearance()));
                y = Math.max(y, -descent);
            }
        }
        Motion limited = new Motion(blended.x() * horizontalFactor,
            y,
            blended.z() * horizontalFactor);
        // Retain the shared hard ceiling during turns and existing momentum. Percentages
        // affect the target above so reducing cruise speed still uses normal braking.
        // Boost is outside the learned displacement budget.
        if (boosted && limited.length() > boost.totalCeiling()) {
            // The server rejects total displacement: a climb or dive spends the same
            // speed budget as horizontal flight, including momentum on a released axis.
            limited = limited.scaled(boost.totalCeiling() / limited.length());
        } else if (learned != null && !boosted) {
            double totalCeiling = learned.total() * controller.speedFactor();
            if (blend != null) totalCeiling = decaying(totalCeiling, limited.length(), previous.length());
            if (limited.length() > totalCeiling) limited = limited.scaled(totalCeiling / limited.length());
        }
        if (learned != null || boosted) controller.accept(limited);
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
        // Unified braking: the planned envelope, or as hard as needed when something is closer than planned.
        double allowed = blend == null ? FlightCorridor.safeSpeed(controller.settings().braking(), clearance)
            : controller.settings().brakingModel().allowed(previous.length(), clearance);
        double adaptiveMaximum;
        if (boosted) adaptiveMaximum = allowed;
        else {
            // A maximum-speed request can exceed the finite terrain horizon even after
            // backoff. Reduce the attainable ceiling too, so sprint cannot mask corrections.
            if (sprintBoost || controls != null) allowed *= controller.speedFactor();
            if (learned != null) {
                double totalCeiling = learned.total() * controller.speedFactor();
                if (blend != null) totalCeiling = decaying(totalCeiling, speed, previous.length());
                allowed = Math.min(allowed, totalCeiling);
            }
            // Speed-scaled braking widens the terrain allowance; adaptive flight still tops out at its own maximum.
            adaptiveMaximum = controls == null ? allowed : Math.min(allowed, horizonMaximum * controller.speedFactor());
        }
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

    /**
     * A ceiling that momentum from above (such as unlimited boost just released) may exceed while braking down
     * to it: this tick's {@code next} speed is allowed when it is no faster than the {@code previous} one.
     */
    private static double decaying(double ceiling, double next, double previous) {
        return previous > ceiling ? Math.max(ceiling, Math.min(next, previous)) : ceiling;
    }

    private static boolean within(Motion motion, double maximum) {
        return motion.length() <= maximum + 1e-9;
    }

}
