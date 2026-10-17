package dev.tntcoating.modules;

import dev.tntcoating.TntCoatingAddon;
import dev.tntcoating.core.AdaptiveFlightController;
import dev.tntcoating.core.AdaptiveFlightController.Motion;
import dev.tntcoating.core.AdaptiveFlightStep;
import dev.tntcoating.core.ElytraPilot;
import dev.tntcoating.core.FlightCorridor;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicInteger;
import meteordevelopment.meteorclient.events.entity.player.PlayerMoveEvent;
import meteordevelopment.meteorclient.events.game.GameLeftEvent;
import meteordevelopment.meteorclient.events.packets.PacketEvent;
import meteordevelopment.meteorclient.events.render.Render3DEvent;
import meteordevelopment.meteorclient.events.world.TickEvent;
import meteordevelopment.meteorclient.gui.GuiTheme;
import meteordevelopment.meteorclient.gui.widgets.WLabel;
import meteordevelopment.meteorclient.gui.widgets.WWidget;
import meteordevelopment.meteorclient.mixininterface.IVec3;
import meteordevelopment.meteorclient.settings.BoolSetting;
import meteordevelopment.meteorclient.settings.DoubleSetting;
import meteordevelopment.meteorclient.settings.IntSetting;
import meteordevelopment.meteorclient.settings.Setting;
import meteordevelopment.meteorclient.settings.SettingGroup;
import meteordevelopment.meteorclient.systems.modules.Module;
import meteordevelopment.meteorclient.systems.modules.Modules;
import meteordevelopment.meteorclient.systems.modules.movement.elytrafly.ElytraFlightMode;
import meteordevelopment.meteorclient.systems.modules.movement.elytrafly.ElytraFlightModes;
import meteordevelopment.meteorclient.systems.modules.movement.elytrafly.ElytraFly;
import meteordevelopment.meteorclient.systems.modules.render.Freecam;
import meteordevelopment.meteorclient.utils.misc.input.Input;
import meteordevelopment.orbit.EventHandler;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.Direction;
import net.minecraft.network.protocol.game.ClientboundPlayerPositionPacket;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.MoverType;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

/** Optional replacement for Meteor's Vanilla movement; flight planners continue to own their normal keys/settings. */
public final class AdaptiveElytraFly extends Module {
    private static final double EPSILON = 1e-7;
    private final SettingGroup general = settings.getDefaultGroup();
    private final SettingGroup terrain = settings.createGroup("Terrain");
    private final SettingGroup recovery = settings.createGroup("Correction Recovery");
    private final Setting<Double> acceleration = general.add(new DoubleSetting.Builder().name("acceleration")
        .description("Maximum velocity change per tick while accelerating or turning, in blocks per tick squared.")
        .defaultValue(0.15).range(0.01, 2).sliderRange(0.01, 0.5).build());
    private final Setting<Double> braking = general.add(new DoubleSetting.Builder().name("braking")
        .description("Velocity reduction per tick when slowing down. Higher values need less clear terrain ahead.")
        .defaultValue(0.3).range(0.01, 2).sliderRange(0.01, 1).build());
    private final Setting<Boolean> sprintBoost = general.add(new BoolSetting.Builder().name("sprint-boost")
        .description("Hold your Sprint key while flying to reach the current adaptive horizontal speed ceiling. Keeps steering, terrain and correction limits.")
        .defaultValue(true).build());
    private final Setting<Integer> lookahead = terrain.add(new IntSetting.Builder().name("lookahead")
        .description("Maximum corridor distance to check, in blocks. Work is bounded at 64 blocks per scan.")
        .defaultValue(64).range(8, 64).sliderRange(8, 64).build());
    private final Setting<Double> margin = terrain.add(new DoubleSetting.Builder().name("terrain-margin")
        .description("Space to leave before missing chunks or obstacles.")
        .defaultValue(0.35).range(0.05, 2).sliderRange(0.05, 1).build());
    private final Setting<Boolean> avoidBlocks = terrain.add(new BoolSetting.Builder().name("avoid-blocks")
        .description("Check swept player boxes for blocks. Meteor's No Crash also enables this protection.")
        .defaultValue(true).build());
    private final Setting<Double> correctionBackoff = recovery.add(new DoubleSetting.Builder().name("correction-backoff")
        .description("Multiply the temporary speed ceiling by this after a server position correction.")
        .defaultValue(0.75).range(0.1, 0.95).sliderRange(0.1, 0.95).build());
    private final Setting<Integer> correctionPause = recovery.add(new IntSetting.Builder().name("correction-pause")
        .description("Hold controlled movement for this many ticks after a correction. Vanilla still accepts the teleport normally.")
        .defaultValue(5).range(0, 40).sliderRange(0, 20).build());
    private final Setting<Integer> recoveryDelay = recovery.add(new IntSetting.Builder().name("recovery-delay")
        .description("Correction-free ticks before the temporary speed ceiling starts recovering.")
        .defaultValue(60).range(1, 1200).sliderRange(20, 200).build());
    private final Setting<Double> recoveryRate = recovery.add(new DoubleSetting.Builder().name("recovery-rate")
        .description("Fraction of the configured speed ceiling restored each clean tick after the delay.")
        .defaultValue(0.0025).range(0.0001, 0.1).sliderRange(0.0001, 0.01).decimalPlaces(4).build());

