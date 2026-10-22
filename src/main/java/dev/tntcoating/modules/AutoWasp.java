/*
 * Adapted from DEVILS by ThianYong-hub and DEVILS contributors.
 * Upstream: https://github.com/ThianYong-hub/DEVILS
 * Revision: 2440a168c3ba6f051f2adf8249a8e6fb5f3a5b02
 * Auto Wasp is also based on Meteor Client, Copyright (c) Meteor Development.
 * SPDX-License-Identifier: GPL-3.0-only
 *
 * Modified for Comet by Boyonabike on 2026-10-22: Minecraft 26.1.2 mappings,
 * local inventory integration, lifecycle/navigation guards and settings credits;
 * speed ramp, setback and server-glide settings, ping-aware target prediction,
 * worn-out elytra handling and chest-swap hysteresis.
 * Distributed under GNU GPL v3; see LICENSE-DEVILS and THIRD_PARTY_NOTICES.md.
 * This program is distributed WITHOUT ANY WARRANTY; without even the implied
 * warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
 */

package dev.tntcoating.modules;

import dev.tntcoating.TntCoatingAddon;
import dev.tntcoating.modules.autowasp.AutoWaspChestSwap;
import dev.tntcoating.modules.autowasp.AutoWaspFlightController;
import dev.tntcoating.modules.autowasp.AutoWaspPathfinder;
import dev.tntcoating.modules.autowasp.AutoWaspTargetTracker;
import meteordevelopment.meteorclient.events.entity.player.PlayerMoveEvent;
import meteordevelopment.meteorclient.events.packets.PacketEvent;
import meteordevelopment.meteorclient.events.world.TickEvent;
import meteordevelopment.meteorclient.settings.BoolSetting;
import meteordevelopment.meteorclient.settings.DoubleSetting;
import meteordevelopment.meteorclient.settings.EnumSetting;
import meteordevelopment.meteorclient.settings.IntSetting;
import meteordevelopment.meteorclient.settings.Setting;
import meteordevelopment.meteorclient.settings.SettingGroup;
import meteordevelopment.meteorclient.settings.Vector3dSetting;
import meteordevelopment.meteorclient.systems.friends.Friends;
import meteordevelopment.meteorclient.systems.modules.Module;
import meteordevelopment.meteorclient.utils.entity.SortPriority;
import meteordevelopment.meteorclient.utils.entity.TargetUtils;
import meteordevelopment.orbit.EventHandler;
import net.minecraft.client.Minecraft;
import net.minecraft.core.component.DataComponents;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.network.protocol.common.ClientboundDisconnectPacket;
import net.minecraft.network.chat.Component;
import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.util.Mth;
import net.minecraft.world.phys.Vec3;
import org.joml.Vector3d;

public class AutoWasp extends Module {
    private static final Direction[] CARDINAL_DIRECTIONS = {
        Direction.NORTH, Direction.SOUTH, Direction.WEST, Direction.EAST
    };
    private static final int TARGET_SEARCH_TICKS = 10;
    private static final int CHEST_SWAP_COOLDOWN_TICKS = 10;

    private final SettingGroup sgGeneral = settings.getDefaultGroup();
    private final SettingGroup sgChestSwap = settings.createGroup("Chest Swap");
    private final SettingGroup sgRamp = settings.createGroup("Speed Ramp");
    private final SettingGroup sgServerGlide = settings.createGroup("Server Glide");

    private final Setting<Double> horizontalSpeed = sgGeneral.add(new DoubleSetting.Builder()
        .name("horizontal-speed")
        .description("Horizontal elytra speed in blocks per tick. Total speed never passes 17 blocks per tick, the most a vanilla server accepts.")
        .defaultValue(2.0)
        .min(0.14)
        .build()
    );

    private final Setting<Double> verticalSpeed = sgGeneral.add(new DoubleSetting.Builder()
        .name("vertical-speed")
        .description("Vertical elytra speed in blocks per tick.")
        .defaultValue(3.0)
        .min(0.0)
        .build()
    );

    private final Setting<Boolean> avoidLanding = sgGeneral.add(new BoolSetting.Builder()
        .name("avoid-landing")
        .description("Will try to avoid landing if your target is on the ground.")
        .defaultValue(true)
        .build()
    );

