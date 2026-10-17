package dev.tntcoating.modules;

import dev.tntcoating.TntCoatingAddon;
import dev.tntcoating.compat.BaritoneBridge;
import dev.tntcoating.core.AreaSpiralPath;
import dev.tntcoating.core.ElytraPilot;
import meteordevelopment.meteorclient.MeteorClient;
import meteordevelopment.meteorclient.events.game.GameLeftEvent;
import meteordevelopment.meteorclient.events.world.TickEvent;
import meteordevelopment.meteorclient.settings.BoolSetting;
import meteordevelopment.meteorclient.settings.DoubleSetting;
import meteordevelopment.meteorclient.settings.IntSetting;
import meteordevelopment.meteorclient.settings.KeybindSetting;
import meteordevelopment.meteorclient.settings.Setting;
import meteordevelopment.meteorclient.settings.SettingGroup;
import meteordevelopment.meteorclient.systems.modules.Module;
import meteordevelopment.meteorclient.systems.modules.Modules;
import meteordevelopment.meteorclient.systems.modules.movement.elytrafly.ElytraFlightModes;
import meteordevelopment.meteorclient.systems.modules.movement.elytrafly.ElytraFly;
import meteordevelopment.meteorclient.utils.misc.Keybind;
import meteordevelopment.orbit.EventHandler;
import meteordevelopment.orbit.EventPriority;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.core.component.DataComponents;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.phys.Vec3;

/** A centre-out area survey, independent of chunk classification and stash detection. */
public final class AreaSpiralSearch extends Module {
    private static final int TERRAIN_CLEARANCE = 24;
    private static final long STUCK_MILLIS = 30_000;

    private final SettingGroup general = settings.getDefaultGroup();
    private final Setting<Boolean> paused = general.add(new BoolSetting.Builder()
        .name("paused").description("Pause spiral steering and take-off without losing the route. ElytraFly stays on; control your flight manually.")
        .defaultValue(false).onChanged(this::onPauseChanged).build());
    private final Setting<Keybind> pauseKey = general.add(new KeybindSetting.Builder()
        .name("pause-key").description("Pause or resume this search while in-game. Unbound by default.")
        .defaultValue(Keybind.none()).action(this::togglePaused).build());
    private final Setting<Boolean> customCenter = general.add(new BoolSetting.Builder()
        .name("custom-center").description("Use center-x/z instead of your position when enabled. Route settings apply on the next activation.")
        .defaultValue(false).build());
    private final Setting<Integer> centerX = general.add(new IntSetting.Builder()
        .name("center-x").description("Block X of the spiral centre. Fly to this centre before spiralling.")
        .defaultValue(0).range(-29_999_984, 29_999_984).sliderRange(-10_000, 10_000).visible(customCenter::get).build());
    private final Setting<Integer> centerZ = general.add(new IntSetting.Builder()
        .name("center-z").description("Block Z of the spiral centre.")
        .defaultValue(0).range(-29_999_984, 29_999_984).sliderRange(-10_000, 10_000).visible(customCenter::get).build());
    private final Setting<Double> spacing = general.add(new DoubleSetting.Builder()
        .name("spiral-spacing").description("Blocks between successive turns. Use tighter spacing for shorter render distances, and slower flight for tighter turns.")
        .defaultValue(256).range(128, 16_384).sliderRange(128, 1024).build());
    private final Setting<Double> radius = general.add(new DoubleSetting.Builder()
        .name("search-radius").description("Stop steering when the spiral reaches this distance from the centre. Does not land or detect stashes.")
        .defaultValue(5000).range(64, 1_000_000).sliderRange(256, 20_000).build());
    private final Setting<Boolean> clockwise = general.add(new BoolSetting.Builder()
        .name("clockwise").description("Spiral clockwise on a north-up map. The first outward leg follows your facing at activation.")
        .defaultValue(true).build());

    private final SettingGroup flight = settings.createGroup("Flight");
    private final Setting<Double> height = flight.add(new DoubleSetting.Builder()
        .name("flight-height").description("Target Y for Vanilla and Packet; climb higher over loaded terrain. Pitch40 uses its own height bounds.")
        .defaultValue(200).range(-50, 1000).sliderRange(64, 320).build());
    private final Setting<Double> turnSpeed = flight.add(new DoubleSetting.Builder()
        .name("turn-speed").description("Maximum degrees to turn per tick towards the spiral.")
        .defaultValue(10).range(1, 45).sliderRange(1, 45).build());
    private final Setting<Boolean> takeOff = flight.add(new BoolSetting.Builder()
        .name("take-off").description("Jump and open the elytra in Vanilla or Packet. Pitch40 needs an existing glide.")
        .defaultValue(true).build());

    private final ElytraPilot pilot = new ElytraPilot("Area Spiral Search", message -> warning("%s", message));
    private AreaSpiralPath path;
    private ClientLevel level;
    private BaritoneBridge baritone;
    private ElytraFlightModes mode;
    private boolean savedSettings, previousAutoPilot, previousAutoTakeOff;
    private double progressDistance, closestApproachDistance, radiusAtStart, spacingAtStart;
    private long progressAt;

