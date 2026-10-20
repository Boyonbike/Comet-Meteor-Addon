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
        .description("Hold your Sprint key to boost horizontal movement and deliberate climb/descent. With unlimited-boost on, Sprint drives the unlimited boost ramp; otherwise it raises the adaptive engine to sprint-max-speed.")
        .defaultValue(true).build());
    private final Setting<BoostKeyMode> boostKeyMode = general.add(new EnumSetting.Builder<BoostKeyMode>()
        .name("boost-key-mode")
        .description("Hold: boost while Sprint is held. Toggle: each Sprint press turns boost on or off; it turns off when you stop gliding.")
        .defaultValue(BoostKeyMode.Hold).visible(sprintBoost::get).build());
    private final Setting<Boolean> toggleOffWhenIdle = general.add(new BoolSetting.Builder().name("toggle-off-when-idle")
        .description("In Toggle mode, turn boost off once you stop pressing every movement key (WASD, Jump, Sneak), unless Meteor's Auto Walk, Elytra Fly autopilot, Baritone or Baritone Elytra is moving you.")
        .defaultValue(true).visible(() -> sprintBoost.get() && boostKeyMode.get() == BoostKeyMode.Toggle).build());
    private final Setting<Double> cruiseMaximum = general.add(new DoubleSetting.Builder().name("cruise-max-speed")
        .description("Horizontal cruise maximum as a percentage of the adaptive engine's attainable speed. 100% uses its full output; see Flight Diagnostics for blocks/second.")
        .defaultValue(35).range(0, 100).sliderRange(0, 100).decimalPlaces(1).build());
    private final Setting<Double> sprintMaximum = general.add(new DoubleSetting.Builder().name("sprint-max-speed")
        .description("Horizontal Sprint maximum: a percentage of boost-max-speed with unlimited-boost on, otherwise of the adaptive engine's attainable speed.")
        .defaultValue(100).range(0, 100).sliderRange(0, 100).decimalPlaces(1).visible(sprintBoost::get).build());
    private final Setting<Double> verticalMaxSpeed = general.add(new DoubleSetting.Builder().name("vertical-max-speed")
        .description("Fastest climb/descent in blocks per tick (3 is 60 b/s). Vertical percentages and the vertical boost ramp are of this.")
        .defaultValue(3).range(.05, 10).sliderRange(.1, 6).decimalPlaces(2).build());
    private final Setting<Double> verticalCruiseMaximum = general.add(new DoubleSetting.Builder().name("vertical-cruise-max-speed")
        .description("Jump/Sneak cruise maximum as a percentage of vertical-max-speed.")
        .defaultValue(35).range(0, 100).sliderRange(0, 100).decimalPlaces(1).build());
    private final Setting<Double> verticalSprintMaximum = general.add(new DoubleSetting.Builder().name("vertical-sprint-max-speed")
        .description("Sprint + Jump/Sneak maximum as a percentage of vertical-max-speed.")
        .defaultValue(100).range(0, 100).sliderRange(0, 100).decimalPlaces(1).visible(sprintBoost::get).build());
    private final Setting<Double> verticalAcceleration = general.add(new DoubleSetting.Builder().name("vertical-acceleration")
        .description("Minimum vertical velocity change per tick; vertical-ramp-step speeds it up when far below the target. Vertical braking uses up to twice this, more from fast climbs and dives (mirroring vertical-ramp-step).")
        .defaultValue(.05).range(.005, .5).sliderRange(.005, .15).decimalPlaces(3).build());
    private final Setting<Double> verticalRampStep = general.add(new DoubleSetting.Builder().name("vertical-ramp-step")
        .description("How fast climb/descent speeds up, in blocks per tick squared at rest, tapering to zero at vertical-max-speed (like VolytraFly's acceleration curve). Zero keeps vertical-acceleration only.")
        .defaultValue(.5).range(0, 5).sliderRange(0, 2).decimalPlaces(2).build());
    private final Setting<Boolean> unlimitedBoost = boost.add(new BoolSetting.Builder().name("unlimited-boost")
        .description("Holding Sprint flies VolytraFly's speed ramp instead of the adaptive engine maximum. Terrain, chunk and server-glide guards still apply. Off restores adaptive Sprint.")
        .defaultValue(true).visible(sprintBoost::get).build());
    private final Setting<Boolean> overdrive = boost.add(new BoolSetting.Builder().name("overdrive")
        .description("Boost to the most a vanilla server accepts per tick instead of boost-max-speed: 340 b/s (17 blocks per tick), or 480 b/s with split-moves. Any boost above 245 b/s is set back whenever two move packets reach the server in the same tick (lag or ping jitter), so 340 b/s rubber-bands no more often than 300 b/s.")
        .defaultValue(false).visible(this::boostVisible).build());
    private final Setting<Boolean> splitMoves = boost.add(new BoolSetting.Builder().name("split-moves")
        .description("Experimental: while boosting above 200 b/s, also report a half-step position each tick. A vanilla server then allows twice the distance per tick (480 b/s with overdrive) and stops setting back 300-340 b/s when two ticks of packets arrive together. An anticheat that counts movement packets may set you back or kick you instead.")
        .defaultValue(false).visible(this::boostVisible).build());
    private final Setting<Double> boostMaxSpeed = boost.add(new DoubleSetting.Builder().name("boost-max-speed")
        .description("Top horizontal boost speed in blocks per tick with overdrive off (14.999 is VolytraFly's 300 b/s). Boost's total 3D speed never exceeds what a vanilla server accepts per move packet: 17 blocks per tick.")
        .defaultValue(14.999).range(.1, 24).sliderRange(1, 17).decimalPlaces(3)
        .visible(() -> boostVisible() && !overdrive.get()).build());
    private final Setting<Double> boostStartSpeed = boost.add(new DoubleSetting.Builder().name("boost-start-speed")
        .description("Horizontal speed boost starts at, in blocks per tick, when slower (2.999 is 60 b/s).")
        .defaultValue(2.999).range(0, 64).sliderRange(0, 10).decimalPlaces(3).visible(this::boostVisible).build());
    private final Setting<Double> boostRampStep = boost.add(new DoubleSetting.Builder().name("boost-ramp-step")
        .description("Horizontal boost acceleration in blocks per tick squared at rest, tapering to zero at boost-max-speed.")
        .defaultValue(.3).range(.01, 5).sliderRange(.05, 2).decimalPlaces(2).visible(this::boostVisible).build());
    private final Setting<Double> boostVerticalStartSpeed = boost.add(new DoubleSetting.Builder().name("boost-vertical-start-speed")
        .description("Climb/descent speed boost starts at, in blocks per tick, when slower. The vertical boost then follows vertical-ramp-step up to vertical-max-speed.")
        .defaultValue(1.5).range(0, 10).sliderRange(0, 6).decimalPlaces(2).visible(this::boostVisible).build());
    private final Setting<Integer> boostRewindTicks = boost.add(new IntSetting.Builder().name("boost-rewind-ticks")
        .description("A server setback rewinds the boost ramp by this many ticks of acceleration instead of restarting it, and flight keeps its momentum (15 loses under 1% near top speed).")
        .defaultValue(15).range(0, 200).sliderRange(0, 60).visible(this::boostVisible).build());
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
    private long glideLimitedTicks, glideHits;
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
        if (controlling && p != null) lastControlledPosition = p.position();
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
        if (ElytraReplace.remaining(chest) > 1) return;
        swapChest(true);
    }

    private void smartLandingSwap(ElytraFly fly) {
        if (!canSmartSwap(fly) || !mc.player.onGround()) return;
        ItemStack chest = mc.player.getItemBySlot(EquipmentSlot.CHEST);
        if (chest.isEmpty() || ElytraReplace.isGlider(chest)) swapChest(false);
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
            if (stack.isEmpty() || ElytraReplace.isGlider(stack) != glider || !chestSlot.mayPlace(stack)
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
            ElytraReplace.isGlider(mc.player.getItemBySlot(EquipmentSlot.CHEST)), mc.player.isFallFlying());
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
            ServerGlide.Context glide = checkBlocks && serverGlideGuard.get() && serverGlide != null
                ? glideContext(mc.player, mc.player.getXRot(), mc.player.getYRot()) : null;
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
            } else if (engine.boostActive()) state = "Unlimited boost";
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
        logSetback(count, target, teleport);
        engine.setback(teleport);
        setbacks = (int) Math.min(Integer.MAX_VALUE, (long) setbacks + count);
        movementTick = Integer.MIN_VALUE;
        samplePosition = null;
        measuredSpeed = 0;
    }

    /** One record per setback, before it rewinds the ramps; the log opens with the session's first setback. */
    private void logSetback(int count, Vec3 target, boolean teleport) {
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
        glideLimitedTicks = glideHits = 0;
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
        state = "Waiting for flight";
        updateStatus();
    }

    /** The engine's settings from this module's and Meteor Elytra Fly's; Meteor's stock speeds only gate axes. */
    private AdaptiveFlightEngine.Settings engineSettings(ElytraFly fly) {
        return new AdaptiveFlightEngine.Settings(acceleration.get(), braking.get(), adaptiveTurnRate.get(),
            cruiseMaximum.get(), sprintMaximum.get(), verticalMaxSpeed.get(), verticalCruiseMaximum.get(),
            verticalSprintMaximum.get(), verticalAcceleration.get(), verticalRampStep.get(),
            boostVisible(), boostTop(), boostStartSpeed.get(), boostRampStep.get(),
            boostVerticalStartSpeed.get(), boostRewindTicks.get(), serverMoveLimit(), lookahead.get(), margin.get(),
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

    /** The horizontal boost ramp's top speed: the server's per-tick limit with overdrive, otherwise boost-max-speed. */
    private double boostTop() { return overdrive.get() ? serverMoveLimit() : boostMaxSpeed.get(); }

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

    /** Lines for the Adaptive Elytra Fly HUD element, or null while the module is off. */
    public List<String> hudLines() {
        if (!isActive()) return null;
        Motion output = engine.output();
        return List.of(
            String.format(Locale.ROOT, "State: %s", state),
            String.format(Locale.ROOT, "Speed: %.1f b/s | H %.1f | V %+.1f",
                measuredSpeed, output.horizontalLength() * 20, output.y() * 20),
            String.format(Locale.ROOT, "%s: %.1f b/s", engine.boostActive() ? "Guard max" : "Engine max",
                engine.adaptiveMaximum() * 20),
            boostLine(),
            String.format(Locale.ROOT, "Setbacks: %d | server glide %s", setbacks, serverGlide == null ? "idle"
                : String.format(Locale.ROOT, "%.1f b/s", serverGlide.horizontalLength() * 20))
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
            double sprint = boostVisible() ? boostTop() * 20 * sprintMaximum.get() / 100
                : maximum * sprintMaximum.get() / 5;
            speedLabel.set(String.format(Locale.ROOT,
                "Horizontal max %.1f b/s | cruise %.1f (%.1f%%) | %s %.1f (%.1f%%) | vertical cruise/Sprint %.1f/%.1f b/s",
                maximum * 20, maximum * cruiseMaximum.get() / 5, cruiseMaximum.get(),
                boostVisible() ? "unlimited boost" : "Sprint", sprint, sprintMaximum.get(),
                verticalMaxSpeed.get() * verticalCruiseMaximum.get() / 5,
                verticalMaxSpeed.get() * verticalSprintMaximum.get() / 5));
        }
        if (boostLabel != null) boostLabel.set(boostLine());
        if (countsLabel != null) countsLabel.set("Setbacks " + (setbacks - diagnosticSetbackBase)
            + " | chunk-limited ticks " + chunkLimitedTicks + " | obstacle-limited ticks " + obstacleLimitedTicks
            + (setbackLog == null ? "" : " | logging to " + setbackLog.path().getFileName()));
        if (glideLabel != null) glideLabel.set(String.format(Locale.ROOT,
            "Server glide %s | glide-limited ticks %d | predicted server wall hits %d",
            serverGlide == null ? "idle" : String.format(Locale.ROOT, "%.1f b/s%s",
                serverGlide.horizontalLength() * 20, rockets > 0 ? " with rocket" : ""),
            glideLimitedTicks, glideHits));
        if (checksLabel != null) checksLabel.set("This tick: " + (engine.chunkChecks() + floorChunkChecks)
            + " chunk checks, " + collisionChecks + " block checks");
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
        boostLabel = table.add(theme.label("")).widget(); table.row();
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
