/*
 * Original Comet implementation by Boyonabike.
 * Independent: written from the pinned 26.2 artifact and Meteor build 35 behaviour; no third-party code.
 * SPDX-License-Identifier: CC0-1.0
 */

package dev.tntcoating.core.combat.mc;

import dev.tntcoating.core.combat.CombatIncidents;
import dev.tntcoating.core.combat.mc.CombatRuntime.DispatchResult;
import dev.tntcoating.core.combat.mc.CombatRuntime.Reason;
import dev.tntcoating.core.combat.mc.CombatRuntime.Status;

/**
 * Classifies dispatch failures that should never happen. Ordinary revalidation rejections (out of reach,
 * rotation not ready, connection closed) are not incidents; failures that indicate a broken invariant are.
 */
public final class CombatDispatchInvariants {
    private CombatDispatchInvariants() {
    }

    public static boolean isInvariantFailure(DispatchResult result) {
        if (result == null || result.status() != Status.FAILED) return false;
        Reason reason = result.reason();
        return switch (reason) {
            case NOT_CLIENT_THREAD,
                 PLAN_MISMATCH,
                 UNSUPPORTED_ACTION,
                 ENTITY_MISMATCH,
                 NO_LEASE,
                 SLOT_SWITCH_FAILED,
                 RISK_NOT_HELD,
                 CAUSATIVE_NOT_ACCEPTED,
                 SUPPORTING_PACKET_FAILED -> true;
            default -> false;
        };
    }

    public static String label(DispatchResult result) {
        return CombatIncidents.dispatchInvariantLabel(
            result.status().name(), result.reason().name());
    }
}