    private final Setting<Boolean> predictMovement = sgGeneral.add(new BoolSetting.Builder()
        .name("predict-movement")
        .description("Leads the target by its recent movement over your ping plus the time other players' movement takes to show, and matches its speed when flying straight at it.")
        .defaultValue(true)
        .build()
    );

    private final Setting<Boolean> onlyFriends = sgGeneral.add(new BoolSetting.Builder()
        .name("only-friends")
        .description("Will only follow friends.")
        .defaultValue(false)
        .build()
    );

    private final Setting<Boolean> friendFilter = sgGeneral.add(new BoolSetting.Builder()
        .name("friend-filter")
        .description("When enabled: onlyFriends=true -> friends, onlyFriends=false -> enemies. When disabled: original logic.")
        .defaultValue(false)
        .build()
    );

    private final Setting<Action> action = sgGeneral.add(new EnumSetting.Builder<Action>()
        .name("action-on-target-loss")
        .description("What to do if you lose the target.")
        .defaultValue(Action.TOGGLE)
        .build()
    );

    private final Setting<Boolean> keepSearching = sgGeneral.add(new BoolSetting.Builder()
        .name("keep-searching")
        .description("Stays enabled and keeps looking for a target instead of disabling when none is loaded.")
        .defaultValue(true)
        .build()
    );

    private final Setting<Vector3d> offset = sgGeneral.add(new Vector3dSetting.Builder()
        .name("offset")
        .description("How many blocks offset to wasp at from the target.")
        .defaultValue(0, 0, 0)
        .build()
    );

    private final Setting<Boolean> autoChestSwap = sgChestSwap.add(new BoolSetting.Builder()
        .name("auto-chest-swap")
        .description("Equips a chestplate near the target on stable ground, and elytra for pursuit. Pauses in containers.")
        .defaultValue(true)
        .build()
    );

    private final Setting<Double> chestSwapHorizontalRange = sgChestSwap.add(new DoubleSetting.Builder()
        .name("chest-swap-horizontal-range")
        .description("Maximum horizontal distance to the target before swapping off elytra.")
        .defaultValue(2.5)
        .range(0.5, 6.0)
        .sliderRange(0.5, 6.0)
        .visible(autoChestSwap::get)
        .build()
    );

    private final Setting<Double> chestSwapVerticalRange = sgChestSwap.add(new DoubleSetting.Builder()
        .name("chest-swap-vertical-range")
        .description("Maximum vertical difference to the target before swapping off elytra.")
        .defaultValue(1.0)
        .range(0.0, 4.0)
        .sliderRange(0.0, 4.0)
        .visible(autoChestSwap::get)
        .build()
    );

    private final Setting<Double> chestSwapGroundDistance = sgChestSwap.add(new DoubleSetting.Builder()
        .name("chest-swap-ground-distance")
        .description("Only swaps off elytra when there is solid ground close enough below you.")
        .defaultValue(1.25)
        .range(0.0, 4.0)
        .sliderRange(0.0, 4.0)
        .visible(autoChestSwap::get)
        .build()
    );

    private final Setting<Double> chestSwapHysteresis = sgChestSwap.add(new DoubleSetting.Builder()
        .name("chest-swap-hysteresis")
        .description("Once the chestplate is on, the target must move this many blocks beyond each range before the elytra goes back on, so a target at the edge of a range does not swap you back and forth.")
        .defaultValue(1.5)
        .range(0.0, 4.0)
        .sliderRange(0.0, 4.0)
        .visible(autoChestSwap::get)
        .build()
    );

    private final Setting<Double> rampStartSpeed = sgRamp.add(new DoubleSetting.Builder()
        .name("ramp-start-speed")
        .description("Total speed, in blocks per tick, that pursuit starts from and that a setback never rewinds below. It then speeds up along the ramp to your speed caps.")
        .defaultValue(1.0)
        .range(0.1, 5.0)
        .sliderRange(0.1, 3.0)
        .build()
    );

