package dev.tntcoating.core;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

/**
 * Public chat waits its turn instead of being refused by a server's chat cooldown, so typing never has to pause. Messages
 * leave one cooldown apart; a refused message goes back to the front and the wait the server reported spaces out the rest.
 */
public final class ChatPublicQueue {
    /** A message still waiting, with how long until it is due to send. */
    public record Queued(long id, String text, long eta) {}
    private record Entry(long id, String text, int retries) {}
    public static final int MAX_QUEUED = 20;
    private static final Pattern REFUSAL = Pattern.compile(
        "^please\\s+wait\\s+(\\d{1,3})\\s+seconds?\\s+before\\s+(?:sending\\s+another\\s+(?:chat\\s+)?message|chatting(?:\\s+again)?|talking(?:\\s+again)?)\\b.*$",
        Pattern.CASE_INSENSITIVE);
    /** Added to the server's delay, which it measures on arrival, so network jitter cannot make a message early. */
    private static final long MARGIN = 250;
    /** A refusal arriving later than this cannot belong to the last message sent. */
    private static final long REFUSAL_WINDOW = 5_000;
    private static final long MAX_DELAY = 60_000;
    private static final int MAX_RETRIES = 3;
    private final ArrayDeque<Entry> queued = new ArrayDeque<>();
    private Entry inFlight;
    private long sentAt, nextAt, learned, minimum, nextId;

    /** The configured cooldown between messages; a longer wait learned from the server still wins. */
    public void minimum(long millis) { minimum = Math.max(0, millis); }

    public static boolean isRefusal(String line) { return REFUSAL.matcher(ChatSender.plain(line).strip()).matches(); }

    public boolean add(String text) {
        if (text == null || text.isBlank() || queued.size() >= MAX_QUEUED) return false;
        queued.addLast(new Entry(++nextId, text, 0));
        return true;
    }

    /** The message to send now, if one is waiting and the cooldown is over; it then counts as sent. */
    public String poll(long now) {
        if (queued.isEmpty() || now < nextAt) return null;
        Entry next = queued.removeFirst();
        inFlight = next;
        sentAt = now;
        nextAt = Math.max(nextAt, now + spacing());
        return next.text();
    }

    /** A chat message reached the server. One typed in normal chat also restarts the cooldown and owns the next refusal. */
    public void sent(String text, long now) {
        if (inFlight != null && now - sentAt < REFUSAL_WINDOW && inFlight.text().strip().equals(text.strip())) return;
        inFlight = null;
        sentAt = now;
        nextAt = Math.max(nextAt, now + spacing());
    }

    /** Reads a server line; true means the refused message will be sent again, so the refusal need not be shown. */
    public boolean refused(String line, long now) {
        var match = REFUSAL.matcher(ChatSender.plain(line).strip());
        if (!match.matches()) return false;
        long wait = Math.min(MAX_DELAY, Long.parseLong(match.group(1)) * 1_000);
        learned = Math.max(learned, wait);
        nextAt = Math.max(nextAt, now + wait + MARGIN);
        Entry refused = inFlight;
        inFlight = null;
        if (refused == null || now - sentAt > REFUSAL_WINDOW || refused.retries() >= MAX_RETRIES) return false;
        queued.addFirst(new Entry(refused.id(), refused.text(), refused.retries() + 1));
        return true;
    }

    /** Waiting messages, oldest first, each with the time left until it sends. */
    public List<Queued> entries(long now) {
        List<Queued> result = new ArrayList<>(queued.size());
        long eta = Math.max(0, nextAt - now), step = spacing();
        for (Entry entry : queued) {
            result.add(new Queued(entry.id(), entry.text(), eta));
            eta += step;
        }
        return result;
    }

    public boolean cancel(long id) { return queued.removeIf(entry -> entry.id() == id); }

    /** How long the next waiting message still has to wait, or 0 when none is waiting. */
    public long waiting(long now) { return queued.isEmpty() ? 0 : Math.max(0, nextAt - now); }

    public void clear() {
        queued.clear();
        inFlight = null;
        sentAt = nextAt = learned = 0;
    }

    private long spacing() { return Math.max(minimum, learned == 0 ? 0 : learned + MARGIN); }
}
