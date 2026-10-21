package dev.tntcoating.core;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.function.Predicate;
import java.util.regex.Pattern;

/** The clan roster: usernames typed in or imported from CSV, kept without duplicates and grouped for the Clan tab. */
public final class ChatClan {
    /** Valid usernames in input order, first spelling kept, and how many non-empty entries were not usernames. */
    public record Import(List<String> names, int invalid) {}
    public record Groups(List<String> friends, List<String> online, List<String> offline) {
        public boolean isEmpty() { return friends.isEmpty() && online.isEmpty() && offline.isEmpty(); }
    }

    private static final Pattern SEPARATORS = Pattern.compile("[\\s,;]+");
    private static final Pattern LINES = Pattern.compile("\\r\\n|\\r|\\n");
    private static final Set<String> HEADERS = Set.of("name", "names", "username", "usernames", "user", "users", "ign", "igns",
        "player", "players", "playername", "member", "members", "membername", "mcname", "minecraftname", "minecraftusername", "nick", "nickname");
    private static final List<String> HEADER_WORDS = List.of("name", "user", "ign", "player", "member");

    private ChatClan() {}

    /** Names typed by hand, separated by commas, semicolons or spaces. */
    public static Import typed(String text) {
        List<String> cells = new ArrayList<>();
        for (String cell : SEPARATORS.split(text.strip())) cells.add(cell);
        return collect(cells);
    }

    /**
     * A CSV or spreadsheet export, separated by commas, semicolons or tabs. A header row naming a username column
     * (Name, Username, IGN, Player, Member...) selects that column; otherwise a single line is a list of names and
     * several lines take the first column, so extra columns such as rank or join date are ignored.
     */
    public static Import csv(String text) {
        String clean = text.startsWith("﻿") ? text.substring(1) : text;
        List<String> lines = new ArrayList<>();
        for (String line : LINES.split(clean)) if (!line.isBlank()) lines.add(line);
        if (lines.isEmpty()) return new Import(List.of(), 0);
        char delimiter = delimiter(lines.getFirst());
        List<List<String>> rows = new ArrayList<>();
        for (String line : lines) rows.add(cells(line, delimiter));
        int column = -1, start = 0;
        List<String> first = rows.getFirst();
        for (int i = 0; i < first.size(); i++) if (header(first.get(i))) { column = i; start = 1; break; }
        if (column < 0 && rows.size() > 1) column = 0;
        List<String> cells = new ArrayList<>();
        for (List<String> row : rows.subList(start, rows.size())) {
            if (column < 0) cells.addAll(row);
            else if (column < row.size()) cells.add(row.get(column));
        }
        return collect(cells);
    }

    /** File contents as text; usernames are ASCII, so only the encodings spreadsheets save in matter. */
    public static String decode(byte[] bytes) {
        if (bytes.length >= 2 && (bytes[0] == (byte) 0xff && bytes[1] == (byte) 0xfe || bytes[0] == (byte) 0xfe && bytes[1] == (byte) 0xff))
            return new String(bytes, StandardCharsets.UTF_16);
        return new String(bytes, StandardCharsets.UTF_8);
    }

    /** Valid, distinct members in saved order; hand-edited entries that are not usernames are skipped. */
    public static List<String> members(List<String> saved) { return collect(saved).names(); }

    public static boolean contains(List<String> saved, String player) {
        return player != null && saved.stream().anyMatch(name -> name.strip().equalsIgnoreCase(player));
    }

    /** Lower-case keys for fast lookups while colouring chat and the tab list. */
    public static Set<String> keys(List<String> saved) {
        return Set.copyOf(members(saved).stream().map(name -> name.toLowerCase(Locale.ROOT)).toList());
    }

    /** Appends names not already saved, ignoring case. */
    public static List<String> add(List<String> saved, List<String> names) {
        List<String> result = new ArrayList<>(saved);
        for (String name : names) if (ChatMessages.validName(name) && !contains(result, name)) result.add(name);
        return List.copyOf(result);
    }

    public static List<String> remove(List<String> saved, String player) {
        return List.copyOf(saved.stream().filter(name -> !name.strip().equalsIgnoreCase(player)).toList());
    }

    /** Friends first (online ones at the top), then other online members, then everyone else; names A-Z within each. */
    public static Groups groups(List<String> members, Predicate<String> friend, Predicate<String> online) {
        List<String> friends = new ArrayList<>(), present = new ArrayList<>(), offline = new ArrayList<>();
        for (String name : members) (friend.test(name) ? friends : online.test(name) ? present : offline).add(name);
        friends.sort(Comparator.comparing((String name) -> !online.test(name)).thenComparing(String.CASE_INSENSITIVE_ORDER));
        present.sort(String.CASE_INSENSITIVE_ORDER);
        offline.sort(String.CASE_INSENSITIVE_ORDER);
        return new Groups(List.copyOf(friends), List.copyOf(present), List.copyOf(offline));
    }

    /** One line for the notice after adding or importing. */
    public static String summary(int added, int existing, int invalid) {
        List<String> parts = new ArrayList<>();
        if (added > 0) parts.add("Added " + count(added, "member"));
        if (existing > 0) parts.add(existing + " already in the clan");
        if (invalid > 0) parts.add("skipped " + count(invalid, "invalid name"));
        if (parts.isEmpty()) return "No usernames found.";
        String text = String.join(", ", parts) + ".";
        return Character.toUpperCase(text.charAt(0)) + text.substring(1);
    }

    private static String count(int n, String singular) { return n + " " + singular + (n == 1 ? "" : "s"); }

    private static Import collect(List<String> cells) {
        var names = new LinkedHashMap<String, String>();
        int invalid = 0;
        for (String raw : cells) {
            String cell = raw.strip();
            if (cell.isEmpty()) continue;
            if (ChatMessages.validName(cell)) names.putIfAbsent(cell.toLowerCase(Locale.ROOT), cell);
            else invalid++;
        }
        return new Import(List.copyOf(names.values()), invalid);
    }

    private static boolean header(String cell) {
        String word = cell.toLowerCase(Locale.ROOT).replaceAll("[^a-z]", "");
        if (HEADERS.contains(word)) return true;
        // "Minecraft Username" is a header; "Nameless" is a player.
        return !ChatMessages.validName(cell.strip()) && HEADER_WORDS.stream().anyMatch(word::contains);
    }

    private static char delimiter(String line) {
        char best = ',';
        long most = line.chars().filter(c -> c == ',').count();
        for (char candidate : new char[] {';', '\t'}) {
            long n = line.chars().filter(c -> c == candidate).count();
            if (n > most) { best = candidate; most = n; }
        }
        return best;
    }

    /** One CSV line: quoted cells may contain the delimiter, and a doubled quote is a literal quote. */
    private static List<String> cells(String line, char delimiter) {
        List<String> result = new ArrayList<>();
        StringBuilder cell = new StringBuilder();
        boolean quoted = false;
        for (int i = 0; i < line.length(); i++) {
            char c = line.charAt(i);
            if (quoted) {
                if (c != '"') cell.append(c);
                else if (i + 1 < line.length() && line.charAt(i + 1) == '"') { cell.append('"'); i++; }
                else quoted = false;
            } else if (c == '"') quoted = true;
            else if (c == delimiter) { result.add(cell.toString()); cell.setLength(0); }
            else cell.append(c);
        }
        result.add(cell.toString());
        return result;
    }
}
