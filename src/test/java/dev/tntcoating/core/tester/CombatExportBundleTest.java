/*
 * Original Comet implementation by Boyonabike.
 * Independent: written from the pinned 26.1.2 artifact and Meteor build 42 behaviour; no third-party code.
 * SPDX-License-Identifier: CC0-1.0
 */

package dev.tntcoating.core.tester;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CombatExportBundleTest {
    private static final String UUID = "123e4567-e89b-12d3-a456-426614174000";
    private static final String NOTICES = "# THIRD_PARTY_NOTICES\nCrystalCombat is GPL-3.0-only.\n";

    @TempDir
    Path temp;

    private static CombatExportBundle.Request request(List<String> debugLines) {
        CombatExportBundle.BuildStamp build = new CombatExportBundle.BuildStamp(
            "0.27.0-tester.1", "fb59152-dirty", "tester.1", "26.2",
            "26.2-20261009.074120-35");
        List<CombatExportBundle.SettingValue> settings = List.of(
            new CombatExportBundle.SettingValue("General", "mode", "DIRECT_ONLY"),
            new CombatExportBundle.SettingValue("General", "last-target", "Alice"),
            new CombatExportBundle.SettingValue("General", "last-server", "203.0.113.9:25565"));
        CombatExportRedactor redactor = new CombatExportRedactor(7L, false, List.of("Alice"));
        return new CombatExportBundle.Request(build, settings, debugLines, 2L, NOTICES, redactor,
            1_700_000_000_000L);
    }

    private static String content(List<CombatExportBundle.Entry> entries, String name) {
        return entries.stream().filter(entry -> entry.name().equals(name)).findFirst()
            .map(entry -> new String(entry.content(), StandardCharsets.UTF_8))
            .orElseThrow();
    }

    @Test
    void createBuildsThePlannedEntrySetInMemory() {
        assertTrue(Files.isDirectory(temp) && isEmpty(temp));
        List<CombatExportBundle.Entry> entries = CombatExportBundle.create(request(List.of()));
        assertEquals(List.of(CombatExportBundle.MANIFEST, CombatExportBundle.BUILD_INFO,
            CombatExportBundle.SETTINGS, CombatExportBundle.DEBUG_LOG, CombatExportBundle.NOTICES),
            entries.stream().map(CombatExportBundle.Entry::name).toList());
        // create() must not write anything.
        assertTrue(isEmpty(temp));
    }

    @Test
    void manifestCarriesVersionsCommitAndPrivacyEvidence() {
        List<CombatExportBundle.Entry> entries = CombatExportBundle.create(request(List.of()));
        JsonObject manifest = JsonParser.parseString(
            content(entries, CombatExportBundle.MANIFEST)).getAsJsonObject();
        assertEquals(CombatExportBundle.FORMAT_VERSION, manifest.get("format").getAsString());
        assertEquals("0.27.0-tester.1", manifest.get("comet_version").getAsString());
        assertEquals("fb59152-dirty", manifest.get("git_commit").getAsString());
        assertEquals("tester.1", manifest.get("tester_suffix").getAsString());
        assertEquals("26.2-20261009.074120-35", manifest.get("meteor_version").getAsString());
        assertEquals("hashed", manifest.getAsJsonObject("privacy").get("identities").getAsString());
        assertEquals("excluded", manifest.getAsJsonObject("privacy").get("chat").getAsString());
        assertEquals("excluded",
            manifest.getAsJsonObject("privacy").get("server_address").getAsString());
        assertEquals(4, manifest.getAsJsonObject("entries").size());
        assertTrue(manifest.getAsJsonObject("entries").has(CombatExportBundle.BUILD_INFO));
        assertTrue(manifest.getAsJsonObject("entries").has(CombatExportBundle.NOTICES));
    }

    @Test
    void buildStampAndNoticesAreIncludedVerbatimEnoughToReproduceTheBuild() {
        List<CombatExportBundle.Entry> entries = CombatExportBundle.create(request(List.of()));
        String build = content(entries, CombatExportBundle.BUILD_INFO);
        assertTrue(build.contains("comet_version=0.27.0-tester.1"));
        assertTrue(build.contains("git_commit=fb59152-dirty"));
        assertTrue(build.contains("tester_suffix=tester.1"));
        assertTrue(build.contains("minecraft_version=26.2"));
        assertTrue(build.contains("meteor_version=26.2-20261009.074120-35"));
        assertEquals(NOTICES, content(entries, CombatExportBundle.NOTICES));
    }

    @Test
    void settingsAndDebugLinesAreRedactedBeforeTheyEnterTheArchive() {
        String debug = "{\"uuid\":\"" + UUID + "\",\"name\":\"Alice\",\"chat\":\"secret\","
            + "\"payload\":{\"text\":\"Alice at 203.0.113.9\"}}";
        List<CombatExportBundle.Entry> entries = CombatExportBundle.create(request(List.of(debug)));

        String settings = content(entries, CombatExportBundle.SETTINGS);
        assertTrue(settings.contains("DIRECT_ONLY"));
        assertFalse(settings.contains("Alice"));
        assertFalse(settings.contains("203.0.113.9"));

        String debugLog = content(entries, CombatExportBundle.DEBUG_LOG);
        assertFalse(debugLog.contains(UUID));
        assertFalse(debugLog.contains("Alice"));
        assertFalse(debugLog.contains("secret"));
        assertFalse(debugLog.contains("203.0.113.9"));
        assertTrue(debugLog.contains("player-"));
    }

    @Test
    void previewNamesEveryFileAndWritesNothing() {
        List<String> preview = CombatExportBundle.preview(request(List.of("{\"ok\":1}")));
        String text = String.join("\n", preview);
        for (String name : List.of(CombatExportBundle.MANIFEST, CombatExportBundle.BUILD_INFO,
            CombatExportBundle.SETTINGS, CombatExportBundle.DEBUG_LOG, CombatExportBundle.NOTICES)) {
            assertTrue(text.contains(name), () -> "preview is missing " + name);
        }
        assertTrue(text.contains("Debug log: 1 line(s)"));
        assertTrue(text.contains("hashed with a per-export salt"));
        assertTrue(text.contains("Nothing has been written yet."));
        assertTrue(text.contains(".crystalcombat export confirm"));
        assertTrue(isEmpty(temp));
    }

    @Test
    void writeProducesAReadableZipWithEveryEntry() throws Exception {
        List<CombatExportBundle.Entry> entries = CombatExportBundle.create(
            request(List.of("{\"name\":\"Alice\"}")));
        Path zip = temp.resolve("crystalcombat-export.zip");
        assertEquals(zip, CombatExportBundle.write(zip, entries));
        assertTrue(Files.size(zip) > 0);

        try (ZipFile file = new ZipFile(zip.toFile())) {
            for (CombatExportBundle.Entry entry : entries) {
                ZipEntry zipEntry = file.getEntry(entry.name());
                assertNotNull(zipEntry, () -> "missing zip entry " + entry.name());
                assertArrayEquals(entry.content(), file.getInputStream(zipEntry).readAllBytes());
            }
            assertNotNull(file.getEntry(CombatExportBundle.NOTICES));
        }
        // A second write to the same path is refused rather than silently overwritten.
        assertThrows(FileAlreadyExistsException.class,
            () -> CombatExportBundle.write(zip, entries));
    }

    @Test
    void emptyDebugLogStillProducesAFullyDescribedExport() {
        List<CombatExportBundle.Entry> entries = CombatExportBundle.create(request(List.of()));
        assertEquals("", content(entries, CombatExportBundle.DEBUG_LOG));
        JsonObject manifest = JsonParser.parseString(
            content(entries, CombatExportBundle.MANIFEST)).getAsJsonObject();
        assertEquals(0, manifest.get("debug_lines").getAsInt());
        assertEquals(2, manifest.get("debug_dropped").getAsInt());
    }

    @Test
    void departedPlayerRecordsInNestedDebugDataStayRedactedInTheArchive() {
        String debug = "{\"nested\":[{\"player\":{\"uuid\":\"" + UUID
            + "\",\"name\":\"DepartedPlayer\"}}]}";
        List<CombatExportBundle.Entry> entries = CombatExportBundle.create(request(List.of(debug)));
        String debugLog = content(entries, CombatExportBundle.DEBUG_LOG);

        assertFalse(debugLog.contains("DepartedPlayer"));
        assertFalse(debugLog.contains(UUID));
        String expected = CombatExportRedactor.pseudonym(7L, UUID);
        assertTrue(debugLog.contains(expected));

        String twice = "{\"a\":{\"uuid\":\"" + UUID + "\",\"name\":\"DepartedPlayer\"},"
            + "\"b\":{\"uuid\":\"" + UUID + "\",\"playerName\":\"DepartedPlayer\"}}";
        String repeated = content(CombatExportBundle.create(request(List.of(twice))),
            CombatExportBundle.DEBUG_LOG);
        assertEquals(4, occurrences(repeated, expected),
            "both player records and both name fields use one per-export pseudonym");
    }

    private static int occurrences(String text, String needle) {
        int count = 0;
        for (int index = text.indexOf(needle); index >= 0; index = text.indexOf(needle, index + 1)) {
            count++;
        }
        return count;
    }

    private static boolean isEmpty(Path directory) {
        try (var stream = Files.list(directory)) {
            return stream.findAny().isEmpty();
        } catch (Exception failure) {
            throw new AssertionError(failure);
        }
    }
}
