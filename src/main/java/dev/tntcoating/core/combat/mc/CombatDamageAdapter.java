/*
 * Original Comet implementation by Boyonabike.
 * Reimplemented idea from Meteor Client build 26.2-20261009.074120-35 (GPL-3.0-only): the exposure
 * sampling grid and the 600 blast-resistance floor follow DamageUtils.getExposure; no Meteor source is
 * copied. See THIRD_PARTY_NOTICES.md.
 * SPDX-License-Identifier: CC0-1.0
 */

package dev.tntcoating.core.combat.mc;

import dev.tntcoating.core.combat.Box;
import dev.tntcoating.core.combat.Cell;
import dev.tntcoating.core.combat.CombatDamage;
import dev.tntcoating.core.combat.EntityKey;
import dev.tntcoating.core.combat.ExposureInterval;
import dev.tntcoating.core.combat.ExposurePolicy;
import dev.tntcoating.core.combat.ExposureView;
import dev.tntcoating.core.combat.MitigatedDamage;
import dev.tntcoating.core.combat.MitigationState;
import dev.tntcoating.core.combat.MitigationView;
import dev.tntcoating.core.combat.RawDamage;
import dev.tntcoating.core.combat.RaycastHit;
import dev.tntcoating.core.combat.Vec;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.Holder;
import net.minecraft.world.damagesource.CombatRules;
import net.minecraft.world.damagesource.DamageScaling;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.damagesource.DamageType;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.material.FluidState;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;

import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * The adapter between the pure combat model and the pinned 26.2 world.
 *
 * <p>Exposure is sampled with the audited Meteor build-35 grid, but each ray goes through an explicit
 * {@link ExposurePolicy}. The damage functions return {@link RawDamage} and {@link MitigatedDamage}
 * separately and never subtract absorption. Values are unrounded: Meteor's {@code int} cast (audit C18) is
 * never used as an upper bound here.</p>
 *
 * <p>Virtual blocks (planned obsidian for a crystal base) are supported by a read-only overlay wrapper; the
 * client world is never mutated.</p>
 */
public final class CombatDamageAdapter {
    /** Vanilla end crystals explode with radius 6, so the damage path uses twice the radius. */
    public static final double CRYSTAL_POWER = CombatDamage.CRYSTAL_EXPLOSION_POWER;
    /** Meteor's audited blast-resistance floor below which a block does not count as cover. */
    public static final float METEOR_BLAST_RESISTANCE_FLOOR = 600.0F;

    private static final Holder<DamageType> EXPLOSION_TYPE =
        Holder.direct(new DamageType("explosion", DamageScaling.ALWAYS, 0.1F));
    private static final DamageSource EXPLOSION_SOURCE = new DamageSource(EXPLOSION_TYPE);

    public CombatDamageAdapter() {
    }

    // ------------------------------------------------------------------ exposure

    /**
     * Unobstructed fraction of the sample grid between {@code source} and the target box, replicating the
     * audited Meteor build-35 sampling grid (inclusive min/max corners, no Y offset) through the policy.
     *
     * @return fraction in {@code [0, 1]}; {@code 0} for a degenerate box, exactly like the audited code
     */
    public double exposure(BlockGetter world, Vec source, Box targetBox, ExposurePolicy policy,
                           Collection<Cell> overlays) {
        Objects.requireNonNull(world, "world");
        Objects.requireNonNull(overlays, "overlays");
        return exposure(exposureView(world, overlays), source, targetBox, policy);
    }

