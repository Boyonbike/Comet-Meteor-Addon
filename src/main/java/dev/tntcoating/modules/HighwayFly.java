/*
 * Comet highway flight and travel helpers.
 * SPDX-License-Identifier: GPL-3.0-only
 */

package dev.tntcoating.modules;

import dev.tntcoating.TntCoatingAddon;
import dev.tntcoating.compat.BaritoneBridge;
import dev.tntcoating.modules.autowasp.AutoWaspChestSwap;
import dev.tntcoating.core.AdaptiveFlightController.Motion;
import dev.tntcoating.core.AdaptiveFlightEngine;
import dev.tntcoating.core.AltitudeHold;
import dev.tntcoating.core.BaritoneFlight;
import dev.tntcoating.core.ElytraReplacement;
import dev.tntcoating.core.ElytraTakeoff;
import dev.tntcoating.core.FlightCorridor;
import dev.tntcoating.core.HighwayCenter;
import dev.tntcoating.core.HighwayCourse;
import dev.tntcoating.core.DiscordWebhook;
import dev.tntcoating.core.HighwayDetour;
import dev.tntcoating.core.HighwayFlySession;
import dev.tntcoating.core.HighwayFlyConfigMigration;
import dev.tntcoating.core.HighwayPathfinder;
import dev.tntcoating.core.HighwayNavigation;
import dev.tntcoating.core.HighwayPortalCrossing;
import dev.tntcoating.core.HighwayFlightSafety;
import dev.tntcoating.core.HighwayLavaSafety;
import dev.tntcoating.core.HighwayTravelSafety;
import dev.tntcoating.core.HighwayWalkingProgress;
import dev.tntcoating.core.HighwayHealthUpdates;
import dev.tntcoating.core.HighwayReconnectBackoff;
import dev.tntcoating.core.HighwayRecoveryTargets;
import dev.tntcoating.core.HighwayRouteLine;
import dev.tntcoating.core.HighwayWebhook;
import dev.tntcoating.core.HighwayWebhookLifecycle;
import dev.tntcoating.core.HighwayWebhookPolicy;
import dev.tntcoating.core.TemporaryModulePause;
import dev.tntcoating.core.TripEventLog;
import dev.tntcoating.core.dashboard.HighwayTelemetry;
import dev.tntcoating.core.HighwayProbeCache;
import dev.tntcoating.core.PortalLookahead;
import dev.tntcoating.core.ServerGlide;
import dev.tntcoating.core.SetHomeScheduler;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import meteordevelopment.meteorclient.MeteorClient;
import meteordevelopment.meteorclient.events.entity.player.PlayerMoveEvent;
import meteordevelopment.meteorclient.events.game.GameJoinedEvent;
import meteordevelopment.meteorclient.events.game.GameLeftEvent;
import meteordevelopment.meteorclient.events.game.OpenScreenEvent;
import meteordevelopment.meteorclient.events.world.ServerConnectBeginEvent;
import meteordevelopment.meteorclient.events.packets.PacketEvent;
import meteordevelopment.meteorclient.events.world.TickEvent;
import meteordevelopment.meteorclient.gui.GuiTheme;
import meteordevelopment.meteorclient.gui.widgets.WWidget;
import meteordevelopment.meteorclient.mixininterface.IVec3;
import meteordevelopment.meteorclient.settings.BlockPosSetting;
import meteordevelopment.meteorclient.settings.BoolSetting;
import meteordevelopment.meteorclient.settings.DoubleSetting;
import meteordevelopment.meteorclient.settings.EnumSetting;
import meteordevelopment.meteorclient.settings.IntSetting;
import meteordevelopment.meteorclient.settings.Setting;
import meteordevelopment.meteorclient.settings.SettingGroup;
import meteordevelopment.meteorclient.settings.StringSetting;
import meteordevelopment.meteorclient.systems.modules.Module;
import meteordevelopment.meteorclient.systems.modules.Modules;
import meteordevelopment.meteorclient.systems.modules.misc.AutoReconnect;
import meteordevelopment.meteorclient.systems.modules.movement.AutoWalk;
import meteordevelopment.meteorclient.systems.modules.movement.AirJump;
import meteordevelopment.meteorclient.systems.modules.movement.Blink;
import meteordevelopment.meteorclient.systems.modules.movement.Flight;
import meteordevelopment.meteorclient.systems.modules.movement.LongJump;
import meteordevelopment.meteorclient.systems.modules.movement.NoFall;
import meteordevelopment.meteorclient.systems.modules.movement.Scaffold;
import meteordevelopment.meteorclient.systems.modules.movement.speed.Speed;
import meteordevelopment.meteorclient.systems.modules.movement.elytrafly.ElytraFly;
import meteordevelopment.meteorclient.systems.modules.render.Freecam;
import meteordevelopment.meteorclient.utils.Utils;
import meteordevelopment.meteorclient.utils.player.InvUtils;
import meteordevelopment.orbit.EventHandler;
import meteordevelopment.orbit.EventPriority;
import net.minecraft.client.gui.screens.TitleScreen;
import net.minecraft.client.gui.screens.multiplayer.JoinMultiplayerScreen;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.component.DataComponents;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.Connection;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.network.protocol.common.ClientboundDisconnectPacket;
import net.minecraft.network.protocol.game.ClientboundLoginPacket;
import net.minecraft.network.protocol.game.ClientboundPlayerPositionPacket;
import net.minecraft.network.protocol.game.ClientboundPlayerCombatKillPacket;
import net.minecraft.network.protocol.game.ClientboundSetHealthPacket;
import net.minecraft.network.protocol.game.ClientboundRespawnPacket;
import net.minecraft.network.protocol.game.ClientboundBlockUpdatePacket;
import net.minecraft.network.protocol.game.ClientboundSectionBlocksUpdatePacket;
import net.minecraft.network.protocol.game.ClientboundLevelChunkWithLightPacket;
import net.minecraft.network.protocol.game.ClientboundForgetLevelChunkPacket;
import net.minecraft.network.protocol.game.ServerboundPlayerCommandPacket;
import net.minecraft.tags.FluidTags;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.MoverType;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.PositionMoveRotation;
import net.minecraft.world.entity.boss.enderdragon.EndCrystal;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.enchantment.EnchantmentEffectComponents;
import net.minecraft.world.item.enchantment.EnchantmentHelper;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.NetherPortalBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.shapes.VoxelShape;

/** Flies a locked straight line with elytra physics and small local detours. */
public final class HighwayFly extends Module {
    private static final double HEADROOM = 1;
    private static final int SETBACK_STOP_DISTANCE = 64;
    private static final int DASHBOARD_PATH_LIMIT = 256;
    private static final double ALTITUDE_GAIN = .05;
    private static final double ALTITUDE_DAMPING = .1;
    private static final double ALTITUDE_DEADBAND = .25;
    private static final double ALTITUDE_MAX_PITCH = 30;
    private static final double LATERAL_GAIN = .35;
    private static final int RETRY_TICKS = 20;
    private static final double CLIMB_TOLERANCE = .5;
    private static final int CLIMB_STALL_TICKS = 60;
    private static final int PATH_FORWARD_STEPS = 32;
    private static final double PATH_FORWARD_STEP = 2;
    private static final double PATH_LATERAL_STEP = 1;
    private static final double PATH_VERTICAL_STEP = 1;
    private static final int WALK_TIMEOUT_TICKS = 20 * 30;
    private static final double RUBBERBAND_MIN_DISTANCE = 1.5;

    public enum State { TAKING_OFF, CLIMBING, CRUISE, DETOUR, PATHING, WAIT_CHUNKS, BLOCKED, WALKING, LANDING, DONE }

    public enum PortalMode { Avoid, Pass, Stop }

    /** How auto-set-home decides when to send the next command. */
    public enum HomeTiming {
        AUTO("Auto detect"),
        FIXED("Fixed timer");

        private final String label;

        HomeTiming(String label) {
            this.label = label;
        }

        @Override
        public String toString() {
            return label;
        }
    }

    /** Shared countdowns and durable history, including while the helper is paused. */
    public record AutoSetHomeStatus(String mode, String home, boolean waiting, long waitMillis,
                                    long intervalMillis, boolean fixedTimer, String cooldownLabel,
                                    boolean cooldownKnown, SetHomeScheduler.Status schedule,
                                    boolean paused, String account, String server,
                                    List<SetHomeScheduler.Entry> history) {}

    /** Snapshot for the Highway Fly status and runtime HUDs. */
    public record TripStatus(String state, String action, String detail, boolean started,
                             long tripNanos, long activeNanos) {}

    // Keep group creation in the order people configure a trip; saved names migrate before loading.
    private final SettingGroup general = settings.createGroup("Route", false);
    private final SettingGroup flight = settings.createGroup("Flight & Altitude", false);
    private final SettingGroup altitude = flight;
    private final SettingGroup detour = settings.createGroup("Navigation & Recovery", false);
    private final SettingGroup walking = detour;
    private final SettingGroup portals = detour;
    private final SettingGroup elytra = settings.createGroup("Elytra & Safety", false);
    private final SettingGroup safety = elytra;
    private final SettingGroup integrations = settings.createGroup("Integrations", false);
    private final SettingGroup homeGroup = integrations;
    private final SettingGroup lobbyGroup = integrations;
    private final SettingGroup camera = settings.createGroup("Camera & Controls", false);
    private final SettingGroup compatibility = camera;
    final SettingGroup dashboardGroup = settings.createGroup("Dashboard", false);
    private final SettingGroup webhookGroup = settings.createGroup("Webhooks", false);
    private final SettingGroup webhookEvents = webhookGroup;
    private final SettingGroup webhookTiming = webhookGroup;
    private final SettingGroup diagnostics = settings.createGroup("Diagnostics", false);
    final Setting<BlockPos> destination = general.add(new BlockPosSetting.Builder()
        .name("destination")
        .description("The block to fly to. Its Y is ignored for the line; target-y sets the altitude.")
        .defaultValue(new BlockPos(0, 120, 1000))
        .build());
    private final Setting<Integer> arrivalDistance = general.add(new IntSetting.Builder()
        .name("arrival-distance")
        .description("Horizontal distance at which the flight starts landing.")
        .defaultValue(32).min(1).max(256).sliderRange(1, 128)
        .build());
    private final Setting<Boolean> landOnArrival = general.add(new BoolSetting.Builder()
        .name("land-on-arrival")
        .description("Descend to the destination instead of stopping in the air.")
        .defaultValue(true)
        .build());
    private final Setting<Boolean> freeLook = camera.add(new BoolSetting.Builder()
        .name("free-look")
        .description("Let the mouse look around without changing the flight heading. The camera turns only; the player body and movement keep the autopilot heading.")
        .defaultValue(true)
        .build());
    private final Setting<Boolean> disableMouseInputs = camera.add(new BoolSetting.Builder()
        .name("disable-mouse-look")
        .description("Ignore mouse look while the elytra is flying, so the controller owns the camera. Mouse buttons, scroll and GUI mouse use still work.")
        .defaultValue(true)
        .build());
    final Setting<Boolean> lockAltitude = altitude.add(new BoolSetting.Builder()
        .name("lock-altitude")
        .description("Hold target-y instead of following the destination block's Y.")
        .defaultValue(true)
        .build());
    final Setting<Integer> targetY = altitude.add(new IntSetting.Builder()
        .name("target-y")
        .description("Preferred flight altitude. The controller holds this Y unless a detour needs another one.")
        .defaultValue(120).min(0).max(255).sliderRange(5, 125)
        .build());
    private final Setting<Integer> minY = altitude.add(new IntSetting.Builder()
        .name("min-y")
        .description("Lowest altitude a detour may use.")
        .defaultValue(5).min(0).max(255).sliderRange(0, 125)
        .build());
    private final Setting<Integer> maxY = altitude.add(new IntSetting.Builder()
        .name("max-y")
        .description("Highest altitude a detour may use.")
        .defaultValue(125).min(0).max(255).sliderRange(5, 255)
        .build());
    private final Setting<Double> verticalStep = detour.add(new DoubleSetting.Builder()
        .name("vertical-step")
        .description("Step between candidate altitudes when the line is blocked.")
        .defaultValue(2).range(1, 16).sliderRange(1, 8).decimalPlaces(1)
        .build());
    private final Setting<Double> speedBps = flight.add(new DoubleSetting.Builder()
        .name("speed-bps")
        .description("Horizontal speed cap in blocks per second. Terrain, turns and chunk loading can slow the flight.")
        .defaultValue(60).range(20, 340).sliderRange(20, 340).decimalPlaces(1)
        .build());
    private final Setting<Double> verticalSpeedBps = flight.add(new DoubleSetting.Builder()
        .name("vertical-speed-bps")
        .description("Climb and descent speed cap in blocks per second.")
        .defaultValue(60).range(0, 60).sliderRange(0, 60).decimalPlaces(1)
        .build());
    private final Setting<Integer> lookahead = detour.add(new IntSetting.Builder()
        .name("lookahead")
        .description("How far ahead the line controller aims while returning from a detour.")
        .defaultValue(48).min(8).max(128).sliderRange(8, 128)
        .build());
    private final Setting<Integer> probeDistance = detour.add(new IntSetting.Builder()
        .name("probe-distance")
        .description("How far ahead obstacles are checked. Capped at the corridor's 64 blocks.")
        .defaultValue(48).min(8).max(64).sliderRange(8, 64)
        .build());
    private final Setting<Double> turnRate = flight.add(new DoubleSetting.Builder()
        .name("turn-rate")
        .description("Maximum yaw change per tick while turning back to the line or around an obstacle.")
        .defaultValue(15).range(1, 45).sliderRange(1, 30).decimalPlaces(1)
        .build());
    private final Setting<Double> maxDetour = detour.add(new DoubleSetting.Builder()
        .name("max-detour")
        .description("Largest yaw offset attempted around an obstacle.")
        .defaultValue(45).range(0, 60).sliderRange(0, 45).decimalPlaces(1)
        .build());
    private final Setting<Double> detourStep = detour.add(new DoubleSetting.Builder()
        .name("detour-step")
        .description("Yaw increment between lateral detour attempts.")
        .defaultValue(5).range(1, 15).sliderRange(1, 15).decimalPlaces(1)
        .build());
    private final Setting<Integer> detourHold = detour.add(new IntSetting.Builder()
        .name("detour-hold-ticks")
        .description("Ticks to keep a chosen detour heading before trying the permanent line again.")
        .defaultValue(8).min(1).max(40).sliderRange(1, 40)
        .build());
    private final Setting<Double> detourWidth = detour.add(new DoubleSetting.Builder()
        .name("detour-width")
        .description("Total clear width the detour probe requires. 0.6 is the player's own body; 1.2 keeps 0.3 blocks clear on each side.")
        .defaultValue(1.2).range(0.6, 8).sliderRange(0.6, 4).decimalPlaces(2)
        .build());
    private final Setting<Boolean> pathfind = detour.add(new BoolSetting.Builder()
        .name("pathfind")
        .description("Search the highway cross-section for a short path around a blocked line instead of only trying yaw offsets.")
        .defaultValue(true)
        .build());
    private final Setting<Integer> corridorWidth = detour.add(new IntSetting.Builder()
        .name("corridor-width")
        .description("Usable highway width in blocks. A 5-wide highway uses five lateral path cells.")
        .defaultValue(5).min(3).max(9).sliderRange(3, 9)
        .visible(pathfind::get)
        .build());
    private final Setting<Integer> corridorHeight = detour.add(new IntSetting.Builder()
        .name("corridor-height")
        .description("Usable highway height in blocks. A 3-tall highway uses three vertical path levels.")
        .defaultValue(3).min(2).max(5).sliderRange(2, 5)
        .visible(pathfind::get)
        .build());
    private final Setting<Double> pathSpeedBps = detour.add(new DoubleSetting.Builder()
        .name("path-speed-bps")
        .description("Speed cap while following a tight corridor path. Slow enough to line up a one-block gap; the normal speed resumes on the clear line.")
        .defaultValue(8).range(1, 300).sliderRange(1, 64).decimalPlaces(1)
        .visible(pathfind::get)
        .build());
    private final Setting<PortalMode> portalMode = portals.add(new EnumSetting.Builder<PortalMode>()
        .name("portal-mode")
        .description("Avoid portals, pass through them at speed, or stop before them.")
        .defaultValue(PortalMode.Avoid)
        .build());
    private final Setting<Integer> portalDistance = portals.add(new IntSetting.Builder()
        .name("portal-distance")
        .description("Distance at which a portal is detected for stop/pass handling.")
        .defaultValue(16).min(4).max(32).sliderRange(4, 32)
        .visible(() -> portalMode.get() != PortalMode.Avoid)
        .build());
    private final Setting<Boolean> swapElytra = elytra.add(new BoolSetting.Builder()
        .name("swap-elytra")
        .description("Put on the best elytra in your inventory when the flight starts, swap in a spare when the worn one is nearly broken, and log out if there is no spare left.")
        .defaultValue(true)
        .build());
    private final Setting<Boolean> swapBack = elytra.add(new BoolSetting.Builder()
        .name("swap-back")
        .description("Wear an available chestplate during grounded walking or lobby recovery, and put the displaced chestplate back on after landing.")
        .defaultValue(true)
        .visible(swapElytra::get)
        .build());
    private final Setting<Boolean> autoTakeoff = flight.add(new BoolSetting.Builder()
        .name("auto-takeoff")
        .description("Jump and open the elytra when the flight starts on the ground.")
        .defaultValue(true)
        .build());
    private final Setting<Integer> minDurability = elytra.add(new IntSetting.Builder()
        .name("min-durability")
        .description("Swap in a spare elytra, or log out if there is none, when the worn one has this many durability points left.")
        .defaultValue(10).min(1).max(100).sliderRange(1, 100)
        .build());
    private final Setting<Boolean> stopBeforeLava = safety.add(new BoolSetting.Builder()
        .name("stop-before-lava")
        .description("Try a safe flying route over or around lava, then hand it to Baritone if flight cannot pass. Only actual lava immersion causes a lava logout.")
        .defaultValue(true)
        .build());
    private final Setting<Double> minimumHealth = safety.add(new DoubleSetting.Builder()
        .name("minimum-health")
        .description("Log out at or below this much remaining red health (2 points = 1 heart). Absorption is excluded; 0 disables.")
        .defaultValue(8).range(0, 20).sliderRange(0, 20).decimalPlaces(1)
        .build());
    private final Setting<Integer> noProgressTimeout = safety.add(new IntSetting.Builder()
        .name("no-progress-timeout")
        .description("Seconds without getting closer to the destination before trying Baritone, or logging out if recovery also cannot advance.")
        .defaultValue(30).min(10).max(300).sliderRange(10, 120)
        .build());
    private final Setting<Boolean> passEndCrystals = safety.add(new BoolSetting.Builder()
        .name("pass-end-crystals")
        .description("Ignore end crystal collision boxes instead of being stopped by one on the highway. Explosion damage is still real.")
        .defaultValue(true)
        .build());
    private final Setting<Boolean> recoverFromGround = flight.add(new BoolSetting.Builder()
        .name("recover-from-ground")
        .description("If the flight touches the ground before the destination, take off again instead of ending the trip.")
        .defaultValue(true)
        .build());
    private final Setting<Boolean> walkFallback = walking.add(new BoolSetting.Builder()
        .name("walk-fallback")
        .description("After the elytra attempts fail, land and let Baritone walk part of the blocked stretch before taking off again.")
        .defaultValue(true)
        .build());
    private final Setting<Integer> flightAttempts = walking.add(new IntSetting.Builder()
        .name("flight-attempts")
        .description("Consecutive elytra path attempts before the Baritone walk fallback.")
        .defaultValue(3).min(1).max(10).sliderRange(1, 10)
        .visible(walkFallback::get)
        .build());
    private final Setting<Integer> walkDistance = walking.add(new IntSetting.Builder()
        .name("walk-distance")
        .description("How far ahead along the route Baritone walks before the flight resumes after a blocked path.")
        .defaultValue(20).min(4).max(64).sliderRange(4, 64)
        .visible(walkFallback::get)
        .build());
    private final Setting<Boolean> rubberbandDetection = walking.add(new BoolSetting.Builder()
        .name("rubberband-detection")
        .description("Detect repeated server position corrections during flight and hand the blocked stretch to Baritone.")
        .defaultValue(true)
        .build());
    private final Setting<Integer> rubberbandThreshold = walking.add(new IntSetting.Builder()
        .name("rubberband-threshold")
        .description("Position corrections within the window before landing for a walk.")
        .defaultValue(2).min(1).max(10).sliderRange(1, 6)
        .visible(rubberbandDetection::get)
        .build());
    private final Setting<Integer> rubberbandWindowTicks = walking.add(new IntSetting.Builder()
        .name("rubberband-window-ticks")
        .description("Maximum ticks between corrections for them to count as one rubberband episode.")
        .defaultValue(60).min(20).max(200).sliderRange(20, 120)
        .visible(rubberbandDetection::get)
        .build());
    private final Setting<Integer> rubberbandWalkDistance = walking.add(new IntSetting.Builder()
        .name("rubberband-walk-distance")
        .description("How far ahead Baritone walks after repeated rubberbanding.")
        .defaultValue(10).min(4).max(64).sliderRange(4, 64)
        .visible(rubberbandDetection::get)
        .build());
    private final Setting<Boolean> debug = diagnostics.add(new BoolSetting.Builder()
        .name("debug")
        .description("Log detour and wait transitions.")
        .defaultValue(false)
        .build());

