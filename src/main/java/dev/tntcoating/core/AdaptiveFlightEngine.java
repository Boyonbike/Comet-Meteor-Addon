package dev.tntcoating.core;

import dev.tntcoating.core.AdaptiveFlightController.Motion;
import java.util.Objects;
import java.util.function.DoubleSupplier;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

/**
 * Adaptive Elytra Fly's per-tick flight pipeline, independent of the Minecraft client: momentum, unlimited boost
 * ramps, close-object control and the terrain guard. Deterministic: the same keys and terrain always give the
 * same flight, and a server setback only rewinds the boost ramps.
 */
public final class AdaptiveFlightEngine {
    private static final double EPSILON = 1e-7;
    // A heading change faster than this, in degrees per tick, counts as turning for maneuver mode. Turn assistance
    // closes the gap every tick at speed, so the gap is about this tick's mouse turn (or a strafe key).
    private static final double MANEUVER_TURN_DEGREES = 2;
    // Maneuver mode outlasts its trigger by this many ticks, so it cannot flicker between capped and full speed.
    private static final int MANEUVER_HOLD_TICKS = 10;

    /**
     * The module's settings and Meteor Elytra Fly's stock speeds, which only enable an axis (zero disables it) and
     * size the passive sink. {@code cruise}, {@code sprint}, {@code verticalCruise} and {@code verticalSprint} are
     * speed caps in blocks per tick: cruise and Sprint never pass them, nor the adaptive engine's own attainable
     * speed. {@code unlimitedBoost} is Sprint boost with unlimited boost on; {@code boostTop} and
     * {@code verticalMaximum} shape the horizontal and vertical ramp curves, which the Sprint caps only stop short
     * of, never reshape, and {@code boostCeiling} is the total 3D speed boost may never exceed (see
     * {@link ServerMoveLimit}). After a setback both ramps hold their rewound speed for {@code setbackHoldTicks}
     * before accelerating. With {@code maneuverMode}, flying horizontally, vertically and turning at once holds total
     * 3D speed to {@code maneuverCeiling}, and boost speeds up again from there afterwards. Switching straight between
     * Jump and Sneak slows the old direction over {@code verticalReversalTicks} before turning around; zero turns
     * around at once.
     */
    public record Settings(double acceleration, double braking, double turnRate,
                           double cruise, double sprint,
                           double verticalMaximum, double verticalCruise, double verticalSprint,
                           double verticalAcceleration, double verticalRampStep, int verticalReversalTicks,
                           boolean unlimitedBoost, double boostTop, double boostStart, double boostRampStep,
                           double boostVerticalStart, int rewindTicks, int setbackHoldTicks, double boostCeiling,
                           boolean maneuverMode, double maneuverCeiling, int lookahead, double margin, boolean safeManeuvering, double closeObjectSpeed,
                           double stockHorizontalSpeed, double stockVerticalSpeed, double fallMultiplier) {}

    /** Minecraft's collision for a movement from the current body, without step-up. */
    public interface Collisions {
        Motion resolve(Motion movement);
    }

    /**
     * Movement keys this tick (yaw in degrees), Minecraft's own vertical movement for the passive sink, the body,
     * the terrain probe, the clear distance to the floor (only asked for during a descent), the server-glide guard
     * context or null, and collision resolution.
     */
    public record Input(int forward, int right, float yaw, boolean jump, boolean sneak, double naturalY,
                        AABB body, FlightCorridor.Probe probe, DoubleSupplier floorClearance,
                        ServerGlide.Context glide, Collisions collisions) {}

    private Settings settings;
    private final AdaptiveFlightController controller;
    private final BoostRamp horizontalRamp = new BoostRamp(new BoostRamp.Settings(0, 0, 1, 0, 0));
    private final BoostRamp verticalRamp = new BoostRamp(new BoostRamp.Settings(0, 0, 1, 0, 0));
    private int verticalBoostDirection, maneuverTicks;
    private boolean sprintKey, boostActive, maneuvering, nearObject, closeObjectOverride, maneuverMode;
    private boolean releaseSafeCollisions, contactResolved;
    private double releaseSpeed, adaptiveMaximum, reversalBrake;
    private AdaptiveFlightStep.Result lastStep;
    private int chunkChecks;

