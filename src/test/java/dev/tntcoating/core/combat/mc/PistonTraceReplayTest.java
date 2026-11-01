/*
 * Original Comet implementation by Boyonabike.
 * Independent: test doubles written against the project's own interfaces; no third-party code.
 * SPDX-License-Identifier: CC0-1.0
 */

package dev.tntcoating.core.combat.mc;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import dev.tntcoating.core.combat.Capability;
import dev.tntcoating.core.combat.Cell;
import dev.tntcoating.core.combat.CombatDamagePolicy;
import dev.tntcoating.core.combat.ConnectionKey;
import dev.tntcoating.core.combat.EffectiveHealth;
import dev.tntcoating.core.combat.EntityKey;
import dev.tntcoating.core.combat.Observation;
import dev.tntcoating.core.combat.ObservationKind;
import dev.tntcoating.core.combat.ObservationPayload;
import dev.tntcoating.core.combat.PistonFacing;
import dev.tntcoating.core.combat.PistonGeometry;
import dev.tntcoating.core.combat.PistonGeometry.PowerKind;
import dev.tntcoating.core.combat.PistonLayoutSelection;
import dev.tntcoating.core.combat.PistonPlanner;
import dev.tntcoating.core.combat.PistonSequence;
import dev.tntcoating.core.combat.Safety;
import dev.tntcoating.core.combat.TickBudget;
import dev.tntcoating.core.combat.TraceReplayer;
import dev.tntcoating.core.combat.Vec;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

/**
 * Phase 5 exit gate: replays every recorded Phase 0 piston fixture through the real
 * {@link PistonTrapController} and {@link PistonSequence}, not only through the ledger.
 *
 * <p>What is fixture data: the scenario name, the recorded piston-push label (source, piston and power
 * cells), every block id in the client trace, the recorded crystal spawn (entity id, UUID, position), the
 * arena floor block changes, the server piston-tick start/end records and the crystal positions recorded in
 * every {@code server_tick_end}. What is replay scaffolding: the holed target, the obsidian crystal base and
 * any support the controller itself derives (the Phase 0 harness spawned a crystal in an open arena and
 * placed blocks directly, so no trap target or support packets exist in those traces). The controller still
 * derives the trap order, the supports, the crystal cell and the attack. The WAIT_PUSH progression uses the
 * recorded progress values at their recorded tick offsets (never a fixed one- or two-tick delay), and the
 * computed push is checked against the crystal position the server actually recorded.</p>
 */
final class PistonTraceReplayTest {
    private static final Path FIXTURES = Path.of("src/test/resources/combat/traces");
    private static final String END_CRYSTAL = "minecraft:end_crystal";
    private static final Pattern PUSH_LABEL = Pattern.compile(
        "(source|piston|power)=BlockPos\\{x=(-?\\d+), y=(-?\\d+), z=(-?\\d+)}");
    private static final int SCENARIO_START_TICK = 25;
    private static final double RECORDED_POSITION_TOLERANCE = 0.01;

    @Test
    void everyRecordedPistonFixtureReplaysTheWholeTrapThroughTheController() throws IOException {
        List<Path> fixtures = pistonFixtures();
        assertEquals(16, fixtures.size(), "all sixteen beta layouts have a recorded push");
        for (Path fixture : fixtures) {
            replay(fixture);
        }
    }

