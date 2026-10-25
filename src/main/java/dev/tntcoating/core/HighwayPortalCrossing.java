/*
 * Comet highway portal crossing.
 * SPDX-License-Identifier: GPL-3.0-only
 */

package dev.tntcoating.core;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

/** A committed perpendicular crossing: no waypoint changes while touching a portal. */
public record HighwayPortalCrossing(Vec3 direction, Vec3 target) {
    public static HighwayPortalCrossing plan(Vec3 feet, AABB body, BlockPos portal,
                                             Direction.Axis plane, Vec3 approach) {
        boolean normalX = plane == Direction.Axis.Z;
        double component = normalX ? approach.x : approach.z;
        double center = (normalX ? portal.getX() : portal.getZ()) + .5;
        double start = normalX ? feet.x : feet.z;
        double half = normalX ? body.getXsize()*.5 : body.getZsize()*.5;
        double sign = Math.abs(start - center) >= .5 + half ? Math.signum(center - start)
            : component < 0 ? -1 : 1;
        Vec3 direction = normalX ? new Vec3(sign,0,0) : new Vec3(0,0,sign);
        double exit = center + sign * (.5 + half + .75);
        Vec3 target = normalX ? new Vec3(exit,feet.y,feet.z) : new Vec3(feet.x,feet.y,exit);
        return new HighwayPortalCrossing(direction, target);
    }

    public boolean finished(Vec3 feet) {
        return feet.subtract(target).dot(direction) >= -1e-6;
    }
}
