/*
 * Original Comet implementation by Boyonabike.
 * Independent: written from the pinned 26.2 artifact and Meteor build 35 behaviour; no third-party code.
 * SPDX-License-Identifier: CC0-1.0
 */

package dev.tntcoating.core.combat;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Replays every Phase 0 client fixture through the ledger and checks the final state against the
 * scenario label. The expected states below are the documented outcome of each harness scenario, not a
 * second derivation from the same events: for example {@code crystal_destroyed_by_explosion} must leave
 * the second crystal {@code REMOVED}, never {@code EXPLODED}.
 */
class CrystalLedgerTraceReplayTest {
    private static final Path FIXTURES = Path.of("src/test/resources/combat/traces");

    private static final Set<String> ALL_CRYSTALS_EXPLODED = Set.of(
        "open_target", "holed_target", "holed_vertical", "ack_barrier", "ack_zero_damage",
        "server_stall", "self_damage_mid", "self_damage_near", "self_damage_zero",
        "two_crystals_stronger", "two_crystals_weaker", "window_boundary");

    private static final Set<String> ALL_CRYSTALS_OBSERVED = Set.of(
        "out_of_reach_attack",
        "piston_east_eye_redstone", "piston_east_eye_torch", "piston_east_feet_redstone",
        "piston_east_feet_torch", "piston_north_eye_redstone", "piston_north_eye_torch",
        "piston_north_feet_redstone", "piston_north_feet_torch", "piston_south_eye_redstone",
        "piston_south_eye_torch", "piston_south_feet_redstone", "piston_south_feet_torch",
        "piston_west_eye_redstone", "piston_west_eye_torch", "piston_west_feet_redstone",
        "piston_west_feet_torch");

    private static final Set<String> NO_CRYSTALS = Set.of(
        "absorption_bundle", "self_absorption", "c6_stronger_in_window", "totem_friend",
        "rejected_placement");

    @Test
    void everyFixtureIsPresent() throws IOException {
        assertEquals(60, fixtureTraces().size());
    }

    @Test
    void everyPhase0TraceReplaysWithItsLabelledFinalState() throws IOException {
        List<String> failures = new ArrayList<>();
        for (Path trace : fixtureTraces()) {
            String scenario = scenario(trace);
            TraceReplayer.Replay replay = TraceReplayer.load(trace);
            CrystalLedger ledger = new CrystalLedger(1_000_000);
            List<HazardId> hazards = reserveActorPlacements(ledger, replay);

            for (Observation observation : replay.observations()) ledger.observe(observation);

            try {
                assertScenario(scenario, replay, ledger, hazards);
                assertPlacementsBound(replay, ledger, hazards);
            } catch (AssertionError error) {
                failures.add(scenario + " [" + trace + "]: " + error.getMessage());
            }
        }
        assertTrue(failures.isEmpty(), String.join(System.lineSeparator(), failures));
    }

    @Test
    void unappliedReceiptsNeverBecomeObservations() throws IOException {
        Path dimensionChange = FIXTURES.resolve(
            "dimension_change/delay-0-tps-20/run-0/client.jsonl");
        TraceReplayer.Replay replay = TraceReplayer.load(dimensionChange);

        List<Observation> crystals = replay.observations().stream()
            .filter(TraceReplayer::isCrystalSpawn)
            .toList();

        assertEquals(2, crystals.size());
        assertTrue(replay.observations().stream()
                .noneMatch(observation -> observation.kind() == ObservationKind.REMOVED
                    && observation.entity() != null && observation.entity().id() == 273),
            "the dimension change's removal was cancelled before vanilla applied it");
        assertEquals(2, replay.observations().stream()
            .filter(observation -> observation.kind() == ObservationKind.RESPAWN)
            .count());
    }

    @Test
    void placementHazardsAreDerivedFromThePlaceCrystalLabel() throws IOException {
        TraceReplayer.Replay replay = TraceReplayer.load(
            FIXTURES.resolve("open_target/delay-0-tps-20/run-0/client.jsonl"));

        assertEquals(1, replay.placements().size());
        assertTrue(replay.labels().stream().anyMatch(label -> label.text().equals("place-crystal")));
        assertEquals(ObservationKind.SETBACK, replay.observations().getFirst().kind());
        assertTrue(replay.observations().stream().anyMatch(TraceReplayer::isCrystalSpawn));
    }

    @Test
    void pistonLabelsResolveTheirSourceCell() throws IOException {
        TraceReplayer.Replay replay = TraceReplayer.load(
            FIXTURES.resolve("piston_east_eye_redstone/delay-0-tps-20/run-0/client.jsonl"));

        assertEquals(1, replay.placements().size());
        assertEquals(Box.fromCell(new Cell(0, -59, 0)), replay.placements().getFirst().source());
    }

