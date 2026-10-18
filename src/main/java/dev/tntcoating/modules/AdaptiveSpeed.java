package dev.tntcoating.modules;

import dev.tntcoating.core.AdaptiveFlightController;
import dev.tntcoating.core.AdaptiveFlightController.Motion;
import dev.tntcoating.core.AdaptiveSpeedStep;
import dev.tntcoating.core.AdaptiveSpeedTerrain;
import dev.tntcoating.core.FlightCorridor;
import dev.tntcoating.render.AdaptiveEngineHud;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicInteger;
import meteordevelopment.meteorclient.events.entity.player.PlayerMoveEvent;
import meteordevelopment.meteorclient.events.render.Render2DEvent;
import meteordevelopment.meteorclient.mixininterface.IVec3;
import meteordevelopment.meteorclient.settings.*;
import meteordevelopment.meteorclient.systems.modules.Modules;
import meteordevelopment.meteorclient.systems.modules.movement.Anchor;
import meteordevelopment.meteorclient.systems.modules.movement.speed.Speed;
import meteordevelopment.meteorclient.systems.modules.movement.speed.SpeedModes;
import meteordevelopment.meteorclient.systems.modules.render.Freecam;
import meteordevelopment.meteorclient.systems.modules.world.Timer;
import meteordevelopment.meteorclient.utils.misc.input.Input;
import meteordevelopment.meteorclient.utils.player.PlayerUtils;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.world.entity.MoverType;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

import static meteordevelopment.meteorclient.MeteorClient.mc;

/** The built-in Speed instance owns these settings and this controller; no second event subscriber. */
public final class AdaptiveSpeed {
    private static final double ENGINE_MAXIMUM = 2; // 40 blocks/second.
    private static final int LOOKAHEAD = 64;
    private static final double TERRAIN_MARGIN = .15;
    private static final double CORRECTION_BACKOFF = .75;
    private static final int CORRECTION_PAUSE = 5;
    private static final int RECOVERY_DELAY = 60;
    private static final double RECOVERY_RATE = .0025;

    private final Speed owner;
    private final Setting<Double> acceleration, braking, dynamicSpeed;
    private final Setting<Boolean> engineHud;
    private final AdaptiveFlightController controller;
    // Network events only touch this mailbox and the volatile control flag.
    private final AtomicInteger corrections = new AtomicInteger();
    private volatile boolean controlling;
    private boolean selected, timerHeld, maneuvering, sprintRequested;
    private double lastTimer = Timer.OFF, measuredSpeed, adaptiveMaximum, targetFraction;
    private ClientLevel world;
    private LocalPlayer player;
    private Vec3 lastPosition;
    private int movementTick = Integer.MIN_VALUE;
    private String state = "Ready", hud = "Adaptive";

    private final FlightCorridor.Probe probe = new FlightCorridor.Probe() {
        @Override public boolean loaded(int x, int z) { return mc.level.getChunkSource().hasChunk(x, z); }
        @Override public boolean clear(AABB box) {
            return !mc.level.getBlockCollisions(mc.player, box).iterator().hasNext();
        }
    };
    private final FlightCorridor.Probe chunksOnly = new FlightCorridor.Probe() {
        @Override public boolean loaded(int x, int z) { return probe.loaded(x, z); }
        @Override public boolean clear(AABB box) { return true; }
    };

