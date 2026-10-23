package dev.tntcoating.modules;

import dev.tntcoating.TntCoatingAddon;
import dev.tntcoating.compat.BaritoneBridge;
import dev.tntcoating.compat.BaritoneBridge.Trip;
import dev.tntcoating.core.AdaptiveFlightController.Motion;
import dev.tntcoating.core.AdaptiveFlightEngine;
import dev.tntcoating.core.BaritoneFlight;
import dev.tntcoating.core.ElytraBoostDelay;
import dev.tntcoating.core.ElytraHandoff;
import dev.tntcoating.core.ElytraHandoff.Refusal;
import dev.tntcoating.core.ElytraTakeoff;
import dev.tntcoating.core.FlightCorridor;
import dev.tntcoating.core.ServerGlide;
import dev.tntcoating.mixin.FireworkRocketAccessor;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import meteordevelopment.meteorclient.MeteorClient;
import meteordevelopment.meteorclient.events.entity.player.PlayerMoveEvent;
import meteordevelopment.meteorclient.events.entity.player.InteractItemEvent;
import meteordevelopment.meteorclient.events.game.GameJoinedEvent;
import meteordevelopment.meteorclient.events.game.GameLeftEvent;
import meteordevelopment.meteorclient.events.packets.PacketEvent;
import meteordevelopment.meteorclient.events.world.TickEvent;
import meteordevelopment.meteorclient.mixininterface.IVec3d;
import meteordevelopment.meteorclient.settings.BoolSetting;
import meteordevelopment.meteorclient.settings.DoubleSetting;
import meteordevelopment.meteorclient.settings.IntSetting;
import meteordevelopment.meteorclient.settings.Setting;
import meteordevelopment.meteorclient.settings.SettingGroup;
import meteordevelopment.meteorclient.systems.modules.Module;
import meteordevelopment.meteorclient.systems.modules.Modules;
import meteordevelopment.meteorclient.systems.modules.movement.elytrafly.ElytraFly;
import meteordevelopment.meteorclient.systems.modules.render.Freecam;
import meteordevelopment.meteorclient.utils.player.InvUtils;
import meteordevelopment.orbit.EventHandler;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.Direction;
import net.minecraft.network.protocol.game.ClientboundPlayerPositionPacket;
import net.minecraft.network.protocol.game.ServerboundPlayerCommandPacket;
import net.minecraft.network.protocol.game.ServerboundUseItemPacket;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.MoverType;
import net.minecraft.world.entity.PositionMoveRotation;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.projectile.FireworkRocketEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.component.Fireworks;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.VoxelShape;

