/* SPDX-License-Identifier: GPL-3.0-only */
package dev.tntcoating.core;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.function.LongSupplier;
import java.util.regex.Pattern;

/** Durable wall-clock scheduling; pending interactions are deliberately never restored from disk. */
public final class SetHomeScheduler {
    private static final long MAX_COOLDOWN = 86_400_000;
    private static final Pattern TELEPORT_REPLY = Pattern.compile(
        "^you have to wait (\\d+)m (\\d{1,2})s to teleport again\\.$", Pattern.CASE_INSENSITIVE);
    private static final Pattern WAIT = Pattern.compile("\\bwait\\b");
    private static final Pattern COOLDOWN_CUE = Pattern.compile("\\bcooldown\\b");
    private static final Pattern SUCCESS = Pattern.compile("^successfully moved home:\\s*(.+?)\\s*$", Pattern.CASE_INSENSITIVE);
    public enum Reply { IGNORED, COOLDOWN, REFUSED, SUCCESS }
    public record Location(double x, double y, double z, String dimension) {
        public Location {
            if (!Double.isFinite(x) || !Double.isFinite(y) || !Double.isFinite(z)
                || dimension == null || dimension.isBlank() || dimension.length() > 256)
                throw new IllegalArgumentException("Invalid home location");
        }
    }
    public record Entry(String home, long sentAt, long confirmedAt, Location location) {
        public Entry {
            if (ChatHomeNames.valid(home) == null || sentAt <= 0 || confirmedAt < sentAt || location == null)
                throw new IllegalArgumentException("Invalid home history");
        }
    }
    public record State(long lastSentAt, long lastSuccessAt, long successActionAt, long learnedMillis,
                        long cooldownEnd, long retryAt, List<Entry> history) {
        public State {
            if (lastSentAt < 0 || lastSuccessAt < 0 || successActionAt < 0 || learnedMillis < 0
                || learnedMillis > MAX_COOLDOWN || cooldownEnd < 0 || retryAt < 0 || history == null
                || history.size() > 100 || history.stream().anyMatch(Objects::isNull))
                throw new IllegalArgumentException("Invalid saved home state");
            history = List.copyOf(history);
        }
        public static State empty() { return new State(0, 0, 0, 0, 0, 0, List.of()); }
    }
    public record Status(String phase, long waitMillis, long cooldownMillis, long bufferMillis,
                         long intervalMillis, long retryMillis, long nextAttemptAt, long learnedMillis,
                         boolean waiting, long responseMillis, boolean estimatedCooldown) {}
    private record Attempt(String home, long sentAt, long actionAt, Location location, boolean dialog, long deadline) {}

    private final LongSupplier clock;
    private State state;
    private Attempt pending;
    // Only an uninterrupted, observed success can anchor the next cooldown sample.
    // Restored timestamps and missed replies must never turn offline time into a cooldown.
    private long learningActionAt;
    private long learningUntil;
    private long discoveryIntervalMillis = 60_000;
    public SetHomeScheduler(LongSupplier clock, State restored) {
        this.clock = Objects.requireNonNull(clock);
        state = Objects.requireNonNull(restored);
    }
    public State state() { return state; }
    public boolean waiting() { return pending != null; }
    public boolean waitingForDialog() { return pending != null && pending.dialog(); }
    public String requestedHome() { return pending == null ? null : pending.home(); }
    public void cancel() { pending = null; learningActionAt = 0; }
    public void expire() { if (pending != null && now() >= pending.deadline()) cancel(); }