    private static void replay(Path fixtureDir) throws IOException {
        String scenario = fixtureDir.getFileName().toString();
        Path run = fixtureDir.resolve("delay-0-tps-20/run-0");
        assertTrue(Files.isDirectory(run), scenario + ": recorded run folder");
        TraceReplayer.Replay trace = TraceReplayer.load(run.resolve("client.jsonl"));

        String recordedScenario = trace.labels().stream()
            .map(TraceReplayer.Label::text)
            .filter(text -> text.startsWith("scenario-start "))
            .findFirst()
            .orElseThrow(() -> new AssertionError(scenario + ": no scenario-start label"))
            .substring("scenario-start ".length());
        assertEquals(scenario, recordedScenario, scenario + ": fixture scenario name");

        String[] parts = scenario.split("_");
        assertEquals(4, parts.length, scenario + ": piston_<dir>_<feet|eye>_<torch|redstone>");
        PistonFacing facing = facingFor(parts[1]);
        int vertical = "eye".equals(parts[2]) ? 1 : 0;
        PowerKind kind = "torch".equals(parts[3])
            ? PowerKind.REDSTONE_TORCH : PowerKind.REDSTONE_BLOCK;
        String combinationId = parts[1] + "-" + parts[2] + "-" + parts[3];

        Map<String, Cell> labelled = labelledCells(scenario, trace);
        Cell sourceCell = labelled.get("source");
        Cell pistonCell = labelled.get("piston");
        Cell powerCell = labelled.get("power");
        assertEquals(offset(sourceCell, facing, -1), pistonCell,
            scenario + ": recorded piston is one cell behind the source");
        assertEquals(offset(sourceCell, facing, -2), powerCell,
            scenario + ": recorded power is two cells behind the source");
        assertTrue(PistonGeometry.trace(scenario).isPresent(), scenario + ": trace evidence");

        PistonGeometry.PistonPush expectedPush =
            PistonGeometry.fromSource(sourceCell, facing, vertical, kind);
        assertEquals(scenario, expectedPush.scenario(),
            scenario + ": layout reconstructs the fixture scenario");
        Cell destinationCell = expectedPush.destination();

        List<PistonTick> pistonTicks = serverPistonTicks(run.resolve("server.jsonl"));
        assertEquals(3, pistonTicks.size(), scenario + ": three recorded extension ticks");
        assertEquals(0.0, pistonTicks.get(0).progress(), 1.0e-9, scenario + ": extension begins");
        assertEquals(0.5, pistonTicks.get(1).progress(), 1.0e-9, scenario + ": half extended");
        assertEquals(1.0, pistonTicks.get(2).progress(), 1.0e-9, scenario + ": fully extended");
        assertEquals(PistonGeometry.EXTENSION_TICKS,
            pistonTicks.get(2).tick() - pistonTicks.get(0).tick(),
            scenario + ": recorded extension lasts the measured two ticks");
        assertEquals(parts[1], pistonTicks.get(0).direction(), scenario + ": recorded push direction");

        Map<Cell, String> recordedBlocks = recordedBlocks(trace);
        assertEquals(PistonPlanner.PISTON_ITEM, recordedBlocks.get(pistonCell),
            scenario + ": recorded piston block");
        assertEquals(kind.itemId(), recordedBlocks.get(powerCell), scenario + ": recorded power block");
        assertEquals("minecraft:moving_piston", recordedBlocks.get(sourceCell),
            scenario + ": the recorded extension occupies the source cell");
        assertTrue(recordedBlocksContain(trace, sourceCell, "minecraft:piston_head"),
            scenario + ": recorded piston head");

        CombatRuntimeFixture fixture = new CombatRuntimeFixture();
        ConnectionKey conn = fixture.ticket.conn();
        UUID targetUuid = UUID.nameUUIDFromBytes(("piston-replay-" + scenario).getBytes());
        EntityKey target = new EntityKey(conn, 50, targetUuid);
        // The record only has an open arena: the trap target and its hole are replay scaffolding. The
        // pushed cell comes from the recorded source and facing, so the hole is derived, not hard-coded.
        Cell anchor = vertical == 0 ? destinationCell : destinationCell.below();
        Vec targetFeet = new Vec(anchor.x() + 0.5, anchor.y(), anchor.z() + 0.5);

        ReplayWorld world = new ReplayWorld(fixture.self(), target, targetUuid, targetFeet);
        // Apply the recorded arena first: the trap geometry (crystal base and hole) is replay scaffolding
        // layered on top, so the recorded floor cannot overwrite the obsidian base under the crystal.
        applyRecordedArena(world, trace, pistonCell, powerCell);
        world.put(sourceCell.below(), "minecraft:obsidian");
        List<Cell> sides = List.of(
            new Cell(anchor.x() + 1, anchor.y(), anchor.z()),
            new Cell(anchor.x() - 1, anchor.y(), anchor.z()),
            new Cell(anchor.x(), anchor.y(), anchor.z() + 1),
            new Cell(anchor.x(), anchor.y(), anchor.z() - 1));
        for (Cell side : sides) {
            if (vertical == 0 && side.equals(sourceCell)) continue; // the push path stays open
            if (side.equals(sourceCell.below())) continue;          // the crystal base stays obsidian
            world.put(side, "minecraft:stone");
        }
        world.entityCells.add(anchor);
        world.entityCells.add(anchor.above());

        // A player standing next to the trap: the fake client must see every placement and the crystal
        // inside its ranges or the runtime would reject the replay for reachability instead of trap logic.
        fixture.client.eye = new Vec(sourceCell.x() + 0.5, sourceCell.y() + 2.0, sourceCell.z() + 0.5);
        fixture.client.hotbar[0] = PistonTrapController.SUPPORT_ITEM;
        fixture.client.hotbar[1] = PistonTrapController.PISTON_ITEM;
        fixture.client.hotbar[2] = kind.itemId();
        fixture.client.hotbar[3] = PistonTrapController.CRYSTAL_ITEM;
        fixture.client.selectedSlot = 0;

        PistonTrapController controller = new PistonTrapController(fixture.runtime, fixture.session,
            fixture.scheduler, fixture.budget, fixture.crystals,
            PistonTrapController.inventory(fixture.adapter), fixture.client, world);
        controller.setLayouts(PistonLayoutSelection.of(List.of(
            PistonLayoutSelection.byId(combinationId).orElseThrow(
                () -> new AssertionError(scenario + ": unknown layout id " + combinationId)))));
        List<String> debug = new ArrayList<>();
        List<String> incidents = new ArrayList<>();
        controller.setDiagnostics(new PistonTrapController.Diagnostics() {
            @Override
            public void debug(String line) {
                debug.add(line);
            }

            @Override
            public void incident(String line) {
                incidents.add(line);
            }
        });

        Optional<Observation> recordedSpawn = trace.observations().stream()
            .filter(observation -> observation.kind() == ObservationKind.SPAWN)
            .filter(observation -> observation.payload() instanceof ObservationPayload.Spawn spawn
                && END_CRYSTAL.equals(spawn.entityType()))
            .findFirst();
        assertTrue(recordedSpawn.isPresent(), scenario + ": recorded crystal spawn");
        EntityKey crystal = recordedSpawn.orElseThrow().entity();
        Vec crystalSpawn = recordedSpawn.orElseThrow().position();
        assertNotNull(crystal, scenario + ": spawn identity");
        assertEquals(sourceCell, cellOf(crystalSpawn), scenario + ": crystal spawns in the source cell");

        List<CrystalSample> recordedCrystal = recordedCrystalPositions(run.resolve("server.jsonl"), crystal.uuid());
        assertTrue(recordedCrystal.size() >= 2, scenario + ": recorded crystal positions");
        Vec recordedSpawnPosition = recordedCrystal.get(0).position();
        Vec recordedFinalPosition = recordedCrystal.get(recordedCrystal.size() - 1).position();

        int tick = 1;
        int pushStartTick = -1;
        int nextPistonTick = 0;
        int attackTick = -1;
        boolean crystalDelivered = false;
        boolean attacked = false;
        while (tick < 40 && !attacked) {
            controller.tick(tick, TickBudget.unlimited());

            if (controller.awaitingBlockConfirmation()) {
                Cell cell = controller.pendingCell().orElseThrow();
                int attempts = fixture.sink.attempts;
                String block = blockFor(scenario, cell, expectedPush, recordedBlocks);
                world.put(cell, block);
                if (cell.equals(expectedPush.piston())) world.face(cell, facing);
                controller.onBlockUpdate(cell);
                assertEquals(attempts, fixture.sink.attempts, scenario + ": observations never send");
            }

            if (!crystalDelivered && fixture.sink.count(CombatTestFakes.FakeSink.Kind.PLACE) > 0) {
                int attempts = fixture.sink.attempts;
                controller.onCrystalSpawn(crystal, END_CRYSTAL, crystal.uuid(), cellOf(crystalSpawn));
                fixture.client.putCrystal(crystal.id(), crystal.uuid(), crystalSpawn);
                world.crystalEntities.add(crystal);
                world.crystalUuids.add(crystal.uuid());
                world.uuids.put(crystal, crystal.uuid());
                assertEquals(attempts, fixture.sink.attempts, scenario + ": observations never send");
                crystalDelivered = true;
            }

            if (controller.phase() == PistonSequence.PistonPhase.WAIT_PUSH) {
                if (pushStartTick < 0) pushStartTick = tick;
                int elapsed = tick - pushStartTick;
                while (nextPistonTick < pistonTicks.size()) {
                    PistonTick recorded = pistonTicks.get(nextPistonTick);
                    int offset = recorded.tick() - pistonTicks.get(0).tick();
                    if (offset > elapsed) break;
                    controller.activeTrap().orElseThrow()
                        .pistonProgress(recorded.progress(), tick);
                    nextPistonTick++;
                }
            }

            long attacks = fixture.sink.sends.stream()
                .filter(send -> send.kind() == CombatTestFakes.FakeSink.Kind.ATTACK).count();
            attacked = attacks == 1;
            if (attacked && attackTick < 0) attackTick = tick;
            tick++;
        }

        PistonSequence sequence = controller.activeTrap().orElseThrow(
            () -> new AssertionError(scenario + ": trap disappeared lastFailure=" + controller.lastFailure() + " attempts=" + fixture.sink.attempts + " sends=" + fixture.sink.sends + " debug=" + debug + " incidents=" + incidents));
        PistonPlanner.Plan plan = sequence.plan();
        assertEquals(scenario, PistonGeometry.scenario(plan.push().layout(), plan.push().powerKind()),
            scenario + ": controller planned the recorded layout");
        assertEquals(sourceCell, plan.push().source(), scenario + ": planned source");
        assertEquals(pistonCell, plan.push().piston(), scenario + ": planned piston");
        assertEquals(powerCell, plan.push().power(), scenario + ": planned power");
        assertEquals(target, plan.target(), scenario + ": planned target");
        assertTrue(nextPistonTick == pistonTicks.size(), scenario + ": every recorded piston tick was fed");
        int lastExtensionTick = pushStartTick
            + (pistonTicks.get(pistonTicks.size() - 1).tick() - pistonTicks.get(0).tick());
        assertTrue(attackTick > lastExtensionTick,
            scenario + ": the attack follows the recorded extension ticks (attack tick " + attackTick
                + ", last recorded extension fed at tick " + lastExtensionTick + ")");
        Vec sourceCentre = plan.push().source().center();
        Vec computedFinal = sequence.computedPushed();
        double computedDx = computedFinal.x() - sourceCentre.x();
        double computedDy = computedFinal.y() - sourceCentre.y();
        double computedDz = computedFinal.z() - sourceCentre.z();
        double recordedDx = recordedFinalPosition.x() - recordedSpawnPosition.x();
        double recordedDy = recordedFinalPosition.y() - recordedSpawnPosition.y();
        double recordedDz = recordedFinalPosition.z() - recordedSpawnPosition.z();
        // The recorded crystal x/y/z is the entity base: a feet-level crystal sits at the cell floor, while
        // the push geometry works in block centres, so compare y with the half-block entity offset.
        assertEquals(recordedSpawnPosition.x(), sourceCentre.x(),
            RECORDED_POSITION_TOLERANCE, scenario + ": recorded spawn x");
        assertEquals(recordedSpawnPosition.y(), sourceCentre.y() - 0.5,
            RECORDED_POSITION_TOLERANCE, scenario + ": recorded spawn y");
        assertEquals(recordedSpawnPosition.z(), sourceCentre.z(),
            RECORDED_POSITION_TOLERANCE, scenario + ": recorded spawn z");
        assertEquals(recordedFinalPosition.x(), computedFinal.x(), RECORDED_POSITION_TOLERANCE,
            scenario + ": computed final crystal x matches the server trace");
        assertEquals(recordedFinalPosition.y(), computedFinal.y() - 0.5, RECORDED_POSITION_TOLERANCE,
            scenario + ": computed final crystal y matches the server trace");
        assertEquals(recordedFinalPosition.z(), computedFinal.z(), RECORDED_POSITION_TOLERANCE,
            scenario + ": computed final crystal z matches the server trace");
        assertEquals(recordedDx, computedDx, RECORDED_POSITION_TOLERANCE,
            scenario + ": computed push displacement x matches the server trace");
        assertEquals(recordedDy, computedDy, RECORDED_POSITION_TOLERANCE,
            scenario + ": computed push displacement y matches the server trace");
        assertEquals(recordedDz, computedDz, RECORDED_POSITION_TOLERANCE,
            scenario + ": computed push displacement z matches the server trace");
        assertTrue(recordedFinalPosition.distance(recordedSpawnPosition) > 0.5,
            scenario + ": the fixture records a real push");

        List<PistonSequence.PistonPhase> expectedHistory = List.of(
            PistonSequence.PistonPhase.RESERVE,
            PistonSequence.PistonPhase.SUPPORTS,
            PistonSequence.PistonPhase.PISTON,
            PistonSequence.PistonPhase.CRYSTAL,
            PistonSequence.PistonPhase.POWER,
            PistonSequence.PistonPhase.WAIT_PUSH,
            PistonSequence.PistonPhase.ATTACK,
            PistonSequence.PistonPhase.WAIT_REMOVE);
        assertEquals(expectedHistory, sequence.history(), scenario + ": phases walk in order");
        assertEquals(PistonSequence.PistonPhase.WAIT_REMOVE, sequence.phase(),
            scenario + ": the recorded window ends waiting for removal (no explosion in the fixture)");

        long supports = plan.intents().stream()
            .filter(intent -> intent.action() == dev.tntcoating.core.combat.Action.PLACE_SUPPORT)
            .count();
        assertEquals(1, fixture.sink.count(CombatTestFakes.FakeSink.Kind.PISTON),
            scenario + ": exactly one piston placement");
        assertEquals(1, fixture.sink.count(CombatTestFakes.FakeSink.Kind.PLACE),
            scenario + ": exactly one crystal placement");
        assertEquals(1, fixture.sink.count(CombatTestFakes.FakeSink.Kind.POWER),
            scenario + ": exactly one power placement");
        assertEquals(supports, fixture.sink.count(CombatTestFakes.FakeSink.Kind.SUPPORT),
            scenario + ": every planned support was placed once");
        assertEquals(1, fixture.sink.count(CombatTestFakes.FakeSink.Kind.ATTACK),
            scenario + ": exactly one attack");
        assertEquals(crystal.id(), fixture.sink.last(CombatTestFakes.FakeSink.Kind.ATTACK).number(),
            scenario + ": the attack targets the recorded crystal entity");
        assertEquals(crystal.uuid(), sequence.bound().orElseThrow().uuid(),
            scenario + ": the bound crystal is the recorded spawn");

        int attemptsAfterAttack = fixture.sink.attempts;
        int afterAttackTick = tick;
        for (int later = afterAttackTick; later < afterAttackTick + 3; later++) {
            controller.tick(later, TickBudget.unlimited());
        }
        assertEquals(attemptsAfterAttack, fixture.sink.attempts,
            scenario + ": no packets after the attack");
        assertEquals(1, fixture.sink.count(CombatTestFakes.FakeSink.Kind.ATTACK),
            scenario + ": no second attack");
        assertEquals(PistonSequence.PistonPhase.WAIT_REMOVE, controller.phase(),
            scenario + ": still waiting for the removal the fixture never records");
        assertTrue(incidents.isEmpty(), scenario + ": no incident during the happy path: " + incidents);
        assertTrue(debug.stream().anyMatch(line -> line.contains("trap-plan")),
            scenario + ": the plan is logged");
        assertTrue(debug.stream().anyMatch(line -> line.contains("computed-final=")),
            scenario + ": the computed push is logged");
    }

