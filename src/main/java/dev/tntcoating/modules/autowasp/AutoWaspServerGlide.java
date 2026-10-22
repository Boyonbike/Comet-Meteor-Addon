/*
 * Part of Comet's Auto Wasp port; see AutoWasp.java and THIRD_PARTY_NOTICES.md for DEVILS provenance.
 * Written for Comet by Boyonabike on 2026-10-22, independent of Adaptive Elytra Fly's classes.
 * SPDX-License-Identifier: GPL-3.0-only
 */

package dev.tntcoating.modules.autowasp;

import java.util.Objects;
import net.minecraft.util.Mth;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

/**
 * The elytra tick a vanilla server runs for a client-moved player. The server keeps simulating an unmodified glide
 * velocity, moves it from the reported position once per tick, then restores that position. A horizontal hit in that
 * hidden move costs {@code lost horizontal speed * 10 - 3} health however slowly Auto Wasp itself moved, so walls
 * must stay outside its reach.
 *
 * Mirrors FireworkRocketEntity.tick, LivingEntity.aiStep's low-speed zeroing, travelFallFlying,
 * updateFallFlyingMovement and handleFallFlyingCollisions for players.
 */
public final class AutoWaspServerGlide {
    /** A wall stopping no more horizontal speed than this deals no damage; exactly .3 still makes a hurt flash. */
    public static final double HARMLESS_SPEED = 0.3 - 1e-7;
    // The server may run its tick from a packet early or late, and its glide keeps growing meanwhile.
    static final double REACH_SCALE = 1.05;
    static final double REACH_PADDING = 0.03;
    static final int SEARCH_STEPS = 10;
    static final int STEADY_TICKS = 1200;
    // Steering starts while a wall is within this many settled glide reaches, so the hidden glide has
    // turned away by the time the guard would otherwise hold pursuit back from the wall.
    static final double STEER_LOOKAHEAD = 2;
    // A sweep over more chunk columns than this is treated as unloaded rather than checked.
    static final int MAX_CHUNKS = 16;
    // Level headings to try, relative to the camera's: straight back first, then ever closer to the camera's.
    private static final float[] STEER_TURNS = {180, 135, -135, 90, -90, 45, -45};
    private static final double HORIZONTAL_DRAG = 0.99F;
    private static final double VERTICAL_DRAG = 0.98F;

    private AutoWaspServerGlide() {}

    public interface Collider {
        /** The part of this movement Minecraft allows the box, without step-up. */
        Vec3 collide(AABB box, Vec3 movement);
    }

    public interface Chunks {
        /** A non-loading lookup: implementations must never request or generate a chunk. */
        boolean loaded(int chunkX, int chunkZ);
    }

    /**
     * The server's glide velocity before its next travel tick, the rotation it will use, and the client's view of
     * block collisions. Attached rockets boost the velocity before the glide.
     */
    public record Context(Vec3 velocity, int rockets, float pitch, float yaw,
                          double gravity, boolean slowFalling, Collider collider) {
        public Context {
            Objects.requireNonNull(velocity, "velocity");
            Objects.requireNonNull(collider, "collider");
            if (!finite(velocity)) throw new IllegalArgumentException("Velocity must be finite.");
            if (rockets < 0) throw new IllegalArgumentException("Rocket count cannot be negative.");
            if (!Float.isFinite(pitch) || !Float.isFinite(yaw)) {
                throw new IllegalArgumentException("Rotation must be finite.");
            }
            // The gravity attribute may be negative.
            if (!Double.isFinite(gravity)) throw new IllegalArgumentException("Gravity must be finite.");
        }
    }

    /** The velocity the server keeps after its tick, and the wall damage that tick dealt. */
    public record Tick(Vec3 velocity, double damage, boolean horizontalCollision) {}

    /** The movement to use instead, and the damage that could not be avoided. */
    public record Clearance(Vec3 motion, boolean limited, double damage) {}

    /** A rotation to report to the server in place of the camera's. */
    public record Rotation(float pitch, float yaw) {}

