package dev.tntcoating.core;

import java.util.function.Consumer;
import meteordevelopment.meteorclient.systems.modules.Modules;
import meteordevelopment.meteorclient.systems.modules.movement.elytrafly.ElytraFlightModes;
import meteordevelopment.meteorclient.systems.modules.movement.elytrafly.ElytraFly;
import meteordevelopment.meteorclient.utils.misc.input.Input;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.network.protocol.game.ServerboundPlayerCommandPacket;
import net.minecraft.world.phys.Vec3;

/** Shared steering and take-off for modules that fly towards points using Meteor's ElytraFly. */
public final class ElytraPilot {
    /** Blocks above or below the target height before Vanilla mode climbs or descends. */
    private static final double BAND = 2;
    /** Closer than this horizontally only the height is adjusted. */
    private static final double CLOSE = 4;
    /** Blocks of open air needed above the player's head to jump and open the elytra. */
    private static final double HEADROOM = 3;
    private static final int FORWARD = 1, JUMP = 2, SNEAK = 4;
    // Client-thread ownership, paired with each pilot's temporary landing settings.
    private static int landingPilots;

    /** Adaptive movement yields while a Comet pilot is using Meteor's controlled landing sequence. */
    public static boolean landingInProgress() { return landingPilots > 0; }

    private final Minecraft mc = Minecraft.getInstance();
    private final ElytraTakeoff takeoff = new ElytraTakeoff();
    private final String flightName;
    private final Consumer<String> warning;
    /** Keys this pilot is holding down, as FORWARD, JUMP and SNEAK bits. */
    private int held;
    private int landingOpenCooldown;
    private boolean gaveUp, warnedCovered, warnedPitch40;
    private LandingSettings landing;

    public ElytraPilot(String flightName, Consumer<String> warning) {
        this.flightName = flightName;
        this.warning = warning;
    }

    /** Starts a new flight, using the current glide state when deciding whether to retry take-off. */
    public void begin() {
        release();
        restoreLandingSettings();
        takeoff.reset(mc.player != null && mc.player.isFallFlying());
        gaveUp = false;
        warnedCovered = false;
        warnedPitch40 = false;
    }

    /** Clears flight state, restores landing settings and gives held keys back to the keyboard. */
    public void reset() {
        begin();
    }

    /** Steers once per tick. The caller owns enabling ElytraFly and stopping any competing flight. */
    public void steer(Vec3 target, double turnSpeed, boolean takeOff) {
        if (mc.player == null || mc.level == null || target == null) {
            release();
            return;
        }
        ElytraFly elytraFly = Modules.get().get(ElytraFly.class);
        ElytraFlightModes mode = elytraFly.flightMode.get();
        double dx = target.x - mc.player.getX(), dz = target.z - mc.player.getZ(), dy = target.y - mc.player.getY();
        boolean far = Math.hypot(dx, dz) > CLOSE;
        // Every ElytraFly mode flies where the player faces.
        if (far) mc.player.setYRot(ElytraSteer.turn(mc.player.getYRot(), ElytraSteer.yawTo(dx, dz), (float) turnSpeed));
        // Vanilla moves only while gliding. Packet moves whenever the player is falling, gliding or not.
        boolean flying = mode == ElytraFlightModes.Packet ? !mc.player.onGround() : mc.player.isFallFlying();
        boolean keys = mode == ElytraFlightModes.Vanilla || mode == ElytraFlightModes.Packet;
        if (!elytraFly.isActive() || !flying || !keys) {
            release();
            if (elytraFly.isActive() && !mc.player.isFallFlying()) takeOff(mode, takeOff);
            return;
        }
        hold(mc.options.keyUp, FORWARD, far);
        if (mode == ElytraFlightModes.Packet) {
            // Packet sinks whenever jump is up, and ignores every key while sneaking.
            hold(mc.options.keyJump, JUMP, dy > 0);
            hold(mc.options.keyShift, SNEAK, false);
        } else {
            ElytraSteer.Climb climb = ElytraSteer.climb(dy, BAND);
            hold(mc.options.keyJump, JUMP, climb == ElytraSteer.Climb.UP);
            hold(mc.options.keyShift, SNEAK, climb == ElytraSteer.Climb.DOWN);
        }
    }

