/*
 * Original Comet implementation by Boyonabike.
 * Independent: written from the pinned 26.2 artifact and Meteor build 35 behaviour; no third-party code.
 * SPDX-License-Identifier: CC0-1.0
 */

package dev.tntcoating.core.combat.mc;

import dev.tntcoating.core.combat.Box;
import dev.tntcoating.core.combat.CombatDamage;
import dev.tntcoating.core.combat.CombatDifficulty;
import dev.tntcoating.core.combat.ExposurePolicy;
import dev.tntcoating.core.combat.MitigatedDamage;
import dev.tntcoating.core.combat.MitigationState;
import dev.tntcoating.core.combat.RawDamage;
import dev.tntcoating.core.combat.Vec;
import meteordevelopment.meteorclient.utils.entity.DamageUtils;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.Holder;
import net.minecraft.world.damagesource.CombatRules;
import net.minecraft.world.damagesource.DamageScaling;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.damagesource.DamageType;
import net.minecraft.world.effect.MobEffect;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.monster.zombie.Zombie;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.Explosion;
import net.minecraft.world.level.ExplosionDamageCalculator;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.HashMap;
import java.util.Map;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Differential tests against the pinned code paths themselves, not against a second copy of their formulas:
 *
 * <ul>
 *   <li>raw explosion damage calls the pinned 26.2
 *       {@link ExplosionDamageCalculator#getEntityDamageAmount(Explosion, Entity, float)} on a real (unsafe
 *       allocated) entity in a bootstrapped, server-less test JVM;</li>
 *   <li>mitigation calls the pinned {@link CombatRules} functions the server calls;</li>
 *   <li>exposure calls Meteor build 35's own private {@code DamageUtils.getExposure} sampler through reflection
 *       with an equivalent raycast factory.</li>
 * </ul>
 *
 * <p>The Resistance stage is also probed, not recorded: an unsafe-allocated {@link LivingEntity} with only
 * its effect map populated calls the pinned {@code getDamageAfterMagicAbsorb} with no level, which applies
 * Resistance and leaves protection to {@link CombatRules} exactly as the server chain does. The one stage
 * that cannot run headlessly is the difficulty multiplier inside {@code Player.hurtServer}; the differential
 * rows use {@code NORMAL} difficulty (where the multiplier is identity) and that branch is bytecode-verified
 * in the audit and covered by the pure tests.</p>
 */
class CombatDamageAdapterPinnedTest {
    static {
        AdapterTestSupport.bootstrap();
    }

    private static final CombatDamageAdapter ADAPTER = new CombatDamageAdapter();
    private static final Holder<DamageType> EXPLOSION_TYPE =
        Holder.direct(new DamageType("explosion", DamageScaling.ALWAYS, 0.1F));
    private static final DamageSource EXPLOSION_SOURCE = new DamageSource(EXPLOSION_TYPE);

    // ------------------------------------------------------------------ raw damage

    @Test
    void rawDamageMatchesThePinnedExplosionCalculator() {
        double[][] rows = {
            {0.0, 1.0}, {0.5, 1.0}, {4.0, 1.0}, {4.0, 0.5}, {8.0, 1.0}, {11.9, 0.75}, {12.0, 1.0},
            {3.0, 0.0}, {9.5, 0.25},
        };
        for (double[] row : rows) {
            double pinned = pinnedRawDamage(row[0], row[1]);
            double actual = ADAPTER.rawExplosionDamage(row[0], row[1]).value();
            assertEquals(pinned, actual, 1.0E-4, "distance=" + row[0] + " exposure=" + row[1]);
        }
    }

    @Test
    void rawDamageIsNotTruncatedLikeMeteorDamageUtils() {
        // Audit C18: Meteor casts the raw value to int before mitigation. The pinned 26.2 calculator does not.
        double raw = ADAPTER.rawExplosionDamage(4.0, 1.0).value();
        double pinned = pinnedRawDamage(4.0, 1.0);
        assertEquals(pinned, raw, 1.0E-4);
        assertTrue(raw > (int) pinned, "unrounded value must exceed the truncated one: " + raw);
        assertEquals(47.0, (int) pinned, 0.0);
    }

    // ------------------------------------------------------------------ mitigation

