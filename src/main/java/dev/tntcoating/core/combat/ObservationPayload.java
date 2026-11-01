/*
 * Original Comet implementation by Boyonabike.
 * Independent: written from the pinned 26.2 artifact and Meteor build 35 behaviour; no third-party code.
 * SPDX-License-Identifier: CC0-1.0
 */

package dev.tntcoating.core.combat;

import java.util.Map;

/**
 * Immutable detail carried by an {@link Observation}. One variant exists for every
 * {@link ObservationKind}; kinds whose only fact is their existence use {@link None} or an empty marker.
 * Every collection is defensively copied.
 */
public sealed interface ObservationPayload {
    /** No extra detail (for example a death marker). */
    record None() implements ObservationPayload {}

    record Spawn(String entityType) implements ObservationPayload {}

    record Move(boolean hasPosition, boolean hasRotation) implements ObservationPayload {}

    /** {@code tracking} marks an entity leaving tracking range rather than an authoritative removal. */
    record Removed(String removalReason, boolean tracking) implements ObservationPayload {}

    record BlockUpdate(String oldBlock, String newBlock, boolean movingPiston) implements ObservationPayload {}

    record Explosion(double radius) implements ObservationPayload {}

    record Health(double health, double absorption, int food, double saturation) implements ObservationPayload {}

    /** Health and absorption are separate entity-data fields; the known flags record which arrived. */
    record EntityData(boolean healthKnown, double health, boolean absorptionKnown,
                      double absorption, String entityType) implements ObservationPayload {}

    record Effect(String effectId, int amplifier, int durationTicks, boolean removed)
        implements ObservationPayload {}

    record Equipment(Map<String, String> slots) implements ObservationPayload {
        public Equipment {
            slots = slots == null ? Map.of() : Map.copyOf(slots);
        }
    }

    record DamageEvent(String sourceType, int sourceCauseId, int sourceDirectId)
        implements ObservationPayload {}

    record EntityStatus(int eventId) implements ObservationPayload {}

    record TotemPop() implements ObservationPayload {}

    record Setback() implements ObservationPayload {}

    record Death() implements ObservationPayload {}

    record Respawn(String dimension, boolean dimensionChanged) implements ObservationPayload {}

    /** {@code event} is the recorder's join/leave marker, not a packet class name. */
    record ConnectionChange(String event) implements ObservationPayload {}
}
