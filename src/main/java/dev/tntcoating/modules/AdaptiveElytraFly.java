package dev.tntcoating.modules;

import com.google.gson.JsonObject;
import dev.tntcoating.TntCoatingAddon;
import dev.tntcoating.compat.MeteorUpgrades;
import dev.tntcoating.core.AdaptiveFlightController.Motion;
import dev.tntcoating.core.AdaptiveFlightEngine;
import dev.tntcoating.core.AdaptiveFlightStep;
import dev.tntcoating.core.ElytraBoostDelay;
import dev.tntcoating.core.FlightCorridor;
import dev.tntcoating.core.PacketCaptureLog;
import dev.tntcoating.core.ServerGlide;
import dev.tntcoating.core.ServerMoveLimit;
import dev.tntcoating.core.SmartElytraSwap;
import dev.tntcoating.mixin.FireworkRocketAccessor;
import dev.tntcoating.mixin.LocalPlayerAccessor;
import java.io.IOException;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import meteordevelopment.meteorclient.MeteorClient;
import meteordevelopment.meteorclient.events.entity.player.PlayerMoveEvent;
import meteordevelopment.meteorclient.events.game.GameLeftEvent;
import meteordevelopment.meteorclient.events.packets.PacketEvent;
import meteordevelopment.meteorclient.events.render.Render3DEvent;
import meteordevelopment.meteorclient.events.world.TickEvent;
import meteordevelopment.meteorclient.gui.GuiTheme;
import meteordevelopment.meteorclient.gui.widgets.WLabel;
import meteordevelopment.meteorclient.gui.widgets.WWidget;
import meteordevelopment.meteorclient.mixininterface.IVec3;
import meteordevelopment.meteorclient.pathing.PathManagers;
import meteordevelopment.meteorclient.settings.BoolSetting;
import meteordevelopment.meteorclient.settings.DoubleSetting;
import meteordevelopment.meteorclient.settings.EnumSetting;
import meteordevelopment.meteorclient.settings.IntSetting;
import meteordevelopment.meteorclient.settings.Setting;
import meteordevelopment.meteorclient.settings.SettingGroup;
import meteordevelopment.meteorclient.systems.modules.Module;
import meteordevelopment.meteorclient.systems.modules.Modules;
import meteordevelopment.meteorclient.systems.modules.movement.AutoWalk;
import meteordevelopment.meteorclient.systems.modules.movement.elytrafly.ElytraFlightMode;
import meteordevelopment.meteorclient.systems.modules.movement.elytrafly.ElytraFlightModes;
import meteordevelopment.meteorclient.systems.modules.movement.elytrafly.ElytraFly;
import meteordevelopment.meteorclient.systems.modules.render.Freecam;
import meteordevelopment.meteorclient.utils.misc.input.Input;
import meteordevelopment.meteorclient.utils.player.InvUtils;
import meteordevelopment.meteorclient.utils.player.Rotations;
import meteordevelopment.meteorclient.utils.player.SlotUtils;
import meteordevelopment.orbit.EventHandler;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.multiplayer.PlayerInfo;
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
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.inventory.ContainerInput;
import net.minecraft.world.entity.MoverType;
import net.minecraft.world.entity.PositionMoveRotation;
import net.minecraft.world.entity.projectile.FireworkRocketEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.component.Fireworks;
import net.minecraft.world.item.enchantment.EnchantmentEffectComponents;
import net.minecraft.world.item.enchantment.EnchantmentHelper;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.VoxelShape;

/** Optional replacement for Meteor's Vanilla movement; flight planners continue to own their normal keys/settings. */
public final class AdaptiveElytraFly extends Module {
    public enum BoostKeyMode { Hold, Toggle }

