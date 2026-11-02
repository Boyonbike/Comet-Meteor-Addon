/*
 * Original Comet implementation by Boyonabike.
 * Independent: written from the pinned 26.2 artifact and Meteor build 35 behaviour; no third-party code.
 * SPDX-License-Identifier: CC0-1.0
 */

package dev.tntcoating.core.combat.mc;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.tntcoating.core.combat.ArmorPiece;
import dev.tntcoating.core.combat.Box;
import dev.tntcoating.core.combat.CombatDamage;
import dev.tntcoating.core.combat.CombatDifficulty;
import dev.tntcoating.core.combat.MitigatedDamage;
import dev.tntcoating.core.combat.MitigationState;
import dev.tntcoating.core.combat.MitigationView;
import dev.tntcoating.core.combat.RawDamage;
import dev.tntcoating.core.combat.Vec;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/**
 * Regression guard for the P3.1 safety rule that self and per-friend upper bounds must price the weakest
 * mitigation envelope: armour that could break before arrival is gone, and an expiring Resistance is gone.
 * Pricing {@code current()} understates the bound and can let a placement through that the safety model
 * rejects.
 */
class DirectWorldReaderTest {
    private static final RawDamage RAW = new RawDamage(100.0);
    private static final double EPSILON = 1.0e-9;
    private static final Path READER =
        Path.of("src/main/java/dev/tntcoating/core/combat/mc/DirectWorldReader.java");
    private static CombatDamageAdapter adapter;

    @BeforeAll
    static void bootstrap() {
        // CombatDamageAdapter's static damage source needs the pinned registry bootstrap; no game starts.
        AdapterTestSupport.bootstrap();
        adapter = new CombatDamageAdapter();
    }

    @Test
    void upperBoundPricesTheWeakestEnvelopeNotTheCurrentMitigation() {
        MitigationState current = new MitigationState(CombatDifficulty.NORMAL, 20.0, 8.0, 16.0,
            2, false, false, false);
        MitigationView view = new MitigationView() {
            @Override
            public MitigationState current() {
                return current;
            }

            @Override
            public List<ArmorPiece> pieces() {
                // The only armour could break before arrival, so the envelope loses all of it.
                return List.of(new ArmorPiece(20.0, 8.0, 16.0, true));
            }

            @Override
            public boolean effectsMayExpire() {
                return true;
            }
        };

        MitigatedDamage weakest = DirectWorldReader.upperBound(adapter, RAW, view);
        MitigatedDamage expected = CombatDamage.upperBound(RAW, CombatDamage.weakestEnvelope(
            view.current(), view.pieces(), view.effectsMayExpire()));
        MitigatedDamage currentOnly = CombatDamage.upperBound(RAW, view.current());

        assertEquals(expected.beforeAbsorption(), weakest.beforeAbsorption(), EPSILON,
            "self/friend upper bounds must use the weakest envelope");
        assertTrue(weakest.beforeAbsorption() > currentOnly.beforeAbsorption() + EPSILON,
            "breakable armour and expiring Resistance must raise the upper bound; old-vs-new "
                + currentOnly.beforeAbsorption() + " vs " + weakest.beforeAbsorption());
    }

    @Test
    void aStableArmourSetStillUsesTheEnvelopePathWithoutInflatingTheBound() {
        MitigationState current = new MitigationState(CombatDifficulty.NORMAL, 20.0, 8.0, 16.0,
            0, false, false, false);
        MitigationView view = new MitigationView() {
            @Override
            public MitigationState current() {
                return current;
            }

            @Override
            public List<ArmorPiece> pieces() {
                return List.of(new ArmorPiece(20.0, 8.0, 16.0, false));
            }
        };

        MitigatedDamage weakest = DirectWorldReader.upperBound(adapter, RAW, view);
        MitigatedDamage currentOnly = CombatDamage.upperBound(RAW, view.current());
        // Vanilla's armour absorption runs through a float parameter; allow its rounding here.
        assertEquals(currentOnly.beforeAbsorption(), weakest.beforeAbsorption(), 1.0e-4,
            "non-breakable armour and non-expiring effects must keep the current mitigation");
    }

