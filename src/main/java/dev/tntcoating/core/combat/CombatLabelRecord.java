/*
 * Original Comet implementation by Boyonabike.
 * Independent: written from the pinned 26.2 artifact and Meteor build 35 behaviour; no third-party code.
 * SPDX-License-Identifier: CC0-1.0
 */

package dev.tntcoating.core.combat;

import java.util.Objects;

/** A free-text marker written by the recorder's label hotkey or command. */
public record CombatLabelRecord(
    long connectionId,
    long receiptSeq,
    long receiptNanos,
    long clientTick,
    String label
) implements CombatTraceLine {
    public CombatLabelRecord {
        Objects.requireNonNull(label, "label");
    }

    @Override
    public String type() {
        return "label";
    }
}
