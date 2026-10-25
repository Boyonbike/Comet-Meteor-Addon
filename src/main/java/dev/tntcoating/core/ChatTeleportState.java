package dev.tntcoating.core;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;

/** Teleport lifecycle, followed by a server-reported or explicitly estimated cooldown. */
public final class ChatTeleportState {
    public enum Kind { Home, TPA }
    public enum Phase { Waiting, Request, Teleport, Cooldown }
    public record Timer(Kind kind, Phase phase, String target, long deadline, long expires, boolean estimated) {
        public Timer(Kind kind, Phase phase, String target, long deadline, long expires) {
            this(kind, phase, target, deadline, expires, false);
        }
        /** The state without the target, for a row or tile that already names it. */
        public String status(long now) {
            if (deadline == 0) return "Waiting for server";
            String time = (estimated ? "~" : "") + remaining(deadline - now);
            return switch (phase) {
                case Cooldown -> "Cooldown " + time;
                case Request -> "Request pending " + time;
                default -> "Teleporting in " + time;
            };
        }
        public String label(long now) {
            String title = kind + (target.isEmpty() ? "" : " " + target);
            if (deadline == 0) return title + ": awaiting server";
            return title + ": " + switch (phase) {
                case Cooldown -> "cooldown ";
                case Request -> "request ";
                default -> "teleport ";
            } + (estimated ? "~" : "") + remaining(deadline - now);
        }
    }

    /** Time left, rounded up to the second: "45s", "9m 35s", "10m" or "1h 5m". */
    public static String remaining(long millis) {
        long seconds = Math.max(0, (millis + 999) / 1000);
        if (seconds < 60) return seconds + "s";
        if (seconds < 3_600) return seconds / 60 + "m" + (seconds % 60 == 0 ? "" : " " + seconds % 60 + "s");
        long minutes = (seconds + 59) / 60;
        return minutes / 60 + "h" + (minutes % 60 == 0 ? "" : " " + minutes % 60 + "m");
    }
    private record Operation(String target, long refreshedAt) {}
    private record Completed(String target, long at) {}

    /** One duration stated in a line, and where in that line its number starts. */
    public record Duration(long millis, int start) {}

    private static final Pattern DURATION = Pattern.compile("(\\d+(?:\\.\\d+)?)\\s*(hours?|hrs?|h|minutes?|mins?|m|seconds?|secs?|s)(?![a-z])");
    /** What may separate the parts of one compound duration, as in "9m 35s" or "1 minute and 5 seconds". */
    private static final Pattern DURATION_JOIN = Pattern.compile("\\s*(?:,|and)?\\s*");
    private static final Pattern HOME_WORD = Pattern.compile("\\b(?:home|homes|homecancel)\\b");
    private static final Pattern TPA_WORD = Pattern.compile("\\b(?:tpa|tpahere|tpaccept|tpacancel|teleport request|teleportation request)\\b");
    private static final Pattern COOLDOWN_CUE = Pattern.compile("cooldown|\\bwait\\b|can use|can teleport again|cannot teleport|can't teleport");
    // "Teleporting to: base" is 6b6t's line for the moment a home teleport happens; its warmup line has no colon.
    private static final Pattern SUCCESS = Pattern.compile("\\bteleported\\b|\\bteleport(?:ation)?\\s+(?:complete(?:d)?|successful|success)\\b|\\bteleporting to:\\s*\\S");
    private static final Pattern FUTURE = Pattern.compile("\\b(?:will|would|going to|about to)\\b.*\\bteleport|\\bteleported\\s+(?:in|after|within)\\b", Pattern.DOTALL);
    private static final Pattern NEGATIVE_SUCCESS = Pattern.compile("\\b(?:not|never)\\s+(?:been\\s+)?teleported\\b|\\b(?:haven't|hasn't|wasn't|weren't)\\b.*\\bteleported\\b", Pattern.DOTALL);
    private static final Pattern COOLDOWN_FINISHED = Pattern.compile("\\bcooldown\\b.*\\b(?:expired|ended|finished|over)\\b|\\b(?:can|may)\\s+(?:now\\s+)?(?:use|teleport)\\b.*\\bagain\\b", Pattern.DOTALL);
    private static final Pattern NEGATED = Pattern.compile("\\b(?:not|never|cannot|can't|hasn't|isn't|haven't|wasn't|won't)\\b");
    private static final Pattern WHITESPACE = Pattern.compile("\\s+");
    private final EnumMap<Kind, Timer> timers = new EnumMap<>(Kind.class);
    private final EnumMap<Kind, Operation> operations = new EnumMap<>(Kind.class);
    private final EnumMap<Kind, Completed> completed = new EnumMap<>(Kind.class);
    private final EnumMap<Kind, Long> announcedCooldowns = new EnumMap<>(Kind.class);
    private final EnumMap<Kind, Long> learnedCooldowns = new EnumMap<>(Kind.class);
    private final EnumMap<Kind, Long> configuredCooldowns = new EnumMap<>(Kind.class);
    private final EnumMap<Kind, Long> manualCooldowns = new EnumMap<>(Kind.class);
    private Kind lastKind;
    private long lastContextAt;

