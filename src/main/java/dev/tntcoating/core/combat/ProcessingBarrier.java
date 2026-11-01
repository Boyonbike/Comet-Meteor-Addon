/*
 * Original Comet implementation by Boyonabike.
 * Independent: written from the pinned 26.2 artifact and Meteor build 35 behaviour; no third-party code.
 * SPDX-License-Identifier: CC0-1.0
 */

package dev.tntcoating.core.combat;

/**
 * Rule 3 of the safety model: retirement needs a processing barrier formed by the block-change
 * acknowledgement plus one server tick.
 *
 * <p><strong>This class is the strict never-satisfied marker.</strong> It reports {@code false} for
 * {@link RiskModel#STRICT} and {@link RiskModel#METEOR_STYLE}, where the ordering from acknowledgement to
 * health synchronisation remains unverified (audit C20) and no extra packet is allowed. The
 * {@link RiskModel#BARRIER} model implements its own bounded, explicitly-armed evidence path inside
 * {@link CombatRiskBudget}: one side-effect-free sequenced interaction, the exact acknowledgement plus one
 * server tick, and only for reservations whose explosion predates the probe and that saw no health change.
 * This class itself still has no send path and never becomes satisfied.
 */
public final class ProcessingBarrier {
    private ProcessingBarrier() {
    }

    /** Always {@code false}: the strict barrier is not implemented as a satisfiable gate. */
    public static boolean satisfied() {
        return false;
    }

    /** Human-readable explanation for logs and review notes. */
    public static String evidenceGap() {
        return "block-change acknowledgement to health-sync ordering is unverified (audit C20); the strict barrier is never satisfied";
    }
}
