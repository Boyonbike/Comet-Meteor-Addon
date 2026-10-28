/*
 * Adapted from Quiettee Utils BoatPhase by Quiettee and contributors.
 * Copyright (c) Quiettee Utils contributors; Comet adaptations (c) 2026 Comet contributors.
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package dev.tntcoating.modules.boatfly;

import dev.tntcoating.TntCoatingAddon;
import dev.tntcoating.core.BoatLavaAvoidance;
import meteordevelopment.meteorclient.MeteorClient;
import dev.tntcoating.events.BoatPlayerCorrectionEvent;
import meteordevelopment.meteorclient.events.game.GameLeftEvent;
import meteordevelopment.meteorclient.events.game.GameJoinedEvent;
import meteordevelopment.meteorclient.events.packets.PacketEvent;
import meteordevelopment.meteorclient.events.world.TickEvent;
import meteordevelopment.meteorclient.gui.GuiTheme;
import meteordevelopment.meteorclient.gui.widgets.WWidget;
import meteordevelopment.meteorclient.gui.widgets.containers.WTable;
import meteordevelopment.meteorclient.settings.*;
import meteordevelopment.meteorclient.systems.modules.Module;
import meteordevelopment.meteorclient.systems.modules.Modules;
import meteordevelopment.meteorclient.utils.misc.Keybind;
import meteordevelopment.orbit.EventHandler;
import meteordevelopment.orbit.EventPriority;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.network.Connection;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.game.ClientboundEntityPositionSyncPacket;
import net.minecraft.network.protocol.game.ClientboundMoveEntityPacket;
import net.minecraft.network.protocol.game.ClientboundMoveVehiclePacket;
import net.minecraft.network.protocol.game.ClientboundSetPassengersPacket;
import net.minecraft.network.protocol.game.ClientboundTeleportEntityPacket;
import net.minecraft.network.protocol.game.ServerboundInteractPacket;
import net.minecraft.network.protocol.game.ServerboundMoveVehiclePacket;
import net.minecraft.network.protocol.game.ServerboundPlayerInputPacket;
import net.minecraft.tags.FluidTags;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.vehicle.boat.AbstractBoat;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import java.io.IOException;
import java.io.PrintWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicInteger;

public abstract class BoatFlyEngine extends Module {
    /** Saved module names of every boat controller; at most one is active at a time. */
    public static final String MANUAL = "boat-fly", AUTOMATIC = dev.tntcoating.core.LobbyRecoveryStore.NATIVE_BOAT;
    private static final List<String> ENGINES = List.of(MANUAL, AUTOMATIC);

    // Shared groups, in the order a driver tunes them; controllers insert their own groups around these.
    protected final SettingGroup sgControls = settings.createGroup("Controls", true);
    protected final SettingGroup sgSpeed = settings.createGroup("Speed", true);
    protected final SettingGroup sgPhasing = settings.createGroup("Phasing", false);
    protected final SettingGroup sgSafety = settings.createGroup("Safety", false);
    protected final SettingGroup sgRecovery = settings.createGroup("Correction Recovery", false);
    protected final SettingGroup sgDiagnostics = settings.createGroup("Diagnostics", false);

    private final Setting<Keybind> dismountKey = sgControls.add(new KeybindSetting.Builder()
        .name("dismount-key")
        .description("Key or mouse button that leaves the boat. While bound, sneak no longer dismounts; unbound uses Minecraft's sneak key.")
        .defaultValue(Keybind.none()).build());
    private final Setting<Boolean> lockYaw = sgControls.add(new BoolSetting.Builder()
        .name("lock-boat-yaw").description("Turn the boat to face where you look while steering by hand.")
        .defaultValue(true).build());
    private final Setting<Boolean> pauseOthers = sgControls.add(new BoolSetting.Builder()
        .name("pause-conflicting-modules")
        .description("Turn off other flight and boat modules while you drive, and turn them back on after you leave the boat.")
        .defaultValue(true).build());

    private final Setting<Boolean> airSubsteps = sgSpeed.add(new BoolSetting.Builder()
        .name("air-substeps").description("Split fast air travel into several small movement packets so the server accepts more speed.")
        .defaultValue(true).build());
    private final Setting<Double> substepSpeed = sgSpeed.add(new DoubleSetting.Builder()
        .name("substep-speed").description("Horizontal blocks per tick in open air with substeps (x20 for blocks per second).")
        .defaultValue(3.2).min(.1).max(BoatPhaseAirSteps.MAX_SPEED).sliderRange(.1, 3.8).visible(airSubsteps::get).build());
    private final Setting<Double> limitedSpeed = sgSpeed.add(new DoubleSetting.Builder()
        .name("limited-speed")
        .description("Horizontal blocks per tick in a single packet. Used when substeps are off or the server rejects them, and caps adaptive phasing.")
        .defaultValue(0.99).min(0.01).max(9).sliderRange(0.01, 3).build());
    private final Setting<Double> verticalSpeed = sgSpeed.add(new DoubleSetting.Builder()
        .name("vertical-speed").description("Blocks per tick when rising with jump or descending with sprint.")
        .defaultValue(3).min(0.1).max(BoatPhaseVertical.MAX_SPEED).sliderRange(0.1, BoatPhaseVertical.MAX_SPEED).build());
    private final Setting<Double> airAcceleration = sgSpeed.add(new DoubleSetting.Builder()
        .name("air-acceleration").description("Largest change in horizontal speed per tick; lower values start and stop more smoothly.")
        .defaultValue(0.1).min(0.01).max(1).sliderRange(0.01, 0.3).build());
    private final Setting<Boolean> antiKick = sgSpeed.add(new BoolSetting.Builder()
        .name("anti-kick").description("Make small real descents during long hovers so the server does not kick for flying.")
        .defaultValue(true).build());

    private final Setting<Boolean> phase = sgPhasing.add(new BoolSetting.Builder()
        .name("phase").description("Let the boat move through blocks.")
        .defaultValue(true).build());
    private final Setting<Boolean> adaptivePhase = sgPhasing.add(new BoolSetting.Builder()
        .name("adaptive-phase").description("Keep full speed along the collision-free part of a move and only slow down for the part inside blocks.")
        .defaultValue(true).visible(phase::get).build());
    private final Setting<Double> adaptivePhaseSpeed = sgPhasing.add(new DoubleSetting.Builder()
        .name("adaptive-phase-speed").description("Requested blocks per tick while adaptive phasing; never above limited-speed.")
        .defaultValue(3).min(0.24).max(9).sliderRange(0.24, 3).visible(() -> phase.get() && adaptivePhase.get()).build());
    private final Setting<Double> inBlockSpeed = sgPhasing.add(new DoubleSetting.Builder()
        .name("in-block-speed").description("Horizontal blocks per tick while the boat is inside blocks. 0.24 is the proven safe value.")
        .defaultValue(0.24).min(0.01).max(BoatPhaseMotion.MAX_PHASE_HORIZONTAL).sliderRange(0.01, 0.249).visible(phase::get).build());

    private final Setting<Boolean> stopLava = sgSafety.add(new BoolSetting.Builder()
        .name("stop-before-lava").description("Stop the boat instead of moving into lava.")
        .defaultValue(true).build());
    private final Setting<BoatLavaAvoidance.Mode> lavaAvoidance = sgSafety.add(new EnumSetting.Builder<BoatLavaAvoidance.Mode>()
        .name("lava-avoidance-mode")
        .description("Boat and riders keeps every passenger out of lava. Boat only lets riders touch lava while the boat stays clear.")
        .defaultValue(BoatLavaAvoidance.Mode.BoatAndRiders).visible(stopLava::get).build());

    private final Setting<Integer> correctionHold = sgRecovery.add(new IntSetting.Builder()
        .name("correction-hold").description("Ticks to hold still after the server corrects the boat's position.")
        .defaultValue(8).min(1).max(100).sliderRange(1, 40).build());
    private final Setting<Boolean> remountRetry = sgRecovery.add(new BoolSetting.Builder()
        .name("retry-after-remount").description("After the server rejects fast travel, try full speed again whenever you re-board.")
        .defaultValue(true).build());
    private final Setting<Boolean> landedRetry = sgRecovery.add(new BoolSetting.Builder()
        .name("retry-after-landing").description("With retry-after-remount off: after the server rejects substeps, try them again when you land and re-board.")
        .defaultValue(true).visible(() -> !remountRetry.get()).build());
    private final Setting<Double> landedRetrySpeed = sgRecovery.add(new DoubleSetting.Builder()
        .name("landed-retry-speed").description("Substep speed for that landed retry. Your substep-speed setting is not changed.")
        .defaultValue(3.8).range(.1,BoatPhaseAirSteps.MAX_SPEED).sliderRange(.1,3.8)
        .visible(() -> !remountRetry.get() && landedRetry.get()).build());

    private final Setting<Boolean> debugFile = sgDiagnostics.add(new BoolSetting.Builder()
        .name("debug-file").description("Write a movement log for each ride to .minecraft/comet-boat-fly.")
        .defaultValue(false).build());
    private static final String[] CONFLICTS = { "entity-control", "boat-noclip", "BoatNoclip", "lance", "lance-original",
        "boat-phase", "highway-fly", "mace-smash", "float", "glider-walk", "elytra-fly", "elytra-fly+", "flight", "poltergeist", "belfry", "geyser", "treadmill" };
    /** Runtime-only substep speed for a landed retry; NaN uses substep-speed. */
    private double landedSubstepSpeed = Double.NaN;
    public boolean profileLoading;
    private final BoatPhaseMotion motion = new BoatPhaseMotion();
    private final BoatTickGate eventGate = new BoatTickGate();
    private final BoatPhaseTravel travel = new BoatPhaseTravel();
    private final BoatPhaseMeter meter = new BoatPhaseMeter();
    private final BoatPhaseRemount remount = new BoatPhaseRemount();
    private BoatPhaseTravel.Mode plannedMode = BoatPhaseTravel.Mode.LIMITED;
    private Connection travelConnection;
    private ClientLevel travelWorld;
    private Object lastPlayerCorrection, lastVehicleCorrection, lastInputPacket, lastObservationPacket;
    private Object lastBoardInteraction;
    private int lastGroundedAge = -1000;
    private String boardingRetryReason = "no grounded boarding interaction";
    private String lastModeDescription = "";
    private final List<Module> paused = new ArrayList<>();
    private final ConcurrentLinkedQueue<Observation> observations = new ConcurrentLinkedQueue<>();
    private final AtomicInteger observationCount = new AtomicInteger();
    private volatile long epoch;
    private volatile Connection connection;
    private LocalPlayer player;
    private ClientLevel world;
    private ClientPacketListener network;
    private AbstractBoat boat;
    private boolean driver;
    private boolean correcting;
    private Vec3 lastWire;
    private Vec3 plannedFrom;
    private int plannedTick = -1;
    private int sentCount;
    private int corrections;
    private boolean plannedThroughBlocks;
    private boolean plannedClear;
    private boolean verticalRejected;
    private int lastFastVerticalTick = -100;
    private boolean adaptiveBlocked;
    private int lastAdaptiveTick = -100;
    private int lastSneakTick = -100;
    private boolean sentSneak;
    private int airborneTicks;
    private Vec3 lastStep = Vec3.ZERO;
    private ServerboundMoveVehiclePacket substepPacket;
    private boolean splitting;
    private Vec3 substepOrigin;
    private String status = "board a boat";
    private PrintWriter log;

    private record Observation(long epoch, Connection connection, Packet<?> packet) {}

    protected BoatFlyEngine(String name, String description) {
        super(TntCoatingAddon.CATEGORY, name, description);
    }

    /** Every registered boat controller, manual first. */
    public static List<BoatFlyEngine> engines() {
        Modules modules = Modules.get();
        if (modules == null) return List.of();
        List<BoatFlyEngine> engines = new ArrayList<>(ENGINES.size());
        for (String name : ENGINES) if (modules.get(name) instanceof BoatFlyEngine engine) engines.add(engine);
        return engines;
    }

    /** The controller that currently drives boats, or null. */
    public static BoatFlyEngine active() {
        for (BoatFlyEngine engine : engines()) if (engine.isActive()) return engine;
        return null;
    }

    /** Moves the named groups to the front, in order, so a controller's own sections lead its settings screen. */
    protected final void leadWithGroups(SettingGroup... first) {
        for (int i = first.length - 1; i >= 0; i--) {
            settings.groups.remove(first[i]);
            settings.groups.addFirst(first[i]);
        }
    }

    @Override
    public void onActivate() {
        // Only one controller may drive; inherit the other's paused modules so they are restored once, at the end.
        for (BoatFlyEngine other : engines()) if (other != this && other.isActive()) {
            for (Module module : other.paused) if (!paused.contains(module)) paused.add(module);
            other.paused.clear();
            other.disable();
        }
        detach();
        travel.retry();
        verticalRejected = false;
        remount.reset();
        eventGate.reset();
        adaptiveBlocked = false;
        landedSubstepSpeed = Double.NaN;
        status = "board a boat";
    }

    /** Current setting locations let old profiles follow settings moved between sections. */
    public final java.util.Map<String, String> settingGroups() {
        java.util.Map<String, String> groups = new java.util.HashMap<>();
        for (SettingGroup group : settings) for (Setting<?> setting : group) groups.put(setting.name, group.name);
        return groups;
    }

    /** Profiles replace module flags, so a pending request to re-enable old conflicts must not survive a load. */
    public void beginProfileLoad() { discardRestorationForProfileLoad(); }
    public void endProfileLoad() {}
    /** Saves the user's conflict preferences rather than the flags temporarily cleared while driving. */
    public void restoreBorrowedInSavedConfig(net.minecraft.nbt.CompoundTag root) { restorePausedInSavedConfig(root); }

    protected final void holdRestorationForDisconnect() { moduleRestoreGate.worldLeft(); }

    @Override
    public void onDeactivate() { automationPaused = automationRecovery = false; detach(); }

    @EventHandler
    private void onGameLeft(GameLeftEvent event) {
        moduleRestoreGate.worldLeft();
        detach();
        travel.retry(); travelConnection = null;
        verticalRejected = false;
        remount.reset();
    }

    @EventHandler
    private void onGameJoined(GameJoinedEvent event) { moduleRestoreGate.worldJoined(); }

    private boolean sameSession() {
        return player == mc.player && world == mc.level && network == mc.getConnection();
    }

    private boolean owns(AbstractBoat candidate) {
        return boat == candidate && driver && sameSession() && mc.player != null && mc.player.isAlive()
            && !candidate.isRemoved() && mc.player.getRootVehicle() == candidate && candidate.getControllingPassenger() == mc.player;
    }

    public net.minecraft.world.entity.player.Input dismountInput(net.minecraft.world.entity.player.Input original) {
        boolean ridingBoat = mc.player != null && mc.player.getRootVehicle() instanceof AbstractBoat;
        return dev.tntcoating.core.BoatDismountInput.apply(original, ridingBoat,
            dismountKey.get().isSet(), dismountPressed());
    }

    private boolean dismountPressed() {
        if (!dismountKey.get().isSet()) return mc.options.keyShift.isDown();
        return mc.player != null && mc.gui.screen() == null && mc.isWindowActive() && dismountKey.get().isPressed();
    }

    public boolean phases(AbstractBoat candidate) {
        return owns(candidate) && phase.get() && plannedThroughBlocks && !correcting && motion.holdTicks() == 0 && mc.gui.screen() == null;
    }

    private boolean automationPaused;
    private boolean automationRecovery;

    /** Optional AutoLobby bridge; hold travel without disabling flight or phasing. */
    public void pauseAutomation(boolean paused, boolean recovering) {
        automationPaused = paused;
        automationRecovery = recovering;
        if (paused) {
            motion.cancelTravel();
        }
    }

    public Vec3 movement(AbstractBoat candidate, Vec3 vanilla) {
        if (!owns(candidate)) return vanilla;
        plannedMode = travelMode();
        if (lockYaw.get()) candidate.setYRot(mc.player.getYRot());
        if (automationPaused || correcting || motion.holdTicks() > 0 || mc.gui.screen() != null) {
            candidate.setDeltaMovement(Vec3.ZERO);
            return Vec3.ZERO;
        }
        Vec3 from = candidate.position();
        double forward = (mc.options.keyUp.isDown() ? 1 : 0) - (mc.options.keyDown.isDown() ? 1 : 0);
        double sideways = (mc.options.keyLeft.isDown() ? 1 : 0) - (mc.options.keyRight.isDown() ? 1 : 0);
        boolean automatic = automaticForward() && !automationRecovery;
        if (automatic) { forward = 1; sideways = 0; }
        float yaw = automatic ? automaticYaw() : mc.player.getYRot();
        if (automatic) candidate.setYRot(yaw);
        boolean up = !automatic && mc.options.keyJump.isDown(), down = !automatic && mc.options.keySprint.isDown();
        double floor = world.getMinY() + 2;
        double topOffset = candidate.getSelfAndPassengers().mapToDouble(e -> e.getBoundingBox().maxY - candidate.getY()).max().orElse(candidate.getBbHeight());
        double ceiling = world.getMaxY() - topOffset;
        double airLimit = automationRecovery ? .2 : travelSpeed();
        double verticalSpeed = automationRecovery ? .2 : travelVerticalSpeed();
        double airSpeed = airLimit;
        double solidSpeed = Math.min(adaptivePhaseSpeed.get(), limitedSpeed.get());
        if (automationRecovery) solidSpeed = Math.min(solidSpeed, .2);
        boolean adaptive = phase.get() && adaptivePhase.get() && !adaptiveBlocked;
        BoatPhaseMotion.Point step = motion.plan(point(from), forward, sideways, up, down, yaw,
            adaptive ? Math.max(airSpeed, solidSpeed) : airSpeed, verticalSpeed, floor, ceiling, false);
        Vec3 delta = new Vec3(step.x(), step.y(), step.z());
        boolean throughBlocks = phase.get() && !world.noCollision(candidate, candidate.getBoundingBox().expandTowards(delta).deflate(1e-7));
        boolean embedded = !world.noCollision(candidate, candidate.getBoundingBox().deflate(1e-7));
        if (!embedded && !supported(candidate) && !candidate.isInWater()) airborneTicks++;
        else airborneTicks = 0;
        if (throughBlocks) {
            if (adaptive) {
                step = motion.plan(point(from), forward, sideways, up, down, yaw, solidSpeed, verticalSpeed, floor, ceiling, false);
                step = BoatPhaseMotion.collisionStep(step, inBlockSpeed.get(), p -> {
                    Vec3 desired = new Vec3(p.x(), p.y(), p.z());
                    return point(Entity.collideBoundingBox(candidate, desired, candidate.getBoundingBox(), world,
                        world.getEntityCollisions(candidate, candidate.getBoundingBox().expandTowards(desired))));
                });
                if (Math.hypot(step.x(), step.z()) > inBlockSpeed.get() + 1e-6) lastAdaptiveTick = motion.tick();
            } else step = BoatPhaseMotion.phaseStep(step, Math.min(inBlockSpeed.get(), BoatPhaseMotion.PROVEN_PHASE_HORIZONTAL));
            delta = new Vec3(step.x(), step.y(), step.z());
        } else {
            step = motion.plan(point(from), forward, sideways, up, down, yaw, airSpeed, verticalSpeed, floor, ceiling, false);
            step = BoatPhaseMotion.airStep(step, BoatPhaseMotion.horizontalStep(point(lastStep), airLimit), airAcceleration.get(), airborneTicks,
                antiKick.get(), up || down, from.y - floor);
            delta = new Vec3(step.x(), step.y(), step.z());
            if (phase.get() && !world.noCollision(candidate, candidate.getBoundingBox().expandTowards(delta).deflate(1e-7))) {
                throughBlocks = true;
                step = BoatPhaseMotion.horizontalStep(step, limitedSpeed.get());
                step = BoatPhaseMotion.phaseStep(step, Math.min(inBlockSpeed.get(), BoatPhaseMotion.PROVEN_PHASE_HORIZONTAL));
                delta = new Vec3(step.x(), step.y(), step.z());
            }
        }
        Vec3 waterStep=waterDeparture(candidate,delta.y);
        if(waterStep!=null) {delta=waterStep;throughBlocks=false;}
        plannedThroughBlocks = throughBlocks;
        AABB path = candidate.getBoundingBox().expandTowards(delta);
        if (!loaded(path)) {
            status = "waiting for chunks";
            delta = Vec3.ZERO;
            motion.cancelTravel();
        } else if (stopLava.get() && BoatLavaAvoidance.blocked(candidate.getBoundingBox(),
            candidate.getSelfAndPassengers().filter(entity -> entity != candidate).map(Entity::getBoundingBox).toList(),
            delta, lavaAvoidance.get(), this::containsLava)) {
            status = "lava ahead";
            delta = Vec3.ZERO;
            motion.cancelTravel();
        } else {
            status = throughBlocks ? adaptive ? "adaptive phase" : "phasing"
                : (automatic ? "AFK travel" : "positioning") + (speedFallback() ? " (speed fallback)" : "");
        }
        if(waterStep!=null)status=delta.y>0?"exiting water":"water exit blocked";
        plannedFrom = from;
        plannedTick = motion.tick();
        plannedClear = !throughBlocks && clearTravel(candidate, delta);
        candidate.setDeltaMovement(delta);
        return delta;
    }

    private boolean supported(AbstractBoat candidate) {
        return supported(candidate, .05);
    }

    private boolean supported(AbstractBoat candidate, double depth) {
        AABB b = candidate.getBoundingBox();
        return mc.level != null && !mc.level.noCollision(candidate, new AABB(b.minX + 1e-4, b.minY - depth, b.minZ + 1e-4,
            b.maxX - 1e-4, b.minY + 1e-7, b.maxZ - 1e-4));
    }

    private boolean travelReady(AbstractBoat candidate) {
        return isActive() && owns(candidate) && !automationPaused && !correcting && motion.holdTicks() == 0 && mc.gui.screen() == null;
    }

    private double travelVerticalSpeed() { return verticalRejected ? Math.min(9, verticalSpeed.get()) : verticalSpeed.get(); }

    public double travelSpeed() {
        return switch (travelMode()) {
            case SUBSTEPS -> Double.isNaN(landedSubstepSpeed) ? substepSpeed.get() : landedSubstepSpeed;
            case LIMITED -> limitedSpeed.get();
        };
    }

    private BoatPhaseTravel.Mode travelMode() {
        return travel.select(airSubsteps.get());
    }

    private boolean speedFallback() {
        return airSubsteps.get() && travelMode() == BoatPhaseTravel.Mode.LIMITED;
    }

    /** Overridden by the AFK controller; lobby recovery keeps ordinary steering. */
    protected boolean automaticForward() { return false; }
    protected float automaticYaw() { return mc.player.getYRot(); }

    public boolean clearTravel(AbstractBoat candidate, Vec3 delta) {
        if (!travelReady(candidate) || !Double.isFinite(delta.lengthSqr()) || delta.length() > Math.hypot(BoatPhaseVertical.MAX_SPEED, BoatPhaseAirSteps.MAX_SPEED)) return false;
        return clearRoute(candidate,delta);
    }

    public boolean clearRoute(AbstractBoat candidate, Vec3 delta) {
        if (!travelReady(candidate) || !Double.isFinite(delta.lengthSqr()) || delta.length()>192) return false;
        for (Entity entity : candidate.getSelfAndPassengers().toList()) {
            AABB path = entity.getBoundingBox().expandTowards(delta).deflate(1e-7);
            if (path.minY < world.getMinY()+2 || path.maxY > world.getMaxY()+1 || !loaded(path)
                || !world.getWorldBorder().isWithinBounds(path) || !world.noCollision(entity, path) || containsFluid(path)) return false;
        }
        return true;
    }

    public void afterMove(AbstractBoat candidate) {
        if (!owns(candidate)) return;
        boolean ground = candidate.getDeltaMovement().y <= 0 && supported(candidate);
        candidate.setOnGround(ground);
        candidate.verticalCollisionBelow = ground;
        if (!plannedThroughBlocks && !ground) candidate.verticalCollision = false;
    }

    private boolean loaded(AABB box) {
        for (int x = ((int) Math.floor(box.minX)) >> 4; x <= ((int) Math.floor(box.maxX)) >> 4; x++) {
            for (int z = ((int) Math.floor(box.minZ)) >> 4; z <= ((int) Math.floor(box.maxZ)) >> 4; z++) {
                if (!world.getChunkSource().hasChunk(x, z)) return false;
            }
        }
        return true;
    }

    private boolean containsLava(AABB box) {
        for (BlockPos pos : BlockPos.betweenClosed((int) Math.floor(box.minX), (int) Math.floor(box.minY), (int) Math.floor(box.minZ),
            (int) Math.floor(box.maxX), (int) Math.floor(box.maxY), (int) Math.floor(box.maxZ))) {
            if (world.getFluidState(pos).is(FluidTags.LAVA)) return true;
        }
        return false;
    }

    private boolean containsFluid(AABB box) {
        for (BlockPos pos : BlockPos.betweenClosed((int) Math.floor(box.minX), (int) Math.floor(box.minY), (int) Math.floor(box.minZ),
            (int) Math.floor(box.maxX), (int) Math.floor(box.maxY), (int) Math.floor(box.maxZ))) {
            if (!world.getFluidState(pos).isEmpty()) return true;
        }
        return false;
    }

    private boolean containsWater(AABB box) {
        for(BlockPos pos:BlockPos.betweenClosed(BlockPos.containing(box.minX,box.minY,box.minZ),BlockPos.containing(box.maxX,box.maxY,box.maxZ)))
            if(world.getFluidState(pos).is(FluidTags.WATER))return true;
        return false;
    }

    public Vec3 waterDeparture(AbstractBoat candidate,double ascent) {
        if(candidate==null||world==null||!Double.isFinite(ascent)||ascent<=0||!containsWater(candidate.getBoundingBox()))return null;
        Vec3 step=new Vec3(0,Math.min(.5,ascent),0);
        return clearWaterExit(candidate,step)?step:Vec3.ZERO;
    }

    private boolean clearWaterExit(AbstractBoat candidate,Vec3 step) {
        if(!travelReady(candidate)||!Double.isFinite(step.lengthSqr())||step.y<=0||step.y>.5||step.horizontalDistanceSqr()!=0)return false;
        for(Entity entity:candidate.getSelfAndPassengers().toList()) {
            AABB path=entity.getBoundingBox().expandTowards(step).deflate(1e-7);
            if(path.minY<world.getMinY()+2||path.maxY>world.getMaxY()+1||!loaded(path)
                ||!world.getWorldBorder().isWithinBounds(path)||!world.noCollision(entity,path))return false;
            for(BlockPos pos:BlockPos.betweenClosed(BlockPos.containing(path.minX,path.minY,path.minZ),BlockPos.containing(path.maxX,path.maxY,path.maxZ))) {
                var fluid=world.getFluidState(pos);
                if(!fluid.isEmpty()&&!fluid.is(FluidTags.WATER))return false;
            }
        }
        return true;
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    private void onTick(TickEvent.Pre event) {
        if (mc.player != null && !eventGate.tick(mc.player, mc.player.tickCount)) return;
        Connection currentConnection = mc.getConnection() == null ? null : mc.getConnection().getConnection();
        if (travelConnection != currentConnection) { travel.retry(); verticalRejected = false; remount.reset(); travelConnection = currentConnection; }
        if (travelWorld != mc.level) { remount.reset(); travelWorld=mc.level; }
        AbstractBoat current = mc.player != null && mc.player.getRootVehicle() instanceof AbstractBoat b ? b : null;
        boolean controlling = current != null && current.getControllingPassenger() == mc.player;
        if (!sameSession() || boat != current || controlling != driver || (mc.player != null && !mc.player.isAlive())) {
            detach();
            if (current != null && mc.player.isAlive()) attach(current, controlling);
        }
        if (boat == null) { status = "board a boat"; return; }
        motion.nextTick();
        meter.tick(motion.tick(), System.nanoTime());
        drainObservations();
        String modeDescription = modeDescription();
        if (!modeDescription.equals(lastModeDescription)) {
            record("SPEED-MODE %s substeps=%b", modeDescription, airSubsteps.get());
            lastModeDescription = modeDescription;
        }
        if (driver) {
            if (pauseOthers.get()) pauseConflicts();
            if (motion.holdTicks() > 0) status = "correction hold " + motion.holdTicks();
        } else status = "passenger - observing";
        if (motion.tick() % 20 == 0) {
            record("STATE role=%s boat=%d local=%s wire=%s passengers=%s inside=%b health=%.1f tx=%d corrections=%d status=%s",
                driver ? "driver" : "passenger", boat.getId(), boat.position(), lastWire, boat.getPassengers().stream().map(Entity::getId).toList(),
                !world.noCollision(boat, boat.getBoundingBox()), mc.player.getHealth() + mc.player.getAbsorptionAmount(), sentCount, corrections, status);
            long now = System.nanoTime();
            record("SPEED sentBpt=%.3f sentBps=%.2f echoBpt=%.3f echoBps=%.2f packetsLastTick=%d mode=%s elapsedMs=%d",
                meter.sentBpt(), meter.sentBps(), meter.echoBpt(now), meter.echoBps(now), meter.packetsLastTick(), modeDescription,
                (now - rideStartedNanos) / 1_000_000L);
            if (log != null) log.flush();
        }
    }

    private void attach(AbstractBoat current, boolean controlling) {
        boat = current;
        player = mc.player;
        world = mc.level;
        network = mc.getConnection();
        connection = network.getConnection();
        driver = controlling;
        lastWire = boat.position();
        motion.reset();
        adaptiveBlocked = false;
        lastAdaptiveTick = lastSneakTick = -100;
        travel.newRide();
        lastFastVerticalTick = -100;
        meter.reset();
        lastModeDescription = "";
        rideStartedNanos = System.nanoTime();
        sentSneak = false;
        plannedClear = false;
        sentCount = corrections = 0;
        openLog();
        boolean retryToken = remount.board(boat.getId(), player.tickCount, driver, remountRetry.get() || landedRetry.get());
        record("RETRY-CHECK token=%b driver=%b substepRejected=%b reason=%s", retryToken, driver,
            travel.rejected(BoatPhaseTravel.Mode.SUBSTEPS), boardingRetryReason);
        if (retryToken && remountRetry.get() && (travel.flightRejected() || verticalRejected)) {
            retryConfiguredFlight();
            record("REMOUNT-RETRY horizontal=%.2f vertical=%.2f", travelSpeed(), travelVerticalSpeed());
            info("Reboarded: retrying %.2f horizontal / %.2f vertical b/t.", travelSpeed(), travelVerticalSpeed());
        } else if (retryToken && !remountRetry.get() && airSubsteps.get() && travel.rejected(BoatPhaseTravel.Mode.SUBSTEPS)) {
            travel.retry(BoatPhaseTravel.Mode.SUBSTEPS);
            landedSubstepSpeed = landedRetrySpeed.get();
            record("LANDED-RETRY speed=%.2f", landedSubstepSpeed);
            info("Landed remount: retrying air substeps at %.2f b/t.", landedSubstepSpeed);
        }
        record("MOUNT boat=%d type=%s role=%s position=%s passengers=%s", boat.getId(), boat.getType(), driver ? "driver" : "passenger",
            boat.position(), boat.getPassengers().stream().map(Entity::getId).toList());
        if (driver && pauseOthers.get()) pauseConflicts();
        if (driver) info(boardedMessage(dismountKeyName()));
        else info("Passenger seat: observing the driver's boat; no movement controls are sent.");
    }

    protected final String dismountKeyName() { return dismountKey.get().isSet() ? dismountKey.get().toString() : "sneak"; }

    /** Shown when this client takes the driver's seat. */
    protected String boardedMessage(String dismount) {
        return "Boat ready: WASD steers, jump rises, sprint descends, " + dismount + " dismounts.";
    }

    private void detach() {
        if (boat != null && driver) {
            boolean deliberate = sameSession() && player.isAlive() && player.getRootVehicle()!=boat && recentSneak();
            if (sameSession()) remount.intent(boat.getId(), player.tickCount,
                remountRetry.get() || supported(boat) || player.tickCount - lastGroundedAge <= 5, deliberate,
                retryableFallback(), remountRetry.get() || landedRetry.get());
        }
        epoch++;
        connection = null;
        record("END tx=%d corrections=%d recentSneak=%b inputSneak=%b stillMounted=%b adaptiveFallback=%b", sentCount, corrections,
            recentSneak(), sentSneak, player != null && boat != null && player.getRootVehicle() == boat, adaptiveBlocked);
        if (log != null) { log.close(); log = null; }
        observations.clear();
        observationCount.set(0);
        lastPlayerCorrection = lastVehicleCorrection = lastInputPacket = lastObservationPacket = null;
        plannedMode = BoatPhaseTravel.Mode.LIMITED;
        lastFastVerticalTick = -100;
        meter.reset();
        if (boat != null && driver && !boat.isRemoved()) boat.setDeltaMovement(Vec3.ZERO);
        restorePausedModulesWhenSafe();
        boat = null;
        player = null;
        world = null;
        network = null;
        driver = correcting = false;
        plannedThroughBlocks = false;
        airborneTicks = 0;
        lastAdaptiveTick = -100;
        lastGroundedAge = -1000;
        lastStep = Vec3.ZERO;
        lastWire = plannedFrom = null;
        plannedTick = -1;
        motion.reset();
    }

    private final BoatModuleRestoreGate moduleRestoreGate = new BoatModuleRestoreGate();
    private final PendingModuleRestore pendingModuleRestore = new PendingModuleRestore();
    private boolean restoreSubscribed;

    /** Profiles preserve the user's pre-trip enabled preferences for movement conflicts. */
    protected final void restorePausedInSavedConfig(net.minecraft.nbt.CompoundTag root) {
        dev.tntcoating.core.BorrowedModuleConfig.restorePaused(root,
            paused.stream().map(module -> module.name).collect(java.util.stream.Collectors.toSet()));
    }

    /** Loaded module flags replace the old trip's pending activation preferences. */
    protected final void discardRestorationForProfileLoad() {
        paused.clear();
        if (restoreSubscribed) MeteorClient.EVENT_BUS.unsubscribe(pendingModuleRestore);
        restoreSubscribed = false;
    }

    private boolean canRestorePausedModules() {
        boolean ready = mc.player != null && mc.level != null && mc.getConnection() != null && mc.player.isAlive();
        boolean controlling = ready && isActive() && mc.player.getRootVehicle() instanceof AbstractBoat candidate
            && candidate.getControllingPassenger() == mc.player;
        return moduleRestoreGate.mayRestore(ready, controlling);
    }

    private void restorePausedModulesWhenSafe() {
        if (!paused.isEmpty() && canRestorePausedModules()) {
            for (Module module : new ArrayList<>(paused)) {
                if (!module.isActive()) module.enable();
                paused.remove(module);
            }
        }
        if (!moduleRestoreGate.needsListener(!paused.isEmpty())) {
            if (restoreSubscribed) MeteorClient.EVENT_BUS.unsubscribe(pendingModuleRestore);
            restoreSubscribed = false;
        } else if (!restoreSubscribed) {
            // The engine may be disabled while disconnected; this listener survives until a safe rejoin.
            restoreSubscribed = true;
            MeteorClient.EVENT_BUS.subscribe(pendingModuleRestore);
        }
    }

    private final class PendingModuleRestore {
        @EventHandler private void left(GameLeftEvent event) { moduleRestoreGate.worldLeft(); }
        @EventHandler private void joined(GameJoinedEvent event) { moduleRestoreGate.worldJoined(); }
        @EventHandler private void tick(TickEvent.Post event) { restorePausedModulesWhenSafe(); }
    }

    private void pauseConflicts() {
        for (String name : CONFLICTS) {
            Module module = Modules.get().get(name);
            if (module != null && module != this && module.isActive()) {
                module.toggle();
                if (!paused.contains(module)) paused.add(module);
                record("PAUSE module=%s", module.name);
            }
        }
    }

    private void recover() {
        meter.reset();
        lastStep = Vec3.ZERO;
        airborneTicks = 0;
        if (!adaptiveBlocked && !recentSneak() && motion.tick() - lastAdaptiveTick <= 10) {
            adaptiveBlocked = true;
            record("ADAPTIVE-FALLBACK lastAdaptiveTick=%d", lastAdaptiveTick);
            warning("Adaptive phase corrected; slow step for this ride. Board again or use Resume adaptive phase to retry.");
        }
        motion.corrected(correctionHold.get());
    }

    @Override
    public WWidget getWidget(GuiTheme theme) { return speedRecoveryWidget(theme); }

    /** Current speed mode plus the two manual overrides for server fallbacks. */
    protected final WWidget speedRecoveryWidget(GuiTheme theme) {
        WTable table = theme.table();
        String fallbacks = (adaptiveBlocked ? " · adaptive phase off for this ride" : "")
            + (verticalRejected ? " · vertical limited" : "");
        table.add(theme.label("Speed: " + modeDescription() + fallbacks)).expandX();
        table.row();
        var buttons = table.add(theme.horizontalList()).expandX().widget();
        buttons.add(theme.button("Resume adaptive phase")).widget().action = () -> {
            adaptiveBlocked = false;
            lastAdaptiveTick = -100;
            record("ADAPTIVE-RESUME limitedSpeed=%.3f", limitedSpeed.get());
            info("Adaptive fallback cleared. The adaptive-phase setting still controls whether it is enabled.");
        };
        buttons.add(theme.button("Retry fast air")).widget().action = () -> {
            travel.retry();
            verticalRejected = false;
            lastStep = Vec3.ZERO;
            record("SPEED-RETRY mode=%s", modeDescription());
            info("Speed fallback cleared. %s", modeDescription());
        };
        return table;
    }

    @EventHandler(priority = EventPriority.LOWEST - 100)
    private void onSend(PacketEvent.Send event) {
        if (!event.isCancelled() && mc.isSameThread() && mc.player != null && mc.level != null
            && mc.getConnection() != null && event.connection == mc.getConnection().getConnection()
            && !mc.player.isPassenger() && event.packet instanceof ServerboundInteractPacket interaction
            && lastBoardInteraction != interaction) {
            lastBoardInteraction = interaction;
            if (mc.level != null && mc.level.getEntity(interaction.entityId()) instanceof AbstractBoat candidate) {

                boolean grounded = supported(candidate, .35);
                boolean armed = remount.intent(candidate.getId(), mc.player.tickCount, grounded || remountRetry.get(), true,
                    retryableFallback(), remountRetry.get() || landedRetry.get());
                boardingRetryReason = armed ? "deliberate boarding" : !landedRetry.get() && !remountRetry.get() ? "retry disabled"
                    : !retryableFallback() ? "no flight rejection; terrain limits are separate"
                    : "boat not landed";
            }
        }
        if (event.isCancelled() || !(event.packet instanceof ServerboundMoveVehiclePacket packet) || boat == null || !owns(boat) || event.connection != connection || correcting) return;
        if (packet == substepPacket) return;
        if (splitting) { event.cancel(); return; }
        if (motion.alreadySent()) {
            record("SUPPRESS extra vehicle movement");
            event.cancel();
            return;
        }
        if (lastWire != null) {
            Vec3 delta = packet.position().subtract(lastWire);
            boolean horizontalBatch = (plannedMode == BoatPhaseTravel.Mode.SUBSTEPS)
                && !plannedThroughBlocks;
            int horizontalCount = horizontalBatch ? BoatPhaseAirSteps.count(delta.horizontalDistance()) : 1;
            int verticalCount = BoatPhaseVertical.count(delta.y);
            int count = Math.max(horizontalCount, verticalCount);

            if (horizontalCount == 0 || verticalCount == 0 || count > 1 && (plannedTick != motion.tick()
                || plannedFrom == null || plannedFrom.distanceTo(lastWire) > 1e-4 || !plannedClear && !plannedThroughBlocks)) {
                event.cancel(); boat.setPos(lastWire); boat.setDeltaMovement(Vec3.ZERO);
                record("STEPS-STOP unverified sweep or packet budget; oversized update suppressed");
                return;
            }
            if (count > 1) {
                event.cancel();
                splitting = true;
                substepOrigin = lastWire;
                try {
                    for (int i = 1; i <= count; i++) {
                        Vec3 end = substepOrigin.add(delta.scale((double)i/count));
                        substepPacket = new ServerboundMoveVehiclePacket(end, packet.yRot(), packet.xRot(), packet.onGround());
                        network.send(substepPacket);
                        if (lastWire.distanceTo(end) > 1e-5) break;
                    }
                } finally { substepPacket = null; splitting = false; }
                motion.sent(point(substepOrigin), point(lastWire));
                lastStep = lastWire.subtract(substepOrigin);
                if (Math.abs(lastStep.y) > BoatPhaseVertical.STEP + 1e-6) {
                    lastFastVerticalTick = motion.tick();
                    travel.newRide();
                } else if (horizontalBatch) {
                    if (lastStep.horizontalDistance() > limitedSpeed.get() + 1e-6) lastFastVerticalTick = -100;
                    travel.sent(BoatPhaseTravel.Mode.SUBSTEPS,
                        motion.tick(), lastStep.horizontalDistance(), limitedSpeed.get());
                }
                if (lastWire.distanceTo(packet.position()) > 1e-5) {
                    boat.setPos(lastWire);
                    boat.setDeltaMovement(Vec3.ZERO);
                    record("SUBSTEP-INTERRUPTED position=%s", lastWire);
                }
                record("AIR-STEPS count=%d total=%s horizontal=%.3f", count, lastStep, lastStep.horizontalDistance());
            }
        }
    }

    @EventHandler(priority = EventPriority.LOWEST - 100)
    private void onSent(PacketEvent.Sent event) {
        if (event.packet instanceof ServerboundPlayerInputPacket input && boat != null && sameSession() && event.connection == connection) {
            if (lastInputPacket == input) return;
            lastInputPacket = input;
            sentSneak = input.input().shift();
            if (sentSneak) lastSneakTick = motion.tick();
            record("INPUT %s", input.input());
        }
        if (!(event.packet instanceof ServerboundMoveVehiclePacket packet) || boat == null || !owns(boat) || event.connection != connection) return;

        if (!eventGate.vehicle(packet)) return;
        if (packet == substepPacket && splitting) {
            meter.sent(motion.tick(), System.nanoTime(), lastWire.x, lastWire.z, packet.position().x, packet.position().y, packet.position().z);
            record("SUBSTEP-WIRE from=%s to=%s delta=%s", lastWire, packet.position(), packet.position().subtract(lastWire));
            lastWire = packet.position();
            sentCount++;
            return;
        }
        if (motion.alreadySent()) return;
        if (correcting) {
            motion.acknowledged();
            record("CORRECTION-ACK position=%s", packet.position());
            return;
        }
        Vec3 from = lastWire == null ? packet.position() : lastWire;
        motion.sent(point(from), point(packet.position()));
        lastWire = packet.position();
        lastStep = lastWire.subtract(from);
        meter.sent(motion.tick(), System.nanoTime(), from.x, from.z, lastWire.x, lastWire.y, lastWire.z);
        BoatPhaseTravel.Mode sentMode = plannedTick == motion.tick() && !plannedThroughBlocks ? plannedMode : BoatPhaseTravel.Mode.LIMITED;
        travel.sent(sentMode, motion.tick(), lastStep.horizontalDistance(), limitedSpeed.get());
        if (sentMode != BoatPhaseTravel.Mode.LIMITED && lastStep.horizontalDistance() > limitedSpeed.get() + 1e-6) lastFastVerticalTick = -100;
        sentCount++;
        record("WIRE from=%s to=%s delta=%s local=%s plannedFrom=%s plannedTick=%d inside=%b passengers=%d ground=%b airTicks=%d limitedSpeed=%.3f fallback=%b status=%s",
            from, lastWire, lastWire.subtract(from), boat.position(), plannedFrom, plannedTick,
            !world.noCollision(boat, boat.getBoundingBox()), boat.getPassengers().size(), packet.onGround(), airborneTicks, limitedSpeed.get(), adaptiveBlocked, status);
    }

    public void beforeVehicleCorrection(ClientPacketListener handler, ClientboundMoveVehiclePacket packet) {
        if (handler != network || boat == null || !owns(boat)) return;
        if (lastVehicleCorrection == packet) return;
        lastVehicleCorrection = packet;
        rejectFastAir();
        correcting = true;
        corrections++;
        record("VEHICLE-CORRECTION server=%s local=%s lastWire=%s error=%.3f", packet.position(), boat.position(), lastWire,
            packet.position().distanceTo(boat.position()));
        recover();
    }

    public void afterVehicleCorrection(ClientPacketListener handler, ClientboundMoveVehiclePacket packet) {
        if (handler != network || !correcting) return;
        lastWire = packet.position();
        if (boat != null) boat.setDeltaMovement(Vec3.ZERO);
        correcting = false;
        status = "server correction";
        if (corrections <= 3 || corrections % 10 == 0) warning("Server corrected the boat (#%d); pausing %d ticks.", corrections, correctionHold.get());
    }

    @EventHandler
    private void onPlayerCorrectionBefore(BoatPlayerCorrectionEvent.Before event) {
        if (lastPlayerCorrection == event.packet) return;
        lastPlayerCorrection = event.packet;
        if (boat == null || !driver || !sameSession()) return;
        if (!recentSneak()) rejectFastAir();
        correcting = true;
        corrections++;
        recover();
        record("PLAYER-CORRECTION packet=%s recentSneak=%b inputSneak=%b", event.packet, recentSneak(), sentSneak);
    }

    @EventHandler
    private void onPlayerCorrectionAfter(BoatPlayerCorrectionEvent.After event) {
        if (!correcting) return;
        correcting = false;
        if (boat != null) { lastWire = boat.position(); boat.setDeltaMovement(Vec3.ZERO); }
    }

    @EventHandler
    private void onReceive(PacketEvent.Receive event) {
        long observedEpoch = epoch;
        Connection owner = connection;
        if (owner == null || event.connection != owner) return;
        if (!(event.packet instanceof ClientboundSetPassengersPacket || event.packet instanceof ClientboundEntityPositionSyncPacket
            || event.packet instanceof ClientboundTeleportEntityPacket || event.packet instanceof ClientboundMoveEntityPacket)) return;
        if (observationCount.incrementAndGet() > 256) { observationCount.decrementAndGet(); return; }
        observations.add(new Observation(observedEpoch, owner, event.packet));
    }

    private void drainObservations() {
        Observation entry;
        while ((entry = observations.poll()) != null) {
            observationCount.updateAndGet(n -> Math.max(0, n - 1));
            if (entry.epoch != epoch || entry.connection != connection || boat == null || !sameSession()) continue;
            Packet<?> packet = entry.packet;
            if (lastObservationPacket == packet) continue;
            lastObservationPacket = packet;
            if (packet instanceof ClientboundSetPassengersPacket p && p.getVehicle() == boat.getId()) {
                record("SERVER-PASSENGERS ids=%s", java.util.Arrays.toString(p.getPassengers()));
            } else if (packet instanceof ClientboundEntityPositionSyncPacket p && p.id() == boat.getId()) {
                record("SERVER-BOAT-SYNC values=%s", p.values());
                Vec3 pos = p.values().position();
                meter.echo(motion.tick(), System.nanoTime(), pos.x, pos.y, pos.z);
            } else if (packet instanceof ClientboundTeleportEntityPacket p && p.id() == boat.getId()) {
                record("SERVER-BOAT-POSITION values=%s relatives=%s", p.change(), p.relatives());
            } else if (packet instanceof ClientboundMoveEntityPacket p && p.getEntity(world) == boat && p.hasPosition()) {
                record("SERVER-BOAT-DELTA x=%d y=%d z=%d local=%s", p.getXa(), p.getYa(), p.getZa(), boat.position());
            }
        }
    }

    private void openLog() {
        if (!debugFile.get()) return;
        try {
            Path folder = mc.gameDirectory.toPath().resolve("comet-boat-fly");
            Files.createDirectories(folder);
            String time = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss-SSS"));
            Path path = folder.resolve("boat-" + time + ".log");
            log = new PrintWriter(Files.newBufferedWriter(path, StandardCharsets.UTF_8));
            record("COMET-BOAT-FLY server=%s role=%s", mc.getCurrentServer() == null ? "local" : mc.getCurrentServer().ip, driver ? "driver" : "passenger");
            for (SettingGroup group : settings) for (Setting<?> setting : group) record("cfg %s = %s", setting.name, setting.get());
            info("Boat log: %s", path);
        } catch (IOException e) {
            MeteorClient.LOG.warn("Cannot open {} log", title, e);
        }
    }

    private void record(String format, Object... args) {
        if (log != null) log.printf(Locale.ROOT, "[%d] %s%n", motion.tick(), String.format(Locale.ROOT, format, args));
    }

    private static BoatPhaseMotion.Point point(Vec3 v) { return new BoatPhaseMotion.Point(v.x, v.y, v.z); }

    private boolean recentSneak() {
        return motion.tick() - lastSneakTick <= 10 || (sameSession() && mc.player != null && dismountPressed());
    }

    private void rejectFastAir() {
        if (motion.tick() - lastFastVerticalTick >= 0 && motion.tick() - lastFastVerticalTick <= 20) {
            verticalRejected = true;
            lastFastVerticalTick = -100;
            travel.newRide();
            record("VERTICAL-REJECTED next=%.2f", travelVerticalSpeed());
            warning("Fast vertical movement corrected; using at most 9 vertical b/t until reboarding or Retry fast air.");
            return;
        }
        BoatPhaseTravel.Mode rejected = travel.corrected(motion.tick());
        if (rejected == BoatPhaseTravel.Mode.LIMITED) return;
        record("SPEED-REJECTED mode=%s next=%s", rejected, modeDescription());
        warning("%s corrected: using the limited speed. Retry fast air or re-board to retry.", rejected);
    }

    private long rideStartedNanos;

    private boolean retryableFallback() {
        return remountRetry.get() ? travel.flightRejected() || verticalRejected : travel.rejected(BoatPhaseTravel.Mode.SUBSTEPS);
    }

    private void retryConfiguredFlight() {
        travel.retryFlight();
        verticalRejected = false;
        lastFastVerticalTick = -100;
        lastStep = Vec3.ZERO;
        motion.corrected(4);
    }

    private String modeDescription() {
        String mode = switch (travelMode()) {
            case SUBSTEPS -> "Air substeps";
            case LIMITED -> speedFallback() ? "Speed fallback" : "Limited";
        };
        return String.format(Locale.ROOT, "%s | cap %.2f b/t", mode, travelSpeed());
    }

    /** Stable status/telemetry API for the boat controllers and the dashboard. */
    public String engineStatus() { return automationPaused ? "automation paused" : status; }
    public String speedDescription() { return modeDescription(); }
    public int correctionCount() { return corrections; }
    public double sentSpeedBps() { return meter.sentBps(); }
    public boolean isBoatDriver() { return boat != null && owns(boat); }

    @Override
    public String getInfoString() {
        return boat == null ? status : String.format(Locale.ROOT, "%s | %.2f b/t sent", status, meter.sentBpt());
    }
}