    private final SettingGroup general = settings.getDefaultGroup();
    private final SettingGroup boost = settings.createGroup("Unlimited Boost");
    private final SettingGroup terrain = settings.createGroup("Terrain");
    private final SettingGroup diagnostics = settings.createGroup("Diagnostics");
    private final Setting<Boolean> smartElytraSwap = general.add(new BoolSetting.Builder().name("smart-elytra-swap")
        .description("Wear a chestplate on the ground. Press Jump again in the air to equip an elytra from your inventory and start flying in the same attempt.")
        .defaultValue(true).build());
    private final Setting<Double> acceleration = general.add(new DoubleSetting.Builder().name("acceleration")
        .description("Velocity change per tick while accelerating, in blocks per tick squared. Adaptive turn assistance handles high-speed heading changes separately.")
        .defaultValue(0.15).range(0.01, 2).sliderRange(0.01, 0.5).build());
    private final Setting<Double> braking = general.add(new DoubleSetting.Builder().name("braking")
        .description("Gentlest braking, in blocks per tick squared, used around cruise speed. Faster flight brakes harder only as much as needed: enough that a stop from unlimited boost's top speed fits the lookahead, and up to an instant stop when something appears closer than that. Higher values need less clear terrain ahead.")
        .defaultValue(0.3).range(0.01, 2).sliderRange(0.01, 1).build());
    private final Setting<Double> adaptiveTurnRate = general.add(new DoubleSetting.Builder().name("adaptive-turn-rate")
        .description("Heading assistance in degrees per tick. Rises with speed to double at the adaptive engine's maximum, so fast turns stop sliding sideways, and fades out at low speed, where steering keeps its precise momentum feel. Zero restores momentum steering.")
        .defaultValue(15).range(0, 45).sliderRange(0, 45).build());
    private final Setting<Boolean> sprintBoost = general.add(new BoolSetting.Builder().name("sprint-boost")
        .description("Hold your Sprint key to boost horizontal movement and deliberate climb/descent. With unlimited-boost on, Sprint drives the unlimited boost ramp; otherwise it raises the adaptive engine to boost-max-bps and vertical-boost-max-bps.")
        .defaultValue(true).build());
    private final Setting<BoostKeyMode> boostKeyMode = general.add(new EnumSetting.Builder<BoostKeyMode>()
        .name("boost-key-mode")
        .description("Hold: boost while Sprint is held. Toggle: each Sprint press turns boost on or off; it turns off when you stop gliding.")
        .defaultValue(BoostKeyMode.Hold).visible(sprintBoost::get).build());
    private final Setting<Boolean> toggleOffWhenIdle = general.add(new BoolSetting.Builder().name("toggle-off-when-idle")
        .description("In Toggle mode, turn boost off once you stop pressing every movement key (WASD, Jump, Sneak), unless Meteor's Auto Walk, Elytra Fly autopilot, Baritone or Baritone Elytra is moving you.")
        .defaultValue(true).visible(() -> sprintBoost.get() && boostKeyMode.get() == BoostKeyMode.Toggle).build());
    private final Setting<Double> cruiseMaxBps = general.add(new DoubleSetting.Builder().name("cruise-max-bps")
        .description("Horizontal cruise speed cap in blocks per second. Cruise also never passes the adaptive engine's terrain-safe maximum (about 118 b/s with defaults; see Flight Diagnostics).")
        .defaultValue(41.2).range(0, 480).sliderRange(0, 120).decimalPlaces(1).build());
    private final Setting<Double> verticalCruiseMaxBps = general.add(new DoubleSetting.Builder().name("vertical-cruise-max-bps")
        .description("Jump/Sneak cruise speed cap in blocks per second, up to 60.")
        .defaultValue(21).range(0, 60).sliderRange(0, 60).decimalPlaces(1).build());
    private final Setting<Double> boostMaxBps = general.add(new DoubleSetting.Builder().name("boost-max-bps")
        .description("Horizontal Sprint/boost speed cap in blocks per second. Boost accelerates along its fixed curve and stops at this speed instead of tapering towards it. The curve tops out at 300 b/s (overdrive: 340, or 480 with split-moves); with unlimited-boost off, Sprint also never passes the adaptive engine's maximum.")
        .defaultValue(300).range(0, 480).sliderRange(0, 340).decimalPlaces(1).visible(sprintBoost::get).build());
    private final Setting<Double> verticalBoostMaxBps = general.add(new DoubleSetting.Builder().name("vertical-boost-max-bps")
        .description("Sprint + Jump/Sneak speed cap in blocks per second, up to 60. Vertical boost accelerates along its fixed curve and stops at this speed.")
        .defaultValue(60).range(0, 60).sliderRange(0, 60).decimalPlaces(1).visible(sprintBoost::get).build());
    private final Setting<Boolean> maneuverMode = general.add(new BoolSetting.Builder().name("maneuver-mode")
        .description("While moving horizontally, climbing or descending and turning at once, hold total 3D speed to maneuver-max-bps to reduce setbacks. Boost speeds up again from the cap half a second after you stop.")
        .defaultValue(true).build());
    private final Setting<Double> maneuverMaxBps = general.add(new DoubleSetting.Builder().name("maneuver-max-bps")
        .description("Total 3D speed cap while maneuvering, in blocks per second.")
        .defaultValue(200).range(20, 480).sliderRange(60, 340).decimalPlaces(1).visible(maneuverMode::get).build());
    private final Setting<Double> verticalAcceleration = general.add(new DoubleSetting.Builder().name("vertical-acceleration")
        .description("Minimum vertical velocity change per tick; vertical-ramp-step speeds it up when far below the target. Vertical braking uses up to twice this, more from fast climbs and dives (mirroring vertical-ramp-step).")
        .defaultValue(.05).range(.005, .5).sliderRange(.005, .15).decimalPlaces(3).build());
    private final Setting<Double> verticalRampStep = general.add(new DoubleSetting.Builder().name("vertical-ramp-step")
        .description("How fast climb/descent speeds up, in blocks per tick squared at rest, tapering to zero at 60 b/s (like VolytraFly's acceleration curve). Zero keeps vertical-acceleration only.")
        .defaultValue(.5).range(0, 5).sliderRange(0, 2).decimalPlaces(2).build());
    private final Setting<Integer> verticalReversalTicks = general.add(new IntSetting.Builder().name("vertical-reversal-ticks")
        .description("Switching straight between Jump and Sneak slows the old climb or descent evenly over this many ticks, then speeds up the other way from a standstill. Zero turns around at once; higher is smoother, but never slower than releasing the key.")
        .defaultValue(2).range(0, 20).sliderRange(0, 10).build());
    private final Setting<Boolean> unlimitedBoost = boost.add(new BoolSetting.Builder().name("unlimited-boost")
        .description("Holding Sprint flies VolytraFly's speed ramp instead of the adaptive engine maximum. Terrain, chunk and server-glide guards still apply. Off restores adaptive Sprint.")
        .defaultValue(true).visible(sprintBoost::get).build());
    private final Setting<Boolean> overdrive = boost.add(new BoolSetting.Builder().name("overdrive")
        .description("Raise the boost curve's top from 300 b/s to the most a vanilla server accepts per tick: 340 b/s (17 blocks per tick), or 480 b/s with split-moves. boost-max-bps still caps it. Any boost above 245 b/s is set back whenever two move packets reach the server in the same tick (lag or ping jitter), so 340 b/s rubber-bands no more often than 300 b/s.")
        .defaultValue(false).visible(this::boostVisible).build());
    private final Setting<Boolean> splitMoves = boost.add(new BoolSetting.Builder().name("split-moves")
        .description("Experimental: while boosting above 200 b/s, also report a half-step position each tick. A vanilla server then allows twice the distance per tick (480 b/s with overdrive) and stops setting back 300-340 b/s when two ticks of packets arrive together. An anticheat that counts movement packets may set you back or kick you instead.")
        .defaultValue(false).visible(this::boostVisible).build());
    private final Setting<Double> boostRampStep = boost.add(new DoubleSetting.Builder().name("boost-ramp-step")
        .description("Horizontal boost acceleration in blocks per tick squared at rest, tapering to zero at the curve's 300 b/s top. boost-max-bps stops the ramp sooner without changing it.")
        .defaultValue(.3).range(.01, 5).sliderRange(.05, 2).decimalPlaces(2).visible(this::boostVisible).build());
    private final Setting<Double> boostVerticalStartSpeed = boost.add(new DoubleSetting.Builder().name("boost-vertical-start-speed")
        .description("Climb/descent speed boost starts at, in blocks per tick, when slower. The vertical boost then follows vertical-ramp-step up to vertical-boost-max-bps.")
        .defaultValue(1.5).range(0, 10).sliderRange(0, 6).decimalPlaces(2).visible(this::boostVisible).build());
    private final Setting<Integer> boostRewindTicks = boost.add(new IntSetting.Builder().name("boost-rewind-ticks")
        .description("A server setback rewinds the boost ramp by this many ticks of acceleration instead of restarting it, and flight keeps its momentum (15 loses under 1% near top speed).")
        .defaultValue(15).range(0, 200).sliderRange(0, 60).visible(this::boostVisible).build());
    private final Setting<Integer> boostSetbackHold = boost.add(new IntSetting.Builder().name("boost-setback-hold")
        .description("After a server setback, hold the rewound boost speed for this many ticks before accelerating again. VolytraFly's acceleration delay: without it a server can pin you in place with back-to-back setbacks.")
        .defaultValue(1).range(0, 20).sliderRange(0, 10).visible(this::boostVisible).build());
    private final Setting<Integer> pinnedGlideTicks = boost.add(new IntSetting.Builder().name("pinned-glide-ticks")
        .description("When two setbacks in a row put you back on exactly the same spot, the server is rejecting every move you make from there. Fly with plain vanilla elytra physics for this many ticks so it accepts one, then boost again from the start speed. Zero disables.")
        .defaultValue(10).range(0, 60).sliderRange(0, 40).build());
    private final Setting<Integer> lookahead = terrain.add(new IntSetting.Builder().name("lookahead")
        .description("Maximum corridor distance to check, in blocks. Work is bounded at 64 blocks per scan.")
        .defaultValue(64).range(8, 64).sliderRange(8, 64).build());
    private final Setting<Double> margin = terrain.add(new DoubleSetting.Builder().name("terrain-margin")
        .description("Space to leave before missing chunks or obstacles.")
        .defaultValue(0.35).range(0.05, 2).sliderRange(0.05, 1).build());
    private final Setting<Boolean> flyIntoUnloadedChunks = terrain.add(new BoolSetting.Builder().name("fly-into-unloaded-chunks")
        .description("Treat chunks your client has not loaded yet as open air instead of stopping at their edge, so boost is not held back by how fast the server sends chunks. Blocks there are invisible to every guard, including the server-glide guard: terrain only the server sees can set you back or deal wall damage.")
        .defaultValue(false).build());
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
    private final Setting<Boolean> steerServerGlide = terrain.add(new BoolSetting.Builder().name("steer-server-glide")
        .description("Near a wall you face, report a level heading away from it, so the server's own glide turns away from the wall within about a third of a second. You can then fly right up to the wall without wall damage instead of being held about two blocks back by server-glide-guard. Your camera does not move; other players see your head turned away while it steers.")
        .defaultValue(true).build());
    private final Setting<Boolean> logSetbacks = diagnostics.add(new BoolSetting.Builder().name("log-setbacks")
        .description("Write every server setback while flying to meteor-client/comet/setback-logs (one file per session, at most 4 MB): speed, boost, ping and whether terrain ahead was loaded, to compare flights by numbers instead of feel.")
        .defaultValue(true).build());

