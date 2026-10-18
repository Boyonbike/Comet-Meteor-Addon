package dev.tntcoating.modules;

import dev.tntcoating.core.AdaptiveFlightController;
import dev.tntcoating.core.AdaptiveFlightController.Motion;
import dev.tntcoating.core.AdaptiveFlightStep;
import dev.tntcoating.core.AdaptiveSpeedStep;
import dev.tntcoating.core.AdaptiveSpeedTerrain;
import dev.tntcoating.core.FlightCorridor;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicInteger;
import meteordevelopment.meteorclient.events.entity.player.PlayerMoveEvent;
import meteordevelopment.meteorclient.gui.GuiTheme;
import meteordevelopment.meteorclient.gui.widgets.WLabel;
import meteordevelopment.meteorclient.gui.widgets.WWidget;
import meteordevelopment.meteorclient.mixininterface.IVec3;
import meteordevelopment.meteorclient.settings.*;
import meteordevelopment.meteorclient.systems.modules.Modules;
import meteordevelopment.meteorclient.systems.modules.movement.Anchor;
import meteordevelopment.meteorclient.systems.modules.movement.speed.Speed;
import meteordevelopment.meteorclient.systems.modules.movement.speed.SpeedModes;
import meteordevelopment.meteorclient.systems.modules.render.Freecam;
import meteordevelopment.meteorclient.systems.modules.world.Timer;
import meteordevelopment.meteorclient.utils.misc.Keybind;
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
    private final Speed owner;
    private final Setting<Boolean> enabled, sprintBoost, avoidBlocks, safeManeuvering;
    private final Setting<Double> maximumSpeed, acceleration, braking, turnRate, cruiseMaximum, sprintMaximum,
        sneakMaximum, margin, correctionBackoff, recoveryRate;
    private final Setting<Integer> lookahead, correctionPause, recoveryDelay;
    private final Setting<Keybind> brakeKey;
    private final AdaptiveFlightController controller;
    // Network events only touch this mailbox and the volatile control flag.
    private final AtomicInteger corrections = new AtomicInteger();
    private volatile boolean controlling;
    private boolean selected, timerHeld, maneuvering;
    private double lastTimer = Timer.OFF, adaptiveMaximum, measuredSpeed;
    private ClientLevel world;
    private LocalPlayer player;
    private Vec3 lastPosition;
    private int movementTick = Integer.MIN_VALUE;
    private int chunkChecks, collisionChecks, diagnosticCorrectionBase;
    private long chunkLimitedTicks, obstacleLimitedTicks;
    private String state = "Ready", hud = "Adaptive";
    private WLabel statusLabel, speedLabel, checksLabel, countsLabel;

    private final FlightCorridor.Probe probe = new FlightCorridor.Probe() {
        @Override public boolean loaded(int x, int z) { return mc.level.getChunkSource().hasChunk(x, z); }
        @Override public boolean clear(AABB box) {
            if (!avoidBlocks.get()) return true;
            collisionChecks++;
            return !mc.level.getBlockCollisions(mc.player, box).iterator().hasNext();
        }
    };
    private final FlightCorridor.Probe chunksOnly = new FlightCorridor.Probe() {
        @Override public boolean loaded(int x, int z) { return probe.loaded(x, z); }
        @Override public boolean clear(AABB box) { return true; }
    };

    public AdaptiveSpeed(Speed owner) {
        this.owner = owner;
        SettingGroup engine = owner.settings.createGroup("Comet Adaptive Speed", false);
        SettingGroup controls = owner.settings.createGroup("Comet Speed Controls", false);
        SettingGroup terrain = owner.settings.createGroup("Comet Speed Terrain", false);
        SettingGroup recovery = owner.settings.createGroup("Comet Speed Recovery", false);
        enabled = engine.add(new BoolSetting.Builder().name("adaptive-speed")
            .description("Use the adaptive engine in Vanilla mode. Adaptive Max Speed replaces Vanilla Speed; Strafe keeps Meteor's movement.")
            .defaultValue(true).onChanged(value -> reset()).build());
        maximumSpeed = engine.add(new DoubleSetting.Builder().name("adaptive-max-speed")
            .description("Absolute horizontal ceiling in blocks/second at 20 ticks/second, before cruise/Sprint percentages. Terrain and corrections can lower it.")
            .defaultValue(40).range(0, 200).sliderRange(0, 80).visible(this::enabled).build());
        acceleration = engine.add(new DoubleSetting.Builder().name("acceleration")
            .description("Horizontal velocity gained per tick, in blocks per tick squared.")
            .defaultValue(.08).range(.01, 2).sliderRange(.01, .5).visible(this::enabled).build());
        braking = engine.add(new DoubleSetting.Builder().name("braking")
            .description("Horizontal slowing per tick on release, brake or reversal. Also determines stopping clearance.")
            .defaultValue(.2).range(.01, 2).sliderRange(.01, 1).visible(this::enabled).build());
        turnRate = engine.add(new DoubleSetting.Builder().name("adaptive-turn-rate")
            .description("Heading assistance in degrees per tick, increasing with speed. Zero keeps momentum steering.")
            .defaultValue(20).range(0, 45).sliderRange(0, 45).visible(this::enabled).build());
        sprintBoost = controls.add(new BoolSetting.Builder().name("sprint-boost")
            .description("Hold the physical Sprint key to use the Sprint ceiling, including sideways and backward movement.")
            .defaultValue(true).visible(this::enabled).build());
        cruiseMaximum = controls.add(new DoubleSetting.Builder().name("cruise-max-speed")
            .description("Cruise percentage of the current adaptive maximum after terrain and correction limits.")
            .defaultValue(35).range(0, 100).sliderRange(0, 100).decimalPlaces(1).visible(this::enabled).build());
        sprintMaximum = controls.add(new DoubleSetting.Builder().name("sprint-max-speed")
            .description("Sprint percentage of the current adaptive maximum. Releasing Sprint brakes toward cruise speed.")
            .defaultValue(100).range(0, 100).sliderRange(0, 100).decimalPlaces(1)
            .visible(() -> enabled() && sprintBoost.get()).build());
        sneakMaximum = controls.add(new DoubleSetting.Builder().name("sneak-max-speed")
            .description("Additional percentage while sneaking, if Meteor's When Sneaking allows Speed. Retains vanilla ledge protection.")
            .defaultValue(25).range(0, 100).sliderRange(0, 100).decimalPlaces(1).visible(this::enabled).build());
        brakeKey = controls.add(new KeybindSetting.Builder().name("brake-key")
            .description("Hold to brake horizontal motion even with movement keys held. Jumping and gravity stay vanilla.")
            .defaultValue(Keybind.none()).visible(this::enabled).build());
        lookahead = terrain.add(new IntSetting.Builder().name("lookahead")
            .description("Maximum forward corridor scan in blocks. Bounded work; never loads chunks.")
            .defaultValue(32).range(4, 64).sliderRange(4, 64).visible(this::enabled).build());
        margin = terrain.add(new DoubleSetting.Builder().name("terrain-margin")
            .description("Clearance to keep before walls and missing chunks, in blocks.")
            .defaultValue(.15).range(.01, 2).sliderRange(.01, 1).visible(this::enabled).build());
        avoidBlocks = terrain.add(new BoolSetting.Builder().name("avoid-blocks")
            .description("Brake for solid obstacles along the swept player body. Chunk protection remains active when disabled.")
            .defaultValue(true).visible(this::enabled).build());
        safeManeuvering = terrain.add(new BoolSetting.Builder().name("safe-maneuvering")
            .description("Allow vanilla collision and step-up at crawl speed (up to 6 blocks/second). Keep missing-chunk protection.")
            .defaultValue(true).visible(this::enabled).build());
        correctionBackoff = recovery.add(new DoubleSetting.Builder().name("correction-backoff")
            .description("Multiply the temporary ceiling by this after a server position correction. Also reduce Speed's Timer boost.")
            .defaultValue(.75).range(.1, .95).sliderRange(.1, .95).visible(this::enabled).build());
        correctionPause = recovery.add(new IntSetting.Builder().name("correction-pause")
            .description("Pause horizontal control for this many complete ticks after a correction, without cancelling server packets.")
            .defaultValue(5).range(0, 40).sliderRange(0, 20).visible(this::enabled).build());
        recoveryDelay = recovery.add(new IntSetting.Builder().name("recovery-delay")
            .description("Clean ticks before the temporary ceiling starts recovering.")
            .defaultValue(60).range(1, 1200).sliderRange(20, 200).visible(this::enabled).build());
        recoveryRate = recovery.add(new DoubleSetting.Builder().name("recovery-rate")
            .description("Fraction of the configured ceiling restored each clean tick after the delay.")
            .defaultValue(.0025).range(.0001, .1).sliderRange(.0001, .01).decimalPlaces(4).visible(this::enabled).build());
        controller = new AdaptiveFlightController(tuning());
    }

    public boolean enabled() { return enabled != null && enabled.get(); }
    public boolean selected() { return enabled() && owner.speedMode.get() == SpeedModes.Vanilla; }

    public void reset() {
        // onChanged can run during settings deserialization; the controller may not exist yet.
        releaseTimer();
        if (controller != null) controller.reset();
        corrections.set(0);
        controlling = selected = maneuvering = false;
        world = null;
        player = null;
        lastPosition = null;
        movementTick = Integer.MIN_VALUE;
        chunkChecks = collisionChecks = diagnosticCorrectionBase = 0;
        chunkLimitedTicks = obstacleLimitedTicks = 0;
        adaptiveMaximum = measuredSpeed = 0;
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
        chunkChecks = collisionChecks = 0;
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
        double rise = avoidBlocks.get() ? Math.max(0, Math.min(4, event.movement.y)) : 0;
        FlightCorridor.Result start = FlightCorridor.inspectStart(body, chunksOnly);
        chunkChecks += start.chunkChecks();
        AABB sweepBody = AdaptiveSpeedTerrain.sweepBody(body, rise,
            rise > 0 && start.reason() == FlightCorridor.Reason.CLEAR
                ? mc.level.getBlockCollisions(mc.player, body.expandTowards(0, rise, 0)) : java.util.List.of());
        if (rise > 0 && start.reason() == FlightCorridor.Reason.CLEAR) collisionChecks++;
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
            boolean sprint = sprintBoost.get() && Input.isPressed(mc.options.keySprint);
            boolean brake = brakeKey.get().isSet() && brakeKey.get().isPressed();
            double fraction = finite(sprint ? sprintMaximum.get() : cruiseMaximum.get(), 0, 100) / 100;
            if (mc.player.isShiftKeyDown()) fraction *= finite(sneakMaximum.get(), 0, 100) / 100;
            AdaptiveSpeedStep.Result step = AdaptiveSpeedStep.move(controller, direction,
                finite(maximumSpeed.get(), 0, 200) / 20, fraction, sprint, brake,
                sweepBody, probe, chunksOnly, lookahead.get(), finite(margin.get(), .01, 2), safeManeuvering.get());
            adaptiveMaximum = step.adaptiveMaximum();
            maneuvering = step.safeManeuvering();
            FlightCorridor.Result corridor = step.corridor();
            if (corridor != null) chunkChecks += corridor.chunkChecks();
            if (controller.pauseTicksRemaining() > 0) state = "Correction pause";
            else if (step.terrainLimited() && corridor != null && corridor.reason() == FlightCorridor.Reason.CHUNKS) {
                state = "Chunk limited";
                chunkLimitedTicks++;
            } else if (step.terrainLimited() && corridor != null && corridor.reason() == FlightCorridor.Reason.OBSTACLE) {
                state = maneuvering ? "Low-speed control" : "Obstacle limited";
                obstacleLimitedTicks++;
            } else if (brake) state = "Braking";
            else if (controller.speedFactor() < .999) state = "Recovering";
            else if (direction.horizontalLength() == 0) state = controller.output().horizontalLength() > 1e-7 ? "Braking" : "Idle";
            else state = sprint ? "Sprint boost" : "Cruising";
        } else if (controller.output().horizontalLength() > 0) {
            // Extra SELF moves get fresh clearance from their new position, without another acceleration step.
            Motion output = controller.output();
            FlightCorridor.Result corridor = FlightCorridor.inspect(sweepBody, new Vec3(output.x(), 0, output.z()),
                output.horizontalLength() + margin.get(), maneuvering && output.horizontalLength() <= .3 ? chunksOnly : probe);
            chunkChecks += corridor.chunkChecks();
            double allowed = Math.max(0, corridor.clearDistance() - margin.get());
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
        if (mc.gui.screen() != null || !mc.isWindowActive()) return "Input paused";
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
        measuredSpeed = 0;
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
        return new AdaptiveFlightController.Settings(finite(acceleration.get(), .01, 2), finite(braking.get(), .01, 2),
            finite(correctionBackoff.get(), .1, .95), correctionPause.get(), recoveryDelay.get(),
            finite(recoveryRate.get(), .0001, .1), finite(turnRate.get(), 0, 45));
    }

    public String info() { return hud; }

    private void updateStatus() {
        hud = String.format(Locale.ROOT, "Adaptive %.1f b/s", measuredSpeed);
        if (statusLabel != null) statusLabel.set(String.format(Locale.ROOT,
            "%s | measured %.1f b/s | ceiling %.0f%%", state, measuredSpeed, controller.speedFactor() * 100));
        if (speedLabel != null) {
            double maximum = controlling ? adaptiveMaximum : Math.min(finite(maximumSpeed.get(), 0, 200) / 20,
                AdaptiveFlightStep.maximumSpeed(controller.settings().braking(), lookahead.get(), margin.get()))
                * controller.speedFactor();
            speedLabel.set(String.format(Locale.ROOT,
                "Adaptive max %.1f b/s | cruise %.1f b/s | Sprint %.1f b/s",
                maximum * 20, maximum * cruiseMaximum.get() / 5, maximum * sprintMaximum.get() / 5));
        }
        if (checksLabel != null) checksLabel.set("This tick: " + chunkChecks + " chunk checks, " + collisionChecks + " block checks");
        if (countsLabel != null) countsLabel.set("Corrections " + (controller.correctionCount() - diagnosticCorrectionBase)
            + " | chunk-limited ticks " + chunkLimitedTicks + " | obstacle-limited ticks " + obstacleLimitedTicks);
    }

    public WWidget widget(GuiTheme theme) {
        var list = theme.verticalList();
        var section = list.add(theme.section("Speed Diagnostics", false)).expandX().widget();
        section.add(theme.label("Vanilla mode + Adaptive Speed uses Adaptive Max Speed; Strafe uses Meteor's settings."));
        var table = section.add(theme.table()).expandX().widget();
        statusLabel = table.add(theme.label("")).widget(); table.row();
        speedLabel = table.add(theme.label("")).widget(); table.row();
        countsLabel = table.add(theme.label("")).widget(); table.row();
        checksLabel = table.add(theme.label("")).widget(); table.row();
        table.add(theme.button("Reset Speed Diagnostics")).widget().action = () -> {
            chunkLimitedTicks = obstacleLimitedTicks = 0;
            diagnosticCorrectionBase = controller.correctionCount();
            updateStatus();
        };
        updateStatus();
        return list;
    }

    private static double finite(double value, double minimum, double maximum) {
        return Double.isFinite(value) ? Math.max(minimum, Math.min(maximum, value)) : minimum;
    }
}
