/*
 * Original Comet implementation by Boyonabike.
 * Independent: written from the pinned 26.2 artifact and Meteor build 35 behaviour; no third-party code.
 * SPDX-License-Identifier: CC0-1.0
 */

package dev.tntcoating.core.combat;

import java.util.Map;

/**
 * Engine memory a single planning pass may consult. The attempts map counts attacks that may have left;
 * the preferred target is the hysteresis anchor; the tool-switch tick enforces the configured delay.
 */
public record DirectPlanState(
    long plan,
    long intentId,
    int tick,
    EntityKey preferredTarget,
    Map<EntityKey, Integer> breakAttempts,
    int lastToolSwitchTick
) {
    public DirectPlanState {
        if (intentId < 0) throw new IllegalArgumentException("intentId");
        if (tick < 0) throw new IllegalArgumentException("tick");
        if (breakAttempts == null) throw new IllegalArgumentException("breakAttempts");
        breakAttempts = Map.copyOf(breakAttempts);
    }
}
