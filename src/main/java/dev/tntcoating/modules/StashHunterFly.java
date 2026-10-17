package dev.tntcoating.modules;

import dev.tntcoating.TntCoatingAddon;
import dev.tntcoating.compat.BaritoneBridge;
import dev.tntcoating.compat.StashHunterDrive;
import dev.tntcoating.core.ElytraPilot;
import meteordevelopment.meteorclient.MeteorClient;
import meteordevelopment.meteorclient.events.game.GameLeftEvent;
import meteordevelopment.meteorclient.events.world.TickEvent;
import meteordevelopment.meteorclient.settings.BoolSetting;
import meteordevelopment.meteorclient.settings.DoubleSetting;
import meteordevelopment.meteorclient.settings.Setting;
import meteordevelopment.meteorclient.settings.SettingGroup;
import meteordevelopment.meteorclient.systems.modules.Module;
import meteordevelopment.meteorclient.systems.modules.Modules;
import meteordevelopment.meteorclient.systems.modules.movement.elytrafly.ElytraFlightModes;
import meteordevelopment.meteorclient.systems.modules.movement.elytrafly.ElytraFly;
import meteordevelopment.orbit.EventHandler;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.world.phys.Vec3;
import java.lang.reflect.Method;

public final class StashHunterFly extends Module {
    /** Stash-Hunter skips its movement step on the tick it moves on to the next waypoint. */
    private static final int IDLE_TICKS = 10;
    /** Ticks Stash-Hunter can fly with none of its movement reaching Comet before this Stash-Hunter version is reported as unsupported. */
    private static final int UNHOOKED_TICKS = 100;
    private final SettingGroup general = settings.getDefaultGroup();
    private final Setting<Double> turnSpeed = general.add(new DoubleSetting.Builder().name("turn-speed").description("Most degrees to turn per tick towards Stash-Hunter's next point.").defaultValue(10).min(1).sliderRange(1, 45).build());
    private final Setting<Boolean> takeOff = general.add(new BoolSetting.Builder().name("take-off").description("Jump and open the elytra from the ground when Stash-Hunter sets off. Vanilla and Packet modes only; Pitch40 needs you gliding high up already.").defaultValue(true).build());
    private final ElytraPilot pilot = new ElytraPilot("Stash-Hunter", message -> warning("%s", message));
    private BaritoneBridge baritone;
    private Method stashHunterActive;
    /** The point Stash-Hunter last asked to move towards, or null when it isn't moving the player. */
    private Vec3 target;
    private int idle;
    private boolean driving;
    /** Whether any of Stash-Hunter's movement has reached Comet since the module was turned on. */
    private boolean hooked;
    private boolean warnedUnhooked;
    private int unhooked;

    public StashHunterFly() { super(TntCoatingAddon.CATEGORY, "stash-hunter-elytrafly", "Stash-Hunter flies with Meteor's ElytraFly instead of Baritone's firework flight."); }

    @Override public String getInfoString() {
        if (!driving || mc.player == null) return null;
        if (!Modules.get().get(ElytraFly.class).isActive()) return "ElytraFly off";
        if (mc.player.onGround()) return "Taking off";
        return String.valueOf((int) Math.hypot(target.x - mc.player.getX(), target.z - mc.player.getZ()));
    }

    @Override public void onActivate() {
        target = null;
        idle = 0;
        driving = false;
        pilot.reset();
        hooked = false;
        warnedUnhooked = false;
        unhooked = 0;
        if (!FabricLoader.getInstance().isModLoaded("stash-hunter")) {
            error("Stash-Hunter isn't installed.");
            toggle();
            return;
        }
        baritone = BaritoneBridge.create();
        StashHunterDrive.enable(true);
    }

    @Override public void onDeactivate() {
        // Stash-Hunter goes back to moving the player itself on its next tick.
        StashHunterDrive.enable(false);
        stopDriving();
        baritone = null;
    }

    @EventHandler private void onGameLeft(GameLeftEvent event) {
        stopDriving();
    }

    // Stash-Hunter moves in TickEvent.Post, so this steers towards the point it asked for at the end of the last tick.
    @EventHandler private void onTick(TickEvent.Pre event) {
        if (mc.player == null || mc.level == null) return;
        Vec3 next = StashHunterDrive.poll();
        if (next != null) {
            target = next;
            idle = 0;
            hooked = true;
        } else if (target != null && ++idle >= IDLE_TICKS) {
            target = null;
        }
        if (target == null) {
            if (driving) stopDriving();
            checkHooked();
            return;
        }
        if (!driving) startDriving();
        pilot.steer(target, turnSpeed.get(), takeOff.get());
    }

    private void startDriving() {
        driving = true;
        StashHunterDrive.setDriving(true);
        pilot.begin();
        stopBaritone();
        ElytraFly elytraFly = Modules.get().get(ElytraFly.class);
        // Stash-Hunter turns ElytraFly on when it starts a flight; this covers turning Comet's module on mid-flight.
        if (!elytraFly.isActive()) elytraFly.toggle();
        ElytraFlightModes mode = elytraFly.flightMode.get();
        if (mode == ElytraFlightModes.Bounce) warning("ElytraFly's Bounce mode is for highways, but Stash-Hunter flies high up. Use Vanilla, Packet or Pitch40.");
        else info("Flying Stash-Hunter with ElytraFly (%s).", mode);
    }

    private void stopDriving() {
        pilot.release();
        driving = false;
        target = null;
        StashHunterDrive.setDriving(false);
    }

    /** A Baritone flight or walk Stash-Hunter started before this took over would fight ElytraFly. */
    private void stopBaritone() {
        if (baritone == null) return;
        try {
            if (baritone.flight() != null || baritone.walking() != null) {
                baritone.stop();
                info("Stopped Baritone so ElytraFly does the flying.");
            }
        } catch (LinkageError | RuntimeException e) {
            MeteorClient.LOG.error("Stopping Baritone for Stash-Hunter's ElytraFly flight failed", e);
        }
    }

    /** Stash-Hunter flying with none of its movement reaching Comet means a Stash-Hunter version these hooks don't fit. */
    private void checkHooked() {
        if (hooked || warnedUnhooked) return;
        if (!isStashHunterFlying()) {
            unhooked = 0;
            return;
        }
        if (++unhooked >= UNHOOKED_TICKS) {
            warnedUnhooked = true;
            warning("Stash-Hunter is flying, but its movement isn't reaching Comet, so it still uses its own flight. This Stash-Hunter version isn't supported.");
        }
    }

    /** Stash-Hunter's flight controller, including flights started without this integration module enabled. */
    public boolean isStashHunterFlying() {
        if (!FabricLoader.getInstance().isModLoaded("stash-hunter")) return false;
        try {
            if (stashHunterActive == null) stashHunterActive = Class.forName("com.stashhunter.stashhunter.utils.ElytraController").getMethod("isActive");
            return (boolean) stashHunterActive.invoke(null);
        } catch (ReflectiveOperationException | LinkageError | RuntimeException e) {
            return false;
        }
    }
}