    private final Setting<Double> rampStep = sgRamp.add(new DoubleSetting.Builder()
        .name("ramp-step")
        .description("Acceleration in blocks per tick squared at rest, tapering to zero at 15 blocks per tick (VolytraFly's 300 b/s curve). It also paces speeding up again after braking, a turn or a wall.")
        .defaultValue(0.3)
        .range(0.01, 5.0)
        .sliderRange(0.05, 2.0)
        .build()
    );

    private final Setting<Integer> setbackRewindTicks = sgRamp.add(new IntSetting.Builder()
        .name("setback-rewind-ticks")
        .description("A server setback rewinds the ramp by this many ticks of acceleration instead of restarting it.")
        .defaultValue(15)
        .range(0, 200)
        .sliderRange(0, 60)
        .build()
    );

    private final Setting<Integer> setbackHoldTicks = sgRamp.add(new IntSetting.Builder()
        .name("setback-hold-ticks")
        .description("After a setback, hold the rewound speed for this many ticks before accelerating again, so back-to-back setbacks cannot pin you in place.")
        .defaultValue(1)
        .range(0, 20)
        .sliderRange(0, 10)
        .build()
    );

    private final Setting<Integer> pinnedGlideTicks = sgRamp.add(new IntSetting.Builder()
        .name("pinned-glide-ticks")
        .description("When two setbacks in a row put you back on exactly the same spot, the server is rejecting every move from there. Glide with plain vanilla elytra physics for this many ticks so it accepts one, then ramp up again from the start speed. Zero disables.")
        .defaultValue(10)
        .range(0, 60)
        .sliderRange(0, 40)
        .build()
    );

    private final Setting<Double> turnRate = sgRamp.add(new DoubleSetting.Builder()
        .name("turn-rate")
        .description("How fast pursuit may swing its heading, in degrees per tick, rising with speed to double at horizontal-speed. Turning stays instant below 0.6 blocks per tick, a reversal of 135 degrees or more brakes first, and obstacle avoidance is never limited. Zero turns instantly.")
        .defaultValue(30)
        .range(0, 90)
        .sliderRange(0, 90)
        .build()
    );

    private final Setting<Double> braking = sgRamp.add(new DoubleSetting.Builder()
        .name("braking")
        .description("Deceleration in blocks per tick squared when arriving at the target or a waypoint and when reversing, so pursuit slows down instead of stopping dead or overshooting. Emergency braking near walls is never limited.")
        .defaultValue(0.3)
        .range(0.01, 2.0)
        .sliderRange(0.05, 1.0)
        .build()
    );

    private final Setting<Double> maneuverMaxSpeed = sgRamp.add(new DoubleSetting.Builder()
        .name("maneuver-max-speed")
        .description("Total speed cap, in blocks per tick, while moving horizontally, climbing or descending and turning at once. Only matters with speeds raised above it.")
        .defaultValue(10.0)
        .range(1.0, 17.0)
        .sliderRange(1.0, 17.0)
        .build()
    );

    private final Setting<Boolean> serverGlideGuard = sgServerGlide.add(new BoolSetting.Builder()
        .name("server-glide-guard")
        .description("Keep walls out of reach of the server's own elytra glide. A vanilla server keeps simulating a normal glide from your reported position every tick, and that hidden glide, not your visible speed, deals fly-into-wall damage.")
        .defaultValue(true)
        .build()
    );

    private final Setting<Boolean> steerServerGlide = sgServerGlide.add(new BoolSetting.Builder()
        .name("steer-server-glide")
        .description("Near a wall the server's glide could reach, report a level heading away from it so the hidden glide turns away and pursuit can fly close to walls. Your camera does not move; other players see your head turned away while it steers.")
        .defaultValue(true)
        .build()
    );

    private final AutoWaspPathfinder pathfinder = new AutoWaspPathfinder(this);
    private final AutoWaspFlightController flightController = new AutoWaspFlightController(this, pathfinder);
    private final AutoWaspTargetTracker targetTracker = new AutoWaspTargetTracker();

    private Player target;
    private int targetSearchTimer;
    private boolean waitingForElytra;
    private int chestSwapCooldown;

