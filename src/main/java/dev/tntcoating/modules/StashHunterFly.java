package dev.tntcoating.modules;

import dev.tntcoating.TntCoatingAddon;
import dev.tntcoating.compat.BaritoneBridge;
import dev.tntcoating.compat.StashHunterDrive;
import dev.tntcoating.core.ElytraSteer;
import dev.tntcoating.core.ElytraTakeoff;
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
import meteordevelopment.meteorclient.utils.misc.input.Input;
import meteordevelopment.orbit.EventHandler;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.KeyMapping;
import net.minecraft.network.protocol.game.ServerboundPlayerCommandPacket;
import net.minecraft.world.phys.Vec3;
import java.lang.reflect.Method;

public final class StashHunterFly extends Module {
    /** Stash-Hunter skips its movement step on the tick it moves on to the next waypoint. */
    private static final int IDLE_TICKS = 10;
    /** Blocks above or below Stash-Hunter's flight height before Vanilla mode climbs or descends. */
    private static final double BAND = 2;
    /** Closer than this horizontally only the height is adjusted, as when Stash-Hunter climbs straight up before setting off. */
    private static final double CLOSE = 4;
    /** Blocks of open air needed above the player's head to jump and open the elytra. */
    private static final double HEADROOM = 3;
    /** Ticks Stash-Hunter can fly with none of its movement reaching Comet before this Stash-Hunter version is reported as unsupported. */
    private static final int UNHOOKED_TICKS = 100;
    private static final int FORWARD = 1, JUMP = 2, SNEAK = 4;
    private final SettingGroup general = settings.getDefaultGroup();
    private final Setting<Double> turnSpeed = general.add(new DoubleSetting.Builder().name("turn-speed").description("Most degrees to turn per tick towards Stash-Hunter's next point.").defaultValue(10).min(1).sliderRange(1, 45).build());
    private final Setting<Boolean> takeOff = general.add(new BoolSetting.Builder().name("take-off").description("Jump and open the elytra from the ground when Stash-Hunter sets off. Vanilla and Packet modes only; Pitch40 needs you gliding high up already.").defaultValue(true).build());
    private final ElytraTakeoff takeoff = new ElytraTakeoff();
    private BaritoneBridge baritone;
    private Method stashHunterActive;
    /** The point Stash-Hunter last asked to move towards, or null when it isn't moving the player. */
    private Vec3 target;
    private int idle;
    private boolean driving;
    /** Keys this module is holding down, as FORWARD, JUMP and SNEAK bits. */
    private int held;
    private boolean gaveUp, warnedCovered, warnedPitch40;
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
        held = 0;
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
        steer();
    }

    private void startDriving() {
        driving = true;
        StashHunterDrive.setDriving(true);
        takeoff.reset(mc.player.isFallFlying());
        gaveUp = false;
        warnedCovered = false;
        warnedPitch40 = false;
        stopBaritone();
        ElytraFly elytraFly = Modules.get().get(ElytraFly.class);
        // Stash-Hunter turns ElytraFly on when it starts a flight; this covers turning Comet's module on mid-flight.
        if (!elytraFly.isActive()) elytraFly.toggle();
        ElytraFlightModes mode = elytraFly.flightMode.get();
        if (mode == ElytraFlightModes.Bounce) warning("ElytraFly's Bounce mode is for highways, but Stash-Hunter flies high up. Use Vanilla, Packet or Pitch40.");
        else info("Flying Stash-Hunter with ElytraFly (%s).", mode);
    }

    private void stopDriving() {
        if (mc.options != null) releaseKeys();
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

    private void steer() {
        ElytraFly elytraFly = Modules.get().get(ElytraFly.class);
        ElytraFlightModes mode = elytraFly.flightMode.get();
        double dx = target.x - mc.player.getX(), dz = target.z - mc.player.getZ(), dy = target.y - mc.player.getY();
        boolean far = Math.hypot(dx, dz) > CLOSE;
        // Every ElytraFly mode flies where the player faces.
        if (far) mc.player.setYRot(ElytraSteer.turn(mc.player.getYRot(), ElytraSteer.yawTo(dx, dz), turnSpeed.get().floatValue()));
        // Vanilla moves only while gliding. Packet moves whenever the player is falling, gliding or not.
        boolean flying = mode == ElytraFlightModes.Packet ? !mc.player.onGround() : mc.player.isFallFlying();
        boolean keys = mode == ElytraFlightModes.Vanilla || mode == ElytraFlightModes.Packet;
        if (!elytraFly.isActive() || !flying || !keys) {
            releaseKeys();
            if (elytraFly.isActive() && !mc.player.isFallFlying()) takeOff(mode);
            return;
        }
        hold(mc.options.keyUp, FORWARD, far);
        if (mode == ElytraFlightModes.Packet) {
            // Packet sinks whenever jump is up, and ignores every key while sneaking.
            hold(mc.options.keyJump, JUMP, dy > 0);
        } else {
            ElytraSteer.Climb climb = ElytraSteer.climb(dy, BAND);
            hold(mc.options.keyJump, JUMP, climb == ElytraSteer.Climb.UP);
            hold(mc.options.keyShift, SNEAK, climb == ElytraSteer.Climb.DOWN);
        }
    }

    private void takeOff(ElytraFlightModes mode) {
        if (mode == ElytraFlightModes.Pitch40) {
            if (!warnedPitch40) {
                warnedPitch40 = true;
                warning("Pitch40 can't take off. Start gliding above ElytraFly's pitch40-upper-bounds, or use Vanilla.");
            }
            return;
        }
        if (mode == ElytraFlightModes.Bounce || !takeOff.get() || gaveUp) return;
        boolean fluid = mc.player.isInWater() || mc.player.isInLava();
        ElytraTakeoff.Step step = fluid ? ElytraTakeoff.Step.COVERED
            : takeoff.next(mc.player.onGround(), false, mc.player.getDeltaMovement().y, openAbove());
        switch (step) {
            case JUMP -> mc.player.jumpFromGround();
            case OPEN -> {
                // What the client does for a second press of the jump key in the air.
                if (mc.player.tryToStartFallFlying()) mc.getConnection().send(new ServerboundPlayerCommandPacket(mc.player, ServerboundPlayerCommandPacket.Action.START_FALL_FLYING));
            }
            case COVERED -> {
                if (!warnedCovered) {
                    warnedCovered = true;
                    warning("Something is above you, or you're in water or lava. Stash-Hunter takes off once there's open sky.");
                }
            }
            case GAVE_UP -> {
                gaveUp = true;
                warning("Couldn't take off after %d jumps; take off by hand.", ElytraTakeoff.MAX_JUMPS);
            }
            case WAIT -> {}
        }
    }

    private boolean openAbove() {
        return mc.level.noCollision(mc.player, mc.player.getBoundingBox().expandTowards(0, HEADROOM, 0));
    }

    /** Holds the key down every tick it is wanted (ElytraFly may let go of forward itself), and gives a released key back to the keyboard. */
    private void hold(KeyMapping key, int bit, boolean down) {
        if (down) {
            key.setDown(true);
            held |= bit;
        } else if ((held & bit) != 0) {
            key.setDown(Input.isPressed(key));
            held &= ~bit;
        }
    }

    private void releaseKeys() {
        hold(mc.options.keyUp, FORWARD, false);
        hold(mc.options.keyJump, JUMP, false);
        hold(mc.options.keyShift, SNEAK, false);
    }

    /** Stash-Hunter flying with none of its movement reaching Comet means a Stash-Hunter version these hooks don't fit. */
    private void checkHooked() {
        if (hooked || warnedUnhooked) return;
        if (!stashHunterFlying()) {
            unhooked = 0;
            return;
        }
        if (++unhooked >= UNHOOKED_TICKS) {
            warnedUnhooked = true;
            warning("Stash-Hunter is flying, but its movement isn't reaching Comet, so it still uses its own flight. This Stash-Hunter version isn't supported.");
        }
    }

    private boolean stashHunterFlying() {
        try {
            if (stashHunterActive == null) stashHunterActive = Class.forName("com.stashhunter.stashhunter.utils.ElytraController").getMethod("isActive");
            return (boolean) stashHunterActive.invoke(null);
        } catch (ReflectiveOperationException | LinkageError | RuntimeException e) {
            return false;
        }
    }
}