    /** Vanilla fly-into-wall damage for one horizontal collision. */
    public static double damage(double speedBefore, double speedAfter) {
        float damage = (float) ((speedBefore - speedAfter) * 10 - 3);
        return damage > 0 ? damage : 0;
    }

    /** LivingEntity.updateFallFlyingMovement, using float angles as Minecraft does. */
    public static Vec3 glide(Vec3 velocity, float pitch, float yaw, double gravity) {
        Vec3 look = look(pitch, yaw);
        float radians = pitch * 0.017453292F;
        double lookHorizontal = Math.sqrt(look.x * look.x + look.z * look.z);
        double speed = velocity.horizontalDistance();
        double lift = Math.cos(radians) * Math.cos(radians);
        double x = velocity.x;
        double y = velocity.y + gravity * (-1 + lift * 0.75);
        double z = velocity.z;
        if (y < 0 && lookHorizontal > 0) {
            double gain = y * -0.1 * lift;
            x += look.x * gain / lookHorizontal;
            y += gain;
            z += look.z * gain / lookHorizontal;
        }
        if (radians < 0 && lookHorizontal > 0) {
            double climb = speed * -(float) Math.sin(radians) * 0.04;
            x -= look.x * climb / lookHorizontal;
            y += climb * 3.2;
            z -= look.z * climb / lookHorizontal;
        }
        if (lookHorizontal > 0) {
            x += (look.x / lookHorizontal * speed - x) * 0.1;
            z += (look.z / lookHorizontal * speed - z) * 0.1;
        }
        return new Vec3(x * HORIZONTAL_DRAG, y * VERTICAL_DRAG, z * HORIZONTAL_DRAG);
    }

    /** One attached firework rocket's boost to a gliding player. */
    public static Vec3 rocket(Vec3 velocity, float pitch, float yaw) {
        Vec3 look = look(pitch, yaw);
        return new Vec3(
            velocity.x + look.x * 0.1 + (look.x * 1.5 - velocity.x) * 0.5,
            velocity.y + look.y * 0.1 + (look.y * 1.5 - velocity.y) * 0.5,
            velocity.z + look.z * 0.1 + (look.z * 1.5 - velocity.z) * 0.5);
    }

    /** The velocity a long unboosted glide settles at for this rotation, for flights begun unseen. */
    public static Vec3 steady(float pitch, float yaw, double gravity, boolean slowFalling) {
        Vec3 velocity = Vec3.ZERO;
        for (int tick = 0; tick < STEADY_TICKS; tick++) {
            velocity = settle(velocity);
            velocity = glide(velocity, pitch, yaw, gravity(gravity, slowFalling, velocity));
        }
        return velocity;
    }

    /** The server's travel tick from the reported box: boosts, glide, collision, restitution and damage. */
    public static Tick tick(Context glide, AABB reported) {
        return tick(glide, reported, false);
    }

    /** The server's next glide move, including the timing allowance, or zero when no wall hit could hurt. */
    public static Vec3 reach(Context glide) {
        Objects.requireNonNull(glide, "glide");
        Vec3 start = start(glide);
        if (start.horizontalDistance() <= HARMLESS_SPEED) return Vec3.ZERO;
        return allowance(glide(start, glide.pitch(), glide.yaw(), gravity(glide, start)));
    }