    // Receive events run on Netty. Only this atomic mailbox is touched there; all flight/world work is client-thread work.
    private final AtomicInteger corrections = new AtomicInteger();
    private final AdaptiveFlightController controller = new AdaptiveFlightController(controllerSettings());
    private final FlightCorridor.Probe probe = new FlightCorridor.Probe() {
        @Override public boolean loaded(int x, int z) { return mc.level.getChunkSource().hasChunk(x, z); }
        @Override public boolean clear(AABB swept) {
            if (!checkBlocks) return true;
            for (var shape : mc.level.getBlockCollisions(mc.player, swept)) {
                // Leave downward ground contact to Minecraft's collision solver, which sets onGround normally.
                if (!FlightCorridor.floorContactAllowed(mc.player.getBoundingBox(), toVec(controller.output()),
                    shape.max(Direction.Axis.Y))) return false;
            }
            return true;
        }
    };
    private ClientLevel world;
    private LocalPlayer player;
    private boolean controlling, checkBlocks;
    private int movementTick = Integer.MIN_VALUE;
    private long chunkLimitedTicks, obstacleLimitedTicks;
    private int chunkChecks, collisionChecks;
    private String state = "Waiting for flight", hud = state;
    private WLabel statusLabel, countsLabel, frameLabel, checksLabel;
    private double frameAverageMs, framePeakMs, measuredSpeed;
    private long slowFrames, sampleTime;
    private int diagnosticCorrectionBase;
    private Vec3 samplePosition;

    public AdaptiveElytraFly() {
        super(TntCoatingAddon.CATEGORY, "adaptive-elytra-fly",
            "Smooths Meteor Vanilla Elytra Fly, paces loaded terrain and backs off after position corrections.");
    }

    @Override public void onActivate() {
        reset();
        ElytraFly fly = Modules.get().get(ElytraFly.class);
        if (fly.flightMode.get() != ElytraFlightModes.Vanilla) {
            warning("Choose Vanilla in Meteor's Elytra Fly to use adaptive flight.");
        } else if (mc.player != null && mc.level != null && !fly.isActive()) fly.toggle();
    }

    @Override public void onDeactivate() { reset(); }

    @EventHandler private void onGameLeft(GameLeftEvent event) { reset(); }

    @EventHandler private void onPacket(PacketEvent.Receive event) {
        if (event.packet instanceof ClientboundPlayerPositionPacket) {
            corrections.updateAndGet(n -> n < 1024 ? n + 1 : n);
        }
    }

    @EventHandler private void onTick(TickEvent.Pre event) {
        if (world != mc.level || player != mc.player) {
            reset();
            world = mc.level;
            player = mc.player;
        }
        controller.configure(controllerSettings());
        controller.tick();
        chunkChecks = collisionChecks = 0;
        ElytraFly fly = Modules.get().get(ElytraFly.class);
        if (!eligible(fly)) suspend(reason(fly));
        consumeCorrections();
        measureSpeed();
        updateStatus();
    }

