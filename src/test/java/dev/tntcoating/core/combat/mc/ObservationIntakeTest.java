/*
 * Original Comet implementation by Boyonabike.
 * Independent: tests written against the project's own interfaces; no third-party code.
 * SPDX-License-Identifier: CC0-1.0
 */

package dev.tntcoating.core.combat.mc;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.tntcoating.core.combat.Box;
import dev.tntcoating.core.combat.Observation;
import dev.tntcoating.core.combat.ObservationKind;
import dev.tntcoating.core.combat.ObservationPayload;
import dev.tntcoating.core.combat.Vec;
import java.util.List;
import java.util.UUID;
import net.minecraft.network.syncher.EntityDataSerializers;
import net.minecraft.network.syncher.SynchedEntityData;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/** Receipt ordering, revision stamping and the T1 applied-state surface. */
final class ObservationIntakeTest {
    private static int nextConnection = 1;
    /** The synthetic data ids {@link CombatRuntimeFixture} gives its intake. */
    private static final int HEALTH_ID = 9;
    private static final int ABSORPTION_ID = 10;

    @BeforeAll
    static void bootstrap() {
        AdapterTestSupport.bootstrap();
    }

    private static CombatRuntimeFixture fixture() {
        return new CombatRuntimeFixture();
    }

    @Test
    void appliedRevisionTracksBundlesAndSetbacksQueueInReceiptOrder() {
        CombatRuntimeFixture fixture = fixture();
        ObservationIntake intake = fixture.intake;
        intake.record(1, -1, 0, new ObservationIntake.RawSetback());
        intake.record(1, -1, 0, new ObservationIntake.RawSetback());
        intake.apply(intake.record(1, -1, 0, new ObservationIntake.RawSetback()), null);
        List<Observation> drained = intake.drain();
        assertEquals(1, drained.size());
        assertEquals(ObservationKind.SETBACK, drained.get(0).kind());
        assertTrue(intake.appliedRevision() > 0);
        assertEquals(0, intake.queued());
        assertEquals(0, intake.drain().size());
    }

    @Test
    void receiptsFromAnotherConnectionAreDropped() {
        CombatRuntimeFixture fixture = fixture();
        ObservationIntake intake = fixture.intake;
        long other = 99;
        ObservationIntake.Receipt receipt = intake.record(other, -1, 0, new ObservationIntake.RawSetback());
        intake.useConnection(1);
        intake.apply(receipt, null);
        assertEquals(0, intake.queued());
    }

    @Test
    void healthPacketIsOnlyADeathMarkerNeverAHealthSnapshot() {
        CombatRuntimeFixture fixture = fixture();
        ObservationIntake intake = fixture.intake;
        intake.apply(intake.record(1, -1, 0, new ObservationIntake.RawHealth(19.0f, 20, 5.0f)), null);
        assertEquals(0, intake.queued());
        intake.apply(intake.record(1, -1, 0, new ObservationIntake.RawHealth(0.0f, 20, 5.0f)), null);
        List<Observation> drained = intake.drain();
        assertEquals(1, drained.size());
        assertEquals(ObservationKind.DEATH, drained.get(0).kind());
    }

    @Test
    void entityDataReadsHealthAndAbsorptionThroughTheAccessorIds() {
        CombatRuntimeFixture fixture = fixture();
        ObservationIntake intake = fixture.intake;
        int id = 203;
        UUID uuid = UUID.randomUUID();
        fixture.client.putEntity(id, uuid, "minecraft:player",
            new dev.tntcoating.core.combat.Vec(0.0, 64.0, 0.0), dev.tntcoating.core.combat.Box.fromCell(
                CombatRuntimeFixture.CELL));
        List<SynchedEntityData.DataValue<?>> values = List.of(
            new SynchedEntityData.DataValue<>(9, EntityDataSerializers.FLOAT, 12.0f),
            new SynchedEntityData.DataValue<>(10, EntityDataSerializers.FLOAT, 3.0f));
        intake.apply(intake.record(1, -1, 0, new ObservationIntake.RawEntityData(id, values)), null);
        Observation observation = intake.drain().get(0);
        assertEquals(ObservationKind.ENTITY_DATA, observation.kind());
        ObservationPayload.EntityData data = (ObservationPayload.EntityData) observation.payload();
        assertTrue(data.healthKnown());
        assertEquals(12.0, data.health(), 1.0e-9);
        assertTrue(data.absorptionKnown());
        assertEquals(3.0, data.absorption(), 1.0e-9);
    }

    @Test
    void expansionResolvesTheCrystalAndKeepsTheRadius() {
        CombatRuntimeFixture fixture = fixture();
        ObservationIntake intake = fixture.intake;
        var crystal = fixture.crystal();
        intake.apply(intake.record(1, -1, 0, new ObservationIntake.RawExplosion(
            CombatRuntimeFixture.CRYSTAL_POSITION, 6.0f)), null);
        Observation observation = intake.drain().get(0);
        assertEquals(ObservationKind.EXPLOSION, observation.kind());
        assertEquals(crystal, observation.entity());
        assertEquals(6.0, ((ObservationPayload.Explosion) observation.payload()).radius(), 1.0e-9);
    }

