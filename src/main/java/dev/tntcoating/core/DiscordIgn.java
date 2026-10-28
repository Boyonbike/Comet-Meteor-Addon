package dev.tntcoating.core;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Pattern;

/**
 * Reads the guild member list and the #ign channel history as Discord returns them, and turns the latest message per
 * Discord user into Minecraft usernames. Pure: the HTTP calls live in {@link DiscordIgnClient}.
 */
public final class DiscordIgn {
    /**
     * What one sync found: links from Discord user ID to their Java accounts, the distinct IGNs in name order, how many members were
     * considered, how many messages were not valid account lists, the IGNs two users both claim, and whether the messages
     * came back without content (the Message Content Intent is off).
     */
    public record Result(Map<String, List<String>> links, List<String> names, int members, int invalid, List<String> conflicts,
                         boolean unreadable, Map<String, List<String>> roleIds, Map<String, String> usernames) {}

    /** Minecraft Java usernames: 3-16 letters, digits and underscores. */
    private static final Pattern IGN = Pattern.compile("[A-Za-z0-9_]{3,16}");
    private static final Pattern BEDROCK = Pattern.compile("\\.[A-Za-z0-9_]{1,16}");
    private static final Pattern ACCOUNT_SEPARATORS = Pattern.compile("\\s*[,;/]\\s*|\\R+");

    private DiscordIgn() {}

    /**
     * @param memberPages  JSON arrays from {@code GET /guilds/{id}/members}, in page order
     * @param messagePages JSON arrays from {@code GET /channels/{id}/messages}, newest page first
     */
    public static Result parse(List<String> memberPages, List<String> messagePages) {
        Map<String, List<String>> members = new LinkedHashMap<>();
        Map<String, String> discordUsernames = new LinkedHashMap<>();
        for (String page : memberPages) {
            for (JsonElement element : JsonParser.parseString(page).getAsJsonArray()) {
                if (!element.isJsonObject()) throw new IllegalArgumentException("Malformed Discord record");
                JsonObject member = element.getAsJsonObject();
                JsonObject user = object(member, "user");
                // Bots never speak for a player, and pending members have not accepted the rules yet.
                if (user == null || string(user, "id").isEmpty() || array(member, "roles") == null)
                    throw new IllegalArgumentException("Malformed Discord member");
                if (bool(user, "bot") || bool(member, "pending")) continue;
                List<String> roles = strings(array(member, "roles"));
                String id = string(user, "id");
                if (!id.isEmpty()) {
                    members.put(id, roles);
                    String username = cleanUsername(string(user, "username"));
                    if (!username.isEmpty()) discordUsernames.put(id, username);
                }
            }
        }

        // Discord returns the newest message first, so the first message seen per author is that user's latest claim.
        Map<String, List<String>> claims = new LinkedHashMap<>();
        int invalid = 0, userMessages = 0, readable = 0;
        for (String page : messagePages) {
            for (JsonElement element : JsonParser.parseString(page).getAsJsonArray()) {
                if (!element.isJsonObject()) throw new IllegalArgumentException("Malformed Discord record");
                JsonObject message = element.getAsJsonObject();
                JsonObject author = object(message, "author");
                if (author == null || string(author, "id").isEmpty())
                    throw new IllegalArgumentException("Malformed Discord message author");
                if (bool(author, "bot")) continue;
                String id = string(author, "id");
                // A #ign post only speaks for a member the sync considered; other posters are ignored entirely.
                if (!members.containsKey(id)) continue;
                String username = cleanUsername(string(author, "username"));
                if (!username.isEmpty()) discordUsernames.putIfAbsent(id, username);
                userMessages++;
                String content = string(message, "content");
                if (content.isBlank()) continue;
                readable++;
                List<String> names = claim(content);
                if (names == null) {
                    invalid++;
                    continue;
                }
                claims.putIfAbsent(id, names);
            }
        }

        // One IGN announced by two Discord users cannot be resolved automatically, so it is left out until fixed.
        Map<String, String> spellings = new LinkedHashMap<>();
        Map<String, Set<String>> claimants = new LinkedHashMap<>();
        claims.forEach((id, names) -> {
            for (String ign : names) {
                String key = ign.toLowerCase(Locale.ROOT);
                spellings.putIfAbsent(key, ign);
                claimants.computeIfAbsent(key, ignored -> new HashSet<>()).add(id);
            }
        });
        List<String> conflicts = new ArrayList<>();
        Map<String, List<String>> links = new LinkedHashMap<>();
        Map<String, List<String>> accountRoles = new LinkedHashMap<>();
        Map<String, String> accountUsernames = new LinkedHashMap<>();
        TreeSet<String> unique = new TreeSet<>(String.CASE_INSENSITIVE_ORDER);
        claims.forEach((id, names) -> {
            List<String> accepted = new ArrayList<>();
            for (String ign : names) {
                String key = ign.toLowerCase(Locale.ROOT);
                if (claimants.get(key).size() > 1) {
                    String conflict = spellings.get(key);
                    if (!conflicts.contains(conflict)) conflicts.add(conflict);
                } else {
                    accepted.add(ign);
                    unique.add(ign);
                    accountRoles.put(ign, members.get(id));
                    if (discordUsernames.containsKey(id)) accountUsernames.put(ign, discordUsernames.get(id));
                }
            }
            if (!accepted.isEmpty()) links.put(id, List.copyOf(accepted));
        });

        conflicts.sort(String.CASE_INSENSITIVE_ORDER);
        boolean unreadable = readable < userMessages;
        return new Result(links, List.copyOf(unique), members.size(), invalid, List.copyOf(conflicts), unreadable, Map.copyOf(accountRoles), Map.copyOf(accountUsernames));
    }

