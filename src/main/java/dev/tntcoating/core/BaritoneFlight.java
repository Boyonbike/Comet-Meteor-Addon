package dev.tntcoating.core;

import dev.tntcoating.core.AdaptiveFlightController.Motion;
import java.util.Objects;
import java.util.function.DoubleSupplier;
import net.minecraft.world.phys.AABB;

/** Applies Baritone's requested heading through the same flight pipeline as Adaptive Elytra Fly. */
public final class BaritoneFlight {
    private static final double PITCH_DEADZONE = .1;

    /** Speed caps are blocks per second; ramp steps retain Adaptive Elytra Fly's blocks-per-tick units. */
    public record Settings(double speedBps, double verticalSpeedBps, double rampStep, double verticalRampStep) {
        public Settings {
            Finite.requireNonnegative(speedBps, "Flight speed");
            Finite.requireNonnegative(verticalSpeedBps, "Vertical flight speed");
            Finite.requireNonnegative(rampStep, "Flight ramp step");
            Finite.requireNonnegative(verticalRampStep, "Vertical flight ramp step");
            if (rampStep == 0) throw new IllegalArgumentException("Flight ramp step must be positive.");
        }
    }

    private final AdaptiveFlightEngine engine;
    private int lastTick;
    private boolean tickStarted, stepped, landing;

    public BaritoneFlight(AdaptiveFlightEngine.Settings profile) {
        engine = new AdaptiveFlightEngine(Objects.requireNonNull(profile, "profile"));
    }

    /** Caps arrival speed by distance to the destination, keeping the original boost curve and ramp steps. */
    public static Settings approach(Settings config, double distanceToDestination) {
        Objects.requireNonNull(config, "config");
        Finite.requireNonnegative(distanceToDestination, "Destination distance");
        double arrivalCap = Math.max(4, distanceToDestination * 4);
        return new Settings(Math.min(config.speedBps(), arrivalCap),
            Math.min(config.verticalSpeedBps(), arrivalCap), config.rampStep(), config.verticalRampStep());
    }

    /**
     * Moves once along Baritone's captured rotation. Further moves in the same tick only recheck clearance at
     * the new body. A null result yields to ordinary Elytra physics when the starting body overlaps unknown terrain.
     */
    public Motion move(AdaptiveFlightEngine.Settings profile, Settings config, int tick, float yaw, float pitch,
                       boolean boostReady, double vanillaSpeed, double vanillaMovement,
                       AABB body, FlightCorridor.Probe probe, DoubleSupplier floorClearance,
                       ServerGlide.Context glide, AdaptiveFlightEngine.Collisions collisions) {
        Objects.requireNonNull(profile, "profile");
        Objects.requireNonNull(config, "config");
        landingMode(false);
        double headingPitch = Finite.clamp(pitch, -90, 90, 0);
        return process(settings(profile, config, pitch, boostReady), tick, 1,
            (float) Finite.clamp(yaw, -Float.MAX_VALUE, Float.MAX_VALUE, 0),
            headingPitch < -PITCH_DEADZONE, headingPitch > PITCH_DEADZONE,
            boostReady, vanillaSpeed, vanillaMovement, body, probe, floorClearance, glide, collisions);
    }

    /**
     * Descends towards the landing column without using Baritone's air-waypoint pitch. Offsets are the target
     * column center minus the current position. Each axis is capped at .2 blocks per tick; lower positive
     * cruise caps still apply, while disabled cruise axes retain this slow landing control.
     */
    public Motion land(AdaptiveFlightEngine.Settings profile, Settings config, int tick,
                       double offsetX, double offsetZ, boolean boostReady, double vanillaSpeed, double vanillaMovement,
                       AABB body, FlightCorridor.Probe probe, DoubleSupplier floorClearance,
                       ServerGlide.Context glide, AdaptiveFlightEngine.Collisions collisions) {
        Objects.requireNonNull(profile, "profile");
        Objects.requireNonNull(config, "config");
        if (!Double.isFinite(offsetX) || !Double.isFinite(offsetZ))
            throw new IllegalArgumentException("Landing offsets must be finite.");
        landingMode(true);
        double distance = Math.hypot(offsetX, offsetZ);
        double horizontal = Math.min(distance, landingCap(config.speedBps()));
        double vertical = landingCap(config.verticalSpeedBps());
        float yaw = distance == 0 ? 0 : (float) Math.toDegrees(Math.atan2(-offsetX, offsetZ));
        return process(withCaps(profile, config, horizontal, vertical, true), tick, distance == 0 ? 0 : 1,
            yaw, false, true, boostReady, vanillaSpeed, vanillaMovement, body, probe, floorClearance, glide, collisions);
    }