    // Receive events run on Netty. Only these atomic mailboxes are touched there; all flight/world work is client-thread work.
    private final AtomicInteger corrections = new AtomicInteger();
    private final AtomicReference<ClientboundPlayerPositionPacket> glideSetback = new AtomicReference<>();
    private final AtomicReference<ClientboundSetEntityMotionPacket> glideMotion = new AtomicReference<>();
    private final AtomicReference<Vec3> glideKnockback = new AtomicReference<>(Vec3.ZERO);
    private volatile int playerId = Integer.MIN_VALUE;
    private final AdaptiveFlightEngine engine = new AdaptiveFlightEngine(engineSettings(null));
    private final FlightCorridor.Probe probe = new FlightCorridor.Probe() {
        // Unloaded chunks read as air to the block checks below, so allowing them treats them as open space.
        @Override public boolean loaded(int x, int z) {
            return flyIntoUnloadedChunks.get() || mc.level.getChunkSource().hasChunk(x, z);
        }
        @Override public boolean clear(AABB swept) {
            if (!checkBlocks) return true;
            collisionChecks++;
            for (var shape : mc.level.getBlockCollisions(mc.player, swept)) {
                // Leave downward ground contact to Minecraft's collision solver, which sets onGround normally.
                if (!FlightCorridor.floorContactAllowed(mc.player.getBoundingBox(), toVec(engine.output()),
                    shape.max(Direction.Axis.Y))) return false;
            }
            return true;
        }
    };
    // Soft landing looks straight down, where the floor is an obstacle rather than a touchdown to allow.
    private static final Vec3 DOWN = new Vec3(0, -1, 0);
    private static final double FLOOR_SCAN = 32;
    private final FlightCorridor.Probe floorProbe = new FlightCorridor.Probe() {
        @Override public boolean loaded(int x, int z) { return probe.loaded(x, z); }
        @Override public boolean clear(AABB swept) {
            collisionChecks++;
            return !mc.level.getBlockCollisions(mc.player, swept).iterator().hasNext();
        }
    };
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
    private long glideLimitedTicks, glideHits, glideSteeredTicks;
    private boolean glideSteering;
    // The lowest priority is sent last, so the server's next glide tick uses this heading.
    private static final int GLIDE_STEER_PRIORITY = Integer.MIN_VALUE;
    private ClientLevel world;
    private LocalPlayer player;
    private boolean controlling, checkBlocks;
    private int movementTick = Integer.MIN_VALUE;
    private long chunkLimitedTicks, obstacleLimitedTicks;
    private int floorChunkChecks, collisionChecks;
    private String state = "Waiting for flight", hud = state;
    private WLabel statusLabel, speedLabel, boostLabel, countsLabel, glideLabel, frameLabel, checksLabel;
    private double frameAverageMs, framePeakMs, measuredSpeed;
    private boolean sprintWasPressed, boostToggled, movedSinceToggle;
    // A short grace, so switching between movement keys does not turn toggled boost off.
    private static final int IDLE_TOGGLE_OFF_TICKS = 5;
    private int idleTicks;
    private long slowFrames, sampleTime, clientTicks;
    private int setbacks, diagnosticSetbackBase;
    private Vec3 samplePosition, lastControlledPosition;
    // Back-to-back setbacks to one spot pin the flight: vanilla glide for a few ticks breaks the cycle.
    private static final int PINNED_WINDOW_TICKS = 40;
    private Vec3 lastSetbackTarget;
    private long lastSetbackTick;
    private int pinnedTicks;
    private long pinnedEpisodes;
    // Positions this client reported to the server, newest last, so a setback's target identifies the rejected move.
    private record Reported(long tick, Vec3 position, boolean teleport, double horizontalBoost, double verticalBoost) {}
    private static final int REPORTED_HISTORY = 40;
    private final ArrayDeque<Reported> reported = new ArrayDeque<>();
    private final ElytraBoostDelay boostDelay = new ElytraBoostDelay();
    private static final long SETBACK_LOG_BYTES = 4L * 1024 * 1024;
    private PacketCaptureLog setbackLog;
    private boolean setbackLogFailed;