    private static Cell offset(Cell cell, PistonFacing facing, int steps) {
        return new Cell(cell.x() + facing.dx() * steps, cell.y() + facing.dy() * steps,
            cell.z() + facing.dz() * steps);
    }

    private static PistonFacing facingFor(String direction) {
        return switch (direction) {
            case "east" -> PistonGeometry.EAST;
            case "west" -> PistonGeometry.WEST;
            case "north" -> PistonGeometry.NORTH;
            case "south" -> PistonGeometry.SOUTH;
            default -> throw new AssertionError("unknown piston direction " + direction);
        };
    }

    private static String blockFor(String scenario, Cell cell, PistonGeometry.PistonPush push,
                                   Map<Cell, String> recordedBlocks) {
        if (cell.equals(push.piston())) {
            String recorded = recordedBlocks.get(cell);
            return recorded == null ? PistonPlanner.PISTON_ITEM : recorded;
        }
        if (cell.equals(push.power())) {
            String recorded = recordedBlocks.get(cell);
            return recorded == null ? push.powerKind().itemId() : recorded;
        }
        // No support placement exists in the Phase 0 traces: the harness placed blocks directly. The
        // controller derived this support cell, so the replay confirms it with the planned support item.
        return PistonPlanner.SUPPORT_ITEM;
    }

