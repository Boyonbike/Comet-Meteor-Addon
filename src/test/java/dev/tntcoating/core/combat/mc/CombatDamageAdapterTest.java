/*
 * Original Comet implementation by Boyonabike.
 * Independent: written from the pinned 26.2 artifact and Meteor build 35 behaviour; no third-party code.
 * SPDX-License-Identifier: CC0-1.0
 */

package dev.tntcoating.core.combat.mc;

import dev.tntcoating.core.combat.ArmorPiece;
import dev.tntcoating.core.combat.Box;
import dev.tntcoating.core.combat.ConnectionKey;
import dev.tntcoating.core.combat.EntityKey;
import dev.tntcoating.core.combat.CombatDamage;
import dev.tntcoating.core.combat.CombatDifficulty;
import dev.tntcoating.core.combat.ExposureInterval;
import dev.tntcoating.core.combat.ExposurePolicy;
import dev.tntcoating.core.combat.ExposureView;
import dev.tntcoating.core.combat.MitigatedDamage;
import dev.tntcoating.core.combat.MitigationState;
import dev.tntcoating.core.combat.MitigationView;
import dev.tntcoating.core.combat.RawDamage;
import dev.tntcoating.core.combat.Vec;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.VoxelShape;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Policy semantics of the adapter against an in-memory pinned world. */
class CombatDamageAdapterTest {
    static {
        AdapterTestSupport.bootstrap();
    }

    private static final CombatDamageAdapter ADAPTER = new CombatDamageAdapter();
    private static final Vec SOURCE = new Vec(0.5, 0.5, 0.5);
    private static final Vec FEET = new Vec(0.5, 1.0, 0.5);

    private static AdapterTestSupport.FakeWorld world() {
        return new AdapterTestSupport.FakeWorld();
    }

    @Test
    void openWorldExposureIsOneForEveryPolicy() {
        AdapterTestSupport.FakeWorld world = world();
        var box = AdapterTestSupport.playerBox(0.5, 1.0, 0.5);
        for (ExposurePolicy policy : ExposurePolicy.values()) {
            assertEquals(1.0, ADAPTER.exposure(world, SOURCE, box, policy, List.of()), 1.0E-9,
                "open world must expose fully under " + policy);
        }
    }

    @Test
    void faithfulRaycastIsBlockedByFullCover() {
        AdapterTestSupport.FakeWorld world = world();
        world.set(0, 4, 0, Blocks.OBSIDIAN.defaultBlockState());
        var box = AdapterTestSupport.playerBox(0.5, 1.0, 0.5);
        Vec high = new Vec(0.5, 5.0, 0.5);
        assertEquals(0.0, ADAPTER.exposure(world, high, box, ExposurePolicy.FAITHFUL, List.of()), 1.0E-9);
        assertEquals(0.0, ADAPTER.exposure(world, high, box, ExposurePolicy.TARGET_LOWER_BOUND, List.of()),
            1.0E-9);
        assertEquals(0.0, ADAPTER.exposure(world, high, box, ExposurePolicy.SELF_FRIEND_UPPER_BOUND,
            List.of()), 1.0E-9);
    }

    @Test
    void upperBoundPolicyIgnoresStoneThatFaithfulHonours() {
        AdapterTestSupport.FakeWorld world = world();
        world.set(0, 4, 0, Blocks.STONE.defaultBlockState());
        var box = AdapterTestSupport.playerBox(0.5, 1.0, 0.5);
        Vec high = new Vec(0.5, 5.0, 0.5);
        assertEquals(0.0, ADAPTER.exposure(world, high, box, ExposurePolicy.FAITHFUL, List.of()), 1.0E-9);
        assertEquals(0.0, ADAPTER.exposure(world, high, box, ExposurePolicy.TARGET_LOWER_BOUND, List.of()),
            1.0E-9);
        assertEquals(1.0, ADAPTER.exposure(world, high, box, ExposurePolicy.SELF_FRIEND_UPPER_BOUND,
            List.of()), 1.0E-9, "the self/friend bound must never be lowered by low-resistance cover");
    }

