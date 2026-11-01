/*
 * Original Comet implementation by Boyonabike.
 * Independent: written from the pinned 1.21.11 artifact and Meteor build 82 behaviour; no third-party code.
 * SPDX-License-Identifier: CC0-1.0
 */

package dev.tntcoating.core.tester;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/**
 * Pure builder for the tester evidence zip. The command renders {@link #create} as a preview first; only
 * {@link #write(Path, List)} touches the filesystem.
 *
 * <p>Archive layout (flat): {@value #MANIFEST}, {@value #BUILD_INFO}, {@value #SETTINGS},
 * {@value #DEBUG_LOG}, {@value #NOTICES}. Every debug line is passed through the per-export
 * {@link CombatExportRedactor}; the salt never leaves process memory.</p>
 */
public final class CombatExportBundle {
    public static final String FORMAT_VERSION = "1";
    public static final String MANIFEST = "manifest.json";
    public static final String BUILD_INFO = "build.txt";
    public static final String SETTINGS = "settings.json";
    public static final String DEBUG_LOG = "debug-log.jsonl";
    public static final String NOTICES = "THIRD_PARTY_NOTICES.md";
    private static final Gson GSON = new GsonBuilder().disableHtmlEscaping().create();

    private CombatExportBundle() {
    }

    public record Entry(String name, byte[] content) {
        public Entry {
            Objects.requireNonNull(name, "name");
            Objects.requireNonNull(content, "content");
            if (name.isBlank()) throw new IllegalArgumentException("entry name is blank");
        }
    }

    public record BuildStamp(String cometVersion, String gitCommit, String testerSuffix,
                             String minecraftVersion, String meteorVersion) {
        public BuildStamp {
            cometVersion = safe(cometVersion, "dev");
            gitCommit = safe(gitCommit, "unknown");
            testerSuffix = safe(testerSuffix, "release");
            minecraftVersion = safe(minecraftVersion, "unknown");
            meteorVersion = safe(meteorVersion, "unknown");
        }

        private static String safe(String value, String fallback) {
            return value == null || value.isBlank() ? fallback : value;
        }
    }

    public record SettingValue(String group, String name, String value) {
        public SettingValue {
            group = group == null ? "General" : group;
            name = Objects.requireNonNull(name, "name");
            value = value == null ? "" : value;
        }
    }

    public record Request(BuildStamp build, List<SettingValue> settings, List<String> debugLines,
                          long droppedDebugLines, String notices, CombatExportRedactor redactor,
                          long createdEpochMillis) {
        public Request {
            Objects.requireNonNull(build, "build");
            settings = settings == null ? List.of() : List.copyOf(settings);
            debugLines = debugLines == null ? List.of() : List.copyOf(debugLines);
            notices = notices == null ? "" : notices;
            Objects.requireNonNull(redactor, "redactor");
        }
    }

    /** Builds all archive entries in memory; nothing is written. */
    public static List<Entry> create(Request request) {
        List<String> debugLines = redactDebugLines(request);
        JsonObject settings = settingsJson(request);
        String build = buildText(request);

        JsonObject manifest = new JsonObject();
        manifest.addProperty("format", FORMAT_VERSION);
        manifest.addProperty("created", Instant.ofEpochMilli(request.createdEpochMillis()).toString());
        manifest.addProperty("comet_version", request.build().cometVersion());
        manifest.addProperty("git_commit", request.build().gitCommit());
        manifest.addProperty("tester_suffix", request.build().testerSuffix());
        manifest.addProperty("minecraft_version", request.build().minecraftVersion());
        manifest.addProperty("meteor_version", request.build().meteorVersion());
        manifest.addProperty("debug_lines", debugLines.size());
        manifest.addProperty("debug_dropped", request.droppedDebugLines());
        JsonObject privacy = new JsonObject();
        privacy.addProperty("identities", request.redactor().includeIdentities() ? "included" : "hashed");
        privacy.addProperty("hashes", "per-export salted SHA-256 (salt not stored in this archive)");
        privacy.addProperty("chat", "excluded");
        privacy.addProperty("server_address", "excluded");
        manifest.add("privacy", privacy);
        List<Entry> entries = new ArrayList<>();
        entries.add(new Entry(BUILD_INFO, bytes(build)));
        entries.add(new Entry(SETTINGS, bytes(GSON.toJson(settings))));
        entries.add(new Entry(DEBUG_LOG, bytes(String.join("\n", debugLines)
            + (debugLines.isEmpty() ? "" : "\n"))));
        entries.add(new Entry(NOTICES, bytes(request.notices())));
        // manifest is first in the archive
        List<Entry> all = new ArrayList<>();
        JsonObject entryNames = new JsonObject();
        for (Entry entry : entries) {
            JsonObject item = new JsonObject();
            item.addProperty("bytes", entry.content().length);
            entryNames.addProperty(entry.name(), entry.content().length);
        }
        manifest.add("entries", entryNames);
        all.add(new Entry(MANIFEST, bytes(GSON.toJson(manifest))));
        all.addAll(entries);
        return List.copyOf(all);
    }