    @Test
    void mitigationMatchesThePinnedCombatRulesChain() {
        // NORMAL difficulty: the damage amount is unchanged before armour (Player.hurtServer bytecode).
        record Row(double armour, double toughness, double protection, int resistance, boolean noArmour,
                   boolean noResistance, boolean noEnchantments) {
        }
        List<Row> rows = List.of(
            new Row(0.0, 0.0, 0.0, 0, false, false, false),
            new Row(20.0, 0.0, 0.0, 0, false, false, false),
            new Row(10.0, 8.0, 0.0, 0, false, false, false),
            new Row(20.0, 12.0, 4.0, 0, false, false, false),
            new Row(20.0, 12.0, 8.0, 1, false, false, false),
            new Row(15.0, 6.0, 0.0, 1, false, false, false),
            new Row(7.0, 3.0, 2.0, 0, false, false, false),
            new Row(20.0, 12.0, 4.0, 0, true, false, false),
            new Row(20.0, 12.0, 8.0, 1, false, true, false),
            new Row(20.0, 12.0, 8.0, 1, false, false, true),
            new Row(20.0, 12.0, 8.0, 1, true, true, true)
        );

        for (Row row : rows) {
            MitigationState state = new MitigationState(CombatDifficulty.NORMAL, row.armour(), row.toughness(),
                row.protection(), row.resistance(), row.noArmour(), row.noResistance(), row.noEnchantments());
            float pinned = 60.0F;
            if (!row.noArmour()) {
                pinned = CombatRules.getDamageAfterAbsorb(null, pinned, EXPLOSION_SOURCE,
                    (float) row.armour(), (float) row.toughness());
            }
            if (!row.noResistance()) {
                pinned = pinnedResistance(pinned, row.resistance());
            }
            if (!row.noEnchantments()) {
                pinned = CombatRules.getDamageAfterMagicAbsorb(pinned, (float) row.protection());
            }
            pinned = Math.max(0.0F, pinned);

            MitigatedDamage actual = ADAPTER.mitigated(new RawDamage(60.0), state);
            assertEquals(pinned, actual.beforeAbsorption(), 5.0E-4, "row " + row);
        }
    }

    @Test
    void absorptionIsNeverPartOfMitigation() {
        MitigationState state = new MitigationState(CombatDifficulty.NORMAL, 20.0, 8.0, 4.0, 0,
            false, false, false);
        double withoutAbsorption = ADAPTER.mitigated(new RawDamage(60.0), state).beforeAbsorption();
        // 60 - 20 absorption would be 40 if the adapter wrongly subtracted absorption; the pinned chain gives
        // the value the server passes to hurtServer, before absorption is applied by actuallyHurt.
        float pinned = CombatRules.getDamageAfterAbsorb(null, 60.0F, EXPLOSION_SOURCE, 20.0F, 8.0F);
        pinned = pinnedResistance(pinned, 0);
        pinned = CombatRules.getDamageAfterMagicAbsorb(pinned, 4.0F);
        assertEquals(pinned, withoutAbsorption, 5.0E-4);
    }

    // ------------------------------------------------------------------ exposure

    @Test
    void faithfulExposureMatchesThePinnedMeteorSampler() {
        AdapterTestSupport.FakeWorld open = new AdapterTestSupport.FakeWorld();
        Box box = AdapterTestSupport.playerBox(0.5, 1.0, 0.5);

        assertEquals(pinnedMeteorExposure(new Vec3(0.5, 0.5, 0.5), AdapterTestSupport.aabb(box),
                faithfulFactory(open, List.of())),
            ADAPTER.exposure(open, new Vec(0.5, 0.5, 0.5), box, ExposurePolicy.FAITHFUL, List.of()), 1.0E-6);

        AdapterTestSupport.FakeWorld covered = new AdapterTestSupport.FakeWorld();
        covered.set(0, 4, 0, Blocks.OBSIDIAN.defaultBlockState());
        assertEquals(pinnedMeteorExposure(new Vec3(0.5, 5.0, 0.5), AdapterTestSupport.aabb(box),
                faithfulFactory(covered, List.of())),
            ADAPTER.exposure(covered, new Vec(0.5, 5.0, 0.5), box, ExposurePolicy.FAITHFUL, List.of()), 1.0E-6);

        AdapterTestSupport.FakeWorld partial = new AdapterTestSupport.FakeWorld();
        partial.set(1, 1, 2, Blocks.STONE.defaultBlockState());
        Box wide = new Box(new Vec(0.2, 1.0, 0.2), new Vec(2.8, 2.8, 0.8));
        Vec partialSource = new Vec(0.5, 1.5, 4.5);
        double partialExpected = pinnedMeteorExposure(new Vec3(0.5, 1.5, 4.5), AdapterTestSupport.aabb(wide),
            faithfulFactory(partial, List.of()));
        assertTrue(partialExpected > 0.0 && partialExpected < 1.0, "fixture scene must be partial: " + partialExpected);
        assertEquals(partialExpected,
            ADAPTER.exposure(partial, partialSource, wide, ExposurePolicy.FAITHFUL, List.of()), 1.0E-6);
    }

