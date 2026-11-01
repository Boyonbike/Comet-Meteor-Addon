/*
 * Original Comet implementation by Boyonabike.
 * Independent: written from the pinned 1.21.11 artifact and Meteor build 82 behaviour; no third-party code.
 * SPDX-License-Identifier: CC0-1.0
 */

package dev.tntcoating.core.tester;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CombatExportRedactorTest {
    private static final String UUID = "123e4567-e89b-12d3-a456-426614174000";
    private static final long SALT = 42L;

    private static String line(String payload) {
        return "{\"conn\":1,\"payload\":" + payload + "}";
    }

    @Test
    void uuidAndPlayerNamesBecomeStablePerExportPseudonyms() {
        CombatExportRedactor redactor = new CombatExportRedactor(SALT, false, List.of("Alice"));
        String raw = line("{\"uuid\":\"" + UUID + "\",\"name\":\"Alice\",\"text\":\"Alice\"}");
        String first = redactor.redactLine(raw);

        assertFalse(first.contains(UUID));
        assertFalse(first.contains("Alice"));
        assertTrue(first.contains(CombatExportRedactor.pseudonym(SALT, UUID)));
        assertTrue(first.contains(CombatExportRedactor.pseudonym(SALT, "name:Alice")));
        assertTrue(redactor.redactions() > 0);

        // Stable within one export: same identity + same salt => same pseudonym.
        String second = redactor.redactLine(raw);
        assertTrue(second.contains(CombatExportRedactor.pseudonym(SALT, UUID)));
        assertEquals(first, second);
    }

    @Test
    void differentExportsUseDifferentPseudonyms() {
        String raw = line("{\"uuid\":\"" + UUID + "\"}");
        String first = new CombatExportRedactor(1L, false, List.of()).redactLine(raw);
        String second = new CombatExportRedactor(2L, false, List.of()).redactLine(raw);
        assertFalse(first.equals(second));
        assertTrue(first.contains(CombatExportRedactor.pseudonym(1L, UUID)));
        assertTrue(second.contains(CombatExportRedactor.pseudonym(2L, UUID)));
    }

    @Test
    void chatAndServerFieldsAreAlwaysDropped() {
        CombatExportRedactor redactor = new CombatExportRedactor(SALT, false, List.of());
        String raw = line("{\"chat\":\"secret\",\"serverAddress\":\"mc.example.net\","
            + "\"message\":\"hi\",\"nested\":{\"server\":\"x\",\"ok\":1}}");
        String redacted = redactor.redactLine(raw);
        assertFalse(redacted.contains("chat"));
        assertFalse(redacted.contains("secret"));
        assertFalse(redacted.contains("mc.example.net"));
        assertFalse(redacted.contains("message"));
        assertFalse(redacted.contains("\"server\""));
        assertTrue(redacted.contains("\"ok\""));
    }

    @Test
    void addressesAreScrubbedEvenWhenIdentitiesAreIncluded() {
        CombatExportRedactor redactor = new CombatExportRedactor(SALT, true, List.of("Alice"));
        String redacted = redactor.redactText("Alice from 203.0.113.9:25565 and 2001:db8::1");
        assertTrue(redacted.contains("Alice"));
        assertFalse(redacted.contains("203.0.113.9"));
        assertFalse(redacted.contains("2001:db8::1"));
        assertTrue(redacted.contains("[redacted-address]"));
    }

    @Test
    void identitiesAreKeptWhenOptedIn() {
        CombatExportRedactor redactor = new CombatExportRedactor(SALT, true, List.of("Alice"));
        String redacted = redactor.redactLine(line("{\"uuid\":\"" + UUID + "\",\"name\":\"Alice\"}"));
        assertTrue(redacted.contains(UUID));
        assertTrue(redacted.contains("Alice"));
    }

    @Test
    void settingsTextIsRedactedThroughTheSameRules() {
        CombatExportRedactor redactor = new CombatExportRedactor(SALT, false, List.of("Alice"));
        String value = redactor.redactText("Alice " + UUID);
        assertFalse(value.contains("Alice"));
        assertFalse(value.contains(UUID));
        assertEquals("", redactor.redactText(null));
        assertEquals("", redactor.redactText(""));
    }

    @Test
    void malformedJsonFallsBackToPlainTextRedaction() {
        CombatExportRedactor redactor = new CombatExportRedactor(SALT, false, List.of("Alice"));
        String redacted = redactor.redactLine("Alice connects to 203.0.113.9");
        assertFalse(redacted.contains("Alice"));
        assertFalse(redacted.contains("203.0.113.9"));
    }

    @Test
    void pseudonymsAreShortAndSaltDependent() {
        String pseudonym = CombatExportRedactor.pseudonym(SALT, UUID);
        assertTrue(pseudonym.startsWith("player-"));
        assertEquals("player-".length() + 8, pseudonym.length());
        assertFalse(pseudonym.equals(CombatExportRedactor.pseudonym(SALT + 1, UUID)));
    }

    @Test
    void playerRecordNameIsHashedEvenWhenThePlayerIsNotInTheCurrentLevelList() {
        // Simulates a fight against a player who has since left: only the record's own UUID identifies them.
        CombatExportRedactor redactor = new CombatExportRedactor(SALT, false, List.of());
        String raw = line("{\"nested\":[{\"player\":{\"uuid\":\"" + UUID
            + "\",\"name\":\"SomePlayerWhoLeft\"}}]}");
        String first = redactor.redactLine(raw);

        assertFalse(first.contains("SomePlayerWhoLeft"), "a departed player's name must not leak");
        assertFalse(first.contains(UUID));
        // The player's name and UUID both use that UUID's per-export pseudonym.
        assertEquals(2, occurrences(first, CombatExportRedactor.pseudonym(SALT, UUID)));
        // Same identity within one export: stable hash.
        assertEquals(first, redactor.redactLine(raw));
    }

    @Test
    void playerNameAliasesAndNestedArraysAreHashedAtAnyDepth() {
        CombatExportRedactor redactor = new CombatExportRedactor(SALT, false, List.of());
        String raw = line("{\"groups\":[[{\"profile\":{\"uuid\":\"" + UUID + "\",\"playerName\":\"Ghost\"}},"
            + "{\"other\":{\"display_name\":\"Ghost\",\"UUID\":\"" + UUID + "\"}}]]}");
        String redacted = redactor.redactLine(raw);

        assertFalse(redacted.contains("Ghost"));
        assertFalse(redacted.contains(UUID));
        // Two UUID fields and two name fields for the same player => one pseudonym, four occurrences.
        assertEquals(4, occurrences(redacted, CombatExportRedactor.pseudonym(SALT, UUID)),
            "playerName and display_name under a UUID marker must use that UUID's pseudonym");
    }

    @Test
    void namesWithoutAPlayerUuidMarkerAreLeftAlone() {
        CombatExportRedactor redactor = new CombatExportRedactor(SALT, false, List.of());
        String raw = line("{\"item\":{\"name\":\"obsidian\"},"
            + "\"entity\":{\"name\":\"minecraft:end_crystal\",\"type\":\"minecraft:end_crystal\"}}");
        String redacted = redactor.redactLine(raw);
        assertTrue(redacted.contains("obsidian"));
        assertTrue(redacted.contains("minecraft:end_crystal"));
    }

    @Test
    void departedPlayerNamesHashDifferentlyAcrossExports() {
        String raw = line("{\"player\":{\"uuid\":\"" + UUID + "\",\"name\":\"Ghost\"}}");
        String first = new CombatExportRedactor(1L, false, List.of()).redactLine(raw);
        String second = new CombatExportRedactor(2L, false, List.of()).redactLine(raw);
        assertFalse(first.equals(second));
        assertTrue(first.contains(CombatExportRedactor.pseudonym(1L, UUID)));
        assertTrue(second.contains(CombatExportRedactor.pseudonym(2L, UUID)));
    }

    private static int occurrences(String text, String needle) {
        int count = 0;
        for (int index = text.indexOf(needle); index >= 0; index = text.indexOf(needle, index + 1)) {
            count++;
        }
        return count;
    }

    @Test
    void identitiesOptInKeepsPlayerRecordFields() {
        CombatExportRedactor redactor = new CombatExportRedactor(SALT, true, List.of());
        String raw = line("{\"player\":{\"uuid\":\"" + UUID + "\",\"name\":\"Ghost\"}}");
        String redacted = redactor.redactLine(raw);
        assertTrue(redacted.contains(UUID));
        assertTrue(redacted.contains("Ghost"));
    }
}
