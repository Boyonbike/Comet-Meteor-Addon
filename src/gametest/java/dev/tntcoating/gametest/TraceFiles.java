/*
 * Original Comet test harness code by Boyonabike.
 * Independent: written for this repository against the pinned Minecraft 26.2
 * client and server classes; no third-party client code.
 * SPDX-License-Identifier: CC0-1.0
 */

package dev.tntcoating.gametest;

import dev.tntcoating.core.combat.mc.CombatRecorderHost;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Duration;
import java.util.List;

/** Trace-file lifecycle helpers for the harness. */
public final class TraceFiles {
    private TraceFiles() {
    }

    public static CombatRecorderHost start(ClientGameTestContext context, Path runDirectory)
        throws IOException {
        return context.computeOnClient(client -> {
            try {
                return CombatRecorderHost.start(client, runDirectory.resolve("client"),
                    64L * 1024L * 1024L);
            } catch (IOException error) {
                throw new RuntimeException(error);
            }
        });
    }

    /**
     * Stops the recorder and copies the completed JSONL to {@code client.jsonl}. The trace
     * writer is asynchronous; a missing summary is recorded by returning false.
     */
    public static boolean stopAndCopy(CombatRecorderHost host, Path runDirectory, Duration timeout)
        throws IOException, InterruptedException {
        Path source = host.path();
        host.stop();
        long deadline = System.nanoTime() + timeout.toNanos();
        boolean complete = false;
        while (System.nanoTime() < deadline) {
            if (Files.exists(source)) {
                List<String> lines = Files.readAllLines(source, StandardCharsets.UTF_8);
                if (!lines.isEmpty() && lines.getLast().contains("recorder-summary")) {
                    complete = true;
                    break;
                }
            }
            Thread.sleep(25);
        }
        Files.createDirectories(runDirectory);
        Path target = runDirectory.resolve("client.jsonl");
        if (Files.exists(source)) {
            Files.copy(source, target, StandardCopyOption.REPLACE_EXISTING);
        }
        return complete;
    }

    public static long countEvents(Path trace) throws IOException {
        if (!Files.exists(trace)) return 0;
        return Files.readAllLines(trace, StandardCharsets.UTF_8).stream()
            .filter(line -> line.contains("\"type\":\"event\""))
            .count();
    }
}