    public AdaptiveSpeed(Speed owner) {
        this.owner = owner;
        SettingGroup engine = owner.settings.createGroup("Comet Dynamic Sprint", false);
        acceleration = engine.add(new DoubleSetting.Builder().name("acceleration")
            .description("Engine maximum gained each tick, as a percentage. Small values give fine Sprint control.")
            .defaultValue(.5).range(.01, 10).sliderRange(.05, 2).decimalPlaces(2).build());
        braking = engine.add(new DoubleSetting.Builder().name("braking")
            .description("Engine maximum lost each tick while slowing or reversing, as a percentage.")
            .defaultValue(2).range(.01, 20).sliderRange(.05, 5).decimalPlaces(2).build());
        dynamicSpeed = engine.add(new DoubleSetting.Builder().name("dynamic-speed")
            .description("Sprint target as a percentage of the engine maximum. Normal movement uses Vanilla Speed.")
            .defaultValue(25).range(0, 100).sliderRange(0, 40).decimalPlaces(2).build());
        engineHud = engine.add(new BoolSetting.Builder().name("engine-hud")
            .description("Display a live Dynamic Sprint engine overview in the top-left corner.")
            .defaultValue(false).build());
        controller = new AdaptiveFlightController(tuning());
    }

    public boolean selected() { return owner.speedMode.get() == SpeedModes.Vanilla; }

    public void reset() {
        // onChanged can run during settings deserialization; the controller may not exist yet.
        releaseTimer();
        if (controller != null) controller.reset();
        corrections.set(0);
        controlling = selected = maneuvering = sprintRequested = false;
        world = null;
        player = null;
        lastPosition = null;
        movementTick = Integer.MIN_VALUE;
        measuredSpeed = adaptiveMaximum = targetFraction = 0;
        state = "Ready";
    }

    /** Called on Netty; no world, settings or movement access here. */
    public void corrected() {
        if (controlling) corrections.updateAndGet(count -> count == Integer.MAX_VALUE ? count : count + 1);
    }

    /** Returns true when the stock Vanilla tick should be skipped. */
    public boolean tick() {
        if (world != mc.level || player != mc.player || selected != selected()) {
            if (!selected && selected() && owner.isActive()) releaseStockTimer();
            reset();
            world = mc.level;
            player = mc.player;
            selected = selected();
        }
        if (!selected) return false;
        controller.configure(tuning());
        controller.tick();
        if (!consumeCorrections()) reconcile();
        String pause = pauseReason();
        if (pause != null) suspend(pause);
        updateStatus();
        return true;
    }

