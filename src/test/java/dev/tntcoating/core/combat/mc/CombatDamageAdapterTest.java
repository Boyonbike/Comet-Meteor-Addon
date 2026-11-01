/*
 * Original Comet implementation by Boyonabike.
 * Independent: tests written against the project's own interfaces; no third-party code.
 * SPDX-License-Identifier: CC0-1.0
 */

package dev.tntcoating.core.combat.mc;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.tntcoating.core.combat.Box;
import dev.tntcoating.core.combat.Cell;
import dev.tntcoating.core.combat.CombatDifficulty;
import dev.tntcoating.core.combat.ExposurePolicy;
import dev.tntcoating.core.combat.MitigationState;
import dev.tntcoating.core.combat.Vec;
import java.util.List;
import net.minecraft.world.level.block.Blocks;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/** Exposure policies, overlay substitution and the conservative bound helpers. */
final class CombatDamageAdapterTest {
    @BeforeAll
    static void bootstrap() {
        AdapterTestSupport.bootstrap();
    }

    private static final Vec EYE = new Vec(0.5, 64.5, 0.5);
    private static final Box TARGET = AdapterTestSupport.playerBox(new Vec(2.5, 64.0, 0.5));

    private static MitigationState bare() {
        return new MitigationState(CombatDifficulty.NORMAL, 0, 0, 0, 0, false, false, false);
    }

    @Test
    void lowerAndUpperPoliciesBracketAFaithfulRaycast() {
        CombatDamageAdapter adapter = new CombatDamageAdapter();
        AdapterTestSupport.FakeWorld world = new AdapterTestSupport.FakeWorld();
        world.set(1, 64, 0, Blocks.STONE.defaultBlockState());
        world.set(1, 65, 0, Blocks.STONE.defaultBlockState());

        double faithful = adapter.exposure(world, EYE, TARGET, ExposurePolicy.FAITHFUL, List.of());
        double lower = adapter.exposure(world, EYE, TARGET, ExposurePolicy.TARGET_LOWER_BOUND,
            List.of());
        double upper = adapter.exposure(world, EYE, TARGET,
            ExposurePolicy.SELF_FRIEND_UPPER_BOUND, List.of());
        assertEquals(0.0, faithful, 1.0e-9);
        assertEquals(0.0, lower, 1.0e-9);
        assertEquals(1.0, upper, 1.0e-9);
    }

    @Test
    void plannedCoverNeverReducesSelfOrFriendUpperBound() {
        CombatDamageAdapter adapter = new CombatDamageAdapter();
        AdapterTestSupport.FakeWorld world = new AdapterTestSupport.FakeWorld();
        List<Cell> wall = List.of(new Cell(1, 64, 0), new Cell(1, 65, 0));
        assertEquals(1.0, adapter.exposure(world, EYE, TARGET, ExposurePolicy.SELF_FRIEND_UPPER_BOUND,
            List.of()), 1.0e-9);
        assertEquals(1.0, adapter.exposure(world, EYE, TARGET,
            ExposurePolicy.SELF_FRIEND_UPPER_BOUND, wall), 1.0e-9);
    }

    @Test
    void degenerateTargetHullFailsClosedPerPolicy() {
        CombatDamageAdapter adapter = new CombatDamageAdapter();
        AdapterTestSupport.FakeWorld world = new AdapterTestSupport.FakeWorld();
        Box flat = new Box(new Vec(2.5, 64.0, 0.5), new Vec(2.5, 64.0, 0.5));
        assertEquals(0.0, adapter.exposure(world, EYE, flat, ExposurePolicy.TARGET_LOWER_BOUND,
            List.of()), 1.0e-9);
        assertEquals(1.0, adapter.exposure(world, EYE, flat,
            ExposurePolicy.SELF_FRIEND_UPPER_BOUND, List.of()), 1.0e-9);
    }
}
