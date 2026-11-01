/*
 * Original Comet implementation by Boyonabike.
 * Independent: tests written against the project's own interfaces; no third-party code.
 * SPDX-License-Identifier: CC0-1.0
 */

package dev.tntcoating.core.combat.mc;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.tntcoating.core.combat.CombatSession;
import dev.tntcoating.core.combat.EntityKey;
import dev.tntcoating.core.combat.Observation;
import dev.tntcoating.core.combat.ObservationKind;
import dev.tntcoating.core.combat.ObservationPayload;
import dev.tntcoating.core.combat.Snapshot;
import dev.tntcoating.core.combat.Vec;
import java.util.List;
import java.util.UUID;
import net.minecraft.network.syncher.EntityDataSerializers;
import net.minecraft.network.syncher.SynchedEntityData;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/** Receipt recording, applied-revision stamping and observation creation. */
final class ObservationIntakeTest {
    private static final int HEALTH_ID = 9;
    private static final int ABSORPTION_ID = 10;

    @BeforeAll
    static void bootstrap() {
        AdapterTestSupport.bootstrap();
    }

    private record Fixture(CombatSession session, CombatTestFakes.FakeClient client,
                           ObservationIntake intake, UUID selfUuid) {
    }

    private static Fixture fixture() {
        return fixture(64);
    }

    private static Fixture fixture(int capacity) {
        CombatSession session = new CombatSession();
        session.open();
        CombatTestFakes.FakeClient client = new CombatTestFakes.FakeClient(session.connection());
        UUID selfUuid = UUID.nameUUIDFromBytes("self".getBytes());
        client.putSelf(1, selfUuid);
        ObservationIntake intake =
            new ObservationIntake(session, client, new EntityDataIds(HEALTH_ID, ABSORPTION_ID), capacity);
        return new Fixture(session, client, intake, selfUuid);
    }

    private static List<SynchedEntityData.DataValue<?>> healthAbsorption(float health, float absorption) {
        return List.of(
            new SynchedEntityData.DataValue<>(HEALTH_ID, EntityDataSerializers.FLOAT, health),
            new SynchedEntityData.DataValue<>(ABSORPTION_ID, EntityDataSerializers.FLOAT, absorption));
    }

    @Test
    void receiptIsNotAnObservationUntilApplication() {
        Fixture fixture = fixture();
        ObservationIntake intake = fixture.intake();
        ObservationIntake.Receipt receipt = intake.record(1L, -1, 0,
            new ObservationIntake.RawEntityData(1, healthAbsorption(18.0f, 2.0f)));
        assertEquals(0, intake.queued());
        assertEquals(0L, intake.appliedRevision());

        intake.apply(receipt, null);

        List<Observation> observations = intake.drain();
        assertEquals(1, observations.size());
        Observation observation = observations.get(0);
        assertEquals(ObservationKind.ENTITY_DATA, observation.kind());
        assertEquals(fixture.selfUuid(), observation.entity().uuid());
        ObservationPayload.EntityData payload = (ObservationPayload.EntityData) observation.payload();
        assertTrue(payload.healthKnown());
        assertTrue(payload.absorptionKnown());
        assertEquals(18.0, payload.health());
        assertEquals(2.0, payload.absorption());
        assertEquals(1L, observation.appliedRevision());
        assertEquals(fixture.session().connection(), observation.conn());
    }

    @Test
    void observationsAreDroppedOnNonClientThread() {
        Fixture fixture = fixture();
        fixture.client().onClientThread = false;
        ObservationIntake.Receipt receipt = fixture.intake().record(1L, -1, 0,
            new ObservationIntake.RawEntityData(1, healthAbsorption(18.0f, 2.0f)));
        fixture.intake().apply(receipt, null);
        assertEquals(0, fixture.intake().queued());
        assertEquals(0L, fixture.intake().appliedRevision());
    }

