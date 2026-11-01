/*
 * Original Comet implementation by Boyonabike.
 * Independent: written from the pinned 26.1.2 artifact and Meteor build 42 behaviour; no third-party code.
 * SPDX-License-Identifier: CC0-1.0
 */

package dev.tntcoating.core.combat.mc;

import dev.tntcoating.core.combat.Box;
import dev.tntcoating.core.combat.Cell;
import dev.tntcoating.core.combat.CombatDamage;
import dev.tntcoating.core.combat.ExposurePolicy;
import dev.tntcoating.core.combat.ExposureView;
import dev.tntcoating.core.combat.MitigatedDamage;
import dev.tntcoating.core.combat.MitigationView;
import dev.tntcoating.core.combat.RawDamage;
import dev.tntcoating.core.combat.RaycastHit;
import dev.tntcoating.core.combat.Vec;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.material.Fluid;
import net.minecraft.world.level.material.FluidState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.CollisionContext;

/**
 * Damage pricing against the live client world, using the audited Meteor build-42 sampling grid and the
 * three T1 raycast policies. All arithmetic is unrounded double precision through the pure
 * {@link CombatDamage} core; Meteor's own {@code DamageUtils} truncates to {@code int} before mitigation and
 * hard-codes power 12, so this adapter is the upper-bound authority, not a mirror of that helper.
 *
 * <p>Crystals explode with radius 6, so the vanilla damage path sees power {@code 12}. The adapter never
 * mutates the world; planned blocks are overlaid in a read-only view.</p>
 */
public final class CombatDamageAdapter {
    /** Vanilla end crystals explode with radius 6, so the damage path uses twice the radius. */
    public static final double CRYSTAL_POWER = CombatDamage.CRYSTAL_EXPLOSION_POWER;

    /**
     * Meteor's audited blast-resistance floor. Below it a block is ignored by the upper-bound raycast, so a
     * self or friend bound can never be lowered by cover the audited factory would not count.
     */
    public static final float METEOR_BLAST_RESISTANCE_FLOOR = 600.0F;

    private static final BlockState OVERLAY_STATE = Blocks.OBSIDIAN.defaultBlockState();

    public CombatDamageAdapter() {
    }

    // ------------------------------------------------------------------ exposure

    /**
     * Unobstructed fraction of the sample grid between {@code source} and the target box, replicating the
     * audited build-42 sampling grid (inclusive min/max corners, no Y offset) through {@code policy}.
     *
     * @return fraction in {@code [0, 1]}; {@code 0} for a degenerate box, exactly like the audited code
     */
    public double exposure(BlockGetter world, Vec source, Box box, ExposurePolicy policy,
                           Collection<Cell> overlays) {
        Objects.requireNonNull(world, "world");
        Objects.requireNonNull(overlays, "overlays");
        return exposure(exposureView(world, overlays), source, box, policy);
    }

    /** Adapter-backed pure seam: the sampled grid only asks this view whether a ray is blocked. */
    public ExposureView exposureView(BlockGetter world, Collection<Cell> overlays) {
        Objects.requireNonNull(world, "world");
        Objects.requireNonNull(overlays, "overlays");
        Map<Cell, BlockState> copy = new HashMap<>();
        for (Cell cell : overlays) {
            if (cell != null) copy.put(cell, OVERLAY_STATE);
        }
        BlockGetter view = copy.isEmpty() ? world : new OverlayBlockGetter(world, copy);
        return (from, to, policy) -> raycast(view, from, to, policy);
    }

    /** The audited sampling grid over a pure {@link ExposureView}; usable without a live world. */
    public double exposure(ExposureView view, Vec source, Box targetBox, ExposurePolicy policy) {
        Objects.requireNonNull(view, "view");
        Objects.requireNonNull(source, "source");
        Objects.requireNonNull(targetBox, "targetBox");
        Objects.requireNonNull(policy, "policy");

        double xDiff = targetBox.max().x() - targetBox.min().x();
        double yDiff = targetBox.max().y() - targetBox.min().y();
        double zDiff = targetBox.max().z() - targetBox.min().z();

        double xStep = 1.0 / (xDiff * 2.0 + 1.0);
        double yStep = 1.0 / (yDiff * 2.0 + 1.0);
        double zStep = 1.0 / (zDiff * 2.0 + 1.0);
        if (!(xStep > 0.0) || !(yStep > 0.0) || !(zStep > 0.0)) return 0.0;

        int misses = 0;
        int hits = 0;
        double xOffset = (1.0 - Math.floor(1.0 / xStep) * xStep) * 0.5;
        double zOffset = (1.0 - Math.floor(1.0 / zStep) * zStep) * 0.5;

        xStep *= xDiff;
        yStep *= yDiff;
        zStep *= zDiff;
        // A flat or point hull makes the pinned sampler's per-axis step zero, so its loop would spin
        // forever. Sample the centre once and answer on the conservative side of each policy's bound.
        if (!(xStep > 0.0) || !(yStep > 0.0) || !(zStep > 0.0)) {
            if (policy == ExposurePolicy.TARGET_LOWER_BOUND) return 0.0;
            if (policy == ExposurePolicy.SELF_FRIEND_UPPER_BOUND) return 1.0;
            Vec centre = targetBox.center();
            return view.raycast(centre, source, policy).blocked() ? 0.0 : 1.0;
        }

        double startX = targetBox.min().x() + xOffset;
        double startY = targetBox.min().y();
        double startZ = targetBox.min().z() + zOffset;
        double endX = targetBox.max().x() + xOffset;
        double endY = targetBox.max().y();
        double endZ = targetBox.max().z() + zOffset;

        for (double x = startX; x <= endX; x += xStep) {
            for (double y = startY; y <= endY; y += yStep) {
                for (double z = startZ; z <= endZ; z += zStep) {
                    RaycastHit hit = view.raycast(new Vec(x, y, z), source, policy);
                    if (hit.blocked()) {
                        misses++;
                    } else {
                        hits++;
                    }
                }
            }
        }
        int total = misses + hits;
        return total == 0 ? 0.0 : (double) hits / (double) total;
    }