    private static Map<String, Cell> labelledCells(String scenario, TraceReplayer.Replay trace) {
        String text = trace.labels().stream()
            .map(TraceReplayer.Label::text)
            .filter(label -> label.startsWith("piston-crystal "))
            .findFirst()
            .orElseThrow(() -> new AssertionError(scenario + ": no piston-crystal label"));
        Matcher matcher = PUSH_LABEL.matcher(text);
        Map<String, Cell> cells = new HashMap<>();
        while (matcher.find()) {
            cells.put(matcher.group(1), new Cell(
                Integer.parseInt(matcher.group(2)),
                Integer.parseInt(matcher.group(3)),
                Integer.parseInt(matcher.group(4))));
        }
        for (String key : List.of("source", "piston", "power")) {
            assertTrue(cells.containsKey(key), scenario + ": label names " + key);
        }
        return cells;
    }

    private static Map<Cell, String> recordedBlocks(TraceReplayer.Replay trace) {
        Map<Cell, String> blocks = new LinkedHashMap<>();
        for (Observation observation : trace.observations()) {
            if (observation.kind() != ObservationKind.BLOCK_UPDATE
                || !(observation.payload() instanceof ObservationPayload.BlockUpdate update)
                || update.newBlock() == null || update.newBlock().isEmpty()
                || "minecraft:air".equals(update.newBlock())) {
                continue;
            }
            blocks.putIfAbsent(observation.cell(), update.newBlock());
        }
        return blocks;
    }

