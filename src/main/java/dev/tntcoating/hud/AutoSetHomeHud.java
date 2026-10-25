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
        return List.of("Mode: Auto detect", "Next: 24s", "Home: test", "Cooldown: ~4m");
    }

    private static List<String> lines(HighwayFly.AutoSetHomeStatus status) {
        List<String> lines = new ArrayList<>(5);
        lines.add("Mode: " + status.mode());
        lines.add((status.waiting() ? "Confirm: " : "Next: ") + remaining(status.waitMillis()));
        lines.add("Home: " + (status.home().isEmpty() ? "not set" : status.home()));

        String cooldown = status.cooldownLabel();
        if (status.fixedTimer()) {
            lines.add("Interval: " + remaining(status.intervalMillis()));
            if (status.cooldownKnown()) {
                lines.add((cooldown.startsWith("Buffer ") || cooldown.isEmpty() ? "" : "Server: ")
                    + (cooldown.isEmpty() ? "clear" : cooldown) + " (ignored)");
            }
        } else if (status.waiting()) {
            lines.add("Server: waiting for override dialog");
        } else if (cooldown.startsWith("Retry ")) {
            lines.add("Retry: " + cooldown.substring("Retry ".length()));
        } else if (cooldown.startsWith("Buffer ")) {
            lines.add("Buffer: " + cooldown.substring("Buffer ".length()));
        } else {
            lines.add("Cooldown: " + (cooldown.isEmpty()
                ? (status.cooldownKnown() ? "clear" : "none detected")
                : cooldown));
        }
        return lines;
    }

    private static String remaining(long millis) {
        return millis <= 0 ? "now" : ChatTeleportState.remaining(millis);
    }
}
