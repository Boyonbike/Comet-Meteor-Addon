package dev.tntcoating.hud;

import dev.tntcoating.modules.HighwayFly;
import java.util.ArrayList;
import java.util.List;
import meteordevelopment.meteorclient.systems.hud.HudElement;
import meteordevelopment.meteorclient.systems.hud.HudElementInfo;
import meteordevelopment.meteorclient.systems.hud.HudRenderer;
import meteordevelopment.meteorclient.systems.modules.Modules;

/** The current Highway Fly phase and what it is doing right now. */
public final class HighwayFlyStatusHud extends HudElement {
    public static final HudElementInfo<HighwayFlyStatusHud> INFO = new HudElementInfo<>(CometHud.GROUP,
        "highway-fly-status", "Highway Fly state and action: flying, routing, maneuvering, walking or lobby recovery.",
        HighwayFlyStatusHud::new);

    private final MeteorTextStyle style = new MeteorTextStyle(settings);

    private HighwayFlyStatusHud() {
        super(INFO);
    }

    @Override
    public void render(HudRenderer renderer) {
        HighwayFly fly = Modules.get().get(HighwayFly.class);
        HighwayFly.TripStatus status = fly == null ? null : fly.tripStatus();
        if (status == null) {
            if (!isInEditor()) {
                setSize(0, 0);
                return;
            }
            style.render(this, renderer, "Highway Fly", List.of(
                "State: Flying",
                "Action: Maneuvering around terrain",
                "245m left · target 1000, -200"));
            return;
        }

        List<String> lines = new ArrayList<>(3);
        lines.add("State: " + status.state());
        lines.add("Action: " + status.action());
        if (!status.detail().isBlank()) lines.add(status.detail());
        style.render(this, renderer, "Highway Fly", lines);
    }
}
