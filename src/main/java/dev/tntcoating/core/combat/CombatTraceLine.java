/*
 * Original Comet implementation by Boyonabike.
 * Independent: written from the pinned 26.2 artifact and Meteor build 35 behaviour; no third-party code.
 * SPDX-License-Identifier: CC0-1.0
 */

package dev.tntcoating.core.combat;

import com.google.gson.JsonObject;

/** One JSON Lines entry in a combat trace file. */
public sealed interface CombatTraceLine permits CombatEventRecord, CombatLabelRecord {
    /** Stable discriminator written as {@code type}. */
    String type();

    /** Receipt sequence number; labels take the next number after the last receipt. */
    long receiptSeq();

    default JsonObject toJson() {
        return CombatTraceCodec.encode(this);
    }
}