    public AutoWasp() {
        super(TntCoatingAddon.CATEGORY, "auto-wasp-devils", "Follows a target with elytra using obstacle-aware routing.");
    }

    @Override
    public void onActivate() {
        if (mc.player == null || mc.level == null) { toggle(); return; }

        // Each flight module owns movement; avoid having another controller overwrite this one.
        var modules = meteordevelopment.meteorclient.systems.modules.Modules.get();
        for (var conflict : new Module[] {
            modules.get(meteordevelopment.meteorclient.systems.modules.movement.AutoWasp.class),
            modules.get(meteordevelopment.meteorclient.systems.modules.movement.elytrafly.ElytraFly.class),
            modules.get(BaritoneElytra.class)
        }) {
            if (conflict != null && conflict.isActive()) {
                conflict.toggle();
                info("Disabled %s for Auto Wasp pursuit.", conflict.title);
            }
        }
        resetNavigationState();
        chestSwapCooldown = 0;

        if (target == null || target.isRemoved()) {
            if (tryAcquireTarget(false)) {

                info("Target set to: " + target.getName().getString());
            } else if (!keepSearching.get()) {

                error("No valid targets.");
                toggle();
            }
        }
    }

    @Override
    public void onDeactivate() {
        target = null;
        targetSearchTimer = 0;
        waitingForElytra = false;
        chestSwapCooldown = 0;
        targetTracker.reset();
        flightController.onDeactivate();
        pathfinder.onDeactivate();
    }

    @EventHandler
    private void onTick(TickEvent.Pre event) {
        onTickSafe();
    }

    @EventHandler
    private void onPostTick(TickEvent.Post event) {
        if (mc.player != null && mc.level != null) flightController.postTick();
    }

    @EventHandler
    private void onMove(PlayerMoveEvent event) {
        onMoveSafe(event);
    }

    // Runs on Netty: the flight controller only fills atomic mailboxes here.
    @EventHandler
    private void onReceive(PacketEvent.Receive event) {
        flightController.onReceive(event.packet);
    }

    @EventHandler
    private void onSend(PacketEvent.Send event) {
        flightController.onSend(event.packet);
    }

    private void onTickSafe() {
        if (mc.player == null || mc.level == null || !mc.player.isAlive() || mc.player.isSpectator()) return;
        flightController.preTick(hasUsableElytra());
        if (meteordevelopment.meteorclient.systems.modules.Modules.get().isActive(
                meteordevelopment.meteorclient.systems.modules.render.Freecam.class)) return;

        pathfinder.tickCaches();
        if (chestSwapCooldown > 0) chestSwapCooldown--;
        if (targetSearchTimer > 0) targetSearchTimer--;

        if (target == null || target.level() != mc.level || target.isRemoved() || target.isDeadOrDying() || target.getHealth() <= 0) {
            if (!handleMissingTarget()) {
                tickAutoChestSwap();
                return;
            }
        }

        if (target == null) {
            tickAutoChestSwap();
            return;
        }

        boolean isFriend = Friends.get().isFriend(target);
        boolean shouldIgnore = friendFilter.get()
            ? (onlyFriends.get() && !isFriend) || (!onlyFriends.get() && isFriend)
            : onlyFriends.get() && !isFriend;
        if (shouldIgnore) {

            if (!handleMissingTarget()) {
                tickAutoChestSwap();
                return;
            }
        }

        if (target == null) {
            tickAutoChestSwap();
            return;
        }

        targetTracker.sample(target.getId(), target.position());
        boolean shouldUseElytra = tickAutoChestSwap();
        if (!hasUsableElytra()) {
            if (!shouldUseElytra) {
                waitingForElytra = false;
                flightController.resetFlightTracking();
                return;
            }
            if (!waitingForElytra) {
                warning("No usable elytra equipped, waiting for swap...");
                waitingForElytra = true;
            }
            flightController.resetFlightTracking();
            return;
        }

        waitingForElytra = false;
        if (!mc.player.isFallFlying()) {
            flightController.handleNotGliding();
            return;
        }

        flightController.onGlidingTick();
        pathfinder.tickPathing(mc.player.position(), getTargetPos(), flightController.getStuckTicks());
    }

