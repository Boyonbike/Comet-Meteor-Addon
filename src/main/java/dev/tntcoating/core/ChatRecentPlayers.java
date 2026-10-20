package dev.tntcoating.core;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;

/** Recent interactions, rather than an alphabetical copy of the server's tab list. */
public final class ChatRecentPlayers {
    private final LinkedHashMap<String, String> players = new LinkedHashMap<>();
    public void touch(String player) {
        if (!ChatMessages.validName(player)) return;
        String key = player.toLowerCase(Locale.ROOT);
        players.remove(key);
        players.put(key, player);
        if (players.size() > 100) players.remove(players.keySet().iterator().next());
    }
    public List<String> names() { return List.copyOf(players.values()).reversed(); }
    public void clear() { players.clear(); }
}
