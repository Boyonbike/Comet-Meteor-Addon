package dev.tntcoating.hud;

import dev.tntcoating.modules.ChatTools;
import java.util.List;
import meteordevelopment.meteorclient.systems.hud.HudElement;
import meteordevelopment.meteorclient.systems.hud.HudElementInfo;
import meteordevelopment.meteorclient.systems.hud.HudRenderer;
import meteordevelopment.meteorclient.systems.modules.Modules;

/** Uses the same timer/request state as the Chat Tools window, with Meteor's placement, colour and scale controls. */
public final class TeleportCountdownHud extends HudElement {
    public static final HudElementInfo<TeleportCountdownHud> INFO = new HudElementInfo<>(CometHud.GROUP,
        "tp-home-countdown", "Chat Tools home / TP countdowns, cooldowns and incoming request expiry.", TeleportCountdownHud::new);
    private final PanelStyle style = new PanelStyle(settings);
    private TeleportCountdownHud() { super(INFO); }
    @Override public void render(HudRenderer renderer) {
        ChatTools tools = Modules.get().get(ChatTools.class);
        List<String> lines = tools != null && tools.isActive() ? tools.countdowns() : List.of();
        if (lines.isEmpty() && isInEditor()) lines = List.of("Home base: cooldown ~25s", "TPA Alice: teleport 3s", "Request from Bob: 90s");
        if (!lines.isEmpty()) style.render(this, renderer, "Teleport countdowns", lines);
        else setSize(0, 0);
    }
}