    /** Human-readable plan shown by `.crystalcombat export` before anything is written. */
    public static List<String> preview(Request request) {
        List<Entry> entries = create(request);
        List<String> lines = new ArrayList<>();
        lines.add("Combat tester export plan (" + entries.size() + " files):");
        for (Entry entry : entries) {
            lines.add("  " + entry.name() + "  " + entry.content().length + " bytes");
        }
        lines.add("Debug log: " + request.debugLines().size() + " line(s), "
            + request.droppedDebugLines() + " dropped by the rolling buffer.");
        lines.add("Privacy: other player names and UUIDs "
            + (request.redactor().includeIdentities() ? "INCLUDED (opt-in)" : "hashed with a per-export salt")
            + "; chat and server addresses are never included.");
        lines.add("Nothing has been written yet. Run `.crystalcombat export confirm` to write "
            + "comet/exports/crystalcombat-<timestamp>.zip.");
        return List.copyOf(lines);
    }

    /** Writes the prepared entries to {@code target}; fails if the file already exists. */
    public static Path write(Path target, List<Entry> entries) throws IOException {
        Objects.requireNonNull(target, "target");
        Objects.requireNonNull(entries, "entries");
        Path parent = target.toAbsolutePath().getParent();
        if (parent != null) Files.createDirectories(parent);
        try (OutputStream stream = Files.newOutputStream(target, StandardOpenOption.CREATE_NEW,
            StandardOpenOption.WRITE);
             ZipOutputStream zip = new ZipOutputStream(stream, StandardCharsets.UTF_8)) {
            for (Entry entry : entries) {
                ZipEntry zipEntry = new ZipEntry(entry.name());
                zip.putNextEntry(zipEntry);
                zip.write(entry.content());
                zip.closeEntry();
            }
        }
        return target;
    }

    private static List<String> redactDebugLines(Request request) {
        List<String> lines = new ArrayList<>(request.debugLines().size());
        for (String line : request.debugLines()) {
            if (line == null || line.isBlank()) continue;
            lines.add(request.redactor().redactLine(line));
        }
        return lines;
    }

    private static JsonObject settingsJson(Request request) {
        JsonObject root = new JsonObject();
        for (SettingValue setting : request.settings()) {
            JsonObject group = root.has(setting.group())
                ? root.getAsJsonObject(setting.group()) : new JsonObject();
            group.addProperty(setting.name(), request.redactor().redactText(setting.value()));
            root.add(setting.group(), group);
        }
        return root;
    }

    private static String buildText(Request request) {
        return "comet_version=" + request.build().cometVersion() + "\n"
            + "git_commit=" + request.build().gitCommit() + "\n"
            + "tester_suffix=" + request.build().testerSuffix() + "\n"
            + "minecraft_version=" + request.build().minecraftVersion() + "\n"
            + "meteor_version=" + request.build().meteorVersion() + "\n"
            + "format=" + FORMAT_VERSION + "\n";
    }

    private static byte[] bytes(String value) {
        return value.getBytes(StandardCharsets.UTF_8);
    }
}