    @Test
    void bundleSharesOneAppliedRevisionAndKeepsReceiptOrder() {
        Fixture fixture = fixture();
        ObservationIntake intake = fixture.intake();
        ObservationIntake.Receipt first = intake.record(1L, 7, 0,
            new ObservationIntake.RawEntityData(1, healthAbsorption(18.0f, 2.0f)));
        ObservationIntake.Receipt second = intake.record(1L, 7, 1,
            new ObservationIntake.RawEntityData(1, List.of(new SynchedEntityData.DataValue<>(
                ABSORPTION_ID, EntityDataSerializers.FLOAT, 4.0f))));

        intake.apply(first, null);
        intake.apply(second, null);

        List<Observation> observations = intake.drain();
        assertEquals(2, observations.size());
        assertEquals(0L, observations.get(0).receiptSeq());
        assertEquals(1L, observations.get(1).receiptSeq());
        assertEquals(observations.get(0).appliedRevision(), observations.get(1).appliedRevision());
        assertEquals(1L, observations.get(0).appliedRevision());
    }

    @Test
    void preassignedBundleRevisionIsCarriedAfterOffThreadRead() {
        Fixture fixture = fixture();
        ObservationIntake intake = fixture.intake();
        ObservationIntake.Receipt first = intake.record(1L, 7, 0, 5L,
            new ObservationIntake.RawEntityData(1, healthAbsorption(18.0f, 2.0f)));
        ObservationIntake.Receipt second = intake.record(1L, 7, 1, 5L,
            new ObservationIntake.RawEntityData(1, List.of(new SynchedEntityData.DataValue<>(
                ABSORPTION_ID, EntityDataSerializers.FLOAT, 4.0f))));
        assertEquals(0L, intake.appliedRevision());

        intake.apply(first, null);
        intake.apply(second, null);

        List<Observation> observations = intake.drain();
        assertEquals(2, observations.size());
        assertEquals(5L, observations.get(0).appliedRevision());
        assertEquals(5L, observations.get(1).appliedRevision());
        assertEquals(5L, intake.appliedRevision());
    }

    @Test
    void separatePacketsGetDistinctAppliedRevisions() {
        Fixture fixture = fixture();
        ObservationIntake intake = fixture.intake();
        ObservationIntake.Receipt first = intake.record(1L, -1, 0,
            new ObservationIntake.RawEntityData(1, healthAbsorption(18.0f, 2.0f)));
        ObservationIntake.Receipt second = intake.record(1L, -1, 0,
            new ObservationIntake.RawEntityData(1, healthAbsorption(17.0f, 2.0f)));
        intake.apply(first, null);
        intake.apply(second, null);
        List<Observation> observations = intake.drain();
        assertEquals(2, observations.size());
        assertTrue(observations.get(1).appliedRevision() > observations.get(0).appliedRevision());
    }

    @Test
    void staleConnectionReceiptIsDroppedAndNewConnectionIsStamped() {
        Fixture fixture = fixture();
        ObservationIntake intake = fixture.intake();
        ObservationIntake.Receipt stale = intake.record(1L, -1, 0,
            new ObservationIntake.RawEntityData(1, healthAbsorption(18.0f, 2.0f)));
        intake.useConnection(2L);
        intake.apply(stale, null);
        assertEquals(0, intake.queued());

        ObservationIntake.Receipt fresh = intake.record(2L, -1, 0,
            new ObservationIntake.RawEntityData(1, healthAbsorption(18.0f, 2.0f)));
        intake.apply(fresh, null);
        assertEquals(1, intake.queued());
        assertEquals(2L, intake.currentConnectionId());
    }

    @Test
    void splitHealthAndAbsorptionEachCarryOnlyTheirKnownField() {
        Fixture fixture = fixture();
        ObservationIntake intake = fixture.intake();
        ObservationIntake.Receipt healthOnly = intake.record(1L, 3, 0,
            new ObservationIntake.RawEntityData(1, List.of(new SynchedEntityData.DataValue<>(
                HEALTH_ID, EntityDataSerializers.FLOAT, 11.0f))));
        ObservationIntake.Receipt absorptionOnly = intake.record(1L, 3, 1,
            new ObservationIntake.RawEntityData(1, List.of(new SynchedEntityData.DataValue<>(
                ABSORPTION_ID, EntityDataSerializers.FLOAT, 6.0f))));
        intake.apply(healthOnly, null);
        intake.apply(absorptionOnly, null);
        List<Observation> observations = intake.drain();
        ObservationPayload.EntityData first =
            (ObservationPayload.EntityData) observations.get(0).payload();
        ObservationPayload.EntityData second =
            (ObservationPayload.EntityData) observations.get(1).payload();
        assertTrue(first.healthKnown());
        assertFalse(first.absorptionKnown());
        assertFalse(second.healthKnown());
        assertTrue(second.absorptionKnown());
        assertEquals(observations.get(0).appliedRevision(), observations.get(1).appliedRevision());
    }

