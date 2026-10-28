package dev.tntcoating.core;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Private messages wait their turn instead of being refused by a server's whisper delay, such as 6b6t's "Please wait 2
 * seconds before sending another whisper!". A refused whisper goes back to the front and is sent again once the server's
 * wait is over, and the wait it reported spaces out later whispers so they are not refused at all.
 */
public final class ChatWhisperQueue {
    public record Whisper(String player, String text, String command) {}
    /** A whisper still waiting, with how long until it is due to send. */
    public record Queued(long id, Whisper whisper, long eta) {}
    private record Entry(long id, Whisper whisper, int retries) {}
    public static final int MAX_QUEUED = 20;
    private static final Pattern REFUSAL = Pattern.compile(
        "^please\\s+wait\\s+(\\d{1,3})\\s+seconds?\\s+before\\s+sending\\s+another\\s+(?:whisper|private\\s+message)\\b.*$", Pattern.CASE_INSENSITIVE);
    private static final Set<String> VERBS = Set.of("msg", "w", "whisper", "tell", "r", "reply");
    private static final Set<String> REPLIES = Set.of("r", "reply");
    private static final Pattern WHITESPACE = Pattern.compile("\\s+");
    /** Added to the server's delay, which it measures on arrival, so network jitter cannot make a whisper early. */
    private static final long MARGIN = 250;
    /** A refusal arriving later than this cannot belong to the last whisper sent. */
    private static final long REFUSAL_WINDOW = 5_000;
    private static final long MAX_DELAY = 60_000;
    private static final int MAX_RETRIES = 3;
    private final ArrayDeque<Entry> queued = new ArrayDeque<>();
    private Entry inFlight;
    private long sentAt, nextAt, delay, minimum, nextId;

    /** The configured spacing between whispers; a longer delay learned from the server still wins. */
    public void minimum(long millis) { minimum = Math.max(0, millis); }

    /** Whether a command sends a private message: the usual verbs, or the verb of the configured message command. */
    public static boolean isWhisper(String command, String template) {
        String verb = verb(command);
        return !verb.isEmpty() && (VERBS.contains(verb) || verb.equals(verb(template)));
    }

    /** A complete whisper command; null when it is not one or has no message yet, so the server can explain its usage. */
    public static Whisper parse(String command, String template) {
        if (!isWhisper(command, template)) return null;
        boolean reply = REPLIES.contains(verb(command));
        String[] parts = WHITESPACE.split(withoutSlash(command).strip(), reply ? 2 : 3);
        if (parts.length < (reply ? 2 : 3) || parts[parts.length - 1].isBlank()) return null;
        String player = reply || !ChatMessages.validName(parts[1]) ? null : parts[1];
        return new Whisper(player, parts[parts.length - 1], command);
    }

    public static boolean isRefusal(String line) { return REFUSAL.matcher(ChatSender.plain(line).strip()).matches(); }

    public boolean add(Whisper whisper) {
        if (queued.size() >= MAX_QUEUED) return false;
        queued.addLast(new Entry(++nextId, whisper, 0));
        return true;
    }

    /** The whisper to send now, if one is waiting and the server's delay is over; it then counts as sent. */
    public Whisper poll(long now) {
        if (queued.isEmpty() || now < nextAt) return null;
        Entry next = queued.removeFirst();
        inFlight = next;
        sentAt = now;
        nextAt = Math.max(nextAt, now + spacing());
        return next.whisper();
    }

    /** A whisper command reached the server. One typed elsewhere also restarts the delay and owns the next refusal. */
    public void sent(String command, long now) {
        if (inFlight != null && now - sentAt < REFUSAL_WINDOW
            && withoutSlash(inFlight.whisper().command()).strip().equals(withoutSlash(command).strip())) return;
        inFlight = null;
        sentAt = now;
        nextAt = Math.max(nextAt, now + spacing());
    }

    /**
     * Reads a server line. A whisper-delay refusal teaches the delay and puts the whisper just sent back at the front;
     * true means that whisper will be sent again, so the refusal need not be shown.
     */
    public boolean refused(String line, long now) {
        var match = REFUSAL.matcher(ChatSender.plain(line).strip());
        if (!match.matches()) return false;
        long wait = Math.min(MAX_DELAY, Long.parseLong(match.group(1)) * 1_000);
        delay = Math.max(delay, wait);
        nextAt = Math.max(nextAt, now + wait + MARGIN);
        Entry refused = inFlight;
        inFlight = null;
        if (refused == null || now - sentAt > REFUSAL_WINDOW || refused.retries() >= MAX_RETRIES) return false;
        queued.addFirst(new Entry(refused.id(), refused.whisper(), refused.retries() + 1));
        return true;
    }

    /** The server echoed a whisper to this player, so the one in flight arrived. */
    public void delivered(String player) {
        if (inFlight != null && (inFlight.whisper().player() == null || player.equalsIgnoreCase(inFlight.whisper().player()))) inFlight = null;
    }

    /** Whispers to this player that are waiting or not yet echoed, oldest first. */
    public List<Whisper> pending(String player, long now) {
        List<Whisper> result = new ArrayList<>();
        if (inFlight != null && now - sentAt <= REFUSAL_WINDOW && player.equalsIgnoreCase(inFlight.whisper().player())) result.add(inFlight.whisper());
        for (Entry entry : queued) if (player.equalsIgnoreCase(entry.whisper().player())) result.add(entry.whisper());
        return result;
    }

    /** Waiting whispers, oldest first, each with the time left until it sends. */
    public List<Queued> entries(long now) {
        List<Queued> result = new ArrayList<>(queued.size());
        long eta = Math.max(0, nextAt - now), step = spacing();
        for (Entry entry : queued) {
            result.add(new Queued(entry.id(), entry.whisper(), eta));
            eta += step;
        }
        return result;
    }

    public boolean cancel(long id) { return queued.removeIf(entry -> entry.id() == id); }

    /** How long the next waiting whisper still has to wait, or 0 when none is waiting. */
    public long waiting(long now) { return queued.isEmpty() ? 0 : Math.max(0, nextAt - now); }

    public void clear() {
        queued.clear();
        inFlight = null;
        sentAt = nextAt = delay = 0;
    }

    private long spacing() { return Math.max(minimum, delay == 0 ? 0 : delay + MARGIN); }

    private static String verb(String command) {
        String text = withoutSlash(command).strip();
        int space = text.indexOf(' ');
        String verb = space < 0 ? text : text.substring(0, space);
        return verb.substring(verb.lastIndexOf(':') + 1).toLowerCase(Locale.ROOT);
    }

    private static String withoutSlash(String command) { return command.startsWith("/") ? command.substring(1) : command; }
}