    @Test
    void aDimensionChangeRetiresTheOldEpochsHazardsAndOwnership() throws IOException {
        Path trace = FIXTURES.resolve("dimension_change/delay-0-tps-20/run-0/client.jsonl");
        TraceReplayer.Replay replay = TraceReplayer.load(trace);
        Observation change = replay.observations().stream()
            .filter(observation -> observation.kind() == ObservationKind.RESPAWN)
            .filter(observation -> observation.payload() instanceof ObservationPayload.Respawn respawn
                && respawn.dimensionChanged())
            .findFirst().orElseThrow();

        CombatSession session = new CombatSession();
        session.open();
        ConnectionKey oldEpoch = session.connection();
        Cell cell = new Cell(2, -60, 0);

        CrystalLedger ledger = new CrystalLedger(1_000_000);
        HazardId hazard = ledger.reserveSpawn(oldEpoch, Box.fromCell(cell), Box.fromCell(cell).expand(2.0),
            Set.of());
        ledger.markSent(hazard, 1);
        PlacementId placement = ledger.reservePlacement(oldEpoch, cell, "minecraft:obsidian");
        ledger.markPlacementSent(placement, 1);
        ledger.observe(new Observation(oldEpoch, 1, 1, 1L, 2, ObservationKind.BLOCK_UPDATE, null, cell,
            cell.center(), null, new ObservationPayload.BlockUpdate("minecraft:air", "minecraft:obsidian",
                false)));
        assertEquals(Ownership.INFERRED_OURS, ledger.ownership(oldEpoch, cell));

        ConnectionKey newEpoch = session.dimensionChanged();
        ledger.connectionClosed(oldEpoch);

        assertNotEquals(oldEpoch, newEpoch, "a new world is a new entity epoch");
        assertFalse(ledger.unresolved(hazard), "old-epoch hazards are unaddressable");
        assertTrue(ledger.bound(hazard).isEmpty());
        assertFalse(ledger.blocksCell(cell), "an old-epoch sweep no longer blocks placements");
        assertEquals(Ownership.NOT_OURS, ledger.ownership(cell));

        Observation later = replay.observations().stream()
            .filter(TraceReplayer::isCrystalSpawn)
            .filter(observation -> observation.clientTick() > change.clientTick())
            .findFirst().orElseThrow();
        EntityKey remapped = new EntityKey(newEpoch, later.entity().id(), later.entity().uuid());
        ledger.observe(new Observation(newEpoch, later.receiptSeq(), later.appliedRevision(),
            later.receiptNanos(), later.clientTick(), ObservationKind.SPAWN, remapped, null,
            later.position(), null, later.payload()));

        assertEquals(CrystalState.OBSERVED, ledger.state(remapped),
            "the new world's crystal is tracked under the new epoch");
    }

    @Test
    void aTraceBlockUpdateAndSpawnReconcileInEitherOrder() throws IOException {
        TraceReplayer.Replay replay = TraceReplayer.load(
            FIXTURES.resolve("piston_east_eye_redstone/delay-0-tps-20/run-0/client.jsonl"));
        TraceReplayer.Placement placement = replay.placements().getFirst();
        Cell source = cellOf(placement.source());

        Observation spawn = replay.observations().stream()
            .filter(TraceReplayer::isCrystalSpawn)
            .filter(observation -> observation.entity().equals(placement.intended()))
            .findFirst().orElseThrow();
        Observation blockUpdate = replay.observations().stream()
            .filter(observation -> observation.kind() == ObservationKind.BLOCK_UPDATE)
            .filter(observation -> source.equals(observation.cell()))
            .filter(observation -> observation.clientTick() >= spawn.clientTick())
            .findFirst().orElseThrow();
        String placed = ((ObservationPayload.BlockUpdate) blockUpdate.payload()).newBlock();

        CrystalLedger plannedFirst = reconcileInOrder(placement, placed, blockUpdate, spawn);
        CrystalLedger spawnFirst = reconcileInOrder(placement, placed, spawn, blockUpdate);

        for (CrystalLedger ledger : List.of(plannedFirst, spawnFirst)) {
            assertEquals(Ownership.INFERRED_OURS, ledger.ownership(source),
                "a matching block update owns the cell whichever event is applied first");
            assertEquals(CrystalState.OBSERVED, ledger.state(placement.intended()));
        }
    }

    private static CrystalLedger reconcileInOrder(TraceReplayer.Placement placement, String placed,
                                                  Observation first, Observation second) {
        CrystalLedger ledger = new CrystalLedger(1_000_000);
        HazardId hazard = ledger.reserveSpawn(placement.conn(), placement.source(), placement.swept(),
            Set.of());
        ledger.markSent(hazard, placement.sentTick());
        PlacementId reservation = ledger.reservePlacement(placement.conn(), cellOf(placement.source()),
            placed);
        ledger.markPlacementSent(reservation, placement.sentTick());
        ledger.observe(first);
        ledger.observe(second);
        assertEquals(Optional.of(placement.intended()), ledger.bound(hazard));
        return ledger;
    }