    public AdaptiveElytraFly() {
        super(TntCoatingAddon.CATEGORY, "adaptive-elytra-fly",
            "Smooths Meteor Vanilla Elytra Fly, paces loaded terrain and boosts through server setbacks.");
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

    @Override public void onDeactivate() {
        reset();
        closeSetbackLog();
    }

    @EventHandler private void onGameLeft(GameLeftEvent event) {
        reset();
        closeSetbackLog();
    }

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
        ElytraFly fly = Modules.get().get(ElytraFly.class);
        engine.startTick(engineSettings(fly));
        clientTicks++;
        if (pinnedTicks > 0) pinnedTicks--;
        updateBoostKey();
        playerId = mc.player == null ? Integer.MIN_VALUE : mc.player.getId();
        floorChunkChecks = collisionChecks = 0;
        smartLandingSwap(fly);
        observeBoostDelay();
        if (!eligible(fly)) suspend(reason(fly));
        consumeCorrections();
        updateServerGlide();
        measureSpeed();
        updateStatus();
    }

    /** The server ticks its own glide from the position this tick reported, with the rotation it received. */
    @EventHandler private void onPostTick(TickEvent.Post event) {
        LocalPlayer p = mc.player;
        smartLandingSwap(Modules.get().get(ElytraFly.class));
        if (controlling && p != null) {
            lastControlledPosition = p.position();
            if (p.isFallFlying()) recordReported((LocalPlayerAccessor) p);
        }
        if (serverGlide == null || p == null || mc.level == null || !p.isFallFlying()) return;
        ServerGlide.Tick tick = ServerGlide.tick(glideContext(p, sentRotation ? sentPitch : p.getXRot(),
            sentRotation ? sentYaw : p.getYRot()), p.getBoundingBox());
        serverGlide = tick.velocity();
        if (tick.damage() > 0) glideHits++;
        if (pendingRocketTicks > 0) pendingRocketTicks--;
    }

    /** Runs at vanilla's actual Jump-to-glide check, so holding Jump or ordinary jumps never equip wings. */
    public void prepareSmartTakeoff() {
        if (!canSmartSwap(Modules.get().get(ElytraFly.class)) || mc.screen != null || !mc.isWindowActive()
            || mc.player.onGround() || mc.player.isFallFlying() || mc.player.isInWater()
            || mc.player.isInLava() || mc.player.isPassenger() || mc.player.getAbilities().flying
            || mc.player.hasEffect(MobEffects.LEVITATION)) return;
        ItemStack chest = mc.player.getItemBySlot(EquipmentSlot.CHEST);
        if (ElytraLog.remaining(chest) > 1) return;
        swapChest(true);
    }

    private void smartLandingSwap(ElytraFly fly) {
        if (!canSmartSwap(fly) || !mc.player.onGround()) return;
        ItemStack chest = mc.player.getItemBySlot(EquipmentSlot.CHEST);
        if (chest.isEmpty() || ElytraLog.isGlider(chest)) swapChest(false);
    }

    private boolean canSmartSwap(ElytraFly fly) {
        return isActive() && smartElytraSwap.get() && mc.player != null && mc.level != null && mc.gameMode != null
            && mc.player.isAlive() && !mc.player.isSpectator() && fly != null && fly.isActive()
            && fly.flightMode.get() == ElytraFlightModes.Vanilla && !Modules.get().isActive(Freecam.class)
            && mc.player.containerMenu == mc.player.inventoryMenu
            && mc.player.inventoryMenu.getCarried().isEmpty();
    }

    private void swapChest(boolean glider) {
        // Slot permissions also cover a worn Curse of Binding item; never pick it up and strand it on the cursor.
        var chestSlot = mc.player.inventoryMenu.getSlot(6);
        if (!chestSlot.mayPickup(mc.player)) return;
        var inventory = mc.player.getInventory();
        List<SmartElytraSwap.Candidate> candidates = new ArrayList<>();
        for (int i = 0; i <= Inventory.INVENTORY_SIZE; i++) {
            int slot = i == Inventory.INVENTORY_SIZE ? Inventory.SLOT_OFFHAND : i;
            ItemStack stack = inventory.getItem(slot);
            if (stack.isEmpty() || ElytraLog.isGlider(stack) != glider || !chestSlot.mayPlace(stack)
                || EnchantmentHelper.has(stack, EnchantmentEffectComponents.PREVENT_ARMOR_CHANGE)) continue;
            double[] armor = new double[2];
            if (!glider) stack.forEachModifier(EquipmentSlot.CHEST, (attribute, modifier) -> {
                if (attribute.equals(Attributes.ARMOR)) armor[0] += modifier.amount();
                else if (attribute.equals(Attributes.ARMOR_TOUGHNESS)) armor[1] += modifier.amount();
            });
            int remaining = stack.isDamageableItem() ? stack.getMaxDamage() - stack.getDamageValue() : Integer.MAX_VALUE;
            candidates.add(new SmartElytraSwap.Candidate(slot, glider, remaining, armor[0], armor[1]));
        }
        int slot = SmartElytraSwap.best(candidates, glider);
        if (slot < 0) return;
        if (SlotUtils.isHotbar(slot) || slot == Inventory.SLOT_OFFHAND) {
            // A single click exchanges both items, even with a completely full inventory.
            mc.gameMode.handleContainerInput(mc.player.inventoryMenu.containerId, 6, slot, ContainerInput.SWAP, mc.player);
        } else InvUtils.move().from(slot).toArmor(2);
        if (glider) boostDelay.swapped();
    }