    private static boolean recordedBlocksContain(TraceReplayer.Replay trace, Cell cell, String block) {
        for (Observation observation : trace.observations()) {
            if (observation.kind() == ObservationKind.BLOCK_UPDATE
                && cell.equals(observation.cell())
                && observation.payload() instanceof ObservationPayload.BlockUpdate update
                && block.equals(update.newBlock())) {
                return true;
            }
        }
        return false;
    }

    private static void applyRecordedArena(ReplayWorld world, TraceReplayer.Replay trace,
                                           Cell pistonCell, Cell powerCell) {
        for (Observation observation : trace.observations()) {
            if (observation.kind() != ObservationKind.BLOCK_UPDATE
                || observation.clientTick() > SCENARIO_START_TICK
                || !(observation.payload() instanceof ObservationPayload.BlockUpdate update)
                || update.newBlock() == null || update.newBlock().isEmpty()
                || "minecraft:air".equals(update.newBlock())) {
                continue;
            }
            if (observation.cell().equals(pistonCell) || observation.cell().equals(powerCell)) {
                continue; // the controller's own placements are confirmed by these recorded ids later
            }
            world.put(observation.cell(), update.newBlock());
        }
    }

    private static List<PistonTick> serverPistonTicks(Path serverTrace) throws IOException {
        Map<Integer, PistonTick> byTick = new LinkedHashMap<>();
        for (String line : Files.readAllLines(serverTrace)) {
            if (line.isBlank()) continue;
            JsonObject json = JsonParser.parseString(line).getAsJsonObject();
            String type = json.get("type").getAsString();
            if (!"piston_tick_start".equals(type) && !"piston_tick_end".equals(type)) continue;
            if (!json.has("progress") || !json.has("direction") || !json.has("tick")) continue;
            int tick = json.get("tick").getAsInt();
            // Start and end carry the same tick with the before/after progress; the start value is the
            // state the controller observes first, so it wins for a tick that appears in both records.
            byTick.putIfAbsent(tick, new PistonTick(tick,
                json.get("progress").getAsDouble(), json.get("direction").getAsString()));
        }
        return byTick.values().stream().sorted((a, b) -> Integer.compare(a.tick(), b.tick())).toList();
    }

