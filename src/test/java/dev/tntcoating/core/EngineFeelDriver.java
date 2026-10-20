package dev.tntcoating.core;

import dev.tntcoating.core.AdaptiveFlightController.Motion;
import dev.tntcoating.core.FlightFeelScenarios.Frame;
import dev.tntcoating.core.FlightFeelScenarios.Keys;
import dev.tntcoating.core.FlightFeelScenarios.Profile;
import dev.tntcoating.core.FlightFeelScenarios.World;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

/** Drives {@link AdaptiveFlightEngine} exactly as AdaptiveElytraFly does: one tick start, then the first move. */
final class EngineFeelDriver implements FlightFeelScenarios.Driver {
    private static final Vec3 DOWN = new Vec3(0, -1, 0);
    private static final double FLOOR_SCAN = 32;

    private Profile profile;
    private AdaptiveFlightEngine engine;

    static AdaptiveFlightEngine.Settings settings(Profile p) {
        return new AdaptiveFlightEngine.Settings(p.acceleration(), p.braking(), p.turnRate(), p.cruise(), p.sprint(),
            p.verticalMax(), p.verticalCruise(), p.verticalSprint(), p.verticalAcceleration(), p.verticalRampStep(),
            p.unlimitedBoost(), p.boostMax(), p.boostStart(), p.boostRampStep(), p.boostVerticalStart(),
            p.rewindTicks(), 1, Math.min(ServerMoveLimit.SINGLE, p.boostMax() * p.sprint() / 100), p.lookahead(), p.margin(), p.safeManeuvering(), p.closeObjectSpeed(),
            p.flyHorizontalSpeed(), p.flyVerticalSpeed(), p.fallMultiplier());
    }

    @Override public void reset(Profile profile) {
        this.profile = profile;
        engine = new AdaptiveFlightEngine(settings(profile));
    }

    @Override public Motion output() { return engine.output(); }

    @Override public Frame tick(World world, Keys keys, AABB body, ServerGlide.Context glide) {
        engine.startTick(settings(profile));
        FlightCorridor.Probe probe = world.probe(() -> body, engine::output);
        if (!engine.begin(keys.sprint(), true, world.vanillaSpeed(), world.vanillaSpeed(), body, probe))
            throw new IllegalStateException("Scenario started inside terrain; the module would yield to stock flight.");
        engine.step(new AdaptiveFlightEngine.Input(keys.forward(), keys.right(), keys.yaw(), keys.jump(), keys.sneak(),
            world.naturalY(), body, probe, () -> floorClearance(world, body), glide,
            movement -> world.collide(body, movement)));
        return new Frame(engine.output(), engine.horizontalBoost(), engine.verticalBoost());
    }

    private double floorClearance(World world, AABB body) {
        FlightCorridor.Result below = FlightCorridor.inspect(body, DOWN, FLOOR_SCAN, world.floorProbe());
        return below.reason() == FlightCorridor.Reason.CLEAR ? Double.POSITIVE_INFINITY
            : Math.max(0, below.clearDistance() - profile.margin());
    }
}
