package dev.tntcoating.hud;

import dev.tntcoating.core.SpeedSampler;
import dev.tntcoating.hud.PlayerSpeedHud.Units;
import dev.tntcoating.modules.AdaptiveElytraFly;
import java.util.List;
import java.util.Locale;
import meteordevelopment.meteorclient.settings.EnumSetting;
import meteordevelopment.meteorclient.settings.IntSetting;
import meteordevelopment.meteorclient.settings.Setting;
import meteordevelopment.meteorclient.settings.SettingGroup;
import meteordevelopment.meteorclient.systems.hud.HudElement;
import meteordevelopment.meteorclient.systems.hud.HudElementInfo;
import meteordevelopment.meteorclient.systems.hud.HudRenderer;
import meteordevelopment.meteorclient.systems.modules.Modules;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.LocalPlayer;

import static meteordevelopment.meteorclient.MeteorClient.mc;

/** Measured total 3D speed, climbing and diving included, and whether Adaptive Elytra Fly's maneuver cap holds it. */
public final class Speed3dHud extends HudElement {
    public static final HudElementInfo<Speed3dHud> INFO = new HudElementInfo<>(CometHud.GROUP, "speed-3d",
        "Your measured 3D speed, including vertical movement, and when Adaptive Elytra Fly's maneuver mode caps it.",
        Speed3dHud::new);

    private final SettingGroup measurement = settings.createGroup("Measurement");
    private final Setting<Units> units = measurement.add(new EnumSetting.Builder<Units>()
        .name("units").description("Units used by the speed display.")
        .defaultValue(Units.BlocksPerSecond).build());
    private final Setting<Integer> smoothing = measurement.add(new IntSetting.Builder()
        .name("smoothing").description("Number of ticks used to smooth the displayed speed. One is immediate.")
        .defaultValue(5).range(1, 40).sliderRange(1, 20).build());
    private final MeteorTextStyle style = new MeteorTextStyle(settings);

    private final SpeedSampler sampler = new SpeedSampler();
    private LocalPlayer player;
    private ClientLevel level;

    public Speed3dHud() {
        super(INFO);
    }

    @Override
    public void tick(HudRenderer renderer) {
        if (mc.player == null || mc.level == null) {
            player = null;
            sampler.reset();
            return;
        }
        if (mc.player != player || mc.level != level) {
            player = mc.player;
            level = mc.level;
            sampler.reset();
        }
        var position = mc.player.position();
        sampler.sample(position.x, position.y, position.z, true, System.nanoTime(), 1.0 / smoothing.get());
    }

    @Override
    public void render(HudRenderer renderer) {
        Units unit = units.get();
        String speed = String.format(Locale.ROOT, "3D speed: %.1f %s", sampler.speed() * unit.multiplier, unit.suffix);
        AdaptiveElytraFly fly = Modules.get().get(AdaptiveElytraFly.class);
        List<String> lines = fly != null && fly.maneuverCapped() ? List.of(speed, String.format(Locale.ROOT,
            "Maneuvering ≤ %.0f %s", fly.maneuverMaxBps() * unit.multiplier, unit.suffix)) : List.of(speed);
        style.render(this, renderer, null, lines);
    }
}
