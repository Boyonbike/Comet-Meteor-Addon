package dev.tntcoating.core;

import java.util.Locale;
import java.util.Optional;
import java.util.regex.Pattern;

/** Deliberately narrow server-reply recogniser. Unknown wording stays unconfirmed. */
public final class AutoTpyFeedback {
    public enum Kind { Accepted, RateLimited, Cancelled, Expired, NoPending }
    public record Observation(Kind kind,String player,Long waitMs,String fingerprint) {}
    private static final String NAME="([A-Za-z0-9_]{1,16})";
    private static final Pattern PREFIX=Pattern.compile("^(?:\\[[^]\\r\\n]{1,40}]\\s*)*[»›•▶→\\s]*");
    private static final Pattern ACCEPT=Pattern.compile("^you (?:have )?accepted (?:the )?(?:teleport request from "+NAME+"|"+NAME+"(?:'s|’s) teleport request)[.!]?$",Pattern.CASE_INSENSITIVE);
    private static final Pattern TERMINAL=Pattern.compile("^(?:teleport request from "+NAME+" (?:has (?:been )?|was )?(cancelled|canceled|expired|denied|declined)|"+NAME+" (cancelled|canceled|denied|declined) (?:their |the )?teleport request)[.!]?$",Pattern.CASE_INSENSITIVE);
    private static final Pattern POSSESSIVE_TERMINAL=Pattern.compile("^"+NAME+"(?:'s|’s) teleport request (?:has (?:been )?|was )?(expired|cancelled|canceled|denied|declined)[.!]?$",Pattern.CASE_INSENSITIVE);
    private static final Pattern DENIED=Pattern.compile("^you (?:have )?(?:denied|declined|cancelled|canceled) "+NAME+"(?:'s|’s) teleport request[.!]?$",Pattern.CASE_INSENSITIVE);
    private static final Pattern WAIT=Pattern.compile("(?:please |you (?:must |have to )?)?wait (\\d+(?:\\.\\d+)?)\\s*(seconds?|secs?|s|minutes?|mins?|m)\\b.*(?:command|teleport|tpy|tpaccept|again).*",Pattern.CASE_INSENSITIVE);
    private AutoTpyFeedback() {}
    public static Optional<Observation> parse(String raw) {
        String text=PREFIX.matcher(ChatSender.plain(raw).strip()).replaceFirst("");
        var accepted=ACCEPT.matcher(text);
        if(accepted.matches()) return observation(Kind.Accepted,accepted.group(1)!=null?accepted.group(1):accepted.group(2),null,text);
        if(text.equalsIgnoreCase("Teleport request accepted.")||text.equalsIgnoreCase("Teleport request accepted")) return observation(Kind.Accepted,null,null,text);
        var terminal=TERMINAL.matcher(text);
        if(terminal.matches()) {
            String action=terminal.group(2)!=null?terminal.group(2):terminal.group(4);
            return observation(action.equalsIgnoreCase("expired")?Kind.Expired:Kind.Cancelled,terminal.group(1)!=null?terminal.group(1):terminal.group(3),null,text);
        }
        var possessive=POSSESSIVE_TERMINAL.matcher(text);
        if(possessive.matches()) return observation(possessive.group(2).equalsIgnoreCase("expired")?Kind.Expired:Kind.Cancelled,possessive.group(1),null,text);
        var denied=DENIED.matcher(text);
        if(denied.matches()) return observation(Kind.Cancelled,denied.group(1),null,text);
        String lower=text.toLowerCase(Locale.ROOT);
        if(lower.matches("(?:you have |there (?:is|are) )?no pending teleport requests?[.!]?")) return observation(Kind.NoPending,null,null,text);
        var wait=WAIT.matcher(text);
        if(wait.matches()) {
            double ms=Double.parseDouble(wait.group(1))*1000*(wait.group(2).toLowerCase(Locale.ROOT).startsWith("m")?60:1);
            if(ms>0&&ms<=86400000) return observation(Kind.RateLimited,null,(long)Math.ceil(ms),text);
        }
        if(lower.equals("you are sending commands too quickly.")||lower.equals("please slow down.")) return observation(Kind.RateLimited,null,null,text);
        return Optional.empty();
    }
    private static Optional<Observation> observation(Kind kind,String name,Long wait,String text) { return Optional.of(new Observation(kind,name,wait,text.toLowerCase(Locale.ROOT))); }
}
