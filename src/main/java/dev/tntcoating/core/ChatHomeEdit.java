package dev.tntcoating.core;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

/** Updates locally discovered homes only after a successful server reply. */
public record ChatHomeEdit(String oldName, String newName) {
    private static final Pattern WHITESPACE = Pattern.compile("\\s+");
    private static final Pattern FAILURE = Pattern.compile("\\b(?:not|no|cannot|failed|error|already|unable)\\b");
    private static final Pattern CREATED = Pattern.compile(
        "\\bhome(?:\\s+['\"]?[\\p{L}\\p{N}_+.-]{1,64}['\"]?)?\\s+"
            + "(?:(?:has (?:successfully )?been|was|is|successfully)\\s+)?(?:set|created|saved)\\b"
            + "|\\b(?:you\\s+(?:have\\s+)?|successfully\\s+)(?:set|created|saved)\\s+(?:(?:your|a|the|new)\\s+)?home\\b");

    public static ChatHomeEdit parse(String command) {
        String stripped = command.strip();
        String[] tokens = WHITESPACE.split(stripped.startsWith("/") ? stripped.substring(1) : stripped);
        String lower = tokens[0].toLowerCase(Locale.ROOT);
        String verb = lower.substring(lower.lastIndexOf(':') + 1);
        if (verb.equals("sethome") && tokens.length == 2) {
            String name = ChatHomeNames.valid(tokens[1]);
            return name == null ? null : new ChatHomeEdit(null, name);
        }
        boolean delete = verb.equals("delhome") && tokens.length == 2;
        boolean rename = verb.equals("homerename") && tokens.length == 3;
        if (!delete && !rename) return null;
        String old = ChatHomeNames.valid(tokens[1]);
        String replacement = rename ? ChatHomeNames.valid(tokens[2]) : null;
        return old == null || rename && replacement == null ? null : new ChatHomeEdit(old, replacement);
    }

    /** Whether the line confirms a sethome command, whatever the home is called. */
    public static boolean setSuccess(String message) {
        String text = ChatSender.plain(message).toLowerCase(Locale.ROOT);
        return text.contains("home") && !FAILURE.matcher(text).find()
            && !text.contains("can't") && !text.contains("couldn't")
            && CREATED.matcher(text).find();
    }

    public boolean confirmedBy(String message) {
        if (oldName == null) return setSuccess(message);
        String text = ChatSender.plain(message).toLowerCase(Locale.ROOT);
        if (!text.contains("home") || FAILURE.matcher(text).find()
            || text.contains("can't") || text.contains("couldn't")) return false;
        return newName == null ? text.contains("deleted") || text.contains("removed") : text.contains("renamed");
    }

    public List<String> apply(List<String> homes) {
        List<String> result = new ArrayList<>();
        for (String name : homes) {
            if (!name.equalsIgnoreCase(oldName)) result.add(name);
            else if (newName != null) result.add(newName);
        }
        if (oldName == null && newName != null) result.add(newName);
        return ChatHomeNames.merge(result);
    }
}
