package dev.tntcoating.core;

import java.util.Optional;
import java.util.function.Predicate;
import net.minecraft.core.BlockPos;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

/** Bounded safe targets for walking and for leaving the destination portal. */
public final class HighwayRecoveryTargets {
    private HighwayRecoveryTargets() {}
    public interface ExitProbe {
        boolean clear(AABB swept);
        boolean supported(AABB target);
        boolean portal(AABB target);
    }
    public static Optional<BlockPos> standingTarget(BlockPos projected, int feetY, Predicate<BlockPos> standable) {
        BlockPos best = null; double score = Double.POSITIVE_INFINITY;
        for (int dx=-2;dx<=2;dx++) for (int dz=-2;dz<=2;dz++) for (int dy=-4;dy<=4;dy++) {
            BlockPos candidate = new BlockPos(projected.getX()+dx, feetY+dy, projected.getZ()+dz);
            double cost = dx*dx + dz*dz + Math.abs(dy)*2;
            if (cost >= score || !standable.test(candidate)) continue;
            best = candidate; score = cost;
        }
        return Optional.ofNullable(best);
    }
    public static Optional<Vec3> portalExit(AABB body, Vec3 position, Vec3 preferred, ExitProbe probe) {
        Vec3 heading = new Vec3(preferred.x,0,preferred.z);
        if (heading.lengthSqr()<1e-6) heading = new Vec3(0,0,1);
        heading = heading.normalize();
        Vec3 side = new Vec3(-heading.z,0,heading.x);
        for (Vec3 direction : new Vec3[]{heading, side, side.scale(-1), heading.scale(-1)}) {
            for (double distance=.25;distance<=4;distance+=.25) {
                Vec3 delta=direction.scale(distance); AABB target=body.move(delta);
                AABB swept=body.minmax(target);
                if (!probe.clear(swept)) break;
                if (!probe.supported(target)) break;
                if (!probe.portal(target)) return Optional.of(position.add(delta));
            }
        }
        return Optional.empty();
    }
}