    private void onMoveSafe(PlayerMoveEvent event) {
        if (mc.player == null || mc.level == null || !mc.player.isAlive() || mc.player.isSpectator()
            || event.type != net.minecraft.world.entity.MoverType.SELF) return;
        if (meteordevelopment.meteorclient.systems.modules.Modules.get().isActive(
                meteordevelopment.meteorclient.systems.modules.render.Freecam.class)) return;
        if (target == null || target.level() != mc.level || target.isRemoved() || target.isDeadOrDying()) return;
        if (!hasUsableElytra() || !mc.player.isFallFlying()) return;

        Vec3 targetPos = getTargetPos();
        Vec3 steer = pathfinder.steerTarget(targetPos);
        // Only a straight approach matches the target's own movement; a waypoint is a fixed point to pass.
        Vec3 lead = predictMovement.get() && steer == targetPos ? targetTracker.velocity() : Vec3.ZERO;
        flightController.onMove(event, steer, lead);
    }

    private void findTarget() {
        target = (Player) TargetUtils.get(entity -> {
            if (!(entity instanceof Player player) || entity == mc.player) return false;
            if (player.isDeadOrDying() || player.getHealth() <= 0) return false;

            boolean isFriend = Friends.get().isFriend(player);
            if (friendFilter.get()) return onlyFriends.get() ? isFriend : !isFriend;
            return !onlyFriends.get() || isFriend;
        }, SortPriority.LowestDistance);

    }

    private Vec3 getTargetPos() {
        if (target == null || mc.player == null) return mc.player != null ? mc.player.position() : Vec3.ZERO;

        Vec3 targetPos = target.position();
        if (predictMovement.get()) targetPos = targetTracker.predict(targetPos, pingMs());
        targetPos = targetPos.add(offset.get().x, offset.get().y, offset.get().z);
        if (avoidLanding.get()) targetPos = liftTargetIfOnGround(targetPos);
        return pathfinder.adjustToSafeCorridor(targetPos, mc.player.position());
    }

    private int pingMs() {
        var connection = mc.getConnection();
        var info = connection == null || mc.player == null ? null : connection.getPlayerInfo(mc.player.getUUID());
        return info == null ? 0 : Math.max(0, info.getLatency());
    }

    private Vec3 liftTargetIfOnGround(Vec3 targetPos) {
        if (target == null) return targetPos;

        double distance = Math.max(0.35, target.getBoundingBox().getXsize() / 2.0);
        for (Direction direction : CARDINAL_DIRECTIONS) {
            BlockPos pos = BlockPos.containing(targetPos.relative(direction, distance).relative(direction.getClockWise(), distance)).below();
            if (pathfinder.isSolid(pos) && Math.abs(targetPos.y - (pos.getY() + 1)) <= 0.35) {
                return new Vec3(targetPos.x, pos.getY() + 1.35, targetPos.z);
            }
        }
        return targetPos;
    }

    private boolean tickAutoChestSwap() {
        boolean shouldUseElytra = !shouldSwapToChestplate();
        if (autoChestSwap.get() && target != null) requestChestGear(shouldUseElytra);
        return shouldUseElytra;
    }

    private boolean shouldSwapToChestplate() {
        if (!autoChestSwap.get() || mc.player == null || target == null) return false;

        ItemStack worn = mc.player.getItemBySlot(EquipmentSlot.CHEST);
        boolean wearingChestplate = !worn.isEmpty() && !worn.has(DataComponents.GLIDER);
        double extra = wearingChestplate ? chestSwapHysteresis.get() : 0;
        double horizontalDistance = Math.hypot(mc.player.getX() - target.getX(), mc.player.getZ() - target.getZ());
        double verticalDistance = Math.abs(mc.player.getY() - target.getY());
        int scanDepth = Math.max(3, Mth.ceil(chestSwapGroundDistance.get() + extra) + 2);
        double groundDistance = pathfinder.distanceToSolidBelow(mc.player.position(), scanDepth);
        return AutoWaspChestSwap.wearChestplate(wearingChestplate, horizontalDistance, verticalDistance, groundDistance,
            chestSwapHorizontalRange.get(), chestSwapVerticalRange.get(), chestSwapGroundDistance.get(),
            chestSwapHysteresis.get());
    }

