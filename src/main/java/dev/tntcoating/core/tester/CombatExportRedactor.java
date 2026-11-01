/*
 * Original Comet implementation by Boyonabike.
 * Independent: written from the pinned 1.21.11 artifact and Meteor build 82 behaviour; no third-party code.
 * SPDX-License-Identifier: CC0-1.0
 */

package dev.tntcoating.core.tester;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.JsonPrimitive;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Per-export privacy redactor. By default other players' names and every UUID are replaced with a stable,
 * salted pseudonym, while chat and server addresses are always removed even when identities are opted in.
 *
 * <p>Names are hashed through two paths. The current player list is matched as plain text and hashed by name,
 * and any JSON object that carries a UUID-like {@code uuid} field has its {@code name}/{@code playerName}/
 * {@code displayName} values replaced with that UUID's pseudonym at any depth. The second path does not depend
 * on the player still being online, so an export taken after a fight or a disconnect cannot leak the name of a
 * player who left.</p>
 *
 * <p>The salt is generated fresh for each export and is never written into the archive, so pseudonyms cannot
 * be correlated across exports. Hashing is SHA-256 over {@code salt:identity}; the export only ever contains
 * the first eight hex digits.</p>
 */
public final class CombatExportRedactor {
    private static final Pattern UUID = Pattern.compile(
        "\\b[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}\\b");
    private static final Pattern IPV4_WITH_PORT = Pattern.compile(
        "\\b(?:\\d{1,3}\\.){3}\\d{1,3}(?::\\d{1,5})?\\b");
    private static final Pattern IPV6 = Pattern.compile(
        "\\b[0-9a-fA-F]{1,4}(?::[0-9a-fA-F]{0,4}){2,7}\\b");
    /** Keys never emitted, regardless of the identities setting. */
    public static final Set<String> SENSITIVE_KEYS = Set.of(
        "chat", "chatmessage", "chat_message", "message", "messages", "server", "serveraddress",
        "server_address", "serverip", "server_ip", "address", "ip", "socketaddress", "hostname");

    private final long salt;
    private final boolean includeIdentities;
    private final List<String> otherPlayerNames;
    private int redactions;

    public CombatExportRedactor(long salt, boolean includeIdentities,
                                Collection<String> otherPlayerNames) {
        this.salt = salt;
        this.includeIdentities = includeIdentities;
        Set<String> names = new LinkedHashSet<>();
        if (otherPlayerNames != null) {
            for (String name : otherPlayerNames) {
                if (name != null && !name.isBlank()) names.add(name);
            }
        }
        List<String> sorted = new ArrayList<>(names);
        sorted.sort(Comparator.comparingInt(String::length).reversed().thenComparing(Comparator.naturalOrder()));
        this.otherPlayerNames = List.copyOf(sorted);
    }

    public boolean includeIdentities() {
        return includeIdentities;
    }

    /** Number of identity/address/chat redactions applied so far. Used as export evidence in tests. */
    public int redactions() {
        return redactions;
    }

    /** Redacts one JSON Lines record; input is expected to be the recorder's JSON. */
    public String redactLine(String line) {
        if (line == null || line.isEmpty()) return "";
        JsonElement parsed;
        try {
            parsed = JsonParser.parseString(line);
        } catch (RuntimeException malformed) {
            return redactText(line);
        }
        return redactElement(parsed).toString();
    }

    /** Redacts free text or a settings value; always removes addresses, optionally identities. */
    public String redactText(String text) {
        if (text == null || text.isEmpty()) return "";
        String redacted = text;
        // Addresses are never tester data, even when identities are included.
        redacted = replaceAll(IPV4_WITH_PORT, redacted, "[redacted-address]");
        redacted = replaceAll(IPV6, redacted, "[redacted-address]");
        if (includeIdentities) return redacted;
        redacted = replaceAll(UUID, redacted, match -> pseudonym(salt, match.group()));
        for (String name : otherPlayerNames) {
            if (redacted.contains(name)) {
                String pseudonym = pseudonym(salt, "name:" + name);
                redacted = redacted.replace(name, pseudonym);
            }
        }
        return redacted;
    }