    private Motion process(AdaptiveFlightEngine.Settings settings, int tick, int forward, float yaw,
                           boolean climb, boolean descend, boolean boostReady, double vanillaSpeed, double vanillaMovement,
                           AABB body, FlightCorridor.Probe probe, DoubleSupplier floorClearance,
                           ServerGlide.Context glide, AdaptiveFlightEngine.Collisions collisions) {
        if (!tickStarted || tick != lastTick) {
            engine.startTick(settings);
            lastTick = tick;
            tickStarted = true;
            stepped = false;
        }
        if (!engine.begin(true, boostReady, vanillaSpeed, vanillaMovement, body, probe)) return null;
        if (stepped) engine.repeat(body, probe);
        else {
            engine.step(new AdaptiveFlightEngine.Input(forward, 0, yaw, climb, descend, 0,
                body, probe, floorClearance, glide, collisions));
            stepped = true;
        }
        return engine.output();
    }

    public Motion output() { return engine.output(); }

    public void setback(boolean teleport) {
        engine.setback(teleport);
        // A correction may arrive between two movement events in one client tick. Reapply the corrected ramp
        // on the next event immediately; subsequent events again only recheck terrain.
        tickStarted = stepped = false;
    }

    public void reset() {
        engine.reset();
        tickStarted = stepped = landing = false;
    }

    private void landingMode(boolean active) {
        if (landing == active) return;
        // Landing replaces high cruise momentum immediately, before the first descent can overshoot the column.
        engine.reset();
        tickStarted = stepped = false;
        landing = active;
    }

    private static double landingCap(double speedBps) {
        return speedBps > 0 ? Math.min(AdaptiveFlightStep.LANDING_SPEED, speedBps / 20)
            : AdaptiveFlightStep.LANDING_SPEED;
    }

    private AdaptiveFlightEngine.Settings settings(AdaptiveFlightEngine.Settings p, Settings config,
                                                   float pitch, boolean boostReady) {
        double horizontalCap = Math.min(ServerMoveLimit.SINGLE, config.speedBps() / 20);
        double verticalCap = Math.min(p.verticalMaximum(),
            Math.min(ServerMoveLimit.SINGLE, config.verticalSpeedBps() / 20));
        double curveTop = Math.min(ServerMoveLimit.SINGLE, p.boostTop());
        double headingPitch = Finite.clamp(pitch, -90, 90, 0);
        double slope = Math.abs(Math.tan(Math.toRadians(headingPitch)));
        boolean level = Math.abs(headingPitch) <= PITCH_DEADZONE;
        // A steep route spends its vertical budget first. Lowering the horizontal cap keeps its requested
        // slope attainable instead of flattening the route when the climb or descent reaches its limit.
        // An explicitly disabled vertical axis keeps independent horizontal flight level.
        if (!level && verticalCap > 0) horizontalCap = Math.min(horizontalCap, verticalCap / slope);
        // Terrain may stop actual horizontal movement while the engine's ramp remains ready to follow a new
        // clear heading. Keep that planned speed available for the slope cap so a climb can escape the stop.
        // The engine owns the sole ramp and its setback hold; no second ramp predicts this tick's movement.
        double horizontal = Math.min(horizontalCap,
            Math.max(engine.output().horizontalLength(), engine.horizontalBoost()));
        if (boostReady && engine.horizontalBoost() == 0)
            horizontal = Math.max(horizontal, Math.min(horizontalCap, Math.min(curveTop, p.boostStart())));
        // A level heading releases vertical input and keeps the normal braking ceiling, so an existing climb
        // or dive eases out. An explicit zero vertical speed still disables the axis immediately.
        double vertical = level ? verticalCap : Math.min(verticalCap, slope * horizontal);
        return withCaps(p, config, horizontalCap, vertical, false);
    }

    private static AdaptiveFlightEngine.Settings withCaps(AdaptiveFlightEngine.Settings p, Settings config,
                                                           double horizontalCap, double verticalCap, boolean landing) {
        return new AdaptiveFlightEngine.Settings(p.acceleration(), p.braking(), p.turnRate(),
            horizontalCap, horizontalCap, p.verticalMaximum(), verticalCap, verticalCap,
            p.verticalAcceleration(), config.verticalRampStep(), p.verticalReversalTicks(),
            true, Math.min(ServerMoveLimit.SINGLE, p.boostTop()), p.boostStart(), config.rampStep(), p.boostVerticalStart(),
            p.rewindTicks(), p.setbackHoldTicks(), Math.min(ServerMoveLimit.SINGLE, p.boostCeiling()),
            p.maneuverMode(), p.maneuverCeiling(), p.lookahead(), p.margin(), landing || p.safeManeuvering(),
            landing ? Math.max(p.closeObjectSpeed(), Math.hypot(horizontalCap, verticalCap)) : p.closeObjectSpeed(),
            1, 1, 0);
    }
}
