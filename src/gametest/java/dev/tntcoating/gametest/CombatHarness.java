/*
 * Original Comet test harness code by Boyonabike.
 * Independent: written for this repository against the pinned Minecraft 26.2,
 * Fabric API client game-test and Meteor build 35 behaviour; no third-party client code.
 * SPDX-License-Identifier: CC0-1.0
 */

package dev.tntcoating.gametest;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import dev.tntcoating.core.combat.mc.CombatRecorderHost;
import dev.tntcoating.gametest.server.FakePlayers;
import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestDedicatedServerConnection;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestDedicatedServerContext;
import net.minecraft.server.level.ServerPlayer;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Properties;

/**
 * Phase 0.4/0.5 scripted client/server harness.
 *
 * <p>Properties (set by the Gradle task or by hand): {@code comet.harness.scenarios},
 * {@code comet.harness.delays}, {@code comet.harness.tps}, {@code comet.harness.repeats},
 * {@code comet.harness.seed}, {@code comet.harness.output}. Delay values are one-way
 * milliseconds; TPS values are server tick rates. Every run writes {@code client.jsonl},
 * {@code server.jsonl} and {@code metadata.json} into one folder.</p>
 */
public final class CombatHarness implements FabricClientGameTest {
    private static final Gson GSON = new Gson();
    private static final DateTimeFormatter STAMP =
        DateTimeFormatter.ofPattern("uuuuMMdd-HHmmss").withZone(ZoneOffset.UTC);

    @Override
    public void runTest(ClientGameTestContext context) {
        Config config = Config.fromSystemProperties();
        List<Result> results = new ArrayList<>();
        System.out.println("COMBAT_HARNESS output=" + config.output);
        for (String scenarioName : config.scenarios) {
            Scenario scenario = scenario(scenarioName);
            for (int delay : config.delays) {
                for (int tps : config.tpsValues) {
                    for (int repeat = 0; repeat < config.repeats; repeat++) {
                        long seed = config.seed ^ ((long) scenarioName.hashCode() << 32)
                            ^ ((long) delay << 16) ^ ((long) tps << 8) ^ repeat;
                        Result result = runOne(context, scenario, config, delay, tps, repeat, seed);
                        results.add(result);
                        System.out.println("COMBAT_HARNESS_RESULT " + result.summary());
                        if (result.failure != null) {
                            System.out.println("COMBAT_HARNESS_FAILURE " + result.failure);
                        }
                    }
                }
            }
        }
        long failed = results.stream().filter(result -> result.failure != null).count();
        if (failed > 0) {
            throw new AssertionError(failed + " harness runs failed; see " + config.output);
        }
    }

    private static Result runOne(ClientGameTestContext context, Scenario scenario, Config config,
                                 int delay, int tps, int repeat, long seed) {
        Path runDirectory = config.output.resolve(scenario.name())
            .resolve("delay-" + delay + "-tps-" + tps)
            .resolve("run-" + repeat);
        Result result = new Result(runDirectory, scenario.name(), delay, tps, repeat, seed, config.jitter);
        try {
            Files.createDirectories(runDirectory);
            System.setProperty("comet.harness.output", runDirectory.toString());
            System.setProperty("comet.harness.scenario", scenario.name());
            System.setProperty("comet.harness.run", Integer.toString(repeat));
            System.setProperty("comet.harness.delay", Integer.toString(delay));
            System.setProperty("comet.harness.jitter", Integer.toString(config.jitter));
            System.setProperty("comet.harness.tps", Integer.toString(tps));
            System.setProperty("comet.harness.seed", Long.toString(seed));

            Properties serverProperties = ScenarioContext.serverProperties(seed, 6);
            try (TestDedicatedServerContext server =
                     context.worldBuilder().createServer(serverProperties)) {
                server.runCommand("gamerule send_command_feedback false");
                server.runCommand("gamerule spawn_mobs false");
                server.runOnServer(minecraft -> minecraft.tickRateManager().setTickRate(tps));
                TestDedicatedServerConnection connection = server.connect();
                try {
                    context.waitTicks(20);
                    LatencyHandler.install(context, delay, config.jitter, seed);
                    CombatRecorderHost trace = TraceFiles.start(context, runDirectory);
                    ScenarioContext scenarioContext = new ScenarioContext(context, server, connection,
                        runDirectory, delay, config.jitter, tps, repeat, seed, trace);
                    ServerPlayer target = null;
                    try {
                        trace.label("scenario-start " + scenario.name());
                        scenario.setup(scenarioContext);
                        target = scenarioContext.target();
                        trace.label("scenario-run");
                        scenario.run(scenarioContext);
                        trace.label("scenario-end");
                    } finally {
                        try {
                            result.traceComplete = TraceFiles.stopAndCopy(trace, runDirectory,
                                Duration.ofSeconds(10));
                        } finally {
                            if (target != null) {
                                ServerPlayer player = target;
                                server.runOnServer(minecraft -> FakePlayers.remove(player));
                            }
                        }
                    }
                } finally {
                    try {
                        connection.close();
                    } catch (RuntimeException ignored) {
                    }
                }
            }
        } catch (Throwable error) {
            result.failure = error.getClass().getSimpleName() + ": " + error.getMessage();
        }
        result.writeMetadata();
        return result;
    }

