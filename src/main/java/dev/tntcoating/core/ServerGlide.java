package dev.tntcoating.core;

import dev.tntcoating.core.AdaptiveFlightController.Motion;
import java.util.Objects;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

/**
 * The elytra tick a vanilla server runs for a client-moved player. The server keeps simulating an
 * unmodified glide velocity, moves it from the reported position once per tick, then restores that
 * position. A horizontal hit in that hidden move costs {@code lost horizontal speed * 10 - 3} health
 * however slowly the client itself moved, so walls must stay outside its reach.
 *
 * Mirrors FireworkRocketEntity.tick, LivingEntity.aiStep's low-speed zeroing, travelFallFlying,
 * updateFallFlyingMovement and handleFallFlyingCollisions for players.
 */
public final class ServerGlide {
    /** A wall stopping no more horizontal speed than this deals no damage; exactly .3 still makes a hurt flash. */
    public static final double HARMLESS_SPEED = 0.3 - 1e-7;
    // The server may run its tick from a packet early or late, and its glide keeps growing meanwhile.
    static final double REACH_SCALE = 1.05;
    static final double REACH_PADDING = 0.03;
    static final int SEARCH_STEPS = 10;
    static final int STEADY_TICKS = 1200;
    private static final double HORIZONTAL_DRAG = 0.99F;
    private static final double VERTICAL_DRAG = 0.98F;

    private ServerGlide() {}

    public interface Collider {
        /** The part of this movement Minecraft allows the box, without step-up. */
        Motion collide(AABB box, Motion movement);
    }

    /**
     * The server's glide velocity before its next travel tick, the rotation it will use, and the
     * client's view of block collisions. Attached rockets boost the velocity before the glide.
     */
    public record Context(Motion velocity, int rockets, float pitch, float yaw,
                          double gravity, boolean slowFalling, Collider collider) {
        public Context {
            Objects.requireNonNull(velocity, "velocity");
            Objects.requireNonNull(collider, "collider");
            if (rockets < 0) throw new IllegalArgumentException("Rocket count cannot be negative.");
            if (!Float.isFinite(pitch) || !Float.isFinite(yaw)) {
                throw new IllegalArgumentException("Rotation must be finite.");
            }
            // The gravity attribute may be negative.
            if (!Double.isFinite(gravity)) throw new IllegalArgumentException("Gravity must be finite.");
        }
    }

    /** The velocity the server keeps after its tick, and the wall damage that tick dealt. */
    public record Tick(Motion velocity, double damage, boolean horizontalCollision) {}

    /** The movement to use instead, and the damage that could not be avoided. */
    public record Clearance(Motion motion, boolean limited, double damage) {}

    /** Vanilla fly-into-wall damage for one horizontal collision. */
    public static double damage(double speedBefore, double speedAfter) {
        float damage = (float) ((speedBefore - speedAfter) * 10 - 3);
        return damage > 0 ? damage : 0;
    }

    /** LivingEntity.updateFallFlyingMovement, using float angles as Minecraft does. */
    public static Motion glide(Motion velocity, float pitch, float yaw, double gravity) {
        Motion look = look(pitch, yaw);
        float radians = pitch * 0.017453292F;
        double lookHorizontal = Math.sqrt(look.x() * look.x() + look.z() * look.z());
        double speed = velocity.horizontalLength();
        double lift = Math.cos(radians) * Math.cos(radians);
        double x = velocity.x();
        double y = velocity.y() + gravity * (-1 + lift * 0.75);
        double z = velocity.z();
        if (y < 0 && lookHorizontal > 0) {
            double gain = y * -0.1 * lift;
            x += look.x() * gain / lookHorizontal;
            y += gain;
            z += look.z() * gain / lookHorizontal;
        }
        if (radians < 0 && lookHorizontal > 0) {
            double climb = speed * -(float) Math.sin(radians) * 0.04;
            x -= look.x() * climb / lookHorizontal;
            y += climb * 3.2;
            z -= look.z() * climb / lookHorizontal;
        }
        if (lookHorizontal > 0) {
            x += (look.x() / lookHorizontal * speed - x) * 0.1;
            z += (look.z() / lookHorizontal * speed - z) * 0.1;
        }
        return new Motion(x * HORIZONTAL_DRAG, y * VERTICAL_DRAG, z * HORIZONTAL_DRAG);
    }