    /** Fallback durations are opt-in for a state instance. They are always labelled as estimates. */
    public void cooldowns(int homeSeconds, int tpaSeconds) {
        configuredCooldowns.put(Kind.Home, Math.clamp(homeSeconds, 0, 86_400) * 1_000L);
        configuredCooldowns.put(Kind.TPA, Math.clamp(tpaSeconds, 0, 86_400) * 1_000L);
    }

    /** Session reset removes learned durations so one server never teaches another server's cooldown. */
    public void clear() {
        timers.clear(); operations.clear(); completed.clear(); announcedCooldowns.clear(); learnedCooldowns.clear(); manualCooldowns.clear();
        lastKind = null;
    }

    public void sent(String command, long now) {
        expire(now);
        String stripped = command.strip();
        String[] tokens = WHITESPACE.split(stripped.startsWith("/") ? stripped.substring(1) : stripped);
        String lower = tokens[0].toLowerCase(Locale.ROOT);
        String verb = lower.substring(lower.lastIndexOf(':') + 1);
        if (verb.equals("homecancel")) { stopOperation(Kind.Home); return; }
        if (verb.equals("tpacancel") || verb.equals("tpcancel")) { stopOperation(Kind.TPA); return; }
        if (verb.equals("tpdeny") || verb.equals("tpn") || verb.equals("tpno")) {
            var operation = operations.get(Kind.TPA);
            if (operation != null && tokens.length > 1 && operation.target().equalsIgnoreCase(tokens[1])) stopOperation(Kind.TPA);
            return;
        }
        Kind kind = switch (verb) {
            case "home" -> Kind.Home;
            case "tpa", "tpahere", "tpaccept", "tpyes", "tpy" -> Kind.TPA;
            default -> null;
        };
        if (kind == null) return;
        lastKind = kind; lastContextAt = now;
        String target = tokens.length > 1 ? tokens[1] : "";
        operations.put(kind, new Operation(target, now));
        announcedCooldowns.remove(kind);
        // A retry must not erase a cooldown while waiting for the server to accept or reject that retry.
        Timer previous = timers.get(kind);
        if (previous == null || previous.phase() != Phase.Cooldown)
            timers.put(kind, new Timer(kind, Phase.Waiting, target, 0, now + 120_000));
    }

