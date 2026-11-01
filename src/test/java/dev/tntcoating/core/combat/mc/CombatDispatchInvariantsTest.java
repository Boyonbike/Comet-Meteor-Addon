/*
 * Original Comet implementation by Boyonabike.
 * Independent: written against the project's own interfaces; no third-party code.
 * SPDX-License-Identifier: CC0-1.0
 */

package dev.tntcoating.core.combat.mc;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.tntcoating.core.combat.mc.CombatRuntime.DispatchResult;
import dev.tntcoating.core.combat.mc.CombatRuntime.Reason;
import dev.tntcoating.core.combat.mc.CombatRuntime.Status;
import java.util.EnumSet;
import org.junit.jupiter.api.Test;

class CombatDispatchInvariantsTest {
    @Test
    void onlyFailedInvariantReasonsAreIncidents() {
        for (Reason reason : Reason.values()) {
            if (reason == Reason.NONE) continue;
            boolean expected = EnumSet.of(Reason.NOT_CLIENT_THREAD, Reason.PLAN_MISMATCH,
                Reason.UNSUPPORTED_ACTION, Reason.ENTITY_MISMATCH, Reason.NO_LEASE,
                Reason.SLOT_SWITCH_FAILED, Reason.RISK_NOT_HELD, Reason.CAUSATIVE_NOT_ACCEPTED,
                Reason.SUPPORTING_PACKET_FAILED).contains(reason);
            assertEquals(expected,
                CombatDispatchInvariants.isInvariantFailure(new DispatchResult(Status.FAILED, reason, new CombatRuntime.DispatchTrace())),
                reason.name());
        }
    }

    @Test
    void ordinaryRejectionsAndSuccessfulSendsAreNotIncidents() {
        assertFalse(CombatDispatchInvariants.isInvariantFailure(
            new DispatchResult(Status.BLOCKED, Reason.NOT_READY, new CombatRuntime.DispatchTrace())));
        assertFalse(CombatDispatchInvariants.isInvariantFailure(
            new DispatchResult(Status.BLOCKED, Reason.POLICY_REJECTED, new CombatRuntime.DispatchTrace())));
        assertFalse(CombatDispatchInvariants.isInvariantFailure(
            new DispatchResult(Status.SENT, Reason.NONE, new CombatRuntime.DispatchTrace())));
        assertFalse(CombatDispatchInvariants.isInvariantFailure(null));
    }

    @Test
    void labelNamesStatusAndReason() {
        String label = CombatDispatchInvariants.label(
            new DispatchResult(Status.FAILED, Reason.CAUSATIVE_NOT_ACCEPTED, new CombatRuntime.DispatchTrace()));
        assertTrue(label.contains("FAILED"), label);
        assertTrue(label.contains("CAUSATIVE_NOT_ACCEPTED"), label);
    }
}
