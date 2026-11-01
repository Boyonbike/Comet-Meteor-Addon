/*
 * Original Comet implementation by Boyonabike.
 * Independent: written from the pinned 1.21.11 artifact and Meteor build 82 behaviour; no third-party code.
 * SPDX-License-Identifier: CC0-1.0
 */

package dev.tntcoating.core.tester;

import java.io.IOException;
import java.io.InputStream;
import java.util.Objects;
import java.util.Properties;

/**
 * The build stamp embedded in the JAR by {@code processResources}. It is deliberately a plain data class so
 * the manifest/stamp helpers can be unit-tested without Minecraft, Meteor or a running game.
 *
 * <p>{@link #load()} reads {@code /comet-build.properties}, which Gradle expands from
 * {@code src/main/resources/comet-build.properties}. When the resource is missing or unexpanded (for
 * example a raw IDE classpath) every field falls back safely instead of failing module registration.</p>
 */
public record BuildInfo(String version, String commit, String testerSuffix) {
    public static final BuildInfo UNKNOWN = new BuildInfo("dev", "unknown", "");

    public BuildInfo {
        version = blankTo(version, "dev");
        commit = blankTo(commit, "unknown");
        testerSuffix = testerSuffix == null ? "" : testerSuffix.trim();
    }

    /** True when this is an opt-in tester build. */
    public boolean tester() {
        return !testerSuffix.isEmpty();
    }

    /** Compact, human-readable stamp: {@code v0.27.0-tester.1 fb59152-dirty}. */
    public String display() {
        return "v" + version + " " + commit;
    }

    /** Manifest-friendly tester marker: the suffix, or {@code release}. */
    public String testerLabel() {
        return tester() ? testerSuffix : "release";
    }

    /** Parses a build-stamp properties bundle, treating unexpanded placeholders as absent. */
    public static BuildInfo parse(Properties properties) {
        Objects.requireNonNull(properties, "properties");
        return new BuildInfo(
            value(properties, "version"), value(properties, "commit"), value(properties, "tester"));
    }

    /** Loads the embedded stamp. Never throws; a broken resource becomes {@link #UNKNOWN}. */
    public static BuildInfo load() {
        try (InputStream in = BuildInfo.class.getResourceAsStream("/comet-build.properties")) {
            if (in == null) return UNKNOWN;
            Properties properties = new Properties();
            properties.load(in);
            BuildInfo parsed = parse(properties);
            return "dev".equals(parsed.version()) && "unknown".equals(parsed.commit()) ? UNKNOWN : parsed;
        } catch (IOException | RuntimeException ignored) {
            return UNKNOWN;
        }
    }

    private static String value(Properties properties, String key) {
        String raw = properties.getProperty(key);
        if (raw == null) return "";
        String trimmed = raw.trim();
        // An unexpanded Gradle placeholder means processResources did not run on this classpath.
        if (trimmed.startsWith("${") && trimmed.endsWith("}")) return "";
        return trimmed;
    }

    private static String blankTo(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value.trim();
    }
}
