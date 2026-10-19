package dev.tntcoating.modules;

import dev.tntcoating.TntCoatingAddon;
import dev.tntcoating.compat.MeteorUpgrades;
import dev.tntcoating.core.AdaptiveFlightController;
import dev.tntcoating.core.AdaptiveFlightController.Motion;
import dev.tntcoating.core.AdaptiveFlightStep;
import dev.tntcoating.core.AdaptiveFlightLearning;
import dev.tntcoating.core.AdaptiveFlightMemory;
import dev.tntcoating.core.Finite;
import dev.tntcoating.core.FlightCorridor;
import dev.tntcoating.core.ServerGlide;
import dev.tntcoating.mixin.FireworkRocketAccessor;
import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import meteordevelopment.meteorclient.events.entity.player.PlayerMoveEvent;
import meteordevelopment.meteorclient.MeteorClient;
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
import meteordevelopment.meteorclient.utils.Utils;
import meteordevelopment.orbit.EventHandler;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.multiplayer.resolver.ServerAddress;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.Direction;
import net.minecraft.network.protocol.game.ClientboundExplodePacket;
import net.minecraft.network.protocol.game.ClientboundPlayerPositionPacket;
import net.minecraft.network.protocol.game.ClientboundSetEntityMotionPacket;
import net.minecraft.network.protocol.game.ServerboundMovePlayerPacket;
import net.minecraft.network.protocol.game.ServerboundUseItemPacket;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.MoverType;
import net.minecraft.world.entity.PositionMoveRotation;
import net.minecraft.world.entity.projectile.FireworkRocketEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.component.Fireworks;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.VoxelShape;