    /**
     * Shortens this tick's movement, or if the current position is already inside the hidden glide's reach steps
     * back from it, so the server's own tick cannot hit a wall at damaging speed. Unloaded chunks in either path count
     * as walls. A harmless glide never changes the movement, and when no reachable position is safe the movement is
     * kept and the unavoidable damage reported.
     */
    public static Clearance clear(Context glide, AABB box, Vec3 movement, Chunks chunks) {
        Objects.requireNonNull(glide, "glide");
        Objects.requireNonNull(box, "box");
        Objects.requireNonNull(movement, "movement");
        Vec3 start = start(glide);
        if (start.horizontalDistance() <= HARMLESS_SPEED) return new Clearance(movement, false, 0);
        double moving = risk(glide, box, movement, chunks);
        if (moving == 0) return new Clearance(movement, false, 0);

        double idle = risk(glide, box, Vec3.ZERO, chunks);
        if (idle == 0) {
            double safe = 0;
            double unsafe = 1;
            for (int step = 0; step < SEARCH_STEPS; step++) {
                double middle = (safe + unsafe) * 0.5;
                if (risk(glide, box, movement.scale(middle), chunks) == 0) safe = middle;
                else unsafe = middle;
            }
            return new Clearance(movement.scale(safe), true, 0);
        }

        // Waiting here lets the server's glide reach the wall, so retreat against its heading.
        // If nowhere this tick is out of reach, the hit is unavoidable and freezing would not soften it.
        Vec3 next = glide(start, glide.pitch(), glide.yaw(), gravity(glide, start));
        double horizontal = next.horizontalDistance();
        if (horizontal == 0) return new Clearance(movement, false, moving);
        Vec3 back = new Vec3(-next.x / horizontal, 0, -next.z / horizontal);
        double far = horizontal * REACH_SCALE + REACH_PADDING;
        if (risk(glide, box, back.scale(far), chunks) > 0) return new Clearance(movement, false, moving);
        double unsafe = 0;
        double safe = far;
        for (int step = 0; step < SEARCH_STEPS; step++) {
            double middle = (safe + unsafe) * 0.5;
            if (risk(glide, box, back.scale(middle), chunks) == 0) safe = middle;
            else unsafe = middle;
        }
        return new Clearance(back.scale(safe), true, 0);
    }

    /**
     * The server's glide turns a tenth of the way towards the reported heading each tick and settles at a steady
     * speed along it. While that glide facing the camera, or the current one, could reach a wall from here, this is a
     * level heading, as near straight back as possible, whose settled glide cannot: reporting it turns the hidden
     * glide away, so the guard need not hold the reported position back from the wall. Null when the camera's
     * heading is safe, or when no heading is.
     */
    public static Rotation steer(Context camera, AABB box, Chunks chunks) {
        Objects.requireNonNull(camera, "camera");
        Objects.requireNonNull(box, "box");
        Vec3 level = steady(0, 0, camera.gravity(), camera.slowFalling());
        double speed = level.horizontalDistance();
        if (risk(camera, box, Vec3.ZERO, chunks) == 0 && risk(heading(camera, level.y,
            speed * STEER_LOOKAHEAD, camera.pitch(), camera.yaw()), box, Vec3.ZERO, chunks) == 0) return null;
        for (float turn : STEER_TURNS) {
            float yaw = Mth.wrapDegrees(camera.yaw() + turn);
            if (risk(heading(camera, level.y, speed, 0, yaw), box, Vec3.ZERO, chunks) == 0) {
                return new Rotation(0, yaw);
            }
        }
        return null;
    }

    /** Whether every chunk column the box covers while making this movement is loaded. */
    static boolean loadedAlong(AABB box, Vec3 movement, Chunks chunks) {
        AABB swept = box.expandTowards(movement);
        if (!finite(new Vec3(swept.minX, swept.minY, swept.minZ)) || !finite(new Vec3(swept.maxX, swept.maxY, swept.maxZ))) {
            return false;
        }
        int minX = Mth.floor(swept.minX / 16);
        int minZ = Mth.floor(swept.minZ / 16);
        // AABB maxima are exclusive.
        int maxX = Mth.floor(Math.nextDown(swept.maxX / 16));
        int maxZ = Mth.floor(Math.nextDown(swept.maxZ / 16));
        if (maxX < minX || maxZ < minZ || ((long) maxX - minX + 1) * ((long) maxZ - minZ + 1) > MAX_CHUNKS) return false;
        for (int x = minX; x <= maxX; x++) {
            for (int z = minZ; z <= maxZ; z++) {
                if (!chunks.loaded(x, z)) return false;
            }
        }
        return true;
    }