    private static Cell cellOf(Box box) {
        return new Cell((int) Math.floor(box.min().x()), (int) Math.floor(box.min().y()),
            (int) Math.floor(box.min().z()));
    }

    private static void assertScenario(String scenario, TraceReplayer.Replay replay,
                                       CrystalLedger ledger, List<HazardId> hazards) {
        List<EntityKey> crystals = spawnedCrystals(replay);

        if (NO_CRYSTALS.contains(scenario)) {
            assertTrue(crystals.isEmpty(), "scenario has no crystals");
            return;
        }

        if (scenario.equals("crystal_destroyed_by_explosion")) {
            assertEquals(2, crystals.size());
            assertEquals(CrystalState.EXPLODED, ledger.state(crystals.get(0)));
            assertEquals(CrystalState.REMOVED, ledger.state(crystals.get(1)),
                "a crystal destroyed by the first explosion does not explode");
            return;
        }

        if (scenario.equals("death_respawn")) {
            assertEquals(1, crystals.size());
            assertEquals(CrystalState.REMOVED, ledger.state(crystals.getFirst()));
            // Death keeps hazards and the later rescan must not clear a removed crystal's evidence.
            return;
        }

        if (scenario.equals("dimension_change")) {
            assertEquals(2, crystals.size());
            for (EntityKey crystal : crystals) {
                assertEquals(CrystalState.OBSERVED, ledger.state(crystal),
                    "the old world's removal was never applied; the new world's crystal is observed");
            }
            return;
        }

        if (scenario.equals("disconnect_reconnect")) {
            assertEquals(2, crystals.size());
            EntityKey oldConnection = crystals.get(0);
            EntityKey newConnection = crystals.get(1);
            assertNotNull(oldConnection.conn());
            assertFalse(oldConnection.conn().equals(newConnection.conn()));
            assertNull(ledger.state(oldConnection), "connection close clears that connection's state");
            assertEquals(CrystalState.OBSERVED, ledger.state(newConnection));
            return;
        }

        if (ALL_CRYSTALS_OBSERVED.contains(scenario)) {
            assertFalse(crystals.isEmpty());
            for (EntityKey crystal : crystals) {
                assertEquals(CrystalState.OBSERVED, ledger.state(crystal), "crystal remains in the world");
            }
            for (HazardId hazard : hazards) {
                assertTrue(ledger.unresolved(hazard), "an observed crystal is not terminal evidence");
                assertTrue(ledger.bound(hazard).isPresent());
            }
            return;
        }

        assertTrue(ALL_CRYSTALS_EXPLODED.contains(scenario), "unclassified scenario " + scenario);
        assertFalse(crystals.isEmpty());
        for (EntityKey crystal : crystals) {
            assertEquals(CrystalState.EXPLODED, ledger.state(crystal));
        }
        for (HazardId hazard : hazards) {
            assertFalse(ledger.unresolved(hazard), "an explosion is terminal for the spawn hazard");
            assertTrue(ledger.bound(hazard).isPresent());
        }
    }

    /** Every placement the actor made must bind exactly the crystal it intended to place. */
    private static void assertPlacementsBound(TraceReplayer.Replay replay, CrystalLedger ledger,
                                              List<HazardId> hazards) {
        for (int i = 0; i < replay.placements().size(); i++) {
            TraceReplayer.Placement placement = replay.placements().get(i);
            assertEquals(Optional.of(placement.intended()), ledger.bound(hazards.get(i)),
                "the hazard binds the crystal the placement intended");
        }
    }

    private static List<HazardId> reserveActorPlacements(CrystalLedger ledger, TraceReplayer.Replay replay) {
        List<HazardId> hazards = new ArrayList<>();
        for (TraceReplayer.Placement placement : replay.placements()) {
            HazardId hazard = ledger.reserveSpawn(
                placement.conn(), placement.source(), placement.swept(), Set.of());
            ledger.markSent(hazard, placement.sentTick());
            hazards.add(hazard);
        }
        return hazards;
    }

    private static List<EntityKey> spawnedCrystals(TraceReplayer.Replay replay) {
        return replay.observations().stream()
            .filter(TraceReplayer::isCrystalSpawn)
            .map(Observation::entity)
            .toList();
    }

    private static List<Path> fixtureTraces() throws IOException {
        try (Stream<Path> paths = Files.walk(FIXTURES)) {
            return paths.filter(path -> path.getFileName().toString().equals("client.jsonl"))
                .sorted()
                .toList();
        }
    }

    private static String scenario(Path trace) {
        // .../traces/<scenario>/<delay-tps>/run-<n>/client.jsonl
        return trace.getParent().getParent().getParent().getFileName().toString();
    }
}
