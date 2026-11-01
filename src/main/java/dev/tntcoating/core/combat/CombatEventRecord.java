/*
 * Original Comet implementation by Boyonabike.
 * Independent: written from the pinned 26.2 artifact and Meteor build 35 behaviour; no third-party code.
 * SPDX-License-Identifier: CC0-1.0
 */

package dev.tntcoating.core.combat;

import com.google.gson.JsonObject;

import java.util.Objects;

/**
 * A receipt for one observed packet, stamped with the applied state on the client thread once vanilla has
 * handled it. Unapplied records are legitimate: a packet can be cancelled before vanilla applies it.
 *
 * @param connectionId connection epoch assigned by the recorder host
 * @param receiptSeq   monotonic receipt order within the trace
 * @param bundleId     bundle identity, or {@link #NO_BUNDLE} outside a bundle
 * @param bundlePosition zero-based position inside its bundle, or {@link #NO_BUNDLE_POSITION}
 * @param receiptNanos {@link System#nanoTime()} sampled on the receiving thread
 * @param clientTick   client tick counter at receipt, or the nearest sampled tick
 * @param kind         observation kind
 * @param applied      true once an after-application hook stamped this receipt
 * @param appliedRevision monotonic applied-state revision, or {@link #NO_REVISION} while unapplied
 * @param entityId     resolved entity id, or {@link #NO_ENTITY}
 * @param uuid         resolved entity UUID, {@code null} while unknown
 * @param x            resolved position, {@code null} while unknown
 * @param y            resolved position, {@code null} while unknown
 * @param z            resolved position, {@code null} while unknown
 * @param payload      immutable packet/resolved fields; defensively copied
 */
public record CombatEventRecord(
    long connectionId,
    long receiptSeq,
    long bundleId,
    int bundlePosition,
    long receiptNanos,
    long clientTick,
    ObservationKind kind,
    boolean applied,
    long appliedRevision,
    int entityId,
    String uuid,
    Double x,
    Double y,
    Double z,
    JsonObject payload
) implements CombatTraceLine {
    public static final long NO_BUNDLE = -1L;
    public static final int NO_BUNDLE_POSITION = -1;
    public static final long NO_REVISION = -1L;
    public static final int NO_ENTITY = -1;

    public CombatEventRecord {
        Objects.requireNonNull(kind, "kind");
        payload = payload == null ? new JsonObject() : payload.deepCopy();
    }

    @Override
    public String type() {
        return "event";
    }

    @Override
    public JsonObject payload() {
        return payload.deepCopy();
    }

    /** Returns a copy of this receipt stamped as applied with the resolved fields. */
    public CombatEventRecord withApplied(long revision, int resolvedEntityId, String resolvedUuid,
                                         Double resolvedX, Double resolvedY, Double resolvedZ,
                                         JsonObject resolvedPayload) {
        return new CombatEventRecord(connectionId, receiptSeq, bundleId, bundlePosition, receiptNanos,
            clientTick, kind, true, revision, resolvedEntityId, resolvedUuid,
            resolvedX, resolvedY, resolvedZ, resolvedPayload);
    }

    /** Returns a copy marked unapplied with the given reason appended to the payload. */
    public CombatEventRecord withUnappliedReason(String reason) {
        JsonObject extended = payload.deepCopy();
        extended.addProperty("unappliedReason", reason);
        return new CombatEventRecord(connectionId, receiptSeq, bundleId, bundlePosition, receiptNanos,
            clientTick, kind, false, NO_REVISION, entityId, uuid, x, y, z, extended);
    }
}
