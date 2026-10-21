package dev.tntcoating.core;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;

/**
 * Recent interactions, rather than an alphabetical copy of the server's tab list. They are saved per server/account in
 * a Meteor string-list setting, newest first, so they survive rejoins and restarts. Entries read "server|Name".
 */
public final class ChatRecentPlayers {
    public static final int MAX = 100;
    private final LinkedHashMap<String, String> players = new LinkedHashMap<>();
    public void touch(String player) {
        if (!ChatMessages.validName(player)) return;
        String key = player.toLowerCase(Locale.ROOT);
        players.remove(key);
        players.put(key, player);
        if (players.size() > MAX) players.remove(players.keySet().iterator().next());
    }
    public List<String> names() { return List.copyOf(players.values()).reversed(); }
    /** Replaces the list with saved names, newest first. */
    public void restore(List<String> newestFirst) {
        players.clear();
        for (String player : newestFirst.reversed()) touch(player);
    }
    public void clear() { players.clear(); }

    private static String prefix(String server) {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(server.getBytes(StandardCharsets.UTF_8)) + "|";
    }
    public static List<String> read(List<String> entries, String server) {
        String prefix = prefix(server);
        return entries.stream().filter(e -> e.startsWith(prefix)).map(e -> e.substring(prefix.length()))
            .filter(ChatMessages::validName).toList();
    }
    public static List<String> write(List<String> entries, String server, List<String> newestFirst) {
        String prefix = prefix(server);
        List<String> result = new ArrayList<>(entries.stream().filter(e -> !e.startsWith(prefix)).toList());
        newestFirst.stream().filter(ChatMessages::validName).limit(MAX).forEach(player -> result.add(prefix + player));
        return List.copyOf(result);
    }
}
