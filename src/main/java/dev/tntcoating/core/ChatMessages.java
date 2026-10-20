package dev.tntcoating.core;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

/** Session-local DM history. Only anchored private-message formats are accepted, never public chat bodies. */
public final class ChatMessages {
    private static final String NAME = "([A-Za-z0-9_]{1,16})";
    private static final String PREFIX = "^(?:\\[[^]\\r\\n]{1,40}]\\s*)*";
    private static final List<String> INCOMING = List.of(
        PREFIX + "from\\s+" + NAME + "\\s*[:»›]\\s*(.*)$",
        PREFIX + NAME + "\\s+whispers(?:\\s+to\\s+you)?\\s*[:»›]\\s*(.*)$",
        "^\\[" + NAME + "\\s*(?:->|→|»)\\s*(?:me|you)]\\s*(.*)$");
    private static final List<String> OUTGOING = List.of(
        PREFIX + "to\\s+" + NAME + "\\s*[:»›]\\s*(.*)$",
        PREFIX + "you\\s+whisper\\s+to\\s+" + NAME + "\\s*[:»›]\\s*(.*)$",
        "^\\[(?:me|you)\\s*(?:->|→|»)\\s*" + NAME + "]\\s*(.*)$");
    public static final int MAX_CONVERSATIONS = 100;
    public static final int MAX_MESSAGES = 250;
    public record Message(String player, String text, boolean incoming, long time) {}
    public record Conversation(String player, List<Message> messages, int unread) {}
    private static final class History {
        final String player;
        final List<Message> messages = new ArrayList<>();
        int unread;
        Conversation snapshot;
        History(String player) { this.player = player; }
    }
    private final Map<String, History> histories = new LinkedHashMap<>();
    private List<Pattern> incoming = compile(INCOMING);
    private List<Pattern> outgoing = compile(OUTGOING);

    public void patterns(List<String> extraIncoming, List<String> extraOutgoing) {
        incoming = patternsWith(INCOMING, extraIncoming);
        outgoing = patternsWith(OUTGOING, extraOutgoing);
    }

    private static List<Pattern> patternsWith(List<String> defaults, List<String> extras) {
        List<String> all = new ArrayList<>(defaults);
        all.addAll(extras);
        return compile(all);
    }

    private static List<Pattern> compile(List<String> expressions) {
        List<Pattern> result = new ArrayList<>();
        for (String expression : expressions) {
            try {
                Pattern pattern = Pattern.compile(expression, Pattern.CASE_INSENSITIVE);
                if (pattern.matcher("").groupCount() >= 2) result.add(pattern);
            } catch (PatternSyntaxException ignored) { /* An invalid custom entry cannot break receiving chat. */ }
        }
        return result;
    }

    public Message parse(String raw, String self, long time) {
        String text = ChatSender.plain(raw).strip();
        Message message = parse(text, incoming, true, self, time);
        return message != null ? message : parse(text, outgoing, false, self, time);
    }

    private static Message parse(String text, List<Pattern> patterns, boolean incoming, String self, long time) {
        for (Pattern pattern : patterns) {
            var match = pattern.matcher(text);
            if (!match.matches()) continue;
            String player = match.group(1);
            String body = match.group(2);
            if (validName(player) && body != null && !player.equalsIgnoreCase(self)) {
                return new Message(player, body, incoming, time);
            }
        }
        return null;
    }

    public static boolean validName(String player) {
        return player != null && player.matches("[A-Za-z0-9_]{1,16}");
    }

    public void add(Message message, boolean read) {
        String key = key(message.player());
        History history = histories.remove(key);
        if (history == null) history = new History(message.player());
        history.messages.add(message);
        history.snapshot = null;
        if (history.messages.size() > MAX_MESSAGES) history.messages.removeFirst();
        if (read) history.unread = 0;
        else if (message.incoming()) history.unread++;
        histories.put(key, history);
        if (histories.size() > MAX_CONVERSATIONS) histories.remove(histories.keySet().iterator().next());
    }

    public Conversation conversation(String player) {
        History history = histories.get(key(player));
        if (history == null) return new Conversation(player, List.of(), 0);
        if (history.snapshot == null) history.snapshot = new Conversation(history.player, List.copyOf(history.messages), history.unread);
        return history.snapshot;
    }

    public List<String> recent() { return new ArrayList<>(histories.values().stream().map(h -> h.player).toList().reversed()); }
    public int unread() { return histories.values().stream().mapToInt(h -> h.unread).sum(); }
    public int unread(String player) { History h = histories.get(key(player)); return h == null ? 0 : h.unread; }
    public void read(String player) {
        History h = histories.get(key(player));
        if (h != null && h.unread > 0) { h.unread = 0; h.snapshot = null; }
    }
    public void clear() { histories.clear(); }
    private static String key(String name) { return name.toLowerCase(Locale.ROOT); }
}
