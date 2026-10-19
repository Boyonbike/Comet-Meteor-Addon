package dev.tntcoating.core;

import java.util.List;
import java.util.function.Predicate;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Recognises chat written by a player, so their text can never imitate server replies. A line counts as player chat
 * only when it starts like "&lt;Name&gt;", "Name:" or a private message ("From Name:", "Name whispers to you:",
 * "[Name -&gt; me]"), after optional [Rank] prefixes, and that name is an online player. Server lines such as
 * "Homes: base" or "Error: no home" therefore still reach the parsers.
 */
public final class ChatSender {
    private static final Pattern FORMATTING = Pattern.compile("§.");
    private static final Pattern LEADING_SYMBOLS = Pattern.compile("^[»›•▶→\\s]+");
    private static final String RANKS = "^(?:\\[[^]\\r\\n]{1,40}]\\s*)*";
    private static final String NAME = "([A-Za-z0-9_]{1,16})";
    private static final List<Pattern> SENDERS = List.of(
        Pattern.compile(RANKS + "<" + NAME + ">(?:\\s|$)"),
        Pattern.compile(RANKS + NAME + "\\s*[:»›](?:\\s|$)"),
        Pattern.compile(RANKS + "from\\s+" + NAME + "\\s*[:»›]", Pattern.CASE_INSENSITIVE),
        Pattern.compile(RANKS + NAME + "\\s+whispers(?:\\s+to\\s+you)?\\s*[:»›]", Pattern.CASE_INSENSITIVE),
        Pattern.compile("^\\[" + NAME + "\\s*(?:->|→|»)\\s*[^]\\r\\n]{1,20}]", Pattern.CASE_INSENSITIVE));

    private ChatSender() {}

    /** Text without legacy formatting codes. */
    public static String plain(String raw) {
        return FORMATTING.matcher(raw).replaceAll("");
    }

    /** The username a line appears to come from, or null when it does not start like player chat. */
    public static String apparentSender(String raw) {
        String text = LEADING_SYMBOLS.matcher(plain(raw).strip()).replaceFirst("");
        for (Pattern sender : SENDERS) {
            Matcher match = sender.matcher(text);
            if (match.find()) return match.group(1);
        }
        return null;
    }

    /** True when the line starts with an online player's name as its sender. */
    public static boolean fromPlayer(String raw, Predicate<String> online) {
        String sender = apparentSender(raw);
        return sender != null && online.test(sender);
    }
}