    @Test
    void breakableThresholdIsTenPercentRemainingDurability() {
        assertFalse(DirectWorldReader.couldBreakBeforeArrival(0, 0));
        assertFalse(DirectWorldReader.couldBreakBeforeArrival(-1, 0));
        assertFalse(DirectWorldReader.couldBreakBeforeArrival(1000, 0), "100% remaining is safe");
        assertFalse(DirectWorldReader.couldBreakBeforeArrival(1000, 500), "50% remaining is safe");
        assertFalse(DirectWorldReader.couldBreakBeforeArrival(1000, 899), "10.1% remaining is safe");
        assertTrue(DirectWorldReader.couldBreakBeforeArrival(1000, 900), "10% remaining is breakable");
        assertTrue(DirectWorldReader.couldBreakBeforeArrival(1000, 999), "0.1% remaining is breakable");
        assertTrue(DirectWorldReader.couldBreakBeforeArrival(100, 100), "0% remaining is breakable");
    }

    /**
     * Source guard: the production upper-bound call sites must keep using the envelope helper. A future
     * edit that goes back to {@code current()} (or reimplements the bound locally) fails here.
     */
    @Test
    void productionCallSitesAlwaysGoThroughTheEnvelopeHelper() throws IOException {
        String source = Files.readString(READER);
        assertTrue(source.contains("return upperBound(damage, raw, mitigation(mc.player));"),
            "self upper bound must use the weakest-envelope helper");
        assertTrue(source.contains("upperBound(damage, raw, mitigation(player))"),
            "friend upper bounds must use the weakest-envelope helper");
        assertFalse(source.contains("CombatDamage.upperBound("),
            "reader must not price bounds with a caller-supplied mitigation state");
        assertFalse(source.contains("mitigation(mc.player).current()"),
            "self upper bound must never price current() directly");
        assertFalse(source.contains("mitigation(player).current()"),
            "friend upper bounds must never price current() directly");
        assertTrue(source.contains("worstRaw(damage, box, crystalCentre)"),
            "whole-hull worst bounds must use the full-exposure closest-point helper");
    }

    /**
     * Phase 4.2 source-of-truth for the whole-hull bound: the distance is to the true closest point on the
     * body box, never to the minimum corner (which would understate the bound on a diagonal approach).
     */
    @Test
    void closestDistanceUsesTheTrueClosestPointNotTheMinimumCorner() {
        Box body = new Box(new Vec(0, 0, 0), new Vec(1, 2, 1));
        Vec crystal = new Vec(10, 10, 10);

        double closest = DirectWorldReader.closestDistance(body, crystal);

        assertEquals(Math.sqrt(9.0 * 9.0 + 8.0 * 8.0 + 9.0 * 9.0), closest, EPSILON,
            "the closest point on the box is its maximum corner here");
        double minimumCorner = new Vec(0, 0, 0).distance(crystal);
        assertTrue(closest < minimumCorner - 1.0,
            "the minimum corner is farther away and must never be used as the bound distance");
    }

    @Test
    void worstRawIgnoresCoverAndPricesTheClampedDistanceAtFullExposure() {
        Box body = new Box(new Vec(0, 0, 0), new Vec(1, 2, 1));
        Vec crystal = new Vec(4, 5, 6);
        double distance = DirectWorldReader.closestDistance(body, crystal);

        RawDamage worst = DirectWorldReader.worstRaw(adapter, body, crystal);

        assertEquals(adapter.rawExplosionDamage(distance, 1.0).value(), worst.value(), EPSILON,
            "the worst raw must use full exposure at the true closest point");
        assertTrue(worst.value() > adapter.rawExplosionDamage(distance, 0.5).value(),
            "ignoring cover must never understate the blast");
        assertTrue(worst.value() > adapter.rawExplosionDamage(
            new Vec(0, 0, 0).distance(crystal), 1.0).value(),
            "the minimum corner would understate the bound on a diagonal approach");
    }

    @Test
    void aCrystalTheLedgerNeverSawReadsAsObservedAndNotOwn() {
        dev.tntcoating.core.combat.CombatSession session = new dev.tntcoating.core.combat.CombatSession();
        session.open();
        dev.tntcoating.core.combat.CrystalLedger ledger = new dev.tntcoating.core.combat.CrystalLedger();
        dev.tntcoating.core.combat.EntityKey key = new dev.tntcoating.core.combat.EntityKey(
            session.connection(), 42, java.util.UUID.nameUUIDFromBytes("untracked".getBytes()));
        assertEquals(dev.tntcoating.core.combat.CrystalState.OBSERVED, DirectWorldReader.crystalState(ledger, key));
        assertEquals(false, ledger.own(key));
    }

    @Test
    void breakRangeUsesAttackRangeInSightAndWallsRangeBehindBlocks() {
        assertEquals(5.0, DirectWorldReader.breakRange(true, 5.0, 0.0), 1.0e-9);
        assertEquals(0.0, DirectWorldReader.breakRange(false, 5.0, 0.0), 1.0e-9);
    }
}