    /** Stable per-export pseudonym; the same salt and identity always map to the same short label. */
    public static String pseudonym(long salt, String identity) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest((salt + ":" + identity)
                .getBytes(StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder(8);
            for (int i = 0; i < 4; i++) {
                hex.append(String.format(Locale.ROOT, "%02x", hash[i]));
            }
            return "player-" + hex;
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 unavailable", impossible);
        }
    }

    private JsonElement redactElement(JsonElement element) {
        if (element == null || element.isJsonNull()) return element;
        if (element.isJsonArray()) {
            JsonArray output = new JsonArray();
            for (JsonElement value : element.getAsJsonArray()) output.add(redactElement(value));
            return output;
        }
        if (!element.isJsonObject()) {
            if (element.isJsonPrimitive() && element.getAsJsonPrimitive().isString()) {
                return new JsonPrimitive(redactText(element.getAsString()));
            }
            return element;
        }
        JsonObject input = element.getAsJsonObject();
        // A player record keeps its name even after the player leaves the level, so hash name fields from the
        // object's own UUID marker instead of relying only on the current player list. The name maps to the
        // same per-export pseudonym as that UUID, so the record cannot be used to recover either identity.
        String playerUuid = includeIdentities ? null : playerUuid(input);
        JsonObject output = new JsonObject();
        for (String key : input.keySet()) {
            if (isSensitiveKey(key)) {
                redactions++;
                continue;
            }
            JsonElement value = input.get(key);
            if (playerUuid != null && isPlayerNameKey(key) && isString(value)) {
                String name = value.getAsString();
                if (!name.isBlank()) {
                    output.addProperty(key, pseudonym(salt, playerUuid));
                    redactions++;
                    continue;
                }
            }
            output.add(key, redactElement(value));
        }
        return output;
    }

    /** The first UUID-like {@code uuid} field on the object, or null when it carries none. */
    private static String playerUuid(JsonObject object) {
        for (String key : object.keySet()) {
            if (!"uuid".equals(normalizeKey(key))) continue;
            JsonElement value = object.get(key);
            if (isString(value) && UUID.matcher(value.getAsString()).matches()) {
                return value.getAsString();
            }
        }
        return null;
    }

    private static boolean isPlayerNameKey(String key) {
        String normalized = normalizeKey(key);
        return normalized.equals("name") || normalized.equals("playername")
            || normalized.equals("displayname");
    }

    private static boolean isString(JsonElement value) {
        return value != null && value.isJsonPrimitive() && value.getAsJsonPrimitive().isString();
    }

    private static String normalizeKey(String key) {
        return key == null ? "" : key.toLowerCase(Locale.ROOT).replace("-", "").replace("_", "");
    }

    private boolean isSensitiveKey(String key) {
        if (key == null) return false;
        String normalized = key.toLowerCase(Locale.ROOT)
            .replace("-", "").replace("_", "");
        if (SENSITIVE_KEYS.contains(normalized)) return true;
        return normalized.contains("chat") || normalized.contains("serveraddress")
            || normalized.contains("serverip");
    }

    private String replaceAll(Pattern pattern, String text, String replacement) {
        java.util.regex.Matcher matcher = pattern.matcher(text);
        StringBuilder output = new StringBuilder();
        boolean found = false;
        while (matcher.find()) {
            found = true;
            matcher.appendReplacement(output,
                java.util.regex.Matcher.quoteReplacement(replacement));
        }
        if (!found) return text;
        matcher.appendTail(output);
        redactions++;
        return output.toString();
    }

    private String replaceAll(Pattern pattern, String text,
                              java.util.function.Function<java.util.regex.Matcher, String> replacement) {
        java.util.regex.Matcher matcher = pattern.matcher(text);
        StringBuilder output = new StringBuilder();
        boolean found = false;
        while (matcher.find()) {
            found = true;
            matcher.appendReplacement(output,
                java.util.regex.Matcher.quoteReplacement(replacement.apply(matcher)));
        }
        if (!found) return text;
        matcher.appendTail(output);
        redactions++;
        return output.toString();
    }
}
