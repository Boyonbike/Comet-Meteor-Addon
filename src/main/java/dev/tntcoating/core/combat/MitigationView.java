/*
 * Original Comet implementation by Boyonabike.
 * Independent: written from the pinned 26.2 artifact and Meteor build 35 behaviour; no third-party code.
 * SPDX-License-Identifier: CC0-1.0
 */

package dev.tntcoating.core.combat;

import java.util.List;
import java.util.Objects;

/**
 * What the adapter is allowed to assume about one entity's protection at one instant, plus what could
 * deteriorate before the latest arrival. The adapter prices the current mitigation from {@link #current()} and
 * the weakest possible mitigation from {@link #pieces()} / {@link #effectsMayExpire()}.
 */
public interface MitigationView {
    MitigationState current();

    /** Currently worn pieces, each already judged by the caller as breakable-before-arrival or not. */
    default List<ArmorPiece> pieces() {
        return List.of();
    }

    /** True when a Resistance (or other mitigation) effect could expire before the latest arrival. */
    default boolean effectsMayExpire() {
        return false;
    }

    static MitigationView of(MitigationState current) {
        Objects.requireNonNull(current, "current");
        return new MitigationView() {
            @Override
            public MitigationState current() {
                return current;
            }
        };
    }
}