    private static List<CrystalSample> recordedCrystalPositions(Path serverTrace, UUID uuid)
        throws IOException {
        List<CrystalSample> samples = new ArrayList<>();
        for (String line : Files.readAllLines(serverTrace)) {
            if (line.isBlank()) continue;
            JsonObject json = JsonParser.parseString(line).getAsJsonObject();
            if (!"server_tick_end".equals(json.get("type").getAsString())) continue;
            if (!json.has("crystals") || !json.get("crystals").isJsonArray()) continue;
            for (JsonElement element : json.getAsJsonArray("crystals")) {
                JsonObject crystalJson = element.getAsJsonObject();
                if (!crystalJson.has("uuid")
                    || !uuid.toString().equals(crystalJson.get("uuid").getAsString())) {
                    continue;
                }
                if (crystalJson.has("removed") && crystalJson.get("removed").getAsBoolean()) {
                    return samples; // only positions before the recorded removal
                }
                samples.add(new CrystalSample(json.get("tick").getAsInt(), new Vec(
                    crystalJson.get("x").getAsDouble(),
                    crystalJson.get("y").getAsDouble(),
                    crystalJson.get("z").getAsDouble())));
            }
        }
        return samples;
    }

    private static List<Path> pistonFixtures() throws IOException {
        try (Stream<Path> entries = Files.list(FIXTURES)) {
            return entries
                .filter(Files::isDirectory)
                .filter(path -> path.getFileName().toString().startsWith("piston_"))
                .sorted()
                .toList();
        }
    }