    public AreaSpiralSearch() {
        super(TntCoatingAddon.CATEGORY, "area-spiral-search", "Flies an expanding spiral from a centre point using Meteor's ElytraFly.");
    }

    @Override public String getInfoString() {
        return path == null ? null : (paused.get() ? "Paused: " : "")
            + Math.round(path.radius()) + " / " + Math.round(radiusAtStart) + " blocks";
    }

    @Override public void onActivate() {
        path = null;
        paused.set(false);
        level = null;
        baritone = null;
        savedSettings = false;
        pilot.begin();
        if (mc.player == null || mc.level == null) { stop("Join a world before starting Area Spiral Search."); return; }
        if (mc.level.dimension().equals(Level.NETHER)) {
            stop("Area Spiral Search needs open air in the Overworld or End; it cannot route through the Nether."); return;
        }
        if (!hasElytra()) { stop("Put on a usable elytra before starting Area Spiral Search."); return; }
        level = mc.level;
        ElytraFly fly = elytraFly();
        mode = fly.flightMode.get();
        if (mode == ElytraFlightModes.Bounce) { stop("Choose ElytraFly Vanilla, Packet or Pitch40 before starting Area Spiral Search."); return; }
        if (mode == ElytraFlightModes.Pitch40 && (!mc.player.isFallFlying()
            || !fly.isActive() && mc.player.getY() < Math.max(fly.pitch40upperBounds.get(), fly.pitch40lowerBounds.get() + 40))) {
            stop("Start gliding above ElytraFly's Pitch40 height bounds first, or choose Vanilla or Packet."); return;
        }
        double x = customCenter.get() ? centerX.get() + 0.5 : mc.player.getX();
        double z = customCenter.get() ? centerZ.get() + 0.5 : mc.player.getZ();
        radiusAtStart = radius.get();
        spacingAtStart = spacing.get();
        if (!insideBorder(x - radiusAtStart, z - radiusAtStart) || !insideBorder(x + radiusAtStart, z + radiusAtStart)) {
            stop("The search area crosses the world border. Choose a smaller search-radius or a different centre."); return;
        }
        try {
            baritone = BaritoneBridge.create();
            if (!ownsFlight(false)) return;
            path = new AreaSpiralPath(x, z, mc.player.getYRot(), clockwise.get(), spacingAtStart, radiusAtStart);
            previousAutoPilot = fly.autoPilot.get();
            previousAutoTakeOff = fly.autoTakeOff.get();
            savedSettings = true;
            fly.autoPilot.set(false);
            fly.autoTakeOff.set(false);
            if (!fly.isActive()) fly.toggle();
            if (!fly.isActive()) { stop("ElytraFly could not start. Check its mode and take-off height."); return; }
            // Activating ElytraFly can run mode setting callbacks, so claim these after activation too.
            fly.autoPilot.set(false);
            fly.autoTakeOff.set(false);
            progressDistance = 0;
            closestApproachDistance = Math.hypot(mc.player.getX() - x, mc.player.getZ() - z);
            progressAt = millis();
            info("Spiralling from %.1f, %.1f to %.0f blocks, %.0f blocks apart (%s, %s).", x, z, radiusAtStart,
                spacingAtStart, clockwise.get() ? "clockwise" : "counter-clockwise", mode);
        } catch (LinkageError | RuntimeException e) { integrationFailed(e); }
    }

    @Override public void onDeactivate() {
        pilot.release();
        // Leave ElytraFly on: disabling it can trigger its configured insta-drop or chest swap.
        if (savedSettings) {
            ElytraFly fly = elytraFly();
            if (!fly.autoPilot.get()) fly.autoPilot.set(previousAutoPilot);
            if (!fly.autoTakeOff.get()) fly.autoTakeOff.set(previousAutoTakeOff);
            savedSettings = false;
        }
        path = null;
        paused.set(false);
        level = null;
        baritone = null;
    }

    private void togglePaused() {
        if (isActive() && path != null && mc.gui.screen() == null) paused.set(!paused.get());
    }

