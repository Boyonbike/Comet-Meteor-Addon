package dev.tntcoating.core;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Server-reported teleport timers. An outgoing command alone never invents a duration. */
public final class ChatTeleportState {
    public enum Kind { Home, TPA }
    public enum Phase { Waiting, Request, Teleport, Cooldown }
    public record Timer(Kind kind, Phase phase, String target, long deadline, long expires) {
        public String summary(long now) {
            if (deadline == 0) return kind + " waiting";
            return kind + (phase == Phase.Cooldown ? " CD " : phase == Phase.Request ? " Req " : " ")
                + Math.max(0, (deadline - now + 999) / 1000) + "s";
        }

        public String label(long now) {
            String title = kind + (target.isEmpty() ? "" : " " + target);
            if (deadline == 0) return title + ": awaiting server";
            long seconds = Math.max(0, (deadline - now + 999) / 1000);
            return title + ": " + switch (phase) {
                case Cooldown -> "cooldown ";
                case Request -> "request ";
                default -> "teleport ";
            } + seconds + "s";
        }
    }

    private static final Pattern DURATION = Pattern.compile("(\\d+(?:\\.\\d+)?)\\s*(seconds?|secs?|s|minutes?|mins?|m)\\b");
    private static final Pattern HOME_WORD = Pattern.compile("\\b(?:home|homes|homecancel)\\b");
    private static final Pattern TPA_WORD = Pattern.compile("\\b(?:tpa|tpahere|tpaccept|tpacancel|teleport request|teleportation request)\\b");
    private static final Pattern WHITESPACE = Pattern.compile("\\s+");
    private final EnumMap<Kind, Timer> timers = new EnumMap<>(Kind.class);
    private Kind lastKind;
    private long lastCommand;

    public void clear() { timers.clear(); lastKind = null; }

    public void sent(String command, long now) {
        String stripped = command.strip();
        String[] tokens = WHITESPACE.split(stripped.startsWith("/") ? stripped.substring(1) : stripped);
        String lower = tokens[0].toLowerCase(Locale.ROOT);
        String verb = lower.substring(lower.lastIndexOf(':') + 1);
        if (verb.equals("homecancel")) { timers.remove(Kind.Home); return; }
        if (verb.equals("tpacancel") || verb.equals("tpcancel")) { timers.remove(Kind.TPA); return; }
        if (verb.equals("tpdeny") || verb.equals("tpn")) {
            Timer timer = timers.get(Kind.TPA);
            if (timer != null && tokens.length > 1 && timer.target().equalsIgnoreCase(tokens[1])) timers.remove(Kind.TPA);
            return;
        }
        Kind kind = switch (verb) {
            case "home" -> Kind.Home;
            case "tpa", "tpahere", "tpaccept", "tpyes", "tpy" -> Kind.TPA;
            default -> null;
        };
        if (kind == null) return;
        lastKind = kind;
        lastCommand = now;
        timers.put(kind, new Timer(kind, Phase.Waiting, tokens.length > 1 ? tokens[1] : "", 0, now + 120_000));
    }

    public void message(String raw, long now) {
        String text = ChatSender.plain(raw).toLowerCase(Locale.ROOT);
        boolean home = HOME_WORD.matcher(text).find();
        boolean tpa = TPA_WORD.matcher(text).find();
        boolean teleport = text.contains("teleport") || home || tpa;
        boolean cooldownReply = now - lastCommand < 5_000 && (text.contains("wait") || text.contains("cooldown"))
            && (text.contains("command") || text.contains("again"));
        if ((!teleport && !cooldownReply) || lastKind == null || now - lastCommand > 120_000) return;
        Kind kind = home ? Kind.Home : tpa ? Kind.TPA : lastKind;
        Timer previous = timers.get(kind);
        Matcher duration = DURATION.matcher(text);
        boolean hasDuration = duration.find();
        boolean cooldown = text.contains("cooldown") || text.contains("wait") && (text.contains("before") || text.contains("again"))
            || text.contains("can use") || text.contains("can teleport again")
            || (text.contains("cannot teleport") || text.contains("can't teleport")) && text.contains("for");
        if (hasDuration && cooldown) {
            double seconds = Double.parseDouble(duration.group(1));
            if (duration.group(2).startsWith("m")) seconds *= 60;
            if (seconds > 0 && seconds <= 86_400) {
                long deadline = now + (long) Math.ceil(seconds * 1000);
                timers.put(kind, new Timer(kind, Phase.Cooldown, previous == null ? "" : previous.target(), deadline, deadline));
                return;
            }
        }
        if (previous == null) return;
        if (text.contains("cancelled") || text.contains("canceled") || text.contains("denied")
            || text.contains("declined") || text.contains("expired") || text.contains("teleported") && !text.contains("teleported in")
            || text.contains("does not exist") || text.contains("not found") || text.contains("no pending")
            || text.contains("cannot teleport") || text.contains("can't teleport") || text.contains("invalid home")
            || text.contains("no home") || text.contains("offline") || text.contains("not online")) {
            timers.remove(kind);
            return;
        }
        if (!hasDuration) {
            if (text.contains("accepted")) timers.put(kind, new Timer(kind, Phase.Waiting, previous.target(), 0, now + 30_000));
            return;
        }
        double seconds = Double.parseDouble(duration.group(1));
        if (duration.group(2).startsWith("m")) seconds *= 60;
        if (seconds <= 0 || seconds > 86_400) return;
        Phase phase;
        if (text.contains("expir") || text.contains("time out") || text.contains("timeout")) phase = Phase.Request;
        else if (text.contains("teleporting") || text.contains("commenc") || text.contains("teleport in")
            || text.contains("teleported in") || text.contains("teleportation in")) phase = Phase.Teleport;
        else return;
        long deadline = now + (long) Math.ceil(seconds * 1000);
        timers.put(kind, new Timer(kind, phase, previous.target(), deadline,
            deadline + (phase == Phase.Teleport ? 15_000 : 0)));
    }

    public List<Timer> timers(long now) {
        timers.values().removeIf(timer -> now >= timer.expires());
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
