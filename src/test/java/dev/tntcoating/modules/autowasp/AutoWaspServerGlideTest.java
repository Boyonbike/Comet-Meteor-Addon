package dev.tntcoating.modules.autowasp;

import java.util.ArrayList;
import java.util.List;
import java.util.function.BiPredicate;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class AutoWaspServerGlideTest {
    private static final float EAST = -90; // Minecraft yaw facing +X
    private static final AABB BODY = new AABB(.2, 65, .2, .8, 66.8, .8);

    @Test void glideMatchesVanillaLevelAndClimbingSpeeds() {
        Vec3 level = glideFromRest(40, 0);
        assertEquals(.42, level.horizontalDistance(), .01, "two seconds of level gliding");
        assertEquals(-.15, level.y, .01);
        assertTrue(level.x > 0 && Math.abs(level.z) < 1e-6, "glide follows the look direction");
        assertEquals(1.48, glideFromRest(400, 0).horizontalDistance(), .01);
        assertEquals(.37, glideFromRest(400, -20).horizontalDistance(), .01, "looking up bleeds horizontal speed");
        assertEquals(1.5, AutoWaspServerGlide.steady(0, EAST, .08, false).horizontalDistance(), .02);
    }

    @Test void rocketBoostMatchesFireworkRocketEntity() {
        Vec3 boosted = AutoWaspServerGlide.rocket(new Vec3(.2, -.1, 0), 0, EAST);
        assertEquals(.2 + .1 + (1.5 - .2) * .5, boosted.x, 1e-6);
        assertEquals(-.1 + (0 + .1) * .5, boosted.y, 1e-6);
        assertEquals(0, boosted.z, 1e-6);
    }

    @Test void damageMatchesTheVanillaThreshold() {
        assertEquals(0, AutoWaspServerGlide.damage(.3, 0));
        assertEquals(0, AutoWaspServerGlide.damage(AutoWaspServerGlide.HARMLESS_SPEED, 0));
        assertEquals(1, AutoWaspServerGlide.damage(.4, 0), "half a heart");
        assertEquals(12, AutoWaspServerGlide.damage(1.5, 0));
    }

    @Test void serverTickHitsAWallInsideItsReachEvenWhenTheClientIsStill() {
        World world = new World().wall(.35);
        AutoWaspServerGlide.Tick tick = AutoWaspServerGlide.tick(world.glide(new Vec3(.42, -.15, 0)), BODY);
        assertTrue(tick.horizontalCollision());
        assertEquals(0, tick.velocity().x, "the blocked axis keeps no velocity");
        assertTrue(tick.damage() >= .9 && tick.damage() <= 1.3, "damage " + tick.damage());

        AutoWaspServerGlide.Tick clear = AutoWaspServerGlide.tick(world.glide(new Vec3(.42, -.15, 0)), BODY.move(-.5, 0, 0));
        assertFalse(clear.horizontalCollision());
        assertEquals(0, clear.damage());
    }

    @Test void clearanceLeavesHarmlessOrDistantGlidesUntouched() {
        World world = new World().wall(.85);
        Vec3 crawl = new Vec3(.1, 0, 0);
        AutoWaspServerGlide.Clearance slow = AutoWaspServerGlide.clear(world.glide(new Vec3(.25, -.1, 0)), BODY, crawl, world);
        assertFalse(slow.limited(), "tight-space contact stays available while the server glide is harmless");
        assertSame(crawl, slow.motion());

        World open = new World().wall(30);
        assertFalse(AutoWaspServerGlide.clear(open.glide(new Vec3(1.5, -.15, 0)), BODY, new Vec3(2, 0, 0), open).limited());
    }

    @Test void clearanceStopsTheReportedPositionOutsideTheServerReach() {
        World world = new World().wall(2);
        AutoWaspServerGlide.Context glide = world.glide(new Vec3(1, -.15, 0));
        AutoWaspServerGlide.Clearance clearance = AutoWaspServerGlide.clear(glide, BODY, new Vec3(1, 0, 0), world);
        assertTrue(clearance.limited());
        assertEquals(0, clearance.damage());
        assertTrue(clearance.motion().x > 0 && clearance.motion().x < 1);
        AABB reported = BODY.move(clearance.motion().x, 0, 0);
        assertEquals(0, AutoWaspServerGlide.tick(glide, reported).damage());
        double gap = world.wallX - reported.maxX;
        Vec3 next = AutoWaspServerGlide.glide(new Vec3(1, -.15, 0), 0, EAST, .08);
        assertTrue(gap >= next.x, "gap " + gap + " must cover the server's next move " + next.x);
        assertTrue(gap < next.x * AutoWaspServerGlide.REACH_SCALE + AutoWaspServerGlide.REACH_PADDING + .01,
            "and should not stop much earlier than needed");
    }

    @Test void clearanceStepsBackWhenTheServerGlideAlreadyReachesTheWall() {
        World world = new World().wall(.5);
        AutoWaspServerGlide.Context glide = world.glide(new Vec3(1, -.15, 0));
        assertTrue(AutoWaspServerGlide.tick(glide, BODY).damage() > 0, "waiting here would hurt");
        AutoWaspServerGlide.Clearance clearance = AutoWaspServerGlide.clear(glide, BODY, Vec3.ZERO, world);
        assertTrue(clearance.limited());
        assertTrue(clearance.motion().x < 0, "retreat against the glide heading");
        assertEquals(0, AutoWaspServerGlide.tick(glide, BODY.move(clearance.motion().x, 0, 0)).damage());
    }

    @Test void boxedInPositionsKeepTheRequestedMoveAndReportTheUnavoidableDamage() {
        World world = new World().wall(.5);
        world.obstacles.add(new AABB(-1.5, 60, -20, .2, 80, 20)); // touching the body's back
        Vec3 requested = new Vec3(.3, 0, .2);
        AutoWaspServerGlide.Clearance clearance = AutoWaspServerGlide.clear(world.glide(new Vec3(1, -.15, 0)), BODY,
            requested, world);
        assertFalse(clearance.limited());
        assertSame(requested, clearance.motion());
        assertTrue(clearance.damage() > 0);
    }

    @Test void unloadedTerrainInsideTheServerReachCountsAsAWall() {
        World world = new World((x, z) -> x == 0);
        AutoWaspServerGlide.Context glide = world.glide(new Vec3(1.2, -.15, 0));
        AutoWaspServerGlide.Clearance clearance = AutoWaspServerGlide.clear(glide, BODY.move(14, 0, 0),
            new Vec3(.5, 0, 0), world);
        assertTrue(clearance.limited());
        assertTrue(14.8 + clearance.motion().x + 1.2 <= 16 + .1,
            "the client cannot see blocks the server may hold in the missing chunk");
    }

    @Test void chunkSweepsCoverEveryColumnAndRefuseHugeOnes() {
        World world = new World((x, z) -> x == 0 && z == 0);
        assertTrue(AutoWaspServerGlide.loadedAlong(BODY, new Vec3(10, 0, 10), world));
        assertFalse(AutoWaspServerGlide.loadedAlong(BODY, new Vec3(16, 0, 0), world));
        assertFalse(AutoWaspServerGlide.loadedAlong(BODY, new Vec3(0, 0, -1), world));
        World everywhere = new World();
        assertTrue(AutoWaspServerGlide.loadedAlong(BODY, new Vec3(100, 0, 0), everywhere));
        assertFalse(AutoWaspServerGlide.loadedAlong(BODY, new Vec3(300, 0, 0), everywhere), "too many columns to check");
    }

    @Test void attachedRocketsWidenTheReach() {
        World world = new World().wall(.9);
        Vec3 slow = new Vec3(.5, -.15, 0);
        assertEquals(0, AutoWaspServerGlide.tick(world.glide(slow), BODY).damage());
        AutoWaspServerGlide.Context boosted = new AutoWaspServerGlide.Context(slow, 1, 0, EAST, .08, false, world);
        assertTrue(AutoWaspServerGlide.tick(boosted, BODY).damage() > 0);
        assertTrue(AutoWaspServerGlide.clear(boosted, BODY, Vec3.ZERO, world).motion().x < 0);
    }

    @Test void steeringLeavesSafeHeadingsAlone() {
        World open = new World().wall(30);
        assertNull(AutoWaspServerGlide.steer(open.glide(new Vec3(1.5, -.15, 0)), BODY, open));
        World wall = new World().wall(1);
        assertNull(AutoWaspServerGlide.steer(wall.glide(new Vec3(-1.5, -.15, 0), 0, 90), BODY, wall),
            "facing and gliding away from the wall");
    }

    @Test void steeringReportsALevelHeadingStraightAwayFromAWallAhead() {
        World world = new World().wall(2.5);
        AutoWaspServerGlide.Context glide = world.glide(new Vec3(1.5, -.15, 0), 20, EAST);
        assertEquals(0, AutoWaspServerGlide.tick(glide, BODY).damage());
        AutoWaspServerGlide.Rotation steer = AutoWaspServerGlide.steer(glide, BODY, world);
        assertNotNull(steer);
        assertEquals(0, steer.pitch());
        assertEquals(90, steer.yaw(), 1e-4, "west, straight back from the wall");
    }

    @Test void steeringTurnsSidewaysWhenTheWayBackIsBlocked() {
        World world = new World().wall(1);
        world.obstacles.add(new AABB(-1.5, 60, -20, .2, 80, 20));
        AutoWaspServerGlide.Rotation steer = AutoWaspServerGlide.steer(world.glide(new Vec3(.1, -.15, 0)), BODY, world);
        assertNotNull(steer);
        assertEquals(0, steer.yaw(), 1e-4, "south, along both walls");
    }

    @Test void rejectsInvalidContexts() {
        World world = new World();
        assertThrows(IllegalArgumentException.class,
            () -> new AutoWaspServerGlide.Context(Vec3.ZERO, -1, 0, 0, .08, false, world));
        assertThrows(IllegalArgumentException.class,
            () -> new AutoWaspServerGlide.Context(Vec3.ZERO, 0, Float.NaN, 0, .08, false, world));
        assertThrows(IllegalArgumentException.class,
            () -> new AutoWaspServerGlide.Context(Vec3.ZERO, 0, 0, 0, Double.NaN, false, world));
        assertThrows(IllegalArgumentException.class,
            () -> new AutoWaspServerGlide.Context(new Vec3(Double.NaN, 0, 0), 0, 0, 0, .08, false, world));
        assertDoesNotThrow(() -> new AutoWaspServerGlide.Context(Vec3.ZERO, 0, 0, 0, -1, false, world));
        assertThrows(NullPointerException.class,
            () -> new AutoWaspServerGlide.Context(Vec3.ZERO, 0, 0, 0, .08, false, null));
    }

    // Pursuit flying straight at a wall, with a vanilla server ticking its own settled glide alongside.

    @Test void withoutTheGuardTheServerGlideHitsTheWallPursuitReaches() {
        Flight flight = new Flight(false, false);
        flight.fly(60);
        assertTrue(flight.worstDamage > 0);
    }

    @Test void theGuardHoldsPursuitOutsideTheServerReach() {
        Flight flight = new Flight(true, false);
        flight.fly(600);
        assertEquals(0, flight.worstDamage, "no tick may let the server's glide hit the wall");
        double gap = flight.world.wallX - flight.box.maxX;
        assertTrue(gap > 1.2 && gap < 1.9, "held just outside its reach: " + gap);
    }

    @Test void steeringLetsPursuitReachTheWallWithoutDamage() {
        Flight flight = new Flight(true, true);
        flight.fly(600);
        assertEquals(0, flight.worstDamage, "no tick may let the server's glide hit the wall");
        assertTrue(flight.steeredTicks > 0);
        assertTrue(flight.serverGlide.x < 0, "the hidden glide heads away from the wall");
        assertFalse(flight.retreated, "never pushed back from the wall");
        assertTrue(flight.world.wallX - flight.box.maxX < .01, "reaches the wall");
    }

    private static Vec3 glideFromRest(int ticks, float pitch) {
        Vec3 velocity = Vec3.ZERO;
        for (int tick = 0; tick < ticks; tick++) {
            velocity = AutoWaspServerGlide.settle(velocity);
            velocity = AutoWaspServerGlide.glide(velocity, pitch, EAST, .08);
        }
        return velocity;
    }

    /** Pursuit asking for one block per tick towards a wall ten blocks ahead, after a long glide facing it. */
    private static final class Flight {
        final World world = new World().wall(10);
        final boolean guard, steer;
        AABB box = BODY;
        Vec3 serverGlide = glideFromRest(400, 0);
        double worstDamage;
        int steeredTicks;
        boolean retreated;

        Flight(boolean guard, boolean steer) {
            this.guard = guard;
            this.steer = steer;
        }

        void fly(int ticks) {
            for (int tick = 0; tick < ticks; tick++) {
                AutoWaspServerGlide.Context glide = world.glide(serverGlide);
                AutoWaspServerGlide.Rotation heading = steer ? AutoWaspServerGlide.steer(glide, box, world) : null;
                if (heading != null) {
                    glide = world.glide(serverGlide, heading.pitch(), heading.yaw());
                    steeredTicks++;
                }
                Vec3 motion = new Vec3(1, 0, 0);
                if (guard) motion = AutoWaspServerGlide.clear(glide, box, motion, world).motion();
                if (motion.x < -1e-9) retreated = true;
                Vec3 moved = world.collide(box, motion);
                box = box.move(moved.x, moved.y, moved.z);
                AutoWaspServerGlide.Tick server = AutoWaspServerGlide.tick(glide, box);
                worstDamage = Math.max(worstDamage, server.damage());
                serverGlide = server.velocity();
            }
        }
    }

    /** Axis-separated AABB collision in Minecraft's order, plus a chunk-loading predicate. */
    static final class World implements AutoWaspServerGlide.Chunks, AutoWaspServerGlide.Collider {
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

        AutoWaspServerGlide.Context glide(Vec3 velocity) { return glide(velocity, 0, EAST); }

        AutoWaspServerGlide.Context glide(Vec3 velocity, float pitch, float yaw) {
            return new AutoWaspServerGlide.Context(velocity, 0, pitch, yaw, .08, false, this);
        }

        @Override public boolean loaded(int chunkX, int chunkZ) { return loaded.test(chunkX, chunkZ); }

        @Override public Vec3 collide(AABB box, Vec3 movement) {
            double y = clip(box, 1, movement.y);
            box = box.move(0, y, 0);
            double x;
            double z;
            if (Math.abs(movement.x) < Math.abs(movement.z)) {
                z = clip(box, 2, movement.z);
                box = box.move(0, 0, z);
                x = clip(box, 0, movement.x);
            } else {
                x = clip(box, 0, movement.x);
                box = box.move(x, 0, 0);
                z = clip(box, 2, movement.z);
            }
            return new Vec3(x, y, z);
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
