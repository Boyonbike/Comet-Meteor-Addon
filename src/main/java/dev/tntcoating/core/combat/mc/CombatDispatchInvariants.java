/*
 * Original Comet implementation by Boyonabike.
 * Independent: written from the pinned 1.21.11 artifact and Meteor build 82 behaviour; no third-party code.
 * SPDX-License-Identifier: CC0-1.0
 */

package dev.tntcoating.core.combat.mc;

import dev.tntcoating.core.combat.CombatIncidents;
import dev.tntcoating.core.combat.mc.CombatRuntime.Reason;
import dev.tntcoating.core.combat.mc.CombatRuntime.Status;
import java.util.EnumSet;
import java.util.Set;

/**
 * Which failed dispatches are code-invariant violations rather than ordinary safety blocks. Only a FAILED
 * result whose reason is listed here means a planner/hook built something the runtime had to reject; a
 * BLOCKED result is the safety model working as intended.
 */
public final class CombatDispatchInvariants {
    private static final Set<Reason> INVARIANT_FAILURES = EnumSet.of(Reason.NOT_CLIENT_THREAD,
        Reason.PLAN_MISMATCH, Reason.UNSUPPORTED_ACTION, Reason.ENTITY_MISMATCH, Reason.NO_LEASE,
        Reason.SLOT_SWITCH_FAILED, Reason.RISK_NOT_HELD, Reason.CAUSATIVE_NOT_ACCEPTED,
        Reason.SUPPORTING_PACKET_FAILED);

    private CombatDispatchInvariants() {
    }

    public static boolean isInvariantFailure(CombatRuntime.DispatchResult result) {
        return result != null && result.status() == Status.FAILED
            && INVARIANT_FAILURES.contains(result.reason());
    }

    public static String label(CombatRuntime.DispatchResult result) {
        if (result == null) throw new IllegalArgumentException("result");
        return CombatIncidents.dispatchInvariantLabel(result.status().name(), result.reason().name());
    }
}