public final class BaritoneElytra extends Module {
    /** Blocks of open air needed above the player's head to jump, open the elytra and climb away. */
    private static final double HEADROOM = 3;
    /** A landing at least this close to the goal is walked from, even when min-distance is set lower. Baritone looks for a landing spot once within 48 blocks of the end of its path. */
    private static final int NEAR = 128;
    private static final int RECHECK_TICKS = 10;
    private static final int RESTOCK_TICKS = 10;
    /** Baritone stays active with no path when its pathfinder fails, so a take-off that never gets one gives up after this. */
    private static final int PATH_TICKS = 200;
    private final SettingGroup general = settings.getDefaultGroup();
    private final Setting<Integer> minDistance = general.add(new IntSetting.Builder().name("min-distance").description("Trips shorter than this many blocks are walked.").defaultValue(300).min(32).sliderRange(32, 2000).build());
    private final Setting<Boolean> takeOff = general.add(new BoolSetting.Builder().name("take-off").description("Jump and open the elytra from flat ground. Without this, Baritone needs a drop to fall from, or its elytraAutoJump setting.").defaultValue(true).build());
    private final Setting<Boolean> equip = general.add(new BoolSetting.Builder().name("equip-elytra").description("Put on an elytra from your inventory for the flight.").defaultValue(true).build());
    private final Setting<Boolean> swapBack = general.add(new BoolSetting.Builder().name("swap-back").description("Put the chestplate the elytra replaced back on after landing.").defaultValue(true).visible(equip::get).build());
    private final Setting<Boolean> restock = general.add(new BoolSetting.Builder().name("restock-fireworks").description("In vanilla flight, move fireworks into the hotbar when it has none. Adjustable Elytra Fly needs no fireworks.").defaultValue(true).visible(this::needsFireworks).build());
    private final Setting<Integer> restockSlot = general.add(new IntSetting.Builder().name("restock-slot").description("Hotbar slot (1-9) that receives fireworks when the hotbar has no empty slot; its item goes to where the fireworks were. The neighbouring slot is used while this one is selected.").defaultValue(9).range(1, 9).sliderRange(1, 9).visible(() -> needsFireworks() && restock.get()).build());
    private final SettingGroup flightSettings = settings.createGroup("Elytra Fly");
    private final Setting<Boolean> controlledFlight = flightSettings.add(new BoolSetting.Builder().name("elytra-fly")
        .description("Use Elytra Fly for the whole flight, including approach and landing, without fireworks. Baritone chooses the route and landing spot. Turn off for vanilla firework flight.")
        .defaultValue(true).onChanged(value -> flightModeChanged()).build());
    private final Setting<Boolean> useAdaptiveSettings = flightSettings.add(new BoolSetting.Builder().name("use-adaptive-settings")
        .description("Match Adaptive Elytra Fly's configured boost speeds and ramps, up to 340 b/s without split moves. Turn off to adjust Baritone's speeds and ramps below.")
        .defaultValue(true).visible(controlledFlight::get).build());
    private final Setting<Double> flightSpeed = flightSettings.add(new DoubleSetting.Builder().name("speed-bps")
        .description("Horizontal speed cap in blocks per second. Uses the same boost curve as Adaptive Elytra Fly; terrain and turns can slow flight.")
        .defaultValue(300).range(0, 340).sliderRange(0, 340).decimalPlaces(1).visible(this::customFlightSettings).build());
    private final Setting<Double> verticalSpeed = flightSettings.add(new DoubleSetting.Builder().name("vertical-speed-bps")
        .description("Climb and descent speed cap in blocks per second. Baritone's steering pitch determines the direction and slope.")
        .defaultValue(60).range(0, 60).sliderRange(0, 60).decimalPlaces(1).visible(this::customFlightSettings).build());
    private final Setting<Double> rampStep = flightSettings.add(new DoubleSetting.Builder().name("boost-ramp-step")
        .description("Horizontal acceleration in blocks per tick squared at rest, tapering towards the same curve top as Adaptive Elytra Fly.")
        .defaultValue(.3).range(.01, 5).sliderRange(.01, 1).decimalPlaces(2).visible(this::customFlightSettings).build());
    private final Setting<Double> verticalRampStep = flightSettings.add(new DoubleSetting.Builder().name("vertical-ramp-step")
        .description("Climb and descent acceleration, matching Adaptive Elytra Fly. Zero holds vertical boost at its start speed.")
        .defaultValue(.5).range(0, 5).sliderRange(0, 2).decimalPlaces(2).visible(this::customFlightSettings).build());
    private final ElytraTakeoff takeoff = new ElytraTakeoff();
    private final ElytraBoostDelay boostDelay = new ElytraBoostDelay();
    private final AtomicReference<ClientboundPlayerPositionPacket> correction = new AtomicReference<>();
    private BaritoneFlight flightController;
    private boolean landingControlled;
    private Vec3 lastControlledPosition;
    private int pendingRocketTicks;
    private final FlightCorridor.Probe flightProbe = new FlightCorridor.Probe() {
        @Override public boolean loaded(int x, int z) { return mc.level.getChunkSource().hasChunk(x, z); }
        @Override public boolean clear(AABB swept) {
            Motion output = flightController.output();
            for (VoxelShape shape : mc.level.getBlockCollisions(mc.player, swept)) {
                if (!FlightCorridor.floorContactAllowed(mc.player.getBoundingBox(),
                    new Vec3(output.x(), output.y(), output.z()), shape.max(Direction.Axis.Y))) return false;
            }
            return true;
        }
    };
    private final FlightCorridor.Probe floorProbe = new FlightCorridor.Probe() {
        @Override public boolean loaded(int x, int z) { return flightProbe.loaded(x, z); }
        @Override public boolean clear(AABB swept) {
            return !mc.level.getBlockCollisions(mc.player, swept).iterator().hasNext();
        }
    };
    private BaritoneBridge baritone;
    /** The flight being looked after, or null. */
    private Trip trip;
    /**
     * True when this module started the flight from a #goto: it walks the rest after landing, manages the elytra and
     * ElytraFly, and reports how the flight ended. A flight started by #elytra or another addon gets take-off help,
     * firework restocking and optional adjustable flight, but keeps its own landing and goal handling.
     */
    private boolean ours;
    private boolean glided;
    /** Take-off help has given up on a flight this module didn't start, which is left to Baritone and the player. */
    private boolean handsOff;
    /** Goal that is walked to the end and not checked again. */
    private Object settled;
    /** Goal refused for a reason that can clear up; checked again every {@link #RECHECK_TICKS}. */
    private Object waiting;
    private Refusal waitingReason;
    private int recheck;
    private int restockWait;
    private int pathWait;
    /** Inventory slot holding the chestplate the elytra replaced, or -1. */
    private int chestplateSlot = -1;
    private boolean resumeElytraFly;