    /** The adapter-backed pure seam: the sampled grid only ever asks this view whether a ray is blocked. */
    public ExposureView exposureView(BlockGetter world, Collection<Cell> overlays) {
        Objects.requireNonNull(world, "world");
        Objects.requireNonNull(overlays, "overlays");
        List<Cell> copy = List.copyOf(overlays);
        return (from, to, policy) -> raycast(world, from, to, policy, copy);
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
                    Vec point = new Vec(x, y, z);
                    if (!view.raycast(point, source, policy).blocked()) misses++;
                    hits++;
                }
            }
        }
        return (double) misses / (double) hits;
    }

    /**
     * One ray for the exposure sampler, from the sampled point to the explosion centre. Faithful and
     * lower-bound rays see the overlay blocks; the self/friend upper bound ignores them so it can only rise.
     */
    public RaycastHit raycast(BlockGetter world, Vec from, Vec to, ExposurePolicy policy,
                              Collection<Cell> overlays) {
        Objects.requireNonNull(world, "world");
        Objects.requireNonNull(from, "from");
        Objects.requireNonNull(to, "to");
        Objects.requireNonNull(policy, "policy");
        Objects.requireNonNull(overlays, "overlays");

        Vec3 start = new Vec3(from.x(), from.y(), from.z());
        Vec3 end = new Vec3(to.x(), to.y(), to.z());
        return switch (policy) {
            case FAITHFUL -> faithfulRaycast(scene(world, overlays), start, end);
            case TARGET_LOWER_BOUND -> lowerBoundRaycast(scene(world, overlays), start, end);
            case SELF_FRIEND_UPPER_BOUND -> upperBoundRaycast(world, start, end);
        };
    }

    private RaycastHit faithfulRaycast(BlockGetter scene, Vec3 start, Vec3 end) {
        BlockHitResult hit = scene.clip(new ClipContext(start, end, ClipContext.Block.COLLIDER,
            ClipContext.Fluid.NONE, CollisionContext.empty()));
        if (hit.getType() == HitResult.Type.MISS) return RaycastHit.clear(toVec(end));
        return RaycastHit.blockedAt(toVec(hit.getLocation()));
    }

    private RaycastHit lowerBoundRaycast(BlockGetter scene, Vec3 start, Vec3 end) {
        BlockHitResult hit = BlockGetter.traverseBlocks(start, end, null, (ignored, pos) -> {
            if (scene.getBlockState(pos).isAir()) return null;
            return fullCubeHit(pos, start, end);
        }, ignored -> null);
        return hit == null ? RaycastHit.clear(toVec(end)) : RaycastHit.blockedAt(toVec(hit.getLocation()));
    }

    private RaycastHit upperBoundRaycast(BlockGetter world, Vec3 start, Vec3 end) {
        BlockHitResult hit = BlockGetter.traverseBlocks(start, end, null, (ignored, pos) -> {
            BlockState state = world.getBlockState(pos);
            if (state.getBlock().getExplosionResistance() < METEOR_BLAST_RESISTANCE_FLOOR) return null;
            // Resistance alone is not cover: the ray must actually meet the collision shape, or a
            // non-full block the ray misses would understate the self/friend upper bound.
            BlockHitResult clipped = state.getCollisionShape(world, pos).clip(start, end, pos);
            if (clipped == null) return null;
            return new BlockHitResult(clipped.getLocation(), Direction.UP, pos, false);
        }, ignored -> null);
        return hit == null ? RaycastHit.clear(toVec(end)) : RaycastHit.blockedAt(toVec(hit.getLocation()));
    }

    private static BlockHitResult fullCubeHit(BlockPos pos, Vec3 start, Vec3 end) {
        Optional<Vec3> clip = new AABB(pos).clip(start, end);
        return clip.map(point -> new BlockHitResult(point, Direction.UP, pos, false)).orElse(null);
    }

    private static BlockGetter scene(BlockGetter world, Collection<Cell> overlays) {
        if (overlays.isEmpty()) return world;
        Map<Cell, BlockState> states = new HashMap<>();
        for (Cell cell : overlays) {
            Objects.requireNonNull(cell, "overlay cell");
            states.put(cell, Blocks.OBSIDIAN.defaultBlockState());
        }
        return new OverlayBlockGetter(world, states);
    }

    // ------------------------------------------------------------------ damage

    /** Raw damage from one explosion at {@code centre} against a target at {@code feet} with {@code box}. */
    public RawDamage rawExplosionDamage(BlockGetter world, Vec centre, Vec feet, Box targetBox,
                                        Collection<Cell> overlays) {
        double distance = feet.distance(centre);
        double exposure = exposure(world, centre, targetBox, ExposurePolicy.FAITHFUL, overlays);
        return rawExplosionDamage(distance, exposure);
    }

    /** Raw damage from the audited formula, unrounded, for a known distance and exposure. */
    public RawDamage rawExplosionDamage(double distance, double exposure) {
        if (distance > CRYSTAL_POWER) return new RawDamage(0.0);
        return CombatDamage.rawExplosionDamage(distance, exposure, CRYSTAL_POWER);
    }

    /** Mitigation through the pinned 26.2 rule functions; absorption is never subtracted. */
    public MitigatedDamage mitigated(RawDamage raw, MitigationView view) {
        Objects.requireNonNull(raw, "raw");
        Objects.requireNonNull(view, "view");
        return mitigated(raw, view.current());
    }

    /** Same as {@link #mitigated(RawDamage, MitigationView)} with the weakest possible protection state. */
    public MitigatedDamage upperBound(RawDamage raw, MitigationView view) {
        Objects.requireNonNull(raw, "raw");
        Objects.requireNonNull(view, "view");
        return mitigated(raw, envelope(view));
    }

    /** The weakest protection state the entity could have at the latest arrival. */
    public MitigationState envelope(MitigationView view) {
        Objects.requireNonNull(view, "view");
        return CombatDamage.weakestEnvelope(view.current(), view.pieces(), view.effectsMayExpire());
    }

    public MitigatedDamage mitigated(RawDamage raw, MitigationState state) {
        Objects.requireNonNull(raw, "raw");
        Objects.requireNonNull(state, "state");
        double damage = CombatDamage.afterDifficulty(raw.value(), state.difficulty());
        if (!state.bypassesArmour()) {
            damage = CombatRules.getDamageAfterAbsorb(null, (float) damage, EXPLOSION_SOURCE,
                (float) state.armour(), (float) state.toughness());
        }
        if (!state.bypassesResistance()) {
            damage = CombatDamage.afterResistance(damage, state.resistanceAmplifier());
        }
        if (!state.bypassesEnchantments()) {
            damage = CombatRules.getDamageAfterMagicAbsorb((float) damage, (float) state.protectionPoints());
        }
        return new MitigatedDamage(Math.max(0.0, damage));
    }

    // ------------------------------------------------------------------ sweeps

    /**
     * Sweeps every feasible crystal position against every feasible victim position and returns the exposure
     * interval: the smallest lower-bound exposure and the largest upper-bound exposure any pair can produce.
     *
     * @throws IllegalArgumentException when either set is empty
     */
    public ExposureInterval sweptExposure(BlockGetter world, Collection<Vec> crystalCentres,
                                          Collection<Box> victimBoxes, Collection<Cell> overlays) {
        Objects.requireNonNull(world, "world");
        Objects.requireNonNull(crystalCentres, "crystalCentres");
        Objects.requireNonNull(victimBoxes, "victimBoxes");
        Objects.requireNonNull(overlays, "overlays");
        if (crystalCentres.isEmpty()) throw new IllegalArgumentException("crystalCentres must not be empty");
        if (victimBoxes.isEmpty()) throw new IllegalArgumentException("victimBoxes must not be empty");

        double lower = 1.0;
        double upper = 0.0;
        for (Vec crystal : crystalCentres) {
            Objects.requireNonNull(crystal, "crystal");
            for (Box box : victimBoxes) {
                Objects.requireNonNull(box, "victim box");
                lower = Math.min(lower,
                    exposure(world, crystal, box, ExposurePolicy.TARGET_LOWER_BOUND, overlays));
                upper = Math.max(upper,
                    exposure(world, crystal, box, ExposurePolicy.SELF_FRIEND_UPPER_BOUND, overlays));
            }
        }
        return new ExposureInterval(lower, upper);
    }

    /**
     * Per-friend worst-case exposure as a map: for each friend box, the largest upper-bound raw damage any
     * feasible crystal position can deal, pushed through the friend's weakest possible mitigation state.
     * Distance uses the entity's feet (box centre x/z, minimum y), the vanilla entity-position rule; the
     * exposure rays still cover the full box. An empty crystal set cannot bound anything, so it is
     * rejected instead of returning a low map.
     *
     * @throws IllegalArgumentException when no crystal position is supplied
     */
    public Map<EntityKey, MitigatedDamage> friendUpperBounds(BlockGetter world,
                                                             Collection<Vec> crystalCentres,
                                                             Map<EntityKey, Box> friends,
                                                             MitigationState state,
                                                             Collection<Cell> overlays) {
        Objects.requireNonNull(world, "world");
        Objects.requireNonNull(crystalCentres, "crystalCentres");
        Objects.requireNonNull(friends, "friends");
        Objects.requireNonNull(state, "state");
        Objects.requireNonNull(overlays, "overlays");
        if (crystalCentres.isEmpty()) throw new IllegalArgumentException("crystalCentres must not be empty");

        Map<EntityKey, MitigatedDamage> bounds = new LinkedHashMap<>();
        for (Map.Entry<EntityKey, Box> friend : friends.entrySet()) {
            EntityKey key = Objects.requireNonNull(friend.getKey(), "friend key");
            Box box = Objects.requireNonNull(friend.getValue(), "friend box");
            Vec feet = new Vec(box.center().x(), box.min().y(), box.center().z());
            RawDamage worst = new RawDamage(0.0);
            for (Vec crystal : crystalCentres) {
                Objects.requireNonNull(crystal, "crystal");
                double distance = feet.distance(crystal);
                double exposure = exposure(world, crystal, box,
                    ExposurePolicy.SELF_FRIEND_UPPER_BOUND, overlays);
                RawDamage raw = rawExplosionDamage(distance, exposure);
                if (raw.value() > worst.value()) worst = raw;
            }
            bounds.put(key, mitigated(worst, state));
        }
        return Map.copyOf(bounds);
    }

    // ------------------------------------------------------------------ helpers

    private static Vec toVec(Vec3 point) {
        return new Vec(point.x, point.y, point.z);
    }

    /** Read-only view that substitutes overlay states at declared cells without touching the world. */
    private static final class OverlayBlockGetter implements BlockGetter {
        private final BlockGetter delegate;
        private final Map<Cell, BlockState> overlays;

        private OverlayBlockGetter(BlockGetter delegate, Map<Cell, BlockState> overlays) {
            this.delegate = delegate;
            this.overlays = overlays;
        }

        private BlockState overlay(BlockPos pos) {
            return overlays.get(new Cell(pos.getX(), pos.getY(), pos.getZ()));
        }

        @Override
        public BlockEntity getBlockEntity(BlockPos pos) {
            return delegate.getBlockEntity(pos);
        }

        @Override
        public BlockState getBlockState(BlockPos pos) {
            BlockState overlay = overlay(pos);
            return overlay != null ? overlay : delegate.getBlockState(pos);
        }

        @Override
        public FluidState getFluidState(BlockPos pos) {
            return delegate.getFluidState(pos);
        }

        @Override
        public int getHeight() {
            return delegate.getHeight();
        }

        @Override
        public int getMinY() {
            return delegate.getMinY();
        }
    }

}