    public void sent(String home, Location location, boolean override, long dialogTimeoutMillis) {
        sent(home, location, override, dialogTimeoutMillis, 60_000);
    }
    public void sent(String home, Location location, boolean override, long dialogTimeoutMillis, long intervalMillis) {
        String valid = ChatHomeNames.valid(home);
        if (valid == null) throw new IllegalArgumentException("Invalid home name");
        long now = now();
        if (now > learningUntil) learningActionAt = 0;
        discoveryIntervalMillis = Math.clamp(intervalMillis, 1_000, 3_600_000);
        pending = new Attempt(valid, now, now, location, override,
            now + (override ? Math.max(1_000, dialogTimeoutMillis) : 15_000));
        state = new State(now, state.lastSuccessAt(), state.successActionAt(), state.learnedMillis(),
            state.cooldownEnd(), state.retryAt(), state.history());
    }
    /** Called after the existing dialog button action, without assuming it succeeded. */
    public void clicked(Location location) {
        if (pending == null || !pending.dialog()) return;
        long now = now();
        pending = new Attempt(pending.home(), pending.sentAt(), now, location, false, now + 15_000);
    }
    public Reply message(String raw, long retryMillis) {
        expire();
        if (pending == null) return Reply.IGNORED;
        String text = ChatSender.plain(raw).strip();
        var success = SUCCESS.matcher(text);
        long now = now();
        if (success.matches()) {
            if (!success.group(1).equalsIgnoreCase(pending.home())) return Reply.IGNORED;
            var entries = new ArrayList<Entry>();
            entries.add(new Entry(pending.home(), pending.sentAt(), now, pending.location()));
            entries.addAll(state.history().subList(0, Math.min(99, state.history().size())));
            long actionAt = pending.actionAt();
            learningActionAt = actionAt;
            learningUntil = actionAt + Math.max(state.learnedMillis(), discoveryIntervalMillis) + 15_000;
            state = new State(state.lastSentAt(), now, actionAt, state.learnedMillis(),
                state.learnedMillis() > 0 ? actionAt + state.learnedMillis() : 0, 0, entries);
            pending = null;
            return Reply.SUCCESS;
        }
        String lower = text.toLowerCase(java.util.Locale.ROOT);
        var teleport = TELEPORT_REPLY.matcher(text);
        boolean exactTeleport = teleport.matches();
        // This exception is scoped to an outstanding sethome. Other teleport cooldown formats stay excluded.
        if (lower.contains("teleport") && !exactTeleport) return Reply.IGNORED;
        boolean homeCooldown = lower.matches(".*\\bsethome\\b.*")
            || lower.contains("home") && lower.matches(".*\\b(?:set|setting|create|save|move)\\b.*");
        var durations = ChatTeleportState.durations(lower);
        if ((exactTeleport || homeCooldown && (lower.contains("wait") || lower.contains("cooldown")
            || lower.contains("again"))) && !durations.isEmpty()) {
            var cue = WAIT.matcher(lower);
            if (!cue.find()) {
                cue = COOLDOWN_CUE.matcher(lower);
                if (!cue.find()) cue = null;
            }
            int afterCue = cue == null ? 0 : cue.end();
            long remaining = Math.min(MAX_COOLDOWN, durations.stream()
                .filter(duration -> duration.start() >= afterCue).findFirst()
                .orElse(durations.getFirst()).millis());
            if (remaining == 0) {
                learningActionAt = 0;
                state = new State(state.lastSentAt(), state.lastSuccessAt(), state.successActionAt(),
                    state.learnedMillis(), now, now + Math.max(1_000, retryMillis), state.history());
                pending = null;
                return Reply.REFUSED;
            }
            long learned = remaining;
            if (learningActionAt > 0 && now >= learningActionAt)
                learned = Math.min(MAX_COOLDOWN, now - learningActionAt + remaining);
            learningActionAt = 0;
            state = new State(state.lastSentAt(), state.lastSuccessAt(), state.successActionAt(),
                learned, now + remaining, 0, state.history());
            pending = null;
            return Reply.COOLDOWN;
        }
        if (homeCooldown && lower.matches(".*\\b(?:cannot|unable|denied|failed|limit|maximum|already)\\b.*")) {
            learningActionAt = 0;
            state = new State(state.lastSentAt(), state.lastSuccessAt(), state.successActionAt(),
                state.learnedMillis(), state.cooldownEnd(), now + Math.max(1_000, retryMillis), state.history());
            pending = null;
            return Reply.REFUSED;
        }
        return Reply.IGNORED;
    }
    public boolean ready(boolean fixed, long intervalMillis, long bufferMillis) {
        return pending == null && snapshot(fixed, intervalMillis, bufferMillis).waitMillis() == 0;
    }
    /** Pure snapshot: reading countdowns never expires or consumes an interaction. */
    public Status snapshot(boolean fixed, long intervalMillis, long bufferMillis) {
        long now = now();
        long intervalEnd = state.lastSentAt() == 0 ? 0 : state.lastSentAt() + Math.max(1_000, intervalMillis);
        long cooldownEnd = state.cooldownEnd();
        long bufferEnd = cooldownEnd == 0 ? 0 : cooldownEnd + Math.max(0, bufferMillis);
        boolean currentCooldown = cooldownEnd > state.lastSentAt()
            || cooldownEnd != 0 && cooldownEnd == state.lastSentAt() && state.retryAt() > state.lastSentAt();
        long next = fixed ? intervalEnd : currentCooldown
            ? Math.max(bufferEnd, state.retryAt()) : Math.max(Math.max(intervalEnd, bufferEnd), state.retryAt());
        if (!fixed && state.lastSentAt() != 0) next = Math.max(next, state.lastSentAt() + 1_000);
        long cooldown = Math.max(0, cooldownEnd - now);
        long buffer = cooldownEnd == 0 ? 0 : Math.max(0, bufferEnd - Math.max(now, cooldownEnd));
        long interval = Math.max(0, intervalEnd - now);
        long retry = Math.max(0, state.retryAt() - now);
        String phase = pending != null ? pending.dialog() ? "AWAITING_OVERRIDE" : "AWAITING_CONFIRMATION"
            : fixed ? interval > 0 ? "INTERVAL" : "READY"
            : cooldown > 0 ? "COOLDOWN" : retry > 0 ? "RETRY" : buffer > 0 ? "BUFFER"
            : Math.max(0, next - now) > 0 ? "INTERVAL" : "READY";
        if (pending != null) next = Math.max(next, pending.deadline());
        return new Status(phase, Math.max(0, next - now), cooldown, buffer, interval, retry,
            Math.max(now, next), state.learnedMillis(), pending != null, pending == null ? 0 : Math.max(0, pending.deadline() - now),
            cooldownEnd > 0 && state.lastSuccessAt() >= state.lastSentAt());
    }
    private long now() {
        // A backwards clock adjustment must not make confirmation precede dispatch or authorise an early retry.
        long floor = Math.max(state.lastSentAt(), state.lastSuccessAt());
        if (pending != null) floor = Math.max(floor, pending.actionAt());
        return Math.max(1, Math.max(floor, clock.getAsLong()));
    }
}