    private static Scenario scenario(String name) {
        if (name.startsWith("piston_")) return PistonPushScenario.parse(name);
        return switch (name) {
            case "open_target" -> new OpenTargetScenario();
            case "holed_target" -> new HoledTargetScenario();
            case "holed_vertical" -> ScenarioLibrary.holedVertical();
            case "two_crystals_weaker" -> new TwoCrystalScenario(false, 1);
            case "two_crystals_stronger" -> new TwoCrystalScenario(true, 1);
            case "window_boundary" -> new TwoCrystalScenario(true, 12);
            case "absorption_bundle" -> ScenarioLibrary.absorptionBundle();
            case "self_absorption" -> ScenarioLibrary.selfAbsorption();
            case "c6_stronger_in_window" -> ScenarioLibrary.strongerInWindowDirect();
            case "rejected_placement" -> ScenarioLibrary.rejectedPlacement();
            case "out_of_reach_attack" -> ScenarioLibrary.outOfReachAttack();
            case "crystal_destroyed_by_explosion" -> ScenarioLibrary.crystalDestroyedByExplosion();
            case "ack_barrier" -> ScenarioLibrary.ackBarrier();
            case "ack_zero_damage" -> ScenarioLibrary.ackZeroDamage();
            case "self_damage_near" -> ScenarioLibrary.selfDamage("self_damage_near", 2.5, true);
            case "self_damage_mid" -> ScenarioLibrary.selfDamage("self_damage_mid", 4.5, false);
            case "self_damage_zero" -> ScenarioLibrary.selfDamage("self_damage_zero", 13.5, false);
            case "totem_friend" -> ScenarioLibrary.totemFriend();
            case "server_stall" -> ScenarioLibrary.serverStall();
            case "death_respawn" -> ScenarioLibrary.deathRespawn();
            case "disconnect_reconnect" -> ScenarioLibrary.disconnectReconnect();
            case "dimension_change" -> ScenarioLibrary.dimensionChange();
            default -> throw new IllegalArgumentException("Unknown scenario: " + name);
        };
    }

    private record Config(List<String> scenarios, List<Integer> delays, List<Integer> tpsValues,
                          int repeats, int jitter, long seed, Path output) {
        static Config fromSystemProperties() {
            List<String> scenarios = csv(System.getProperty("comet.harness.scenarios", "open_target"));
            List<Integer> delays = ints(System.getProperty("comet.harness.delays", "0,40,100"));
            List<Integer> tps = ints(System.getProperty("comet.harness.tps", "20,15,10"));
            int repeats = Integer.parseInt(System.getProperty("comet.harness.repeats", "5"));
            int jitter = Integer.parseInt(System.getProperty("comet.harness.jitter", "0"));
            long seed = Long.parseLong(System.getProperty("comet.harness.seed", "26432022"));
            String output = System.getProperty("comet.harness.output");
            Path directory = output == null || output.isBlank()
                ? Path.of("build", "combat-harness", STAMP.format(Instant.now()))
                : Path.of(output);
            return new Config(scenarios, delays, tps, repeats, jitter, seed, directory);
        }

        private static List<String> csv(String value) {
            List<String> values = new ArrayList<>();
            for (String part : value.split(",")) {
                String trimmed = part.trim();
                if (!trimmed.isEmpty()) values.add(trimmed);
            }
            return values;
        }

        private static List<Integer> ints(String value) {
            List<Integer> values = new ArrayList<>();
            for (String part : csv(value)) values.add(Integer.parseInt(part));
            return values;
        }
    }

    private static final class Result {
        private final Path directory;
        private final String scenario;
        private final int delay;
        private final int tps;
        private final int repeat;
        private final long seed;
        private final int jitter;
        private boolean traceComplete;
        private String failure;

        private Result(Path directory, String scenario, int delay, int tps, int repeat, long seed) {
            this(directory, scenario, delay, tps, repeat, seed, 0);
        }

        private Result(Path directory, String scenario, int delay, int tps, int repeat, long seed,
                       int jitter) {
            this.directory = directory;
            this.scenario = scenario;
            this.delay = delay;
            this.tps = tps;
            this.repeat = repeat;
            this.seed = seed;
            this.jitter = jitter;
        }

        private String summary() {
            return scenario + " delay=" + delay + " tps=" + tps + " run=" + repeat
                + " trace=" + traceComplete + " failure=" + (failure == null ? "none" : failure);
        }

        private void writeMetadata() {
            JsonObject json = new JsonObject();
            json.addProperty("scenario", scenario);
            json.addProperty("delayMs", delay);
            json.addProperty("jitterMs", jitter);
            json.addProperty("tps", tps);
            json.addProperty("run", repeat);
            json.addProperty("seed", seed);
            json.addProperty("delayKind", "one-way");
            json.addProperty("traceComplete", traceComplete);
            if (failure != null) json.addProperty("failure", failure);
            try {
                Files.createDirectories(directory);
                Files.writeString(directory.resolve("metadata.json"), GSON.toJson(json),
                    StandardCharsets.UTF_8, StandardOpenOption.CREATE,
                    StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.WRITE);
            } catch (IOException ignored) {
                // The result is still printed; a missing metadata file is visible in the report.
            }
        }
    }
}