    private void requestChestGear(boolean glider) {
        if (mc.player == null || mc.gameMode == null || chestSwapCooldown > 0
            || mc.player.containerMenu != mc.player.inventoryMenu
            || !mc.player.inventoryMenu.getCarried().isEmpty()
            || dev.tntcoating.core.InventorySlotLoan.INSTANCE.isBorrowing()
            || dev.tntcoating.core.InventoryWeaponAttack.INSTANCE.isPending()) return;
        ItemStack worn = mc.player.getItemBySlot(EquipmentSlot.CHEST);
        // A worn-out elytra still counts as a glider but cannot glide, so it is replaced like a missing one.
        if (glider ? isUsableGlider(worn) : !worn.isEmpty() && !worn.has(DataComponents.GLIDER)) return;
        var chestSlot = mc.player.inventoryMenu.getSlot(6);
        if (!chestSlot.mayPickup(mc.player)) return;
        var candidates = new java.util.ArrayList<AutoWaspChestSwap.Candidate>();
        var inventory = mc.player.getInventory();
        for (int i = 0; i <= net.minecraft.world.entity.player.Inventory.INVENTORY_SIZE; i++) {
            int slot = i == net.minecraft.world.entity.player.Inventory.INVENTORY_SIZE
                ? net.minecraft.world.entity.player.Inventory.SLOT_OFFHAND : i;
            var stack = inventory.getItem(slot);
            if (stack.isEmpty() || stack.has(DataComponents.GLIDER) != glider || !chestSlot.mayPlace(stack)
                || net.minecraft.world.item.enchantment.EnchantmentHelper.has(stack,
                    net.minecraft.world.item.enchantment.EnchantmentEffectComponents.PREVENT_ARMOR_CHANGE)) continue;
            double[] armor = new double[2];
            if (!glider) stack.forEachModifier(EquipmentSlot.CHEST, (attribute, modifier) -> {
                if (attribute.equals(net.minecraft.world.entity.ai.attributes.Attributes.ARMOR)) armor[0] += modifier.amount();
                else if (attribute.equals(net.minecraft.world.entity.ai.attributes.Attributes.ARMOR_TOUGHNESS)) armor[1] += modifier.amount();
            });
            candidates.add(new AutoWaspChestSwap.Candidate(slot, glider, remainingDurability(stack), armor[0], armor[1]));
        }
        int slot = AutoWaspChestSwap.best(candidates, glider);
        if (slot < 0) return;
        if (meteordevelopment.meteorclient.utils.player.SlotUtils.isHotbar(slot)
            || slot == net.minecraft.world.entity.player.Inventory.SLOT_OFFHAND) {
            mc.gameMode.handleContainerInput(mc.player.inventoryMenu.containerId, 6, slot,
                net.minecraft.world.inventory.ContainerInput.SWAP, mc.player);
        } else meteordevelopment.meteorclient.utils.player.InvUtils.move().from(slot).toArmor(2);
        if (glider) flightController.elytraSwapped();
        chestSwapCooldown = CHEST_SWAP_COOLDOWN_TICKS;
    }

    private boolean hasUsableElytra() {
        return mc.player != null && isUsableGlider(mc.player.getItemBySlot(EquipmentSlot.CHEST));
    }

    /** A glider on its last point of durability cannot glide. */
    private static boolean isUsableGlider(ItemStack stack) {
        return !stack.isEmpty() && stack.has(DataComponents.GLIDER) && remainingDurability(stack) > 1;
    }

    private static int remainingDurability(ItemStack stack) {
        return stack.isDamageableItem() ? stack.getMaxDamage() - stack.getDamageValue() : Integer.MAX_VALUE;
    }

    private boolean handleMissingTarget() {
        boolean lostTarget = target != null;
        clearTargetState();
        if (lostTarget && !keepSearching.get() && action.get() != Action.CHOOSE_NEW_TARGET) {
            handleTargetLoss();
            return false;
        }
        if (tryAcquireTarget(true)) {

            info("New target: " + target.getName().getString());
            return true;
        }

        if (keepSearching.get()) return false;

        handleTargetLoss();
        return false;
    }

