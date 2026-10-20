package dev.tntcoating.core;

import dev.tntcoating.core.AdaptiveFlightController.Motion;
import dev.tntcoating.core.FlightFeelScenarios.Frame;
import dev.tntcoating.core.FlightFeelScenarios.Keys;
import dev.tntcoating.core.FlightFeelScenarios.Profile;
import dev.tntcoating.core.FlightFeelScenarios.World;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

/**
 * AdaptiveElytraFly's per-tick pipeline as of commit 08ec80a, line for line: onTick's controller, ramp and
 * learning bookkeeping, then move()'s first SELF move of the tick. Held Sprint, a ready swap delay, Meteor
 * Vanilla mode with avoid-blocks, and no setbacks: the flight feel the rework must reproduce.
 */
final class LegacyFeelDriver implements FlightFeelScenarios.Driver {
    private static final double EPSILON = 1e-7;
    private static final double CORRECTION_BACKOFF = .75, RECOVERY_RATE = .0025;
    private static final int CORRECTION_PAUSE = 5, RECOVERY_DELAY = 60;
    private static final Vec3 DOWN = new Vec3(0, -1, 0);
    private static final double FLOOR_SCAN = 32;

    private Profile profile;
    private AdaptiveFlightController controller;
    private final AdaptiveFlightLearning learning = new AdaptiveFlightLearning();
    private final BoostRamp horizontalRamp = new BoostRamp(new BoostRamp.Settings(0, 0, 1, 0, false));
    private final BoostRamp verticalRamp = new BoostRamp(new BoostRamp.Settings(0, 0, 1, 0, false));
    private final BoostSpeedLimit boostSpeedLimit = new BoostSpeedLimit();
    private final ElytraBoostDelay boostDelay = new ElytraBoostDelay();
    private long boostClock;
    private int verticalBoostDirection;
    private boolean boostActive, nearObject, closeObjectOverride;

    @Override public void reset(Profile profile) {
        this.profile = profile;
        controller = new AdaptiveFlightController(controllerSettings());
        learning.configure(profile.verticalMax(), profile.verticalAcceleration());
        learning.forget();
        horizontalRamp.reset();
        verticalRamp.reset();
        boostSpeedLimit.reset();
        boostDelay.reset();
        boostClock = 0;
        verticalBoostDirection = 0;
        boostActive = nearObject = closeObjectOverride = false;
    }

    @Override public Motion output() { return controller.output(); }

    @Override public Frame tick(World world, Keys keys, AABB body, ServerGlide.Context glide) {
        // onTick (TickEvent.Pre)
        controller.configure(controllerSettings());
        controller.tick();
        learning.configure(profile.verticalMax(), profile.verticalAcceleration());
        boostClock++;
        configureRamps();
        horizontalRamp.tick();
        verticalRamp.tick();
        boostDelay.observe(boostClock, true, true);
        learning.tick(RECOVERY_DELAY, RECOVERY_RATE);

        // move(): first SELF move of this tick
        boostDelay.observe(boostClock, true, true);
        boolean sprintKey = boostDelay.ready() && keys.sprint();
        if (boostVisible() || !boostDelay.ready()) closeObjectOverride = false;
        else if (nearObject && sprintKey) closeObjectOverride = true;
        double releaseSpeed = closeObjectOverride ? AdaptiveFlightStep.MAX_HORIZONTAL_SPEED : profile.closeObjectSpeed();
        boolean releaseSafeCollisions = profile.safeManeuvering() && (closeObjectOverride
            || world.vanillaSpeed() <= releaseSpeed && world.vanillaSpeed() <= releaseSpeed);
        boolean harmlessStart = releaseSafeCollisions && controller.output().length() <= releaseSpeed;
        FlightCorridor.Probe probe = world.probe(() -> body, controller::output);
        FlightCorridor.Probe chunksOnly = FlightCorridor.Probe.chunksOnly(probe);
        FlightCorridor.Result start = FlightCorridor.inspectStart(body, harmlessStart ? chunksOnly : probe);
        if (start.reason() != FlightCorridor.Reason.CLEAR)
            throw new IllegalStateException("Scenario started inside terrain; the module would yield to stock flight.");

        Motion requested = requested(keys, world.naturalY());
        boolean sprinting = sprintKey || closeObjectOverride;
        int verticalInput = Finite.clamp(profile.flyVerticalSpeed(), 0, 64, 0) == 0 ? 0
            : keys.jump() ? 1 : keys.sneak() ? -1 : 0;
        AdaptiveFlightStep.Boost boostSpeeds = updateBoost(requested, verticalInput, sprintKey);
        var limits = learning.limits();
        boolean descending = requested.y() < -.05 || controller.output().y() < -.05;
        var blend = new AdaptiveFlightStep.Blend(profile.verticalMax(), profile.verticalRampStep(),
            Math.min(1, limits.verticalAcceleration() / profile.verticalAcceleration()),
            descending ? floorClearance(world, body) : Double.POSITIVE_INFINITY);
        AdaptiveFlightStep.Result step = AdaptiveFlightStep.move(controller, requested,
            Finite.clamp(profile.flyHorizontalSpeed(), 0, 64, 0),
            Math.max(Math.abs(requested.y()), Finite.clamp(profile.flyVerticalSpeed(), 0, 64, 0) * 0.5),
            body, probe, profile.lookahead(), profile.margin(),
            new AdaptiveFlightStep.Controls(sprinting,
                (sprinting ? profile.sprint() : profile.cruise()) / 100,
                (sprinting ? profile.verticalSprint() : profile.verticalCruise()) / 100,
                verticalInput, releaseSafeCollisions, glide, limits, releaseSpeed, blend, boostSpeeds));
        Motion actualMotion = step.motion();
        boolean contactResolved = false;
        if (step.safeManeuvering()) {
            Motion resolved = world.collide(body, actualMotion);
            contactResolved = difference(actualMotion, resolved) > EPSILON;
            actualMotion = resolved;
            controller.accept(actualMotion);
        }
        if (contactResolved) {
            horizontalRamp.limitTo(actualMotion.horizontalLength());
            verticalRamp.limitTo(Math.abs(actualMotion.y()));
        }
        if (closeObjectOverride && step.safeManeuvering() && !contactResolved) closeObjectOverride = false;
        boolean clean = step.corridor() != null && step.corridor().reason() == FlightCorridor.Reason.CLEAR
            && !step.terrainLimited() && !step.glideLimited() && !contactResolved;
        if (boostActive) {
            boostSpeedLimit.observe(actualMotion, clean);
            learning.resetHistory();
        } else learning.observe(actualMotion, verticalInput, sprinting,
            clean && controller.speedFactor() >= .999,
            (sprinting ? profile.sprint() : profile.cruise()) / 100,
            (sprinting ? profile.verticalSprint() : profile.verticalCruise()) / 100);
        boolean maneuvering = step.safeManeuvering();
        nearObject = maneuvering && (contactResolved
            || step.corridor() != null && step.corridor().reason() == FlightCorridor.Reason.OBSTACLE);
        return new Frame(controller.output(), horizontalRamp.speed(), verticalRamp.speed());
    }