    /** One attached firework rocket's boost to a gliding player. */
    public static Motion rocket(Motion velocity, float pitch, float yaw) {
        Motion look = look(pitch, yaw);
        return new Motion(
            velocity.x() + look.x() * 0.1 + (look.x() * 1.5 - velocity.x()) * 0.5,
            velocity.y() + look.y() * 0.1 + (look.y() * 1.5 - velocity.y()) * 0.5,
            velocity.z() + look.z() * 0.1 + (look.z() * 1.5 - velocity.z()) * 0.5);
    }

    /** The velocity a long unboosted glide settles at for this rotation, for flights begun unseen. */
    public static Motion steady(float pitch, float yaw, double gravity, boolean slowFalling) {
        Motion velocity = Motion.ZERO;
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
    public static Motion reach(Context glide) {
        Objects.requireNonNull(glide, "glide");
        Motion start = start(glide);
        if (start.horizontalLength() <= HARMLESS_SPEED) return Motion.ZERO;
        return allowance(glide(start, glide.pitch(), glide.yaw(), gravity(glide, start)));
    }

    /**
     * Shortens this tick's movement, or if the current position is already inside the hidden glide's
     * reach steps back from it, so the server's own tick cannot hit a wall at damaging speed. Unloaded
     * chunks in either path count as walls. A harmless glide never changes the movement, and when no
     * reachable position is safe the movement is kept and the unavoidable damage reported.
     */
    public static Clearance clear(Context glide, AABB box, Motion movement, FlightCorridor.Probe chunks) {
        Objects.requireNonNull(glide, "glide");
        Objects.requireNonNull(box, "box");
        Objects.requireNonNull(movement, "movement");
        Motion start = start(glide);
        if (start.horizontalLength() <= HARMLESS_SPEED) return new Clearance(movement, false, 0);
        double moving = risk(glide, box, movement, chunks);
        if (moving == 0) return new Clearance(movement, false, 0);

        double idle = risk(glide, box, Motion.ZERO, chunks);
        if (idle == 0) {
            double safe = 0;
            double unsafe = 1;
            for (int step = 0; step < SEARCH_STEPS; step++) {
                double middle = (safe + unsafe) * 0.5;
                if (risk(glide, box, scale(movement, middle), chunks) == 0) safe = middle;
                else unsafe = middle;
            }
            return new Clearance(scale(movement, safe), true, 0);
        }

        // Waiting here lets the server's glide reach the wall, so retreat against its heading.
        // If nowhere this tick is out of reach, the hit is unavoidable and freezing would not soften it.
        Motion next = glide(start, glide.pitch(), glide.yaw(), gravity(glide, start));
        double horizontal = next.horizontalLength();
        if (horizontal == 0) return new Clearance(movement, false, moving);
        Motion back = new Motion(-next.x() / horizontal, 0, -next.z() / horizontal);
        double far = horizontal * REACH_SCALE + REACH_PADDING;
        if (risk(glide, box, scale(back, far), chunks) > 0) return new Clearance(movement, false, moving);
        double unsafe = 0;
        double safe = far;
        for (int step = 0; step < SEARCH_STEPS; step++) {
            double middle = (safe + unsafe) * 0.5;
            if (risk(glide, box, scale(back, middle), chunks) == 0) safe = middle;
            else unsafe = middle;
        }
        return new Clearance(scale(back, safe), true, 0);
    }

    private static double risk(Context glide, AABB box, Motion movement, FlightCorridor.Probe chunks) {
        Motion moved = glide.collider().collide(box, movement);
        Motion start = start(glide);
        double worst = damage(start.horizontalLength(), 0);
        if (chunks != null && moved.length() > 0 && FlightCorridor.inspect(box, vec(moved),
            moved.length(), chunks).reason() != FlightCorridor.Reason.CLEAR) return worst;
        AABB reported = box.move(moved.x(), moved.y(), moved.z());
        Tick tick = tick(glide, reported, true);
        if (tick.damage() > 0) return tick.damage();
        if (chunks != null) {
            // The client cannot see blocks the server may hold in a missing chunk.
            Motion reach = reach(glide);
            if (FlightCorridor.inspect(reported, vec(reach), reach.length(), chunks).reason()
                != FlightCorridor.Reason.CLEAR) return worst;
        }
        return 0;
    }

    private static Tick tick(Context glide, AABB reported, boolean padded) {
        Motion velocity = start(glide);
        double before = velocity.horizontalLength();
        Motion next = glide(velocity, glide.pitch(), glide.yaw(), gravity(glide, velocity));
        Motion probe = padded ? allowance(next) : next;
        Motion moved = glide.collider().collide(reported, probe);
        boolean x = !same(probe.x(), moved.x());
        boolean z = !same(probe.z(), moved.z());
        boolean vertical = probe.y() != moved.y();
        // Players do not bounce, so a blocked axis keeps no velocity.
        Motion after = new Motion(x ? 0 : next.x(), vertical ? 0 : next.y(), z ? 0 : next.z());
        return new Tick(after, x || z ? damage(before, after.horizontalLength()) : 0, x || z);
    }

    private static Motion start(Context glide) {
        Motion velocity = glide.velocity();
        for (int rocket = 0; rocket < glide.rockets(); rocket++) {
            velocity = rocket(velocity, glide.pitch(), glide.yaw());
        }
        return settle(velocity);
    }

    /** LivingEntity.aiStep zeroes negligible player velocity before travel. */
    static Motion settle(Motion velocity) {
        double x = velocity.x();
        double y = velocity.y();
        double z = velocity.z();
        if (x * x + z * z < 9.0E-6) {
            x = 0;
            z = 0;
        }
        if (Math.abs(y) < 0.003) y = 0;
        return new Motion(x, y, z);
    }

    /** Entity.calculateViewVector. */
    static Motion look(float pitch, float yaw) {
        float pitchRadians = pitch * 0.017453292F;
        float yawRadians = -yaw * 0.017453292F;
        float yawCos = (float) Math.cos(yawRadians);
        float yawSin = (float) Math.sin(yawRadians);
        float pitchCos = (float) Math.cos(pitchRadians);
        float pitchSin = (float) Math.sin(pitchRadians);
        return new Motion(yawSin * pitchCos, -pitchSin, yawCos * pitchCos);
    }

    private static double gravity(Context glide, Motion velocity) {
        return gravity(glide.gravity(), glide.slowFalling(), velocity);
    }

    private static double gravity(double gravity, boolean slowFalling, Motion velocity) {
        return slowFalling && velocity.y() <= 0 ? Math.min(gravity, 0.01) : gravity;
    }

    private static Motion allowance(Motion next) {
        double horizontal = next.horizontalLength();
        if (horizontal == 0) return next;
        double factor = (horizontal * REACH_SCALE + REACH_PADDING) / horizontal;
        return new Motion(next.x() * factor, next.y(), next.z() * factor);
    }

    private static boolean same(double first, double second) {
        return Math.abs(second - first) < 1.0E-5F;
    }

    private static Motion scale(Motion motion, double factor) {
        return factor == 0 ? Motion.ZERO : new Motion(motion.x() * factor, motion.y() * factor, motion.z() * factor);
    }

    private static Vec3 vec(Motion motion) {
        return new Vec3(motion.x(), motion.y(), motion.z());
    }
}
