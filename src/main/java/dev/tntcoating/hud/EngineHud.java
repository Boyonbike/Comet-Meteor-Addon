package dev.tntcoating.hud;

import dev.tntcoating.modules.AdaptiveElytraFly;
import java.util.List;
import meteordevelopment.meteorclient.systems.hud.HudElement;
import meteordevelopment.meteorclient.systems.hud.HudElementInfo;
import meteordevelopment.meteorclient.systems.hud.HudRenderer;
import meteordevelopment.meteorclient.systems.modules.Modules;

/** Live overview of Adaptive Elytra Fly's engine; hidden while that engine is not running. */
public final class EngineHud extends HudElement {
    public static final HudElementInfo<EngineHud> ADAPTIVE_ELYTRA_FLY = new HudElementInfo<>(CometHud.GROUP,
        "adaptive-elytra-fly-engine", "Adaptive Elytra Fly's state, output, learned limits, correction recovery and the simulated server glide.",
        EngineHud::new);

    private static final String TITLE = "Adaptive Elytra Fly";
    private static final List<String> PREVIEW = List.of("State: Flying", "Speed: 41.0 b/s | H 41.2 | V +0.0",
        "Engine max: 118.0 b/s | recovery 100%", "Learned: H 1280.0 | up 15.0 | down 15.0 b/s", "Total movement max: 1280.0 b/s",
        "Corrections: 0 | server glide 30.0 b/s");

    private final PanelStyle style = new PanelStyle(settings);

    private EngineHud() {
        super(ADAPTIVE_ELYTRA_FLY);
    }

    @Override
    public void render(HudRenderer renderer) {
        AdaptiveElytraFly module = Modules.get().get(AdaptiveElytraFly.class);
        List<String> lines = module == null ? null : module.hudLines();
        if (lines == null && isInEditor()) lines = PREVIEW;
        if (lines != null) style.render(this, renderer, TITLE, lines);
    }
}