    public AdaptiveFlightEngine(Settings settings) {
        this.settings = Objects.requireNonNull(settings, "settings");
        controller = new AdaptiveFlightController(controllerSettings(settings));
        configureRamps();
    }

    /** Once per client tick, before any movement: applies the current settings. */
    public void startTick(Settings settings) {
        this.settings = Objects.requireNonNull(settings, "settings");
        controller.configure(controllerSettings(settings));
        controller.tick();
        configureRamps();
        chunkChecks = 0;
    }

    /**
     * Every controlled movement starts here: Sprint's close-object override, whether this movement may be released
     * to Minecraft collision, and the starting body. False means the body overlaps unknown terrain and the engine
     * has suspended itself, so stock physics can withdraw from the overlap. {@code sprint} is the boost key (held
     * or toggled) and {@code boostReady} whether a recent elytra swap still holds boost back; {@code vanillaSpeed}
     * and {@code vanillaMovement} are the lengths of Minecraft's own glide velocity and movement this tick.
     */
    public boolean begin(boolean sprint, boolean boostReady, double vanillaSpeed, double vanillaMovement,
                         AABB body, FlightCorridor.Probe probe) {
        sprintKey = boostReady && sprint;
        // Sprint lifts the close-object speed only while really touching or blocked, until that contact clears.
        // With unlimited boost, Sprint is boost instead, which falls back to the close-object crawl by itself.
        if (settings.unlimitedBoost() || !boostReady) closeObjectOverride = false;
        else if (nearObject && sprintKey) closeObjectOverride = true;
        releaseSpeed = closeObjectOverride ? AdaptiveFlightStep.MAX_HORIZONTAL_SPEED : settings.closeObjectSpeed();
        releaseSafeCollisions = settings.safeManeuvering() && (closeObjectOverride
            || vanillaSpeed <= releaseSpeed && vanillaMovement <= releaseSpeed);
        boolean harmlessStart = releaseSafeCollisions && controller.output().length() <= releaseSpeed;
        // Corrections and unloads can strand the body in unknown terrain. Let stock physics withdraw from an
        // existing overlap; a blocked path from a safe starting box still uses the terrain guard normally.
        FlightCorridor.Result start = FlightCorridor.inspectStart(body,
            harmlessStart ? FlightCorridor.Probe.chunksOnly(probe) : probe);
        chunkChecks += start.chunkChecks();
        if (start.reason() == FlightCorridor.Reason.CLEAR) return true;
        suspend();
        return false;
    }