    public BaritoneElytra() { super(TntCoatingAddon.CATEGORY, "baritone-elytra", "Flies long Baritone #goto trips with adjustable elytra flight, then walks the rest."); }

    @Override public String getInfoString() {
        if (trip == null || mc.player == null) return null;
        if (landingControlled) return "Landing";
        return glided ? String.valueOf((int) remaining(trip)) : "Taking off";
    }

    @Override public void onActivate() {
        reset();
        baritone = BaritoneBridge.create();
        if (baritone == null) {
            error("Baritone isn't installed.");
            toggle();
        } else updateRocketlessMode();
    }

    @Override public void onDeactivate() {
        boolean airborne = false;
        try {
            if (trip != null && baritone != null && mc.player != null && baritone.flight() != null) {
                // Cancelling mid-air would leave the player gliding with nobody steering.
                airborne = mc.player.isFallFlying();
                if (airborne) info("Baritone keeps flying; #stop cancels it.");
                else baritone.stop();
            }
        } catch (LinkageError | RuntimeException e) {
            MeteorClient.LOG.error("Stopping Baritone's elytra flight failed", e);
        }
        if (airborne && resumeElytraFly) {
            // ElytraFly would fight Baritone's steering for the rest of this flight: turn it back on after landing.
            resumeElytraFly = false;
            ElytraFlyResume.afterLanding();
        }
        try {
            finishFlight();
            if (mc.player != null && !mc.player.isFallFlying()) swapBack();
        } finally {
            if (baritone != null) baritone.rocketless(false);
            baritone = null;
        }
    }

    // The world, Baritone's flight and the inventory slots it refers to are all gone.
    @EventHandler private void onGameLeft(GameLeftEvent event) {
        try {
            finishFlight();
            reset();
        } finally {
            if (baritone != null) baritone.rocketless(false);
        }
    }

    @EventHandler private void onGameJoined(GameJoinedEvent event) { updateRocketlessMode(); }

    @EventHandler private void onInteractItem(InteractItemEvent event) {
        // Native Baritone can force a rocket even when its normal boost-speed threshold is zero. Stop that use
        // before Minecraft predicts consumption or sends a packet, including flights started directly by #elytra.
        if (controlledFlight.get() && baritone != null && mc.player != null && mc.player.isFallFlying()
            && mc.player.getItemInHand(event.hand).is(Items.FIREWORK_ROCKET) && baritone.flight() != null) {
            event.toReturn = InteractionResult.FAIL;
        }
    }

    @EventHandler private void onPacket(PacketEvent.Receive event) {
        if (event.packet instanceof ClientboundPlayerPositionPacket position) correction.set(position);
    }

    @EventHandler private void onSend(PacketEvent.Send event) {
        if (trip == null || mc.player == null || !mc.player.isFallFlying()
            || !(event.packet instanceof ServerboundUseItemPacket use)) return;
        ItemStack stack = mc.player.getItemInHand(use.getHand());
        if (!stack.is(Items.FIREWORK_ROCKET)) return;
        Fireworks fireworks = stack.get(DataComponents.FIREWORKS);
        // Cover the first boost before the rocket entity arrives from the server.
        pendingRocketTicks = Math.max(pendingRocketTicks,
            10 * (1 + (fireworks == null ? 0 : fireworks.flightDuration())));
    }