    /**
     * One ray for one policy. {@link ExposurePolicy#FAITHFUL} is vanilla's collision clip;
     * {@link ExposurePolicy#TARGET_LOWER_BOUND} counts every non-air block as cover (soft cover included);
     * {@link ExposurePolicy#SELF_FRIEND_UPPER_BOUND} counts only blocks at or above Meteor's audited
     * blast-resistance floor, so it can only under-count cover.
     */
    RaycastHit raycast(BlockGetter world, Vec from, Vec to, ExposurePolicy policy) {
        Vec3 start = MinecraftCombatClient.toVec3(from);
        Vec3 end = MinecraftCombatClient.toVec3(to);
        if (policy == ExposurePolicy.FAITHFUL) {
            BlockHitResult hit = world.clip(new ClipContext(start, end, ClipContext.Block.COLLIDER,
                ClipContext.Fluid.NONE, CollisionContext.empty()));
            if (hit.getType() == HitResult.Type.MISS) return RaycastHit.clear(to);
            return RaycastHit.blockedAt(MinecraftCombatClient.toVec(hit.getLocation()));
        }
        if (policy == ExposurePolicy.TARGET_LOWER_BOUND) {
            RaycastHit hit = BlockGetter.traverseBlocks(start, end, null,
                (context, pos) -> {
                    BlockState state = world.getBlockState(pos);
                    if (state.isAir()) return null;
                    Optional<Vec3> clipped = new AABB(pos).clip(start, end);
                    return clipped.map(point -> RaycastHit.blockedAt(MinecraftCombatClient.toVec(point)))
                        .orElse(null);
                },
                context -> null);
            return hit == null ? RaycastHit.clear(to) : hit;
        }
        RaycastHit hit = BlockGetter.traverseBlocks(start, end, null,
            (context, pos) -> {
                BlockState state = world.getBlockState(pos);
                if (state.getBlock().getExplosionResistance() < METEOR_BLAST_RESISTANCE_FLOOR) return null;
                BlockHitResult clipped = state.getCollisionShape(world, pos).clip(start, end, pos);
                return clipped == null ? null
                    : RaycastHit.blockedAt(MinecraftCombatClient.toVec(clipped.getLocation()));
            },
            context -> null);
        return hit == null ? RaycastHit.clear(to) : hit;
    }

    // ------------------------------------------------------------------ damage

    /** Raw explosion damage at {@code distance} with the given exposure, unrounded. */
    public RawDamage rawExplosionDamage(double distance, double exposure) {
        return CombatDamage.rawExplosionDamage(distance, exposure, CRYSTAL_POWER);
    }

    /** Mitigation using the entity's current protection. */
    public MitigatedDamage mitigated(RawDamage raw, MitigationView view) {
        Objects.requireNonNull(raw, "raw");
        Objects.requireNonNull(view, "view");
        return CombatDamage.mitigated(raw, view.current());
    }

    /**
     * Self and friend upper bounds always run through the envelope path, which derives the weakest possible
     * mitigation. Pricing them against {@code current()} would understate the bound whenever armour could
     * break or Resistance could expire before arrival.
     */
    public MitigatedDamage upperBound(RawDamage raw, MitigationView view) {
        Objects.requireNonNull(raw, "raw");
        Objects.requireNonNull(view, "view");
        return CombatDamage.mitigated(raw,
            CombatDamage.weakestEnvelope(view.current(), view.pieces(), view.effectsMayExpire()));
    }

    // ------------------------------------------------------------------ overlay view

    /**
     * Read-only world view that replaces the cells a dispatch will fill with obsidian before it fires. The
     * delegate is never written to; only lookups for overlaid cells are substituted.
     */
    private static final class OverlayBlockGetter implements BlockGetter {
        private final BlockGetter delegate;
        private final Map<Cell, BlockState> overlays;

        private OverlayBlockGetter(BlockGetter delegate, Map<Cell, BlockState> overlays) {
            this.delegate = delegate;
            this.overlays = Map.copyOf(overlays);
        }

        @Override
        public BlockEntity getBlockEntity(BlockPos pos) {
            return overlays.containsKey(cellOf(pos)) ? null : delegate.getBlockEntity(pos);
        }

        @Override
        public BlockState getBlockState(BlockPos pos) {
            BlockState state = overlays.get(cellOf(pos));
            return state != null ? state : delegate.getBlockState(pos);
        }

        @Override
        public FluidState getFluidState(BlockPos pos) {
            return overlays.containsKey(cellOf(pos)) ? OVERLAY_STATE.getFluidState()
                : delegate.getFluidState(pos);
        }

        @Override
        public int getHeight() {
            return delegate.getHeight();
        }

        @Override
        public int getMinY() {
            return delegate.getMinY();
        }

        private static Cell cellOf(BlockPos pos) {
            return new Cell(pos.getX(), pos.getY(), pos.getZ());
        }
    }
}
