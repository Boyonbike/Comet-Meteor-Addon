/*
 * Adapted from DEVILS by ThianYong-hub and DEVILS contributors.
 * Upstream: https://github.com/ThianYong-hub/DEVILS
 * Revision: 2440a168c3ba6f051f2adf8249a8e6fb5f3a5b02
 * Auto Wasp is also based on Meteor Client, Copyright (c) Meteor Development.
 * SPDX-License-Identifier: GPL-3.0-only
 *
 * Modified for Comet by Boyonabike on 2026-10-22: Minecraft 26.1.2 mappings,
 * local inventory integration, lifecycle/navigation guards and settings credits.
 * Distributed under GNU GPL v3; see LICENSE-DEVILS and THIRD_PARTY_NOTICES.md.
 * This program is distributed WITHOUT ANY WARRANTY; without even the implied
 * warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
 */

package dev.tntcoating.modules;

import dev.tntcoating.TntCoatingAddon;
import dev.tntcoating.modules.autowasp.AutoWaspFlightController;
import dev.tntcoating.modules.autowasp.AutoWaspPathfinder;
import meteordevelopment.meteorclient.events.entity.player.PlayerMoveEvent;
import meteordevelopment.meteorclient.events.world.TickEvent;
import meteordevelopment.meteorclient.settings.BoolSetting;
import meteordevelopment.meteorclient.settings.DoubleSetting;
import meteordevelopment.meteorclient.settings.EnumSetting;
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

    private final Setting<Double> horizontalSpeed = sgGeneral.add(new DoubleSetting.Builder()
        .name("horizontal-speed")
        .description("Horizontal elytra speed.")
        .defaultValue(2.0)
        .min(0.14)
        .build()
    );

    private final Setting<Double> verticalSpeed = sgGeneral.add(new DoubleSetting.Builder()
        .name("vertical-speed")
        .description("Vertical elytra speed.")
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
        .description("Tries to predict the targets position according to their movement.")
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

    private final AutoWaspPathfinder pathfinder = new AutoWaspPathfinder(this);
    private final AutoWaspFlightController flightController = new AutoWaspFlightController(this, pathfinder);

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
        flightController.onDeactivate();
        pathfinder.onDeactivate();
    }

    @EventHandler
    private void onTick(TickEvent.Pre event) {
        onTickSafe();
    }

    @EventHandler
    private void onMove(PlayerMoveEvent event) {
        onMoveSafe(event);
    }

    private void onTickSafe() {
        if (mc.player == null || mc.level == null || !mc.player.isAlive() || mc.player.isSpectator()) return;
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

        boolean shouldUseElytra = tickAutoChestSwap();
        if (!hasEquippedElytra()) {
            if (!shouldUseElytra) {
                waitingForElytra = false;
                flightController.resetFlightTracking();
                return;
            }
            if (!waitingForElytra) {
                warning("No elytra equipped, waiting for swap...");
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
        if (!hasEquippedElytra() || !mc.player.isFallFlying()) return;

        Vec3 targetPos = getTargetPos();
        flightController.onMove(event, pathfinder.steerTarget(targetPos));
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

        Vec3 targetPos = target.position().add(offset.get().x, offset.get().y, offset.get().z);
        if (predictMovement.get()) targetPos = targetPos.add(target.getDeltaMovement());
        if (avoidLanding.get()) targetPos = liftTargetIfOnGround(targetPos);
        return pathfinder.adjustToSafeCorridor(targetPos, mc.player.position());
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

        double horizontalDistance = Math.hypot(mc.player.getX() - target.getX(), mc.player.getZ() - target.getZ());
        if (horizontalDistance > chestSwapHorizontalRange.get()) return false;

        double verticalDistance = Math.abs(mc.player.getY() - target.getY());
        if (verticalDistance > chestSwapVerticalRange.get()) return false;

        int scanDepth = Math.max(3, Mth.ceil(chestSwapGroundDistance.get()) + 2);
        double groundDistance = pathfinder.distanceToSolidBelow(mc.player.position(), scanDepth);
        return groundDistance <= chestSwapGroundDistance.get();
    }

    private void requestChestGear(boolean glider) {
        if (mc.player == null || mc.gameMode == null || chestSwapCooldown > 0
            || mc.player.containerMenu != mc.player.inventoryMenu
            || !mc.player.inventoryMenu.getCarried().isEmpty()
            || dev.tntcoating.core.InventorySlotLoan.INSTANCE.isBorrowing()
            || dev.tntcoating.core.InventoryWeaponAttack.INSTANCE.isPending()) return;
        var worn = mc.player.getItemBySlot(EquipmentSlot.CHEST);
        if (!worn.isEmpty() && hasEquippedElytra() == glider) return;
        var chestSlot = mc.player.inventoryMenu.getSlot(6);
        if (!chestSlot.mayPickup(mc.player)) return;
        var candidates = new java.util.ArrayList<dev.tntcoating.core.SmartElytraSwap.Candidate>();
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
            int remaining = stack.isDamageableItem() ? stack.getMaxDamage() - stack.getDamageValue() : Integer.MAX_VALUE;
            candidates.add(new dev.tntcoating.core.SmartElytraSwap.Candidate(slot, glider, remaining, armor[0], armor[1]));
        }
        int slot = dev.tntcoating.core.SmartElytraSwap.best(candidates, glider);
        if (slot < 0) return;
        if (meteordevelopment.meteorclient.utils.player.SlotUtils.isHotbar(slot)
            || slot == net.minecraft.world.entity.player.Inventory.SLOT_OFFHAND) {
            mc.gameMode.handleContainerInput(mc.player.inventoryMenu.containerId, 6, slot,
                net.minecraft.world.inventory.ContainerInput.SWAP, mc.player);
        } else meteordevelopment.meteorclient.utils.player.InvUtils.move().from(slot).toArmor(2);
        chestSwapCooldown = CHEST_SWAP_COOLDOWN_TICKS;
    }

    private boolean hasEquippedElytra() {
        return mc.player != null && mc.player.getItemBySlot(EquipmentSlot.CHEST).has(DataComponents.GLIDER);
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
        pathfinder.clearTargetState();
        flightController.clearStuckState();
    }

    private void resetNavigationState() {
        targetSearchTimer = 0;
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

    @EventHandler
    private void onGameLeft(meteordevelopment.meteorclient.events.game.GameLeftEvent event) {
        if (isActive()) toggle();
    }

    @Override
    public String getInfoString() {
        return target == null ? "Searching" : target.getName().getString();
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