    /**
     * Approaches a landing point at a moderate speed, then glides down, returning true on touching the ground. Uses temporary Vanilla
     * settings because Pitch40 climbs at its lower bound and Packet continues its own packet flight. On touchdown or
     * cancellation, the caller releases keys, turns ElytraFly off and calls restoreLandingSettings. The caller chooses
     * a loaded, dry landing point and must re-check it while approaching.
     */
    public boolean descend(Vec3 target, double turnSpeed) {
        if (mc.player == null || mc.level == null || target == null) {
            release();
            return false;
        }
        if (mc.player.onGround()) {
            release();
            return true;
        }
        ElytraFly elytraFly = Modules.get().get(ElytraFly.class);
        if (landing == null) {
            release();
            landing = new LandingSettings(elytraFly);
            landingPilots++;
            landingOpenCooldown = 0;
            // Changing Meteor's mode does not run Packet.onDeactivate, which normally clears this flag.
            if (elytraFly.flightMode.get() == ElytraFlightModes.Packet) mc.player.getAbilities().flying = false;
            elytraFly.flightMode.set(ElytraFlightModes.Vanilla);
            elytraFly.horizontalSpeed.set(0.4);
            elytraFly.verticalSpeed.set(0.4);
            elytraFly.fallMultiplier.set(0.01);
            elytraFly.acceleration.set(false);
            elytraFly.autoTakeOff.set(false);
            elytraFly.autoHover.set(false);
            elytraFly.autoPilot.set(false);
            elytraFly.useFireworks.set(false);
            elytraFly.noCrash.set(true);
        }
        double dx = target.x - mc.player.getX(), dz = target.z - mc.player.getZ();
        ElytraLanding.Step step = ElytraLanding.step(Math.hypot(dx, dz), mc.player.getY() - target.y, turnSpeed);
        elytraFly.fallMultiplier.set(step.fallMultiplier());
        elytraFly.horizontalSpeed.set(step.horizontalSpeed());
        if (step.forward()) mc.player.setYRot(ElytraSteer.turn(mc.player.getYRot(), ElytraSteer.yawTo(dx, dz), (float) turnSpeed));
        float pitchStep = Math.max(-2, Math.min(2, -mc.player.getXRot()));
        mc.player.setXRot(mc.player.getXRot() + pitchStep);
        hold(mc.options.keyUp, FORWARD, step.forward());
        hold(mc.options.keyJump, JUMP, false);
        hold(mc.options.keyShift, SNEAK, step.descend());
        if (!mc.player.isFallFlying() && landingOpenCooldown-- <= 0) {
            landingOpenCooldown = 20;
            // Packet may fly without opening the wings; Vanilla descent needs an actual glide.
            if (mc.player.tryToStartFallFlying()) mc.player.connection.send(new ServerboundPlayerCommandPacket(mc.player, ServerboundPlayerCommandPacket.Action.START_FALL_FLYING));
        }
        return false;
    }

    private void takeOff(ElytraFlightModes mode, boolean enabled) {
        if (mode == ElytraFlightModes.Pitch40) {
            if (!warnedPitch40) {
                warnedPitch40 = true;
                warning.accept("Pitch40 can't take off. Start gliding above ElytraFly's pitch40-upper-bounds, or use Vanilla.");
            }
            return;
        }
        if (mode == ElytraFlightModes.Bounce || !enabled || gaveUp) return;
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
                    warning.accept("Something is above you, or you're in water or lava. " + flightName + " takes off once there's open sky.");
                }
            }
            case GAVE_UP -> {
                gaveUp = true;
                warning.accept("Couldn't take off after " + ElytraTakeoff.MAX_JUMPS + " jumps; take off by hand.");
            }
            case WAIT -> {}
        }
    }

    private boolean openAbove() {
        return mc.level.noCollision(mc.player, mc.player.getBoundingBox().expandTowards(0, HEADROOM, 0));
    }

    /** Holds wanted keys each tick, and gives a released key back to the keyboard. */
    private void hold(KeyMapping key, int bit, boolean down) {
        if (down) {
            key.setDown(true);
            held |= bit;
        } else if ((held & bit) != 0) {
            key.setDown(Input.isPressed(key));
            held &= ~bit;
        }
    }

    /** Releases only keys owned by this pilot, preserving physically held keys. */
    public void release() {
        if (mc.options != null) {
            hold(mc.options.keyUp, FORWARD, false);
            hold(mc.options.keyJump, JUMP, false);
            hold(mc.options.keyShift, SNEAK, false);
        }
    }

    /** Restores temporary landing settings; call after disabling ElytraFly on touchdown or cancellation. */
    public void restoreLandingSettings() {
        if (landing == null) return;
        try {
            landing.restore(Modules.get().get(ElytraFly.class));
        } finally {
            landing = null;
            landingPilots--;
        }
    }

    private record LandingSettings(ElytraFlightModes mode, double horizontalSpeed, double verticalSpeed,
                                   double fallMultiplier, boolean acceleration, boolean autoTakeOff,
                                   boolean autoHover, boolean autoPilot, boolean useFireworks, boolean noCrash) {
        private LandingSettings(ElytraFly fly) {
            this(fly.flightMode.get(), fly.horizontalSpeed.get(), fly.verticalSpeed.get(), fly.fallMultiplier.get(),
                fly.acceleration.get(), fly.autoTakeOff.get(), fly.autoHover.get(), fly.autoPilot.get(), fly.useFireworks.get(), fly.noCrash.get());
        }

        private void restore(ElytraFly fly) {
            fly.flightMode.set(mode);
            fly.horizontalSpeed.set(horizontalSpeed);
            fly.verticalSpeed.set(verticalSpeed);
            fly.fallMultiplier.set(fallMultiplier);
            fly.acceleration.set(acceleration);
            fly.autoTakeOff.set(autoTakeOff);
            fly.autoHover.set(autoHover);
            // Set after mode: changing back to Pitch40 itself resets auto-pilot.
            fly.autoPilot.set(autoPilot);
            fly.useFireworks.set(useFireworks);
            fly.noCrash.set(noCrash);
        }
    }
}