    @Test
    void lowerBoundTreatsThinNonAirBlocksAsCover() {
        AdapterTestSupport.FakeWorld world = world();
        world.set(0, 4, 0, Blocks.LADDER.defaultBlockState());
        var box = AdapterTestSupport.playerBox(0.5, 1.0, 0.5);
        Vec high = new Vec(0.5, 5.0, 0.5);
        double faithful = ADAPTER.exposure(world, high, box, ExposurePolicy.FAITHFUL, List.of());
        double lower = ADAPTER.exposure(world, high, box, ExposurePolicy.TARGET_LOWER_BOUND, List.of());
        assertTrue(faithful > 0.0, "a thin ladder must not be counted as full cover by the faithful policy");
        assertEquals(0.0, lower, 1.0E-9, "uncertain cover must be treated as blocking for target lower bounds");
    }

    @Test
    void overlayBlocksRaysWithoutMutatingTheWorld() {
        AdapterTestSupport.FakeWorld world = world();
        var box = AdapterTestSupport.playerBox(0.5, 1.0, 0.5);
        assertEquals(1.0, ADAPTER.exposure(world, SOURCE, box, ExposurePolicy.FAITHFUL, List.of()), 1.0E-9);
        double withOverlay = ADAPTER.exposure(world, SOURCE, box, ExposurePolicy.FAITHFUL,
            List.of(AdapterTestSupport.cell(0, 0, 0)));
        assertEquals(0.0, withOverlay, 1.0E-9);
        assertTrue(world.raw(0, 0, 0).isAir(), "a virtual overlay must never be written to the client world");
    }

    @Test
    void rawDamageIsUnroundedAndGuardsPastThePower() {
        double raw = ADAPTER.rawExplosionDamage(4.0, 1.0).value();
        assertEquals(CombatDamage.rawExplosionDamage(4.0, 1.0, CombatDamageAdapter.CRYSTAL_POWER).value(), raw, 0.0);
        assertTrue(raw > 47.0, "the raw value must not be truncated to an int (audit C18): " + raw);
        assertEquals(0.0, ADAPTER.rawExplosionDamage(CombatDamageAdapter.CRYSTAL_POWER + 0.5, 1.0).value(), 0.0);
    }

    @Test
    void rawDamageFromTheWorldUsesFeetDistanceAndFaithfulExposure() {
        AdapterTestSupport.FakeWorld world = world();
        var box = AdapterTestSupport.playerBox(FEET);
        RawDamage raw = ADAPTER.rawExplosionDamage(world, SOURCE,
            new Vec(0.5, 1.0, 0.5), box, List.of());
        assertEquals(CombatDamage.rawExplosionDamage(0.5, 1.0, CombatDamageAdapter.CRYSTAL_POWER).value(), raw.value(),
            1.0E-12);
    }

    @Test
    void mitigationNeverSubtractsAbsorption() {
        MitigationState state = new MitigationState(CombatDifficulty.NORMAL, 20.0, 8.0, 4.0, 0,
            false, false, false);
        RawDamage raw = new RawDamage(60.0);
        MitigatedDamage mitigated = ADAPTER.mitigated(raw, state);
        // float-precision agreement: the pinned CombatRules path computes in float, the pure model in double.
        assertEquals(CombatDamage.mitigated(raw, state).beforeAbsorption(), mitigated.beforeAbsorption(), 1.0E-4);
        // Absorption lives only in EffectiveHealth; the same state must give the same number twice.
        assertEquals(mitigated.beforeAbsorption(), ADAPTER.mitigated(raw, state).beforeAbsorption(), 0.0);
    }

    @Test
    void upperBoundFollowsTheWeakestEnvelope() {
        MitigationState current = new MitigationState(CombatDifficulty.NORMAL, 20.0, 8.0, 4.0, 1,
            false, false, false);
        MitigationView view = new MitigationView() {
            @Override
            public MitigationState current() {
                return current;
            }

            @Override
            public List<ArmorPiece> pieces() {
                return List.of(new ArmorPiece(20.0, 8.0, 4.0, true));
            }

            @Override
            public boolean effectsMayExpire() {
                return true;
            }
        };
        RawDamage raw = new RawDamage(60.0);
        assertEquals(CombatDamage.upperBound(raw, ADAPTER.envelope(view)).beforeAbsorption(),
            ADAPTER.upperBound(raw, view).beforeAbsorption(), 0.0);
        assertTrue(ADAPTER.upperBound(raw, view).beforeAbsorption() > ADAPTER.mitigated(raw, view)
            .beforeAbsorption(), "breaking armour and expiring effects must widen the upper bound");
    }

