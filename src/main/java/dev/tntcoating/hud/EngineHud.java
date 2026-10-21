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
        "adaptive-elytra-fly-engine", "Adaptive Elytra Fly's state, output, boost, setbacks and the simulated server glide.",
        EngineHud::new);

    private static final String TITLE = "Adaptive Elytra Fly";
    private static final List<String> PREVIEW = List.of("State: Flying", "Speed: 41.0 b/s | 3D 41.2 | H 41.2 | V +0.0",
        "Engine max: 118.0 b/s", "Boost: H 0/300 | V 0/60 b/s", "Setbacks: 0 | server glide 30.0 b/s");

    private final MeteorTextStyle style = new MeteorTextStyle(settings);

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
