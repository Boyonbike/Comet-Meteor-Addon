/*
 * Original Comet implementation by Boyonabike.
 * Independent: tests written against the project's own interfaces; no third-party code.
 * SPDX-License-Identifier: CC0-1.0
 */

package dev.tntcoating.core.combat.mc;

import static org.junit.jupiter.api.Assertions.assertEquals;

import dev.tntcoating.core.combat.CombatDamage;
import dev.tntcoating.core.combat.CombatDifficulty;
import dev.tntcoating.core.combat.MitigationState;
import dev.tntcoating.core.combat.RawDamage;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/** Pinned arithmetic: the unrounded vanilla explosion curve and its mitigation chain. */
final class CombatDamageAdapterPinnedTest {
    private static final double EPSILON = 1.0e-9;

    @BeforeAll
    static void bootstrap() {
        AdapterTestSupport.bootstrap();
    }

    @Test
    void pinnedRawExplosionCurve() {
        CombatDamageAdapter adapter = new CombatDamageAdapter();
        assertEquals(85.0, adapter.rawExplosionDamage(0.0, 1.0).value(), EPSILON);
        assertEquals(32.5, adapter.rawExplosionDamage(6.0, 1.0).value(), EPSILON);
        assertEquals(1.0, adapter.rawExplosionDamage(12.0, 1.0).value(), EPSILON);
        assertEquals(14.125, adapter.rawExplosionDamage(6.0, 0.5).value(), EPSILON);
        assertEquals(0.0, adapter.rawExplosionDamage(12.0001, 1.0).value(), EPSILON);
    }

    @Test
    void pinnedMitigationMatchesThePureChain() {
        CombatDamageAdapter adapter = new CombatDamageAdapter();
        MitigationState state = new MitigationState(CombatDifficulty.NORMAL, 20, 8, 0, 0,
            false, false, false);
        assertEquals(CombatDamage.mitigated(new RawDamage(85.0), state).beforeAbsorption(),
            adapter.mitigated(new RawDamage(85.0), dev.tntcoating.core.combat.MitigationView
                .of(state)).beforeAbsorption(), EPSILON);
    }

    @Test
    void exposureNeverEscapesTheUnitInterval() {
        CombatDamageAdapter adapter = new CombatDamageAdapter();
        AdapterTestSupport.FakeWorld world = new AdapterTestSupport.FakeWorld();
        double exposure = adapter.exposure(world, new dev.tntcoating.core.combat.Vec(
            0.5, 64.5, 0.5), AdapterTestSupport.playerBox(new dev.tntcoating.core.combat.Vec(
                2.5, 64.0, 0.5)), dev.tntcoating.core.combat.ExposurePolicy.FAITHFUL,
            java.util.List.of());
        assertEquals(1.0, exposure, EPSILON);
    }
}
