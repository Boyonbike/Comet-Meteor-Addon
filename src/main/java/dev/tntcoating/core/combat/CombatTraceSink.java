/*
 * Original Comet implementation by Boyonabike.
 * Independent: written from the pinned 26.2 artifact and Meteor build 35 behaviour; no third-party code.
 * SPDX-License-Identifier: CC0-1.0
 */

package dev.tntcoating.core.combat;

import com.google.gson.JsonObject;

import java.io.IOException;
import java.nio.file.Path;

/**
 * Where {@link CombatRecorder} writes its JSON Lines. The file-backed implementation is
 * {@link CombatTraceLog}; the tester build also has an in-memory ring ({@code CombatDebugSink}) so the debug
 * log never touches disk until the player runs an export.
 *
 * <p>Implementations must be safe to call from the client thread and from Meteor's network callbacks. A sink
 * only accepts lines; it has no send path and cannot influence combat risk.</p>
 */
public interface CombatTraceSink extends AutoCloseable {
    /** Accepts one encoded line. Returns false when the sink is closed, full or otherwise dropped it. */
    boolean append(JsonObject line);

    long writtenRecords();

    long droppedRecords();

    /** The first I/O failure, or null. Memory sinks always return null. */
    IOException failure();

    /** File path for a file sink; null for a memory sink. */
    default Path path() {
        return null;
    }

    /** Stops accepting new lines. For a file sink this drains asynchronously and then closes the file. */
    @Override
    void close();
}