    @Test
    void overlayExposureMatchesAWorldWithTheSameBlock() {
        Box box = AdapterTestSupport.playerBox(0.5, 1.0, 0.5);
        Vec source = new Vec(0.5, 0.5, 0.5);

        AdapterTestSupport.FakeWorld empty = new AdapterTestSupport.FakeWorld();
        AdapterTestSupport.FakeWorld withBlock = new AdapterTestSupport.FakeWorld();
        withBlock.set(0, 0, 0, Blocks.OBSIDIAN.defaultBlockState());

        double reference = pinnedMeteorExposure(new Vec3(0.5, 0.5, 0.5), AdapterTestSupport.aabb(box),
            faithfulFactory(withBlock, List.of()));
        assertEquals(reference, ADAPTER.exposure(empty, source, box, ExposurePolicy.FAITHFUL,
            List.of(AdapterTestSupport.cell(0, 0, 0))), 1.0E-6);
    }

    @Test
    void lowerAndUpperPolicySamplersMatchTheirPinnedFactories() {
        AdapterTestSupport.FakeWorld mixed = new AdapterTestSupport.FakeWorld();
        mixed.set(1, 1, 2, Blocks.STONE.defaultBlockState());
        mixed.set(1, 2, 2, Blocks.OBSIDIAN.defaultBlockState());
        Box box = new Box(new Vec(0.2, 1.0, 0.2), new Vec(2.8, 2.8, 0.8));
        Vec source = new Vec(0.5, 1.5, 4.5);
        Vec3 source3 = new Vec3(0.5, 1.5, 4.5);

        double pinnedLower = pinnedMeteorExposure(source3, AdapterTestSupport.aabb(box),
            lowerBoundFactory(mixed, List.of()));
        assertEquals(pinnedLower,
            ADAPTER.exposure(mixed, source, box, ExposurePolicy.TARGET_LOWER_BOUND, List.of()), 1.0E-6);

        double pinnedUpper = pinnedMeteorExposure(source3, AdapterTestSupport.aabb(box),
            upperBoundFactory(mixed, List.of()));
        assertEquals(pinnedUpper,
            ADAPTER.exposure(mixed, source, box, ExposurePolicy.SELF_FRIEND_UPPER_BOUND, List.of()), 1.0E-6);
    }

    @Test
    void lowerBoundIsNeverAboveFaithfulAndUpperNeverBelowIt() {
        AdapterTestSupport.FakeWorld scene = new AdapterTestSupport.FakeWorld();
        scene.set(1, 1, 2, Blocks.STONE.defaultBlockState());
        scene.set(1, 2, 2, Blocks.OBSIDIAN.defaultBlockState());
        Box box = new Box(new Vec(0.2, 1.0, 0.2), new Vec(2.8, 2.8, 0.8));
        Vec source = new Vec(0.5, 1.5, 4.5);
        double faithful = ADAPTER.exposure(scene, source, box, ExposurePolicy.FAITHFUL, List.of());
        double lower = ADAPTER.exposure(scene, source, box, ExposurePolicy.TARGET_LOWER_BOUND, List.of());
        double upper = ADAPTER.exposure(scene, source, box, ExposurePolicy.SELF_FRIEND_UPPER_BOUND, List.of());
        assertTrue(lower <= faithful, "lower=" + lower + " faithful=" + faithful);
        assertTrue(faithful <= upper, "faithful=" + faithful + " upper=" + upper);
    }

    // ------------------------------------------------------------------ pinned plumbing

    private static double pinnedRawDamage(double distance, double seen) {
        Entity entity = unsafeEntity(new Vec3(0.5, 0.5, 0.5));
        Explosion explosion = pinnedExplosion(new Vec3(0.5 + distance, 0.5, 0.5));
        return new ExplosionDamageCalculator().getEntityDamageAmount(explosion, entity, (float) seen);
    }

    private static Explosion pinnedExplosion(Vec3 center) {
        return new Explosion() {
            @Override
            public net.minecraft.server.level.ServerLevel level() {
                return null;
            }

            @Override
            public Explosion.BlockInteraction getBlockInteraction() {
                return Explosion.BlockInteraction.KEEP;
            }

            @Override
            public net.minecraft.world.entity.LivingEntity getIndirectSourceEntity() {
                return null;
            }

            @Override
            public Entity getDirectSourceEntity() {
                return null;
            }

            @Override
            public float radius() {
                return 6.0F;
            }

            @Override
            public Vec3 center() {
                return center;
            }

            @Override
            public boolean canTriggerBlocks() {
                return false;
            }

            @Override
            public boolean shouldAffectBlocklikeEntities() {
                return false;
            }
        };
    }

