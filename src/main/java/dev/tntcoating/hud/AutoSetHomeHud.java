package dev.tntcoating.hud;

import dev.tntcoating.core.ChatTeleportState;
import dev.tntcoating.modules.HighwayFly;
import java.util.ArrayList;
import java.util.List;
import meteordevelopment.meteorclient.systems.hud.HudElement;
import meteordevelopment.meteorclient.systems.hud.HudElementInfo;
import meteordevelopment.meteorclient.systems.hud.HudRenderer;
import meteordevelopment.meteorclient.systems.modules.Modules;

/** The next auto-set-home attempt, its timing mode and the server cooldown the helper is tracking. */
public final class AutoSetHomeHud extends HudElement {
    public static final HudElementInfo<AutoSetHomeHud> INFO = new HudElementInfo<>(CometHud.GROUP,
        "auto-set-home-timer", "Highway Fly auto set home: timing mode, next attempt and server cooldown.",
        AutoSetHomeHud::new);

    private final MeteorTextStyle style = new MeteorTextStyle(settings);

    private AutoSetHomeHud() {
        super(INFO);
    }

    @Override
    public void render(HudRenderer renderer) {
        HighwayFly fly = Modules.get().get(HighwayFly.class);
        HighwayFly.AutoSetHomeStatus status = fly == null ? null : fly.autoSetHomeStatus();
        List<String> lines = status == null ? editorLines() : lines(status);
        if (lines.isEmpty()) setSize(0, 0);
        else style.render(this, renderer, "Auto set home", lines);
    }

    private List<String> editorLines() {
        if (!isInEditor()) return List.of();
        return List.of("Mode: Auto detect", "Home: test", "Cooldown: 4m 54s", "Buffer: 10s (after cooldown)", "Next: 5m 4s");
    }

    private static List<String> lines(HighwayFly.AutoSetHomeStatus status) {
        List<String> lines = new ArrayList<>(5);
        lines.add("Mode: " + status.mode());
        lines.add((status.waiting() ? "Confirm: " : "Next: ") + remaining(status.waiting() ? status.schedule().responseMillis() : status.waitMillis()));
        lines.add("Home: " + (status.home().isEmpty() ? "not set" : status.home()));

        var schedule = status.schedule();
        if (status.paused()) lines.add("Paused: countdown continues");
        lines.add("State: " + schedule.phase().toLowerCase(java.util.Locale.ROOT).replace('_', ' '));
        if (status.fixedTimer()) {
            lines.add("Interval: " + remaining(schedule.intervalMillis()));
            lines.add("Server cooldown: " + remaining(schedule.cooldownMillis()) + " (informational)");
        } else {
            lines.add("Cooldown: " + (schedule.learnedMillis() == 0 && schedule.cooldownMillis() == 0
                ? "learning" : remaining(schedule.cooldownMillis())));
            lines.add("Buffer: " + remaining(schedule.bufferMillis())
                + (schedule.cooldownMillis() > 0 ? " (after cooldown)" : ""));
            if (schedule.retryMillis() > 0) lines.add("Retry: " + remaining(schedule.retryMillis()));
        }
        return lines;
    }

    private static String remaining(long millis) {
        return millis <= 0 ? "now" : ChatTeleportState.remaining(millis);
    }
}
