/*
 * Original Comet implementation by Boyonabike.
 * Independent: written from the pinned 26.2 artifact and Meteor build 35 behaviour; no third-party code.
 * SPDX-License-Identifier: CC0-1.0
 */

package dev.tntcoating.core.combat;

import java.util.Objects;

/**
 * Health plus absorption from one coherent applied snapshot, stamped with the revision it reflects.
 * {@code health + absorption} is the only place absorption is counted.
 */
public record EffectiveHealth(double health, double absorption, Capability cap, long revision) {
    public EffectiveHealth {
        Objects.requireNonNull(cap, "cap");
    }

    public double value() {
        return health + absorption;
    }
}
