/*
 * Original Comet implementation by Boyonabike.
 * Independent: written from the pinned 26.2 artifact and Meteor build 35 behaviour; no third-party code.
 * SPDX-License-Identifier: CC0-1.0
 */

package dev.tntcoating.core.combat;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;

/** Phase 2b carry-overs B and 10: the production boundary refuses weak safety and experimental features. */
class CombatProfilesTest {
    @Test
    void conservativeValuesArePinned() {
        Safety safety = CombatProfiles.CONSERVATIVE_SAFETY;
        assertEquals(4.0, safety.minTarget(), 1e-9);
        assertEquals(6.0, safety.maxSelf(), 1e-9);
        assertEquals(2.0, safety.minRemaining(), 1e-9);
        assertEquals(2.0, safety.maxFriend(), 1e-9);
        assertEquals(CombatDamagePolicy.conservativeSafety(), safety);
    }

    @Test
    void productionAcceptsConservativeAndStricterProfiles() {
        assertDoesNotThrow(() -> CombatProfiles.productionSafety(CombatProfiles.CONSERVATIVE_SAFETY));
        assertDoesNotThrow(() -> CombatProfiles.productionSafety(new Safety(5.0, 5.0, 3.0, 1.0)));
    }

    @Test
    void productionAcceptsRaisedSelfCeilingUpToMeteorRange() {
        assertDoesNotThrow(() -> CombatProfiles.productionSafety(new Safety(4.0, 14.0, 2.0, 2.0)));
        assertDoesNotThrow(() -> CombatProfiles.productionSafety(
            new Safety(4.0, CombatDamagePolicy.MAX_SELF_DAMAGE_CEILING, 2.0, 2.0)));
    }

    @Test
    void productionRejectsSelfCeilingAboveMeteorRange() {
        assertThrows(IllegalArgumentException.class,
            () -> CombatProfiles.productionSafety(new Safety(4.0, 36.5, 2.0, 2.0)));
    }

    @Test
    void productionRejectsWeakerMargin() {
        assertThrows(IllegalArgumentException.class,
            () -> CombatProfiles.productionSafety(new Safety(4.0, 6.0, 1.5, 2.0)));
    }

    @Test
    void productionRejectsWeakerTargetFloor() {
        assertThrows(IllegalArgumentException.class,
            () -> CombatProfiles.productionSafety(new Safety(3.0, 6.0, 2.0, 2.0)));
    }

    @Test
    void productionRejectsWeakerFriendCeiling() {
        assertThrows(IllegalArgumentException.class,
            () -> CombatProfiles.productionSafety(new Safety(4.0, 6.0, 2.0, 2.5)));
    }

    @Test
    void productionRejectsNonFiniteOrNegativeBounds() {
        assertThrows(IllegalArgumentException.class,
            () -> CombatProfiles.productionSafety(new Safety(Double.NaN, 6.0, 2.0, 2.0)));
        assertThrows(IllegalArgumentException.class,
            () -> CombatProfiles.productionSafety(new Safety(4.0, Double.POSITIVE_INFINITY, 2.0,
                2.0)));
        assertThrows(IllegalArgumentException.class,
            () -> CombatProfiles.productionSafety(new Safety(4.0, 6.0, -1.0, 2.0)));
        assertThrows(IllegalArgumentException.class,
            () -> CombatProfiles.productionSafety(null));
    }

    @Test
    void productionBudgetIsConservative() {
        CombatRiskBudget budget = CombatProfiles.conservativeBudget((envelope, current) -> current);
        assertEquals(0, budget.outstanding());
        assertEquals(CombatProfiles.CONSERVATIVE_SAFETY, CombatProfiles.CONSERVATIVE_SAFETY);
    }

    @Test
    void productionRefusesEnabledGroupedBound() {
        assertThrows(IllegalArgumentException.class, () -> CombatProfiles.profileBudget(
            (envelope, current) -> current, new GroupedBound(true),
            AnarchyQuietWindow.disabled()));
    }

    @Test
    void productionRefusesEnabledQuietWindow() {
        assertThrows(IllegalArgumentException.class, () -> CombatProfiles.profileBudget(
            (envelope, current) -> current, new GroupedBound(false),
            AnarchyQuietWindow.statistical(40, message -> { })));
    }

    @Test
    void productionAcceptsDisabledExperimentalFeatures() {
        CombatRiskBudget budget = assertDoesNotThrow(() -> CombatProfiles.profileBudget(
            (envelope, current) -> current, new GroupedBound(false),
            AnarchyQuietWindow.disabled()));
        assertEquals(0, budget.outstanding());
    }
}
