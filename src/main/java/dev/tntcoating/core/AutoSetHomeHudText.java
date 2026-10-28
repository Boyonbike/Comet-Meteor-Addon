package dev.tntcoating.core;

import java.util.ArrayList;
import java.util.List;

/** HUD labels read the scheduler snapshot without maintaining a second timer. */
public final class AutoSetHomeHudText {
    private AutoSetHomeHudText() {}
    public static List<String> lines(String mode, String home, boolean fixed, boolean paused,
                                    SetHomeScheduler.Status schedule) {
        List<String> lines = new ArrayList<>();
        lines.add("Mode: " + mode);
        lines.add((schedule.waiting() ? "Confirm: " : "Next: ")
            + remaining(schedule.waiting() ? schedule.responseMillis() : schedule.waitMillis()));
        lines.add("Home: " + (home.isEmpty() ? "not set" : home));
        if (paused) lines.add("Paused: countdown continues");
        lines.add("State: " + schedule.phase().toLowerCase(java.util.Locale.ROOT).replace('_', ' '));
        String cooldown = schedule.estimatedCooldown() ? "Estimated cooldown: " : "Server cooldown: ";
        if (fixed) {
            lines.add("Interval: " + remaining(schedule.intervalMillis()));
            lines.add(cooldown + remaining(schedule.cooldownMillis()) + " (informational)");
        } else {
            lines.add(cooldown + (schedule.learnedMillis() == 0 && schedule.cooldownMillis() == 0
                ? "learning" : remaining(schedule.cooldownMillis())));
            lines.add("Buffer: " + remaining(schedule.bufferMillis())
                + (schedule.cooldownMillis() > 0 ? " (after cooldown)" : ""));
            if (schedule.retryMillis() > 0) lines.add("Retry: " + remaining(schedule.retryMillis()));
        }
        return List.copyOf(lines);
    }
    private static String remaining(long millis) {
        return millis <= 0 ? "now" : ChatTeleportState.remaining(millis);
    }
}
