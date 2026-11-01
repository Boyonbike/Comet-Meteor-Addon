/*
 * Original Comet implementation by Boyonabike.
 * Independent: written from the pinned 26.2 artifact and Meteor build 35 behaviour; no third-party code.
 * SPDX-License-Identifier: CC0-1.0
 */

package dev.tntcoating.core.combat;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CrystalLedgerTest {
    private static final ConnectionKey CONN = new ConnectionKey(1);
    private static final ConnectionKey OTHER_CONN = new ConnectionKey(2);
    private static final ActionGeneration GENERATION = new ActionGeneration(1);
    private static final Cell CELL = new Cell(2, -60, 0);
    private static final Vec SPAWN_POSITION = new Vec(2.5, -60.0, 0.5);
    private static final Box SOURCE = Box.fromCell(CELL);
    private static final Box SWEPT = new Box(new Vec(1.0, -60.0, -1.0), new Vec(4.0, -58.0, 2.0));
    private static final String OBSIDIAN = "minecraft:obsidian";
    private static final String END_CRYSTAL = "minecraft:end_crystal";

    private static EntityKey crystal(long id) {
        return new EntityKey(CONN, (int) id, UUID.nameUUIDFromBytes(("crystal-" + id).getBytes(StandardCharsets.UTF_8)));
    }

    private static EntityKey crystal(ConnectionKey conn, int id) {
        return new EntityKey(conn, id, UUID.nameUUIDFromBytes(("crystal-" + id).getBytes(StandardCharsets.UTF_8)));
    }

    private static Observation observation(long seq, int tick, ObservationKind kind, EntityKey entity,
                                           Cell cell, Vec position, ObservationPayload payload) {
        return new Observation(CONN, seq, seq, seq * 1_000L, tick, kind, entity, cell, position, null, payload);
    }

    private static Observation spawn(long seq, int tick, EntityKey entity, Vec position) {
        return observation(seq, tick, ObservationKind.SPAWN, entity, null, position,
            new ObservationPayload.Spawn(END_CRYSTAL));
    }

    private static Observation removed(long seq, int tick, EntityKey entity, Vec position) {
        return observation(seq, tick, ObservationKind.REMOVED, entity, null, position,
            new ObservationPayload.Removed("unknown", false));
    }

    private static Observation trackingRemoved(long seq, int tick, EntityKey entity, Vec position) {
        return observation(seq, tick, ObservationKind.TRACKING_REMOVED, entity, null, position,
            new ObservationPayload.Removed("out-of-range", true));
    }

    private static Observation explosion(long seq, int tick, Vec position) {
        return observation(seq, tick, ObservationKind.EXPLOSION, null, null, position,
            new ObservationPayload.Explosion(6.0));
    }

    private static Observation blockUpdate(long seq, int tick, Cell cell, String oldBlock, String newBlock) {
        return observation(seq, tick, ObservationKind.BLOCK_UPDATE, null, cell, cell.center(),
            new ObservationPayload.BlockUpdate(oldBlock, newBlock, false));
    }

    private static Observation health(long seq, int tick) {
        return observation(seq, tick, ObservationKind.HEALTH, null, null, new Vec(0.0, 64.0, 0.0),
            new ObservationPayload.Health(20.0, 0.0, 20, 5.0));
    }

    private static HazardId sentHazard(CrystalLedger ledger, ConnectionKey conn, Box source, Box swept, int tick) {
        HazardId hazard = ledger.reserveSpawn(conn, source, swept, Set.of());
        ledger.markSent(hazard, tick);
        return hazard;
    }

    private static HazardId sentHazard(CrystalLedger ledger, int tick) {
        return sentHazard(ledger, CONN, SOURCE, SWEPT, tick);
    }

    @Test
    void reservingAndCancellingBeforeSendReleasesTheHazardAndTheCell() {
        CrystalLedger ledger = new CrystalLedger();
        HazardId hazard = ledger.reserveSpawn(CONN, SOURCE, SWEPT, Set.of());

        assertTrue(ledger.unresolved(hazard));
        assertEquals(CrystalState.PROPOSED, ledger.stateOf(hazard));
        assertTrue(ledger.blocksCell(CELL));

        ledger.cancelUnsent(hazard);

        assertFalse(ledger.unresolved(hazard));
        assertFalse(ledger.blocksCell(CELL));
        assertTrue(ledger.bound(hazard).isEmpty());
    }

    @Test
    void aLateSpawnAfterAbortOrDisableStillBindsToThePersistentHazard() {
        CrystalLedger ledger = new CrystalLedger();
        HazardId hazard = sentHazard(ledger, 10);

        // An abort or disable releases only unsent work; a sent placement hazard survives both.
        ledger.cancelUnsent(hazard);
        ledger.actionGenerationEnded(GENERATION);
        assertTrue(ledger.unresolved(hazard));
        assertEquals(CrystalState.SENT, ledger.stateOf(hazard));

        EntityKey late = crystal(7);
        ledger.observe(spawn(20, 12, late, SPAWN_POSITION));

        assertEquals(Optional.of(late), ledger.bound(hazard));
        assertEquals(CrystalState.OBSERVED, ledger.state(late));
    }

    @Test
    void aSpawnAppliedBeforeTheSendTickDoesNotBind() {
        CrystalLedger ledger = new CrystalLedger();
        HazardId hazard = sentHazard(ledger, 20);
        EntityKey earlier = crystal(25);

        // Out-of-order drain: a crystal that already existed is observed only after the send was marked.
        ledger.observe(spawn(10, 19, earlier, SPAWN_POSITION));

        assertTrue(ledger.bound(hazard).isEmpty(),
            "a crystal that existed before the placement cannot be ours");
        assertEquals(CrystalState.OBSERVED, ledger.state(earlier));
        assertTrue(ledger.unresolved(hazard));

        EntityKey later = crystal(26);
        ledger.observe(spawn(11, 20, later, SPAWN_POSITION));
        assertEquals(Optional.of(later), ledger.bound(hazard), "the first spawn at or after the send binds");
    }

    @Test
    void rescanKeepsABoundHazardWhenItsCrystalIsAbsentFromTheSnapshot() {
        CrystalLedger ledger = new CrystalLedger();
        HazardId hazard = sentHazard(ledger, 10);
        EntityKey bound = crystal(27);
        ledger.observe(spawn(20, 12, bound, SPAWN_POSITION));

        ledger.actionGenerationEnded(GENERATION);
        ledger.rescan(new Snapshot(CONN, 50, List.of()));

        assertEquals(Optional.of(bound), ledger.bound(hazard),
            "absence from a respawn snapshot is not removal evidence");
        assertEquals(CrystalState.OBSERVED, ledger.state(bound));
        assertTrue(ledger.unresolved(hazard));
        assertFalse(ledger.needsRescan(hazard));
    }

    @Test
    void twoSimultaneousPlausibleSpawnsQuarantineTheRegionInsteadOfBinding() {
        CrystalLedger ledger = new CrystalLedger();
        HazardId hazard = sentHazard(ledger, 10);
        EntityKey first = crystal(1);
        EntityKey second = crystal(2);

        ledger.observe(spawn(20, 12, first, SPAWN_POSITION));
        ledger.observe(spawn(21, 12, second, new Vec(2.5, -60.0, 0.5)));

        assertTrue(ledger.bound(hazard).isEmpty(), "ambiguity must not bind either candidate");
        assertEquals(CrystalState.QUARANTINED, ledger.state(first));
        assertEquals(CrystalState.QUARANTINED, ledger.state(second));
        assertEquals(CrystalState.QUARANTINED, ledger.stateOf(hazard));
        assertTrue(ledger.unresolved(hazard));
        assertTrue(ledger.blocksCell(CELL), "no repeat placement in a quarantined region");
        assertTrue(ledger.blocksCell(new Cell(3, -60, 0)), "the swept envelope is quarantined too");
    }

    @Test
    void aSpawnOutsideTheSweptEnvelopeIsIgnoredForBinding() {
        CrystalLedger ledger = new CrystalLedger();
        HazardId hazard = sentHazard(ledger, 10);
        EntityKey unrelated = crystal(3);

        ledger.observe(spawn(20, 12, unrelated, new Vec(40.5, 70.0, -33.5)));

        assertTrue(ledger.bound(hazard).isEmpty());
        assertEquals(CrystalState.OBSERVED, ledger.state(unrelated));
        assertTrue(ledger.unresolved(hazard));
    }

    @Test
    void outOfOrderBlockUpdateAndSpawnReconcile() {
        CrystalLedger ledger = new CrystalLedger();
        HazardId hazard = sentHazard(ledger, 5);
        PlacementId base = ledger.reservePlacement(CONN, CELL, OBSIDIAN);
        ledger.markPlacementSent(base, 5);

        // The block confirmation and the crystal spawn are independent observations and may arrive in
        // either order; the trace fixtures show block updates for the base before the spawn.
        ledger.observe(blockUpdate(10, 6, CELL, "minecraft:air", OBSIDIAN));
        EntityKey crystal = crystal(4);
        ledger.observe(spawn(11, 7, crystal, SPAWN_POSITION));

        assertEquals(Ownership.INFERRED_OURS, ledger.ownership(CELL));
        assertEquals(Optional.of(crystal), ledger.bound(hazard));
        assertEquals(CrystalState.OBSERVED, ledger.state(crystal));
    }

    @Test
    void aBlockUpdateThatArrivesAfterTheSpawnAlsoReconciles() {
        CrystalLedger ledger = new CrystalLedger();
        HazardId hazard = sentHazard(ledger, 5);
        PlacementId base = ledger.reservePlacement(CONN, CELL, OBSIDIAN);
        ledger.markPlacementSent(base, 5);

        EntityKey crystal = crystal(22);
        ledger.observe(spawn(10, 6, crystal, SPAWN_POSITION));
        ledger.observe(blockUpdate(11, 7, CELL, "minecraft:air", OBSIDIAN));

        assertEquals(Ownership.INFERRED_OURS, ledger.ownership(CELL));
        assertEquals(Optional.of(crystal), ledger.bound(hazard));
    }

    @Test
    void duplicateObservationsAreIdempotent() {
        CrystalLedger ledger = new CrystalLedger();
        HazardId hazard = sentHazard(ledger, 10);
        EntityKey crystal = crystal(5);
        Observation spawn = spawn(20, 12, crystal, SPAWN_POSITION);

        ledger.observe(spawn);
        ledger.observe(spawn);
        ledger.observe(spawn(21, 12, crystal, SPAWN_POSITION));

        assertEquals(Optional.of(crystal), ledger.bound(hazard));
        assertEquals(CrystalState.OBSERVED, ledger.state(crystal));
        assertFalse(ledger.blocksCell(new Cell(50, 50, 50)));
    }

    @Test
    void removalFollowedByAnotherPlausibleDelayedSpawnDoesNotDoubleBind() {
        CrystalLedger ledger = new CrystalLedger();
        HazardId hazard = sentHazard(ledger, 10);
        EntityKey first = crystal(6);
        ledger.observe(spawn(20, 12, first, SPAWN_POSITION));
        ledger.observe(removed(21, 20, first, SPAWN_POSITION));
        assertEquals(CrystalState.REMOVED, ledger.state(first));

        EntityKey second = crystal(7);
        ledger.observe(spawn(22, 30, second, SPAWN_POSITION));

        assertEquals(Optional.of(first), ledger.bound(hazard), "the hazard stays bound to its crystal");
        assertEquals(CrystalState.OBSERVED, ledger.state(second));
        assertEquals(Ownership.CONTESTED, ledger.ownership(CELL),
            "a second crystal appeared where ours was; ownership is not ours to claim");
    }

    @Test
    void aSentHazardSurvivesTimeoutTicketInvalidationAndAConfigChange() {
        CombatSession session = new CombatSession();
        session.open();
        Ticket ticket = session.ticket(1);
        session.reconfigure();
        assertFalse(session.valid(ticket));

        CrystalLedger ledger = new CrystalLedger();
        HazardId hazard = sentHazard(ledger, 10);
        for (int i = 0; i < 200; i++) ledger.observe(health(100 + i, 100 + i));

        assertTrue(ledger.unresolved(hazard));
        assertTrue(ledger.blocksCell(CELL));
        assertEquals(CrystalState.SENT, ledger.stateOf(hazard));
    }

    @Test
    void trackingRemovalYieldsTrackingLostAndNeverRemoval() {
        CrystalLedger ledger = new CrystalLedger();
        HazardId hazard = sentHazard(ledger, 10);
        EntityKey crystal = crystal(8);
        ledger.observe(spawn(20, 12, crystal, SPAWN_POSITION));

        ledger.observe(trackingRemoved(21, 30, crystal, SPAWN_POSITION));

        assertEquals(CrystalState.TRACKING_LOST, ledger.state(crystal));
        assertEquals(Optional.of(crystal), ledger.bound(hazard));
        assertTrue(ledger.unresolved(hazard), "leaving tracking range is not terminal evidence");
        assertTrue(ledger.blocksCell(CELL));
    }

    @Test
    void theTrackingRemovedKindWinsOverAContradictoryPayloadFlag() {
        CrystalLedger ledger = new CrystalLedger();
        HazardId hazard = sentHazard(ledger, 10);
        EntityKey crystal = crystal(23);
        ledger.observe(spawn(20, 12, crystal, SPAWN_POSITION));

        ledger.observe(observation(21, 30, ObservationKind.TRACKING_REMOVED, crystal, null,
            SPAWN_POSITION, new ObservationPayload.Removed("out-of-range", false)));

        assertEquals(CrystalState.TRACKING_LOST, ledger.state(crystal));
        assertTrue(ledger.unresolved(hazard));
    }

    @Test
    void aSpawnWithoutSpawnPayloadIsNotAssumedToBeACrystal() {
        CrystalLedger ledger = new CrystalLedger();
        EntityKey entity = crystal(24);

        ledger.observe(observation(20, 12, ObservationKind.SPAWN, entity, null, SPAWN_POSITION,
            new ObservationPayload.None()));

        assertNull(ledger.state(entity));
    }

    @Test
    void anIdenticalBlockUpdateThatCouldBelongToAnotherSendContestsTheCell() {
        CrystalLedger ledger = new CrystalLedger();

        PlacementId placement = ledger.reservePlacement(CONN, CELL, OBSIDIAN);
        ledger.markPlacementSent(placement, 5);
        ledger.observe(blockUpdate(10, 6, CELL, "minecraft:air", OBSIDIAN));
        assertEquals(Ownership.INFERRED_OURS, ledger.ownership(CELL));

        // A second identical update cannot be attributed: it may be a repeat, an earlier send or
        // another player placing the same block.
        ledger.observe(blockUpdate(11, 7, CELL, "minecraft:air", OBSIDIAN));
        assertEquals(Ownership.CONTESTED, ledger.ownership(CELL));
    }

    @Test
    void unexplainedOrPrematureOrMismatchedUpdatesContestTheCell() {
        CrystalLedger ledger = new CrystalLedger();

        PlacementId unsent = ledger.reservePlacement(CONN, CELL, OBSIDIAN);
        ledger.observe(blockUpdate(10, 1, CELL, "minecraft:air", OBSIDIAN));
        assertEquals(Ownership.CONTESTED, ledger.ownership(CELL), "we had not sent it yet");
        ledger.cancelUnsentPlacement(unsent);

        Cell second = new Cell(5, -60, 5);
        PlacementId sent = ledger.reservePlacement(CONN, second, OBSIDIAN);
        ledger.markPlacementSent(sent, 5);
        ledger.observe(blockUpdate(11, 6, second, "minecraft:air", "minecraft:stone"));
        assertEquals(Ownership.CONTESTED, ledger.ownership(second), "the block is not the one we placed");

        Cell third = new Cell(6, -60, 6);
        PlacementId a = ledger.reservePlacement(CONN, third, OBSIDIAN);
        PlacementId b = ledger.reservePlacement(CONN, third, OBSIDIAN);
        ledger.markPlacementSent(a, 5);
        ledger.markPlacementSent(b, 5);
        ledger.observe(blockUpdate(12, 6, third, "minecraft:air", OBSIDIAN));
        assertEquals(Ownership.CONTESTED, ledger.ownership(third), "two reservations could explain the update");

        Cell untouched = new Cell(9, -60, 9);
        ledger.observe(blockUpdate(13, 7, untouched, "minecraft:air", "minecraft:stone"));
        assertEquals(Ownership.NOT_OURS, ledger.ownership(untouched));
    }

    @Test
    void noCellIsEverConfirmedOursWithoutCausalServerEvidence() {
        CrystalLedger ledger = new CrystalLedger();
        PlacementId placement = ledger.reservePlacement(CONN, CELL, OBSIDIAN);
        ledger.markPlacementSent(placement, 5);
        ledger.observe(blockUpdate(10, 6, CELL, "minecraft:air", OBSIDIAN));

        assertEquals(Ownership.INFERRED_OURS, ledger.ownership(CELL));
    }

    @Test
    void deathKeepsHazardsAndRespawnRescanRebindsSurvivingCrystals() {
        CrystalLedger ledger = new CrystalLedger();
        HazardId bound = sentHazard(ledger, CONN, SOURCE, SWEPT, 10);
        EntityKey first = crystal(9);
        ledger.observe(spawn(20, 12, first, SPAWN_POSITION));

        Cell otherCell = new Cell(8, -60, 8);
        HazardId awaiting = sentHazard(ledger, CONN, Box.fromCell(otherCell), Box.fromCell(otherCell), 10);

        ledger.actionGenerationEnded(GENERATION);

        assertTrue(ledger.needsRescan(bound));
        assertTrue(ledger.needsRescan(awaiting));
        assertTrue(ledger.unresolved(bound), "death never retires a sent hazard");
        assertEquals(Optional.of(first), ledger.bound(bound));

        EntityKey second = crystal(10);
        Snapshot snapshot = new Snapshot(CONN, 500, List.of(
            new Snapshot.Crystal(first, SPAWN_POSITION),
            new Snapshot.Crystal(second, otherCell.center())));

        ledger.rescan(snapshot);

        assertEquals(Optional.of(first), ledger.bound(bound));
        assertEquals(CrystalState.OBSERVED, ledger.state(first));
        assertEquals(Optional.of(second), ledger.bound(awaiting));
        assertFalse(ledger.needsRescan(bound));
        assertFalse(ledger.needsRescan(awaiting));
    }

    @Test
    void anUnsentHazardIsReleasedWhenTheActionGenerationEnds() {
        CrystalLedger ledger = new CrystalLedger();
        HazardId proposed = ledger.reserveSpawn(CONN, SOURCE, SWEPT, Set.of());

        ledger.actionGenerationEnded(GENERATION);

        assertFalse(ledger.unresolved(proposed));
        assertFalse(ledger.blocksCell(CELL));
    }

    @Test
    void rescanWithMultipleCandidatesQuarantinesRatherThanGuessing() {
        CrystalLedger ledger = new CrystalLedger();
        HazardId hazard = sentHazard(ledger, 10);
        EntityKey first = crystal(11);
        EntityKey second = crystal(12);

        ledger.rescan(new Snapshot(CONN, 50, List.of(
            new Snapshot.Crystal(first, SPAWN_POSITION),
            new Snapshot.Crystal(second, new Vec(2.4, -60.0, 0.4)))));

        assertTrue(ledger.bound(hazard).isEmpty());
        assertEquals(CrystalState.QUARANTINED, ledger.state(first));
        assertEquals(CrystalState.QUARANTINED, ledger.state(second));
        assertTrue(ledger.blocksCell(CELL));
    }

    @Test
    void rescanQuarantinesTwoHazardsThatShareOneCandidateCrystal() {
        CrystalLedger ledger = new CrystalLedger();
        HazardId first = sentHazard(ledger, 10);
        HazardId second = sentHazard(ledger, 10);
        EntityKey shared = crystal(40);

        ledger.rescan(new Snapshot(CONN, 60, List.of(new Snapshot.Crystal(shared, SPAWN_POSITION))));

        assertTrue(ledger.bound(first).isEmpty(), "insertion order must not hand the crystal to the first hazard");
        assertTrue(ledger.bound(second).isEmpty());
        assertEquals(CrystalState.QUARANTINED, ledger.stateOf(first));
        assertEquals(CrystalState.QUARANTINED, ledger.stateOf(second));
        assertEquals(CrystalState.QUARANTINED, ledger.state(shared),
            "a crystal contested by two hazards is not observed as ours");
        assertTrue(ledger.blocksCell(CELL));
        assertEquals(Ownership.CONTESTED, ledger.ownership(CELL));
    }

    @Test
    void rescanDoesNotRebindAnAlreadyQuarantinedCrystal() {
        CrystalLedger ledger = new CrystalLedger();
        HazardId first = sentHazard(ledger, 10);
        HazardId second = sentHazard(ledger, 10);
        EntityKey shared = crystal(42);
        ledger.rescan(new Snapshot(CONN, 60, List.of(new Snapshot.Crystal(shared, SPAWN_POSITION))));
        assertTrue(ledger.bound(first).isEmpty());
        assertTrue(ledger.bound(second).isEmpty());
        assertEquals(CrystalState.QUARANTINED, ledger.state(shared));

        // A later hazard whose source also contains the quarantined crystal must not launder it back
        // into an observed crystal we own: quarantine is conservative evidence, not a binding opportunity.
        HazardId third = sentHazard(ledger, 11);
        ledger.rescan(new Snapshot(CONN, 61, List.of(new Snapshot.Crystal(shared, SPAWN_POSITION))));

        assertTrue(ledger.bound(third).isEmpty(), "a quarantined crystal must not be rebound to a new hazard");
        assertEquals(CrystalState.QUARANTINED, ledger.stateOf(third));
        assertEquals(CrystalState.QUARANTINED, ledger.state(shared));
        assertEquals(Ownership.CONTESTED, ledger.ownership(CELL));
        assertTrue(ledger.blocksCell(CELL));
    }

    @Test
    void rescanRevivesABoundHazardWhenItsCrystalReappearsInTheSnapshot() {
        CrystalLedger ledger = new CrystalLedger();
        HazardId hazard = sentHazard(ledger, 10);
        EntityKey crystal = crystal(41);
        ledger.observe(spawn(20, 12, crystal, SPAWN_POSITION));
        assertEquals(Optional.of(crystal), ledger.bound(hazard));

        // A non-tracking removal is terminal evidence; absence from a later snapshot is not. A snapshot
        // that still lists the crystal proves it exists and revives the bound hazard.
        ledger.observe(removed(21, 20, crystal, SPAWN_POSITION));
        assertEquals(CrystalState.REMOVED, ledger.state(crystal));
        assertFalse(ledger.unresolved(hazard));

        ledger.rescan(new Snapshot(CONN, 60, List.of(new Snapshot.Crystal(crystal, SPAWN_POSITION))));

        assertEquals(Optional.of(crystal), ledger.bound(hazard), "rescan must not unbind a bound hazard");
        assertEquals(CrystalState.OBSERVED, ledger.state(crystal));
        assertTrue(ledger.unresolved(hazard), "presence in the snapshot revives the terminal hazard");
    }

    @Test
    void connectionCloseClearsOnlyThatConnectionsState() {
        CrystalLedger ledger = new CrystalLedger();
        HazardId firstHazard = sentHazard(ledger, CONN, SOURCE, SWEPT, 10);
        EntityKey first = crystal(13);
        ledger.observe(spawn(20, 12, first, SPAWN_POSITION));

        HazardId secondHazard = sentHazard(ledger, OTHER_CONN, SOURCE, SWEPT, 10);
        EntityKey second = crystal(OTHER_CONN, 13);
        ledger.observe(new Observation(OTHER_CONN, 21, 21, 21_000L, 12, ObservationKind.SPAWN,
            second, null, SPAWN_POSITION, null, new ObservationPayload.Spawn(END_CRYSTAL)));

        ledger.connectionClosed(CONN);

        assertFalse(ledger.unresolved(firstHazard));
        assertNull(ledger.state(first));
        assertTrue(ledger.unresolved(secondHazard));
        assertEquals(CrystalState.OBSERVED, ledger.state(second));
    }

    @Test
    void anExplosionAfterARemovalUpgradesTheCrystalToExploded() {
        CrystalLedger ledger = new CrystalLedger();
        HazardId hazard = sentHazard(ledger, 10);
        EntityKey crystal = crystal(14);
        ledger.observe(spawn(20, 12, crystal, SPAWN_POSITION));

        // Recorded traces apply the crystal removal before the explosion packet.
        ledger.observe(removed(21, 20, crystal, SPAWN_POSITION));
        assertEquals(CrystalState.REMOVED, ledger.state(crystal));
        assertFalse(ledger.unresolved(hazard), "removal is terminal for the spawn hazard");

        ledger.observe(explosion(22, 21, SPAWN_POSITION));

        assertEquals(CrystalState.EXPLODED, ledger.state(crystal));
        assertFalse(ledger.unresolved(hazard));
    }

    @Test
    void anExplosionAtAnotherCrystalsPositionLeavesTheDestroyedCrystalRemoved() {
        CrystalLedger ledger = new CrystalLedger();
        Cell firstCell = new Cell(2, -60, 0);
        Cell secondCell = new Cell(4, -60, 0);
        EntityKey first = crystal(15);
        EntityKey second = crystal(16);
        ledger.observe(spawn(20, 12, first, firstCell.center()));
        ledger.observe(spawn(21, 12, second, secondCell.center()));

        ledger.observe(removed(22, 20, first, firstCell.center()));
        ledger.observe(removed(23, 20, second, secondCell.center()));
        ledger.observe(explosion(24, 21, firstCell.center()));

        assertEquals(CrystalState.EXPLODED, ledger.state(first));
        assertEquals(CrystalState.REMOVED, ledger.state(second),
            "a crystal destroyed by another crystal's explosion does not make a second explosion");
    }

    @Test
    void aSpawnAlreadyInTheBaselineDoesNotBind() {
        CrystalLedger ledger = new CrystalLedger();
        EntityKey existing = crystal(17);
        HazardId hazard = ledger.reserveSpawn(CONN, SOURCE, SWEPT, Set.of(existing));
        ledger.markSent(hazard, 10);

        ledger.observe(spawn(20, 12, existing, SPAWN_POSITION));

        assertTrue(ledger.bound(hazard).isEmpty());
        assertEquals(CrystalState.OBSERVED, ledger.state(existing));
    }

    @Test
    void blockedProtectsBoundCrystalsAndSweptVolumes() {
        CrystalLedger ledger = new CrystalLedger();
        HazardId hazard = sentHazard(ledger, 10);
        EntityKey boundCrystal = crystal(18);
        ledger.observe(spawn(20, 12, boundCrystal, SPAWN_POSITION));

        assertTrue(ledger.blocked(boundCrystal, Box.fromCell(CELL)));
        assertTrue(ledger.blocked(crystal(19), Box.fromCell(new Cell(2, -60, 1))),
            "the unresolved hazard's swept volume is protected");
        assertFalse(ledger.blocked(crystal(20), Box.fromCell(new Cell(60, 60, 60))));
        assertTrue(ledger.unresolved(hazard));
    }

    @Test
    void aSentHazardWithNoSpawnStillBlocksItsCell() {
        CrystalLedger ledger = new CrystalLedger();
        HazardId hazard = sentHazard(ledger, 10);

        for (int i = 0; i < 100; i++) ledger.observe(health(200 + i, 200 + i));

        assertTrue(ledger.unresolved(hazard));
        assertTrue(ledger.blocksCell(CELL));
        assertTrue(ledger.bound(hazard).isEmpty());
    }

    @Test
    void routineDedupeEvictionIsNotObservationLoss() {
        CrystalLedger ledger = new CrystalLedger(4);
        assertFalse(ledger.stale());

        for (int i = 0; i < 10_000; i++) ledger.observe(health(i, i));

        assertFalse(ledger.stale(),
            "evicting old receipt keys from the bounded dedupe window loses no observation");
        assertEquals(4_096, CrystalLedger.DEFAULT_OBSERVATION_CAPACITY);
    }

    @Test
    void observationsLostSetsStaleAndOnlyReconcileClearsIt() {
        CrystalLedger ledger = new CrystalLedger();
        assertFalse(ledger.stale());

        ledger.observationsLost(CONN);
        assertTrue(ledger.stale());

        for (int i = 0; i < 100; i++) ledger.observe(health(i, i));
        assertTrue(ledger.stale(), "stale is level-triggered; ordinary observations never clear it");

        ledger.reconcile(new Snapshot(CONN, 500, List.of()));
        assertFalse(ledger.stale(), "only a fresh snapshot after the loss clears stale");
    }

    @Test
    void anExplosionInsideTheSweptEnvelopeButOutsideAssociationRangeLeavesTheCrystalObserved() {
        CrystalLedger ledger = new CrystalLedger();
        HazardId hazard = sentHazard(ledger, 10);
        EntityKey crystal = crystal(28);
        ledger.observe(spawn(20, 12, crystal, SPAWN_POSITION));

        // Inside the swept box (1,-60,-1)..(4,-58,2), but 2.22 blocks from the bound crystal's last
        // known position. Pushed positions are Phase 5's job; Phase 1.3 associates only by proximity.
        ledger.observe(explosion(21, 20, new Vec(3.9, -59.0, 1.9)));

        assertEquals(CrystalState.OBSERVED, ledger.state(crystal),
            "an explosion in the swept envelope is not evidence about this crystal");
        assertFalse(ledger.explosionObserved(crystal));
        assertTrue(ledger.unresolved(hazard), "an unassociated explosion must not retire the hazard");
        assertTrue(ledger.blocksCell(CELL));
    }

    @Test
    void rescanDoesNotBindAHazardThatWasNeverSent() {
        CrystalLedger ledger = new CrystalLedger();
        HazardId proposed = ledger.reserveSpawn(CONN, SOURCE, SWEPT, Set.of());
        EntityKey candidate = crystal(29);

        ledger.rescan(new Snapshot(CONN, 50, List.of(
            new Snapshot.Crystal(candidate, SPAWN_POSITION))));

        assertTrue(ledger.bound(proposed).isEmpty(),
            "a hazard whose placement never left must not acquire a crystal");
        assertEquals(CrystalState.PROPOSED, ledger.stateOf(proposed));
    }

    @Test
    void aSpawnWithNoEntityTypeNeverBinds() {
        CrystalLedger ledger = new CrystalLedger();
        HazardId hazard = sentHazard(ledger, 10);
        EntityKey unknown = crystal(30);

        ledger.observe(observation(20, 12, ObservationKind.SPAWN, unknown, null, SPAWN_POSITION,
            new ObservationPayload.Spawn(null)));

        assertTrue(ledger.bound(hazard).isEmpty(), "an untyped spawn is not known to be an end crystal");
        assertNull(ledger.state(unknown));
    }

    @Test
    void aReplacementPlacementAtAnAnsweredCellIsInferredOursNotContested() {
        CrystalLedger ledger = new CrystalLedger();
        PlacementId first = ledger.reservePlacement(CONN, CELL, OBSIDIAN);
        ledger.markPlacementSent(first, 5);
        ledger.observe(blockUpdate(10, 6, CELL, "minecraft:air", OBSIDIAN));
        assertEquals(Ownership.INFERRED_OURS, ledger.ownership(CELL));

        // The answered placement's bookkeeping is pruned, so a later placement at the same cell can be
        // attributed instead of inheriting a permanent contest.
        PlacementId second = ledger.reservePlacement(CONN, CELL, OBSIDIAN);
        ledger.markPlacementSent(second, 10);
        ledger.observe(blockUpdate(11, 11, CELL, "minecraft:stone", OBSIDIAN));

        assertEquals(Ownership.INFERRED_OURS, ledger.ownership(CELL),
            "the matching replacement placement answers the cell");
    }

    @Test
    void removalAndExplosionAreSeparateFacts() {
        CrystalLedger ledger = new CrystalLedger();
        EntityKey removed = crystal(31);
        EntityKey exploded = crystal(32);
        ledger.observe(spawn(20, 12, removed, SPAWN_POSITION));
        ledger.observe(spawn(21, 12, exploded, new Vec(4.5, -60.0, 0.5)));
        ledger.observe(removed(22, 20, removed, SPAWN_POSITION));
        ledger.observe(removed(23, 20, exploded, new Vec(4.5, -60.0, 0.5)));
        ledger.observe(explosion(24, 21, new Vec(4.5, -60.0, 0.5)));

        assertTrue(ledger.noLongerExists(removed));
        assertFalse(ledger.explosionObserved(removed), "a removal is not an observed explosion");
        assertTrue(ledger.noLongerExists(exploded));
        assertTrue(ledger.explosionObserved(exploded));
        assertEquals(CrystalState.REMOVED, ledger.state(removed),
            "REMOVED and EXPLODED stay distinct");
        assertEquals(CrystalState.EXPLODED, ledger.state(exploded));
    }

    @Test
    void ownReportsOurBoundSpawnAndRejectsOtherCrystals() {
        CrystalLedger ledger = new CrystalLedger();
        EntityKey ours = crystal(41);
        EntityKey foreign = crystal(42);
        assertFalse(ledger.own(ours), "nothing is ours before a spawn binds");
        HazardId hazard = sentHazard(ledger, 10);
        ledger.observe(spawn(20, 12, ours, SPAWN_POSITION));
        assertEquals(Optional.of(ours), ledger.bound(hazard));
        assertTrue(ledger.own(ours));
        assertFalse(ledger.own(foreign));
        assertFalse(ledger.own(null));
        EntityKey otherConnection = crystal(OTHER_CONN, 41);
        assertFalse(ledger.own(otherConnection), "own is per connection");
    }

    @Test
    void ownRemainsTrueAfterTheBoundCrystalExploded() {
        CrystalLedger ledger = new CrystalLedger();
        EntityKey crystal = crystal(43);
        HazardId hazard = sentHazard(ledger, 10);
        ledger.observe(spawn(20, 12, crystal, SPAWN_POSITION));
        assertTrue(ledger.own(crystal));
        ledger.observe(explosion(21, 13, SPAWN_POSITION));
        assertEquals(CrystalState.EXPLODED, ledger.state(crystal));
        assertTrue(ledger.own(crystal), "the spawn remains ours after detonation");
        assertFalse(ledger.unresolved(hazard), "the hazard is terminal after the explosion");
    }
}