    public void message(String raw, long now) {
        expire(now);
        if (lastKind == null) return;
        String text = ChatSender.plain(raw).toLowerCase(Locale.ROOT);
        boolean home = HOME_WORD.matcher(text).find();
        boolean tpa = TPA_WORD.matcher(text).find();
        boolean cooldownText = COOLDOWN_CUE.matcher(text).find();
        boolean genericCooldown = text.contains("cooldown") || text.contains("wait") && (text.contains("command") || text.contains("again") || text.contains("before"));
        if (!home && !tpa && !text.contains("teleport") && !(genericCooldown && now - lastContextAt < 5_000)) return;
        Kind kind = home ? Kind.Home : tpa ? Kind.TPA : lastKind;
        Timer previous = timers.get(kind);
        Operation operation = operations.get(kind);
        Completed success = completed.get(kind);
        if (previous == null && operation == null && (success == null || now - success.at() > 120_000)) return;
        if (home || tpa) { lastKind = kind; lastContextAt = now; }

        boolean request = text.contains("expir") || text.contains("time out") || text.contains("timeout");
        boolean warmup = text.contains("teleporting") || text.contains("commenc") || text.contains("teleport in")
            || text.contains("teleported in") || text.contains("teleportation in");
        List<Duration> durations = durations(text);
        Duration cooldown = cooldownText ? cooldownDuration(text, durations, warmup || request) : null;
        if (previous != null && previous.phase() == Phase.Cooldown && durations.isEmpty()
            && COOLDOWN_FINISHED.matcher(text).find() && !NEGATED.matcher(text).find()) {
            timers.remove(kind);
            return;
        }
        boolean failed = text.contains("cancelled") || text.contains("canceled") || text.contains("denied")
            || text.contains("declined") || text.contains("expired") || text.contains("does not exist") || text.contains("not found")
            || text.contains("no pending") || text.contains("cannot teleport") || text.contains("can't teleport")
            || text.contains("cannot be teleported") || text.contains("could not") || text.contains("failed")
            || text.contains("invalid home") || text.contains("no home") || text.contains("offline") || text.contains("not online")
            || NEGATIVE_SUCCESS.matcher(text).find();

        if (!failed && SUCCESS.matcher(text).find() && !FUTURE.matcher(text).find()) {
            if (operation != null) complete(kind, operation, cooldown, now);
            else if (cooldown != null) reportCooldown(kind, cooldown.millis(), now);
            // Duplicate success lines, including chat/action-bar copies, must never restart the countdown.
            return;
        }

        if (failed) {
            if (cooldown != null) reportCooldown(kind, cooldown.millis(), now);
            else stopOperation(kind);
            return;
        }

        Duration activeDuration = durations.stream().filter(d -> d != cooldown).findFirst().orElse(null);
        if ((warmup || request) && activeDuration != null && activeDuration.millis() > 0 && operation != null) {
            if (cooldown != null) announcedCooldowns.put(kind, cooldown.millis());
            Phase phase = request ? Phase.Request : Phase.Teleport;
            long deadline = now + activeDuration.millis();
            operations.put(kind, new Operation(operation.target(), now));
            timers.put(kind, new Timer(kind, phase, operation.target(), deadline, deadline + (phase == Phase.Teleport ? 15_000 : 0)));
            return;
        }
        if (cooldown != null) { reportCooldown(kind, cooldown.millis(), now); return; }
        if (operation != null && text.contains("accepted")) {
            operations.put(kind, new Operation(operation.target(), now));
            timers.put(kind, new Timer(kind, Phase.Waiting, operation.target(), 0, now + 30_000));
        }
    }

    /**
     * Networks such as 6b6t complete a teleport to a player or home on another of their servers by moving the player
     * there, with no success line. A switch at the end of a warmup therefore confirms that teleport.
     */
    public void transferred(long now) {
        expire(now);
        for (Kind kind : Kind.values()) {
            Timer timer = timers.get(kind);
            Operation operation = operations.get(kind);
            if (timer != null && operation != null && timer.phase() == Phase.Teleport && now >= timer.deadline() - 2_000)
                complete(kind, operation, null, now);
        }
    }

    private void complete(Kind kind, Operation operation, Duration cooldown, long now) {
        completed.put(kind, new Completed(operation.target(), now));
        operations.remove(kind);
        lastKind = kind; lastContextAt = now;
        Long announced = announcedCooldowns.remove(kind);
        long millis = cooldown != null ? cooldown.millis() : announced != null ? announced : estimate(kind);
        if (cooldown != null || announced != null) learnedCooldowns.put(kind, millis);
        if (millis > 0) startCooldown(kind, operation.target(), millis, now, cooldown == null && announced == null);
        else timers.remove(kind);
    }

    /** Every duration stated in a lower-case line, with compound forms such as "9m 35s" merged. */
    public static List<Duration> durations(String text) {
        List<Duration> result = new ArrayList<>();
        var match = DURATION.matcher(text);
        int previousEnd = -1, previousUnit = 0;
        while (match.find()) {
            String unit = match.group(2);
            int scale = unit.startsWith("h") ? 3_600 : unit.startsWith("m") ? 60 : 1;
            long millis = (long) Math.ceil(Double.parseDouble(match.group(1)) * scale * 1_000);
            // A smaller unit directly after a larger one continues it: "9m 35s" is one 575-second duration.
            boolean continues = !result.isEmpty() && scale < previousUnit
                && DURATION_JOIN.matcher(text).region(previousEnd, match.start()).matches();
            if (continues) {
                Duration first = result.removeLast();
                millis += first.millis();
                if (millis <= 86_400_000L) result.add(new Duration(millis, first.start()));
            } else if (millis <= 86_400_000L) result.add(new Duration(millis, match.start()));
            previousEnd = match.end();
            previousUnit = scale;
        }
        return result;
    }

    /** Keep warmup/request duration separate from a cooldown announced on the same line. */
    private static Duration cooldownDuration(String text, List<Duration> durations, boolean hasOtherPhase) {
        var cue = COOLDOWN_CUE.matcher(text);
        if (!cue.find()) return null;
        for (Duration duration : durations) if (duration.start() >= cue.end()) return duration;
        return !hasOtherPhase && !durations.isEmpty() ? durations.getLast() : null;
    }

