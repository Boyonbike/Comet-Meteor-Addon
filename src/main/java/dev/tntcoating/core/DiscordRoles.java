package dev.tntcoating.core;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.io.IOException;
import java.math.BigInteger;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.HashSet;
import java.util.Set;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.Predicate;

/** Discord role labels, hierarchy ordering and saved roster metadata. */
public final class DiscordRoles {
    public record Role(String id, String name, int position) {}
    public record Groups(List<String> friends, List<String> others) {}
    private static final Comparator<Role> HIERARCHY = Comparator.comparingInt(Role::position).reversed()
        .thenComparing(role -> new BigInteger(role.id()));

    private DiscordRoles() {}

    /** Resolve every accepted account's roles; a missing definition must not publish partial metadata. */
    public static Map<String, List<Role>> bind(Map<String, List<String>> roleIds, String guildId, String json)
        throws IOException {
        try {
            Map<String, Role> definitions = new LinkedHashMap<>();
            for (JsonElement element : JsonParser.parseString(json).getAsJsonArray()) {
                Role role = readRole(element.getAsJsonObject());
                if (!role.id().equals(guildId)) definitions.put(role.id(), role);
            }
            Map<String, List<Role>> result = new LinkedHashMap<>();
            for (var entry : roleIds.entrySet()) {
                List<Role> roles = new ArrayList<>();
                for (String id : entry.getValue()) {
                    if (id.equals(guildId)) continue;
                    Role role = definitions.get(id);
                    if (role == null) throw new IOException("Discord member refers to an unknown role; sync again.");
                    if (!roles.contains(role)) roles.add(role);
                }
                roles.sort(HIERARCHY);
                result.put(key(entry.getKey()), List.copyOf(roles));
            }
            return Map.copyOf(result);
        } catch (RuntimeException exception) {
            throw new IOException("Discord returned malformed role data.", exception);
        }
    }

    /** Distinct saved role IDs in Discord hierarchy order, including roles used by alternate accounts. */
    public static List<Role> availableRoles(Map<String, List<Role>> roster) {
        Map<String, Role> roles = new LinkedHashMap<>();
        for (var memberRoles : roster.values()) for (Role role : memberRoles) roles.putIfAbsent(role.id(), role);
        return roles.values().stream().sorted(HIERARCHY).toList();
    }

    /** Only positive ID matches admit accounts; missing metadata or unknown roles match nobody. */
    public static Set<String> matchingMembers(Map<String, List<Role>> roster, Set<String> roleIds) {
        if (roleIds.isEmpty()) return Set.of();
        Set<String> members = new HashSet<>();
        for (var entry : roster.entrySet()) {
            if (entry.getValue().stream().anyMatch(role -> roleIds.contains(role.id()))) members.add(key(entry.getKey()));
        }
        return Set.copyOf(members);
    }

    /**
     * The relay a saved roster came from, so its permissions cannot outlive the source. Never blank, so a missing
     * saved source cannot match an unconfigured connection.
     */
    public static String source(String relay) { return "relay " + DiscordConnection.relay(relay); }

    /**
     * Selected-role access counts only while the roster came from the relay being synced now.
     * A missing, blank or stale source admits nobody until the next successful sync rewrites it.
     */
    public static Set<String> authorizedMembers(Map<String, List<Role>> roster, Set<String> roleIds,
                                                String savedSource, String relay) {
        if (savedSource == null || !savedSource.equals(source(relay))) return Set.of();
        return matchingMembers(roster, roleIds);
    }

    public static String label(Map<String, List<Role>> roster, String player) {
        var roles = roster.get(key(player));
        if (roles == null) return "";
        if (roles.isEmpty()) return "No role";
        return String.join(" · ", roles.stream().map(Role::name).toList());
    }

    /** Friends preserve their online-first ordering; everyone else follows Discord's role hierarchy. */
    public static Groups groups(List<String> names, Predicate<String> friend, Predicate<String> online,
                                Map<String, List<Role>> roster) {
        List<String> friends = new ArrayList<>(), others = new ArrayList<>();
        for (String name : names) (friend.test(name) ? friends : others).add(name);
        friends.sort(Comparator.comparing((String name) -> !online.test(name)).thenComparing(String.CASE_INSENSITIVE_ORDER));
        others.sort((a, b) -> {
            List<Role> left = roster.getOrDefault(key(a), List.of()), right = roster.getOrDefault(key(b), List.of());
            int rank = left.isEmpty() ? (right.isEmpty() ? 0 : 1)
                : right.isEmpty() ? -1 : HIERARCHY.compare(left.getFirst(), right.getFirst());
            return rank == 0 ? String.CASE_INSENSITIVE_ORDER.compare(a, b) : rank;
        });
        return new Groups(List.copyOf(friends), List.copyOf(others));
    }

    public static String encode(Map<String, List<Role>> roster) {
        JsonObject root = new JsonObject();
        roster.entrySet().stream().sorted(Map.Entry.comparingByKey()).forEach(entry -> {
            JsonArray roles = new JsonArray();
            for (Role role : entry.getValue()) {
                JsonObject item = new JsonObject();
                item.addProperty("id", role.id());
                item.addProperty("name", role.name());
                item.addProperty("position", role.position());
                roles.add(item);
            }
            root.add(key(entry.getKey()), roles);
        });
        return root.toString();
    }

    /** Corrupt local metadata hides badges until the next successful sync; the roster itself stays available. */
    public static Map<String, List<Role>> decode(String json) {
        if (json == null || json.isBlank()) return Map.of();
        try {
            Map<String, List<Role>> result = new LinkedHashMap<>();
            for (var entry : JsonParser.parseString(json).getAsJsonObject().entrySet()) {
                if (!ChatMessages.validName(entry.getKey())) throw new IllegalArgumentException("Invalid account");
                List<Role> roles = new ArrayList<>();
                for (JsonElement item : entry.getValue().getAsJsonArray()) roles.add(readRole(item.getAsJsonObject()));
                roles.sort(HIERARCHY);
                result.put(key(entry.getKey()), List.copyOf(roles));
            }
            return Map.copyOf(result);
        } catch (RuntimeException exception) { return Map.of(); }
    }

    private static Role readRole(JsonObject object) {
        String id = object.get("id").getAsString();
        if (!id.matches("[0-9]+")) throw new IllegalArgumentException("Invalid role ID");
        String name = object.get("name").getAsString().replaceAll("§.", "").replaceAll("[\\p{Cntrl}§]", "").strip();
        int position = object.get("position").getAsInt();
        if (name.isEmpty() || position < 0) throw new IllegalArgumentException("Invalid role");
        return new Role(id, name, position);
    }

    private static String key(String name) { return name.toLowerCase(Locale.ROOT); }

}
