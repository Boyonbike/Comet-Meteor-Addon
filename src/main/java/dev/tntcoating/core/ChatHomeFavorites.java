package dev.tntcoating.core;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;

/** Home favourites are isolated by server/account and survive reconnects through Meteor settings. */
public final class ChatHomeFavorites {
    private ChatHomeFavorites() {}
    private static String prefix(String server) {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(server.getBytes(StandardCharsets.UTF_8)) + "|";
    }
    public static List<String> names(List<String> entries, String server) {
        String prefix = prefix(server);
        return ChatHomeNames.merge(entries.stream().filter(e -> e.startsWith(prefix)).map(e -> e.substring(prefix.length())).toList());
    }
    public static List<String> toggle(List<String> entries, String server, String home) {
        String valid = ChatHomeNames.valid(home);
        if (valid == null) return List.copyOf(entries);
        String prefix = prefix(server);
        boolean exists = names(entries, server).stream().anyMatch(name -> name.equalsIgnoreCase(valid));
        List<String> result = new ArrayList<>(entries);
        result.removeIf(e -> e.startsWith(prefix) && e.substring(prefix.length()).equalsIgnoreCase(valid));
        if (!exists) result.add(prefix + valid);
        return List.copyOf(result);
    }
    public static List<String> edit(List<String> entries, String server, ChatHomeEdit edit) {
        if (edit.oldName() == null) return List.copyOf(entries);
        String prefix = prefix(server);
        List<String> result = new ArrayList<>();
        for (String entry : entries) {
            if (!entry.startsWith(prefix) || !entry.substring(prefix.length()).equalsIgnoreCase(edit.oldName())) result.add(entry);
            else if (edit.newName() != null) result.add(prefix + edit.newName());
        }
        return List.copyOf(result);
    }
}