    /** The first controlled movement of a client tick, after {@link #begin}: one step of the pipeline. */
    public AdaptiveFlightStep.Result step(Input input) {
        Objects.requireNonNull(input, "input");
        Settings s = settings;
        Motion requested = requested(input);
        boolean sprinting = sprintKey || closeObjectOverride;
        int verticalInput = Finite.clamp(s.stockVerticalSpeed(), 0, 64, 0) == 0 ? 0
            : input.jump() ? 1 : input.sneak() ? -1 : 0;
        // A switch straight between Jump and Sneak slows the old direction evenly, from its speed when the switch began.
        double previousY = controller.output().y();
        if (verticalInput * previousY >= 0) reversalBrake = 0;
        else if (reversalBrake == 0) reversalBrake = Math.abs(previousY) / (Math.max(0, s.verticalReversalTicks()) + 1);
        updateManeuver(requested, verticalInput);
        AdaptiveFlightStep.Boost boost = updateBoost(requested, verticalInput);
        // The step works in shares of what the adaptive engine can attain; a cap above that is no limit.
        double horizon = AdaptiveFlightStep.maximumSpeed(s.braking(), s.lookahead(), s.margin());
        double verticalHorizon = Math.min(horizon, s.verticalMaximum());
        // Only a deliberate descent is worth a floor scan; Meteor's small fall-multiplier glide lands harmlessly.
        boolean descending = requested.y() < -.05 || controller.output().y() < -.05;
        var blend = new AdaptiveFlightStep.Blend(s.verticalMaximum(), s.verticalAcceleration(), s.verticalRampStep(),
            descending ? input.floorClearance().getAsDouble() : Double.POSITIVE_INFINITY, reversalBrake);
        // Probe the actual blended trajectory once, rather than separately scanning old and desired headings.
        AdaptiveFlightStep.Result step = AdaptiveFlightStep.move(controller, requested,
            Finite.clamp(s.stockHorizontalSpeed(), 0, 64, 0),
            Math.max(Math.abs(requested.y()), Finite.clamp(s.stockVerticalSpeed(), 0, 64, 0) * 0.5),
            input.body(), input.probe(), s.lookahead(), s.margin(),
            new AdaptiveFlightStep.Controls(sprinting,
                share(sprinting ? s.sprint() : s.cruise(), horizon),
                share(sprinting ? s.verticalSprint() : s.verticalCruise(), verticalHorizon),
                verticalInput, releaseSafeCollisions, input.glide(), releaseSpeed, blend, boost,
                maneuverMode ? s.maneuverCeiling() : Double.POSITIVE_INFINITY));
        Motion actual = step.motion();
        contactResolved = false;
        if (step.safeManeuvering()) {
            Motion resolved = input.collisions().resolve(actual);
            contactResolved = difference(actual, resolved) > EPSILON;
            actual = resolved;
            controller.accept(actual);
        }
        // As in VolytraFly, only a real collision costs ramp speed; guard clipping resumes once clear.
        if (contactResolved) {
            horizontalRamp.limitTo(actual.horizontalLength());
            verticalRamp.limitTo(Math.abs(actual.y()));
        }
        if (closeObjectOverride && step.safeManeuvering() && !contactResolved) closeObjectOverride = false;
        adaptiveMaximum = step.adaptiveMaximum();
        maneuvering = step.safeManeuvering();
        // Slow flight in open air is released to Minecraft collision too; only contact or a blocked crawl is an object.
        nearObject = maneuvering && (contactResolved
            || step.corridor() != null && step.corridor().reason() == FlightCorridor.Reason.OBSTACLE);
        if (step.corridor() != null) chunkChecks += step.corridor().chunkChecks();
        lastStep = step;
        return step;
    }

    /**
     * A further controlled movement in the same tick, after {@link #begin}: it must not reuse clearance from the
     * old position or advance acceleration twice, so it only shortens the tick's movement to what is clear.
     */
    public void repeat(AABB body, FlightCorridor.Probe probe) {
        Motion output = controller.output();
        if (output.length() <= EPSILON) return;
        FlightCorridor.Result actual = FlightCorridor.inspect(body, new Vec3(output.x(), output.y(), output.z()),
            output.length() + settings.margin(), releaseSafeCollisions && maneuvering
                && output.length() <= releaseSpeed ? FlightCorridor.Probe.chunksOnly(probe) : probe);
        chunkChecks += actual.chunkChecks();
        double allowed = Math.max(0, actual.clearDistance() - settings.margin());
        if (allowed < output.length()) controller.accept(output.scaled(allowed / output.length()));
    }

    /**
     * A server position correction. A setback keeps momentum, rewinds each boost ramp by its rewind ticks and holds
     * it there briefly, as VolytraFly does; a teleport elsewhere (such as a command) stops the flight.
     */
    public void setback(boolean teleport) {
        if (teleport) {
            controller.reset();
            horizontalRamp.disengage();
            verticalRamp.disengage();
            verticalBoostDirection = 0;
            boostActive = false;
            clearManeuver();
            return;
        }
        horizontalRamp.corrected();
        verticalRamp.corrected();
    }

