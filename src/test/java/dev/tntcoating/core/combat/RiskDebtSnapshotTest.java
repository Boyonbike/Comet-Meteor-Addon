/*
 * Original Comet implementation by Boyonabike.
 * Independent: tests written against the project's own interfaces; no third-party code.
 * SPDX-License-Identifier: CC0-1.0
 */

package dev.tntcoating.core.combat;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

/** Stage 5 acceptance: the renderer's snapshot is a pure, read-only value. */
final class RiskDebtSnapshotTest {

    @Test
    void emptySnapshotRendersNothing() {
        assertFalse(RiskDebtSnapshot.empty().visible());
        assertTrue(RiskDebtSnapshot.empty().displayLines().isEmpty());
        assertTrue(RiskDebtSnapshot.empty().causes().isEmpty());
        assertEquals(0, RiskDebtSnapshot.empty().count());
        assertEquals(0.0, RiskDebtSnapshot.empty().bound(), 0.0);
    }

    @Test
    void debtShowsTheCountAndTheBound() {
        RiskDebtSnapshot snapshot = new RiskDebtSnapshot(2, 12.5, List.of());

        assertTrue(snapshot.visible());
        assertEquals(List.of("unresolved crystals: 2 (up to 12.5 damage)"), snapshot.displayLines());
    }

    @Test
    void aZeroCountWithABoundIsStillDebt() {
        RiskDebtSnapshot snapshot = new RiskDebtSnapshot(0, 0.5, List.of());

        assertTrue(snapshot.visible());
        assertEquals(List.of("unresolved crystals: 0 (up to 0.5 damage)"), snapshot.displayLines());
    }

    @Test
    void suspensionAloneIsDisplayedWithTheDebtLineAbsent() {
        RiskDebtSnapshot snapshot = new RiskDebtSnapshot(0, 0.0,
            List.of(SuspensionCause.SETBACK, SuspensionCause.SERVER_STALL));

        assertTrue(snapshot.visible());
        assertEquals(List.of("paused: rubber-banded, resuming shortly; server stalled"), snapshot.displayLines());
    }

    @Test
    void debtAndSuspensionAreBothDisplayedInStableOrder() {
        RiskDebtSnapshot snapshot = new RiskDebtSnapshot(1, 3.0, List.of(SuspensionCause.DEATH));

        assertEquals(List.of(
            "unresolved crystals: 1 (up to 3.0 damage)",
            "paused: dead, resumes after respawn"), snapshot.displayLines());
    }

    @Test
    void causesAreCopiedAndTheViewIsImmutable() {
        List<SuspensionCause> causes = new ArrayList<>(List.of(SuspensionCause.SETBACK));
        RiskDebtSnapshot snapshot = new RiskDebtSnapshot(1, 1.0, causes);

        causes.add(SuspensionCause.DEATH);

        assertEquals(List.of(SuspensionCause.SETBACK), snapshot.causes());
        assertThrows(UnsupportedOperationException.class,
            () -> snapshot.causes().add(SuspensionCause.DEATH));
    }

    @Test
    void invalidValuesAreRejected() {
        assertThrows(IllegalArgumentException.class, () -> new RiskDebtSnapshot(-1, 0.0, List.of()));
        assertThrows(IllegalArgumentException.class, () -> new RiskDebtSnapshot(0, -0.1, List.of()));
        assertThrows(IllegalArgumentException.class,
            () -> new RiskDebtSnapshot(0, Double.NaN, List.of()));
        assertThrows(IllegalArgumentException.class,
            () -> new RiskDebtSnapshot(0, Double.POSITIVE_INFINITY, List.of()));
        assertThrows(NullPointerException.class, () -> new RiskDebtSnapshot(0, 0.0, null));
        assertThrows(NullPointerException.class,
            () -> new RiskDebtSnapshot(0, 0.0, java.util.Arrays.asList(SuspensionCause.DEATH, null)));
    }
}
