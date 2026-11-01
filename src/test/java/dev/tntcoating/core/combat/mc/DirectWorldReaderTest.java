/*
 * Original Comet implementation by Boyonabike.
 * Independent: tests written against the project's own interfaces; no third-party code.
 * SPDX-License-Identifier: CC0-1.0
 */

package dev.tntcoating.core.combat.mc;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.tntcoating.core.combat.ArmorPiece;
import dev.tntcoating.core.combat.Box;
import dev.tntcoating.core.combat.Cell;
import dev.tntcoating.core.combat.CombatDamage;
import dev.tntcoating.core.combat.CombatDifficulty;
import dev.tntcoating.core.combat.ExposurePolicy;
import dev.tntcoating.core.combat.MitigatedDamage;
import dev.tntcoating.core.combat.MitigationState;
import dev.tntcoating.core.combat.MitigationView;
import dev.tntcoating.core.combat.RawDamage;
import dev.tntcoating.core.combat.Vec;
import java.util.List;
import net.minecraft.world.level.block.Blocks;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/** Direct-world damage reads against a scripted world: exposure policies and mitigation envelopes. */
final class DirectWorldReaderTest {
    @BeforeAll
    static void bootstrap() {
        AdapterTestSupport.bootstrap();
    }

    private static MitigationState bare() {
        return new MitigationState(CombatDifficulty.NORMAL, 0, 0, 0, 0, false, false, false);
    }

    private static MitigationView view(MitigationState state, List<ArmorPiece> pieces,
                                       boolean effectsMayExpire) {
        return new MitigationView() {
            @Override
            public MitigationState current() {
                return state;
            }

            @Override
            public List<ArmorPiece> pieces() {
                return pieces;
            }

            @Override
            public boolean effectsMayExpire() {
                return effectsMayExpire;
            }
        };
    }

    @Test
    void upperBoundUsesTheWeakestEnvelopeNotTheCurrentMitigation() {
        CombatDamageAdapter adapter = new CombatDamageAdapter();
        MitigationState current = new MitigationState(CombatDifficulty.NORMAL, 20, 8, 0, 0,
            false, false, false);
        List<ArmorPiece> breakable = List.of(new ArmorPiece(20, 8, 0, true));
        MitigatedDamage currentDamage = adapter.mitigated(new RawDamage(100),
            MitigationView.of(current));
        MitigatedDamage upper = adapter.upperBound(new RawDamage(100),
            view(current, breakable, true));
        assertTrue(upper.beforeAbsorption() > currentDamage.beforeAbsorption(),
            "a breakable chestplate must raise the upper bound");
    }

    @Test
    void stableArmourDoesNotInflateTheBound() {
        CombatDamageAdapter adapter = new CombatDamageAdapter();
        MitigationState stable = new MitigationState(CombatDifficulty.NORMAL, 20, 8, 0, 0,
            false, false, false);
        List<ArmorPiece> pieces = List.of(new ArmorPiece(20, 8, 0, false));
        assertEquals(stable, CombatDamage.weakestEnvelope(stable, pieces, false));
        assertEquals(adapter.mitigated(new RawDamage(100), MitigationView.of(stable))
                .beforeAbsorption(),
            adapter.upperBound(new RawDamage(100), view(stable, pieces, false))
                .beforeAbsorption(), 1.0e-9);
    }

    @Test
    void faithfulExposureIsOneInOpenAir() {
        CombatDamageAdapter adapter = new CombatDamageAdapter();
        AdapterTestSupport.FakeWorld world = new AdapterTestSupport.FakeWorld();
        Box target = AdapterTestSupport.playerBox(new Vec(2.5, 64.0, 0.5));
        double exposure = adapter.exposure(world, new Vec(0.5, 64.5, 0.5), target,
            ExposurePolicy.FAITHFUL, List.of());
        assertEquals(1.0, exposure, 1.0e-9);
    }

    @Test
    void astralOverlayCellsSubstituteObsidianForExposure() {
        CombatDamageAdapter adapter = new CombatDamageAdapter();
        AdapterTestSupport.FakeWorld world = new AdapterTestSupport.FakeWorld();
        Vec eye = new Vec(0.5, 64.5, 0.5);
        Box target = AdapterTestSupport.playerBox(new Vec(2.5, 64.0, 0.5));
        List<Cell> wall = List.of(new Cell(1, 64, 0), new Cell(1, 65, 0));

        assertEquals(1.0, adapter.exposure(world, eye, target, ExposurePolicy.FAITHFUL,
            List.of()), 1.0e-9);
        assertEquals(0.0, adapter.exposure(world, eye, target, ExposurePolicy.FAITHFUL, wall),
            1.0e-9);
        assertEquals(0.0, adapter.exposure(world, eye, target,
            ExposurePolicy.SELF_FRIEND_UPPER_BOUND, wall), 1.0e-9);
    }

    @Test
    void aRealBlockLowersFaithfulExposureButNotAThinWallPolicy() {
        CombatDamageAdapter adapter = new CombatDamageAdapter();
        AdapterTestSupport.FakeWorld world = new AdapterTestSupport.FakeWorld();
        world.set(1, 64, 0, Blocks.OBSIDIAN.defaultBlockState());
        world.set(1, 65, 0, Blocks.OBSIDIAN.defaultBlockState());
        Vec eye = new Vec(0.5, 64.5, 0.5);
        Box target = AdapterTestSupport.playerBox(new Vec(2.5, 64.0, 0.5));
        assertEquals(0.0, adapter.exposure(world, eye, target, ExposurePolicy.FAITHFUL,
            List.of()), 1.0e-9);
        assertEquals(0.0, adapter.exposure(world, eye, target,
            ExposurePolicy.TARGET_LOWER_BOUND, List.of()), 1.0e-9);
    }

    @Test
    void rawExplosionDamageFallsToZeroBeyondThePowerRadius() {
        CombatDamageAdapter adapter = new CombatDamageAdapter();
        assertEquals(0.0, adapter.rawExplosionDamage(12.5, 1.0).value(), 1.0e-9);
        assertTrue(adapter.rawExplosionDamage(4.0, 1.0).value() > 0.0);
    }
}