    private final ElytraTakeoff takeoff = new ElytraTakeoff();
    private final AtomicReference<ClientboundPlayerPositionPacket> correction = new AtomicReference<>();
    private final HighwayNavigation navigation = new HighwayNavigation();
    private final HighwayReconnectBackoff reconnectBackoff = new HighwayReconnectBackoff();
    private final AtomicInteger blockRevision = new AtomicInteger();
    private record ProbeBlock(BlockState state, AABB collision) {}
    private final HighwayProbeCache<ProbeBlock> probeCache = new HighwayProbeCache<>(4096);
    private final FlightCorridor.Probe strictProbe = new FlightCorridor.Probe() {
        @Override public boolean loaded(int x, int z) { return mc.level.getChunkSource().hasChunk(x, z); }
        @Override public boolean clear(AABB swept) { return clearSwept(swept, false); }
    };
    private final FlightCorridor.Probe moveProbe = new FlightCorridor.Probe() {
        @Override public boolean loaded(int x, int z) { return mc.level.getChunkSource().hasChunk(x, z); }
        @Override public boolean clear(AABB swept) { return clearSwept(swept, true); }
    };
    private final FlightCorridor.Probe floorProbe = new FlightCorridor.Probe() {
        @Override public boolean loaded(int x, int z) { return mc.level.getChunkSource().hasChunk(x, z); }
        @Override public boolean clear(AABB swept) { return !mc.level.getBlockCollisions(mc.player, swept).iterator().hasNext(); }
    };
    private final PortalLookahead.Probe portalProbe = new PortalLookahead.Probe() {
        @Override public boolean loaded(int x, int z) { return mc.level.getChunkSource().hasChunk(x, z); }
        @Override public boolean portal(BlockPos pos) { return mc.level.getBlockState(pos).is(Blocks.NETHER_PORTAL); }
    };
    private final HighwayLavaSafety.Probe lavaProbe = new HighwayLavaSafety.Probe() {
        @Override public boolean loaded(int x, int z) { return mc.level.getChunkSource().hasChunk(x, z); }
        @Override public boolean lava(BlockPos pos) { return mc.level.getFluidState(pos).is(FluidTags.LAVA); }
        @Override public AABB lavaBounds(BlockPos pos) {
            var fluid = mc.level.getFluidState(pos);
            return fluid.is(FluidTags.LAVA) ? fluid.getAABB(mc.level, pos) : null;
        }
    };

    private HighwayCourse course;
    private HighwayRouteLine walkingLine;
    private BaritoneFlight controller;
    private Vec3 lastControlledPosition;
    private String dimension;
    private State state = State.TAKING_OFF;
    private double preferredY;
    private double activeY;
    private double detourY;
    private float detourYawOffset;
    private int detourTicks;
    private double landingX;
    private double landingZ;
    private PortalLookahead.Hit portalHit;
    private boolean portalInside;
    private int retryTicks;
    private int climbTicks;
    private boolean warnedWaiting;
    private boolean warnedTakeoff;
    /** Inventory slot holding the chestplate auto-swap displaced, or -1 when there is nothing to restore. */
    private int chestplateSlot = -1;
    private boolean altitudeBlocked;
    private List<Vec3> path = List.of();
    private BaritoneBridge walkBaritone;
    private BlockPos walkTarget;
    private boolean walkStarted;
    private HighwayPortalCrossing portalCrossing;
    private int portalCrossingTicks;
    private boolean portalWalkPaused;
    private int walkTicks;
    private int walkBlocks;
    private int blockedAttempts;
    private int takeoffAttempts;
    private double lastProgressRemaining = Double.MAX_VALUE;
    private float flightYaw;
    private float flightPitch;
    private float lastYawStep;
    private float cameraYaw;
    private float cameraPitch;
    private int rubberbandCount;
    private int lastRubberbandTick;
    private int walkFailures;
    private int failedSwaps;
    private boolean deathNotified;
    private final AtomicBoolean deathPending = new AtomicBoolean();
    private final HighwayTravelSafety travelSafety = new HighwayTravelSafety();
    private final HighwayWalkingProgress walkingProgress = new HighwayWalkingProgress();
    private final HighwayHealthUpdates healthUpdates = new HighwayHealthUpdates(travelSafety);
    private boolean walkRecoveryAttempted;
    private boolean warnedLavaRecovery;
    private final HighwayWebhookLifecycle webhookLifecycle = new HighwayWebhookLifecycle();
    private final HighwayWebhookPolicy webhookPolicy = new HighwayWebhookPolicy();
    private final TemporaryModulePause<Module> pausedModules = new TemporaryModulePause<>(new TemporaryModulePause.Access<>() {
        @Override public boolean active(Module module) { return module.isActive(); }
        @Override public void toggle(Module module) { toggleConflictingModule(module); }
    });
    private PendingModuleRestore pendingModuleRestore;
    private boolean safetyDisconnect;
    private boolean leavingWorld;
    private HighwayWebhook.Context lastWebhookContext;
    boolean profileLoading;
    private boolean stoppingTrip;
    private boolean resumingRecovery;
    boolean durableTripAvailable;
    BlockPos tripDestination;
    int tripTargetY;
    private long connectedSince;
    private long tripStartedAtNanos;
    private long activeStartedAtNanos;
    private long activeNanos;

    final HighwayFlySession session = new HighwayFlySession();
    private final Setting<Boolean> lobbyRecovery = lobbyGroup.add(new BoolSetting.Builder()
        .name("lobby-recovery").description("Resume this trip after reconnecting, entering a lobby portal when needed.")
        .defaultValue(true).onChanged(this::onLobbyRecoveryChanged).build());
    private final Setting<Boolean> autoSetHome = homeGroup.add(new BoolSetting.Builder()
        .name("auto-set-home").description("Use Auto Set Home during this trip. Configure its timing and home in the independent module.")
        .defaultValue(true).onChanged(value -> syncHelpers()).build());
    private final Setting<Boolean> antiPortal = integrations.add(new BoolSetting.Builder()
        .name("anti-portal").description("Block portal travel during flight; allow the lobby recovery portal.")
        .defaultValue(true).onChanged(value -> syncHelpers()).build());
    private final Setting<Boolean> webhook = webhookGroup.add(new BoolSetting.Builder()
        .name("webhook")
        .description("Post trip events to Discord. Set webhook-url, then enable this.")
        .defaultValue(false).onChanged(this::onWebhookChanged).build());
    private final Setting<String> webhookUrl = webhookGroup.add(new StringSetting.Builder()
        .name("webhook-url")
        .description("Discord webhook URL from Channel settings → Integrations → Webhooks. Anyone with this URL can post in the channel, so keep it private.")
        .defaultValue("").visible(webhook::get).wide().build());
    private final Setting<String> webhookMention = webhookGroup.add(new StringSetting.Builder()
        .name("mention")
        .description("Optional Discord user or role ID to ping on attention and safety-logout events. Accepts a raw ID or a pasted mention; leave blank to never ping.")
        .defaultValue("").visible(webhook::get).build());
    private final Setting<Boolean> pauseOtherModules = compatibility.add(new BoolSetting.Builder()
        .name("pause-incompatible-modules")
        .description("Temporarily disable conflicting movement, packet and scaffold modules. Previously active modules are restored when Highway Fly stops.")
        .defaultValue(true).onChanged(value -> syncHelpers()).build());
    private final Setting<Boolean> webhookTrip = webhookEvents.add(new BoolSetting.Builder()
        .name("trip-events").description("Flight start/resume, arrival and manual trip stops.")
        .defaultValue(true).visible(webhook::get).build());
    private final Setting<Boolean> webhookHome = webhookEvents.add(new BoolSetting.Builder()
        .name("home-replaced-events").description("Send a check-in after each confirmed home replacement.")
        .defaultValue(true).visible(webhook::get).build());
    private final Setting<Boolean> webhookSafety = webhookEvents.add(new BoolSetting.Builder()
        .name("safety-logouts").description("Damage, actual lava immersion, durability and stalled recovery logouts.")
        .defaultValue(true).visible(webhook::get).build());
    private final Setting<Boolean> webhookDeath = webhookEvents.add(new BoolSetting.Builder()
        .name("death-events").description("Notify once if the player dies during this trip.")
        .defaultValue(true).visible(webhook::get).build());
    private final Setting<Boolean> webhookLava = webhookEvents.add(new BoolSetting.Builder()
        .name("lava-recovery-events").description("Notify when an impassable flying route is handed to Baritone around lava.")
        .defaultValue(true).visible(webhook::get).build());
    private final Setting<Boolean> webhookConnection = webhookEvents.add(new BoolSetting.Builder()
        .name("connection-recovery-events").description("Disconnects, dimension changes, lobby recovery and login failures.")
        .defaultValue(true).visible(webhook::get).build());
    private final Setting<Boolean> webhookPortals = webhookEvents.add(new BoolSetting.Builder()
        .name("portal-events").description("Portal discovery, entry and portal wait notifications.")
        .defaultValue(true).visible(webhook::get).build());
    private final Setting<Boolean> webhookAttention = webhookEvents.add(new BoolSetting.Builder()
        .name("attention-events").description("Warnings that need you to check the trip.")
        .defaultValue(true).visible(webhook::get).build());
    private final Setting<Integer> webhookCooldown = webhookTiming.add(new IntSetting.Builder()
        .name("event-cooldown-seconds").description("Minimum seconds between repeats of the same event; 0 disables. Home replacements, death and safety logout are not delayed.")
        .defaultValue(30).range(0, 3600).sliderRange(0, 300)
        .visible(webhook::get).onChanged(value -> webhookPolicy.reset()).build());
    private boolean reconnectLeased;
    private boolean reconnectWasEnabled;
    private double reconnectPreviousDelay;