    @EventHandler private void onPostTick(TickEvent.Post event) {
        if (pendingRocketTicks > 0) pendingRocketTicks--;
    }

    @EventHandler(priority = Integer.MIN_VALUE) private void onMove(PlayerMoveEvent event) {
        if (event.type != MoverType.SELF) return;
        if (trip == null || !controlledFlight.get() || baritone == null || mc.player == null || mc.level == null
            || !mc.player.isFallFlying() || mc.player.isInWater() || mc.player.isInLava()
            || mc.player.isPassenger() || Modules.get().isActive(Freecam.class)) {
            resetFlightController();
            return;
        }
        try {
            BaritoneBridge.Steering steering = baritone.steering();
            if (steering == null) {
                resetFlightController();
                return;
            }
            pauseElytraFly();
            AdaptiveElytraFly adaptive = Modules.get().get(AdaptiveElytraFly.class);
            if (adaptive == null) return;
            AdaptiveFlightEngine.Settings profile = adaptive.baritoneFlightSettings();
            if (flightController == null) flightController = new BaritoneFlight(profile);
            BaritoneFlight.Settings config = useAdaptiveSettings.get()
                ? new BaritoneFlight.Settings(profile.sprint() * 20, profile.verticalSprint() * 20,
                    profile.boostRampStep(), profile.verticalRampStep())
                : new BaritoneFlight.Settings(flightSpeed.get(), verticalSpeed.get(), rampStep.get(), verticalRampStep.get());
            ClientboundPlayerPositionPacket packet = correction.getAndSet(null);
            if (packet != null) {
                Vec3 target = lastControlledPosition == null ? mc.player.position()
                    : PositionMoveRotation.calculateAbsolute(new PositionMoveRotation(lastControlledPosition,
                        mc.player.getDeltaMovement(), mc.player.getYRot(), mc.player.getXRot()),
                        packet.change(), packet.relatives()).position();
                flightController.setback(lastControlledPosition == null || target.distanceToSqr(lastControlledPosition) > 64 * 64);
            }
            boostDelay.observe(mc.player.tickCount, true, true);
            Vec3 natural = mc.player.getDeltaMovement();
            ServerGlide.Context glide = new ServerGlide.Context(new Motion(natural.x, natural.y, natural.z), attachedRockets(),
                steering.pitch(), steering.yaw(), mc.player.getGravity(), mc.player.hasEffect(MobEffects.SLOW_FALLING),
                this::resolveCollision);
            Trip landing = steering.landing();
            landingControlled = landing != null;
            Motion output;
            if (landingControlled) {
                // Baritone's landing waypoint is above the floor. Its vanilla pitch can turn upward below it,
                // so guide the body to the selected column and descend under Elytra Fly's own control.
                output = flightController.land(profile, config, mc.player.tickCount,
                    landing.x() + .5 - mc.player.getX(), landing.z() + .5 - mc.player.getZ(),
                    boostDelay.ready(), natural.length(), event.movement.length(), mc.player.getBoundingBox(), flightProbe,
                    () -> floorClearance(profile.margin()), glide, movement -> resolveCollision(mc.player.getBoundingBox(), movement));
            } else {
                Trip destination = baritone.flight();
                if (destination != null) {
                    double distance = mc.player.position().distanceTo(new Vec3(destination.x() + .5,
                        destination.y() + .5, destination.z() + .5));
                    config = BaritoneFlight.approach(config, distance);
                }
                output = flightController.move(profile, config, mc.player.tickCount, steering.yaw(), steering.pitch(),
                    boostDelay.ready(), natural.length(), event.movement.length(), mc.player.getBoundingBox(), flightProbe,
                    () -> floorClearance(profile.margin()), glide, movement -> resolveCollision(mc.player.getBoundingBox(), movement));
            }
            if (output == null) return;
            lastControlledPosition = mc.player.position();
            ((IVec3d) event.movement).meteor$set(output.x(), output.y(), output.z());
        } catch (LinkageError | RuntimeException e) {
            MeteorClient.LOG.error("Baritone Elytra's adjustable flight failed", e);
            error("Adjustable flight failed; Baritone keeps flying with normal physics. See the log.");
            controlledFlight.set(false);
            resetFlightController();
        }
    }

