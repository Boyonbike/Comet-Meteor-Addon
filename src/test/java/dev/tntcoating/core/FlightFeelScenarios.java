package dev.tntcoating.core;

import dev.tntcoating.core.AdaptiveFlightController.Motion;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.function.IntFunction;
import java.util.function.Supplier;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

/**
 * Scripted, setback-free flights whose per-tick output is the recorded flight feel. A driver runs the
 * Adaptive Elytra Fly pipeline exactly as the module does; the runner plays the part of Minecraft: it
 * moves the body, resolves collisions against a synthetic wall, floor and unloaded-chunk edge, and
 * tracks the server's hidden glide.
 */
final class FlightFeelScenarios {
    static final Path GOLDEN_DIRECTORY = Path.of("src/test/resources/flight-feel");
    private static final double GRAVITY = .08;
    private static final double BODY_HALF_WIDTH = .3, BODY_HEIGHT = .6;

    private FlightFeelScenarios() {}

    /**
     * Module and Meteor Elytra Fly settings. Cruise and Sprint values are the engine's speed caps in blocks per tick;
     * the profiles below match the 35%/100% shares they were recorded with.
     */
    record Profile(String name,
                   double acceleration, double braking, double turnRate, double cruise, double sprint,
                   double verticalMax, double verticalCruise, double verticalSprint,
                   double verticalAcceleration, double verticalRampStep,
                   boolean unlimitedBoost, double boostMax, double boostStart, double boostRampStep,
                   double boostVerticalStart, int rewindTicks,
                   int lookahead, double margin, boolean safeManeuvering, double closeObjectSpeed,
                   double flyHorizontalSpeed, double flyVerticalSpeed, double fallMultiplier) {}

    static final Profile DEFAULTS = new Profile("defaults",
        .15, .3, 15, cruise(.3, 64, .35), 15, 3, 3 * .35, 3, .05, .5,
        true, 14.999, 2.999, .3, 1.5, 15,
        64, .35, true, .6,
        1, 1, .01);

    /** The user's saved values as of 2026-10-20 (Adaptive Elytra Fly and Meteor Elytra Fly). */
    static final Profile USER = new Profile("user",
        .15, .3, 15, cruise(.3, 64, .2), 15, 3, 3 * .35, 3, .15, .5,
        true, 14.999, 2.999, .3, 1.2499999999999998, 15,
        64, .2, true, .6,
        2.797482889048999, 2, .01);

    static final List<Profile> PROFILES = List.of(DEFAULTS, USER);

    /** The old default cruise: 35% of the adaptive engine's attainable speed. */
    private static double cruise(double braking, int lookahead, double margin) {
        return AdaptiveFlightStep.maximumSpeed(braking, lookahead, margin) * .35;
    }

    /** Keys held this tick; yaw in degrees, as Minecraft reports it. */
    record Keys(int forward, int right, float yaw, boolean jump, boolean sneak, boolean sprint) {
        static Keys of(int forward, float yaw, boolean jump, boolean sneak, boolean sprint) {
            return new Keys(forward, 0, yaw, jump, sneak, sprint);
        }
    }