    @Test
    void setHealthNeverFabricatesAbsorptionButZeroHealthIsDeath() {
        Fixture fixture = fixture();
        ObservationIntake intake = fixture.intake();
        ObservationIntake.Receipt healthy = intake.record(1L, -1, 0,
            new ObservationIntake.RawHealth(19.0f, 20, 5.0f));
        intake.apply(healthy, null);
        assertEquals(0, intake.queued());

        ObservationIntake.Receipt dead = intake.record(1L, -1, 0,
            new ObservationIntake.RawHealth(0.0f, 20, 5.0f));
        intake.apply(dead, null);
        List<Observation> observations = intake.drain();
        assertEquals(1, observations.size());
        assertEquals(ObservationKind.DEATH, observations.get(0).kind());
        assertEquals(fixture.selfUuid(), observations.get(0).entity().uuid());
    }

    @Test
    void zeroHealthWithoutSelfIdentityIsDropped() {
        Fixture fixture = fixture();
        fixture.client().self = null;
        ObservationIntake.Receipt dead = fixture.intake().record(1L, -1, 0,
            new ObservationIntake.RawHealth(0.0f, 20, 5.0f));
        fixture.intake().apply(dead, null);
        assertEquals(0, fixture.intake().queued());
    }

    @Test
    void explosionResolvesOneCrystalAndRejectsAmbiguity() {
        Fixture fixture = fixture();
        Vec position = new Vec(0.5, 64.0, 0.5);
        UUID crystalUuid = UUID.nameUUIDFromBytes("crystal".getBytes());
        fixture.client().putCrystal(3, crystalUuid, position);
        ObservationIntake intake = fixture.intake();
        ObservationIntake.Receipt receipt = intake.record(1L, -1, 0,
            new ObservationIntake.RawExplosion(position.x(), position.y(), position.z(), 6.0f));
        intake.apply(receipt, null);
        Observation observation = intake.drain().get(0);
        assertEquals(ObservationKind.EXPLOSION, observation.kind());
        assertEquals(crystalUuid, observation.entity().uuid());

        fixture.client().putCrystal(4, UUID.nameUUIDFromBytes("crystal-two".getBytes()), position);
        ObservationIntake.Receipt ambiguous = intake.record(1L, -1, 0,
            new ObservationIntake.RawExplosion(position.x(), position.y(), position.z(), 6.0f));
        intake.apply(ambiguous, null);
        Observation ambiguousObservation = intake.drain().get(0);
        assertNull(ambiguousObservation.entity());
    }

    @Test
    void boundedQueueOverflowsAndDropsNewest() {
        Fixture fixture = fixture(2);
        ObservationIntake intake = fixture.intake();
        for (int index = 0; index < 3; index++) {
            ObservationIntake.Receipt receipt = intake.record(1L, index + 1, 0,
                new ObservationIntake.RawEntityData(1, healthAbsorption(18.0f + index, 2.0f)));
            intake.apply(receipt, null);
        }
        assertTrue(intake.overflowed());
        assertEquals(2, intake.queued());
        intake.clearOverflow();
        assertFalse(intake.overflowed());
    }

    @Test
    void blockChangedAckIsForwardedToTheRuntimeListener() {
        Fixture fixture = fixture();
        java.util.concurrent.atomic.AtomicInteger ack = new java.util.concurrent.atomic.AtomicInteger(-1);
        fixture.intake().setBlockAckListener(ack::set);
        ObservationIntake.Receipt receipt = fixture.intake().record(1L, -1, 0,
            new ObservationIntake.RawBlockAck(77));
        fixture.intake().apply(receipt, null);
        assertEquals(77, ack.get());
        assertEquals(0, fixture.intake().queued());
    }