    private boolean customFlightSettings() { return controlledFlight.get() && !useAdaptiveSettings.get(); }

    private boolean needsFireworks() { return !controlledFlight.get(); }

    private void flightModeChanged() {
        // A trip refused for missing fireworks becomes eligible as soon as powered flight is selected.
        settled = waiting = null;
        waitingReason = null;
        recheck = 0;
        updateRocketlessMode();
    }

    private void updateRocketlessMode() {
        if (baritone != null) baritone.rocketless(isActive() && controlledFlight.get()
            && mc.player != null && mc.level != null);
    }

    private Motion resolveCollision(AABB body, Motion movement) {
        Vec3 vec = new Vec3(movement.x(), movement.y(), movement.z());
        List<VoxelShape> entities = mc.level.getEntityCollisions(mc.player, body.expandTowards(vec));
        Vec3 allowed = Entity.collideBoundingBox(mc.player, vec, body, mc.level, entities);
        return new Motion(allowed.x, allowed.y, allowed.z);
    }

    private double floorClearance(double margin) {
        FlightCorridor.Result below = FlightCorridor.inspect(mc.player.getBoundingBox(), new Vec3(0, -1, 0), 32, floorProbe);
        return below.reason() == FlightCorridor.Reason.CLEAR ? Double.POSITIVE_INFINITY
            : Math.max(0, below.clearDistance() - margin);
    }

    private void resetFlightController() {
        if (flightController != null) flightController.reset();
        landingControlled = false;
        lastControlledPosition = null;
        correction.set(null);
    }

    private void pauseElytraFly() {
        ElytraFly elytraFly = Modules.get().get(ElytraFly.class);
        if (elytraFly != null && elytraFly.isActive()) {
            ElytraFly.ChestSwapMode chestSwap = elytraFly.chestSwap.get();
            Setting<?> dropSetting = elytraFly.settings.get("insta-drop");
            BoolSetting drop = dropSetting instanceof BoolSetting bool ? bool : null;
            boolean instaDrop = drop != null && drop.get();
            try {
                // A handoff must keep the wings on and must not arm Meteor's forced-ground drop listener.
                elytraFly.chestSwap.set(ElytraFly.ChestSwapMode.Never);
                if (drop != null) drop.set(false);
                elytraFly.toggle();
                resumeElytraFly = true;
            } finally {
                elytraFly.chestSwap.set(chestSwap);
                if (drop != null) drop.set(instaDrop);
            }
        }
    }

    private int attachedRockets() {
        int count = 0;
        for (FireworkRocketEntity rocket : mc.level.getEntitiesOfClass(FireworkRocketEntity.class,
            mc.player.getBoundingBox().inflate(16))) {
            if (rocket.getEntityData().get(FireworkRocketAccessor.comet$attachedTarget())
                .orElse(Integer.MIN_VALUE) == mc.player.getId()) count++;
        }
        return Math.max(count, pendingRocketTicks > 0 ? 1 : 0);
    }

    @EventHandler private void onTick(TickEvent.Pre event) {
        if (mc.player == null || mc.level == null || baritone == null) return;
        try {
            updateRocketlessMode();
            if (trip != null) tickFlight();
            else tickGround();
            if (trip == null && mc.player.onGround()) swapBack();
        } catch (LinkageError | RuntimeException e) {
            MeteorClient.LOG.error("Handing Baritone's trip to its elytra flight failed", e);
            error("Baritone didn't accept the elytra hand-off (%s). Turning off; see the log.", e);
            toggle();
        }
    }

    private void tickGround() {
        Trip flight = baritone.flight();
        if (flight != null) {
            // Started by #elytra or another addon.
            start(flight, false);
            return;
        }
        Trip walk = baritone.walking();
        if (walk == null || walk.goal() == settled) return;
        if (walk.goal() == waiting && ++recheck < RECHECK_TICKS) return;
        recheck = 0;
        Refusal refusal = refusal(walk);
        if (refusal == null) {
            launch(walk);
            return;
        }
        if (refusal.settled) settled = walk.goal();
        else if (walk.goal() == waiting && refusal == waitingReason) return;
        else {
            waiting = walk.goal();
            waitingReason = refusal;
        }
        report(refusal);
    }