    /** Handles only horizontal SELF movement; vertical physics and collision resolution stay in Minecraft. */
    public boolean move(PlayerMoveEvent event) {
        if (!selected()) {
            if (selected || controlling || timerHeld) reset();
            return false;
        }
        if (event.type != MoverType.SELF) return false;
        if (world != mc.level || player != mc.player || !selected) tick();
        String pause = pauseReason();
        if (pause != null) {
            suspend(pause);
            return true;
        }
        consumeCorrections();
        Anchor anchor = Modules.get().get(Anchor.class);
        if (anchor != null && anchor.isActive() && anchor.controlMovement) {
            suspend("Anchor control");
            ((IVec3) event.movement).meteor$setXZ(anchor.deltaX, anchor.deltaZ);
            return true;
        }
        if (!Double.isFinite(event.movement.y)) { suspend("Invalid movement"); return true; }
        // Resolve headroom before extending the horizontal sweep, matching vanilla's Y-before-XZ collision order.
        // A ceiling already above us must not turn a clipped jump into a horizontal wall.
        AABB body = mc.player.getBoundingBox();
        double rise = Math.max(0, Math.min(4, event.movement.y));
        FlightCorridor.Result start = FlightCorridor.inspectStart(body, chunksOnly);
        AABB sweepBody = AdaptiveSpeedTerrain.sweepBody(body, rise,
            rise > 0 && start.reason() == FlightCorridor.Reason.CLEAR
                ? mc.level.getBlockCollisions(mc.player, body.expandTowards(0, rise, 0)) : java.util.List.of());
        if (!controlling) {
            Vec3 natural = mc.player.getDeltaMovement();
            double length = Math.hypot(natural.x, natural.z);
            if (Double.isFinite(length) && length > 0) {
                double scale = Math.min(1, .3 / length);
                controller.accept(new Motion(natural.x * scale, 0, natural.z * scale));
            }
        }
        controlling = true;
        if (movementTick != mc.player.tickCount) {
            movementTick = mc.player.tickCount;
            Vec3 input = PlayerUtils.getHorizontalVelocity(20);
            Motion direction = new Motion(input.x, 0, input.z);
            boolean sprint = Input.isPressed(mc.options.keySprint) || mc.player.isSprinting();
            double normalFraction = finite(owner.vanillaSpeed.get(), 0, ENGINE_MAXIMUM * 20)
                / (ENGINE_MAXIMUM * 20);
            double fraction = sprint ? finite(dynamicSpeed.get(), 0, 100) / 100 : normalFraction;
            AdaptiveSpeedStep.Result step = AdaptiveSpeedStep.move(controller, direction,
                ENGINE_MAXIMUM, fraction, sprint, false,
                sweepBody, probe, chunksOnly, LOOKAHEAD, TERRAIN_MARGIN, true);
            sprintRequested = sprint;
            targetFraction = fraction;
            adaptiveMaximum = step.adaptiveMaximum();
            maneuvering = step.safeManeuvering();
            FlightCorridor.Result corridor = step.corridor();
            if (controller.pauseTicksRemaining() > 0) state = "Correction pause";
            else if (step.terrainLimited() && corridor != null && corridor.reason() == FlightCorridor.Reason.CHUNKS) {
                state = "Chunk limited";
            } else if (step.terrainLimited() && corridor != null && corridor.reason() == FlightCorridor.Reason.OBSTACLE) {
                state = maneuvering ? "Low-speed control" : "Obstacle limited";
            } else if (controller.speedFactor() < .999) state = "Recovering";
            else if (direction.horizontalLength() == 0) state = controller.output().horizontalLength() > 1e-7 ? "Braking" : "Idle";
            else state = sprint ? "Dynamic sprint" : "Vanilla speed";
        } else if (controller.output().horizontalLength() > 0) {
            // Extra SELF moves get fresh clearance from their new position, without another acceleration step.
            Motion output = controller.output();
            FlightCorridor.Result corridor = FlightCorridor.inspect(sweepBody, new Vec3(output.x(), 0, output.z()),
                output.horizontalLength() + TERRAIN_MARGIN, maneuvering && output.horizontalLength() <= .3 ? chunksOnly : probe);
            double allowed = Math.max(0, corridor.clearDistance() - TERRAIN_MARGIN);
            if (allowed < output.horizontalLength()) {
                double scale = allowed / output.horizontalLength();
                controller.accept(new Motion(output.x() * scale, 0, output.z() * scale));
            }
        }
        Motion output = controller.output();
        ((IVec3) event.movement).meteor$setXZ(output.x(), output.z());
        updateTimer(output.horizontalLength() > 1e-7 && controller.pauseTicksRemaining() == 0);
        updateStatus();
        return true;
    }

    private String pauseReason() {
        if (mc.player == null || mc.level == null) return "No world";
        if (!mc.player.isAlive()) return "Dead";
        if (mc.player.isFallFlying() || mc.player.getAbilities().flying) return "Flying";
        if (mc.player.onClimbable()) return "Climbing";
        if (mc.player.getVehicle() != null) return "Riding";
        if (!owner.whenSneaking.get() && mc.player.isShiftKeyDown()) return "Sneaking";
        if (owner.vanillaOnGround.get() && !mc.player.onGround()) return "Ground only";
        if (!owner.inLiquids.get() && (mc.player.isInWater() || mc.player.isInLava())) return "In liquid";
        if (Modules.get().isActive(Freecam.class)) return "Freecam";
        if (mc.screen != null || !mc.isWindowActive()) return "Input paused";
        return null;
    }

    private boolean consumeCorrections() {
        int count = corrections.getAndSet(0);
        if (count == 0) return false;
        if (controlling) {
            controller.corrected(count);
            lastPosition = null;
            measuredSpeed = 0;
            // Do not reset movementTick: a mid-tick correction must not grant another acceleration step.
            releaseTimer();
        }
        return true;
    }

