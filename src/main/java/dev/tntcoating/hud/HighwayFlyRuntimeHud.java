package dev.tntcoating.hud;

import dev.tntcoating.modules.HighwayFly;
import java.util.List;
import meteordevelopment.meteorclient.systems.hud.HudElement;
import meteordevelopment.meteorclient.systems.hud.HudElementInfo;
import meteordevelopment.meteorclient.systems.hud.HudRenderer;
import meteordevelopment.meteorclient.systems.modules.Modules;

/** How long the current Highway Fly trip has been running and how much of that was active flight. */
public final class HighwayFlyRuntimeHud extends HudElement {
    public static final HudElementInfo<HighwayFlyRuntimeHud> INFO = new HudElementInfo<>(CometHud.GROUP,
        "highway-fly-runtime", "Highway Fly elapsed time: active time and total trip time.",
        HighwayFlyRuntimeHud::new);

    private final MeteorTextStyle style = new MeteorTextStyle(settings);

    private HighwayFlyRuntimeHud() {
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
            style.render(this, renderer, "Highway Fly runtime", List.of(
                "Active: 1h 02m 03s",
                "Trip: 1h 20m 15s"));
            return;
        }

        if (!status.started()) {
            style.render(this, renderer, "Highway Fly runtime", List.of("Waiting for the trip to start"));
            return;
        }

        style.render(this, renderer, "Highway Fly runtime", List.of(
            "Active: " + HudTime.duration(status.activeNanos()),
            "Trip: " + HudTime.duration(status.tripNanos())));
    }
}
