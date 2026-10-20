package dev.tntcoating.core;

import java.util.ArrayList;
import java.util.List;

/** Shared wall-clock countdown text for the chat tools window and Meteor HUD. */
public final class ChatCountdowns {
    private ChatCountdowns() {}
    public static String request(ChatTpaRequests.Request request, long now) {
        return (request.toThem() ? "Travel to " : "Request from ") + request.player() + ": "
            + ChatTeleportState.remaining(request.expires() - now);
    }
    public static List<String> lines(List<ChatTeleportState.Timer> timers, List<ChatTpaRequests.Request> requests, long now) {
        List<String> result = new ArrayList<>();
        for (var timer : timers) result.add(timer.label(now));
        for (var request : requests) result.add(request(request, now));
        return List.copyOf(result);
    }
    public static String player(String player, List<ChatTeleportState.Timer> timers, List<ChatTpaRequests.Request> requests, long now) {
        List<String> result = new ArrayList<>();
        for (var timer : timers) if (timer.kind() == ChatTeleportState.Kind.TPA && timer.target().equalsIgnoreCase(player)) result.add(timer.status(now));
        for (var request : requests) if (request.player().equalsIgnoreCase(player)) result.add("Incoming request " + ChatTeleportState.remaining(request.expires() - now));
        return String.join(" \u00b7 ", result);
    }
    public static String home(String home, List<ChatTeleportState.Timer> timers, long now) {
        return timers.stream().filter(t -> t.kind() == ChatTeleportState.Kind.Home && t.target().equalsIgnoreCase(home))
            .map(t -> t.status(now)).findFirst().orElse("");
    }
}