    /** This context gliding along the yaw at the given horizontal speed, facing the given rotation. */
    private static Context heading(Context glide, double y, double speed, float pitch, float yaw) {
        Vec3 look = look(0, yaw);
        return new Context(new Vec3(look.x * speed, y, look.z * speed), glide.rockets(), pitch, yaw,
            glide.gravity(), glide.slowFalling(), glide.collider());
    }

    private static double risk(Context glide, AABB box, Vec3 movement, Chunks chunks) {
        Vec3 moved = glide.collider().collide(box, movement);
        Vec3 start = start(glide);
        double worst = damage(start.horizontalDistance(), 0);
        if (chunks != null && moved.length() > 0 && !loadedAlong(box, moved, chunks)) return worst;
        AABB reported = box.move(moved.x, moved.y, moved.z);
        Tick tick = tick(glide, reported, true);
        if (tick.damage() > 0) return tick.damage();
        if (chunks != null) {
            // The client cannot see blocks the server may hold in a missing chunk.
            Vec3 reach = reach(glide);
            if (!loadedAlong(reported, reach, chunks)) return worst;
        }
        return 0;
    }

    private static Tick tick(Context glide, AABB reported, boolean padded) {
        Vec3 velocity = start(glide);
        double before = velocity.horizontalDistance();
        Vec3 next = glide(velocity, glide.pitch(), glide.yaw(), gravity(glide, velocity));
        Vec3 probe = padded ? allowance(next) : next;
        Vec3 moved = glide.collider().collide(reported, probe);
        boolean x = !same(probe.x, moved.x);
        boolean z = !same(probe.z, moved.z);
        boolean vertical = probe.y != moved.y;
        // Players do not bounce, so a blocked axis keeps no velocity.
        Vec3 after = new Vec3(x ? 0 : next.x, vertical ? 0 : next.y, z ? 0 : next.z);
        return new Tick(after, x || z ? damage(before, after.horizontalDistance()) : 0, x || z);
    }

    private static Vec3 start(Context glide) {
        Vec3 velocity = glide.velocity();
        for (int rocket = 0; rocket < glide.rockets(); rocket++) {
            velocity = rocket(velocity, glide.pitch(), glide.yaw());
        }
        return settle(velocity);
    }

    /** LivingEntity.aiStep zeroes negligible player velocity before travel. */
    static Vec3 settle(Vec3 velocity) {
        double x = velocity.x;
        double y = velocity.y;
        double z = velocity.z;
        if (x * x + z * z < 9.0E-6) {
            x = 0;
            z = 0;
        }
        if (Math.abs(y) < 0.003) y = 0;
        return new Vec3(x, y, z);
    }

    /** Entity.calculateViewVector. */
    static Vec3 look(float pitch, float yaw) {
        float pitchRadians = pitch * 0.017453292F;
        float yawRadians = -yaw * 0.017453292F;
        float yawCos = (float) Math.cos(yawRadians);
        float yawSin = (float) Math.sin(yawRadians);
        float pitchCos = (float) Math.cos(pitchRadians);
        float pitchSin = (float) Math.sin(pitchRadians);
        return new Vec3(yawSin * pitchCos, -pitchSin, yawCos * pitchCos);
    }

    private static double gravity(Context glide, Vec3 velocity) {
        return gravity(glide.gravity(), glide.slowFalling(), velocity);
    }

    private static double gravity(double gravity, boolean slowFalling, Vec3 velocity) {
        return slowFalling && velocity.y <= 0 ? Math.min(gravity, 0.01) : gravity;
    }

    private static Vec3 allowance(Vec3 next) {
        double horizontal = next.horizontalDistance();
        if (horizontal == 0) return next;
        double factor = (horizontal * REACH_SCALE + REACH_PADDING) / horizontal;
        return new Vec3(next.x * factor, next.y, next.z * factor);
    }

    private static boolean same(double first, double second) {
        return Math.abs(second - first) < 1.0E-5F;
    }

    private static boolean finite(Vec3 vec) {
        return Double.isFinite(vec.x) && Double.isFinite(vec.y) && Double.isFinite(vec.z);
    }
}