    @Override public void onActivate() {
        stoppingTrip = false;
        if (profileLoading) return;
        syncHelpers();
        cancelPendingModuleRestore();
        leavingWorld = false;
        safetyDisconnect = false;
        clearRuntime();
        webhookLifecycle.reset();
        webhookPolicy.reset();
        lastWebhookContext = null;
        deathNotified = false;
        deathPending.set(false);
        healthUpdates.reset(mc.player == null ? 20 : mc.player.getHealth());
        if (!ensureCompatibleModules()) return;
        if (resumingRecovery) {
            startFlight(true);
            return;
        }
        if (lobbyEnabled() && lobby().resumePending()) {
            prepareReconnectDelay();
            return;
        }
        session.stop();
        if (mc.player != null && mc.level != null) startFlight();
    }

    @Override public void onDeactivate() {
        stoppingTrip = true;
        if (!profileLoading && (session.flying() || session.recovering())) {
            notifyWebhook(HighwayWebhook.Event.STOPPED,"Highway Fly was turned off; the trip has ended.",routeFields());
        }
        deathNotified = false;
        deathPending.set(false);
        healthUpdates.clear();
        pauseRuntime();
        clearRuntime();
        session.stop();
        releaseReconnect();
        pauseFlight();
        if (profileLoading) lobby().stopWalking();
        else lobby().cancelRecovery();
        syncHelpers();
        restorePausedModulesWhenSafe();
    }

    private String serverAddress() {
        return mc.getCurrentServer() == null ? null : mc.getCurrentServer().ip;
    }

    private String dimensionId() {
        return mc.level == null ? null : mc.level.dimension().identifier().toString();
    }

    private static String dimensionName(String id) {
        return id == null ? "unknown" : DiscordWebhook.dimensionName(id);
    }

    /** Who and where to put on a webhook; null when there is no player to describe. */
    private HighwayWebhook.Context webhookContext() {
        if (mc.player == null) return lastWebhookContext;
        String server = serverAddress();
        if ((server == null || server.isBlank()) && session.server() != null) server = session.server();
        if (server == null || server.isBlank()) server = "Singleplayer";
        String dimension = dimensionId();
        if (dimension == null) dimension = session.dimension();
        BlockPos pos = mc.player.blockPosition();
        lastWebhookContext = new HighwayWebhook.Context(mc.player.getName().getString(), server,
            dimension == null ? "" : dimensionName(dimension), pos.getX(), pos.getY(), pos.getZ());
        return lastWebhookContext;
    }

    /** The destination and remaining distance, for events that belong to a route. */
    List<HighwayWebhook.Field> routeFields() {
        if (course == null) {
            return tripDestination == null ? List.of() : List.of(new HighwayWebhook.Field("🎯 Destination",
                tripDestination.getX() + " " + tripDestination.getZ(), true, true));
        }
        if (mc.player == null) return List.of();
        Vec3 end = course.destination();
        int remaining = Math.max(0, (int) Math.round(course.distanceToDestination(mc.player.position())));
        return List.of(
            new HighwayWebhook.Field("🎯 Destination", (int) end.x + " " + (int) end.z, true, true),
            new HighwayWebhook.Field("📏 Remaining", remaining + " blocks", true));
    }

    void notifyWebhook(HighwayWebhook.Event event, String description, HighwayWebhook.Field... fields) {
        notifyWebhook(event, description, List.of(fields));
    }

    void notifyWebhook(HighwayWebhook.Event event, String description, List<HighwayWebhook.Field> fields) {
        TripEventLog.INSTANCE.record(event, description);
        if ((event == HighwayWebhook.Event.STOPPED || event == HighwayWebhook.Event.FINISHED
            || event == HighwayWebhook.Event.SAFETY_LOGOUT) && !webhookLifecycle.terminal()) return;
        if (!webhook.get() || fields == null || !webhookEventEnabled(event) || !HighwayWebhook.configured(webhookUrl.get())) return;
        long cooldown = (event == HighwayWebhook.Event.STATUS || event == HighwayWebhook.Event.HOME_REPLACED) ? 0 : TimeUnit.SECONDS.toNanos(webhookCooldown.get());
        if (!webhookPolicy.allow(event, System.nanoTime(), cooldown)) return;
        try {
            String mention = switch (event) {
                case NEEDS_HELP, SAFETY_LOGOUT -> webhookMention.get();
                default -> "";
            };
            HighwayWebhook.send(webhookUrl.get(), event, description, webhookContext(), fields, mention);
        } catch (RuntimeException e) {
            MeteorClient.LOG.warn("Highway Fly webhook could not be sent", e);
        }
    }