    @Test
    void respawnReportsDimensionChangeOnlyAfterTheFirstKnownDimension() {
        Fixture fixture = fixture();
        ObservationIntake intake = fixture.intake();
        ObservationIntake.Receipt first = intake.record(1L, -1, 0,
            new ObservationIntake.RawRespawn("minecraft:overworld"));
        intake.apply(first, null);
        Observation firstObservation = intake.drain().get(0);
        ObservationPayload.Respawn firstPayload =
            (ObservationPayload.Respawn) firstObservation.payload();
        assertFalse(firstPayload.dimensionChanged());

        ObservationIntake.Receipt second = intake.record(1L, -1, 0,
            new ObservationIntake.RawRespawn("minecraft:the_nether"));
        intake.apply(second, null);
        ObservationPayload.Respawn secondPayload =
            (ObservationPayload.Respawn) intake.drain().get(0).payload();
        assertTrue(secondPayload.dimensionChanged());
    }

    @Test
    void entityEventDeathOnlyCountsForSelf() {
        Fixture fixture = fixture();
        ObservationIntake intake = fixture.intake();
        EntityKey self = fixture.client().selfKey();
        EntityKey other = new EntityKey(fixture.session().connection(), 2,
            UUID.nameUUIDFromBytes("friend".getBytes()));

        ObservationIntake.Receipt otherDeath = intake.record(1L, -1, 0,
            new ObservationIntake.RawEntityEvent(3));
        intake.apply(otherDeath, other);
        assertEquals(0, intake.queued());

        ObservationIntake.Receipt selfDeath = intake.record(1L, -1, 0,
            new ObservationIntake.RawEntityEvent(3));
        intake.apply(selfDeath, self);
        List<Observation> observations = intake.drain();
        assertEquals(1, observations.size());
        assertEquals(ObservationKind.DEATH, observations.get(0).kind());
    }

    @Test
    void crystalSnapshotCarriesTheAppliedRevisionTheRuntimeStamps() {
        Fixture fixture = fixture();
        UUID crystalUuid = UUID.nameUUIDFromBytes("crystal".getBytes());
        Vec position = new Vec(1.5, 64.0, 1.5);
        fixture.client().putCrystal(5, crystalUuid, position);
        Snapshot snapshot = new Snapshot(fixture.session().connection(),
            fixture.intake().appliedRevision(), fixture.client().crystals());
        assertEquals(1, snapshot.crystals().size());
        assertEquals(0L, snapshot.appliedRevision());
    }

    @Test
    void deltaSyncedHealthIsCompletedFromAppliedEntity() {
        Fixture fixture = fixture();
        ObservationIntake intake = fixture.intake();
        // Plain damage with no absorption: the server sends only the health field.
        fixture.client().appliedHealth.put(1, new CombatClient.HealthPair(11.0, 0.0));
        ObservationIntake.Receipt healthOnly = intake.record(1L, 3, 0,
            new ObservationIntake.RawEntityData(1, List.of(new SynchedEntityData.DataValue<>(
                HEALTH_ID, EntityDataSerializers.FLOAT, 11.0f))));
        intake.apply(healthOnly, null);
        // Absorption eaten without a health change: the server sends only the absorption field.
        fixture.client().appliedHealth.put(1, new CombatClient.HealthPair(11.0, 2.5));
        ObservationIntake.Receipt absorptionOnly = intake.record(1L, 4, 0,
            new ObservationIntake.RawEntityData(1, List.of(new SynchedEntityData.DataValue<>(
                ABSORPTION_ID, EntityDataSerializers.FLOAT, 2.5f))));
        intake.apply(absorptionOnly, null);

        List<Observation> observations = intake.drain();
        org.junit.jupiter.api.Assertions.assertEquals(2, observations.size());
        ObservationPayload.EntityData first = (ObservationPayload.EntityData) observations.get(0).payload();
        ObservationPayload.EntityData second = (ObservationPayload.EntityData) observations.get(1).payload();
        org.junit.jupiter.api.Assertions.assertTrue(first.healthKnown() && first.absorptionKnown());
        org.junit.jupiter.api.Assertions.assertEquals(11.0, first.health());
        org.junit.jupiter.api.Assertions.assertEquals(0.0, first.absorption());
        org.junit.jupiter.api.Assertions.assertTrue(second.healthKnown() && second.absorptionKnown());
        org.junit.jupiter.api.Assertions.assertEquals(11.0, second.health());
        org.junit.jupiter.api.Assertions.assertEquals(2.5, second.absorption());
    }
}