    /** Called by the Meteor mixin, instead of the original Vanilla move handler. Other modes remain Meteor's. */
    public boolean move(ElytraFly fly, ElytraFlightMode mode, PlayerMoveEvent event) {
        if (!isActive() || !eligible(fly) || event.type != MoverType.SELF) return false;
        // Stock hover has its own near-ground/key handling, so let that branch run in full when requested.
        if (ElytraPilot.landingInProgress() || (fly.autoHover.get() && mc.options.keyShift.isDown())) {
            suspend(ElytraPilot.landingInProgress() ? "Landing" : "Meteor hover");
            return false;
        }
        checkBlocks = avoidBlocks.get() || fly.noCrash.get();
        // Corrections/unloads can strand the body in unknown terrain. Let stock physics withdraw from an existing
        // overlap; a blocked path from a safe starting box still uses the adaptive guard normally.
        FlightCorridor.Result start = FlightCorridor.inspectStart(mc.player.getBoundingBox(), probe);
        chunkChecks += start.chunkChecks();
        if (checkBlocks) collisionChecks += start.collisionChecks();
        if (start.reason() != FlightCorridor.Reason.CLEAR) {
            suspend("Terrain overlap");
            updateStatus();
            return false;
        }
        controlling = true;
        consumeCorrections();
        if (movementTick != mc.player.tickCount) {
            movementTick = mc.player.tickCount;
            mode.autoTakeoff();
            mode.handleAutopilot();
            Motion requested = requested(fly, event.movement.y);
            // Probe the actual blended trajectory once, rather than separately scanning old and desired headings.
            AdaptiveFlightStep.Result step = AdaptiveFlightStep.move(controller, requested,
                finite(fly.horizontalSpeed.get(), 0, 64),
                Math.max(Math.abs(requested.y()), finite(fly.verticalSpeed.get(), 0, 64) * 0.5),
                mc.player.getBoundingBox(), probe, lookahead.get(), margin.get(),
                sprintBoost.get() && mc.gui.screen() == null && mc.isWindowActive()
                    && Input.isPressed(mc.options.keySprint));
            FlightCorridor.Result ahead = step.corridor();
            if (ahead != null) {
                chunkChecks += ahead.chunkChecks();
                if (checkBlocks) collisionChecks += ahead.collisionChecks();
            }
            if (controller.pauseTicksRemaining() > 0) state = "Correction pause";
            else if (step.terrainLimited() && ahead != null && ahead.reason() == FlightCorridor.Reason.CHUNKS) {
                state = "Chunk limited";
                chunkLimitedTicks++;
            } else if (step.terrainLimited() && ahead != null && ahead.reason() == FlightCorridor.Reason.OBSTACLE) {
                state = "Obstacle limited";
                obstacleLimitedTicks++;
            } else if (ahead != null && ahead.reason() == FlightCorridor.Reason.INVALID) state = "Invalid terrain";
            else if (controller.speedFactor() < 0.999) state = "Recovering";
            else if (step.lookaheadLimited()) state = "Lookahead limited";
            else if (controller.isBoosting()) state = "Sprint boost";
            else state = "Flying";
            updateStatus();
        } else if (controller.output().length() > EPSILON) {
            // A second SELF move in one tick must not reuse clearance from the old position or advance acceleration twice.
            Motion output = controller.output();
            FlightCorridor.Result actual = inspect(toVec(output), output.length() + margin.get());
            double allowed = Math.max(0, actual.clearDistance() - margin.get());
            if (allowed < output.length()) controller.accept(scale(output, allowed / output.length()));
        }
        Motion output = controller.output();
        ((IVec3) event.movement).meteor$set(output.x(), output.y(), output.z());
        return true;
    }

    private boolean eligible(ElytraFly fly) {
        return mc.player != null && mc.level != null && fly != null && fly.isActive()
            && fly.flightMode.get() == ElytraFlightModes.Vanilla && mc.player.isFallFlying()
            && mc.player.getItemBySlot(EquipmentSlot.CHEST).has(DataComponents.GLIDER)
            && !mc.player.isInLava() && !(mc.player.isInWater() && fly.stopInWater.get())
            && !Modules.get().isActive(Freecam.class);
    }

    private String reason(ElytraFly fly) {
        if (mc.level == null || mc.player == null) return "No world";
        if (!fly.isActive()) return "Elytra Fly off";
        if (fly.flightMode.get() != ElytraFlightModes.Vanilla) return "Vanilla required";
        return "Waiting for glide";
    }

    private Motion requested(ElytraFly fly, double naturalY) {
        int forward = (mc.options.keyUp.isDown() ? 1 : 0) - (mc.options.keyDown.isDown() ? 1 : 0);
        int right = (mc.options.keyRight.isDown() ? 1 : 0) - (mc.options.keyLeft.isDown() ? 1 : 0);
        double length = Math.hypot(forward, right);
        double speed = finite(fly.horizontalSpeed.get(), 0, 64);
        double yaw = Math.toRadians(mc.player.getYRot());
        double x = length == 0 ? 0 : (-Math.sin(yaw) * forward - Math.cos(yaw) * right) * speed / length;
        double z = length == 0 ? 0 : (Math.cos(yaw) * forward - Math.sin(yaw) * right) * speed / length;
        double y = naturalY < 0 ? naturalY * finite(fly.fallMultiplier.get(), 0, 64) : 0;
        if (mc.options.keyJump.isDown()) y += 0.5 * finite(fly.verticalSpeed.get(), 0, 64);
        else if (mc.options.keyShift.isDown()) y -= 0.5 * finite(fly.verticalSpeed.get(), 0, 64);
        return new Motion(x, finite(y, -64, 64), z);
    }