    private boolean tryAcquireTarget(boolean useCooldown) {
        if (useCooldown && targetSearchTimer > 0) return false;

        findTarget();
        targetSearchTimer = TARGET_SEARCH_TICKS;
        return target != null;
    }

    private void clearTargetState() {
        target = null;
        targetTracker.reset();
        pathfinder.clearTargetState();
        flightController.clearStuckState();
    }

    private void resetNavigationState() {
        targetSearchTimer = 0;
        targetTracker.reset();
        pathfinder.resetNavigationState();
        flightController.resetNavigationState();
    }

    private void handleTargetLoss() {
        warning("Lost target!");

        switch (action.get()) {
            case CHOOSE_NEW_TARGET -> {
                findTarget();
                if (target == null) {
                    error("No valid targets found.");
                    toggle();
                } else {
                    info("New target: " + target.getName().getString());
                }
            }
            case TOGGLE -> toggle();
            case DISCONNECT -> {
                if (isActive()) toggle();
                if (mc.player != null && mc.player.connection != null) {
                    mc.player.connection.handleDisconnect(new ClientboundDisconnectPacket(
                        Component.literal("%s[%sAuto Wasp%s] Lost target.".formatted(ChatFormatting.GRAY, ChatFormatting.BLUE, ChatFormatting.GRAY))
                    ));
                }
            }
        }
    }

    public Minecraft client() {
        return mc;
    }

    public double horizontalSpeedValue() {
        return horizontalSpeed.get();
    }

    public double verticalSpeedValue() {
        return verticalSpeed.get();
    }

    public double rampStartSpeedValue() {
        return rampStartSpeed.get();
    }

    public double rampStepValue() {
        return rampStep.get();
    }

    public int setbackRewindTicksValue() {
        return setbackRewindTicks.get();
    }

    public int setbackHoldTicksValue() {
        return setbackHoldTicks.get();
    }

    public int pinnedGlideTicksValue() {
        return pinnedGlideTicks.get();
    }

    public double turnRateValue() {
        return turnRate.get();
    }

    public double brakingValue() {
        return braking.get();
    }

    public double maneuverMaxSpeedValue() {
        return maneuverMaxSpeed.get();
    }

    public boolean serverGlideGuardValue() {
        return serverGlideGuard.get();
    }

    public boolean steerServerGlideValue() {
        return steerServerGlide.get();
    }

    @EventHandler
    private void onGameLeft(meteordevelopment.meteorclient.events.game.GameLeftEvent event) {
        if (isActive()) toggle();
    }

    @Override
    public String getInfoString() {
        if (target == null) return "Searching";
        String state = flightController.state();
        String name = target.getName().getString();
        return state.equals("Pursuing") || state.equals("Waiting") ? name : name + " | " + state;
    }

    @Override
    public meteordevelopment.meteorclient.gui.widgets.WWidget getWidget(
            meteordevelopment.meteorclient.gui.GuiTheme theme) {
        var list = theme.verticalList();
        list.add(theme.label("Auto Wasp adapted from DEVILS"));
        list.add(theme.label("Credit: ThianYong-hub and DEVILS contributors"));
        list.add(theme.label("Based also on Meteor Client / Meteor Development"));
        list.add(theme.label("GNU GPL v3 (GPL-3.0-only) - no warranty"));
        list.add(theme.label("Source: github.com/ThianYong-hub/DEVILS"));
        list.add(theme.label("Upstream revision: 2440a168c3ba"));
        list.add(theme.label("Comet port: Boyonabike, 22 October 2026"));
        list.add(theme.label("Licence: LICENSE-DEVILS inside the Comet JAR"));
        list.add(theme.label("Modified source: accompanying Comet source ZIP"));
        return list;
    }

    public enum Action {
        TOGGLE("Toggle module"),
        CHOOSE_NEW_TARGET("Choose new target"),
        DISCONNECT("Disconnect");

        private final String title;

        Action(String title) {
            this.title = title;
        }

        @Override
        public String toString() {
            return title;
        }
    }
}