    /**
     * The pinned Resistance path: a real {@link LivingEntity} method call without a running game. The instance
     * is unsafe-allocated so no constructor runs; only the effect map is populated. With no level, the method
     * applies Resistance and skips enchantment protection, which the caller then adds through {@link CombatRules}.
     */
    private static float pinnedResistance(float damage, int resistanceAmplifier) {
        try {
            Field unsafeField = sun.misc.Unsafe.class.getDeclaredField("theUnsafe");
            unsafeField.setAccessible(true);
            sun.misc.Unsafe unsafe = (sun.misc.Unsafe) unsafeField.get(null);
            LivingEntity living = (LivingEntity) unsafe.allocateInstance(Zombie.class);
            Field effectsField = LivingEntity.class.getDeclaredField("activeEffects");
            effectsField.setAccessible(true);
            Map<Holder<MobEffect>, MobEffectInstance> effects = new HashMap<>();
            effects.put(MobEffects.RESISTANCE,
                new MobEffectInstance(MobEffects.RESISTANCE, 200, resistanceAmplifier));
            effectsField.set(living, effects);
            Method method = LivingEntity.class.getDeclaredMethod("getDamageAfterMagicAbsorb",
                DamageSource.class, float.class);
            method.setAccessible(true);
            return (float) method.invoke(living, EXPLOSION_SOURCE, damage);
        } catch (ReflectiveOperationException exception) {
            throw new AssertionError("cannot construct the pinned living-entity fixture", exception);
        }
    }

    /** A real {@link Entity} instance without running the heavyweight constructor or needing a running game. */
    private static Entity unsafeEntity(Vec3 position) {
        try {
            Field unsafeField = sun.misc.Unsafe.class.getDeclaredField("theUnsafe");
            unsafeField.setAccessible(true);
            sun.misc.Unsafe unsafe = (sun.misc.Unsafe) unsafeField.get(null);
            Entity entity = (Entity) unsafe.allocateInstance(DummyEntity.class);
            Field positionField = Entity.class.getDeclaredField("position");
            positionField.setAccessible(true);
            positionField.set(entity, position);
            return entity;
        } catch (ReflectiveOperationException exception) {
            throw new AssertionError("cannot construct the pinned entity fixture", exception);
        }
    }

    private static final class DummyEntity extends Entity {
        private DummyEntity() {
            super(null, null);
        }

        @Override
        protected void defineSynchedData(net.minecraft.network.syncher.SynchedEntityData.Builder builder) {
        }

        @Override
        public boolean hurtServer(net.minecraft.server.level.ServerLevel level,
                                  net.minecraft.world.damagesource.DamageSource source, float damage) {
            return false;
        }

        @Override
        protected void readAdditionalSaveData(net.minecraft.world.level.storage.ValueInput input) {
        }

        @Override
        protected void addAdditionalSaveData(net.minecraft.world.level.storage.ValueOutput output) {
        }
    }

    /** Meteor's own private sampler, invoked through reflection so the fixture is the pinned implementation. */
    private static double pinnedMeteorExposure(Vec3 source, AABB box, DamageUtils.RaycastFactory factory) {
        try {
            Method exposure = DamageUtils.class.getDeclaredMethod("getExposure", Vec3.class, AABB.class,
                DamageUtils.RaycastFactory.class);
            exposure.setAccessible(true);
            return ((Number) exposure.invoke(null, source, box, factory)).doubleValue();
        } catch (ReflectiveOperationException exception) {
            throw new AssertionError("pinned Meteor DamageUtils.getExposure is not callable", exception);
        }
    }

    private static DamageUtils.RaycastFactory faithfulFactory(BlockGetter scene, List<Vec> ignored) {
        return (context, pos) -> {
            BlockState state = scene.getBlockState(pos);
            if (state.isAir()) return null;
            return state.getCollisionShape(scene, pos).clip(context.start(), context.end(), pos);
        };
    }

    private static DamageUtils.RaycastFactory lowerBoundFactory(BlockGetter scene, List<Vec> ignored) {
        return (context, pos) -> {
            if (scene.getBlockState(pos).isAir()) return null;
            return fullCubeHit(pos, context.start(), context.end());
        };
    }

    private static DamageUtils.RaycastFactory upperBoundFactory(BlockGetter world, List<Vec> ignored) {
        return (context, pos) -> {
            BlockState state = world.getBlockState(pos);
            if (state.getBlock().getExplosionResistance() < CombatDamageAdapter.METEOR_BLAST_RESISTANCE_FLOOR) {
                return null;
            }
            BlockHitResult clipped = state.getCollisionShape(world, pos).clip(context.start(), context.end(), pos);
            if (clipped == null) return null;
            return new BlockHitResult(clipped.getLocation(), Direction.UP, pos, false);
        };
    }

    private static BlockHitResult fullCubeHit(BlockPos pos, Vec3 start, Vec3 end) {
        Optional<Vec3> clip = new AABB(pos).clip(start, end);
        return clip.map(point -> new BlockHitResult(point, Direction.UP, pos, false)).orElse(null);
    }
}