/** Optional replacement for Meteor's Vanilla movement; flight planners continue to own their normal keys/settings. */
public final class AdaptiveElytraFly extends Module {
    private static final double EPSILON = 1e-7;
    // Flight memory is written off the client thread; its fsync must never stall a movement tick.
    private static final ExecutorService MEMORY_WRITER = Executors.newSingleThreadExecutor(task -> {
        Thread thread = new Thread(task, "comet-flight-memory");
        thread.setDaemon(true);
        return thread;
    });
    private final SettingGroup general = settings.getDefaultGroup();
    private final SettingGroup terrain = settings.createGroup("Terrain");
    private final SettingGroup recovery = settings.createGroup("Correction Recovery");
    private final Setting<Double> acceleration = general.add(new DoubleSetting.Builder().name("acceleration")
        .description("Velocity change per tick while accelerating, in blocks per tick squared. Adaptive turn assistance handles high-speed heading changes separately.")
        .defaultValue(0.15).range(0.01, 2).sliderRange(0.01, 0.5).build());
    private final Setting<Double> braking = general.add(new DoubleSetting.Builder().name("braking")
        .description("Velocity reduction per tick when slowing down. Higher values need less clear terrain ahead.")
        .defaultValue(0.3).range(0.01, 2).sliderRange(0.01, 1).build());
    private final Setting<Double> adaptiveTurnRate = general.add(new DoubleSetting.Builder().name("adaptive-turn-rate")
        .description("Heading assistance in degrees per tick. Rises with speed to double at the adaptive engine's maximum, so fast turns stop sliding sideways, and fades out at low speed, where steering keeps its precise momentum feel. Zero restores momentum steering.")
        .defaultValue(15).range(0, 45).sliderRange(0, 45).build());
    private final Setting<Boolean> sprintBoost = general.add(new BoolSetting.Builder().name("sprint-boost")
        .description("Hold your Sprint key to boost horizontal movement and deliberate climb/descent. Keeps terrain and correction limits.")
        .defaultValue(true).build());
    private final Setting<Double> cruiseMaximum = general.add(new DoubleSetting.Builder().name("cruise-max-speed")
        .description("Horizontal cruise maximum as a percentage of the adaptive engine's attainable speed. 100% uses its full output; see Flight Diagnostics for blocks/second.")
        .defaultValue(35).range(0, 100).sliderRange(0, 100).decimalPlaces(1).build());
    private final Setting<Double> sprintMaximum = general.add(new DoubleSetting.Builder().name("sprint-max-speed")
        .description("Horizontal Sprint maximum as a percentage of the adaptive engine's attainable speed. 100% uses its full output.")
        .defaultValue(100).range(0, 100).sliderRange(0, 100).decimalPlaces(1).visible(sprintBoost::get).build());
    private final Setting<Double> verticalCruiseMaximum = general.add(new DoubleSetting.Builder().name("vertical-cruise-max-speed")
        .description("Jump/Sneak cruise maximum as a percentage of the learned adaptive ascent or descent limit.")
        .defaultValue(35).range(0, 100).sliderRange(0, 100).decimalPlaces(1).build());
    private final Setting<Double> verticalSprintMaximum = general.add(new DoubleSetting.Builder().name("vertical-sprint-max-speed")
        .description("Sprint + Jump/Sneak maximum as a percentage of the learned adaptive ascent or descent limit.")
        .defaultValue(100).range(0, 100).sliderRange(0, 100).decimalPlaces(1).visible(sprintBoost::get).build());
    private final Setting<Double> initialVerticalSpeed = general.add(new DoubleSetting.Builder().name("initial-vertical-speed")
        .description("Starting climb/descent limit on an unknown server, in blocks per tick. Clean boosted flight learns higher limits separately for up and down.")
        .defaultValue(.75).range(.05, 10).sliderRange(.1, 3).build());
    private final Setting<Double> verticalAcceleration = general.add(new DoubleSetting.Builder().name("vertical-acceleration")
        .description("Maximum vertical velocity change per tick. Vertical braking uses up to twice this; setbacks can reduce it further.")
        .defaultValue(.05).range(.005, .5).sliderRange(.005, .15).decimalPlaces(3).build());
    private final Setting<Boolean> rememberLimits = recovery.add(new BoolSetting.Builder().name("remember-server-limits")
        .description("Save tested horizontal, ascent, descent, total movement and vertical acceleration limits separately for each server and dimension.")
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
    private final Setting<Boolean> safeManeuvering = terrain.add(new BoolSetting.Builder().name("safe-maneuvering")
        .description("Resolve close-object movement with Minecraft collision, including after braking for a wall. Keeps unloaded-chunk protection.")
        .defaultValue(true).build());
    private final Setting<Double> closeObjectSpeed = terrain.add(new DoubleSetting.Builder().name("close-object-speed")
        .description("Maximum movement speed near an object, in blocks per tick. Press Sprint there to override it until clear.")
        .defaultValue(.6).range(.1, 4).sliderRange(.1, 2).decimalPlaces(2).visible(safeManeuvering::get).build());
    private final Setting<Boolean> serverGlideGuard = terrain.add(new BoolSetting.Builder().name("server-glide-guard")
        .description("Keep walls out of reach of the server's own elytra glide. A vanilla server keeps simulating a normal glide from your reported position every tick, and that hidden speed, not your visible speed, deals fly-into-wall damage.")
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

    // Receive events run on Netty. Only these atomic mailboxes are touched there; all flight/world work is client-thread work.
    private final AtomicInteger corrections = new AtomicInteger();
    private final AtomicReference<ClientboundPlayerPositionPacket> glideSetback = new AtomicReference<>();
    private final AtomicReference<ClientboundSetEntityMotionPacket> glideMotion = new AtomicReference<>();
    private final AtomicReference<Vec3> glideKnockback = new AtomicReference<>(Vec3.ZERO);
    private volatile int playerId = Integer.MIN_VALUE;
    private final AdaptiveFlightController controller = new AdaptiveFlightController(controllerSettings());
    private final AdaptiveFlightLearning learning = new AdaptiveFlightLearning();
    private AdaptiveFlightMemory memory;
    private AdaptiveFlightMemory.ProfileLimits savedLimits = AdaptiveFlightMemory.ProfileLimits.UNKNOWN;
    private String profile;
    private boolean memoryDirty;
    private int memorySaveTicks;
    private final AtomicBoolean memoryWriteQueued = new AtomicBoolean();
    private volatile String memoryFailure;
    private volatile boolean memoryWarning;
    private final FlightCorridor.Probe probe = new FlightCorridor.Probe() {
        @Override public boolean loaded(int x, int z) { return mc.level.getChunkSource().hasChunk(x, z); }
        @Override public boolean clear(AABB swept) {
            if (!checkBlocks) return true;
            collisionChecks++;
            for (var shape : mc.level.getBlockCollisions(mc.player, swept)) {
                // Leave downward ground contact to Minecraft's collision solver, which sets onGround normally.
                if (!FlightCorridor.floorContactAllowed(mc.player.getBoundingBox(), toVec(controller.output()),
                    shape.max(Direction.Axis.Y))) return false;
            }
            return true;
        }
    };
    private final FlightCorridor.Probe chunksOnly = FlightCorridor.Probe.chunksOnly(probe);
    // The server resolves its own glide against blocks the same way, minus step-up.
    private final ServerGlide.Collider collider = (box, movement) -> {
        Vec3 vec = toVec(movement);
        List<VoxelShape> entities = mc.level.getEntityCollisions(mc.player, box.expandTowards(vec));
        Vec3 allowed = Entity.collideBoundingBox(mc.player, vec, box, mc.level, entities);
        return new Motion(allowed.x, allowed.y, allowed.z);
    };
    // The velocity the server simulates for this player's glide; null while not gliding.
    private Motion serverGlide;
    private boolean groundedSinceReset, sentRotation;
    private float sentPitch, sentYaw;
    private int rockets, pendingRocketTicks;
    private long glideLimitedTicks, glideHits;
    private ClientLevel world;
    private LocalPlayer player;
    private boolean controlling, checkBlocks;
    private int movementTick = Integer.MIN_VALUE;
    private long chunkLimitedTicks, obstacleLimitedTicks;
    private int chunkChecks, collisionChecks;
    private String state = "Waiting for flight", hud = state;
    private WLabel statusLabel, speedLabel, learningLabel, countsLabel, glideLabel, frameLabel, checksLabel;
    private double frameAverageMs, framePeakMs, measuredSpeed;
    private double adaptiveMaximum;
    private boolean maneuvering, closeObjectOverride;
    private long slowFrames, sampleTime, lastTickNanos;
    private int diagnosticCorrectionBase;
    private Vec3 samplePosition, lastControlledPosition;

    public AdaptiveElytraFly() {
        super(TntCoatingAddon.CATEGORY, "adaptive-elytra-fly",
            "Smooths Meteor Vanilla Elytra Fly, paces loaded terrain and backs off after position corrections.");
    }

    @Override public void onActivate() {
        reset();
        if (!MeteorUpgrades.applied("ElytraFlyMixin")) {
            error("This Meteor build changed Elytra Fly, so Comet's adaptive movement could not attach to it. See the log.");
            toggle();
            return;
        }
        ElytraFly fly = Modules.get().get(ElytraFly.class);
        if (fly.flightMode.get() != ElytraFlightModes.Vanilla) {
            warning("Choose Vanilla in Meteor's Elytra Fly to use adaptive flight.");
        } else if (mc.player != null && mc.level != null && !fly.isActive()) fly.toggle();
    }

    @Override public void onDeactivate() { reset(); }

    @EventHandler private void onGameLeft(GameLeftEvent event) { reset(); }

    @EventHandler private void onPacket(PacketEvent.Receive event) {
        if (event.packet instanceof ClientboundPlayerPositionPacket position) {
            corrections.updateAndGet(n -> n < 1024 ? n + 1 : n);
            glideSetback.set(position);
        } else if (event.packet instanceof ClientboundSetEntityMotionPacket motion && motion.id() == playerId) {
            glideMotion.set(motion);
        } else if (event.packet instanceof ClientboundExplodePacket explosion) {
            explosion.playerKnockback().ifPresent(push -> glideKnockback.accumulateAndGet(push, Vec3::add));
        }
    }

    // Movement and item-use packets are sent from the client thread.
    @EventHandler private void onSend(PacketEvent.Send event) {
        if (event.packet instanceof ServerboundMovePlayerPacket move && move.hasRotation()) {
            sentPitch = move.getXRot(0);
            sentYaw = move.getYRot(0);
            sentRotation = Float.isFinite(sentPitch) && Float.isFinite(sentYaw);
        } else if (event.packet instanceof ServerboundUseItemPacket use && mc.player != null && mc.player.isFallFlying()) {
            ItemStack stack = mc.player.getItemInHand(use.getHand());
            if (stack.is(Items.FIREWORK_ROCKET)) {
                // The server boosts for at least this long; the rocket entity, once it arrives, covers the rest.
                Fireworks fireworks = stack.get(DataComponents.FIREWORKS);
                pendingRocketTicks = Math.max(pendingRocketTicks,
                    10 * (1 + (fireworks == null ? 0 : fireworks.flightDuration())));
            }
        }
    }

    @EventHandler private void onTick(TickEvent.Pre event) {
        if (world != mc.level || player != mc.player) {
            reset();
            world = mc.level;
            player = mc.player;
        }
        long now = System.nanoTime();
        if (controlling && lastTickNanos != 0 && now - lastTickNanos > 250_000_000L) learning.pauseForLag();
        lastTickNanos = now;
        controller.configure(controllerSettings());
        controller.tick();
        learning.configure(initialVerticalSpeed.get(), verticalAcceleration.get());
        bindProfile();
        playerId = mc.player == null ? Integer.MIN_VALUE : mc.player.getId();
        chunkChecks = collisionChecks = 0;
        ElytraFly fly = Modules.get().get(ElytraFly.class);
        if (!eligible(fly)) suspend(reason(fly));
        consumeCorrections();
        learning.tick(recoveryDelay.get(), recoveryRate.get());
        updateServerGlide();
        saveProfile(false);
        measureSpeed();
        updateStatus();
    }

    /** The server ticks its own glide from the position this tick reported, with the rotation it received. */
    @EventHandler private void onPostTick(TickEvent.Post event) {
        LocalPlayer p = mc.player;
        if (controlling && p != null) lastControlledPosition = p.position();
        if (serverGlide == null || p == null || mc.level == null || !p.isFallFlying()) return;
        ServerGlide.Tick tick = ServerGlide.tick(glideContext(p, sentRotation ? sentPitch : p.getXRot(),
            sentRotation ? sentYaw : p.getYRot()), p.getBoundingBox());
        serverGlide = tick.velocity();
        if (tick.damage() > 0) glideHits++;
        if (pendingRocketTicks > 0) pendingRocketTicks--;
    }

    /** Follows the glide velocity the server keeps for this player, which client movement never changes. */
    private void updateServerGlide() {
        ClientboundPlayerPositionPacket setback = glideSetback.getAndSet(null);
        ClientboundSetEntityMotionPacket motion = glideMotion.getAndSet(null);
        Vec3 knockback = glideKnockback.getAndSet(Vec3.ZERO);
        LocalPlayer p = mc.player;
        if (p == null || mc.level == null || !p.isFallFlying()) {
            serverGlide = null;
            groundedSinceReset = true;
            rockets = pendingRocketTicks = 0;
            return;
        }
        // Both sides start a takeoff from the same vanilla velocity. A flight already under way when this
        // module started is unknown, so assume a long glide.
        if (serverGlide == null) serverGlide = groundedSinceReset ? toMotion(p.getDeltaMovement())
            : ServerGlide.steady(p.getXRot(), p.getYRot(), p.getGravity(), p.hasEffect(MobEffects.SLOW_FALLING));
        if (setback != null) serverGlide = toMotion(PositionMoveRotation.calculateAbsolute(
            new PositionMoveRotation(p.position(), toVec(serverGlide), p.getYRot(), p.getXRot()),
            setback.change(), setback.relatives()).deltaMovement());
        if (motion != null) serverGlide = toMotion(motion.movement());
        if (!knockback.equals(Vec3.ZERO)) serverGlide = toMotion(toVec(serverGlide).add(knockback));
        rockets = Math.max(attachedRockets(p), pendingRocketTicks > 0 ? 1 : 0);
    }

    private int attachedRockets(LocalPlayer p) {
        int count = 0;
        for (FireworkRocketEntity rocket : mc.level.getEntitiesOfClass(FireworkRocketEntity.class,
            p.getBoundingBox().inflate(16))) {
            if (rocket.getEntityData().get(FireworkRocketAccessor.comet$attachedTarget())
                .orElse(Integer.MIN_VALUE) == p.getId()) count++;
        }
        return count;
    }

    private ServerGlide.Context glideContext(LocalPlayer p, float pitch, float yaw) {
        return new ServerGlide.Context(serverGlide, rockets, pitch, yaw, p.getGravity(),
            p.hasEffect(MobEffects.SLOW_FALLING), collider);
    }

    /** Called by the Meteor mixin, instead of the original Vanilla move handler. Other modes remain Meteor's. */
    public boolean move(ElytraFly fly, ElytraFlightMode mode, PlayerMoveEvent event) {
        if (!isActive() || !eligible(fly) || event.type != MoverType.SELF) return false;
        // Stock hover has its own near-ground/key handling, so let that branch run in full when requested.
        if (fly.autoHover.get() && mc.options.keyShift.isDown()) {
            suspend("Meteor hover");
            return false;
        }
        checkBlocks = avoidBlocks.get() || fly.noCrash.get();
        consumeCorrections();
        // Keep close-object contact under our controller: yielding to stock here
        // would invoke Meteor's unconditional No Crash and trap tight-space movement.
        boolean sprintKey = sprintBoost.get() && mc.screen == null && mc.isWindowActive()
            && Input.isPressed(mc.options.keySprint);
        if (maneuvering && sprintKey) closeObjectOverride = true;
        double releaseSpeed = closeObjectOverride ? AdaptiveFlightStep.MAX_HORIZONTAL_SPEED : closeObjectSpeed.get();
        boolean releaseSafeCollisions = safeManeuvering.get() && (closeObjectOverride
            || mc.player.getDeltaMovement().length() <= releaseSpeed && event.movement.length() <= releaseSpeed);
        boolean harmlessStart = releaseSafeCollisions && controller.output().length() <= releaseSpeed;
        // Corrections/unloads can strand the body in unknown terrain. Let stock physics withdraw from an existing
        // overlap; a blocked path from a safe starting box still uses the adaptive guard normally.
        FlightCorridor.Result start = FlightCorridor.inspectStart(mc.player.getBoundingBox(),
            harmlessStart ? chunksOnly : probe);
        chunkChecks += start.chunkChecks();
        if (start.reason() != FlightCorridor.Reason.CLEAR) {
            suspend("Terrain overlap");
            updateStatus();
            return false;
        }
        controlling = true;
        if (movementTick != mc.player.tickCount) {
            movementTick = mc.player.tickCount;
            mode.autoTakeoff();
            mode.handleAutopilot();
            Motion requested = requested(fly, event.movement.y);
            boolean sprinting = sprintKey || closeObjectOverride;
            int verticalInput = Finite.clamp(fly.verticalSpeed.get(), 0, 64, 0) == 0 ? 0
                : mc.options.keyJump.isDown() ? 1 : mc.options.keyShift.isDown() ? -1 : 0;
            ServerGlide.Context glide = checkBlocks && serverGlideGuard.get() && serverGlide != null
                ? glideContext(mc.player, mc.player.getXRot(), mc.player.getYRot()) : null;
            // Probe the actual blended trajectory once, rather than separately scanning old and desired headings.
            AdaptiveFlightStep.Result step = AdaptiveFlightStep.move(controller, requested,
                Finite.clamp(fly.horizontalSpeed.get(), 0, 64, 0),
                Math.max(Math.abs(requested.y()), Finite.clamp(fly.verticalSpeed.get(), 0, 64, 0) * 0.5),
                mc.player.getBoundingBox(), probe, lookahead.get(), margin.get(),
                new AdaptiveFlightStep.Controls(sprinting,
                    (sprinting ? sprintMaximum.get() : cruiseMaximum.get()) / 100,
                    (sprinting ? verticalSprintMaximum.get() : verticalCruiseMaximum.get()) / 100,
                    verticalInput, releaseSafeCollisions, glide, learning.limits(), releaseSpeed));
            Motion actualMotion = step.motion();
            boolean contactResolved = false;
            if (step.safeManeuvering()) {
                Motion resolved = resolveCollision(actualMotion);
                contactResolved = difference(actualMotion, resolved) > EPSILON;
                actualMotion = resolved;
                controller.accept(actualMotion);
            }
            if (closeObjectOverride && step.safeManeuvering() && !contactResolved) closeObjectOverride = false;
            learning.observe(actualMotion, verticalInput, sprinting,
                step.corridor() != null && step.corridor().reason() == FlightCorridor.Reason.CLEAR
                    && !step.glideLimited() && !contactResolved && controller.speedFactor() >= .999,
                (sprinting ? sprintMaximum.get() : cruiseMaximum.get()) / 100,
                (sprinting ? verticalSprintMaximum.get() : verticalCruiseMaximum.get()) / 100);
            adaptiveMaximum = step.adaptiveMaximum();
            maneuvering = step.safeManeuvering();
            if (step.glideLimited()) glideLimitedTicks++;
            FlightCorridor.Result ahead = step.corridor();
            if (ahead != null) {
                chunkChecks += ahead.chunkChecks();
            }
            if (controller.pauseTicksRemaining() > 0) state = "Correction pause";
            else if (step.terrainLimited() && ahead != null && ahead.reason() == FlightCorridor.Reason.CHUNKS) {
                state = "Chunk limited";
                chunkLimitedTicks++;
            } else if (ahead != null && ahead.reason() == FlightCorridor.Reason.INVALID) state = "Invalid terrain";
            else if (step.glideLimited()) state = "Glide clearance";
            else if (maneuvering) state = closeObjectOverride ? "Sprint close-object override" : "Close-object control";
            else if (step.terrainLimited() && ahead != null && ahead.reason() == FlightCorridor.Reason.OBSTACLE) {
                state = "Obstacle limited";
                obstacleLimitedTicks++;
            } else if (controller.speedFactor() < 0.999) state = "Recovering";
            else if (step.lookaheadLimited()) state = "Lookahead limited";
            else if (controller.isBoosting()) state = "Sprint boost";
            else state = "Flying";
            updateStatus();
        } else if (controller.output().length() > EPSILON) {
            // A second SELF move in one tick must not reuse clearance from the old position or advance acceleration twice.
            Motion output = controller.output();
            FlightCorridor.Result actual = FlightCorridor.inspect(mc.player.getBoundingBox(), toVec(output),
                output.length() + margin.get(), releaseSafeCollisions && maneuvering
                    && output.length() <= releaseSpeed ? chunksOnly : probe);
            chunkChecks += actual.chunkChecks();
            double allowed = Math.max(0, actual.clearDistance() - margin.get());
            if (allowed < output.length()) controller.accept(output.scaled(allowed / output.length()));
        }
        Motion output = controller.output();
        lastControlledPosition = mc.player.position();
        ((IVec3) event.movement).meteor$set(output.x(), output.y(), output.z());
        return true;
    }

    private boolean eligible(ElytraFly fly) {
        return mc.player != null && mc.level != null && fly != null && fly.isActive()
            && fly.flightMode.get() == ElytraFlightModes.Vanilla && mc.player.isFallFlying()
            && ElytraReplace.isGlider(mc.player.getItemBySlot(EquipmentSlot.CHEST))
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
        double speed = Finite.clamp(fly.horizontalSpeed.get(), 0, 64, 0);
        double yaw = Math.toRadians(mc.player.getYRot());
        double x = length == 0 ? 0 : (-Math.sin(yaw) * forward - Math.cos(yaw) * right) * speed / length;
        double z = length == 0 ? 0 : (Math.cos(yaw) * forward - Math.sin(yaw) * right) * speed / length;
        double y = naturalY < 0 ? naturalY * Finite.clamp(fly.fallMultiplier.get(), 0, 64, 0) : 0;
        if (mc.options.keyJump.isDown()) y += 0.5 * Finite.clamp(fly.verticalSpeed.get(), 0, 64, 0);
        else if (mc.options.keyShift.isDown()) y -= 0.5 * Finite.clamp(fly.verticalSpeed.get(), 0, 64, 0);
        return new Motion(x, Finite.clamp(y, -64, 64, 0), z);
    }

    private void consumeCorrections() {
        int count = corrections.getAndSet(0);
        if (count > 0 && controlling && mc.player != null && mc.player.isFallFlying()) {
            // Several packets before one controlled move carry no independent speed evidence.
            if (count > 1) learning.pauseForLag();
            // Long explicit teleports still pause movement, but do not permanently
            // teach a server that an otherwise clean flight speed was rejected.
            ClientboundPlayerPositionPacket packet = glideSetback.get();
            Vec3 target = packet == null || lastControlledPosition == null ? mc.player.position()
                : PositionMoveRotation.calculateAbsolute(new PositionMoveRotation(lastControlledPosition,
                    mc.player.getDeltaMovement(), mc.player.getYRot(), mc.player.getXRot()),
                    packet.change(), packet.relatives()).position();
            if (lastControlledPosition != null && target.distanceToSqr(lastControlledPosition) <= 64 * 64)
                learning.corrected(correctionBackoff.get());
            else learning.resetHistory();
            controller.corrected(count, learning.isLagPaused());
            saveProfile(true);
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
        maneuvering = false;
        closeObjectOverride = false;
        adaptiveMaximum = 0;
    }

    private void reset() {
        saveProfile(true);
        profile = null;
        savedLimits = AdaptiveFlightMemory.ProfileLimits.UNKNOWN;
        learning.configure(initialVerticalSpeed.get(), verticalAcceleration.get());
        learning.forget();
        corrections.set(0);
        glideSetback.set(null);
        glideMotion.set(null);
        glideKnockback.set(Vec3.ZERO);
        playerId = Integer.MIN_VALUE;
        serverGlide = null;
        groundedSinceReset = sentRotation = false;
        rockets = pendingRocketTicks = 0;
        glideLimitedTicks = glideHits = 0;
        controller.reset();
        controlling = false;
        world = null;
        player = null;
        movementTick = Integer.MIN_VALUE;
        chunkLimitedTicks = obstacleLimitedTicks = slowFrames = 0;
        chunkChecks = collisionChecks = 0;
        frameAverageMs = framePeakMs = measuredSpeed = 0;
        adaptiveMaximum = 0;
        maneuvering = false;
        closeObjectOverride = false;
        samplePosition = null;
        lastControlledPosition = null;
        sampleTime = 0;
        lastTickNanos = 0;
        diagnosticCorrectionBase = 0;
        state = "Waiting for flight";
        updateStatus();
    }

    private static Path memoryPath() {
        return MeteorClient.FOLDER.toPath().resolve("comet/adaptive-flight-limits.dat");
    }

    /** The server/world and dimension these limits belong to, whether or not they are persisted. */
    private String profileIdentity() {
        if (mc.level == null) return null;
        String server;
        if (mc.isLocalServer()) server = "local:" + Utils.getWorldName();
        else if (mc.getCurrentServer() != null && !mc.getCurrentServer().ip.isBlank()) {
            ServerAddress address = ServerAddress.parseString(mc.getCurrentServer().ip);
            server = "remote:" + address.getHost().toLowerCase(Locale.ROOT) + ":" + address.getPort();
        } else return null; // Never merge unrelated unknown or Realm connections into a shared profile.
        String identity = server + "|" + mc.level.dimension().identifier();
        return identity.length() <= 1024 ? identity : null;
    }

    /**
     * Learning restarts only when the server, world or dimension changes. Toggling remember-server-limits only
     * changes persistence, so the current session's learning is kept either way.
     */
    private void bindProfile() {
        String next = profileIdentity();
        if (Objects.equals(profile, next)) return;
        saveProfile(true);
        profile = next;
        savedLimits = AdaptiveFlightMemory.ProfileLimits.UNKNOWN;
        if (next != null && rememberLimits.get()) savedLimits = memory().getProfile(next);
        learning.restore(savedLimits.limits(), savedLimits.ceilings());
        controller.reset();
    }

    private AdaptiveFlightMemory memory() {
        if (memory == null) memory = AdaptiveFlightMemory.load(memoryPath());
        return memory;
    }

    private void saveProfile(boolean flush) {
        if (profile != null && rememberLimits.get()) {
            var current = new AdaptiveFlightMemory.ProfileLimits(learning.remembered(), learning.ceilings());
            if (!current.equals(savedLimits)) {
                memory().putProfile(profile, current);
                savedLimits = current;
                memoryDirty = true;
            }
        }
        String failure = memoryFailure;
        if (failure != null) {
            memoryFailure = null;
            if (!memoryWarning) warning("Could not save flight limits: %s", failure);
            memoryWarning = true;
            memoryDirty = true; // Retried on the next save interval.
        }
        if (!flush && ++memorySaveTicks < 200) return;
        memorySaveTicks = 0;
        if (!memoryDirty || memory == null) return;
        memoryDirty = false;
        AdaptiveFlightMemory target = memory;
        // A queued write snapshots the latest profiles when it runs, so further saves coalesce into it.
        if (!memoryWriteQueued.compareAndSet(false, true)) return;
        MEMORY_WRITER.execute(() -> {
            memoryWriteQueued.set(false);
            try {
                target.save(memoryPath());
                memoryWarning = false;
            } catch (IOException | RuntimeException e) {
                memoryFailure = e.getMessage() == null ? e.toString() : e.getMessage();
            }
        });
    }

    private AdaptiveFlightController.Settings controllerSettings() {
        return new AdaptiveFlightController.Settings(acceleration.get(), braking.get(), correctionBackoff.get(),
            correctionPause.get(), recoveryDelay.get(), recoveryRate.get(), adaptiveTurnRate.get());
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

    /** Lines for the Adaptive Elytra Fly HUD element, or null while the module is off. */
    public List<String> hudLines() {
        if (!isActive()) return null;
        Motion output = controller.output();
        var limits = learning.limits();
        return List.of(
            String.format(Locale.ROOT, "State: %s", state),
            String.format(Locale.ROOT, "Speed: %.1f b/s | H %.1f | V %+.1f",
                measuredSpeed, output.horizontalLength() * 20, output.y() * 20),
            String.format(Locale.ROOT, "Engine max: %.1f b/s | recovery %.0f%%",
                adaptiveMaximum * 20, controller.speedFactor() * 100),
            String.format(Locale.ROOT, "Learned: H %.1f | up %.1f | down %.1f b/s",
                limits.horizontal() * 20, limits.ascent() * 20, limits.descent() * 20),
            String.format(Locale.ROOT, "Total movement max: %.1f b/s", limits.total() * 20),
            String.format(Locale.ROOT, "Corrections: %d | server glide %s",
                controller.correctionCount(), serverGlide == null ? "idle"
                    : String.format(Locale.ROOT, "%.1f b/s", serverGlide.horizontalLength() * 20))
        );
    }

    private void updateStatus() {
        hud = String.format(Locale.ROOT, "%s %.0f b/s", state, measuredSpeed);
        // Diagnostics labels are only visible in a settings screen; skip their formatting during play.
        if (mc.screen == null) return;
        if (statusLabel != null) statusLabel.set(String.format(Locale.ROOT,
            "%s | ceiling %.0f%% | measured %.1f b/s", state, controller.speedFactor() * 100, measuredSpeed));
        if (speedLabel != null) {
            double maximum = controlling ? adaptiveMaximum : AdaptiveFlightStep.maximumSpeed(
                braking.get(), lookahead.get(), margin.get()) * controller.speedFactor();
            maximum = Math.min(maximum, learning.limits().horizontal() * controller.speedFactor());
            maximum = Math.min(maximum, learning.limits().total() * controller.speedFactor());
            speedLabel.set(String.format(Locale.ROOT,
                "Horizontal max %.1f b/s | cruise %.1f (%.1f%%) | Sprint %.1f (%.1f%%) | vertical cruise/Sprint %.1f%%/%.1f%%",
                maximum * 20, maximum * cruiseMaximum.get() / 5, cruiseMaximum.get(),
                maximum * sprintMaximum.get() / 5, sprintMaximum.get(),
                verticalCruiseMaximum.get(), verticalSprintMaximum.get()));
        }
        if (learningLabel != null) {
            var limits = learning.limits();
            var guidance = learning.ceilings();
            learningLabel.set(String.format(Locale.ROOT,
                "Adaptive limits: horizontal %.1f | up %.1f | down %.1f | total %.1f b/s | vertical acceleration %.3f | correction guidance %.1f/%.1f/%.1f/%.1f%s",
                Math.min(limits.horizontal(), AdaptiveFlightStep.maximumSpeed(braking.get(), lookahead.get(), margin.get())) * 20,
                limits.ascent() * 20, limits.descent() * 20, limits.total() * 20, limits.verticalAcceleration(),
                guidance.horizontal() * 20, guidance.ascent() * 20, guidance.descent() * 20, guidance.total() * 20,
                profile == null || !rememberLimits.get() ? " | session only" : " | server memory"));
        }
        if (countsLabel != null) countsLabel.set("Corrections " + (controller.correctionCount() - diagnosticCorrectionBase)
            + " | chunk-limited ticks " + chunkLimitedTicks + " | obstacle-limited ticks " + obstacleLimitedTicks);
        if (glideLabel != null) glideLabel.set(String.format(Locale.ROOT,
            "Server glide %s | glide-limited ticks %d | predicted server wall hits %d",
            serverGlide == null ? "idle" : String.format(Locale.ROOT, "%.1f b/s%s",
                serverGlide.horizontalLength() * 20, rockets > 0 ? " with rocket" : ""),
            glideLimitedTicks, glideHits));
        if (checksLabel != null) checksLabel.set("This tick: " + chunkChecks + " chunk checks, " + collisionChecks + " block checks");
        if (frameLabel != null) frameLabel.set(String.format(Locale.ROOT,
            "Frames: average %.1f ms | peak %.1f ms | over 50 ms: %d", frameAverageMs, framePeakMs, slowFrames));
    }

    @Override public String getInfoString() { return hud; }

    @Override public WWidget getWidget(GuiTheme theme) {
        var list = theme.verticalList();
        var section = list.add(theme.section("Flight Diagnostics", false)).expandX().widget();
        var table = section.add(theme.table()).expandX().widget();
        statusLabel = table.add(theme.label("")).widget(); table.row();
        speedLabel = table.add(theme.label("")).widget(); table.row();
        learningLabel = table.add(theme.label("")).widget(); table.row();
        countsLabel = table.add(theme.label("")).widget(); table.row();
        glideLabel = table.add(theme.label("")).widget(); table.row();
        checksLabel = table.add(theme.label("")).widget(); table.row();
        frameLabel = table.add(theme.label("")).widget(); table.row();
        table.add(theme.button("Reset Flight Diagnostics")).widget().action = () -> {
            chunkLimitedTicks = obstacleLimitedTicks = slowFrames = 0;
            glideLimitedTicks = glideHits = 0;
            frameAverageMs = framePeakMs = 0;
            measuredSpeed = 0;
            samplePosition = null;
            diagnosticCorrectionBase = controller.correctionCount();
            updateStatus();
        };
        table.row();
        table.add(theme.button("Forget This Server's Flight Limits")).widget().action = () -> {
            if (profile != null && memory != null) { memory.clear(profile); memoryDirty = true; }
            learning.forget();
            savedLimits = AdaptiveFlightMemory.ProfileLimits.UNKNOWN;
            controller.reset();
            movementTick = Integer.MIN_VALUE;
            saveProfile(true);
            updateStatus();
        };
        updateStatus();
        return list;
    }

    private static Vec3 toVec(Motion motion) { return new Vec3(motion.x(), motion.y(), motion.z()); }
    private static Motion toMotion(Vec3 vec) {
        return Double.isFinite(vec.x) && Double.isFinite(vec.y) && Double.isFinite(vec.z)
            ? new Motion(vec.x, vec.y, vec.z) : Motion.ZERO;
    }
    private Motion resolveCollision(Motion motion) {
        Vec3 requested = toVec(motion);
        List<VoxelShape> entities = mc.level.getEntityCollisions(mc.player,
            mc.player.getBoundingBox().expandTowards(requested));
        return toMotion(Entity.collideBoundingBox(mc.player, requested,
            mc.player.getBoundingBox(), mc.level, entities));
    }
    private static double difference(Motion a, Motion b) {
        return Math.sqrt((a.x() - b.x()) * (a.x() - b.x())
            + (a.y() - b.y()) * (a.y() - b.y())
            + (a.z() - b.z()) * (a.z() - b.z()));
    }
}
