/*
 * Original Comet implementation by Boyonabike.
 * Independent: written from the pinned 26.2 artifact and Meteor build 35 behaviour; no third-party code.
 * SPDX-License-Identifier: CC0-1.0
 */

package dev.tntcoating.core.combat;

import com.google.gson.JsonObject;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class CombatRecorderBufferTest {
    private static CombatRecorderBuffer.Receipt receipt(ObservationKind kind, int entityId, long tick) {
        JsonObject payload = new JsonObject();
        payload.addProperty("packet", kind.name());
        return new CombatRecorderBuffer.Receipt(1L, 1_000L + tick, tick, kind, entityId, null, null, null, null, payload);
    }

    @Test
    void receiptsStayUnappliedUntilTheApplicationHookStampsThem() {
        CombatRecorderBuffer buffer = new CombatRecorderBuffer(16);
        Object packet = new Object();

        CombatEventRecord received = buffer.receive(receipt(ObservationKind.HEALTH, -1, 4L), packet);

        assertFalse(received.applied());
        assertEquals(CombatEventRecord.NO_REVISION, received.appliedRevision());
        assertEquals(List.of(), buffer.drain(4L, 20));
    }

    @Test
    void stampingAnApplicationReleasesTheReceipt() {
        CombatRecorderBuffer buffer = new CombatRecorderBuffer(16);
        Object packet = new Object();
        buffer.receive(receipt(ObservationKind.HEALTH, -1, 4L), packet);

        boolean stamped = buffer.stamp(packet, record -> record.withApplied(
            7L, -1, null, null, null, null, record.payload()));

        assertTrue(stamped);
        List<CombatEventRecord> drained = buffer.drain(4L, 20);
        assertEquals(1, drained.size());
        assertTrue(drained.getFirst().applied());
        assertEquals(7L, drained.getFirst().appliedRevision());
    }

    @Test
    void anUnappliedReceiptFlushesAfterThePendingWindowWithAReason() {
        CombatRecorderBuffer buffer = new CombatRecorderBuffer(16);
        buffer.receive(receipt(ObservationKind.HEALTH, -1, 10L), new Object());

        assertEquals(List.of(), buffer.drain(29L, 20));
        List<CombatEventRecord> drained = buffer.drain(30L, 20);

        assertEquals(1, drained.size());
        assertFalse(drained.getFirst().applied());
        assertEquals("no-application", drained.getFirst().payload().get("unappliedReason").getAsString());
    }

    @Test
    void bundlePositionsAreRecordedAndOrderSurvivesOutOfOrderApplications() {
        CombatRecorderBuffer buffer = new CombatRecorderBuffer(16);
        Object first = new Object();
        Object second = new Object();
        Object third = new Object();
        buffer.registerBundle(new Object(), List.of(first, second, third));

        buffer.receive(receipt(ObservationKind.ENTITY_DATA, 9, 5L), first);
        buffer.receive(receipt(ObservationKind.HEALTH, -1, 5L), second);
        buffer.receive(receipt(ObservationKind.EXPLOSION, -1, 5L), third);

        buffer.stamp(third, record -> record.withApplied(3L, -1, null, null, null, null, record.payload()));
        buffer.stamp(first, record -> record.withApplied(1L, 9, null, null, null, null, record.payload()));
        assertEquals(List.of(0L), buffer.drain(5L, 20).stream().map(CombatEventRecord::receiptSeq).toList(),
            "the applied head is released, the young unresolved receipt holds the rest of the bundle back");

        buffer.stamp(second, record -> record.withApplied(2L, -1, null, null, null, null, record.payload()));
        List<CombatEventRecord> drained = buffer.drain(5L, 20);

        assertEquals(2, drained.size());
        assertEquals(List.of(ObservationKind.HEALTH, ObservationKind.EXPLOSION),
            drained.stream().map(CombatEventRecord::kind).toList());
        assertEquals(List.of(1, 2), drained.stream().map(CombatEventRecord::bundlePosition).toList());
        assertEquals(1, drained.stream().map(CombatEventRecord::bundleId).distinct().count());
        assertEquals(List.of(1L, 2L), drained.stream().map(CombatEventRecord::receiptSeq).toList());
    }

    @Test
    void receiptsOutsideABundleCarryNoBundleIdentity() {
        CombatRecorderBuffer buffer = new CombatRecorderBuffer(16);
        CombatEventRecord record = buffer.receive(receipt(ObservationKind.SPAWN, 4, 2L), new Object());

        assertEquals(CombatEventRecord.NO_BUNDLE, record.bundleId());
        assertEquals(CombatEventRecord.NO_BUNDLE_POSITION, record.bundlePosition());
    }

    @Test
    void stampByEntityMatchesTheOldestReceiptOfThatKind() {
        CombatRecorderBuffer buffer = new CombatRecorderBuffer(16);
        buffer.receive(receipt(ObservationKind.SPAWN, 12, 2L), new Object());
        buffer.receive(receipt(ObservationKind.SPAWN, 12, 3L), new Object());
        buffer.receive(receipt(ObservationKind.SPAWN, 13, 3L), new Object());

        assertTrue(buffer.stampByEntity(ObservationKind.SPAWN, 12,
            record -> record.withApplied(1L, 12, "uuid", 0.5, 64.0, 0.5, record.payload())));
        assertFalse(buffer.stampByEntity(ObservationKind.SPAWN, 99,
            record -> record.withApplied(1L, 99, null, null, null, null, record.payload())));

        List<CombatEventRecord> drained = buffer.drainAll();
        assertEquals(3, drained.size());
        assertTrue(drained.get(0).applied(), "the oldest SPAWN for entity 12 was stamped");
        assertFalse(drained.get(1).applied(), "the second SPAWN for entity 12 is untouched");
        assertFalse(drained.get(2).applied(), "the SPAWN for entity 13 is untouched");
    }

    @Test
    void stampByPositionMatchesTheOldestBlockUpdateAtThatCell() {
        CombatRecorderBuffer buffer = new CombatRecorderBuffer(16);
        JsonObject payload = new JsonObject();
        payload.addProperty("block", "minecraft:stone");
        buffer.receive(new CombatRecorderBuffer.Receipt(1L, 5L, 2L, ObservationKind.BLOCK_UPDATE, -1, null, 1.0, 2.0, 3.0, payload), new Object());

        assertTrue(buffer.stampByPosition(ObservationKind.BLOCK_UPDATE, 1, 2, 3,
            record -> record.withApplied(2L, -1, null, 1.0, 2.0, 3.0, record.payload())));
        assertFalse(buffer.stampByPosition(ObservationKind.BLOCK_UPDATE, 9, 9, 9,
            record -> record.withApplied(2L, -1, null, 9.0, 9.0, 9.0, record.payload())));
    }

    @Test
    void overflowDropsTheNewestReceiptAndCountsIt() {
        CombatRecorderBuffer buffer = new CombatRecorderBuffer(2);
        assertNotNull(buffer.receive(receipt(ObservationKind.SPAWN, 1, 1L), new Object()));
        assertNotNull(buffer.receive(receipt(ObservationKind.SPAWN, 2, 1L), new Object()));
        assertNull(buffer.receive(receipt(ObservationKind.SPAWN, 3, 1L), new Object()));

        assertEquals(1L, buffer.dropped());
        assertEquals(2, buffer.drainAll().size());
    }

    @Test
    void drainAllFlushesEverythingInReceiptOrder() {
        CombatRecorderBuffer buffer = new CombatRecorderBuffer(16);
        buffer.receive(receipt(ObservationKind.SPAWN, 1, 1L), new Object());
        buffer.receive(receipt(ObservationKind.EXPLOSION, -1, 2L), new Object());

        List<CombatEventRecord> drained = buffer.drainAll();

        assertEquals(2, drained.size());
        assertEquals(List.of(0L, 1L), drained.stream().map(CombatEventRecord::receiptSeq).toList());
        assertFalse(drained.getFirst().applied());
        assertEquals("recorder-closed", drained.getFirst().payload().get("unappliedReason").getAsString());
    }
}
