/*
 * Original Comet implementation by Boyonabike.
 * Independent: written from the pinned 26.2 artifact and Meteor build 35 behaviour; no third-party code.
 * SPDX-License-Identifier: CC0-1.0
 */

package dev.tntcoating.core.combat;

import java.util.Map;
import java.util.Objects;

/** Worst-case self and per-friend exposure from one planned detonation. */
public record ExposureBound(MitigatedDamage selfUpper, Map<EntityKey, MitigatedDamage> friendUpper,
                            MitigationEnvelope envelope) {
    public ExposureBound {
        Objects.requireNonNull(selfUpper, "selfUpper");
        Objects.requireNonNull(envelope, "envelope");
        friendUpper = friendUpper == null ? Map.of() : Map.copyOf(friendUpper);
    }
}