    private void launch(Trip walk) {
        try {
            updateRocketlessMode();
            baritone.fly(walk);
        } catch (IllegalArgumentException e) {
            settled = walk.goal();
            warning("Baritone won't fly there: %s Walking instead.", e.getMessage());
            return;
        }
        start(walk, true);
        info("Flying to %d, %d, %d blocks away.", walk.x(), walk.z(), (int) remaining(walk));
    }

    private void start(Trip flight, boolean ours) {
        trip = flight;
        this.ours = ours;
        glided = mc.player.isFallFlying();
        handsOff = false;
        takeoff.reset(glided);
        restockWait = 0;
        pathWait = 0;
        resetFlightController();
        boostDelay.reset();
        pendingRocketTicks = 0;
        if (!glided) boostDelay.swapped();
        ElytraFly elytraFly = Modules.get().get(ElytraFly.class);
        if ((ours || controlledFlight.get()) && elytraFly.isActive()) {
            pauseElytraFly();
            info("Paused Meteor ElytraFly while Baritone controls the flight.");
        }
    }

    private void tickFlight() {
        Trip flight = baritone.flight();
        if (flight == null) {
            endFlight();
            return;
        }
        if (ours || controlledFlight.get()) pauseElytraFly();
        // #goto and #goal mid-flight redirect it, and the new goal is the one to walk to after landing. A flight someone
        // else started is followed wherever it goes, landing spot included.
        if (!ours || flight.goal() != null && flight.goal() != trip.goal()) trip = flight;
        boolean fallFlying = mc.player.isFallFlying();
        glided |= fallFlying;
        if (ours && !fallFlying) equipElytra();
        restock();
        if (!takeOff.get() || handsOff || baritone.autoJump()) return;
        if (!baritone.readyForTakeoff()) {
            if (!glided && ++pathWait > PATH_TICKS) {
                // Readiness is read from Baritone's status text, which can change between Baritone versions.
                MeteorClient.LOG.warn("Baritone elytra take-off timed out; Baritone's status was \"{}\"", baritone.flightStatus());
                fallBack("Baritone couldn't find a flight path.", true);
            }
            return;
        }
        boolean fluid = mc.player.isInWater() || mc.player.isInLava();
        ElytraTakeoff.Step step = fluid ? ElytraTakeoff.Step.COVERED : takeoff.next(mc.player.onGround(), fallFlying, mc.player.getDeltaMovement().y, openAbove());
        switch (step) {
            case JUMP -> mc.player.jumpFromGround();
            case OPEN -> {
                // What the client does for a second press of the jump key in the air.
                if (mc.player.tryToStartFallFlying()) mc.getConnection().send(new ServerboundPlayerCommandPacket(mc.player, ServerboundPlayerCommandPacket.Action.START_FALL_FLYING));
            }
            case COVERED -> fallBack("Something is above you, or you're in water or lava, so you can't take off.", false);
            case GAVE_UP -> fallBack("Couldn't take off after %d jumps.".formatted(ElytraTakeoff.MAX_JUMPS), true);
            case WAIT -> {}
        }
    }

    /** Cancels a #goto flight that can't take off and walks instead. A flight someone else started is left to them. */
    private void fallBack(String reason, boolean settle) {
        Trip flight = trip;
        if (!ours || flight.goal() == null) {
            handsOff = true;
            warning("%s Take off by hand, or #stop.", reason);
            return;
        }
        baritone.stop();
        finishFlight();
        baritone.walk(flight);
        if (settle) {
            settled = flight.goal();
            warning("%s Walking instead.", reason);
        } else {
            // Checked again while walking, so it takes off once there is open sky.
            waiting = flight.goal();
            waitingReason = Refusal.COVERED;
            recheck = 0;
            warning("%s Walking until there's open sky.", reason);
        }
    }

