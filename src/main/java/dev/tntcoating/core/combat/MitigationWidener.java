/*
 * Original Comet implementation by Boyonabike.
 * Independent: written from the pinned 26.2 artifact and Meteor build 35 behaviour; no third-party code.
 * SPDX-License-Identifier: CC0-1.0
 */

package dev.tntcoating.core.combat;

/**
 * Re-prices one outstanding {@link MitigatedDamage} against a newer protection envelope.
 *
 * <p>Plan adaptation: {@link MitigationEnvelope} carries only a revision, not the protection facts that
 * changed, so the concrete recalculation has to be injected. The budget applies the result only when the
 * envelope revision is newer, takes {@code max(current, widened)} so a bound can never narrow, and
 * suspends rather than keeping stale bounds when the widener is missing, throws, or returns an invalid
 * value.
 */
@FunctionalInterface
public interface MitigationWidener {
    MitigatedDamage widen(MitigationEnvelope envelope, MitigatedDamage current);
}