    /** Username metadata is saved separately from commands/IGNs, so labels never become teleport targets. */
    public static String encodeUsernames(Map<String, String> usernames) {
        JsonObject root = new JsonObject();
        usernames.entrySet().stream().sorted(Map.Entry.comparingByKey()).forEach(entry ->
            root.addProperty(entry.getKey().toLowerCase(Locale.ROOT), cleanUsername(entry.getValue())));
        return root.toString();
    }

    public static Map<String, String> decodeUsernames(String json) {
        if (json == null || json.isBlank()) return Map.of();
        try {
            Map<String, String> result = new LinkedHashMap<>();
            for (var entry : JsonParser.parseString(json).getAsJsonObject().entrySet()) {
                if (!ChatMessages.validName(entry.getKey()) || !entry.getValue().isJsonPrimitive()
                    || !entry.getValue().getAsJsonPrimitive().isString()) return Map.of();
                String username = cleanUsername(entry.getValue().getAsString());
                if (!username.isEmpty()) result.put(entry.getKey().toLowerCase(Locale.ROOT), username);
            }
            return Map.copyOf(result);
        } catch (RuntimeException exception) { return Map.of(); }
    }

    private static String cleanUsername(String username) {
        return username.replaceAll("§.", "").replaceAll("[\\p{Cntrl}§]", "").strip();
    }

    /**
     * Applies the synced names to a list while keeping names the player added by hand: only names an earlier sync
     * added are removed again, and names already present in another case are not duplicated.
     */
    public static List<String> reconcile(List<String> current, List<String> managed, List<String> next) {
        List<String> result = ChatClan.members(current);
        Set<String> kept = ChatClan.keys(next);
        for (String name : ChatClan.members(managed)) {
            if (!kept.contains(name.toLowerCase(Locale.ROOT))) result = ChatClan.remove(result, name);
        }
        return ChatClan.add(result, next);
    }

    public record Update(List<String> names, List<String> managed) {}

    /** Old configs recorded all synced names as owned, including manual overlaps; that metadata is untrustworthy. */
    public static Update update(List<String> current, List<String> managed, List<String> next, boolean trustedOwnership) {
        List<String> owned = trustedOwnership ? managed : List.of();
        return new Update(reconcile(current, owned, next), managedNames(current, owned, next));
    }

    /** Only entries actually introduced by sync are owned; overlap with manual entries stays manual. */
    public static List<String> managedNames(List<String> current, List<String> managed, List<String> next) {
        Set<String> existing = ChatClan.keys(current);
        Set<String> owned = ChatClan.keys(managed);
        return ChatClan.members(next).stream().filter(name -> {
            String key = name.toLowerCase(Locale.ROOT);
            return owned.contains(key) || !existing.contains(key);
        }).toList();
    }

    /** A complete account list, or null for prose/malformed input. A Bedrock-only list has no Java accounts. */
    static List<String> claim(String content) {
        String text = unquote(content.strip());
        if (text.regionMatches(true, 0, "ign:", 0, 4)) text = text.substring(4).strip();
        text = unquote(text);
        Map<String, String> names = new LinkedHashMap<>();
        for (String part : ACCOUNT_SEPARATORS.split(text, -1)) {
            String name = unquote(part.strip());
            if (BEDROCK.matcher(name).matches()) continue;
            if (!IGN.matcher(name).matches()) return null;
            names.putIfAbsent(name.toLowerCase(Locale.ROOT), name);
        }
        return List.copyOf(names.values());
    }

    private static String unquote(String text) {
        if (text.length() >= 2 && text.startsWith("`") && text.indexOf('`', 1) == text.length() - 1)
            return text.substring(1, text.length() - 1).strip();
        return text;
    }

    private static JsonObject object(JsonObject object, String key) {
        JsonElement element = object.get(key);
        return element != null && element.isJsonObject() ? element.getAsJsonObject() : null;
    }

    private static JsonArray array(JsonObject object, String key) {
        JsonElement element = object.get(key);
        return element != null && element.isJsonArray() ? element.getAsJsonArray() : null;
    }

    private static String string(JsonObject object, String key) {
        JsonElement element = object.get(key);
        if (element == null || element.isJsonNull()) return "";
        if (!element.isJsonPrimitive() || !element.getAsJsonPrimitive().isString())
            throw new IllegalArgumentException("Malformed Discord " + key);
        return element.getAsString();
    }

    private static boolean bool(JsonObject object, String key) {
        JsonElement element = object.get(key);
        if (element == null || element.isJsonNull()) return false;
        if (!element.isJsonPrimitive() || !element.getAsJsonPrimitive().isBoolean())
            throw new IllegalArgumentException("Malformed Discord " + key);
        return element.getAsBoolean();
    }

    private static List<String> strings(JsonArray array) {
        if (array == null) return List.of();
        List<String> values = new ArrayList<>();
        for (JsonElement element : array) {
            if (!element.isJsonPrimitive() || !element.getAsJsonPrimitive().isString())
                throw new IllegalArgumentException("Malformed Discord member role");
            values.add(element.getAsString());
        }
        return List.copyOf(values);
    }
}