    @Test
    void respawnDimensionChangeIsComputedFromThePreviousDimension() {
        CombatRuntimeFixture fixture = fixture();
        ObservationIntake intake = fixture.intake;
        intake.apply(intake.record(1, -1, 0,
            new ObservationIntake.RawRespawn("minecraft:overworld")), null);
        Observation first = intake.drain().get(0);
        assertTrue(!((ObservationPayload.Respawn) first.payload()).dimensionChanged());
        intake.apply(intake.record(1, -1, 0,
            new ObservationIntake.RawRespawn("minecraft:the_nether")), null);
        Observation second = intake.drain().get(0);
        assertTrue(((ObservationPayload.Respawn) second.payload()).dimensionChanged());
        intake.apply(intake.record(1, -1, 0,
            new ObservationIntake.RawRespawn("minecraft:the_nether")), null);
        assertTrue(!((ObservationPayload.Respawn) intake.drain().get(0).payload())
            .dimensionChanged());
    }

    @Test
    void blockAckReachesTheListenerWithoutAnObservation() {
        CombatRuntimeFixture fixture = fixture();
        ObservationIntake intake = fixture.intake;
        int[] seen = {-1};
        intake.setBlockAckListener(sequence -> seen[0] = sequence);
        intake.apply(intake.record(1, -1, 0, new ObservationIntake.RawBlockAck(42)), null);
        assertEquals(42, seen[0]);
        assertEquals(0, intake.queued());
    }

    @Test
    void entityEventsOnlyProduceDeathForSelfAndTotemForAnyone() {
        CombatRuntimeFixture fixture = fixture();
        ObservationIntake intake = fixture.intake;
        var self = fixture.client.self;
        var other = fixture.crystal();
        intake.apply(intake.record(1, -1, 0, new ObservationIntake.RawEntityEvent(3)), self);
        assertEquals(ObservationKind.DEATH, intake.drain().get(0).kind());
        intake.apply(intake.record(1, -1, 0, new ObservationIntake.RawEntityEvent(3)), other);
        assertEquals(0, intake.queued());
        intake.apply(intake.record(1, -1, 0, new ObservationIntake.RawEntityEvent(35)), other);
        assertEquals(ObservationKind.TOTEM_POP, intake.drain().get(0).kind());
    }

    @Test
    void capacityOverflowIsStickyUntilReconciled() {
        CombatRuntimeFixture fixture = fixture();
        ObservationIntake intake = new ObservationIntake(fixture.session, fixture.client,
            new EntityDataIds(9, 10), 1);
        intake.apply(intake.record(1, -1, 0, new ObservationIntake.RawSetback()), null);
        intake.apply(intake.record(1, -1, 0, new ObservationIntake.RawSetback()), null);
        assertTrue(intake.overflowed());
        assertEquals(1, intake.queued());
        intake.clearOverflow();
        assertTrue(!intake.overflowed());
    }

    @Test
    void offThreadApplyIsIgnored() {
        CombatRuntimeFixture fixture = fixture();
        fixture.client.onClientThread = false;
        fixture.intake.apply(fixture.intake.record(1, -1, 0,
            new ObservationIntake.RawSetback()), null);
        assertEquals(0, fixture.intake.queued());
    }

    @Test
    void deltaSyncedHealthIsCompletedFromAppliedEntity() {
        CombatRuntimeFixture fixture = fixture();
        ObservationIntake intake = fixture.intake;
        // Plain damage with no absorption: the server sends only the health field.
        fixture.client.appliedHealth.put(1, new CombatClient.HealthPair(11.0, 0.0));
        ObservationIntake.Receipt healthOnly = intake.record(1L, 3, 0,
            new ObservationIntake.RawEntityData(1, List.of(new SynchedEntityData.DataValue<>(
                HEALTH_ID, EntityDataSerializers.FLOAT, 11.0f))));
        intake.apply(healthOnly, null);
        // Absorption eaten without a health change: the server sends only the absorption field.
        fixture.client.appliedHealth.put(1, new CombatClient.HealthPair(11.0, 2.5));
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

    @Test
    void nonHealthFloatsAtSharedDataIdsAreIgnored() {
        CombatRuntimeFixture fixture = fixture();
        ObservationIntake intake = fixture.intake;
        // An Interaction's height sits at the health id and a Display's view range at the absorption id.
        int hologram = fixture.client.putEntity(2, UUID.nameUUIDFromBytes("hologram".getBytes()),
            "minecraft:interaction", new Vec(3.5, 64.0, 3.5), Box.around(new Vec(3.5, 64.0, 3.5), 0.5));
        fixture.client.healthFields.put(hologram, CombatClient.HealthFields.NONE);
        intake.apply(intake.record(1L, 3, 0, new ObservationIntake.RawEntityData(hologram, List.of(
            new SynchedEntityData.DataValue<>(HEALTH_ID, EntityDataSerializers.FLOAT, 1.8f)))), null);
        intake.apply(intake.record(1L, 4, 0, new ObservationIntake.RawEntityData(hologram, List.of(
            new SynchedEntityData.DataValue<>(ABSORPTION_ID, EntityDataSerializers.FLOAT, 64.0f)))), null);
        assertEquals(0, intake.queued());

        // A mob has health but no synched absorption: a float at the absorption id is not its absorption.
        int mob = fixture.client.putEntity(3, UUID.nameUUIDFromBytes("mob".getBytes()),
            "minecraft:zombie", new Vec(5.5, 64.0, 5.5), Box.around(new Vec(5.5, 64.0, 5.5), 0.5));
        fixture.client.healthFields.put(mob, CombatClient.HealthFields.HEALTH);
        fixture.client.appliedHealth.put(mob, new CombatClient.HealthPair(15.0, 0.0));
        intake.apply(intake.record(1L, 5, 0, new ObservationIntake.RawEntityData(mob, List.of(
            new SynchedEntityData.DataValue<>(ABSORPTION_ID, EntityDataSerializers.FLOAT, 7.0f)))), null);
        assertEquals(0, intake.queued());
    }
}
