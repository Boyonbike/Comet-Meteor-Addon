/*
 * Original Comet implementation by Boyonabike.
 * Independent: written from the pinned 26.2 artifact and Meteor build 35 behaviour; no third-party code.
 * SPDX-License-Identifier: CC0-1.0
 */

package dev.tntcoating.core.combat.mc;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CombatDispatchInvariantsTest {
    private static CombatRuntime.DispatchResult result(CombatRuntime.Status status,
                                                       CombatRuntime.Reason reason) {
        return new CombatRuntime.DispatchResult(status, reason, new CombatRuntime.DispatchTrace());
    }

    @Test
    void onlyFailedInvariantReasonsAreIncidents() {
        List<CombatRuntime.Reason> invariantReasons = List.of(
            CombatRuntime.Reason.NOT_CLIENT_THREAD,
            CombatRuntime.Reason.PLAN_MISMATCH,
            CombatRuntime.Reason.UNSUPPORTED_ACTION,
            CombatRuntime.Reason.ENTITY_MISMATCH,
            CombatRuntime.Reason.NO_LEASE,
            CombatRuntime.Reason.SLOT_SWITCH_FAILED,
            CombatRuntime.Reason.RISK_NOT_HELD,
            CombatRuntime.Reason.CAUSATIVE_NOT_ACCEPTED,
            CombatRuntime.Reason.SUPPORTING_PACKET_FAILED);
        for (CombatRuntime.Reason reason : invariantReasons) {
            assertTrue(CombatDispatchInvariants.isInvariantFailure(result(CombatRuntime.Status.FAILED, reason)),
                () -> "expected invariant failure for " + reason);
        }
    }

    @Test
    void ordinaryRejectionsAndSuccessfulSendsAreNotIncidents() {
        assertFalse(CombatDispatchInvariants.isInvariantFailure(
            result(CombatRuntime.Status.FAILED, CombatRuntime.Reason.OUT_OF_REACH)));
        assertFalse(CombatDispatchInvariants.isInvariantFailure(
            result(CombatRuntime.Status.FAILED, CombatRuntime.Reason.CONNECTION_CLOSED)));
        assertFalse(CombatDispatchInvariants.isInvariantFailure(
            result(CombatRuntime.Status.BLOCKED, CombatRuntime.Reason.SUSPENDED)));
        assertFalse(CombatDispatchInvariants.isInvariantFailure(
            result(CombatRuntime.Status.SENT, CombatRuntime.Reason.NONE)));
        assertFalse(CombatDispatchInvariants.isInvariantFailure(null));
    }

    @Test
    void labelNamesStatusAndReason() {
        String label = CombatDispatchInvariants.label(
            result(CombatRuntime.Status.FAILED, CombatRuntime.Reason.PLAN_MISMATCH));
        assertTrue(label.startsWith("incident: "));
        assertTrue(label.contains("dispatch-invariant"));
        assertTrue(label.contains("status=FAILED"));
        assertTrue(label.contains("reason=PLAN_MISMATCH"));
    }
}
