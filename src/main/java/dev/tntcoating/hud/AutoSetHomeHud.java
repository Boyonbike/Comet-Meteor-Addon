package dev.tntcoating.hud;

import dev.tntcoating.core.AutoSetHomeHudText;
import dev.tntcoating.modules.HighwayFly;
import dev.tntcoating.modules.AutoSetHome;
import java.util.List;
import meteordevelopment.meteorclient.systems.hud.HudElement;
import meteordevelopment.meteorclient.systems.hud.HudElementInfo;
import meteordevelopment.meteorclient.systems.hud.HudRenderer;
import meteordevelopment.meteorclient.systems.modules.Modules;

/** The next auto-set-home attempt, its timing mode and the server cooldown the helper is tracking. */
public final class AutoSetHomeHud extends HudElement {
    public static final HudElementInfo<AutoSetHomeHud> INFO = new HudElementInfo<>(CometHud.GROUP,
        "auto-set-home-timer", "Auto Set Home: timing mode, next attempt and server cooldown.",
        AutoSetHomeHud::new);

    private final MeteorTextStyle style = new MeteorTextStyle(settings);

    private AutoSetHomeHud() {
        super(INFO);
    }

    @Override
    public void render(HudRenderer renderer) {
        AutoSetHome fly = Modules.get().get(AutoSetHome.class);
        HighwayFly.AutoSetHomeStatus status = fly == null ? null : fly.status();
        List<String> lines = status == null ? editorLines() : lines(status);
        if (lines.isEmpty()) setSize(0, 0);
        else style.render(this, renderer, "Auto set home", lines);
    }

    private List<String> editorLines() {
        if (!isInEditor()) return List.of();
        return List.of("Mode: Auto detect", "Home: test", "Cooldown: 4m 54s", "Buffer: 10s (after cooldown)", "Next: 5m 4s");
    }

    private static List<String> lines(HighwayFly.AutoSetHomeStatus status) {
        return AutoSetHomeHudText.lines(status.mode(), status.home(), status.fixedTimer(), status.paused(), status.schedule());
    }
}