    private void reportCooldown(Kind kind, long millis, long now) {
        Timer previous = timers.get(kind);
        Operation operation = operations.get(kind);
        Completed success = completed.get(kind);
        String target = previous != null ? previous.target() : success != null ? success.target() : operation != null ? operation.target() : "";
        if (success != null && now >= success.at() && now - success.at() <= 86_400_000) {
            // The server often reports remaining time after a retry; include elapsed time since confirmed success.
            learnedCooldowns.put(kind, Math.min(86_400_000L, millis + now - success.at()));
        }
        if (millis > 0) startCooldown(kind, target, millis, now, false);
        else if (previous != null && previous.phase() == Phase.Cooldown) timers.remove(kind);
        operations.remove(kind);
        announcedCooldowns.remove(kind);
    }

    private void startCooldown(Kind kind, String target, long millis, long now, boolean estimated) {
        long deadline = now + millis;
        timers.put(kind, new Timer(kind, Phase.Cooldown, target, deadline, deadline, estimated));
    }

    private void stopOperation(Kind kind) {
        operations.remove(kind); announcedCooldowns.remove(kind);
        Timer previous = timers.get(kind);
        if (previous != null && previous.phase() != Phase.Cooldown) timers.remove(kind);
    }

    private void expire(long now) {
        timers.values().removeIf(timer -> now >= timer.expires());
        for (Kind kind : Kind.values()) {
            Operation operation = operations.get(kind);
            Timer active = timers.get(kind);
            if (operation != null && now - operation.refreshedAt() >= 120_000 && (active == null || active.phase() == Phase.Cooldown)) {
                operations.remove(kind); announcedCooldowns.remove(kind);
            }
        }
    }

    /** The length a cooldown is assumed to last when the server has not said: manual, then learned, then the fallback. */
    private long estimate(Kind kind) {
        Long manual = manualCooldowns.get(kind);
        return manual != null ? manual : learnedCooldowns.getOrDefault(kind, configuredCooldowns.getOrDefault(kind, 0L));
    }

    /** Lengths the player set themselves; they replace learned and fallback estimates but never a time the server reports. */
    public Map<Kind, Long> manual() { return Map.copyOf(manualCooldowns); }
    public void manual(Map<Kind, Long> durations) {
        manualCooldowns.clear();
        durations.forEach((kind, millis) -> { if (millis >= 0 && millis <= 86_400_000L) manualCooldowns.put(kind, millis); });
    }

    /**
     * Sets a manual length, or with null returns to the learned one. An estimated countdown that is running (or has
     * already run out) is re-timed from when its teleport finished, so a newly claimed reduction shows straight away.
     */
    public void manual(Kind kind, Long millis, long now) {
        expire(now);
        if (millis == null) manualCooldowns.remove(kind);
        else manualCooldowns.put(kind, Math.clamp(millis, 0, 86_400_000L));
        Timer timer = timers.get(kind);
        Completed success = completed.get(kind);
        if (success == null || timer != null && (timer.phase() != Phase.Cooldown || !timer.estimated())) return;
        long deadline = success.at() + estimate(kind);
        if (deadline > now) startCooldown(kind, success.target(), deadline - now, now, true);
        else timers.remove(kind);
    }

    /** Cooldown lengths learned from server replies, for keeping between sessions on the same server. */
    public Map<Kind, Long> learned() { return Map.copyOf(learnedCooldowns); }
    public void learned(Map<Kind, Long> durations) {
        learnedCooldowns.clear();
        durations.forEach((kind, millis) -> { if (millis > 0 && millis <= 86_400_000L) learnedCooldowns.put(kind, millis); });
    }

    public List<Timer> timers(long now) {
        expire(now);
        List<Timer> result = new ArrayList<>();
        for (Timer timer : timers.values()) {
            // Reaching zero is not evidence that the server actually teleported the player.
            result.add(timer.deadline() > 0 && now >= timer.deadline() && timer.phase() == Phase.Teleport
                ? new Timer(timer.kind(), Phase.Waiting, timer.target(), 0, timer.expires()) : timer);
        }
        return result;
    }

    public boolean canCancel(Kind kind, long now) {
        Timer timer = timers.get(kind);
        return timer != null && timer.phase() != Phase.Cooldown && now < timer.expires()
            && (timer.deadline() == 0 || now < timer.deadline());
    }
}