    @Test
    void sweptExposureBracketsEveryPair() {
        AdapterTestSupport.FakeWorld world = world();
        world.set(0, 1, 2, Blocks.STONE.defaultBlockState());
        world.set(0, 2, 2, Blocks.STONE.defaultBlockState());
        List<Vec> crystals = List.of(new Vec(0.5, 1.5, 4.5), new Vec(0.5, 2.5, 4.5));
        List<dev.tntcoating.core.combat.Box> victims = List.of(
            AdapterTestSupport.playerBox(0.5, 1.0, 0.5),
            AdapterTestSupport.playerBox(0.5, 2.0, 0.5));
        ExposureInterval interval = ADAPTER.sweptExposure(world, crystals, victims, List.of());
        double expectedLower = 1.0;
        double expectedUpper = 0.0;
        for (Vec crystal : crystals) {
            for (var box : victims) {
                expectedLower = Math.min(expectedLower,
                    ADAPTER.exposure(world, crystal, box, ExposurePolicy.TARGET_LOWER_BOUND, List.of()));
                expectedUpper = Math.max(expectedUpper,
                    ADAPTER.exposure(world, crystal, box, ExposurePolicy.SELF_FRIEND_UPPER_BOUND, List.of()));
            }
        }
        assertEquals(expectedLower, interval.lower(), 0.0);
        assertEquals(expectedUpper, interval.upper(), 0.0);
        assertTrue(interval.lower() <= interval.upper());
    }

    @Test
    void sweptExposureRejectsEmptyInputs() {
        AdapterTestSupport.FakeWorld world = world();
        var box = AdapterTestSupport.playerBox(0.5, 1.0, 0.5);
        assertThrows(IllegalArgumentException.class,
            () -> ADAPTER.sweptExposure(world, List.of(), List.of(box), List.of()));
        assertThrows(IllegalArgumentException.class,
            () -> ADAPTER.sweptExposure(world, List.of(new Vec(0.5, 0.5, 0.5)), List.of(), List.of()));
    }

    @Test
    void degenerateTargetBoxDoesNotSpinAndStaysOnTheSafeSideOfEveryBound() {
        var world = world();
        var point = new dev.tntcoating.core.combat.Box(new Vec(0.5, 1.0, 0.5), new Vec(0.5, 1.0, 0.5));
        assertEquals(1.0, ADAPTER.exposure(world, SOURCE, point, ExposurePolicy.FAITHFUL, List.of()), 1.0E-9,
            "a degenerate box samples its centre once; the only ray is unobstructed in an open world");
        assertEquals(0.0, ADAPTER.exposure(world, SOURCE, point, ExposurePolicy.TARGET_LOWER_BOUND, List.of()),
            1.0E-9, "the pinned sampler would spin forever on a zero step; the target lower bound stays pessimistic");
        assertEquals(1.0, ADAPTER.exposure(world, SOURCE, point, ExposurePolicy.SELF_FRIEND_UPPER_BOUND,
            List.of()), 1.0E-9, "the self/friend upper bound must not be lowered by the degenerate-box fallback");
    }

    @Test
    void perFriendExposureIsReturnedAsAnUpperBoundMap() {
        AdapterTestSupport.FakeWorld world = world();
        Box near = AdapterTestSupport.playerBox(0.5, 1.0, 2.5);
        Box far = AdapterTestSupport.playerBox(0.5, 1.0, 5.0);
        EntityKey nearKey = new EntityKey(new ConnectionKey(1L), 2, UUID.randomUUID());
        EntityKey farKey = new EntityKey(new ConnectionKey(1L), 3, UUID.randomUUID());
        Map<EntityKey, Box> friends = new LinkedHashMap<>();
        friends.put(nearKey, near);
        friends.put(farKey, far);

        Map<EntityKey, MitigatedDamage> bounds = ADAPTER.friendUpperBounds(world,
            List.of(new Vec(0.5, 1.5, 0.5)), friends, new MitigationState(CombatDifficulty.NORMAL, 0.0,
                0.0, 0.0, 0, false, false, false), List.of());

        assertEquals(2, bounds.size());
        assertTrue(bounds.get(nearKey).beforeAbsorption() > bounds.get(farKey).beforeAbsorption(),
            "the nearer friend must carry the larger upper bound: " + bounds);
        assertTrue(bounds.get(farKey).beforeAbsorption() > 0.0, "in range and unobstructed: " + bounds);
        assertThrows(IllegalArgumentException.class, () -> ADAPTER.friendUpperBounds(world, List.of(),
            friends, new MitigationState(CombatDifficulty.NORMAL, 0.0, 0.0, 0.0, 0, false, false, false),
            List.of()));
    }