    private static Cell cellOf(Vec position) {
        return new Cell((int) Math.floor(position.x()), (int) Math.floor(position.y()),
            (int) Math.floor(position.z()));
    }

    private record PistonTick(int tick, double progress, String direction) {
    }

    private record CrystalSample(int tick, Vec position) {
    }

    /** Read-only fixture world: the arena floor comes from the trace, the trap geometry from the plan. */
    private static final class ReplayWorld implements PistonTrapController.World {
        private final EntityKey self;
        private final EntityKey target;
        private final UUID targetUuid;
        private final Vec targetPosition;
        private final Map<Cell, String> blocks = new HashMap<>();
        private final Map<Cell, PistonFacing> facings = new HashMap<>();
        private final Set<Cell> entityCells = new HashSet<>();
        private final Set<Cell> powered = new HashSet<>();
        private final Set<Cell> extended = new HashSet<>();
        private final Set<EntityKey> crystalEntities = new HashSet<>();
        private final Set<UUID> crystalUuids = new HashSet<>();
        private final Map<EntityKey, UUID> uuids = new HashMap<>();
        private final Vec selfPosition = new Vec(40.0, 0.0, 40.0);
        private int tick;

        private ReplayWorld(EntityKey self, EntityKey target, UUID targetUuid, Vec targetPosition) {
            this.self = self;
            this.target = target;
            this.targetUuid = targetUuid;
            this.targetPosition = targetPosition;
        }

        private void put(Cell cell, String block) {
            blocks.put(cell, block);
        }

        private void face(Cell cell, PistonFacing facing) {
            facings.put(cell, facing);
        }

        @Override
        public EntityKey self() {
            return self;
        }