    private void observeBoostDelay() {
        if (mc.player != null) boostDelay.observe(clientTicks,
            ElytraLog.isGlider(mc.player.getItemBySlot(EquipmentSlot.CHEST)), mc.player.isFallFlying());
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
        observeBoostDelay();
        consumeCorrections();
        if (pinnedTicks > 0) {
            // Minecraft's own glide, which a server always accepts, until it stops pinning us in place.
            engine.suspend();
            controlling = true;
            state = "Unpinning";
            lastControlledPosition = mc.player.position();
            updateStatus();
            return true;
        }
        // Keep close-object contact under our control: yielding to stock here
        // would invoke Meteor's unconditional No Crash and trap tight-space movement.
        boolean sprint = boostKeyMode.get() == BoostKeyMode.Toggle ? boostToggled : sprintKeyDown();
        AABB body = mc.player.getBoundingBox();
        if (!engine.begin(sprint, boostDelay.ready(), mc.player.getDeltaMovement().length(), event.movement.length(),
            body, probe)) {
            suspend("Terrain overlap");
            updateStatus();
            return false;
        }
        controlling = true;
        if (movementTick != mc.player.tickCount) {
            movementTick = mc.player.tickCount;
            mode.autoTakeoff();
            mode.handleAutopilot();
            int forward = (mc.options.keyUp.isDown() ? 1 : 0) - (mc.options.keyDown.isDown() ? 1 : 0);
            int right = (mc.options.keyRight.isDown() ? 1 : 0) - (mc.options.keyLeft.isDown() ? 1 : 0);
            ServerGlide.Context glide = serverGlide == null ? null
                : glideContext(mc.player, mc.player.getXRot(), mc.player.getYRot());
            ServerGlide.Rotation steer = glide != null && steerServerGlide.get()
                ? ServerGlide.steer(glide, body, FlightCorridor.Probe.chunksOnly(probe)) : null;
            glideSteering = steer != null;
            if (glideSteering) {
                Rotations.rotate(steer.yaw(), steer.pitch(), GLIDE_STEER_PRIORITY);
                glide = glideContext(mc.player, steer.pitch(), steer.yaw());
                glideSteeredTicks++;
            }
            if (!checkBlocks || !serverGlideGuard.get()) glide = null;
            AdaptiveFlightStep.Result step = engine.step(new AdaptiveFlightEngine.Input(forward, right,
                mc.player.getYRot(), mc.options.keyJump.isDown(), mc.options.keyShift.isDown(), event.movement.y,
                body, probe, () -> checkBlocks ? floorClearance() : Double.POSITIVE_INFINITY, glide,
                this::resolveCollision));
            if (step.glideLimited()) glideLimitedTicks++;
            FlightCorridor.Result ahead = step.corridor();
            if (step.terrainLimited() && ahead != null && ahead.reason() == FlightCorridor.Reason.CHUNKS) {
                state = "Chunk limited";
                chunkLimitedTicks++;
            } else if (ahead != null && ahead.reason() == FlightCorridor.Reason.INVALID) state = "Invalid terrain";
            else if (step.glideLimited()) state = "Glide clearance";
            else if (engine.nearObject())
                state = engine.closeObjectOverride() ? "Sprint close-object override" : "Close-object control";
            else if (step.terrainLimited() && ahead != null && ahead.reason() == FlightCorridor.Reason.OBSTACLE) {
                state = "Obstacle limited";
                obstacleLimitedTicks++;
            } else if (engine.maneuverMode()) state = "Maneuver capped";
            else if (engine.boostActive()) state = "Unlimited boost";
            else if (step.lookaheadLimited()) state = "Lookahead limited";
            else if (engine.sprintBoosting()) state = "Sprint boost";
            else state = "Flying";
            updateStatus();
        } else engine.repeat(body, probe);
        Motion output = engine.output();
        lastControlledPosition = mc.player.position();
        ((IVec3) event.movement).meteor$set(output.x(), output.y(), output.z());
        return true;
    }

    private boolean eligible(ElytraFly fly) {
        return mc.player != null && mc.level != null && fly != null && fly.isActive()
            && fly.flightMode.get() == ElytraFlightModes.Vanilla && mc.player.isFallFlying()
            && ElytraLog.isGlider(mc.player.getItemBySlot(EquipmentSlot.CHEST))
            && !mc.player.isInLava() && !(mc.player.isInWater() && fly.stopInWater.get())
            && !Modules.get().isActive(Freecam.class);
    }

    private String reason(ElytraFly fly) {
        if (mc.level == null || mc.player == null) return "No world";
        if (!fly.isActive()) return "Elytra Fly off";
        if (fly.flightMode.get() != ElytraFlightModes.Vanilla) return "Vanilla required";
        return "Waiting for glide";
    }

    /**
     * Server position corrections. A setback keeps momentum and only rewinds the boost ramps; a teleport far
     * away (a command, a portal plugin) stops controlled movement.
     */
    private void consumeCorrections() {
        int count = corrections.getAndSet(0);
        if (count == 0 || !controlling || mc.player == null || !mc.player.isFallFlying()) return;
        ClientboundPlayerPositionPacket packet = glideSetback.get();
        Vec3 target = packet == null || lastControlledPosition == null ? mc.player.position()
            : PositionMoveRotation.calculateAbsolute(new PositionMoveRotation(lastControlledPosition,
                mc.player.getDeltaMovement(), mc.player.getYRot(), mc.player.getXRot()),
                packet.change(), packet.relatives()).position();
        boolean teleport = lastControlledPosition == null || target.distanceToSqr(lastControlledPosition) > 64 * 64;
        // A second setback straight back to where the last one put us: every move from there is being rejected.
        boolean pinned = !teleport && lastSetbackTarget != null && clientTicks - lastSetbackTick <= PINNED_WINDOW_TICKS
            && target.distanceToSqr(lastSetbackTarget) < .01;
        lastSetbackTarget = teleport ? null : target;
        lastSetbackTick = clientTicks;
        logSetback(count, target, teleport, pinned);
        engine.setback(teleport);
        if (pinned && pinnedGlideTicks.get() > 0) {
            if (pinnedTicks == 0) pinnedEpisodes++;
            pinnedTicks = pinnedGlideTicks.get();
            engine.suspend();
        }
        remember(new Reported(clientTicks, target, true, 0, 0));
        setbacks = (int) Math.min(Integer.MAX_VALUE, (long) setbacks + count);
        movementTick = Integer.MIN_VALUE;
        samplePosition = null;
        measuredSpeed = 0;
    }

    /** The position vanilla last reported, once per tick, with the boost that produced it. */
    private void recordReported(LocalPlayerAccessor sent) {
        Vec3 position = new Vec3(sent.comet$xLast(), sent.comet$yLast(), sent.comet$zLast());
        Reported last = reported.peekLast();
        if (last != null && last.position().distanceToSqr(position) < 1e-12) return;
        remember(new Reported(clientTicks, position, false, engine.horizontalBoost(), engine.verticalBoost()));
    }

    private void remember(Reported move) {
        reported.addLast(move);
        while (reported.size() > REPORTED_HISTORY) reported.removeFirst();
    }