    private void reconcile() {
        if (!controlling || mc.player == null) { lastPosition = null; measuredSpeed = 0; return; }
        Vec3 current = mc.player.position();
        if (lastPosition != null) {
            double dx = current.x - lastPosition.x, dz = current.z - lastPosition.z;
            double length = Math.hypot(dx, dz);
            if (Double.isFinite(length)) {
                measuredSpeed = measuredSpeed * .75 + length * 20 * .25;
                Motion previous = controller.output();
                // Feed actual collision/ledge clipping back into momentum instead of pushing a wall at full speed.
                if (length + 1e-5 < previous.horizontalLength()) controller.accept(new Motion(dx, 0, dz));
            }
        }
        lastPosition = current;
    }

    private void suspend(String reason) {
        controlling = false;
        controller.accept(Motion.ZERO);
        movementTick = Integer.MIN_VALUE;
        lastPosition = null;
        maneuvering = false;
        sprintRequested = false;
        measuredSpeed = adaptiveMaximum = targetFraction = 0;
        state = reason;
        releaseTimer();
    }

    private void updateTimer(boolean moving) {
        double requested = owner.timer.get();
        if (!Double.isFinite(requested) || requested < .01) requested = Timer.OFF;
        if (!moving || requested == Timer.OFF) { releaseTimer(); return; }
        // Position corrections must reduce both displacement and any accelerated client clock.
        lastTimer = requested > 1 ? 1 + (requested - 1) * controller.speedFactor() : requested;
        Modules.get().get(Timer.class).setOverride(lastTimer);
        timerHeld = true;
    }

    private void releaseStockTimer() {
        Timer timer = Modules.get().get(Timer.class);
        // Strafe/stock Vanilla may have left Speed's override behind before an adaptive handoff.
        double stock = owner.timer.get();
        if (timer != null && stock != Timer.OFF && Double.compare(timer.getMultiplier(), stock) == 0) {
            timer.setOverride(Timer.OFF);
        }
    }

    private void releaseTimer() {
        if (!timerHeld) return;
        Timer timer = Modules.get().get(Timer.class);
        // Avoid clearing a different module's later override when its value is distinguishable.
        if (timer != null && Double.compare(timer.getMultiplier(), lastTimer) == 0) timer.setOverride(Timer.OFF);
        timerHeld = false;
        lastTimer = Timer.OFF;
    }

    private AdaptiveFlightController.Settings tuning() {
        return new AdaptiveFlightController.Settings(
            ENGINE_MAXIMUM * finite(acceleration.get(), .01, 10) / 100,
            ENGINE_MAXIMUM * finite(braking.get(), .01, 20) / 100,
            CORRECTION_BACKOFF, CORRECTION_PAUSE, RECOVERY_DELAY, RECOVERY_RATE, 0);
    }

    public String info() { return hud; }

    public void renderHud(Render2DEvent event) {
        if (!engineHud.get() || !selected() || !owner.isActive()) return;
        AdaptiveEngineHud.renderLeft(event, "Dynamic Sprint", List.of(
            String.format(Locale.ROOT, "State: %s", state),
            String.format(Locale.ROOT, "Speed: %.1f b/s | output %.1f b/s",
                measuredSpeed, controller.output().horizontalLength() * 20),
            String.format(Locale.ROOT, "Target: %s %.1f%% | max %.1f b/s",
                sprintRequested ? "Sprint" : "Vanilla", targetFraction * 100, adaptiveMaximum * 20),
            String.format(Locale.ROOT, "Recovery: %.0f%% | corrections %d",
                controller.speedFactor() * 100, controller.correctionCount())
        ));
    }

    private void updateStatus() {
        hud = String.format(Locale.ROOT, "%s %.1f b/s", state, measuredSpeed);
    }

    private static double finite(double value, double minimum, double maximum) {
        return Double.isFinite(value) ? Math.max(minimum, Math.min(maximum, value)) : minimum;
    }
}
