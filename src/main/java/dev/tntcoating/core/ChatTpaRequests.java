package dev.tntcoating.core;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Incoming requests are separate from outgoing TPA timers and are always answered by name. */
public final class ChatTpaRequests {
    /**
     * Verbs are the server's own accept/deny commands (namespace included) when its clickable prompt offered them.
     * Null verbs are unknown: callers may pick the server's command, otherwise /tpy and /tpn are used.
     */
    public record Request(String player, boolean toThem, long expires, String yesVerb, String noVerb) {
        public Request(String player, boolean toThem, long expires) { this(player, toThem, expires, null, null); }
        public String yesCommand() { return "/" + (yesVerb == null ? "tpy" : yesVerb) + " " + player; }
        public String noCommand() { return "/" + (noVerb == null ? "tpn" : noVerb) + " " + player; }
        public boolean verbsKnown() { return yesVerb != null && noVerb != null; }
        public String description() { return toThem ? "Teleport to " + player : player + " wants to teleport to you"; }
    }
    public enum ChangeKind { Upsert, Cancelled, Expired, Accepted }
    public record Change(ChangeKind kind, Request request, boolean named) {
        public Change(ChangeKind kind,Request request) { this(kind,request,true); }
    }
    private final List<Change> changes = new ArrayList<>();
    private int maximum = 64, rejected;
    public void capacity(int value) { if(value<1||value>16384) throw new IllegalArgumentException("Invalid request capacity"); maximum=value; }
    public int rejected() { return rejected; }
    public List<Change> drainChanges() { var result=List.copyOf(changes);changes.clear();return result; }
    private void changed(ChangeKind kind,Request request) { changed(kind,request,true); }
    private void changed(ChangeKind kind,Request request,boolean named) {
        if(changes.size()>=32768) changes.removeFirst();
        changes.add(new Change(kind,request,named));
    }
    private void remove(Request request,ChangeKind kind) { remove(request,kind,true); }
    private void remove(Request request,ChangeKind kind,boolean named) { if(requests.remove(key(request.player()))!=null) changed(kind,request,named); }
    private record Decision(String player, boolean yes, String verb) {}
    private static final String NAME = "([A-Za-z0-9_]{1,16})";
    private static final String PREFIX = "^(?:\\[[^]\\r\\n]{1,40}]\\s*)*";
    private static final List<Pattern> INCOMING = List.of(
        Pattern.compile(PREFIX + NAME + "\\s+(?:has\\s+)?(?:requested|requests|wants|would like)\\s+(?:to teleport to you|(?:that you|you to) teleport to them)\\b.*", Pattern.CASE_INSENSITIVE | Pattern.DOTALL),
        Pattern.compile(PREFIX + NAME + "\\s+(?:has\\s+)?sent you (?:a )?(?:teleport|teleportation|tpa) request\\b.*", Pattern.CASE_INSENSITIVE | Pattern.DOTALL),
        Pattern.compile(PREFIX + "(?:incoming |you (?:have )?received (?:a )?)?(?:teleport|teleportation|tpa) request from\\s+" + NAME + "\\b.*", Pattern.CASE_INSENSITIVE | Pattern.DOTALL)
    );
    private static final Pattern PLAYER_CHAT = Pattern.compile(PREFIX + "(?:<[A-Za-z0-9_]{1,16}>|[A-Za-z0-9_]{1,16}\\s*[:»›])\\s+.*", Pattern.DOTALL);
    private static final Pattern EXPIRY = Pattern.compile("(?:expir\\w*|time ?out)\\s+(?:(?:in|after)\\s+)?(\\d+(?:\\.\\d+)?)\\s*(seconds?|secs?|s|minutes?|mins?|m)\\b", Pattern.CASE_INSENSITIVE);
    private static final Pattern LEADING_SYMBOLS = Pattern.compile("^[»›•▶→\\s]+");
    private static final Pattern NAME_COLON = Pattern.compile("^[A-Za-z0-9_]{1,16}:.*");
    private static final Pattern USERNAME = Pattern.compile("[A-Za-z0-9_]{1,16}");
    private static final Pattern WHITESPACE = Pattern.compile("\\s+");
    /** An optional plain namespace, such as essentials:, then the command name. Nothing else is ever sent back. */
    private static final Pattern VERB = Pattern.compile("(?:[A-Za-z0-9_.-]{1,32}:)?([A-Za-z]{2,16})");
    private final Map<String, Request> requests = new LinkedHashMap<>();
    private String mostRecent;
    private long mostRecentAt;

    public void clear() { requests.clear(); changes.clear(); rejected=0; mostRecent = null; }

    public List<Request> pending(long now) {
        var expired = requests.values().stream().filter(request -> now >= request.expires()).toList();
        expired.forEach(request -> remove(request,ChangeKind.Expired));
        return new ArrayList<>(requests.values());
    }