    private FlightCorridor.Result inspect(Vec3 direction, double distance) {
        FlightCorridor.Result result = FlightCorridor.inspect(mc.player.getBoundingBox(), direction, distance, probe);
        chunkChecks += result.chunkChecks();
        if (checkBlocks) collisionChecks += result.collisionChecks();
        return result;
    }

    private void consumeCorrections() {
        int count = corrections.getAndSet(0);
        if (count > 0 && controlling && mc.player != null && mc.player.isFallFlying()) {
            controller.corrected(count);
            movementTick = Integer.MIN_VALUE;
            samplePosition = null;
            measuredSpeed = 0;
        }
    }

    private void suspend(String why) {
        controlling = false;
        controller.accept(Motion.ZERO);
        movementTick = Integer.MIN_VALUE;
        state = why;
    }

    private void reset() {
        corrections.set(0);
        controller.reset();
        controlling = false;
        world = null;
        player = null;
        movementTick = Integer.MIN_VALUE;
        chunkLimitedTicks = obstacleLimitedTicks = slowFrames = 0;
        chunkChecks = collisionChecks = 0;
        frameAverageMs = framePeakMs = measuredSpeed = 0;
        samplePosition = null;
        sampleTime = 0;
        diagnosticCorrectionBase = 0;
        state = "Waiting for flight";
        updateStatus();
    }

    private AdaptiveFlightController.Settings controllerSettings() {
        return new AdaptiveFlightController.Settings(acceleration.get(), braking.get(), correctionBackoff.get(),
            correctionPause.get(), recoveryDelay.get(), recoveryRate.get());
    }

    private void measureSpeed() {
        if (!controlling || mc.player == null) { samplePosition = null; measuredSpeed = 0; return; }
        long now = System.nanoTime();
        Vec3 position = mc.player.position();
        if (samplePosition == null) { samplePosition = position; sampleTime = now; }
        else if (now - sampleTime >= 1_000_000_000L) {
            measuredSpeed = Math.hypot(position.x - samplePosition.x, position.z - samplePosition.z)
                * 1_000_000_000.0 / (now - sampleTime);
            samplePosition = position;
            sampleTime = now;
        }
    }

    @EventHandler private void onRender(Render3DEvent event) {
        if (!controlling || !Double.isFinite(event.frameTime) || event.frameTime <= 0) return;
        double ms = event.frameTime * 1000;
        frameAverageMs = frameAverageMs == 0 ? ms : frameAverageMs * 0.95 + ms * 0.05;
        framePeakMs = Math.max(framePeakMs, ms);
        if (ms > 50) slowFrames++;
    }

    private void updateStatus() {
        hud = String.format(Locale.ROOT, "%s %.0f b/s", state, measuredSpeed);
        if (statusLabel != null) statusLabel.set(String.format(Locale.ROOT,
            "%s | ceiling %.0f%% | measured %.1f b/s", state, controller.speedFactor() * 100, measuredSpeed));
        if (countsLabel != null) countsLabel.set("Corrections " + (controller.correctionCount() - diagnosticCorrectionBase)
            + " | chunk-limited ticks " + chunkLimitedTicks + " | obstacle-limited ticks " + obstacleLimitedTicks);
        if (checksLabel != null) checksLabel.set("This tick: " + chunkChecks + " chunk checks, " + collisionChecks + " block checks");
        if (frameLabel != null) frameLabel.set(String.format(Locale.ROOT,
            "Frames: average %.1f ms | peak %.1f ms | over 50 ms: %d", frameAverageMs, framePeakMs, slowFrames));
    }

    @Override public String getInfoString() { return hud; }

    @Override public WWidget getWidget(GuiTheme theme) {
        var table = theme.table();
        statusLabel = table.add(theme.label("")).widget(); table.row();
        countsLabel = table.add(theme.label("")).widget(); table.row();
        checksLabel = table.add(theme.label("")).widget(); table.row();
        frameLabel = table.add(theme.label("")).widget(); table.row();
        table.add(theme.button("Reset Flight Diagnostics")).widget().action = () -> {
            chunkLimitedTicks = obstacleLimitedTicks = slowFrames = 0;
            frameAverageMs = framePeakMs = 0;
            measuredSpeed = 0;
            samplePosition = null;
            diagnosticCorrectionBase = controller.correctionCount();
            updateStatus();
        };
        updateStatus();
        return table;
    }

    private static Vec3 toVec(Motion motion) { return new Vec3(motion.x(), motion.y(), motion.z()); }
    private static Motion scale(Motion motion, double factor) {
        return new Motion(motion.x() * factor, motion.y() * factor, motion.z() * factor);
    }
    private static double finite(double value, double min, double max) {
        return Double.isFinite(value) ? Math.max(min, Math.min(max, value)) : 0;
    }
}
