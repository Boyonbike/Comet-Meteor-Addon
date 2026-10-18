package dev.tntcoating.core;

import dev.tntcoating.core.AdaptiveFlightController.Motion;
import dev.tntcoating.core.AdaptiveFlightController.Settings;
import dev.tntcoating.core.AdaptiveFlightStep.Controls;
import java.util.ArrayList;
import java.util.List;
import java.util.function.BiPredicate;
import net.minecraft.world.phys.AABB;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class ServerGlideTest {
    private static final float EAST = -90; // Minecraft yaw facing +X
    private static final AABB BODY = new AABB(.2, 65, .2, .8, 66.8, .8);

    @Test void glideMatchesVanillaLevelAndClimbingSpeeds() {
        Motion level = glideFromRest(40, 0);
        assertEquals(.42, level.horizontalLength(), .01, "two seconds of level gliding");
        assertEquals(-.15, level.y(), .01);
        assertTrue(level.x() > 0 && Math.abs(level.z()) < 1e-6, "glide follows the look direction");
        assertEquals(1.48, glideFromRest(400, 0).horizontalLength(), .01);
        assertEquals(.37, glideFromRest(400, -20).horizontalLength(), .01, "looking up bleeds horizontal speed");
        assertEquals(1.5, ServerGlide.steady(0, EAST, .08, false).horizontalLength(), .02);
    }

    @Test void glideRedirectsTowardTheLookDirectionWithoutGainingSpeedFromTheTurn() {
        Motion north = ServerGlide.glide(new Motion(0, 0, -1), 0, EAST, .08);
        assertTrue(north.x() > 0, "a tenth of the heading error is corrected each tick");
        assertTrue(north.z() < 0);
        assertTrue(north.horizontalLength() < 1);
    }

    @Test void rocketBoostMatchesFireworkRocketEntity() {
        Motion boosted = ServerGlide.rocket(new Motion(.2, -.1, 0), 0, EAST);
        assertEquals(.2 + .1 + (1.5 - .2) * .5, boosted.x(), 1e-6);
        assertEquals(-.1 + (0 + .1) * .5, boosted.y(), 1e-6);
        assertEquals(0, boosted.z(), 1e-6);
    }

    @Test void damageMatchesTheVanillaThresholdAndFloatRounding() {
        assertEquals(0, ServerGlide.damage(.3, 0));
        assertTrue(ServerGlide.damage(1, .7) > 0, "a loss rounding just above .3 still lands a tiny hit and hurt flash");
        assertEquals(0, ServerGlide.damage(ServerGlide.HARMLESS_SPEED, 0));
        assertEquals(0, ServerGlide.damage(1, .71));
        assertEquals(1, ServerGlide.damage(.4, 0), "half a heart");
        assertEquals(12, ServerGlide.damage(1.5, 0));
        assertEquals((float) (.75 * 10 - 3), ServerGlide.damage(1, .25));
    }

    @Test void serverTickHitsAWallInsideItsReachEvenWhenTheClientIsStill() {
        World world = new World().wall(.35); // where 0.23.6 stopped: its terrain margin
        ServerGlide.Tick tick = ServerGlide.tick(world.glide(new Motion(.42, -.15, 0)), BODY);
        assertTrue(tick.horizontalCollision());
        assertEquals(0, tick.velocity().x(), "the blocked axis keeps no velocity");
        assertTrue(tick.damage() >= .9 && tick.damage() <= 1.3, "the half heart seen at walls: " + tick.damage());

        ServerGlide.Tick clear = ServerGlide.tick(world.glide(new Motion(.42, -.15, 0)), BODY.move(-.5, 0, 0));
        assertFalse(clear.horizontalCollision());
        assertEquals(0, clear.damage());
    }

    @Test void glancingWallHitsAndFloorContactAreHarmless() {
        World world = new World();
        world.obstacles.add(new AABB(-20, 60, .8, 20, 80, 2)); // wall touching the body's +Z side
        Motion along = new Motion(1.4, -.15, .05);
        ServerGlide.Tick glancing = ServerGlide.tick(world.glide(along, 0, -88), BODY);
        assertTrue(glancing.horizontalCollision());
        assertEquals(0, glancing.damage(), "zeroing a small sideways axis keeps nearly all speed");

        World floor = new World();
        floor.obstacles.add(new AABB(-20, 64, -20, 20, 65, 20));
        ServerGlide.Tick landing = ServerGlide.tick(floor.glide(new Motion(1.4, -.15, 0)), BODY);
        assertFalse(landing.horizontalCollision());
        assertEquals(0, landing.damage());
        assertEquals(0, landing.velocity().y());
    }

    @Test void clearanceLeavesHarmlessOrDistantGlidesUntouched() {
        World world = new World().wall(.85);
        Motion crawl = new Motion(.1, 0, 0);
        ServerGlide.Clearance slow = ServerGlide.clear(world.glide(new Motion(.25, -.1, 0)), BODY, crawl, world);
        assertFalse(slow.limited(), "tight-space contact stays available while the server glide is harmless");
        assertSame(crawl, slow.motion());

        World open = new World().wall(30);
        ServerGlide.Clearance far = ServerGlide.clear(open.glide(new Motion(1.5, -.15, 0)), BODY,
            new Motion(2, 0, 0), open);
        assertFalse(far.limited());
    }

    @Test void clearanceStopsTheReportedPositionOutsideTheServerReach() {
        World world = new World().wall(2);
        ServerGlide.Context glide = world.glide(new Motion(1, -.15, 0));
        ServerGlide.Clearance clearance = ServerGlide.clear(glide, BODY, new Motion(1, 0, 0), world);
        assertTrue(clearance.limited());
        assertEquals(0, clearance.damage());
        assertTrue(clearance.motion().x() > 0 && clearance.motion().x() < 1);
        AABB reported = BODY.move(clearance.motion().x(), 0, 0);
        assertEquals(0, ServerGlide.tick(glide, reported).damage());
        double gap = world.wallX - reported.maxX;
        Motion next = ServerGlide.glide(new Motion(1, -.15, 0), 0, EAST, .08);
        assertTrue(gap >= next.x(), "gap " + gap + " must cover the server's next move " + next.x());
        assertTrue(gap < next.x() * ServerGlide.REACH_SCALE + ServerGlide.REACH_PADDING + .01,
            "and should not stop much earlier than needed");
    }

    @Test void clearanceStepsBackWhenTheServerGlideAlreadyReachesTheWall() {
        World world = new World().wall(.5);
        ServerGlide.Context glide = world.glide(new Motion(1, -.15, 0));
        assertTrue(ServerGlide.tick(glide, BODY).damage() > 0, "waiting here would hurt");
        ServerGlide.Clearance clearance = ServerGlide.clear(glide, BODY, Motion.ZERO, world);
        assertTrue(clearance.limited());
        assertEquals(0, clearance.damage());
        assertTrue(clearance.motion().x() < 0, "retreat against the glide heading");
        assertEquals(0, clearance.motion().y());
        assertEquals(0, ServerGlide.tick(glide, BODY.move(clearance.motion().x(), 0, 0)).damage());
    }

    @Test void boxedInPositionsKeepTheRequestedMoveAndReportTheUnavoidableDamage() {
        World world = new World().wall(.5);
        world.obstacles.add(new AABB(-1.5, 60, -20, .2, 80, 20)); // touching the body's back
        ServerGlide.Context glide = world.glide(new Motion(1, -.15, 0));
        Motion requested = new Motion(.3, 0, .2);
        ServerGlide.Clearance clearance = ServerGlide.clear(glide, BODY, requested, world);
        assertFalse(clearance.limited(), "holding still cannot soften a hit no reachable position avoids");
        assertSame(requested, clearance.motion());
        assertTrue(clearance.damage() > 0);
    }

    @Test void unloadedTerrainInsideTheServerReachCountsAsAWall() {
        World world = new World((x, z) -> x == 0);
        ServerGlide.Context glide = world.glide(new Motion(1.2, -.15, 0));
        ServerGlide.Clearance clearance = ServerGlide.clear(glide, BODY.move(14, 0, 0), new Motion(.5, 0, 0), world);
        assertTrue(clearance.limited());
        assertTrue(14.8 + clearance.motion().x() + 1.2 <= 16 + .1,
            "the client cannot see blocks the server may hold in the missing chunk");
    }

    @Test void attachedRocketsWidenTheReach() {
        World world = new World().wall(.9);
        Motion slow = new Motion(.5, -.15, 0);
        assertEquals(0, ServerGlide.tick(world.glide(slow), BODY).damage());
        ServerGlide.Context boosted = new ServerGlide.Context(slow, 1, 0, EAST, .08, false, world);
        assertTrue(ServerGlide.tick(boosted, BODY).damage() > 0);
        assertTrue(ServerGlide.clear(boosted, BODY, Motion.ZERO, world).motion().x() < 0);
    }

    @Test void rejectsInvalidContexts() {
        World world = new World();
        assertThrows(IllegalArgumentException.class,
            () -> new ServerGlide.Context(Motion.ZERO, -1, 0, 0, .08, false, world));
        assertThrows(IllegalArgumentException.class,
            () -> new ServerGlide.Context(Motion.ZERO, 0, Float.NaN, 0, .08, false, world));
        assertThrows(IllegalArgumentException.class,
            () -> new ServerGlide.Context(Motion.ZERO, 0, 0, 0, Double.NaN, false, world));
        assertDoesNotThrow(() -> new ServerGlide.Context(Motion.ZERO, 0, 0, 0, -1, false, world),
            "the gravity attribute may be negative");
        assertThrows(NullPointerException.class,
            () -> new ServerGlide.Context(Motion.ZERO, 0, 0, 0, .08, false, null));
    }

    // The production pipeline flying at a wall, with a vanilla server ticking its own glide alongside.

    @Test void stoppingAtTheTerrainMarginLetsTheServerGlideHitTheWall() {
        Flight flight = new Flight(false, glideFromRest(40, 0), 10, new Motion(2.06, 0, 0));
        flight.fly(120);
        assertTrue(flight.worstDamage >= .5, "0.23.6 stops 0.35 blocks short, inside the hidden glide's reach");
    }

    @Test void serverGlideGuardPreventsWallDamageWhileHoldingIntoTheWall() {
        Flight flight = new Flight(true, glideFromRest(40, 0), 10, new Motion(2.06, 0, 0));
        flight.fly(600);
        assertEquals(0, flight.worstDamage, "no tick may let the server's glide hit the wall");
        assertTrue(flight.glideLimitedTicks > 0);
        assertTrue(flight.serverGlide.horizontalLength() > 1.2, "the hidden glide kept growing all along");
        double gap = flight.world.wallX - flight.box.maxX;
        assertTrue(gap > 1.2 && gap < 1.8, "held just outside its reach: " + gap);
    }

    @Test void brakingAllowsForTheServerGlideSoTheStopStaysSmooth() {
        Flight flight = new Flight(true, glideFromRest(400, 0), 30, new Motion(2.06, 0, 0));
        double previous = 2.06;
        for (int tick = 0; tick < 80; tick++) {
            flight.fly(1);
            double speed = flight.lastMotion.length();
            // Only the low-speed crawl that follows the stop may be held back by the guard.
            assertFalse(flight.lastGlideLimited && previous > AdaptiveFlightStep.SAFE_MANEUVER_SPEED,
                "the guard cut a " + previous + " b/t approach short at tick " + tick);
            if (tick > 2) assertTrue(previous - speed <= .3 + 1e-9, "braking stays within its rate");
            previous = speed;
        }
        assertEquals(0, flight.worstDamage);
        assertTrue(previous < .01);
        double gap = flight.world.wallX - flight.box.maxX;
        assertTrue(gap > 1.5 && gap < 2.2, "stopped outside the 1.5-block glide reach: " + gap);
    }

    @Test void harmlessServerGlideStillAllowsWallContactForTightSpaces() {
        // Shortly after takeoff the hidden glide is still slow; a harmless contact then keeps zeroing it.
        Flight flight = new Flight(true, new Motion(.1, -.05, 0), 1, Motion.ZERO);
        flight.fly(200);
        assertEquals(0, flight.worstDamage);
        assertTrue(flight.world.wallX - flight.box.maxX < .01, "harmless contact reaches the wall");
        assertTrue(flight.serverGlide.horizontalLength() <= ServerGlide.HARMLESS_SPEED);
    }

    private static Motion glideFromRest(int ticks, float pitch) {
        Motion velocity = Motion.ZERO;
        for (int tick = 0; tick < ticks; tick++) {
            velocity = ServerGlide.settle(velocity);
            velocity = ServerGlide.glide(velocity, pitch, EAST, .08);
        }
        return velocity;
    }

    /** Module defaults at cruise, holding Forward toward a wall ahead. */
    private static final class Flight {
        final World world;
        final boolean guard;
        final AdaptiveFlightController controller =
            new AdaptiveFlightController(new Settings(.15, .3, .75, 5, 60, .0025, 15));
        AABB box = BODY;
        Motion serverGlide;
        Motion lastMotion = Motion.ZERO;
        boolean lastGlideLimited;
        double worstDamage;
        int glideLimitedTicks;

        Flight(boolean guard, Motion serverGlide, double gap, Motion output) {
            this.guard = guard;
            this.serverGlide = serverGlide;
            world = new World().wall(gap);
            controller.accept(output);
        }

        void fly(int ticks) {
            for (int tick = 0; tick < ticks; tick++) {
                controller.tick();
                boolean release = AdaptiveFlightStep.harmless(controller.output());
                ServerGlide.Context glide = world.glide(serverGlide);
                var step = AdaptiveFlightStep.move(controller, new Motion(1, 0, 0), 1, .5, box, world, 64, .35,
                    new Controls(false, .35, 0, release, guard ? glide : null));
                if (step.glideLimited()) glideLimitedTicks++;
                lastGlideLimited = step.glideLimited();
                lastMotion = step.motion();
                Motion moved = world.collide(box, step.motion());
                assertFalse(world.overlaps(box.move(moved.x(), moved.y(), moved.z())));
                box = box.move(moved.x(), moved.y(), moved.z());
                ServerGlide.Tick server = ServerGlide.tick(glide, box);
                worstDamage = Math.max(worstDamage, server.damage());
                serverGlide = server.velocity();
            }
        }
    }

    /** Axis-separated AABB collision in Minecraft's order, plus a chunk-loading predicate. */
    static final class World implements FlightCorridor.Probe, ServerGlide.Collider {
        private static final double SHAPE_EPSILON = 1e-7;
        final List<AABB> obstacles = new ArrayList<>();
        private final BiPredicate<Integer, Integer> loaded;
        double wallX = Double.POSITIVE_INFINITY;

        World() { this((x, z) -> true); }
        World(BiPredicate<Integer, Integer> loaded) { this.loaded = loaded; }

        /** A wall whose face is this far ahead of the test body's +X side. */
        World wall(double gap) {
            wallX = BODY.maxX + gap;
            obstacles.add(new AABB(wallX, 60, -20, wallX + 1, 80, 20));
            return this;
        }

        ServerGlide.Context glide(Motion velocity) { return glide(velocity, 0, EAST); }

        ServerGlide.Context glide(Motion velocity, float pitch, float yaw) {
            return new ServerGlide.Context(velocity, 0, pitch, yaw, .08, false, this);
        }

        boolean overlaps(AABB box) {
            return obstacles.stream().anyMatch(o -> o.deflate(SHAPE_EPSILON).intersects(box));
        }

        @Override public boolean loaded(int chunkX, int chunkZ) { return loaded.test(chunkX, chunkZ); }
        @Override public boolean clear(AABB swept) { return !overlaps(swept); }

        @Override public Motion collide(AABB box, Motion movement) {
            double y = clip(box, 1, movement.y());
            box = box.move(0, y, 0);
            double x;
            double z;
            if (Math.abs(movement.x()) < Math.abs(movement.z())) {
                z = clip(box, 2, movement.z());
                box = box.move(0, 0, z);
                x = clip(box, 0, movement.x());
            } else {
                x = clip(box, 0, movement.x());
                box = box.move(x, 0, 0);
                z = clip(box, 2, movement.z());
            }
            return new Motion(x, y, z);
        }

        private double clip(AABB box, int axis, double distance) {
            for (AABB obstacle : obstacles) {
                if (!overlapsExcept(box, obstacle, axis)) continue;
                if (distance > 0 && max(box, axis) - SHAPE_EPSILON <= min(obstacle, axis)) {
                    distance = Math.min(distance, min(obstacle, axis) - max(box, axis));
                } else if (distance < 0 && min(box, axis) + SHAPE_EPSILON >= max(obstacle, axis)) {
                    distance = Math.max(distance, max(obstacle, axis) - min(box, axis));
                }
            }
            return Math.abs(distance) < SHAPE_EPSILON ? 0 : distance;
        }

        private static boolean overlapsExcept(AABB first, AABB second, int skipped) {
            for (int axis = 0; axis < 3; axis++) {
                if (axis == skipped) continue;
                if (max(first, axis) - SHAPE_EPSILON <= min(second, axis)
                    || min(first, axis) + SHAPE_EPSILON >= max(second, axis)) return false;
            }
            return true;
        }

        private static double min(AABB box, int axis) {
            return axis == 0 ? box.minX : axis == 1 ? box.minY : box.minZ;
        }

        private static double max(AABB box, int axis) {
            return axis == 0 ? box.maxX : axis == 1 ? box.maxY : box.maxZ;
        }
    }
}