    private void endFlight() {
        Trip flight = trip;
        boolean mine = ours, wasGliding = glided;
        finishFlight();
        // Whoever started it reports its end.
        if (!mine) return;
        double remaining = remaining(flight);
        switch (ElytraHandoff.end(mc.player.isFallFlying(), wasGliding, remaining, Math.max(minDistance.get(), NEAR))) {
            case WALK_REST -> {
                if (flight.goal() == null) return;
                settled = flight.goal();
                baritone.walk(flight);
                info("Landed %d blocks from the goal; walking the rest.", (int) remaining);
            }
            case LANDED_SHORT -> warning("Landed %d blocks short of %d, %d. Baritone lands early when %s run low; #goto again to carry on.", (int) remaining, flight.x(), flight.z(), controlledFlight.get() ? "elytra durability reserves" : "fireworks or elytra durability reserves");
            case STOPPED_MID_AIR -> warning("Baritone stopped the flight in mid-air; you're gliding on your own.");
            case STOPPED_ON_GROUND -> info("The elytra flight was cancelled before take-off.");
        }
    }

    private void finishFlight() {
        resetFlightController();
        boostDelay.reset();
        pendingRocketTicks = 0;
        trip = null;
        ours = false;
        glided = false;
        if (resumeElytraFly) {
            resumeElytraFly = false;
            ElytraFly elytraFly = Modules.get().get(ElytraFly.class);
            if (!elytraFly.isActive()) elytraFly.toggle();
        }
    }

    private void reset() {
        trip = null;
        settled = null;
        waiting = null;
        waitingReason = null;
        recheck = 0;
        chestplateSlot = -1;
    }

    private Refusal refusal(Trip walk) {
        int durability = elytraDurability(mc.player.getItemBySlot(EquipmentSlot.CHEST));
        if (equip.get()) {
            int slot = bestElytraSlot();
            if (slot >= 0) durability = Math.max(durability, elytraDurability(mc.player.getInventory().getItem(slot)));
        }
        boolean clear = !mc.player.isInWater() && !mc.player.isInLava() && openAbove();
        return ElytraHandoff.check(remaining(walk), minDistance.get(), baritone.elytraSupported(), durability, baritone.minDurability(),
            needsFireworks() ? fireworks() : 0, baritone.minFireworks(), mc.player.isFallFlying(), mc.player.onGround(), clear,
            controlledFlight.get());
    }

    private void report(Refusal refusal) {
        switch (refusal) {
            case UNSUPPORTED -> warning("Baritone has no elytra pathfinder for this system. Walking.");
            case NO_ELYTRA -> warning("No elytra with at least %d durability %s (Baritone's elytraMinimumDurability). Walking.",
                baritone.minDurability(), equip.get() ? "worn or in your inventory" : "worn");
            case FIREWORKS -> warning("Only %d fireworks %s, and Baritone lands at %d (elytraMinFireworksBeforeLanding). Walking.",
                fireworks(), restock.get() || baritone.useInventory() ? "in your inventory" : "in your hotbar", baritone.minFireworks());
            case COVERED -> info("Something is above you, or you're in water or lava. Walking until there's open sky to take off.");
            case SHORT, AIRBORNE -> {}
        }
    }

    private boolean openAbove() {
        return mc.level.noCollision(mc.player, mc.player.getBoundingBox().expandTowards(0, HEADROOM, 0));
    }

    private double remaining(Trip trip) {
        return ElytraHandoff.horizontalDistance(mc.player.getX(), mc.player.getZ(), trip.x() + 0.5, trip.z() + 0.5);
    }

    private void equipElytra() {
        if (!equip.get() || mc.player.containerMenu != mc.player.inventoryMenu) return;
        ItemStack chest = mc.player.getItemBySlot(EquipmentSlot.CHEST);
        if (elytraDurability(chest) >= baritone.minDurability()) return;
        int slot = bestElytraSlot();
        if (slot < 0 || elytraDurability(mc.player.getInventory().getItem(slot)) < baritone.minDurability()) return;
        // The click swap leaves whatever was worn in the elytra's slot.
        boolean chestplate = !chest.isEmpty() && !chest.is(Items.ELYTRA) && mc.player.getEquipmentSlotForItem(chest) == EquipmentSlot.CHEST;
        InvUtils.move().from(slot).toArmor(2);
        if (chestplate) chestplateSlot = slot;
    }

