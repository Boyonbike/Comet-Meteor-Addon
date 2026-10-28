package dev.tntcoating.core;

import java.util.List;

/** Game-thread ownership of one asynchronous read. Cancelled or stale reads cannot publish results. */
public final class DiscordIgnSyncState {
    public record Settings(String token, String guildId, String channelId,
                           boolean clan, boolean whitelist) {
        @Override public String toString() { return "Discord IGN settings (token redacted)"; }
    }

    public record Ticket(long revision, Settings settings, List<String> clan, List<String> whitelist) {
        public Ticket {
            clan = List.copyOf(clan);
            whitelist = List.copyOf(whitelist);
        }
    }

    private long revision;
    private Ticket current;

    public boolean busy() { return current != null; }
    public boolean isCurrent(Ticket ticket) { return current == ticket; }

    public Ticket begin(Settings settings, List<String> clan, List<String> whitelist) {
        if (busy()) throw new IllegalStateException("A Discord read is already running");
        current = new Ticket(++revision, settings, clan, whitelist);
        return current;
    }

    public void cancel() { current = null; }

    /** Release only this read; a stale callback must never release its replacement. */
    public boolean complete(Ticket ticket, Settings settings, List<String> clan, List<String> whitelist) {
        if (current != ticket) return false;
        current = null;
        return ticket.settings().equals(settings) && ticket.clan().equals(clan) && ticket.whitelist().equals(whitelist);
    }
}