    private boolean boostVisible() { return profile.unlimitedBoost(); }

    private AdaptiveFlightController.Settings controllerSettings() {
        return new AdaptiveFlightController.Settings(profile.acceleration(), profile.braking(), CORRECTION_BACKOFF,
            CORRECTION_PAUSE, RECOVERY_DELAY, RECOVERY_RATE, profile.turnRate(),
            Math.max(0, Math.min(profile.lookahead(), FlightCorridor.MAX_DISTANCE) - profile.margin()),
            boostVisible() ? profile.boostMax() * profile.sprint() / 100 : 0);
    }

    private void configureRamps() {
        boostSpeedLimit.configure(true);
        boostSpeedLimit.tick();
        double horizontalMaximum = profile.boostMax() * profile.sprint() / 100;
        double verticalMaximum = profile.verticalMax() * profile.verticalSprint() / 100;
        horizontalRamp.configure(new BoostRamp.Settings(Math.min(profile.boostStart(), horizontalMaximum),
            horizontalMaximum, profile.boostRampStep(), profile.rewindTicks(), false));
        verticalRamp.configure(new BoostRamp.Settings(Math.min(profile.boostVerticalStart(), verticalMaximum),
            verticalMaximum, Math.max(1e-6, profile.verticalRampStep()), profile.rewindTicks(), false));
    }

    private AdaptiveFlightStep.Boost updateBoost(Motion requested, int verticalInput, boolean sprintKey) {
        boolean boosting = profile.unlimitedBoost() && sprintKey && controller.pauseTicksRemaining() == 0;
        Motion previous = controller.output();
        if (boosting && requested.horizontalLength() > 0) {
            if (horizontalRamp.engaged()) horizontalRamp.advance();
            else horizontalRamp.engage(previous.horizontalLength());
        } else horizontalRamp.disengage();
        if (boosting && verticalInput != 0) {
            if (verticalRamp.engaged() && verticalBoostDirection == verticalInput) verticalRamp.advance();
            else verticalRamp.engage(previous.y() * verticalInput > 0 ? Math.abs(previous.y()) : 0);
            verticalBoostDirection = verticalInput;
        } else {
            verticalRamp.disengage();
            verticalBoostDirection = 0;
        }
        boostActive = horizontalRamp.speed() > 0 || verticalRamp.speed() > 0;
        return boostActive ? new AdaptiveFlightStep.Boost(horizontalRamp.speed(), verticalRamp.speed(),
            boostSpeedLimit.ceiling()) : null;
    }

    private Motion requested(Keys keys, double naturalY) {
        int forward = keys.forward();
        int right = keys.right();
        double length = Math.hypot(forward, right);
        double speed = Finite.clamp(profile.flyHorizontalSpeed(), 0, 64, 0);
        double yaw = Math.toRadians(keys.yaw());
        double x = length == 0 ? 0 : (-Math.sin(yaw) * forward - Math.cos(yaw) * right) * speed / length;
        double z = length == 0 ? 0 : (Math.cos(yaw) * forward - Math.sin(yaw) * right) * speed / length;
        double y = naturalY < 0 ? naturalY * Finite.clamp(profile.fallMultiplier(), 0, 64, 0) : 0;
        if (keys.jump()) y += 0.5 * Finite.clamp(profile.flyVerticalSpeed(), 0, 64, 0);
        else if (keys.sneak()) y -= 0.5 * Finite.clamp(profile.flyVerticalSpeed(), 0, 64, 0);
        return new Motion(x, Finite.clamp(y, -64, 64, 0), z);
    }

    private double floorClearance(World world, AABB body) {
        FlightCorridor.Result below = FlightCorridor.inspect(body, DOWN, FLOOR_SCAN, world.floorProbe());
        return below.reason() == FlightCorridor.Reason.CLEAR ? Double.POSITIVE_INFINITY
            : Math.max(0, below.clearDistance() - profile.margin());
    }

    private static double difference(Motion a, Motion b) {
        return Math.sqrt((a.x() - b.x()) * (a.x() - b.x())
            + (a.y() - b.y()) * (a.y() - b.y())
            + (a.z() - b.z()) * (a.z() - b.z()));
    }
}