    private void swapBack() {
        if (chestplateSlot < 0 || mc.player.containerMenu != mc.player.inventoryMenu) return;
        int slot = chestplateSlot;
        chestplateSlot = -1;
        ItemStack stored = mc.player.getInventory().getItem(slot);
        if (swapBack.get() && mc.player.getItemBySlot(EquipmentSlot.CHEST).is(Items.ELYTRA) && !stored.isEmpty() && !stored.is(Items.ELYTRA)
            && mc.player.getEquipmentSlotForItem(stored) == EquipmentSlot.CHEST) InvUtils.move().from(slot).toArmor(2);
    }

    private void restock() {
        if (!needsFireworks() || !restock.get() || baritone.useInventory()) return;
        if (restockWait > 0) {
            restockWait--;
            return;
        }
        if (mc.player.containerMenu != mc.player.inventoryMenu) return;
        Inventory inventory = mc.player.getInventory();
        int empty = -1;
        for (int i = 0; i < Inventory.SELECTION_SIZE; i++) {
            ItemStack stack = inventory.getItem(i);
            if (isFirework(stack)) return;
            if (empty < 0 && stack.isEmpty()) empty = i;
        }
        for (int i = Inventory.SELECTION_SIZE; i < Inventory.INVENTORY_SIZE; i++) {
            if (!isFirework(inventory.getItem(i))) continue;
            // Without an empty hotbar slot, the item swapped out goes to where the fireworks were.
            int preferred = restockSlot.get() - 1;
            int to = empty >= 0 ? empty : inventory.getSelectedSlot() != preferred ? preferred : preferred == 0 ? 1 : preferred - 1;
            InvUtils.move().from(i).toHotbar(to);
            restockWait = RESTOCK_TICKS;
            return;
        }
    }

    /**
     * Fireworks Baritone can fire and also counts before landing for safety. It counts the 36 inventory slots (not the
     * offhand) but fires only from the hotbar, unless it or restock-fireworks moves them there.
     */
    private int fireworks() {
        int end = restock.get() || baritone.useInventory() ? Inventory.INVENTORY_SIZE : Inventory.SELECTION_SIZE;
        int count = 0;
        for (int i = 0; i < end; i++) {
            ItemStack stack = mc.player.getInventory().getItem(i);
            if (isFirework(stack)) count += stack.getCount();
        }
        return count;
    }

    /** Baritone's test: rockets with a star would hurt the player, so it never fires them. */
    private static boolean isFirework(ItemStack stack) {
        Fireworks fireworks = stack.get(DataComponents.FIREWORKS);
        return stack.is(Items.FIREWORK_ROCKET) && fireworks != null && fireworks.explosions().isEmpty();
    }

    /**
     * Remaining durability of an elytra Baritone can fly with (a real elytra, not about to break), otherwise -1. Unlike
     * the other elytra modules this needs the vanilla elytra item: Baritone's elytra process checks for exactly that.
     */
    private static int elytraDurability(ItemStack stack) {
        return stack.is(Items.ELYTRA) && !stack.nextDamageWillBreak() ? stack.getMaxDamage() - stack.getDamageValue() : -1;
    }

    private int bestElytraSlot() {
        int best = -1, bestDurability = -1;
        for (int i = 0; i < Inventory.INVENTORY_SIZE; i++) {
            int durability = elytraDurability(mc.player.getInventory().getItem(i));
            if (durability > bestDurability) {
                best = i;
                bestDurability = durability;
            }
        }
        return best;
    }

    /** Turns ElytraFly back on once a flight left to Baritone has landed, after this module itself is off. */
    private static final class ElytraFlyResume {
        private static ElytraFlyResume pending;

        static void afterLanding() {
            if (pending != null) return;
            pending = new ElytraFlyResume();
            MeteorClient.EVENT_BUS.subscribe(pending);
        }

        @EventHandler private void onTick(TickEvent.Post event) {
            // Without a player there is no flight left to conflict with.
            if (MeteorClient.mc.player != null && MeteorClient.mc.player.isFallFlying()) return;
            MeteorClient.EVENT_BUS.unsubscribe(this);
            pending = null;
            ElytraFly elytraFly = Modules.get().get(ElytraFly.class);
            if (elytraFly != null && !elytraFly.isActive()) elytraFly.toggle();
        }
    }
}