    /** Called only for Minecraft's disconnect, not a live configuration/world transfer. */
    public void onClientDisconnect(boolean transferring, boolean unexpected) {
        if (!isActive()) {
            if (lobby().isActive()) lobby().onClientDisconnect(transferring, unexpected);
            return;
        }
        if (isActive() && !transferring) leavingWorld = true;
        if (!isActive() || !session.flying() && !session.recovering()) return;
        HighwayWebhookLifecycle.Disconnect kind = webhookLifecycle.disconnected(transferring,unexpected);
        if (kind == HighwayWebhookLifecycle.Disconnect.NONE) return;
        String reason = kind == HighwayWebhookLifecycle.Disconnect.MANUAL
            ? "Left the server manually. Auto-reconnect will not start."
            : "Connection lost.";
        reason += lobbyEnabled() ? " The trip is paused until the next join." : " The trip has stopped.";
        if (kind == HighwayWebhookLifecycle.Disconnect.UNEXPECTED && lobbyEnabled() && lobby().reconnect.get()) {
            reason += " Auto-reconnect is preparing another attempt.";
        }
        notifyWebhook(HighwayWebhook.Event.DISCONNECTED,reason,routeFields());
        if (!lobbyEnabled()) webhookLifecycle.terminal();
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    private void onGameLeft(GameLeftEvent event) {
        if (!isActive()) return;
        boolean hadTrip = session.flying() || session.recovering();
        if (session.disconnected(lobbyEnabled())) {
            reconnectBackoff.failed();
            pauseRuntime();
            lobby().pauseForDisconnect();
            armReconnect();
            pauseFlight();
        } else {
            if (hadTrip) notifyWebhook(HighwayWebhook.Event.STOPPED,
                "Left the world; the trip ended because lobby recovery is off.",routeFields());
            disable();
        }
        if (pendingModuleRestore != null) pendingModuleRestore.worldLeft();
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    private void onGameJoined(GameJoinedEvent event) {
        if (!isActive()) return;
        leavingWorld = false;
        if (!ensureCompatibleModules()) return;
        webhookLifecycle.joined();
        deathNotified = false;
        releaseReconnect();
        connectedSince = System.nanoTime();
        if (session.recovering() && !lobbyEnabled()) {
            disable();
            return;
        }
        if (session.flying() && !java.util.Objects.equals(session.dimension(), dimensionId())) {
            if (!session.disconnected(lobbyEnabled())) {
                notifyWebhook(HighwayWebhook.Event.DIMENSION,
                    "The server moved you from " + dimensionName(session.dimension()) + " to "
                        + dimensionName(dimensionId()) + "; Highway Fly stopped.");
                disable();
                return;
            }
            pauseRuntime();
            pauseFlight();
        }
        if (lobbyEnabled() && lobby().resumePending()) {
            prepareReconnectDelay();
            return;
        }
        if (!session.flying()) startFlight();
    }

    @EventHandler
    private void onTick(TickEvent.Pre event) {
        if (!isActive() || profileLoading || mc.player == null || mc.level == null || !Utils.canUpdate()) return;
        syncHelpers();
        if (!ensureCompatibleModules()) return;
        boolean tripWorld = session.flying() || session.recoveryAllowed(serverAddress());
        if (reconnectBackoff.failures() > 0 && connectedSince != 0
            && System.nanoTime() - connectedSince >= 300_000_000_000L) {
            reconnectBackoff.reset();
            prepareReconnectDelay();
        }
        if (tripWorld && (deathPending.getAndSet(false) || mc.player.isDeadOrDying())) {
            if (!deathNotified) {
                deathNotified = true;
                notifyWebhook(HighwayWebhook.Event.DEATH,
                    "You died while Highway Fly was active. The trip has ended.", routeFields());
            }
            webhookLifecycle.terminal();
            disable();
            return;
        }
        if (tripWorld) {
            if (mc.player.isInLava()) {
                logOut("The player entered lava; logging out immediately.");
                return;
            }
            if (healthUpdates.lowHealth(minimumHealth.get())) {
                logOut("Remaining health is at or below " + minimumHealth.get() + " health points ("
                    + minimumHealth.get() / 2 + " hearts).");
                return;
            }

        }
        if (session.flying() && lobbyEnabled()
            && (!java.util.Objects.equals(session.dimension(), dimensionId()) || lobby().isKnownLobby())) {
            session.disconnected(true);
            pauseRuntime();
            pauseFlight();
            lobby().saveRecovery();
            lobby().resumePending();
        }
        if (session.recovering()) {
            pauseRuntime();
            if (!lobbyEnabled()) {
                disable();
                return;
            }
            if (session.recoveryAllowed(serverAddress())) {
                equipRecoveryChestplate();
            }
            return;
        }
        if (!session.flying()) {
            if (lobby().waitingForTripRecord && mc.player.tickCount % 100 == 0) lobby().resumePending();
            return;
        }
        tickFlight(event);
        if (!isActive()) return;
    }

    private void onLobbyRecoveryChanged(boolean enabled) {
        syncHelpers();
        if (!lobbyEnabled()) {
            releaseReconnect();
            if (isActive() && !stoppingTrip && session.recovering()) disable();
        } else prepareReconnectDelay();
    }

    private void onWebhookChanged(boolean enabled) {
        webhookPolicy.reset();
        if (!enabled) return;
        if (webhookUrl.get().isBlank()) {
            warning("Set webhook-url to a Discord webhook URL; no messages will be sent until it is set.");
        } else if (!HighwayWebhook.configured(webhookUrl.get())) {
            warning("webhook-url is not a Discord webhook URL; no messages will be sent.");
        }
    }

    private boolean webhookEventEnabled(HighwayWebhook.Event event) {
        return switch (HighwayWebhookPolicy.category(event)) {
            case TRIP -> webhookTrip.get();
            case STATUS -> false;
            case HOME -> webhookHome.get();
            case SAFETY -> webhookSafety.get();
            case DEATH -> webhookDeath.get();
            case LAVA -> webhookLava.get();
            case CONNECTION -> webhookConnection.get();
            case PORTAL -> webhookPortals.get();
            case ATTENTION -> webhookAttention.get();
        };
    }

    private List<Module> incompatibleModules() {
        List<Module> conflicts = new ArrayList<>();
        // Controllers come before their dependencies, so dependent modules restore last.
        for (Class<? extends Module> type : List.<Class<? extends Module>>of(
            BaritoneElytra.class, AutoWasp.class,
            meteordevelopment.meteorclient.systems.modules.movement.AutoWasp.class,
            AdaptiveElytraFly.class, ElytraFly.class, AutoWalk.class, Flight.class,
            AirJump.class, LongJump.class, Speed.class,
            meteordevelopment.meteorclient.systems.modules.world.Timer.class,
            Blink.class, CometScaffold.class, Scaffold.class, Freecam.class, NoFall.class)) {
            Module module = Modules.get().get(type);
            if (module == null) continue;
            if (module instanceof NoFall) {
                Setting<?> mode = module.settings.get("mode");
                if (mode != null && "Packet".equals(String.valueOf(mode.get()))) continue;
            }
            if (module instanceof meteordevelopment.meteorclient.systems.modules.world.Timer timer
                && timer.getMultiplier() == 1) continue;
            conflicts.add(module);
        }
        return conflicts;
    }

    /** Refresh ownership if another controller is enabled while this trip is running. */
    private boolean ensureCompatibleModules() {
        if (profileLoading) return true;
        List<Module> conflicts = incompatibleModules();
        if (!pauseOtherModules.get()) {
            for (Module module : conflicts) {
                if (!module.isActive()) continue;
                error("Turn off %s or enable pause-incompatible-modules.", module.title);
                disable();
                return false;
            }
            return true;
        }
        try {
            pausedModules.acquire(conflicts);
            return true;
        } catch (RuntimeException | LinkageError e) {
            MeteorClient.LOG.error("Highway Fly could not pause an incompatible module", e);
            error("Could not pause another movement module safely; stopping Highway Fly.");
            disable();
            return false;
        }
    }

    private void toggleConflictingModule(Module module) {
        if (!module.isActive()) {
            module.enable();
            return;
        }
        if (module instanceof BaritoneElytra) {
            BaritoneBridge bridge = BaritoneBridge.create();
            // Disabling Baritone Elytra alone deliberately leaves its airborne native route running.
            if (bridge != null) bridge.stop();
        }
        if (!(module instanceof ElytraFly fly)) {
            module.disable();
            return;
        }
        // Meteor's normal disable can swap off the wings or force a ground drop during handoff.
        ElytraFly.ChestSwapMode previousSwap = fly.chestSwap.get();
        Setting<?> dropSetting = fly.settings.get("insta-drop");
        BoolSetting drop = dropSetting instanceof BoolSetting bool ? bool : null;
        boolean previousDrop = drop != null && drop.get();
        try {
            fly.chestSwap.set(ElytraFly.ChestSwapMode.Never);
            if (drop != null) drop.set(false);
            fly.disable();
        } finally {
            fly.chestSwap.set(previousSwap);
            if (drop != null) drop.set(previousDrop);
        }
    }

    private void releasePausedModules() {
        try { pausedModules.release(); }
        catch (RuntimeException | LinkageError e) {
            MeteorClient.LOG.warn("Highway Fly could not restore a paused module", e);
        }
    }

    private void restorePausedModulesWhenSafe() {
        if (pausedModules.snapshot().isEmpty()) return;
        boolean safeWorld = mc.level == null || mc.player != null && !mc.player.isDeadOrDying()
            && mc.player.getHealth() > 0 && !mc.player.isInLava();
        if (!safetyDisconnect && !leavingWorld && safeWorld) {
            releasePausedModules();
            if (pausedModules.snapshot().isEmpty()) return;
        }
        cancelPendingModuleRestore();
        pendingModuleRestore = new PendingModuleRestore(safetyDisconnect || leavingWorld);
        MeteorClient.EVENT_BUS.subscribe(pendingModuleRestore);
    }

    private void cancelPendingModuleRestore() {
        if (pendingModuleRestore == null) return;
        MeteorClient.EVENT_BUS.unsubscribe(pendingModuleRestore);
        pendingModuleRestore = null;
    }

    /** Runs independently after this module unsubscribes, including while the main menu is open. */
    private final class PendingModuleRestore {
        private boolean waitForWorldExit;

        private PendingModuleRestore(boolean waitForWorldExit) {
            this.waitForWorldExit = waitForWorldExit;
        }

        private void worldLeft() { waitForWorldExit = false; }

        @EventHandler private void onLeft(GameLeftEvent event) { worldLeft(); }

        @EventHandler private void onPostTick(TickEvent.Post event) {
            if (pendingModuleRestore != this || isActive() || waitForWorldExit) return;
            if (mc.level != null && (mc.player == null || mc.player.isDeadOrDying()
                || mc.player.getHealth() <= 0 || mc.player.isInLava())) return;
            releasePausedModules();
            if (pausedModules.snapshot().isEmpty()) cancelPendingModuleRestore();
        }
    }

    /** The disconnect screen captures its timer before GameLeft, so prepare the delay during the trip. */
    void prepareReconnectDelay() {
        if (profileLoading || !lobby().isActive() || !lobby().reconnect.get() || session.server() == null
            || !session.flying() && !session.recoveryAllowed(serverAddress())) return;
        AutoReconnect module = Modules.get().get(AutoReconnect.class);
        if (module == null) return;
        if (!reconnectLeased) {
            reconnectWasEnabled = module.isActive();
            reconnectPreviousDelay = module.time.get();
            reconnectLeased = true;
        }
        module.time.set(reconnectBackoff.delay(lobby().reconnectDelay.get(), ThreadLocalRandom.current().nextDouble()));
    }

    /** Enable the prepared reconnect screen only when this trip can be recovered on that server. */
    void armReconnect() {
        if (!reconnectLeased) return;
        AutoReconnect module = Modules.get().get(AutoReconnect.class);
        if (module == null || module.lastServerConnection == null || module.lastServerConnection.right() == null
            || !session.reconnectAllowed(lobby().reconnect.get(), module.lastServerConnection.right().ip)) return;
        module.enable();
        module.time.set(reconnectBackoff.delay(lobby().reconnectDelay.get(), ThreadLocalRandom.current().nextDouble()));
    }

    @EventHandler
    void onServerConnect(ServerConnectBeginEvent event) {
        if (!isActive() && !lobby().isActive()) return;
        if (event.info == null || !session.recoveryAllowed(event.info.ip)) releaseReconnect();
        else if (reconnectLeased) {
            reconnectBackoff.failed();
            AutoReconnect module = Modules.get().get(AutoReconnect.class);
            module.time.set(reconnectBackoff.delay(lobby().reconnectDelay.get(), ThreadLocalRandom.current().nextDouble()));
        }
    }

    @EventHandler
    void onOpenScreen(OpenScreenEvent event) {
        // A manual Disconnect or leaving the retry screen must not leave our global reconnect enabled.
        if (session.recovering() && (event.screen instanceof TitleScreen
            || event.screen instanceof JoinMultiplayerScreen)) releaseReconnect();
    }

    /** Restore the user's preference when the join finishes, the trip stops, or the helper is disabled. */
    void releaseReconnect() {
        if (!reconnectLeased) return;
        reconnectLeased = false;
        AutoReconnect module = Modules.get().get(AutoReconnect.class);
        if (module == null) return;
        module.time.set(reconnectPreviousDelay);
        if (!reconnectWasEnabled) module.disable();
    }

    /** Modules serializes all modules together; keep a temporary reconnect lease out of saved profiles. */
    public void restoreReconnectInSavedConfig(CompoundTag tag) {
        if (reconnectLeased) {
            HighwayFlyConfigMigration.restoreReconnectPreference(tag, reconnectWasEnabled, reconnectPreviousDelay);
        }
        for (var entry : tag.getListOrEmpty("modules")) {
            if (!(entry instanceof CompoundTag module)) continue;
            String savedName = module.getStringOr("name", "");
            if (pausedModules.snapshot().stream().anyMatch(paused -> paused.name.equals(savedName))) module.putBoolean("active", true);
            if (savedName.equals(name) && (!durableTripAvailable || !lobbyEnabled())) module.putBoolean("active", false);
        }
    }

    public void beginProfileLoad() {
        profileLoading = true;
        releaseReconnect();
        if (isActive()) pauseFlight();
        session.stop();
        lobby().resetRecovery();
        syncHelpers();
        homes().beginProfileLoad();
        portalGuard().beginProfileLoad();
        lobby().beginProfileLoad();
    }
    public void endProfileLoad() {
        profileLoading = false;
        homes().endProfileLoad();
        portalGuard().endProfileLoad();
        lobby().endProfileLoad();
        // Requests are temporary and absent from saved helper active flags.
        syncHelpers();
        if (isActive()) {
            if (!lobbyEnabled() || !lobby().resumePending()) {
                warning("No saved trip identity; enable Highway Fly in the intended world to start a new trip.");
                // Still loading: disabling must not erase a recovery file from another server.
                profileLoading = true;
                disable();
                profileLoading = false;
            } else {
                onActivate();
            }
        }
        syncHelpers();
        prepareReconnectDelay();
        dashboard.sync();
    }

    /** Current setting locations let old profiles follow settings moved between sections. */
    public Map<String, String> settingGroups() {
        Map<String, String> groups = new HashMap<>();
        for (SettingGroup group : settings) for (Setting<?> setting : group) groups.put(setting.name, group.name);
        return groups;
    }

    public HighwayFly() {
        super(TntCoatingAddon.CATEGORY, "highway-fly",
            "Highway elytra flight with lobby recovery, automatic homes and portal protection.");
        runInMainMenu = true;
        MeteorClient.EVENT_BUS.subscribe(dashboard);
    }

    /** True while the autopilot should handle mouse look. */
    public boolean shouldControlMouseLook() {
        return isActive() && session.flying() && disableMouseInputs.get() && mc.screen == null
            && mc.player != null && mc.player.isFallFlying();
    }

    public boolean shouldCaptureMouseLook() {
        return shouldControlMouseLook() && freeLook.get();
    }

    public boolean shouldOverrideCamera() {
        return shouldControlMouseLook() && freeLook.get();
    }

    public float cameraYaw() {
        return cameraYaw;
    }

    public float cameraPitch() {
        return cameraPitch;
    }

    /** Mouse delta from MouseHandler; dx turns the view, dy pitches it. */
    public void onMouseLook(double dx, double dy) {
        if (mc.options == null) return;
        double sensitivity = mc.options.sensitivity().get() * .6 + .2;
        double scale = sensitivity * sensitivity * sensitivity * 8 * .15;
        if (mc.options.invertMouseX().get()) dx = -dx;
        if (mc.options.invertMouseY().get()) dy = -dy;
        cameraYaw = Mth.wrapDegrees(cameraYaw + (float) (dx * scale));
        cameraPitch = Mth.clamp(cameraPitch + (float) (dy * scale), -90, 90);
    }

    @Override
    public WWidget getWidget(GuiTheme theme) {
        var list = theme.verticalList();
        for (Class<? extends HighwayHelper> type : List.of(AntiPortal.class, AutoSetHome.class, AutoLobby.class)) {
            HighwayHelper helper = Modules.get().get(type);
            list.add(theme.label(helper.title + ": " + helper.activationLabel()));
            list.add(theme.button("Open " + helper.title + " settings")).widget().action =
                () -> mc.setScreen(theme.moduleScreen(helper));
        }
        list.add(dashboard.getWidget(theme));
        list.add(theme.button("Send test webhook")).widget().action = this::sendTestWebhook;
        list.add(theme.label("Posts one test message with the URL above; trip events are unchanged."))
            .widget().color = theme.textSecondaryColor();
        return list;
    }

    private void sendTestWebhook() {
        String url = webhookUrl.get();
        if (!HighwayWebhook.configured(url)) {
            mc.execute(() -> warning("Set a valid Discord webhook URL before sending a test."));
            return;
        }
        try {
            HighwayWebhook.send(url, HighwayWebhook.Event.TEST,
                "Webhook test from Highway Fly. If this message arrives, the URL and Discord permissions work.",
                webhookContext(),
                List.of(new HighwayWebhook.Field("Note", "Manual test; this does not change trip events.", false)));
            mc.execute(() -> info("Test webhook sent. Check the Discord channel."));
        } catch (RuntimeException e) {
            MeteorClient.LOG.warn("Highway Fly test webhook could not be sent", e);
            mc.execute(() -> error("Could not send the test webhook."));
        }
    }

    /** Snapshot for the status and runtime HUDs; null only while Highway Fly is off. */
    public TripStatus tripStatus() {
        if (!isActive()) return null;
        long now = System.nanoTime();
        long trip = tripStartedAtNanos == 0 ? 0 : Math.max(0, now - tripStartedAtNanos);
        long active = activeNanos + (activeStartedAtNanos == 0 ? 0 : Math.max(0, now - activeStartedAtNanos));
        boolean started = tripStartedAtNanos != 0;

        if (session.recovering()) {
            boolean allowed = session.recoveryAllowed(serverAddress());
            String lobbyState = lobby().getInfoString();
            String action = allowed ? (lobbyState == null ? "Waiting in the lobby" : lobbyState)
                : "Waiting to reconnect";
            return new TripStatus(allowed ? "Lobby recovery" : "Waiting for server", action,
                allowed ? "Trip paused; recovery is automatic." : "Waiting for the trip server.",
                started, trip, active);
        }
        if (session.flying()) {
            State current = state == null ? State.TAKING_OFF : state;
            String detail = current == State.DONE ? "Trip complete." : routeDetail();
            return new TripStatus(stateLabel(current), actionForState(current), detail, started, trip, active);
        }
        return new TripStatus("Starting", "Preparing the route", destinationDetail(), started, trip, active);
    }

    /** Broad phase for the status HUD. */
    static String stateLabel(State current) {
        return switch (current) {
            case TAKING_OFF -> "Taking off";
            case CLIMBING -> "Climbing";
            case CRUISE, DETOUR, PATHING -> "Flying";
            case WAIT_CHUNKS -> "Waiting";
            case BLOCKED -> "Blocked";
            case WALKING -> "Walking";
            case LANDING -> "Landing";
            case DONE -> "Done";
        };
    }

    /** The specific action shown under the phase in the status HUD. */
    static String actionForState(State current) {
        return switch (current) {
            case TAKING_OFF -> "Taking off from the ground";
            case CLIMBING -> "Climbing to cruise altitude";
            case CRUISE -> "Cruising to the destination";
            case DETOUR -> "Routing around an obstacle";
            case PATHING -> "Maneuvering around terrain";
            case WAIT_CHUNKS -> "Waiting for chunks to load";
            case BLOCKED -> "Searching for a safe way through";
            case WALKING -> "Walking to recover the route";
            case LANDING -> "Maneuvering to land";
            case DONE -> "Arrived at the destination";
        };
    }

    private String routeDetail() {
        BlockPos target = tripDestination != null ? tripDestination : destination.get();
        if (course == null || mc.player == null) return "Target " + target.getX() + ", " + target.getZ();
        long remaining = Math.max(0, Math.round(course.distanceToDestination(mc.player.position())));
        return remaining + "m left · target " + target.getX() + ", " + target.getZ();
    }

    private String destinationDetail() {
        BlockPos target = tripDestination != null ? tripDestination : destination.get();
        return "Target " + target.getX() + ", " + target.getZ();
    }

    private void beginRuntime(boolean resuming) {
        long now = System.nanoTime();
        if (!resuming) {
            tripStartedAtNanos = now;
            activeNanos = 0;
        } else if (tripStartedAtNanos == 0) {
            tripStartedAtNanos = now;
        }
        activeStartedAtNanos = now;
    }

    private void pauseRuntime() {
        if (activeStartedAtNanos == 0) return;
        activeNanos += Math.max(0, System.nanoTime() - activeStartedAtNanos);
        activeStartedAtNanos = 0;
    }

    private void clearRuntime() {
        tripStartedAtNanos = 0;
        activeStartedAtNanos = 0;
        activeNanos = 0;
    }

    /** Shared home status remains available while paused or disconnected. */
    public AutoSetHomeStatus autoSetHomeStatus() {
        return homes().status();
    }

    @Override
    public String getInfoString() {
        if (session.recovering() && !session.recoveryAllowed(serverAddress())) return "Waiting for server";
        if (session.recovering()) return lobby().getInfoString();
        if (course == null || mc.player == null || state == null) return null;
        return switch (state) {
            case TAKING_OFF -> "Taking off";
            case CLIMBING -> "Climbing";
            case CRUISE -> ((int) course.distanceToDestination(mc.player.position())) + "m";
            case DETOUR -> "Detour";
            case PATHING -> "Path";
            case WAIT_CHUNKS -> "Chunks";
            case BLOCKED -> "Blocked";
            case WALKING -> "Walking";
            case LANDING -> "Landing";
            case DONE -> "Done";
        };
    }

    /** Immutable telemetry for the local dashboard. Read-only; safe to call every few ticks on the game thread. */
    public HighwayTelemetry dashboardTelemetry() {
        if (mc.player == null) return HighwayTelemetry.idle(homes().telemetry());
        ItemStack chest = mc.player.getItemBySlot(EquipmentSlot.CHEST);
        int remainingDurability = elytraRemaining(chest);
        // Main storage excludes armour and offhand; add both explicitly so worn wings count once.
        Inventory inventory = mc.player.getInventory();
        int[] durability = new int[Inventory.INVENTORY_SIZE + 2];
        for (int slot = 0; slot < Inventory.INVENTORY_SIZE; slot++) {
            durability[slot] = elytraRemaining(inventory.getItem(slot));
        }
        durability[Inventory.INVENTORY_SIZE] = remainingDurability;
        durability[Inventory.INVENTORY_SIZE + 1] = elytraRemaining(inventory.getItem(Inventory.SLOT_OFFHAND));
        HighwayTelemetry.Elytra elytra = new HighwayTelemetry.Elytra(usableElytra(chest),
            remainingDurability < 0 || remainingDurability == Integer.MAX_VALUE ? null : remainingDurability,
            minDurability.get(), ElytraReplacement.countUsable(durability, minDurability.get()));
        if (!isActive()) return HighwayTelemetry.idle(homes().telemetry(), elytra);
        TripStatus status = tripStatus();
        HighwayTelemetry.Route route = null;
        HighwayTelemetry.Altitude altitude;
        if (course != null) {
            Vec3 position = mc.player.position();
            double remaining = Math.max(0, course.remaining(position));
            double distance = Math.max(0, course.distanceToDestination(position));
            double progress = Math.max(0, Math.min(1, 1 - remaining / course.length()));
            route = new HighwayTelemetry.Route(
                round(course.origin().x), round(course.origin().z),
                round(course.destination().x), round(course.destination().z),
                round(course.length()), round(remaining), round(distance), round(progress),
                round(HighwayCourse.yaw(course.origin(), course.destination())),
                round(course.targetY()), round(course.arrivalDistance()));
            altitude = new HighwayTelemetry.Altitude(
                round(lockAltitude.get() ? targetY.get() : course.targetY()),
                round(activeY), round(preferredY), altitudeBlocked);
        } else {
            altitude = new HighwayTelemetry.Altitude(
                round(lockAltitude.get() ? targetY.get() : destination.get().getY()), null, null, altitudeBlocked);
        }

        HighwayTelemetry.Home home = homes().telemetry();
        List<Vec3> sourcePath = path == null ? List.of() : path;
        int firstPoint = Math.max(0, sourcePath.size() - DASHBOARD_PATH_LIMIT);
        List<HighwayTelemetry.Point> renderedPath = new ArrayList<>(sourcePath.size() - firstPoint);
        for (int index = firstPoint; index < sourcePath.size(); index++) {
            Vec3 point = sourcePath.get(index);
            renderedPath.add(new HighwayTelemetry.Point(round(point.x), round(point.y), round(point.z)));
        }
        HighwayTelemetry.Portal.Hit hit = portalHit == null ? null : new HighwayTelemetry.Portal.Hit(
            round(portalHit.distance()), portalHit.pos().getX(), portalHit.pos().getY(), portalHit.pos().getZ());
        List<String> paused = pausedModules.snapshot().stream().map(module -> module.title).sorted().toList();
        State current = state == null ? State.TAKING_OFF : state;
        return new HighwayTelemetry(
            true,
            current.name(),
            status == null ? stateLabel(current) : status.state(),
            status == null ? actionForState(current) : status.action(),
            status == null ? routeDetail() : status.detail(),
            status != null && status.started(),
            status == null ? 0 : status.tripNanos(),
            status == null ? 0 : status.activeNanos(),
            dimension == null ? dimensionId() : dimension,
            serverAddress(),
            session.recovering(),
            session.recoveryAllowed(serverAddress()),
            lobby().getInfoString(),
            home,
            route,
            altitude,
            new HighwayTelemetry.Counters(detourTicks, blockedAttempts, takeoffAttempts, rubberbandCount,
                walkFailures, failedSwaps, walkBlocks, walkTicks, retryTicks, climbTicks),
            new HighwayTelemetry.Path(sourcePath.size() > DASHBOARD_PATH_LIMIT, renderedPath),
            new HighwayTelemetry.Walk(walkStarted, walkTarget != null,
                walkTarget == null ? null : (double) walkTarget.getX(),
                walkTarget == null ? null : (double) walkTarget.getY(),
                walkTarget == null ? null : (double) walkTarget.getZ(), walkBlocks, walkFailures),
            new HighwayTelemetry.Portal(portalMode.get().name(), portalInside, portalCrossing != null,
                portalCrossingTicks, portalWalkPaused, portalHit != null, hit),
            new HighwayTelemetry.Safety(round(travelSafety.damageTaken()), round(minimumHealth.get()),
                noProgressTimeout.get(), safetyDisconnect, deathNotified),
            elytra,
            new HighwayTelemetry.Webhook(webhook.get(), HighwayWebhook.configured(webhookUrl.get()),
                webhookLifecycle.terminal()),
            paused);
    }

    private static double round(double value) {
        if (!Double.isFinite(value)) return 0;
        double scaled = value * 1000.0;
        if (Math.abs(scaled) > 9.0e15) return value;
        return Math.round(scaled) / 1000.0;
    }

    void startFlight() {
        startFlight(false);
    }

    void startFlight(boolean resuming) {
        PendingSwapBack.cancel();
        reset();
        if (mc.player == null || mc.level == null) {
            toggle();
            return;
        }
        if (!validDestination(resuming)) {
            toggle();
            return;
        }
        if (mc.player.isPassenger() || mc.player.isInWater() || mc.player.isInLava()) {
            error("Get out of the vehicle, water and lava first.");
            toggle();
            return;
        }
        if (!ensureCompatibleModules()) return;

        BlockPos target = destination.get();
        double desiredY = lockAltitude.get() ? targetY.get() : target.getY();
        if (desiredY < minY.get() || desiredY > maxY.get()) {
            error("Destination Y is outside the min/max altitude band.");
            toggle();
            return;
        }
        Vec3 destinationPoint = new Vec3(target.getX() + .5, desiredY, target.getZ() + .5);
        try {
            Vec3 origin = new Vec3(mc.player.getX(), desiredY, mc.player.getZ());
            if (resuming && origin.distanceTo(destinationPoint)<1) origin = destinationPoint.add(0,0,-1);
            course = new HighwayCourse(origin,
                destinationPoint, desiredY, arrivalDistance.get());
        } catch (IllegalArgumentException e) {
            error("Invalid route: %s", e.getMessage());
            toggle();
            return;
        }
        if (!resuming && course.length() < 64) {
            error("Destination must be at least 64 blocks away.");
            toggle();
            return;
        }
        if (!usableElytra(mc.player.getItemBySlot(EquipmentSlot.CHEST))) {
            if (!swapElytra.get()) {
                error("Wear an elytra first.");
                toggle();
                return;
            }
            if (!canUseInventory()) {
                error("Close the container and drop the cursor item first.");
                toggle();
                return;
            }
            if (!mc.player.inventoryMenu.getSlot(6).mayPickup(mc.player)) {
                error("Can't change the chest slot right now.");
                toggle();
                return;
            }
            int swapped = swapInElytra(Math.max(2, minDurability.get()));
            if (swapped < 0) {
                error("No usable elytra in your inventory.");
                toggle();
                return;
            }
            info("Put on an elytra with %d durability left.", swapped);
        }
        recenterCourseAt(mc.player.position());
        walkingLine = HighwayRouteLine.from(mc.player.position(), destinationPoint);

        preferredY = desiredY;
        activeY = cruiseY();
        lastProgressRemaining = course.distanceToDestination(mc.player.position());
        detourY = preferredY;
        detourTicks = 0;
        landingX = destinationPoint.x;
        landingZ = destinationPoint.z;
        dimension = mc.level.dimension().identifier().toString();
        lastControlledPosition = mc.player.position();
        takeoff.reset(mc.player.isFallFlying());
        state = !mc.player.isFallFlying() ? State.TAKING_OFF
            : needsClimb() ? State.CLIMBING : State.CRUISE;
        climbTicks = 0;
        flightPitch = mc.player.getXRot();
        mc.player.setYRot(HighwayCourse.yaw(mc.player.position(), course.aimPoint(mc.player.position(),
            lookahead.get(), LATERAL_GAIN)));
        flightYaw = mc.player.getYRot();
        cameraYaw = flightYaw;
        cameraPitch = flightPitch;
        session.start(dimension, serverAddress());
        beginRuntime(resuming);
        travelSafety.resetProgress(-course.distanceToDestination(mc.player.position()));
        tripDestination = target.immutable();
        tripTargetY = (int) Math.round(desiredY);
        connectedSince = System.nanoTime();
        if (lobbyEnabled()) lobby().saveRecovery();
        prepareReconnectDelay();
        info("Flying %d blocks to %d, %d.", (int) course.length(), target.getX(), target.getZ());
        notifyWebhook(resuming ? HighwayWebhook.Event.RESUMED : HighwayWebhook.Event.STARTED,
            resuming
                ? "Resumed the route to " + target.getX() + ", " + target.getZ() + "."
                : "Started a " + (int) course.length() + "-block route to " + target.getX() + ", " + target.getZ() + ".",
            new HighwayWebhook.Field("🎯 Destination", target.getX() + " " + target.getZ(), true, true),
            new HighwayWebhook.Field("🎯 Target Y", String.valueOf((int) desiredY), true));
    }

    private void pauseFlight() {
        if (walkBaritone != null) {
            try { walkBaritone.stop(); }
            catch (RuntimeException | LinkageError e) { MeteorClient.LOG.warn("Stopping the recovery walk failed", e); }
            finally { walkBaritone = null; }
        }
        restoreChestplate();
        reset();
        if (mc.player != null) mc.player.setXRot(0);
    }

    @EventHandler
    private void onPacket(PacketEvent.Receive event) {
        if (!isActive()) return;
        // Receipt order is preserved before vanilla dispatches login/health handling to the client thread.
        // GameJoined must not discard health already received for this connection.
        if (event.packet instanceof ClientboundLoginPacket) healthUpdates.connectionStarted();
        if (event.packet instanceof ClientboundPlayerCombatKillPacket killed
            && mc.player != null && killed.playerId() == mc.player.getId()
            && (session.flying() || session.recoveryAllowed(serverAddress()))) deathPending.set(true);
        if (event.packet instanceof ClientboundSetHealthPacket health
            && session.healthTrackingAllowed(serverAddress())) healthUpdates.accept(health.getHealth());
        if (event.packet instanceof ClientboundBlockUpdatePacket || event.packet instanceof ClientboundSectionBlocksUpdatePacket
            || event.packet instanceof ClientboundLevelChunkWithLightPacket || event.packet instanceof ClientboundForgetLevelChunkPacket
            || event.packet instanceof ClientboundRespawnPacket || event.packet instanceof ClientboundLoginPacket) {
            blockRevision.incrementAndGet();
        }
        if (session.flying() && event.packet instanceof ClientboundPlayerPositionPacket position) correction.set(position);
    }

    private void tickFlight(TickEvent.Pre event) {
        if (state == State.DONE) return;
        if (mc.player == null || mc.level == null) {
            toggle();
            return;
        }
        if (!mc.level.dimension().identifier().toString().equals(dimension)) {
            error("Dimension changed; stopping before the route is lost.");
            finish("Stopped in a new dimension");
            return;
        }
        if (mc.player.isInWater()) {
            error("In water; stopping.");
            finish("Stopped in water");
            return;
        }
        if (state == State.LANDING && mc.player.onGround()) {
            finish("Landed");
            return;
        }
        if (state == State.WALKING) equipRecoveryChestplate();
        ItemStack chest = mc.player.getItemBySlot(EquipmentSlot.CHEST);
        HighwayFlightSafety.Action protection = HighwayFlightSafety.action(
            elytraRemaining(chest), minDurability.get(), swapElytra.get(), canUseInventory(),
            HighwayFlightSafety.requiresWings(state == State.WALKING, walkStarted, mc.player.onGround()));
        if (protection == HighwayFlightSafety.Action.LOGOUT) {
            logOut("Low or missing elytra durability and a replacement cannot be equipped safely.");
            return;
        }
        if (protection == HighwayFlightSafety.Action.REPLACE) {
            int spare = swapInElytra(Math.max(2, minDurability.get()));
            if (spare < 0) {
                logOut("No usable spare elytra is available; logging out before the wings fail.");
                return;
            }
            if (elytraRemaining(mc.player.getItemBySlot(EquipmentSlot.CHEST)) <= Math.max(2, minDurability.get())) {
                if (++failedSwaps >= 3) logOut("The elytra replacement was rejected; logging out safely.");
                return;
            }
            failedSwaps = 0;
            info("Equipped spare elytra with %d durability left.", spare);
        }
        if (altitudeBlocked && mc.player.getY() >= preferredY - CLIMB_TOLERANCE) {
            altitudeBlocked = false;
        }
        if (course != null && state != State.WALKING && state != State.LANDING && state != State.DONE) {
            double remaining = course.distanceToDestination(mc.player.position());
            if (remaining < lastProgressRemaining - 2) {
                blockedAttempts = 0;
                lastProgressRemaining = remaining;
            }
        }
        if (portalCrossing != null && tickPortalCrossing()) return;
        if (state != State.LANDING && state != State.WALKING && course != null
            && travelSafety.noProgress(-course.distanceToDestination(mc.player.position()), noProgressTimeout.get() * 20)) {
            if (state == State.WALKING || walkRecoveryAttempted) {
                logOut("Flight and Baritone recovery could not make forward progress for " + noProgressTimeout.get() + " seconds.");
            } else if (!startWalkFallback("No forward progress for " + noProgressTimeout.get() + " seconds.")) {
                logOut("Flight could not advance and Baritone recovery is unavailable.");
            }
            return;
        }
        if (state == State.WALKING) {
            tickWalking();
            return;
        }
        if (state == State.LANDING) {
            if (!mc.player.isFallFlying()) {
                // Losing the glide while airborne is not a completed landing.
                if (mc.player.tryToStartFallFlying()) mc.getConnection().send(new ServerboundPlayerCommandPacket(
                    mc.player, ServerboundPlayerCommandPacket.Action.START_FALL_FLYING));
            }
            return;
        }
        if (course.arrived(mc.player.position())) {
            if (portalMode.get() == PortalMode.Pass && portalInside) {
                if (debug.get()) info("Holding arrival until clear of the portal.");
                return;
            }
            if (!landOnArrival.get()) {
                finish("Arrived");
                return;
            }
            landingX = destination.get().getX() + .5;
            landingZ = destination.get().getZ() + .5;
            state = State.LANDING;
            info("Arrived; landing.");
            return;
        }

        if (portalMode.get() != PortalMode.Avoid) updatePortalHit();
        if (portalMode.get() == PortalMode.Stop && portalHit != null
            && portalHit.distance() <= Math.max(4, speedBps.get() / 20 * 2)) {
            error("Portal ahead; stopping.");
            finish("Stopped before portal");
            return;
        }

        if (state == State.BLOCKED) {
            if (retryTicks++ >= RETRY_TICKS) {
                retryTicks = 0;
                        state = State.CRUISE;
            }
            return;
        }
        if (state == State.WAIT_CHUNKS) {
                state = State.CRUISE;
            return;
        }
        if (!mc.player.isFallFlying()) {
            if (!autoTakeoff.get() && !recoverFromGround.get()) {
                if (!warnedWaiting) {
                    warnedWaiting = true;
                    info("Waiting for you to open the elytra.");
                }
                return;
            }
            if (state != State.TAKING_OFF) {
                state = State.TAKING_OFF;
                takeoff.reset(true);
            }
            ElytraTakeoff.Step step = takeoff.next(mc.player.onGround(), false,
                mc.player.getDeltaMovement().y, openAbove());
            switch (step) {
                case JUMP -> mc.player.jumpFromGround();
                case OPEN -> {
                    if (mc.player.tryToStartFallFlying()) {
                        mc.getConnection().send(new ServerboundPlayerCommandPacket(mc.player,
                            ServerboundPlayerCommandPacket.Action.START_FALL_FLYING));
                    }
                }
                case COVERED, GAVE_UP -> {
                    if (!warnedTakeoff) {
                        warnedTakeoff = true;
                        warning("No room to open the elytra; keeping the flight active and retrying.");
                    }
                    if (++takeoffAttempts >= flightAttempts.get()) {
                        takeoffAttempts = 0;
                        if (startWalkFallback("No room to open the elytra.", walkDistance.get())) {
                            return;
                        }
                        logOut("The elytra could not take off and Baritone recovery is unavailable.");
                        return;
                    }
                    takeoff.reset(true);
                }
                case WAIT -> {}
            }
            return;
        }

        if (state == State.TAKING_OFF) {
            takeoffAttempts = 0;
            state = needsClimb() ? State.CLIMBING : State.CRUISE;
            climbTicks = 0;
        }
        if (state == State.CLIMBING) {
            if (mc.player.getY() >= preferredY - CLIMB_TOLERANCE) {
                state = State.CRUISE;
                altitudeBlocked = false;
                climbTicks = 0;
            } else if (++climbTicks > CLIMB_STALL_TICKS && mc.player.getDeltaMovement().y < .02) {
                warning("No room to climb to target-y; continuing at the current altitude.");
                activeY = mc.player.getY();
                altitudeBlocked = true;
                state = State.CRUISE;
                climbTicks = 0;
            }
        }
        warnedWaiting = false;
    }

    @EventHandler(priority = Integer.MIN_VALUE)
    private void onMove(PlayerMoveEvent event) {
        if (!isActive() || !session.flying()) return;
        if (event.type != MoverType.SELF || course == null || mc.player == null || mc.level == null) return;
        if (mc.player.isDeadOrDying() || deathPending.get()) {
            ((IVec3) event.movement).meteor$set(0, 0, 0);
            return;
        }
        if (mc.player.isInLava()) {
            ((IVec3) event.movement).meteor$set(0, 0, 0);
            logOut("The player entered lava; logging out immediately.");
            return;
        }
        if (portalCrossing != null || tryPortalCrossing(event.movement)) {
            if (portalCrossing != null) moveThroughPortal(event);
            else zeroHorizontal(event);
            return;
        }
        // Baritone owns grounded movement, including collision resolution, step-up and
        // alternate route heights. Flight predictions must never interrupt its safe detour.
        if (state == State.WALKING && walkStarted) return;
        if (!mc.player.isFallFlying() || mc.player.isPassenger()
            || mc.player.isInWater()) {
            guardUncontrolledLavaMove(event);
            return;
        }
        if (state != State.CLIMBING && state != State.CRUISE && state != State.DETOUR
            && state != State.PATHING && state != State.WALKING && state != State.LANDING) {
            guardUncontrolledLavaMove(event);
            return;
        }

        try {
            AdaptiveElytraFly adaptive = Modules.get().get(AdaptiveElytraFly.class);
            if (adaptive == null) {
                guardUncontrolledLavaMove(event);
                return;
            }
            AdaptiveFlightEngine.Settings profile = adaptive.baritoneFlightSettings();
            if (controller == null) controller = new BaritoneFlight(profile);
            BaritoneFlight.Settings config;
            ClientboundPlayerPositionPacket packet = correction.getAndSet(null);
            if (packet != null && lastControlledPosition != null) {
                Vec3 target = PositionMoveRotation.calculateAbsolute(new PositionMoveRotation(lastControlledPosition,
                    mc.player.getDeltaMovement(), mc.player.getYRot(), mc.player.getXRot()),
                    packet.change(), packet.relatives()).position();
                if (rubberband(target)) {
                    zeroHorizontal(event);
                    return;
                }
                navigation.setback();
                controller.setback(target.distanceToSqr(lastControlledPosition) > SETBACK_STOP_DISTANCE * SETBACK_STOP_DISTANCE);
            }
            Vec3 natural = mc.player.getDeltaMovement();
            double horizontalBps = Math.hypot(natural.x, natural.z) * 20;
            Motion output;
            if (state == State.WALKING) {
                if (walkStarted) return;
                config = flightConfig(profile, speedBps.get());
                float yaw = smoothYaw(course.waypoint(mc.player.position(), lookahead.get(), LATERAL_GAIN).yaw());
                setFlightLook(yaw, flightPitch);
                ServerGlide.Context glide = glide(natural, 0, yaw, flightPitch);
                output = controller.land(profile, config, mc.player.tickCount, 0, 0, true,
                    natural.length(), event.movement.length(), mc.player.getBoundingBox(), moveProbe,
                    () -> floorClearance(profile.margin()), glide,
                    movement -> resolveCollision(mc.player.getBoundingBox(), movement));
            } else if (state == State.CLIMBING) {
                float yaw = smoothYaw(course.waypoint(mc.player.position(), lookahead.get(), LATERAL_GAIN).yaw());
                setFlightLook(yaw, -89f);
                ServerGlide.Context glide = glide(natural, 0, yaw, -89f);
                // A tiny forward cap keeps a steep climb attainable: a zero horizontal cap would also zero the
                // slope-capped vertical output in BaritoneFlight.settings.
                BaritoneFlight.Settings climbConfig = new BaritoneFlight.Settings(2, verticalSpeedBps.get(),
                    profile.boostRampStep(), profile.verticalRampStep());
                output = controller.move(profile, climbConfig, mc.player.tickCount, yaw, -89f, true,
                    natural.length(), event.movement.length(), mc.player.getBoundingBox(), moveProbe,
                    () -> floorClearance(profile.margin()), glide,
                    movement -> resolveCollision(mc.player.getBoundingBox(), movement));
            } else if (state == State.LANDING) {
                config = flightConfig(profile, speedBps.get());
                float yaw = HighwayCourse.yaw(mc.player.position(),
                    new Vec3(landingX, mc.player.getY(), landingZ));
                yaw = smoothYaw(yaw);
                setFlightLook(yaw, flightPitch);
                ServerGlide.Context glide = glide(natural, 0, yaw, flightPitch);
                output = controller.land(profile, config, mc.player.tickCount,
                    landingX - mc.player.getX(), landingZ - mc.player.getZ(), true,
                    natural.length(), event.movement.length(), mc.player.getBoundingBox(), moveProbe,
                    () -> floorClearance(profile.margin()), glide,
                    movement -> resolveCollision(mc.player.getBoundingBox(), movement));
            } else {
                HighwayCourse.Waypoint aim = course.waypoint(mc.player.position(), lookahead.get(), LATERAL_GAIN);
                float baseYaw = smoothYaw(aim.yaw());
                AABB body = mc.player.getBoundingBox();

                boolean routeClear = directClear(body, aim.yaw());
                HighwayNavigation.Step guidance = null;
                if (!pathfind.get() && state == State.PATHING) {
                    navigation.reset(); path = List.of(); state = State.CRUISE;
                }
                if (pathfind.get() && (state == State.CRUISE || state == State.PATHING)) {
                    guidance = navigation.update(mc.player.tickCount, mc.player.position(), body, natural,
                        routeClear, speedBps.get(), strictProbe);
                    if (guidance.needsPath()) {
                        planPath(body);
                        guidance = navigation.update(mc.player.tickCount, mc.player.position(), body, natural,
                            routeClear, speedBps.get(), strictProbe);
                    }
                    path = navigation.points();
                    if (guidance.followingPath()) state = State.PATHING;
                    else if (state == State.PATHING) state = State.CRUISE;
                    if (!routeClear && !guidance.followingPath() && !guidance.needsPath()) {
                        zeroHorizontal(event); // Setback cooldown: do not choose another side immediately.
                        return;
                    }
                }

                if (state == State.DETOUR) {
                    if (++detourTicks > detourHold.get()) {
                        detourTicks = 0;
                        state = State.CRUISE;
                        activeY = cruiseY();
                    }
                }
                if (state == State.CRUISE) {
                    if (routeClear) {
                        activeY = cruiseY();
                        detourTicks = 0;
                    } else {
                    HighwayDetour.Choice choice = HighwayDetour.choose(detourBody(body), baseYaw,
                        preferredY, new HighwayDetour.Config(maxDetour.get(), detourStep.get(),
                            minY.get(), maxY.get(), verticalStep.get(), probeDistance.get(), 0), strictProbe);
                    switch (choice.reason()) {
                        case CLEAR -> {
                            activeY = cruiseY();
                            detourTicks = 0;
                        }
                        case DETOUR -> {
                            detourYawOffset = choice.yawOffsetDegrees();
                            detourY = choice.targetY();
                            activeY = detourY;
                            detourTicks = 0;
                            state = State.DETOUR;
                            if (debug.get()) info("Detour %.0f degrees, Y %.1f.", detourYawOffset, activeY);
                        }
                        case CHUNKS -> {
                            state = State.WAIT_CHUNKS;
                            zeroHorizontal(event);
                            return;
                        }
                        case INVALID, BLOCKED -> {
                            failFlight("Blocked and no detour fits the altitude band.", event);
                            return;
                        }
                    }
                    }
                }

                float yaw;
                if (state == State.PATHING) {
                    Vec3 target = guidance == null ? null : guidance.target();
                    if (target == null) {
                        failFlight("Blocked and no corridor path target is visible.", event);
                        return;
                    }
                    yaw = smoothYaw(HighwayCourse.yaw(mc.player.position(), target));
                    activeY = Mth.clamp(target.y, minY.get(), maxY.get());
                } else if (state == State.DETOUR) {
                    yaw = smoothYaw(baseYaw + detourYawOffset);
                } else {
                    yaw = baseYaw;
                }
                double y = mc.player.getY();
                float pitch = AltitudeHold.pitch(activeY, y, natural.y * 20, horizontalBps,
                    new AltitudeHold.Config(ALTITUDE_GAIN, ALTITUDE_DAMPING, verticalSpeedBps.get(),
                        verticalSpeedBps.get(), ALTITUDE_DEADBAND, ALTITUDE_MAX_PITCH));
                setFlightLook(yaw, pitch);
                ServerGlide.Context glide = glide(natural, 0, yaw, pitch);
                double cap = state == State.PATHING ? pathSpeed() : speedBps.get();
                if (guidance != null && guidance.followingPath()) cap = Math.min(cap, guidance.speedLimit());
                config = BaritoneFlight.approach(flightConfig(profile, cap), course.distanceToDestination(mc.player.position()));
                output = controller.move(profile, config, mc.player.tickCount, yaw, pitch, true,
                    natural.length(), event.movement.length(), mc.player.getBoundingBox(), moveProbe,
                    () -> floorClearance(profile.margin()), glide,
                    movement -> resolveCollision(mc.player.getBoundingBox(), movement));
            }
            if (output == null) {
                guardUncontrolledLavaMove(event);
                return;
            }
            if (HighwayLavaSafety.intersects(mc.player.getBoundingBox(), new Vec3(output.x(), output.y(), output.z()), lavaProbe)) {
                ((IVec3) event.movement).meteor$set(0, 0, 0);
                if (state == State.CLIMBING) {
                    // A blocked vertical climb does not rule out level flight around the lava.
                    controller.reset();
                    navigation.reset();
                    path = List.of();
                    altitudeBlocked = true;
                    activeY = mc.player.getY();
                    climbTicks = 0;
                    state = State.CRUISE;
                    return;
                }
                if (state != State.WALKING && state != State.LANDING) {
                    failFlight("The selected flying route would enter lava.", event);
                }
                return;
            }
            lastControlledPosition = mc.player.position();
            ((IVec3) event.movement).meteor$set(output.x(), output.y(), output.z());
        } catch (LinkageError | RuntimeException e) {
            MeteorClient.LOG.error("Highway Fly's flight failed", e);
            error("Flight failed; stopping. See the log.");
            finish("Flight controller error");
        }
    }

    private BaritoneFlight.Settings flightConfig(AdaptiveFlightEngine.Settings profile, double cap) {
        return new BaritoneFlight.Settings(Math.max(0, cap), verticalSpeedBps.get(),
            profile.boostRampStep(), profile.verticalRampStep());
    }

    /** Protect vanilla fallback motion after solid collision clipping, without treating a prediction as immersion. */
    private void guardUncontrolledLavaMove(PlayerMoveEvent event) {
        if (state == State.WALKING && walkStarted) return;
        Motion resolved = resolveCollision(mc.player.getBoundingBox(),
            new Motion(event.movement.x, event.movement.y, event.movement.z));
        if (HighwayLavaSafety.intersects(mc.player.getBoundingBox(),
            new Vec3(resolved.x(), resolved.y(), resolved.z()), lavaProbe)) {
            ((IVec3) event.movement).meteor$set(0, 0, 0);
        }
    }

    private void failFlight(String message, PlayerMoveEvent event) {
        blockedAttempts++;
        zeroHorizontal(event);
        // This is reached only after the airborne pathfinder/detour has tried to pass.
        if (stopBeforeLava.get() && lavaAhead()) {
            avoidLava();
            return;
        }
        if (blockedAttempts < flightAttempts.get()) {
            state = State.BLOCKED;
            retryTicks = 0;
            warning("%s Retrying elytra flight (%d/%d).", message, blockedAttempts, flightAttempts.get());
            return;
        }
        if (startWalkFallback(message)) return;
        logOut("Flight is blocked and Baritone recovery is unavailable.");
    }

    private boolean rubberband(Vec3 correctedPosition) {
        if (!rubberbandDetection.get() || state == State.WALKING || state == State.LANDING
            || state == State.BLOCKED || state == State.TAKING_OFF) return false;
        if (lastControlledPosition == null) return false;
        double distanceSq = correctedPosition.distanceToSqr(lastControlledPosition);
        if (distanceSq < RUBBERBAND_MIN_DISTANCE * RUBBERBAND_MIN_DISTANCE) return false;
        int now = mc.player.tickCount;
        if (now - lastRubberbandTick > rubberbandWindowTicks.get()) rubberbandCount = 0;
        lastRubberbandTick = now;
        rubberbandCount++;
        if (rubberbandCount < rubberbandThreshold.get()) return false;
        rubberbandCount = 0;
        correction.set(null);
        return startWalkFallback("Repeated rubberbanding.", rubberbandWalkDistance.get());
    }

    /** Pause waypoint steering until the whole body has crossed the portal plane. */
    private boolean tryPortalCrossing(Vec3 movement) {
        boolean walking = state == State.WALKING && walkStarted;
        boolean flying = mc.player.isFallFlying() && portalMode.get() == PortalMode.Pass
            && (state == State.CRUISE || state == State.PATHING || state == State.DETOUR);
        if ((!walking && !flying) || mc.player.isPassenger() || mc.player.isInWater() || mc.player.isInLava()) return false;
        Vec3 approach = new Vec3(movement.x,0,movement.z);
        if (approach.lengthSqr() < 1e-6) return false;
        var hit = PortalLookahead.first(mc.player.getBoundingBox(), approach, walking ? 1.25 : 4, portalProbe);
        if (hit.isEmpty()) return false;
        if (portalMode.get() == PortalMode.Stop) {
            finish("Stopped before portal");
            return true;
        }
        BlockState block = mc.level.getBlockState(hit.get().pos());
        HighwayPortalCrossing crossing = HighwayPortalCrossing.plan(mc.player.position(),
            mc.player.getBoundingBox(),hit.get().pos(),block.getValue(NetherPortalBlock.AXIS),approach);
        if (!portalCrossingClear(crossing,walking)) {
            // A diagonal approach may still be lining up with the opening. Leave it in
            // control while this step stays outside; never let an unsafe entry step through.
            if (hit.get().distance() > approach.length() + .05) return false;
            if (walking) retryWalk("Portal has no safe straight crossing; choosing another walking target.");
            else { state = State.BLOCKED; retryTicks = 0; }
            return true;
        }
        if (walking) {
            try { walkBaritone.stop(); }
            catch (RuntimeException | LinkageError e) {
                logOut("Could not pause Baritone safely before a portal.");
                return true;
            }
        }
        // Ground walking does not consume the flight controller's correction mailbox.
        // The route was checked from the current (already corrected) player position.
        correction.set(null);
        portalWalkPaused = walking;
        portalCrossing = crossing;
        portalCrossingTicks = 0;
        navigation.reset();
        return true;
    }

    private boolean portalCrossingClear(HighwayPortalCrossing crossing, boolean walking) {
        Vec3 delta = crossing.target().subtract(mc.player.position());
        int steps = Math.max(1,(int)Math.ceil(delta.length()/.25));
        if (steps > 64) return false;
        for (int i=0;i<=steps;i++) {
            AABB body = mc.player.getBoundingBox().move(delta.scale((double)i/steps));
            if (!loaded(body.expandTowards(0,walking ? -.2 : 0,0)) || !mc.level.noCollision(mc.player,body)) return false;
            if (walking && !mc.level.getBlockCollisions(mc.player,body.move(0,-.15,0)).iterator().hasNext()) return false;
            for (BlockPos pos : BlockPos.betweenClosed(BlockPos.containing(body.minX,body.minY,body.minZ),
                BlockPos.containing(body.maxX,body.maxY,body.maxZ))) {
                var fluid = mc.level.getFluidState(pos);
                if (!fluid.isEmpty() && fluid.getAABB(mc.level, pos).intersects(body)) return false;
            }
        }
        return true;
    }

    private boolean tickPortalCrossing() {
        if (++portalCrossingTicks > 60 || correction.get() != null
            || !portalCrossingClear(portalCrossing,portalWalkPaused)) {
            logOut("Straight portal crossing was obstructed or corrected; stopping safely.");
            return true;
        }
        if (!portalCrossing.finished(mc.player.position()) || PortalLookahead.contains(mc.player.getBoundingBox(),portalProbe)) return true;
        portalCrossing = null;
        navigation.reset();
        lastProgressRemaining = course.distanceToDestination(mc.player.position());
        if (portalWalkPaused) {
            portalWalkPaused = false;
            try {
                walkBaritone.avoidLava(true);
                walkBaritone.walkToExact(walkTarget.getX(),walkTarget.getY(),walkTarget.getZ());
            }
            catch (RuntimeException | LinkageError e) { retryWalk("Could not resume Baritone after the portal."); }
        }
        return false;
    }

    private void moveThroughPortal(PlayerMoveEvent event) {
        Vec3 direction = portalCrossing.direction();
        float yaw = HighwayCourse.yaw(Vec3.ZERO,direction);
        setFlightLook(yaw,0);
        // Ordinary walking speed on the ground; a bounded flight speed keeps the far exit loaded.
        double speed = portalWalkPaused ? .24 : Math.min(.8,Math.max(.24,speedBps.get()/20));
        double y = portalWalkPaused ? event.movement.y : 0;
        ((IVec3)event.movement).meteor$set(direction.x*speed,y,direction.z*speed);
        lastControlledPosition = mc.player.position();
    }

    private boolean startWalkFallback(String reason) {
        return startWalkFallback(reason, walkDistance.get());
    }

    private boolean startWalkFallback(String reason, int blocks) {
        if (!walkFallback.get()) {
            warning("%s Baritone walk fallback is off.", reason);
            return false;
        }
        BaritoneBridge bridge;
        try {
            bridge = BaritoneBridge.create();
        } catch (LinkageError | RuntimeException e) {
            warning("%s Baritone is unavailable: %s", reason, e.getMessage());
            return false;
        }
        if (bridge == null) {
            warning("%s Baritone isn't installed, so the walk fallback is unavailable.", reason);
            return false;
        }
        double distance = Math.min(blocks, Math.max(4, course.remaining(mc.player.position()) - 1));
        // Keep a stable cardinal/diagonal line through the configured destination. Local
        // player offsets and subsequent course recentering must not move the walking goal sideways.
        Vec3 center = walkingLine.target(mc.player.position(), distance);
        walkTarget = BlockPos.containing(center.x, course.targetY(), center.z);
        walkBaritone = bridge;
        walkStarted = false;
        walkTicks = 0;
        walkBlocks = (int) distance;
        if (!walkRecoveryAttempted) {
            walkRecoveryAttempted = true;
            walkingProgress.reset();
            travelSafety.resetProgress(-course.distanceToDestination(mc.player.position()));
        }
        state = State.WALKING;
        warning("%s Landing for a Baritone walk %d blocks ahead.", reason, walkBlocks);
        return true;
    }

    private void tickWalking() {
        if (walkBaritone == null || walkTarget == null) {
            logOut("Baritone recovery lost its walking target; the trip cannot continue safely.");
            return;
        }
        try { walkBaritone.avoidLava(true); }
        catch (RuntimeException | LinkageError e) {
            logOut("Baritone's lava avoidance could not be enabled safely.");
            return;
        }
        if (!walkStarted) {
            if (++walkTicks > WALK_TIMEOUT_TICKS) {
                logOut("Could not land safely for walking recovery; stopping the unattended trip.");
                return;
            }
            if (mc.player.onGround()) {
                Optional<BlockPos> grounded = HighwayRecoveryTargets.standingTarget(walkTarget,
                    Mth.floor(mc.player.getY()), feet -> walkingLine.onLine(feet) && standable(feet));
                if (grounded.isEmpty()) {
                    retryWalk("No loaded, standable walking target was found.");
                    return;
                }
                walkTarget = grounded.get();
                try {
                    walkBaritone.walkToExact(walkTarget.getX(), walkTarget.getY(), walkTarget.getZ());
                    walkStarted = true;
                    walkTicks = 0;
                    info("Baritone is walking %d blocks ahead.", walkBlocks);
                } catch (LinkageError | RuntimeException e) {
                    warning("Baritone couldn't start the walk: %s", e.getMessage());
                    retryWalk("Baritone could not start the recovery walk.");
                }
            }
            return;
        }
        boolean arrived;
        try { arrived = walkBaritone.atWalkingTarget(walkTarget.getX(), walkTarget.getY(), walkTarget.getZ()); }
        catch (RuntimeException | LinkageError e) {
            retryWalk("Baritone recovery arrival could not be checked.");
            return;
        }
        if (arrived) {
            try {
                walkBaritone.stop();
            } catch (LinkageError | RuntimeException e) {
                MeteorClient.LOG.warn("Stopping the Baritone walk failed", e);
            }
            walkBaritone = null;
            walkStarted = false;
            walkBlocks = 0;
            walkFailures = 0;
            walkRecoveryAttempted = false;
            navigation.reset();
            recenterCourseAt(mc.player.position());
            blockedAttempts = 0;
            takeoffAttempts = 0;
            state = State.TAKING_OFF;
            takeoff.reset(true);
            climbTicks = 0;
            info("Baritone walk done; taking off again.");
            return;
        }
        BaritoneBridge.WalkingProgress progress;
        try { progress = walkBaritone.walkingProgress(); }
        catch (RuntimeException | LinkageError e) {
            retryWalk("Baritone recovery progress could not be read.");
            return;
        }
        if (walkingProgress.stalled(progress == null ? null : progress.path(),
            progress == null ? 0 : progress.position(), noProgressTimeout.get() * 20,
            mc.player.isUsingItem() && mc.player.getUseItem().has(DataComponents.FOOD))) {
            logOut("Baritone has not advanced along its path for " + noProgressTimeout.get() + " seconds.");
        }
    }

    private void retryWalk(String reason) {
        try { if (walkBaritone != null) walkBaritone.stop(); }
        catch (RuntimeException | LinkageError e) { MeteorClient.LOG.warn("Stopping recovery walk failed", e); }
        walkBaritone = null;
        walkStarted = false;
        if (++walkFailures >= 8) {
            logOut("Repeated walking recovery failed; stopping rather than looping indefinitely.");
            return;
        }
        int next = Math.min(200, Math.max(20, walkBlocks + 20));
        if (!startWalkFallback(reason, next)) {
            logOut("Baritone recovery could not be restarted; flight is still blocked.");
        }
    }

    boolean loaded(AABB box) {
        for (int x=Mth.floor(box.minX/16);x<=Mth.floor(Math.nextDown(box.maxX/16));x++) {
            for (int z=Mth.floor(box.minZ/16);z<=Mth.floor(Math.nextDown(box.maxZ/16));z++) {
                if (!mc.level.hasChunk(x,z)) return false;
            }
        }
        return true;
    }

    private boolean standable(BlockPos feet) {
        AABB standing = new AABB(feet.getX()+.2,feet.getY(),feet.getZ()+.2,
            feet.getX()+.8,feet.getY()+1.8,feet.getZ()+.8);
        return loaded(standing.expandTowards(0,-1,0)) && mc.level.noCollision(mc.player,standing)
            && mc.level.getBlockCollisions(mc.player,standing.move(0,-.15,0)).iterator().hasNext()
            && !HighwayLavaSafety.intersects(standing, Vec3.ZERO, lavaProbe)
            && !mc.level.getFluidState(feet).is(FluidTags.LAVA)
            && !mc.level.getFluidState(feet).is(FluidTags.WATER);
    }

    private double centerOffsetAt(Vec3 at) {
        if (course == null) return 0;
        double max = Math.max(3, Math.min(8, corridorWidth.get() * .5 + 2));
        return HighwayCenter.offset(mc.player.getBoundingBox(), at, course.perpendicular(), course.targetY(),
            new HighwayCenter.Config(max, .5), strictProbe);
    }

    /** Reset the course origin to the current position, centered in the free cross-section. */
    private void recenterCourseAt(Vec3 at) {
        if (course == null || course.distanceToDestination(at) < 1) return;
        double offset = centerOffsetAt(at);
        Vec3 origin = new Vec3(
            at.x + course.perpendicular().x * offset,
            course.targetY(),
            at.z + course.perpendicular().z * offset
        );
        course = new HighwayCourse(origin, course.destination(), course.targetY(), course.arrivalDistance());
        navigation.reset();
        lastProgressRemaining = course.distanceToDestination(mc.player.position());
    }

    private boolean validDestination(boolean resuming) {
        BlockPos target = destination.get();
        if (target == null) {
            error("Set a destination first.");
            return false;
        }
        if (lockAltitude.get() && (targetY.get() < minY.get() || targetY.get() > maxY.get())) {
            error("Target Y must be inside the min/max altitude band.");
            return false;
        }
        double dx = target.getX() + .5 - mc.player.getX();
        double dz = target.getZ() + .5 - mc.player.getZ();
        if (!resuming && dx * dx + dz * dz < 64 * 64) {
            error("Destination must be at least 64 blocks away.");
            return false;
        }
        return true;
    }

    private void updatePortalHit() {
        HighwayCourse course = this.course;
        if (course == null || mc.player == null) return;
        HighwayCourse.Waypoint aim = course.waypoint(mc.player.position(), lookahead.get(), LATERAL_GAIN);
        portalHit = PortalLookahead.first(mc.player.getBoundingBox(), direction(aim.yaw()),
            portalDistance.get(), portalProbe).orElse(null);
        portalInside = PortalLookahead.contains(mc.player.getBoundingBox(), portalProbe);
    }

    private boolean lavaAhead() {
        if (mc.player == null || course == null) return false;
        HighwayCourse.Waypoint aim = course.waypoint(mc.player.position(), lookahead.get(), LATERAL_GAIN);
        AABB body = mc.player.getBoundingBox().inflate(.25);
        double distance = probeDistance.get();
        return HighwayLavaSafety.intersects(body, direction(aim.yaw()).scale(distance), lavaProbe)
            || HighwayLavaSafety.intersects(body, direction(flightYaw).scale(distance), lavaProbe);
    }

    private void avoidLava() {
        if (state == State.WALKING) return;
        int distance = Math.max(walkDistance.get(), probeDistance.get() + 4);
        if (startWalkFallback("Lava ahead; handing the blocked stretch to Baritone.", distance)) {
            notifyWebhook(HighwayWebhook.Event.LAVA_AVOIDANCE,
                "No safe flying route past the lava was found. Landing in a safe place so Baritone can route around it before flight resumes.", routeFields());
        } else {
            state = State.BLOCKED;
            retryTicks = 0;
            if (!warnedLavaRecovery) {
                warnedLavaRecovery = true;
                warning("Lava blocks flight and Baritone recovery is unavailable; holding and retrying the route.");
                notifyWebhook(HighwayWebhook.Event.NEEDS_HELP,
                    "Flight could not pass the lava and Baritone recovery is unavailable. Holding safely and retrying; the no-progress watchdog remains active.", routeFields());
            }
        }
    }

    private boolean clearSwept(AABB swept, boolean allowFloor) {
        Motion output = controller == null ? null : controller.output();
        Vec3 motion = output == null ? Vec3.ZERO : new Vec3(output.x(), output.y(), output.z());
        for (BlockPos pos : BlockPos.betweenClosed(
            (int) Math.floor(swept.minX), (int) Math.floor(swept.minY), (int) Math.floor(swept.minZ),
            (int) Math.floor(swept.maxX), (int) Math.floor(swept.maxY), (int) Math.floor(swept.maxZ))) {
            BlockPos fixed = pos.immutable();
            ProbeBlock cached = probeCache.get(mc.level, mc.player.tickCount, blockRevision.get(), fixed.asLong(), () -> {
                BlockState block = mc.level.getBlockState(fixed);
                VoxelShape shape = block.getCollisionShape(mc.level,fixed,CollisionContext.empty());
                return new ProbeBlock(block,shape.isEmpty() ? null : shape.bounds().move(fixed));
            });
            BlockState state = cached.state();
            if (state.getFluidState().is(FluidTags.LAVA)
                && state.getFluidState().getAABB(mc.level, fixed).intersects(swept)) return false;
            if (state.is(Blocks.NETHER_PORTAL)) {
                if (portalMode.get() == PortalMode.Pass) continue;
                return false;
            }
            AABB box = cached.collision();
            if (box == null) continue;
            if (!box.intersects(swept)) continue;
            if (allowFloor && FlightCorridor.floorContactAllowed(mc.player.getBoundingBox(), motion, box.maxY)) continue;
            return false;
        }
        return true;
    }

    private double floorClearance(double margin) {
        FlightCorridor.Result below = FlightCorridor.inspect(mc.player.getBoundingBox(), new Vec3(0, -1, 0), 32, floorProbe);
        return below.reason() == FlightCorridor.Reason.CLEAR ? Double.POSITIVE_INFINITY
            : Math.max(0, below.clearDistance() - margin);
    }

    private ServerGlide.Context glide(Vec3 natural, int rockets, float yaw, float pitch) {
        return new ServerGlide.Context(new Motion(natural.x, natural.y, natural.z), rockets,
            pitch, yaw, mc.player.getGravity(), mc.player.hasEffect(net.minecraft.world.effect.MobEffects.SLOW_FALLING),
            this::resolveCollision);
    }

    private Motion resolveCollision(AABB body, Motion movement) {
        Vec3 vec = new Vec3(movement.x(), movement.y(), movement.z());
        List<VoxelShape> entities = mc.level.getEntityCollisions(mc.player, body.expandTowards(vec));
        if (passEndCrystals.get() && !entities.isEmpty()) {
            List<EndCrystal> crystals = mc.level.getEntitiesOfClass(EndCrystal.class, body.expandTowards(vec));
            if (!crystals.isEmpty()) {
                List<VoxelShape> filtered = new ArrayList<>(entities.size());
                for (VoxelShape shape : entities) {
                    boolean crystal = false;
                    for (EndCrystal entity : crystals) {
                        if (shape.bounds().intersects(entity.getBoundingBox())) {
                            crystal = true;
                            break;
                        }
                    }
                    if (!crystal) filtered.add(shape);
                }
                entities = filtered;
            }
        }
        Vec3 allowed = net.minecraft.world.entity.Entity.collideBoundingBox(mc.player, vec, body, mc.level, entities);
        return new Motion(allowed.x, allowed.y, allowed.z);
    }

    private float smoothYaw(float target) {
        double delta = Mth.wrapDegrees(target - flightYaw);
        double desired = Math.clamp(delta, -turnRate.get(), turnRate.get());
        double step = Math.clamp(desired, lastYawStep - 3, lastYawStep + 3);
        if (Math.signum(step) == Math.signum(delta) && Math.abs(step) > Math.abs(delta)) step = delta;
        return flightYaw + (float) step;
    }

    private void setFlightLook(float yaw, float pitch) {
        lastYawStep = Mth.wrapDegrees(yaw - flightYaw);
        flightYaw = Mth.wrapDegrees(yaw);
        flightPitch = Mth.clamp(pitch, -90, 90);
        // Camera rendering is separate; server and player always receive the actual flight rotation.
        mc.player.setYRot(flightYaw);
        mc.player.setXRot(flightPitch);
    }

    private Vec3 direction(float yaw) {
        double radians = Math.toRadians(yaw);
        return new Vec3(-Math.sin(radians), 0, Math.cos(radians));
    }

    private AABB detourBody(AABB body) {
        double bodyWidth = Math.max(body.maxX - body.minX, body.maxZ - body.minZ);
        double extra = Math.max(0, detourWidth.get() - bodyWidth) * .5;
        return extra > 0 ? body.inflate(extra, 0, extra) : body;
    }

    private double pathSpeed() {
        if (path.size() < 2) return speedBps.get();
        Vec3 position = mc.player.position();
        AABB body = mc.player.getBoundingBox();
        for (Vec3 point : path) {
            if (Math.abs(point.y - preferredY) > .5) return Math.min(speedBps.get(), pathSpeedBps.get());
            AABB at = body.move(point.subtract(position));
            if (!strictProbe.clear(at.expandTowards(0, .75, 0).inflate(.2, 0, .2))) {
                return Math.min(speedBps.get(), pathSpeedBps.get());
            }
        }
        return speedBps.get();
    }

    private double cruiseY() {
        return altitudeBlocked ? Math.min(preferredY, mc.player.getY()) : preferredY;
    }

    private boolean needsClimb() {
        return mc.player != null && preferredY > mc.player.getY() + CLIMB_TOLERANCE;
    }

    private boolean openAbove() {
        return mc.level.noCollision(mc.player,
            mc.player.getBoundingBox().expandTowards(0, HEADROOM, 0));
    }

    private void zeroHorizontal(PlayerMoveEvent event) {
        ((IVec3) event.movement).meteor$set(0, event.movement.y, 0);
    }

    private void finish(String message) {
        if (state == State.DONE) return;
        state = State.DONE;
        if (mc.player != null) mc.player.setXRot(0);
        info(message);
        notifyWebhookFinished(message);
        toggle();
    }

    private void notifyWebhookFinished(String message) {
        if (message.equals("Stopped in a new dimension")) {
            if (!webhookLifecycle.terminal()) return;
            String now = dimensionId();
            notifyWebhook(HighwayWebhook.Event.DIMENSION,
                "The server moved you to " + dimensionName(now) + ", so the route stopped.",
                new HighwayWebhook.Field("↩️ From", dimensionName(dimension), true),
                new HighwayWebhook.Field("➡️ To", dimensionName(now), true));
            return;
        }
        boolean arrived = message.equals("Arrived") || message.equals("Landed");
        notifyWebhook(arrived ? HighwayWebhook.Event.FINISHED : HighwayWebhook.Event.STOPPED, message, routeFields());
    }

    private boolean directClear(AABB body, float yaw) {
        // An elevated route is not finished while returning to cruise Y would enter lava.
        return HighwayDetour.clearAt(body, cruiseY(), yaw, probeDistance.get(), strictProbe);
    }

    private void planPath(AABB body) {
        HighwayPathfinder.Path found = HighwayPathfinder.find(body, mc.player.position(), course.direction(),
            preferredY, new HighwayPathfinder.Config(corridorWidth.get(), corridorHeight.get(), PATH_FORWARD_STEPS,
                PATH_FORWARD_STEP, PATH_LATERAL_STEP, PATH_VERTICAL_STEP, minY.get(), maxY.get()), strictProbe);
        navigation.commit(found);
        path = navigation.points();
        if (debug.get()) {
            info("Path %s: %d points, %d steps.", found.reason(), path.size(), found.progress());
        }
    }

    /** Ends the trip and disables reconnect before a safety disconnect. */
    private void logOut(String reason) {
        notifyWebhook(HighwayWebhook.Event.SAFETY_LOGOUT,
            reason + " Highway Fly disconnected before the trip could continue.", routeFields());
        MutableComponent message = Component.literal("[Highway Fly] " + reason);
        // End and clear the trip before disconnecting, so GameLeft cannot arm recovery or reconnect.
        safetyDisconnect = true;
        toggle();
        // Reconnecting straight away would put the player back in the air on the same elytra.
        AutoReconnect autoReconnect = Modules.get().get(AutoReconnect.class);
        if (autoReconnect != null && autoReconnect.isActive()) {
            autoReconnect.toggle();
            message.append(Component.literal("\n\nAutoReconnect was turned off.").withColor(0xFF808080));
        }
        PendingSwapBack.cancel();
        mc.player.connection.handleDisconnect(new ClientboundDisconnectPacket(message));
    }

    /** Whether inventory clicks are safe: no container screen open and nothing held on the cursor. */
    private boolean canUseInventory() {
        return mc.player != null && mc.gameMode != null
            && mc.player.containerMenu == mc.player.inventoryMenu
            && mc.player.inventoryMenu.getCarried().isEmpty();
    }

    /** Remaining durability of the vanilla elytra, otherwise -1. */
    private static int elytraRemaining(ItemStack stack) {
        if (!stack.is(Items.ELYTRA)) return -1;
        return stack.isDamageableItem() ? stack.getMaxDamage() - stack.getDamageValue() : Integer.MAX_VALUE;
    }

    /** An elytra with one durability left can no longer glide, so two are needed to start. */
    private static boolean usableElytra(ItemStack stack) {
        return elytraRemaining(stack) > 1;
    }

    /**
     * Puts the most durable spare elytra from the hotbar, main inventory or offhand into the chest slot. A single swap
     * click exchanges both items, so the displaced item stays in the spare's slot even with a full inventory.
     *
     * @return the remaining durability of the elytra put on, or -1 when no spare above the threshold is reachable
     */
    private int swapInElytra(int threshold) {
        if (!canUseInventory()) return -1;
        var chestSlot = mc.player.inventoryMenu.getSlot(6);
        // Slot permissions also cover a worn Curse of Binding item; never pick it up and strand it on the cursor.
        if (!chestSlot.mayPickup(mc.player)) return -1;
        Inventory inventory = mc.player.getInventory();
        int[] spares = new int[Inventory.SLOT_OFFHAND + 1];
        Arrays.fill(spares, -1);
        for (int slot = 0; slot < Inventory.INVENTORY_SIZE; slot++) {
            ItemStack stack = inventory.getItem(slot);
            if (stack.is(Items.ELYTRA) && chestSlot.mayPlace(stack)
                && !EnchantmentHelper.has(stack, EnchantmentEffectComponents.PREVENT_ARMOR_CHANGE)) {
                spares[slot] = elytraRemaining(stack);
            }
        }
        ItemStack offhand = inventory.getItem(Inventory.SLOT_OFFHAND);
        if (offhand.is(Items.ELYTRA) && chestSlot.mayPlace(offhand)
            && !EnchantmentHelper.has(offhand, EnchantmentEffectComponents.PREVENT_ARMOR_CHANGE)) {
            spares[Inventory.SLOT_OFFHAND] = elytraRemaining(offhand);
        }
        int slot = ElytraReplacement.best(spares, threshold);
        if (slot < 0) return -1;
        // The click swap leaves whatever was worn in the elytra's slot.
        ItemStack worn = mc.player.getItemBySlot(EquipmentSlot.CHEST);
        boolean chestplate = !worn.isEmpty() && !worn.is(Items.ELYTRA)
            && mc.player.getEquipmentSlotForItem(worn) == EquipmentSlot.CHEST;
        InvUtils.move().from(slot).toArmor(2);
        if (elytraRemaining(mc.player.getItemBySlot(EquipmentSlot.CHEST)) <= threshold) return -1;
        if (chestplate && chestplateSlot < 0) chestplateSlot = slot;
        return spares[slot];
    }

    /** Wear armour only after landing; the walking controller may then jump without demanding wings again. */
    void equipRecoveryChestplate() {
        if (!swapElytra.get() || !swapBack.get() || mc.player == null || !mc.player.onGround()
            || mc.player.isFallFlying() || !canUseInventory()
            || dev.tntcoating.core.InventorySlotLoan.INSTANCE.isBorrowing()
            || dev.tntcoating.core.InventoryWeaponAttack.INSTANCE.isPending()) return;
        ItemStack worn = mc.player.getItemBySlot(EquipmentSlot.CHEST);
        if (!worn.isEmpty() && !worn.is(Items.ELYTRA)) return;
        var chestSlot = mc.player.inventoryMenu.getSlot(6);
        if (!chestSlot.mayPickup(mc.player)) return;
        List<AutoWaspChestSwap.Candidate> candidates = new ArrayList<>();
        Inventory inventory = mc.player.getInventory();
        for (int index = 0; index <= Inventory.INVENTORY_SIZE; index++) {
            int slot = index == Inventory.INVENTORY_SIZE ? Inventory.SLOT_OFFHAND : index;
            ItemStack stack = inventory.getItem(slot);
            if (stack.isEmpty() || stack.has(DataComponents.GLIDER) || !chestSlot.mayPlace(stack)
                || mc.player.getEquipmentSlotForItem(stack) != EquipmentSlot.CHEST
                || EnchantmentHelper.has(stack, EnchantmentEffectComponents.PREVENT_ARMOR_CHANGE)) continue;
            double[] armour = new double[2];
            stack.forEachModifier(EquipmentSlot.CHEST, (attribute, modifier) -> {
                if (attribute.equals(Attributes.ARMOR)) armour[0] += modifier.amount();
                else if (attribute.equals(Attributes.ARMOR_TOUGHNESS)) armour[1] += modifier.amount();
            });
            int durability = stack.isDamageableItem() ? stack.getMaxDamage() - stack.getDamageValue() : Integer.MAX_VALUE;
            candidates.add(new AutoWaspChestSwap.Candidate(slot, false, durability, armour[0], armour[1]));
        }
        int slot = AutoWaspChestSwap.best(candidates, false);
        if (slot < 0) return;
        InvUtils.move().from(slot).toArmor(2);
        // The elytra is now stored in this slot. swapInElytra remembers the armour's new slot at takeoff.
        if (!mc.player.getItemBySlot(EquipmentSlot.CHEST).is(Items.ELYTRA)) chestplateSlot = -1;
    }

    /** Puts the displaced chestplate back on now when the player is safely grounded, or once they land. */
    private void restoreChestplate() {
        if (chestplateSlot < 0) return;
        if (!swapElytra.get() || !swapBack.get() || mc.player == null) {
            chestplateSlot = -1;
            return;
        }
        if (mc.player.onGround() && canUseInventory()) {
            swapBackNow();
        } else if (mc.player.getItemBySlot(EquipmentSlot.CHEST).is(Items.ELYTRA)) {
            PendingSwapBack.schedule(chestplateSlot);
            chestplateSlot = -1;
        } else {
            chestplateSlot = -1;
        }
    }

    private void swapBackNow() {
        int slot = chestplateSlot;
        chestplateSlot = -1;
        if (slot < 0 || mc.player == null) return;
        ItemStack worn = mc.player.getItemBySlot(EquipmentSlot.CHEST);
        ItemStack stored = mc.player.getInventory().getItem(slot);
        if (worn.is(Items.ELYTRA) && !stored.isEmpty() && !stored.is(Items.ELYTRA)
            && mc.player.getEquipmentSlotForItem(stored) == EquipmentSlot.CHEST) {
            InvUtils.move().from(slot).toArmor(2);
        }
    }

    /** Finishes a swap-back once the same player is on the ground with the inventory free. */
    private static final class PendingSwapBack {
        private static PendingSwapBack pending;
        private final int slot;
        private final int playerId;

        private PendingSwapBack(int slot, int playerId) {
            this.slot = slot;
            this.playerId = playerId;
        }

        static void schedule(int slot) {
            if (MeteorClient.mc.player == null) return;
            cancel();
            pending = new PendingSwapBack(slot, MeteorClient.mc.player.getId());
            MeteorClient.EVENT_BUS.subscribe(pending);
        }

        static void cancel() {
            if (pending == null) return;
            MeteorClient.EVENT_BUS.unsubscribe(pending);
            pending = null;
        }

        @EventHandler
        private void onTick(TickEvent.Post event) {
            LocalPlayer player = MeteorClient.mc.player;
            if (player == null) return;
            if (player.getId() != playerId || player.isDeadOrDying()) {
                cancel();
                return;
            }
            if (!player.onGround() || player.containerMenu != player.inventoryMenu
                || !player.inventoryMenu.getCarried().isEmpty()) return;
            ItemStack worn = player.getItemBySlot(EquipmentSlot.CHEST);
            ItemStack stored = player.getInventory().getItem(slot);
            cancel();
            if (worn.is(Items.ELYTRA) && !stored.isEmpty() && !stored.is(Items.ELYTRA)
                && player.getEquipmentSlotForItem(stored) == EquipmentSlot.CHEST) {
                InvUtils.move().from(slot).toArmor(2);
            }
        }
    }

    private void reset() {
        portalCrossing = null;
        portalCrossingTicks = 0;
        portalWalkPaused = false;
        probeCache.clear();
        navigation.reset();
        failedSwaps = 0;
        lastYawStep = 0;
        walkFailures = 0;
        walkRecoveryAttempted = false;
        warnedLavaRecovery = false;
        walkingProgress.reset();
        course = null;
        walkingLine = null;
        controller = null;
        lastControlledPosition = null;
        dimension = null;
        state = State.TAKING_OFF;
        preferredY = 0;
        activeY = 0;
        detourY = 0;
        detourYawOffset = 0;
        detourTicks = 0;
        path = List.of();
        portalHit = null;
        portalInside = false;
        retryTicks = 0;
        climbTicks = 0;
        warnedWaiting = false;
        warnedTakeoff = false;
        chestplateSlot = -1;
        altitudeBlocked = false;
        walkBaritone = null;
        walkTarget = null;
        walkStarted = false;
        walkTicks = 0;
        walkBlocks = 0;
        blockedAttempts = 0;
        takeoffAttempts = 0;
        lastProgressRemaining = Double.MAX_VALUE;
        rubberbandCount = 0;
        lastRubberbandTick = 0;
        correction.set(null);
        takeoff.reset(false);
    }

    private final HighwayDashboard dashboard = new HighwayDashboard(this);
    AutoSetHome homes() { return Modules.get().get(AutoSetHome.class); }
    AntiPortal portalGuard() { return Modules.get().get(AntiPortal.class); }
    AutoLobby lobby() { return Modules.get().get(AutoLobby.class); }

    private boolean lobbyEnabled() { return lobby().isActive(); }

    /** Preserve the saved trip's arrival checks, including destinations closer than a new trip permits. */
    void resumeRecoveredFlight() {
        if (isActive()) { startFlight(true); return; }
        resumingRecovery = true;
        try { enable(); }
        finally { resumingRecovery = false; }
    }

    private void syncHelpers() {
        boolean running = isActive() && !profileLoading && !stoppingTrip;
        homes().managed(running && autoSetHome.get());
        portalGuard().managed(running && antiPortal.get());
        lobby().managed(running && lobbyRecovery.get());
    }

    void helperDisabled(HighwayHelper helper) {
        if (profileLoading) return;
        if (helper instanceof AutoSetHome) autoSetHome.set(false);
        if (helper instanceof AntiPortal) antiPortal.set(false);
        if (helper instanceof AutoLobby) lobbyRecovery.set(false);
    }

    boolean homesAllowed() {
        return !profileLoading && !session.recovering() && (!isActive()
            || session.setHomeAllowed(true, dimensionId(), serverAddress()));
    }

    boolean portalsAllowed() {
        return !profileLoading && !session.recovering() && (!isActive() || session.portalProtectionAllowed(true));
    }

}
