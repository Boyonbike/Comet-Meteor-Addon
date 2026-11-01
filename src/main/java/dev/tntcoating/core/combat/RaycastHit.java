/*
 * Original Comet implementation by Boyonabike.
 * Independent: written from the pinned 26.2 artifact and Meteor build 35 behaviour; no third-party code.
 * SPDX-License-Identifier: CC0-1.0
 */

package dev.tntcoating.core.combat;

import java.util.Objects;

/**
 * One ray result as the exposure sampler sees it. {@code point} is the clip point when blocked and the ray
 * end point when clear, mirroring vanilla {@code BlockHitResult#getLocation()} semantics.
 */
public record RaycastHit(boolean blocked, Vec point) {
    public RaycastHit {
        Objects.requireNonNull(point, "point");
    }

    public static RaycastHit clear(Vec end) {
        return new RaycastHit(false, end);
    }

    public static RaycastHit blockedAt(Vec point) {
        return new RaycastHit(true, point);
    }
}