    /**
     * A wall filling every block at or beyond {@code wallZ}, a floor below {@code floorY} and chunks at or beyond
     * chunk {@code unloadedChunkZ} missing; infinities mean none. {@code vanillaSpeed} stands for the length of
     * Minecraft's own glide velocity and movement, and {@code naturalY} its vertical movement, each tick.
     */
    record World(double wallZ, double floorY, int unloadedChunkZ, boolean flyIntoUnloaded,
                 boolean glideGuard, double vanillaSpeed, double naturalY) {
        static World open() {
            return new World(Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY, Integer.MAX_VALUE, false, false, 1.2, -.5);
        }
        World wall(double z) { return new World(z, floorY, unloadedChunkZ, flyIntoUnloaded, glideGuard, vanillaSpeed, naturalY); }
        World floor(double y) { return new World(wallZ, y, unloadedChunkZ, flyIntoUnloaded, glideGuard, vanillaSpeed, naturalY); }
        World unloadedFrom(int chunkZ, boolean flyInto) {
            return new World(wallZ, floorY, chunkZ, flyInto, glideGuard, vanillaSpeed, naturalY);
        }
        World glide() { return new World(wallZ, floorY, unloadedChunkZ, flyIntoUnloaded, true, vanillaSpeed, naturalY); }
        World vanilla(double speed) { return new World(wallZ, floorY, unloadedChunkZ, flyIntoUnloaded, glideGuard, speed, naturalY); }

        boolean loaded(int chunkX, int chunkZ) { return flyIntoUnloaded || chunkZ < unloadedChunkZ; }

        /** The module's terrain probe: blocks wholly below the feet allow a downward touchdown. */
        FlightCorridor.Probe probe(Supplier<AABB> body, Supplier<Motion> output) {
            return new FlightCorridor.Probe() {
                @Override public boolean loaded(int x, int z) { return World.this.loaded(x, z); }
                @Override public boolean clear(AABB swept) {
                    if (swept.maxZ > wallZ) return false;
                    if (swept.minY < floorY) {
                        Motion motion = output.get();
                        return FlightCorridor.floorContactAllowed(body.get(),
                            new Vec3(motion.x(), motion.y(), motion.z()), floorY);
                    }
                    return true;
                }
            };
        }

        /** The module's straight-down floor scan probe: every block is an obstacle. */
        FlightCorridor.Probe floorProbe() {
            return new FlightCorridor.Probe() {
                @Override public boolean loaded(int x, int z) { return World.this.loaded(x, z); }
                @Override public boolean clear(AABB swept) { return swept.maxZ <= wallZ && swept.minY >= floorY; }
            };
        }

        /** Minecraft collision against the wall and floor planes, without step-up. */
        Motion collide(AABB box, Motion movement) {
            double y = movement.y();
            if (y < 0 && box.minY >= floorY - 1e-7) y = Math.max(y, floorY - box.minY);
            double z = movement.z();
            if (z > 0 && box.maxZ <= wallZ + 1e-7) z = Math.min(z, wallZ - box.maxZ);
            return new Motion(movement.x(), y, z);
        }
    }

    record Scenario(String name, World world, int ticks, IntFunction<Keys> keys) {}

    /** One movement tick of output: the final movement and both boost ramp speeds. */
    record Frame(Motion motion, double horizontalRamp, double verticalRamp) {}

    /** Runs the flight pipeline for one movement tick, exactly as the module does. */
    interface Driver {
        void reset(Profile profile);
        /** The controller output the terrain probe sees mid-step. */
        Motion output();
        Frame tick(World world, Keys keys, AABB body, ServerGlide.Context glide);
    }

    static List<Scenario> scenarios() {
        World open = World.open();
        List<Scenario> list = new ArrayList<>();
        list.add(new Scenario("cruise-accelerate", open, 80, t -> Keys.of(1, 0, false, false, false)));
        list.add(new Scenario("cruise-release", open, 140, t -> Keys.of(t < 80 ? 1 : 0, 0, false, false, false)));
        list.add(new Scenario("boost-release", open, 360,
            t -> Keys.of(1, 0, false, false, t >= 40 && t < 240)));
        list.add(new Scenario("turns-cruise", open, 180,
            t -> Keys.of(1, t < 80 ? 0 : t < 120 ? 90 : -80, false, false, false)));
        list.add(new Scenario("turns-boost", open, 300,
            t -> Keys.of(1, t < 140 ? 0 : t < 190 ? 90 : -80, false, false, t >= 20 && t < 230)));
        list.add(new Scenario("strafe-diagonal", open, 140,
            t -> new Keys(t < 100 ? 1 : 0, t < 60 ? 1 : 0, 30, false, false, false)));
        list.add(new Scenario("vertical-cruise", open, 260, t -> Keys.of(1, 0,
            t < 60, t >= 90 && t < 150, false)));
        list.add(new Scenario("vertical-boost", open, 220, t -> Keys.of(t < 120 ? 1 : 0, 0,
            t >= 20 && t < 100, t >= 140 && t < 190, t >= 20 && t < 190)));
        // Jump straight to Sneak and back, cruising and then boosting: each switch turns around the next tick.
        list.add(new Scenario("vertical-reversal", open, 200, t -> Keys.of(1, 0,
            t < 50 || t >= 90 && t < 140, t >= 50 && t < 90 || t >= 140, t >= 90)));
        list.add(new Scenario("climb-boost-only", open, 140, t -> Keys.of(0, 0, t < 80, false, t < 80)));
        list.add(new Scenario("wall-cruise-crawl", open.wall(40.5).vanilla(.3), 220,
            t -> Keys.of(1, 0, false, false, false)));
        list.add(new Scenario("wall-boost", open.wall(160.5), 200, t -> Keys.of(1, 0, false, false, true)));
        list.add(new Scenario("wall-glide-guard", open.wall(40.5).glide(), 160,
            t -> Keys.of(1, 0, false, false, false)));
        list.add(new Scenario("landing", open.floor(180), 260, t -> Keys.of(1, 0, false, true, false)));
        list.add(new Scenario("chunk-edge-stop", open.unloadedFrom(6, false), 200,
            t -> Keys.of(1, 0, false, false, true)));
        list.add(new Scenario("chunk-edge-fly-into", open.unloadedFrom(6, true), 200,
            t -> Keys.of(1, 0, false, false, true)));
        return list;
    }