    private void onPauseChanged(boolean pause) {
        if (!isActive() || path == null) return;
        pilot.release();
        // Manual flight must not become a swept route segment on the first resumed tick.
        path.resetObservation();
        progressDistance = path.distanceTravelled();
        AreaSpiralPath.Point approach = path.rejoinTarget();
        closestApproachDistance = mc.player == null || approach == null ? Double.POSITIVE_INFINITY
            : Math.hypot(mc.player.getX() - approach.x(), mc.player.getZ() - approach.z());
        progressAt = millis();
        if (pause) info("Spiral paused. Route saved; ElytraFly remains on for manual flight. Turn paused off or use pause-key to resume.");
        else {
            pilot.begin();
            info("Spiral resumed from the saved route progress.");
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST) private void onGameLeft(GameLeftEvent event) {
        if (isActive()) toggle();
    }

    @EventHandler private void onTick(TickEvent.Pre event) {
        if (mc.player == null || mc.level == null) { pilot.release(); return; }
        if (mc.level != level) { stop("World or dimension changed. Start Area Spiral Search again to search here."); return; }
        if (!mc.player.isAlive()) { stop("Area Spiral Search stopped because the player died."); return; }
        if (!hasElytra()) { stop("A usable elytra is no longer equipped. Area Spiral Search stopped."); return; }
        ElytraFly fly = elytraFly();
        if (!fly.isActive()) { stop("ElytraFly was turned off. Area Spiral Search stopped."); return; }
        if (fly.flightMode.get() != mode || fly.autoPilot.get() || fly.autoTakeOff.get()) {
            stop("ElytraFly's mode or automatic controls changed. Area Spiral Search released control."); return;
        }
        try {
            if (!ownsFlight(true) || path == null) return;
            if (paused.get()) { pilot.release(); return; }
            double lookAhead = Math.max(12, Math.min(Math.min(48, spacingAtStart / 8), mc.player.getDeltaMovement().horizontalDistance() * 6));
            AreaSpiralPath.Point target = path.target(mc.player.getX(), mc.player.getZ(), lookAhead);
            if (path.finished()) {
                info("Spiral complete at %.0f blocks from the centre. Steering stopped; ElytraFly remains on with its previous controls.", radiusAtStart);
                toggle();
                return;
            }
            if (!insideBorder(target.x(), target.z())) { stop("The next spiral point is outside the world border. Steering stopped."); return; }
            double routeDistance = path.distanceTravelled();
            AreaSpiralPath.Point approach = path.rejoinTarget();
            double approachDistance = approach == null ? Double.POSITIVE_INFINITY
                : Math.hypot(mc.player.getX() - approach.x(), mc.player.getZ() - approach.z());
            // Physical displacement alone would let a too-fast flight circle a missed turn forever.
            // Getting back to a paused route counts as progress without advancing the survey itself.
            if (routeDistance - progressDistance >= 8 || closestApproachDistance - approachDistance >= 8) {
                progressDistance = routeDistance;
                closestApproachDistance = Math.min(closestApproachDistance, approachDistance);
                progressAt = millis();
            } else if (millis() - progressAt >= STUCK_MILLIS) {
                stop("No route progress for 30 seconds. Steering stopped; lower ElytraFly speed, increase spacing/turn-speed, or check take-off and chunk loading."); return;
            }
            pilot.steer(new Vec3(target.x(), targetHeight(target), target.z()), turnSpeed.get(), takeOff.get());
        } catch (LinkageError | RuntimeException e) { integrationFailed(e); }
    }

    /** Yield to other navigators instead of cancelling their routes or fighting their steering. */
    private boolean ownsFlight(boolean running) {
        TrailHunter hunter = Modules.get().get(TrailHunter.class);
        StashHunterFly stash = Modules.get().get(StashHunterFly.class);
        if (hunter != null && hunter.isActive() || stash != null && stash.isStashHunterFlying()) {
            stop(running ? "Another flight controller started. Area Spiral Search released control."
                : "Stop Trail Hunter and Stash-Hunter's flight before starting Area Spiral Search.");
            return false;
        }
        if (baritone != null && (baritone.flightActive() || baritone.walking() != null)) {
            if (running && elytraFly().isActive()) elytraFly().toggle();
            stop(running ? "A Baritone trip started. Area Spiral Search and ElytraFly stopped."
                : "Stop the current Baritone trip before starting Area Spiral Search.");
            return false;
        }
        return true;
    }

    private boolean hasElytra() {
        var chest = mc.player.getItemBySlot(EquipmentSlot.CHEST);
        return chest.has(DataComponents.GLIDER) && !chest.nextDamageWillBreak();
    }

    private boolean insideBorder(double x, double z) {
        return level.getWorldBorder().isWithinBounds(BlockPos.containing(x, mc.player.getY(), z));
    }

    /** This is height clearance over loaded surfaces, not a terrain pathfinder. */
    private double targetHeight(AreaSpiralPath.Point target) {
        double dx = target.x() - mc.player.getX(), dz = target.z() - mc.player.getZ();
        double distance = Math.hypot(dx, dz);
        double y = height.get();
        for (int ahead = 0; ahead <= 128; ahead += 8) {
            int x = (int) Math.floor(mc.player.getX() + (distance > 0 ? dx / distance * ahead : 0));
            int z = (int) Math.floor(mc.player.getZ() + (distance > 0 ? dz / distance * ahead : 0));
            if (level.hasChunk(x >> 4, z >> 4)) y = Math.max(y, level.getHeight(Heightmap.Types.MOTION_BLOCKING, x, z) + TERRAIN_CLEARANCE);
        }
        return y;
    }

    private ElytraFly elytraFly() { return Modules.get().get(ElytraFly.class); }
    private static long millis() { return System.nanoTime() / 1_000_000; }
    private void stop(String reason) { warning("%s", reason); if (isActive()) toggle(); }
    private void integrationFailed(Throwable e) {
        MeteorClient.LOG.error("Area Spiral Search flight integration failed", e);
        stop("Area Spiral Search could not use this flight integration (" + e.getClass().getSimpleName() + "). See the log.");
    }
}
