/*
 * Original Comet implementation by Boyonabike.
 * Independent: written from the pinned 26.2 artifact and Meteor build 35 behaviour; no third-party code.
 * SPDX-License-Identifier: CC0-1.0
 */

package dev.tntcoating.core.combat;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.Test;

class GroupedBoundTest {
    private static final ServerTickInterval WINDOW = new ServerTickInterval(0L, 5L);

    private static GroupedBound.Member member(double bound, ServerTickInterval arrival,
                                              long revision, boolean proofs) {
        return new GroupedBound.Member(bound, arrival, revision, proofs, proofs, proofs, proofs,
            proofs);
    }

    @Test
    void disabledPathIsAlwaysThePlainSum() {
        GroupedBound bound = new GroupedBound(false);
        Bound result = bound.bound(List.of(member(6.0, WINDOW, 1L, true),
            member(4.0, WINDOW, 1L, true)));
        assertEquals(10.0, result.value());
        assertEquals(2, result.components());
    }

    @Test
    void enabledPathGroupsWhenEveryObligationHolds() {
        GroupedBound bound = new GroupedBound(true);
        Bound result = bound.bound(List.of(member(6.0, new ServerTickInterval(0L, 2L), 1L, true),
            member(4.0, new ServerTickInterval(1L, 5L), 1L, true)));
        assertEquals(10.0, result.value());
        assertEquals(1, result.components());
    }

    @Test
    void enabledPathFallsBackWhenAnyProofIsMissing() {
        GroupedBound bound = new GroupedBound(true);
        Bound result = bound.bound(List.of(member(6.0, WINDOW, 1L, true),
            member(4.0, WINDOW, 1L, false)));
        assertEquals(10.0, result.value());
        assertEquals(2, result.components());
    }

    @Test
    void enabledPathFallsBackOnMixedEnvelopeRevisions() {
        GroupedBound bound = new GroupedBound(true);
        Bound result = bound.bound(List.of(member(6.0, WINDOW, 1L, true),
            member(4.0, WINDOW, 2L, true)));
        assertEquals(10.0, result.value());
        assertEquals(2, result.components());
    }

    @Test
    void enabledPathFallsBackWhenArrivalLeavesTheWindow() {
        GroupedBound bound = new GroupedBound(true, 4L);
        Bound result = bound.bound(List.of(member(6.0, new ServerTickInterval(0L, 2L), 1L, true),
            member(4.0, new ServerTickInterval(3L, 4L), 1L, true)));
        assertEquals(10.0, result.value());
        assertEquals(2, result.components());
    }

    @Test
    void singleMemberGroupsToItselfWhenProven() {
        GroupedBound bound = new GroupedBound(true);
        Bound result = bound.bound(List.of(member(6.0, WINDOW, 1L, true)));
        assertEquals(6.0, result.value());
        assertEquals(1, result.components());
    }

    @Test
    void emptyInputIsZero() {
        assertEquals(0.0, new GroupedBound(true).bound(List.of()).value());
    }

    @Test
    void constructorRejectsInvalidWindow() {
        assertThrows(IllegalArgumentException.class, () -> new GroupedBound(true, 0L));
    }

    @Test
    void defaultsKeepGroupingOff() {
        assertFalse(GroupedBound.DEFAULT_ENABLED);
        assertEquals(10L, GroupedBound.DEFAULT_WINDOW_TICKS);
        assertEquals(0.0, new GroupedBound(GroupedBound.DEFAULT_ENABLED).bound(List.of()).value());
        assertTrue(new GroupedBound(GroupedBound.DEFAULT_ENABLED).enabled() == false);
    }
}