    public void message(String raw, List<String> clickableCommands, String self, long now, int timeoutSeconds) {
        pending(now);
        String text = LEADING_SYMBOLS.matcher(ChatSender.plain(raw).strip()).replaceFirst("");
        if (PLAYER_CHAT.matcher(text).matches()) return;
        String lower = text.toLowerCase(Locale.ROOT);
        boolean requestText = lower.contains("request") && (lower.contains("teleport") || lower.contains("tpa"));
        boolean terminal = requestText && (lower.contains("cancelled") || lower.contains("canceled") || lower.contains("expired")
            || lower.contains("denied") || lower.contains("declined") || lower.contains("accepted"));
        if (terminal) {
            // An acknowledgement of an outgoing request does not consume an incoming request from that player.
            if (lower.contains("your teleport request") && !lower.startsWith("you ")) return;
            var named = requests.values().stream().filter(request -> Pattern.compile("\\b" + Pattern.quote(request.player()) + "\\b", Pattern.CASE_INSENSITIVE).matcher(text).find()).toList();
            ChangeKind kind=lower.contains("accepted")?ChangeKind.Accepted:lower.contains("expired")?ChangeKind.Expired:ChangeKind.Cancelled;
            if (!named.isEmpty()) named.forEach(request -> remove(request,kind));
            else if (requests.size() == 1 && !NAME_COLON.matcher(text).matches()) remove(requests.values().iterator().next(),kind,false);
            return;
        }

        long expires = expiry(text, now, now + Math.max(1, timeoutSeconds) * 1_000L);
        boolean toThem = lower.contains("to them") || lower.contains("that you teleport");
        Map<String, Decision> yes = new LinkedHashMap<>();
        Map<String, Decision> no = new LinkedHashMap<>();
        for (String command : clickableCommands) {
            Decision decision = decision(command);
            if (decision != null) (decision.yes() ? yes : no).put(key(decision.player()), decision);
        }
        boolean added = false;
        for (Decision decision : yes.values()) {
            Decision deny = no.get(key(decision.player()));
            if (deny == null) continue;
            // Answer with the exact commands the server's own buttons use.
            add(decision.player(), self, toThem, expires, now, decision.verb(), deny.verb());
            added = true;
        }
        if (!added) for (Pattern pattern : INCOMING) {
            Matcher match = pattern.matcher(text);
            if (!match.matches()) continue;
            add(match.group(1), self, toThem, expires, now, null, null);
            added = true;
            break;
        }
        // Many servers put the request lifetime on a separate line immediately after the invitation.
        if (!added && requestText && mostRecent != null && now - mostRecentAt < 5_000 && EXPIRY.matcher(text).find()) {
            Request previous = requests.get(mostRecent);
            if (previous != null) {
                var updated=new Request(previous.player(),previous.toThem(),expires,previous.yesVerb(),previous.noVerb());
                requests.put(mostRecent,updated);changed(ChangeKind.Upsert,updated);
            }
        }
    }

    public void sent(String command, long now) {
        pending(now);
        Decision decision = decision(command);
        if (decision != null) requests.remove(key(decision.player()));
    }

    public boolean isPending(Request request, long now) {
        pending(now);
        return request.equals(requests.get(key(request.player())));
    }

    private void add(String player, String self, boolean toThem, long expires, long now, String yesVerb, String noVerb) {
        if (self != null && player.equalsIgnoreCase(self)) return;
        String key = key(player);
        if(!requests.containsKey(key)&&requests.size()>=maximum) { rejected++;return; }
        requests.remove(key);
        var request=new Request(player,toThem,expires,yesVerb,noVerb);
        requests.put(key,request);changed(ChangeKind.Upsert,request);
        mostRecent = key;
        mostRecentAt = now;
    }

    private static Decision decision(String command) {
        String stripped = command.strip();
        String[] tokens = WHITESPACE.split(stripped.startsWith("/") ? stripped.substring(1) : stripped);
        if (tokens.length != 2 || !USERNAME.matcher(tokens[1]).matches()) return null;
        Matcher verbMatch = VERB.matcher(tokens[0]);
        if (!verbMatch.matches()) return null;
        String verb = verbMatch.group(1).toLowerCase(Locale.ROOT);
        return switch (verb) {
            case "tpy", "tpaccept", "tpyes" -> new Decision(tokens[1], true, tokens[0]);
            case "tpn", "tpdeny", "tpno" -> new Decision(tokens[1], false, tokens[0]);
            default -> null;
        };
    }

    private static long expiry(String text, long now, long fallback) {
        Matcher match = EXPIRY.matcher(text);
        if (!match.find()) return fallback;
        double seconds = Double.parseDouble(match.group(1));
        if (match.group(2).toLowerCase(Locale.ROOT).startsWith("m")) seconds *= 60;
        return seconds > 0 && seconds <= 86_400 ? now + (long) Math.ceil(seconds * 1_000) : fallback;
    }

    private static String key(String name) { return name.toLowerCase(Locale.ROOT); }
}
