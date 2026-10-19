package dev.tntcoating.hud;

import dev.tntcoating.core.SpeedSampler;
import java.util.List;
import java.util.Locale;
import meteordevelopment.meteorclient.settings.BoolSetting;
import meteordevelopment.meteorclient.settings.EnumSetting;
import meteordevelopment.meteorclient.settings.IntSetting;
import meteordevelopment.meteorclient.settings.Setting;
import meteordevelopment.meteorclient.settings.SettingGroup;
import meteordevelopment.meteorclient.systems.hud.HudElement;
import meteordevelopment.meteorclient.systems.hud.HudElementInfo;
import meteordevelopment.meteorclient.systems.hud.HudRenderer;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.LocalPlayer;

import static meteordevelopment.meteorclient.MeteorClient.mc;

/** Measured player speed from wall-clock time, so Timer cannot skew it and teleports never show as a spike. */
public final class PlayerSpeedHud extends HudElement {
    public static final HudElementInfo<PlayerSpeedHud> INFO = new HudElementInfo<>(CometHud.GROUP, "player-speed",
        "Your measured movement speed, including module-driven movement. Unaffected by Timer; teleports are ignored.",
        PlayerSpeedHud::new);

    public enum Units {
        BlocksPerSecond(1, "b/s"),
        KilometersPerHour(3.6, "km/h");

        private final double multiplier;
        private final String suffix;

        Units(double multiplier, String suffix) {
            this.multiplier = multiplier;
            this.suffix = suffix;
        }
    }

    private final SettingGroup measurement = settings.createGroup("Measurement");
    private final Setting<Boolean> includeVertical = measurement.add(new BoolSetting.Builder()
        .name("include-vertical").description("Include climbing and falling in the displayed speed.")
        .defaultValue(false).build());
    private final Setting<Units> units = measurement.add(new EnumSetting.Builder<Units>()
        .name("units").description("Units used by the speed display.")
        .defaultValue(Units.BlocksPerSecond).build());
    private final Setting<Integer> smoothing = measurement.add(new IntSetting.Builder()
        .name("smoothing").description("Number of ticks used to smooth the displayed speed. One is immediate.")
        .defaultValue(5).range(1, 40).sliderRange(1, 20).build());
    private final PanelStyle style = new PanelStyle(settings);

    private final SpeedSampler sampler = new SpeedSampler();
    private LocalPlayer player;
    private ClientLevel level;

    public PlayerSpeedHud() {
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
        sampler.sample(position.x, position.y, position.z, includeVertical.get(), System.nanoTime(), 1.0 / smoothing.get());
    }

    @Override
    public void render(HudRenderer renderer) {
        double value = sampler.speed() * units.get().multiplier;
        style.render(this, renderer, null, List.of(String.format(Locale.ROOT, "Speed: %.1f %s", value, units.get().suffix)));
    }
}