        @Override
        public boolean hasItem(String itemId) {
            return PistonTrapController.SUPPORT_ITEM.equals(itemId)
                || PistonTrapController.PISTON_ITEM.equals(itemId)
                || itemId != null && itemId.equals(PistonGeometry.PowerKind.REDSTONE_TORCH.itemId())
                || itemId != null && itemId.equals(PistonGeometry.PowerKind.REDSTONE_BLOCK.itemId())
                || PistonTrapController.CRYSTAL_ITEM.equals(itemId);
        }

        @Override
        public List<EntityKey> targets() {
            return List.of(target);
        }

        @Override
        public boolean alive(EntityKey entity) {
            return true;
        }

        @Override
        public EffectiveHealth health(EntityKey entity) {
            return new EffectiveHealth(20.0, 0.0, Capability.KNOWN, 0L);
        }

        @Override
        public Map<EntityKey, EffectiveHealth> friendHealth() {
            return Map.of();
        }

        @Override
        public Safety safety() {
            return new Safety(6.0, 6.0, CombatDamagePolicy.MIN_REMAINING_HEALTH, 2.0);
        }

        @Override
        public boolean withinAttackEnvelope(EntityKey entity, Vec pushed) {
            return position(entity).distance(pushed) <= 4.5;
        }

        @Override
        public UUID uuid(EntityKey entity) {
            return uuids.get(entity);
        }

        @Override
        public Set<EntityKey> crystalEntities() {
            return crystalEntities;
        }

        @Override
        public Set<UUID> crystalUuids() {
            return crystalUuids;
        }

        @Override
        public Optional<Vec> crystalClientPosition(EntityKey entity) {
            return Optional.empty();
        }

        @Override
        public void refresh(int serverTick) {
            tick = serverTick;
        }

        @Override
        public void onServerTick(int serverTick) {
            tick = serverTick;
        }

        @Override
        public Vec position(EntityKey entity) {
            return entity.equals(target) ? targetPosition : selfPosition;
        }

        @Override
        public double speed(EntityKey entity) {
            return 0.0;
        }

        @Override
        public int stationaryTicks(EntityKey entity) {
            return 10;
        }

        @Override
        public double crystalDamage(Vec crystalCentre, Vec victimFeet) {
            return crystalCentre.distance(victimFeet) < 2.0 ? 10.0 : 0.0;
        }

        @Override
        public List<EntityKey> nearbyFriends() {
            return List.of();
        }

        @Override
        public boolean friendly(EntityKey entity) {
            return false;
        }

        @Override
        public double mitigation(EntityKey entity) {
            return 1.0;
        }

        @Override
        public double selfDamageCeiling() {
            return 6.0;
        }

        @Override
        public double friendDamageCeiling() {
            return 2.0;
        }

        @Override
        public double minimumTargetDamage() {
            return 6.0;
        }

        @Override
        public long planId() {
            return 7L;
        }

        @Override
        public int currentTick() {
            return tick;
        }

        @Override
        public List<Vec> feasiblePositions(EntityKey entity) {
            return List.of(position(entity));
        }

        @Override
        public String blockId(Cell cell) {
            return blocks.get(cell);
        }

        @Override
        public boolean replaceable(Cell cell) {
            String block = blocks.get(cell);
            return block == null || "minecraft:air".equals(block) || "minecraft:cave_air".equals(block);
        }

        @Override
        public boolean fullSupport(Cell cell) {
            String block = blocks.get(cell);
            return "minecraft:stone".equals(block) || "minecraft:obsidian".equals(block)
                || "minecraft:bedrock".equals(block);
        }

        @Override
        public boolean entityFree(Cell cell) {
            return !entityCells.contains(cell);
        }

        @Override
        public boolean crystalPresent(Cell cell) {
            return false;
        }

        @Override
        public boolean pistonPowered(Cell cell) {
            return powered.contains(cell);
        }

        @Override
        public boolean pistonExtended(Cell cell) {
            return extended.contains(cell);
        }

        @Override
        public PistonFacing pistonFacing(Cell cell) {
            return facings.get(cell);
        }

        @Override
        public boolean redstoneComponent(Cell cell) {
            return false;
        }
    }
}