    /**
     * The move the server rejected: the one reported right after the position it set us back to. Its speed, how much
     * velocity changed from the move before and how sharply it turned show what the server objects to.
     */
    private void describeRejectedMove(JsonObject record, Vec3 target) {
        Reported[] moves = reported.toArray(new Reported[0]);
        int accepted = -1;
        for (int i = moves.length - 1; i >= 0; i--) {
            if (moves[i].position().distanceToSqr(target) < 1e-4) {
                accepted = i;
                break;
            }
        }
        boolean found = accepted >= 0 && accepted + 1 < moves.length;
        record.addProperty("rejectedMoveFound", found);
        if (!found) return;
        Reported from = moves[accepted], rejected = moves[accepted + 1];
        Vec3 velocity = rejected.position().subtract(from.position()).scale(1.0 / Math.max(1, rejected.tick() - from.tick()));
        record.addProperty("rejectedSpeed", velocity.length() * 20);
        record.addProperty("rejectedHorizontalSpeed", velocity.horizontalDistance() * 20);
        record.addProperty("rejectedVerticalSpeed", velocity.y * 20);
        record.addProperty("rejectedHorizontalBoost", rejected.horizontalBoost() * 20);
        record.addProperty("rejectedVerticalBoost", rejected.verticalBoost() * 20);
        record.addProperty("rejectedAfterSetback", from.teleport());
        record.addProperty("rejectedTicksAgo", clientTicks - rejected.tick());
        if (accepted == 0 || from.teleport()) return;
        Reported before = moves[accepted - 1];
        Vec3 previous = from.position().subtract(before.position()).scale(1.0 / Math.max(1, from.tick() - before.tick()));
        record.addProperty("previousSpeed", previous.length() * 20);
        record.addProperty("velocityChange", velocity.subtract(previous).length() * 20);
        double h0 = previous.horizontalDistance(), h1 = velocity.horizontalDistance();
        double turn = h0 < 1e-6 || h1 < 1e-6 ? 0 : Math.toDegrees(Math.acos(Math.max(-1, Math.min(1,
            (previous.x * velocity.x + previous.z * velocity.z) / (h0 * h1)))));
        record.addProperty("turnDegrees", turn);
    }

    /** One record per setback, before it rewinds the ramps; the log opens with the session's first setback. */
    private void logSetback(int count, Vec3 target, boolean teleport, boolean pinned) {
        if (!logSetbacks.get()) {
            closeSetbackLog();
            return;
        }
        if (setbackLog == null || !setbackLog.isAccepting()) {
            if (setbackLogFailed || setbackLog != null) return;
            try {
                setbackLog = PacketCaptureLog.open(MeteorClient.FOLDER.toPath().resolve("comet/setback-logs"),
                    SETBACK_LOG_BYTES);
            } catch (IOException | RuntimeException e) {
                setbackLogFailed = true;
                warning("Could not open the setback log: %s", e.getMessage() == null ? e : e.getMessage());
                return;
            }
        }
        Motion output = engine.output();
        Vec3 position = mc.player.position();
        JsonObject record = new JsonObject();
        record.addProperty("time", Instant.now().toString());
        record.addProperty("tick", clientTicks);
        record.addProperty("count", count);
        record.addProperty("dimension", mc.level.dimension().identifier().toString());
        record.addProperty("x", position.x);
        record.addProperty("y", position.y);
        record.addProperty("z", position.z);
        record.addProperty("correctionDistance", lastControlledPosition == null ? -1 : target.distanceTo(lastControlledPosition));
        record.addProperty("teleport", teleport);
        record.addProperty("speed", output.length() * 20);
        record.addProperty("horizontalSpeed", output.horizontalLength() * 20);
        record.addProperty("verticalSpeed", output.y() * 20);
        record.addProperty("boosting", engine.boostActive());
        record.addProperty("horizontalBoost", engine.horizontalBoost() * 20);
        record.addProperty("verticalBoost", engine.verticalBoost() * 20);
        record.addProperty("state", state);
        PlayerInfo info = mc.getConnection() == null ? null : mc.getConnection().getPlayerInfo(mc.player.getUUID());
        record.addProperty("ping", info == null ? -1 : info.getLatency());
        record.addProperty("chunkAheadLoaded", chunkAheadLoaded(position, output));
        record.addProperty("flyIntoUnloadedChunks", flyIntoUnloadedChunks.get());
        record.addProperty("overdrive", overdrive.get());
        record.addProperty("splitMoves", splitMoves.get());
        record.addProperty("boostTop", boostTop() * 20);
        record.addProperty("boostMaxBps", boostMaxBps.get());
        record.addProperty("verticalBoostMaxBps", verticalBoostMaxBps.get());
        record.addProperty("maneuverMode", engine.maneuverMode());
        record.addProperty("maneuverMaxBps", maneuverMode.get() ? maneuverMaxBps.get() : -1);
        record.addProperty("pinned", pinned);
        record.addProperty("setbackHold", boostSetbackHold.get());
        describeRejectedMove(record, target);
        setbackLog.append(record);
    }

    /** Whether the client has the chunk two seconds of current horizontal flight ahead, capped at 64 blocks. */
    private boolean chunkAheadLoaded(Vec3 position, Motion output) {
        double horizontal = output.horizontalLength();
        double reach = Math.min(64, horizontal * 40);
        double x = horizontal == 0 ? position.x : position.x + output.x() / horizontal * reach;
        double z = horizontal == 0 ? position.z : position.z + output.z() / horizontal * reach;
        return mc.level.getChunkSource().hasChunk((int) Math.floor(x / 16), (int) Math.floor(z / 16));
    }

    private void closeSetbackLog() {
        if (setbackLog != null) setbackLog.close();
        setbackLog = null;
        setbackLogFailed = false;
    }

    private void suspend(String why) {
        controlling = false;
        glideSteering = false;
        engine.suspend();
        movementTick = Integer.MIN_VALUE;
        state = why;
    }

    private void reset() {
        engine.reset();
        boostDelay.reset();
        corrections.set(0);
        glideSetback.set(null);
        glideMotion.set(null);
        glideKnockback.set(Vec3.ZERO);
        playerId = Integer.MIN_VALUE;
        serverGlide = null;
        groundedSinceReset = sentRotation = false;
        rockets = pendingRocketTicks = 0;
        glideLimitedTicks = glideHits = glideSteeredTicks = 0;
        glideSteering = false;
        controlling = false;
        world = null;
        player = null;
        movementTick = Integer.MIN_VALUE;
        chunkLimitedTicks = obstacleLimitedTicks = slowFrames = 0;
        floorChunkChecks = collisionChecks = 0;
        frameAverageMs = framePeakMs = measuredSpeed = 0;
        boostToggled = false;
        samplePosition = null;
        lastControlledPosition = null;
        sampleTime = 0;
        setbacks = diagnosticSetbackBase = 0;
        lastSetbackTarget = null;
        pinnedTicks = 0;
        pinnedEpisodes = 0;
        reported.clear();
        state = "Waiting for flight";
        updateStatus();
    }