    @Test
    void friendUpperBoundsUsesTheFeetNotTheMinimumCorner() {
        AdapterTestSupport.FakeWorld world = world();
        Box friend = AdapterTestSupport.playerBox(0.5, 1.0, 0.5);
        Vec crystal = new Vec(0.9, 2.7, 0.9);
        Vec feet = new Vec(friend.center().x(), friend.min().y(), friend.center().z());
        assertTrue(friend.min().distance(crystal) > feet.distance(crystal) + 0.1,
            "fixture precondition: the box floor corner must be measurably farther than the feet");

        EntityKey key = new EntityKey(new ConnectionKey(1L), 4, UUID.randomUUID());
        MitigationState state = new MitigationState(CombatDifficulty.NORMAL, 0.0, 0.0, 0.0, 0,
            false, false, false);
        double exposure = ADAPTER.exposure(world, crystal, friend,
            ExposurePolicy.SELF_FRIEND_UPPER_BOUND, List.of());
        double expected = ADAPTER.mitigated(
            ADAPTER.rawExplosionDamage(feet.distance(crystal), exposure), state).beforeAbsorption();

        double actual = ADAPTER.friendUpperBounds(world, List.of(crystal), Map.of(key, friend), state, List.of())
            .get(key).beforeAbsorption();

        assertEquals(expected, actual, 1.0E-9,
            "the upper bound must use the entity's feet (box centre x/z, minimum y), not the min corner");
    }

    @Test
    void exposureSamplerRunsOnThePureViewSeam() {
        Box box = AdapterTestSupport.playerBox(0.5, 1.0, 0.5);
        assertEquals(1.0, ADAPTER.exposure(ExposureView.open(), new Vec(0.5, 0.5, 0.5), box,
            ExposurePolicy.TARGET_LOWER_BOUND), 1.0E-9);
    }

    @Test
    void raycastResultCarriesTheBlockedPoint() {
        AdapterTestSupport.FakeWorld world = world();
        world.set(0, 2, 0, Blocks.OBSIDIAN.defaultBlockState());
        var hit = ADAPTER.raycast(world, new Vec(0.5, 1.0, 0.5), new Vec(0.5, 4.0, 0.5),
            ExposurePolicy.FAITHFUL, List.of());
        assertTrue(hit.blocked());
        assertEquals(2.0, hit.point().y(), 1.0E-9);
    }

    @Test
    void upperBoundIgnoresAHighResistanceBlockWhoseCollisionShapeMissesTheRay() {
        AdapterTestSupport.FakeWorld world = world();
        BlockState table = Blocks.ENCHANTING_TABLE.defaultBlockState();
        world.set(0, 0, 0, table);

        // Preconditions taken from the pinned artifact: the enchanting table is at or above the 600
        // blast-resistance floor but its collision shape is only 0.75 blocks tall, so it is not full cover.
        assertTrue(table.getBlock().getExplosionResistance()
                >= CombatDamageAdapter.METEOR_BLAST_RESISTANCE_FLOOR,
            "fixture precondition: block must be at or above the upper-bound cover floor");
        VoxelShape shape = table.getCollisionShape(world, BlockPos.ZERO);
        assertFalse(shape.isEmpty(), "fixture precondition: the block must have a collision shape");
        assertTrue(shape.max(Direction.Axis.Y) < 0.9,
            "fixture precondition: the shape must end below the ray start, so the ray misses it");

        Vec from = new Vec(0.5, 0.9, 0.5);
        Vec to = new Vec(0.5, 4.0, 0.5);
        assertFalse(ADAPTER.raycast(world, from, to, ExposurePolicy.SELF_FRIEND_UPPER_BOUND, List.of())
                .blocked(),
            "a ray that misses the collision shape must not count the block as cover");

        world.set(0, 0, 0, Blocks.OBSIDIAN.defaultBlockState());
        assertTrue(ADAPTER.raycast(world, from, to, ExposurePolicy.SELF_FRIEND_UPPER_BOUND, List.of())
                .blocked(),
            "a full cube at or above the floor is cover when the ray hits it");
    }
}