    /** Stock flight takes over: stop controlled movement and release both ramps. */
    public void suspend() {
        controller.accept(Motion.ZERO);
        horizontalRamp.disengage();
        verticalRamp.disengage();
        verticalBoostDirection = 0;
        boostActive = false;
        maneuvering = nearObject = closeObjectOverride = false;
        adaptiveMaximum = 0;
        clearManeuver();
    }

    public void reset() {
        controller.reset();
        horizontalRamp.reset();
        verticalRamp.reset();
        verticalBoostDirection = 0;
        sprintKey = boostActive = maneuvering = nearObject = closeObjectOverride = false;
        releaseSafeCollisions = contactResolved = false;
        adaptiveMaximum = reversalBrake = 0;
        clearManeuver();
        lastStep = null;
        chunkChecks = 0;
    }

    public Motion output() { return controller.output(); }
    /** Sprint is boosting (outside unlimited boost) on the latest step. */
    public boolean sprintBoosting() { return controller.isBoosting(); }
    public boolean boostActive() { return boostActive; }
    public double horizontalBoost() { return horizontalRamp.speed(); }
    public double verticalBoost() { return verticalRamp.speed(); }
    public double horizontalBoostCeiling() { return horizontalRamp.ceiling(); }
    public double verticalBoostCeiling() { return verticalRamp.ceiling(); }
    public double adaptiveMaximum() { return adaptiveMaximum; }
    public boolean maneuvering() { return maneuvering; }
    public boolean nearObject() { return nearObject; }
    public boolean closeObjectOverride() { return closeObjectOverride; }
    /** Maneuver mode holds total 3D speed to its ceiling on the latest step. */
    public boolean maneuverMode() { return maneuverMode; }
    public boolean contactResolved() { return contactResolved; }
    /** The latest step, or null before the first. */
    public AdaptiveFlightStep.Result lastStep() { return lastStep; }
    public int chunkChecks() { return chunkChecks; }

    /** Engages, advances or releases each axis's boost ramp for this step; null while neither boosts. */
    private AdaptiveFlightStep.Boost updateBoost(Motion requested, int verticalInput) {
        boolean boosting = settings.unlimitedBoost() && sprintKey;
        Motion previous = controller.output();
        if (boosting && requested.horizontalLength() > 0) {
            if (horizontalRamp.engaged()) horizontalRamp.advance();
            else horizontalRamp.engage(previous.horizontalLength());
        } else horizontalRamp.disengage();
        if (boosting && verticalInput != 0) {
            // While a reversal slows the old direction, the new direction's boost waits at its start speed.
            if (verticalRamp.engaged() && verticalBoostDirection == verticalInput && previous.y() * verticalInput >= 0)
                verticalRamp.advance();
            else verticalRamp.engage(previous.y() * verticalInput > 0 ? Math.abs(previous.y()) : 0);
            verticalBoostDirection = verticalInput;
        } else {
            verticalRamp.disengage();
            verticalBoostDirection = 0;
        }
        // Maneuvering holds both ramps to their share of the 3D ceiling, so they speed up from it afterwards.
        double ramps = Math.hypot(horizontalRamp.speed(), verticalRamp.speed());
        if (maneuverMode && ramps > settings.maneuverCeiling()) {
            double ceiling = settings.maneuverCeiling();
            verticalRamp.limitTo(verticalRamp.speed() * ceiling / ramps);
            // The vertical ramp never drops below its start speed, so horizontal takes what remains.
            horizontalRamp.limitTo(Math.sqrt(Math.max(0, ceiling * ceiling
                - verticalRamp.speed() * verticalRamp.speed())));
        }
        boostActive = horizontalRamp.speed() > 0 || verticalRamp.speed() > 0;
        return boostActive ? new AdaptiveFlightStep.Boost(horizontalRamp.speed(), verticalRamp.speed(),
            settings.boostCeiling()) : null;
    }