    /** The engine's settings from this module's and Meteor Elytra Fly's; Meteor's stock speeds only gate axes. */
    private AdaptiveFlightEngine.Settings engineSettings(ElytraFly fly) {
        return new AdaptiveFlightEngine.Settings(acceleration.get(), braking.get(), adaptiveTurnRate.get(),
            cruiseMaxBps.get() / 20, boostMaxBps.get() / 20, VERTICAL_TOP, verticalCruiseMaxBps.get() / 20,
            verticalBoostMaxBps.get() / 20, verticalAcceleration.get(), verticalRampStep.get(), verticalReversalTicks.get(),
            boostVisible(), boostTop(), BOOST_START, boostRampStep.get(),
            boostVerticalStartSpeed.get(), boostRewindTicks.get(), boostSetbackHold.get(), serverMoveLimit(),
            maneuverMode.get(), maneuverMaxBps.get() / 20, lookahead.get(), margin.get(),
            safeManeuvering.get(), closeObjectSpeed.get(),
            fly == null ? 0 : fly.horizontalSpeed.get(), fly == null ? 0 : fly.verticalSpeed.get(),
            fly == null ? 0 : fly.fallMultiplier.get());
    }

    /** Clear distance straight down to the floor, for soft landings; infinite when none is within scan range. */
    private double floorClearance() {
        FlightCorridor.Result below = FlightCorridor.inspect(mc.player.getBoundingBox(), DOWN, FLOOR_SCAN, floorProbe);
        floorChunkChecks += below.chunkChecks();
        return below.reason() == FlightCorridor.Reason.CLEAR ? Double.POSITIVE_INFINITY
            : Math.max(0, below.clearDistance() - margin.get());
    }

    private boolean boostVisible() { return sprintBoost.get() && unlimitedBoost.get(); }

    /** The most a vanilla server accepts per tick in total, which boost never exceeds. */
    private double serverMoveLimit() { return ServerMoveLimit.of(boostVisible() && splitMoves.get()); }

    // Below this, one packet a tick is comfortably within what a server accepts even when two ticks bunch together.
    private static final double SPLIT_MOVE_DISTANCE = 10;

    /**
     * Called by the mixin just before vanilla reports this tick's position: while boosting fast, report the halfway
     * point first, so each packet covers half the tick's distance against the server's doubled allowance.
     */
    public void sendSplitMove() {
        if (!isActive() || !boostVisible() || !splitMoves.get() || !controlling || !engine.boostActive()
            || mc.player == null || mc.getConnection() == null || !mc.player.isFallFlying()) return;
        LocalPlayerAccessor reported = (LocalPlayerAccessor) mc.player;
        Vec3 last = new Vec3(reported.comet$xLast(), reported.comet$yLast(), reported.comet$zLast());
        Vec3 current = mc.player.position();
        if (current.distanceToSqr(last) <= SPLIT_MOVE_DISTANCE * SPLIT_MOVE_DISTANCE) return;
        mc.getConnection().send(new ServerboundMovePlayerPacket.Pos(last.add(current).scale(.5), false,
            mc.player.horizontalCollision));
    }

    // The boost curves, in blocks per tick: VolytraFly's 60 to 300 b/s horizontally and a 60 b/s vertical top.
    // The b/s settings cap speed along them; they never reshape them.
    private static final double BOOST_START = 2.999, BOOST_TOP = 14.999, VERTICAL_TOP = 3;

    /** The horizontal boost curve's top speed: the server's per-tick limit with overdrive, otherwise 300 b/s. */
    private double boostTop() { return overdrive.get() ? serverMoveLimit() : BOOST_TOP; }

    /** The physical Sprint key, including a rebound mouse button, while Minecraft is focused with no screen open. */
    private boolean sprintKeyDown() {
        return sprintBoost.get() && mc.screen == null && mc.isWindowActive() && Input.isPressed(mc.options.keySprint);
    }

    /**
     * Toggle mode flips boost on each Sprint press; it turns off when gliding stops and, with toggle-off-when-idle,
     * once every movement key has been released after moving, unless something else is moving the player.
     */
    private void updateBoostKey() {
        boolean pressed = Input.isPressed(mc.options.keySprint);
        // Track the raw key, so closing a screen while Sprint is held is not mistaken for a new press.
        if (pressed && !sprintWasPressed && sprintKeyDown()) {
            boostToggled = !boostToggled;
            movedSinceToggle = false;
            idleTicks = 0;
        }
        sprintWasPressed = pressed;
        if (boostKeyMode.get() != BoostKeyMode.Toggle || !sprintBoost.get()
            || mc.player == null || !mc.player.isFallFlying()) boostToggled = false;
        if (!boostToggled || !toggleOffWhenIdle.get()) return;
        if (movementKeyDown() || automatedMovement()) {
            movedSinceToggle = true;
            idleTicks = 0;
        } else if (movedSinceToggle && ++idleTicks >= IDLE_TOGGLE_OFF_TICKS) boostToggled = false;
    }

    /** Movement keys as Minecraft sees them, including keys held down by Auto Walk's Simple mode or autopilot. */
    private boolean movementKeyDown() {
        var options = mc.options;
        return options.keyUp.isDown() || options.keyDown.isDown() || options.keyLeft.isDown()
            || options.keyRight.isDown() || options.keyJump.isDown() || options.keyShift.isDown();
    }

    /** Something other than the player's own keys is driving movement. */
    private boolean automatedMovement() {
        Modules modules = Modules.get();
        ElytraFly fly = modules.get(ElytraFly.class);
        return modules.isActive(AutoWalk.class) || modules.isActive(BaritoneElytra.class)
            || fly != null && fly.isActive() && fly.autoPilot.get() || PathManagers.get().isPathing();
    }

