package dev.tntcoating.modules;

import dev.tntcoating.TntCoatingAddon;
import java.util.Locale;
import meteordevelopment.meteorclient.events.render.Render2DEvent;
import meteordevelopment.meteorclient.events.world.TickEvent;
import meteordevelopment.meteorclient.settings.BoolSetting;
import meteordevelopment.meteorclient.settings.EnumSetting;
import meteordevelopment.meteorclient.settings.IntSetting;
import meteordevelopment.meteorclient.settings.Setting;
import meteordevelopment.meteorclient.settings.SettingGroup;
import meteordevelopment.meteorclient.systems.modules.Module;
import meteordevelopment.orbit.EventHandler;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.world.phys.Vec3;

/** Toggleable, compact player-speed readout which works for ordinary and module-driven movement. */
public final class PlayerSpeedHud extends Module {
    private static final int PADDING = 4;
    private static final int BACKGROUND = 0xb0000000;
    private static final int ACCENT = 0xff55aaff;
    private static final int TEXT = 0xffeeeeee;

    private final SettingGroup general = settings.getDefaultGroup();
    private final Setting<Position> position = general.add(new EnumSetting.Builder<Position>()
        .name("position").description("Screen corner or edge used for the speed display.")
        .defaultValue(Position.TopCenter).build());
    private final Setting<Integer> horizontalOffset = general.add(new IntSetting.Builder()
        .name("horizontal-offset").description("Distance from the selected screen edge or center.")
        .defaultValue(0).range(-1000, 1000).sliderRange(-200, 200).build());
    private final Setting<Integer> verticalOffset = general.add(new IntSetting.Builder()
        .name("vertical-offset").description("Distance from the selected screen edge.")
        .defaultValue(6).range(0, 1000).sliderRange(0, 200).build());
    private final Setting<Boolean> includeVertical = general.add(new BoolSetting.Builder()
        .name("include-vertical").description("Include climbing and falling in the displayed speed.")
        .defaultValue(false).build());
    private final Setting<Units> units = general.add(new EnumSetting.Builder<Units>()
        .name("units").description("Units used by the speed display.")
        .defaultValue(Units.BlocksPerSecond).build());
    private final Setting<Integer> smoothing = general.add(new IntSetting.Builder()
        .name("smoothing").description("Number of ticks used to smooth the displayed speed. One is immediate.")
        .defaultValue(5).range(1, 40).sliderRange(1, 20).build());

    private ClientLevel level;
    private LocalPlayer player;
    private Vec3 previousPosition;
    private double displayedSpeed;

    public PlayerSpeedHud() {
        super(TntCoatingAddon.CATEGORY, "player-speed-hud",
            "Shows your measured movement speed on the HUD.");
    }

    @Override
    public void onActivate() {
        reset();
    }

    @Override
    public void onDeactivate() {
        reset();
    }

    @EventHandler
    private void onTick(TickEvent.Post event) {
        if (mc.player == null || mc.level == null) {
            reset();
            return;
        }
        if (mc.player != player || mc.level != level || previousPosition == null) {
            player = mc.player;
            level = mc.level;
            previousPosition = player.position();
            displayedSpeed = 0;
            return;
        }

        Vec3 current = player.position();
        double dx = current.x - previousPosition.x;
        double dy = current.y - previousPosition.y;
        double dz = current.z - previousPosition.z;
        previousPosition = current;
        double measured = (includeVertical.get() ? Math.sqrt(dx * dx + dy * dy + dz * dz) : Math.hypot(dx, dz)) * 20;
        displayedSpeed += (measured - displayedSpeed) / smoothing.get();
    }

    @EventHandler
    private void onRender(Render2DEvent event) {
        if (mc.player == null || mc.level == null || mc.screen != null || mc.options.hideGui) return;
        double value = displayedSpeed * units.get().multiplier;
        String label = String.format(Locale.ROOT, "Speed: %.1f %s", value, units.get().suffix);
        int width = mc.font.width(label) + PADDING * 2 + 2;
        int height = mc.font.lineHeight + PADDING * 2;
        int x = switch (position.get()) {
            case TopLeft, BottomLeft -> 6 + horizontalOffset.get();
            case TopCenter, BottomCenter -> (event.screenWidth - width) / 2 + horizontalOffset.get();
            case TopRight, BottomRight -> event.screenWidth - width - 6 - horizontalOffset.get();
        };
        int y = switch (position.get()) {
            case TopLeft, TopCenter, TopRight -> verticalOffset.get();
            case BottomLeft, BottomCenter, BottomRight -> event.screenHeight - height - verticalOffset.get();
        };
        x = Math.max(0, Math.min(event.screenWidth - width, x));
        y = Math.max(0, Math.min(event.screenHeight - height, y));
        event.graphics.fill(x, y, x + width, y + height, BACKGROUND);
        event.graphics.fill(x, y, x + 2, y + height, ACCENT);
        event.graphics.text(mc.font, label, x + PADDING + 1, y + PADDING, TEXT, true);
    }

    private void reset() {
        level = null;
        player = null;
        previousPosition = null;
        displayedSpeed = 0;
    }

    public enum Position {
        TopLeft, TopCenter, TopRight, BottomLeft, BottomCenter, BottomRight
    }

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
}