    /** Maneuvering: horizontal and vertical input while turning, held for a short grace once that stops. */
    private void updateManeuver(Motion requested, int verticalInput) {
        if (!settings.maneuverMode()) {
            clearManeuver();
            return;
        }
        Motion previous = controller.output();
        boolean turning = false;
        if (previous.horizontalLength() > EPSILON && requested.horizontalLength() > 0) {
            double gap = Math.IEEEremainder(Math.atan2(requested.z(), requested.x())
                - Math.atan2(previous.z(), previous.x()), 2 * Math.PI);
            turning = Math.abs(gap) > Math.toRadians(MANEUVER_TURN_DEGREES);
        }
        if (turning && verticalInput != 0) maneuverTicks = MANEUVER_HOLD_TICKS;
        else if (maneuverTicks > 0) maneuverTicks--;
        maneuverMode = maneuverTicks > 0;
    }

    private void clearManeuver() {
        maneuverMode = false;
        maneuverTicks = 0;
    }

    /** Meteor Vanilla's requested movement from the keys, with its fall-multiplier sink. */
    private Motion requested(Input input) {
        int forward = input.forward();
        int right = input.right();
        double length = Math.hypot(forward, right);
        double speed = Finite.clamp(settings.stockHorizontalSpeed(), 0, 64, 0);
        double yaw = Math.toRadians(input.yaw());
        double x = length == 0 ? 0 : (-Math.sin(yaw) * forward - Math.cos(yaw) * right) * speed / length;
        double z = length == 0 ? 0 : (Math.cos(yaw) * forward - Math.sin(yaw) * right) * speed / length;
        double naturalY = input.naturalY();
        double y = naturalY < 0 ? naturalY * Finite.clamp(settings.fallMultiplier(), 0, 64, 0) : 0;
        double vertical = Finite.clamp(settings.stockVerticalSpeed(), 0, 64, 0);
        if (input.jump()) y += 0.5 * vertical;
        else if (input.sneak()) y -= 0.5 * vertical;
        return new Motion(x, Finite.clamp(y, -64, 64, 0), z);
    }

    /** The Sprint caps stop each boost ramp short without reshaping its curve. */
    private void configureRamps() {
        Settings s = settings;
        horizontalRamp.configure(new BoostRamp.Settings(s.boostStart(), s.boostTop(), s.boostRampStep(),
            s.rewindTicks(), s.setbackHoldTicks(), Finite.clamp(s.sprint(), 0, Double.MAX_VALUE, 0)));
        // The ramp needs a positive step; with vertical-ramp-step at zero, vertical boost holds its start speed.
        verticalRamp.configure(new BoostRamp.Settings(s.boostVerticalStart(), s.verticalMaximum(),
            Math.max(1e-6, s.verticalRampStep()), s.rewindTicks(), s.setbackHoldTicks(),
            Finite.clamp(s.verticalSprint(), 0, Double.MAX_VALUE, 0)));
    }

    /** A speed cap as a share of what the adaptive engine can attain, at most all of it. */
    private static double share(double cap, double attainable) {
        if (!(attainable > 0) || !(cap > 0)) return 0;
        return Math.min(1, cap / attainable);
    }

    private static AdaptiveFlightController.Settings controllerSettings(Settings s) {
        return new AdaptiveFlightController.Settings(s.acceleration(), s.braking(), s.turnRate(),
            Math.max(0, Math.min(s.lookahead(), FlightCorridor.MAX_DISTANCE) - s.margin()),
            // Without unlimited boost, braking stays constant, exactly as adaptive flight always braked. A Sprint
            // cap never moves this: braking is planned from the ramp's own top speed.
            s.unlimitedBoost() ? s.boostTop() : 0);
    }

    private static double difference(Motion a, Motion b) {
        return Math.sqrt((a.x() - b.x()) * (a.x() - b.x())
            + (a.y() - b.y()) * (a.y() - b.y())
            + (a.z() - b.z()) * (a.z() - b.z()));
    }
}
