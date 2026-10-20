package dev.tntcoating.core;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;

/** Case-insensitive prefix matches, preserving contact order and merging duplicate usernames. */
public final class ChatPlayerSearch {
    private ChatPlayerSearch() {}

    @SafeVarargs
    public static List<String> matching(String query, List<String>... sources) {
        String prefix = query.strip().toLowerCase(Locale.ROOT);
        var matches = new LinkedHashMap<String, String>();
        for (var source : sources) for (String name : source) {
            if (!ChatMessages.validName(name)) continue;
            String key = name.toLowerCase(Locale.ROOT);
            if (key.startsWith(prefix)) matches.putIfAbsent(key, name);
        }
        return List.copyOf(matches.values());
    }
}