    private void measureSpeed() {
        if (!controlling || mc.player == null) { samplePosition = null; measuredSpeed = 0; return; }
        long now = System.nanoTime();
        Vec3 position = mc.player.position();
        if (samplePosition == null) { samplePosition = position; sampleTime = now; }
        else if (now - sampleTime >= 1_000_000_000L) {
            measuredSpeed = position.distanceTo(samplePosition)
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

    /** Maneuver mode is holding controlled flight to {@link #maneuverMaxBps()}. */
    public boolean maneuverCapped() { return isActive() && controlling && engine.maneuverMode(); }

    public double maneuverMaxBps() { return maneuverMaxBps.get(); }

    /** Lines for the Adaptive Elytra Fly HUD element, or null while the module is off. */
    public List<String> hudLines() {
        if (!isActive()) return null;
        Motion output = engine.output();
        return List.of(
            String.format(Locale.ROOT, "State: %s%s", state, maneuverCapped()
                ? String.format(Locale.ROOT, " | maneuver ≤%.0f", maneuverMaxBps.get()) : ""),
            String.format(Locale.ROOT, "Speed: %.1f b/s | 3D %.1f | H %.1f | V %+.1f",
                measuredSpeed, output.length() * 20, output.horizontalLength() * 20, output.y() * 20),
            String.format(Locale.ROOT, "%s: %.1f b/s", engine.boostActive() ? "Guard max" : "Engine max",
                engine.adaptiveMaximum() * 20),
            boostLine(),
            String.format(Locale.ROOT, "Setbacks: %d | server glide %s", setbacks, serverGlide == null ? "idle"
                : String.format(Locale.ROOT, "%.1f b/s%s", serverGlide.horizontalLength() * 20,
                    glideSteering ? " (steered away)" : ""))
        );
    }

    private String boostLine() {
        if (!boostVisible()) return "Boost: off (adaptive Sprint)";
        String toggle = boostKeyMode.get() != BoostKeyMode.Toggle ? "" : boostToggled ? " (on)" : " (off)";
        return String.format(Locale.ROOT, "Boost%s: H %.0f/%.0f | V %.0f/%.0f b/s%s", toggle,
            engine.horizontalBoost() * 20, engine.horizontalBoostCeiling() * 20,
            engine.verticalBoost() * 20, engine.verticalBoostCeiling() * 20,
            (overdrive.get() ? " | overdrive" : "") + (splitMoves.get() ? " | split" : ""));
    }

    private void updateStatus() {
        hud = String.format(Locale.ROOT, "%s %.0f b/s", state, measuredSpeed);
        // Diagnostics labels are only visible in a settings screen; skip their formatting during play.
        if (mc.screen == null) return;
        if (statusLabel != null) statusLabel.set(String.format(Locale.ROOT, "%s | measured %.1f b/s", state, measuredSpeed));
        if (speedLabel != null) {
            double maximum = controlling && !engine.boostActive() ? engine.adaptiveMaximum()
                : AdaptiveFlightStep.maximumSpeed(braking.get(), lookahead.get(), margin.get());
            // What each cap actually allows: none passes its curve's top or, outside boost, the engine maximum.
            double vertical = Math.min(maximum, VERTICAL_TOP) * 20;
            double sprint = Math.min(boostMaxBps.get(), (boostVisible() ? boostTop() : maximum) * 20);
            speedLabel.set(String.format(Locale.ROOT,
                "Horizontal max %.1f b/s | cruise %.1f | %s %.1f | vertical cruise/Sprint %.1f/%.1f b/s",
                maximum * 20, Math.min(cruiseMaxBps.get(), maximum * 20),
                boostVisible() ? "unlimited boost" : "Sprint", sprint,
                Math.min(verticalCruiseMaxBps.get(), vertical),
                Math.min(verticalBoostMaxBps.get(), boostVisible() ? VERTICAL_TOP * 20 : vertical)));
        }
        if (boostLabel != null) boostLabel.set(boostLine());
        if (countsLabel != null) countsLabel.set("Setbacks " + (setbacks - diagnosticSetbackBase)
            + " | unpinned " + pinnedEpisodes
            + " | chunk-limited ticks " + chunkLimitedTicks + " | obstacle-limited ticks " + obstacleLimitedTicks
            + (setbackLog == null ? "" : " | logging to " + setbackLog.path().getFileName()));
        if (glideLabel != null) glideLabel.set(String.format(Locale.ROOT,
            "Server glide %s | glide-limited ticks %d | steered ticks %d | predicted server wall hits %d",
            serverGlide == null ? "idle" : String.format(Locale.ROOT, "%.1f b/s%s",
                serverGlide.horizontalLength() * 20, rockets > 0 ? " with rocket" : ""),
            glideLimitedTicks, glideSteeredTicks, glideHits));
        if (checksLabel != null) checksLabel.set("This tick: " + (engine.chunkChecks() + floorChunkChecks)
            + " chunk checks, " + collisionChecks + " block checks");
        if (frameLabel != null) frameLabel.set(String.format(Locale.ROOT,
            "Frames: average %.1f ms | peak %.1f ms | over 50 ms: %d", frameAverageMs, framePeakMs, slowFrames));
    }

    @Override public String getInfoString() { return hud; }

    @Override public WWidget getWidget(GuiTheme theme) {
        var list = theme.verticalList();
        list.add(theme.label("Unlimited boost, setback rewind and soft landing"));
        list.add(theme.label("based on VolytraFly"));
        list.add(theme.label("Credit: Volizray"));
        list.add(theme.label("Source: github.com/Volizray/VolytraFly-Addon"));
        list.add(theme.label("VolytraFly licence: CC0 1.0 (public domain)"));
        var section = list.add(theme.section("Flight Diagnostics", false)).expandX().widget();
        var table = section.add(theme.table()).expandX().widget();
        statusLabel = table.add(theme.label("")).widget(); table.row();
        speedLabel = table.add(theme.label("")).widget(); table.row();
        boostLabel = table.add(theme.label("")).widget(); table.row();
        countsLabel = table.add(theme.label("")).widget(); table.row();
        glideLabel = table.add(theme.label("")).widget(); table.row();
        checksLabel = table.add(theme.label("")).widget(); table.row();
        frameLabel = table.add(theme.label("")).widget(); table.row();
        table.add(theme.button("Reset Flight Diagnostics")).widget().action = () -> {
            chunkLimitedTicks = obstacleLimitedTicks = slowFrames = 0;
            glideLimitedTicks = glideHits = glideSteeredTicks = 0;
            frameAverageMs = framePeakMs = 0;
            measuredSpeed = 0;
            samplePosition = null;
            diagnosticSetbackBase = setbacks;
            updateStatus();
        };
        table.row();
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
}