    /** Plays a scenario through a driver, moving the body and tracking the server's glide as the game would. */
    static List<Frame> run(Driver driver, Profile profile, Scenario scenario) {
        driver.reset(profile);
        World world = scenario.world();
        AABB[] body = { new AABB(.5 - BODY_HALF_WIDTH, 200, .5 - BODY_HALF_WIDTH,
            .5 + BODY_HALF_WIDTH, 200 + BODY_HEIGHT, .5 + BODY_HALF_WIDTH) };
        ServerGlide.Collider collider = world::collide;
        Motion serverGlide = world.glideGuard() ? ServerGlide.steady(0, 0, GRAVITY, false) : null;
        List<Frame> frames = new ArrayList<>(scenario.ticks());
        for (int tick = 0; tick < scenario.ticks(); tick++) {
            Keys keys = scenario.keys().apply(tick);
            ServerGlide.Context glide = serverGlide == null ? null
                : new ServerGlide.Context(serverGlide, 0, 0, keys.yaw(), GRAVITY, false, collider);
            Frame frame = driver.tick(world, keys, body[0], glide);
            Motion moved = world.collide(body[0], frame.motion());
            body[0] = body[0].move(moved.x(), moved.y(), moved.z());
            if (serverGlide != null) serverGlide = ServerGlide.tick(
                new ServerGlide.Context(serverGlide, 0, 0, keys.yaw(), GRAVITY, false, collider), body[0]).velocity();
            frames.add(frame);
        }
        return frames;
    }

    static String fileName(Profile profile, Scenario scenario) {
        return profile.name() + "-" + scenario.name() + ".csv";
    }

    static String toCsv(List<Frame> frames) {
        StringBuilder out = new StringBuilder("tick,x,y,z,horizontalRamp,verticalRamp\n");
        for (int tick = 0; tick < frames.size(); tick++) {
            Frame frame = frames.get(tick);
            out.append(tick).append(',').append(frame.motion().x()).append(',').append(frame.motion().y())
                .append(',').append(frame.motion().z()).append(',').append(frame.horizontalRamp())
                .append(',').append(frame.verticalRamp()).append('\n');
        }
        return out.toString();
    }

    static List<Frame> fromCsv(String csv) {
        List<Frame> frames = new ArrayList<>();
        String[] lines = csv.split("\n");
        for (int line = 1; line < lines.length; line++) {
            String[] cells = lines[line].split(",");
            frames.add(new Frame(new Motion(Double.parseDouble(cells[1]), Double.parseDouble(cells[2]),
                Double.parseDouble(cells[3])), Double.parseDouble(cells[4]), Double.parseDouble(cells[5])));
        }
        return frames;
    }

    static List<Frame> golden(Profile profile, Scenario scenario) throws IOException {
        try (InputStream in = FlightFeelScenarios.class.getResourceAsStream("/flight-feel/" + fileName(profile, scenario))) {
            if (in == null) throw new IOException("Missing golden " + fileName(profile, scenario));
            return fromCsv(new String(in.readAllBytes(), StandardCharsets.UTF_8));
        }
    }

    /** Rewrites every golden from a driver. Run only for a deliberate change of flight feel. */
    static void record(Driver driver) throws IOException {
        Files.createDirectories(GOLDEN_DIRECTORY);
        for (Profile profile : PROFILES) for (Scenario scenario : scenarios()) {
            Files.writeString(GOLDEN_DIRECTORY.resolve(fileName(profile, scenario)), toCsv(run(driver, profile, scenario)));
        }
    }
}
