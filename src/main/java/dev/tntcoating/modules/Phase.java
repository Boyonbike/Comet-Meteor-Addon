package dev.tntcoating.modules;

import dev.tntcoating.TntCoatingAddon;
import dev.tntcoating.core.InventorySlotLoan;
import dev.tntcoating.core.InventoryWeaponAttack;
import dev.tntcoating.mixin.GameModeInvoker;
import meteordevelopment.meteorclient.events.entity.player.PlayerMoveEvent;
import meteordevelopment.meteorclient.events.game.GameLeftEvent;
import meteordevelopment.meteorclient.events.packets.PacketEvent;
import meteordevelopment.meteorclient.events.world.CollisionShapeEvent;
import meteordevelopment.meteorclient.events.world.TickEvent;
import meteordevelopment.meteorclient.settings.*;
import meteordevelopment.meteorclient.systems.modules.Module;
import meteordevelopment.meteorclient.utils.player.InvUtils;
import meteordevelopment.meteorclient.utils.player.Rotations;
import meteordevelopment.meteorclient.utils.world.BlockUtils;
import meteordevelopment.orbit.EventHandler;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.protocol.game.*;
import net.minecraft.util.Mth;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.decoration.ItemFrame;
import net.minecraft.world.inventory.ContainerInput;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.ScaffoldingBlock;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.Shapes;

/** Bephax's five phase modes using Meteor rotations and Comet's inventory coordination. */
public final class Phase extends Module {
    public enum PhaseMode { Normal, Sand, Climb, Pearl, Clip }

    private final SettingGroup sgGeneral = settings.getDefaultGroup();
    private final SettingGroup sgPearl = settings.createGroup("Pearl");
    private final SettingGroup sgClipping = settings.createGroup("Clipping");
    private final Setting<PhaseMode> mode = sgGeneral.add(new EnumSetting.Builder<PhaseMode>()
        .name("mode").description("How to clip into or move through blocks.").defaultValue(PhaseMode.Pearl).build());
    private final Setting<Integer> pitch = sgPearl.add(new IntSetting.Builder().name("pitch")
        .description("Downward pearl throw angle.").defaultValue(83).range(70, 90).sliderRange(70, 90).build());
    private final Setting<Boolean> swapAlternative = sgPearl.add(new BoolSetting.Builder().name("swap-alternative")
        .description("Temporarily swap inventory pearls into the selected slot; otherwise use hotbar or offhand pearls.").defaultValue(true).build());
    private final Setting<Boolean> attack = sgPearl.add(new BoolSetting.Builder().name("attack")
        .description("Break item frames in the way and scaffolding at your feet before throwing.").defaultValue(false).build());
    private final Setting<Boolean> swing = sgPearl.add(new BoolSetting.Builder().name("swing")
        .description("Show the pearl throw hand swing.").defaultValue(true).build());
    private final Setting<Boolean> instantRotation = sgPearl.add(new BoolSetting.Builder().name("instant-rotation")
        .description("Aim immediately rather than turn toward the throw over several ticks.").defaultValue(false).build());
    private final Setting<Boolean> selfFill = sgPearl.add(new BoolSetting.Builder().name("self-fill")
        .description("Place a resistant hotbar block beside you before throwing.").defaultValue(false).build());
    private final Setting<Double> blocks = sgClipping.add(new DoubleSetting.Builder().name("blocks")
        .description("Initial forward clip distance for Normal mode.").defaultValue(0.003).range(0.001, 10).sliderMax(1).build());
    private final Setting<Double> distance = sgClipping.add(new DoubleSetting.Builder().name("distance")
        .description("Forward movement while sneaking inside a block in Normal mode.").defaultValue(0.2).range(0, 10).sliderMax(1).build());
    private final Setting<Boolean> autoClip = sgClipping.add(new BoolSetting.Builder().name("auto-clip")
        .description("Apply the initial Normal-mode clip on activation.").defaultValue(true).build());

    private LocalPlayer physicsPlayer;
    private boolean previousNoPhysics;
    private int session;
    private int pearlTicks;
    private float pearlYaw;
    private float aimYaw;
    private float aimPitch;
    private boolean prepared;
    private boolean inspectingCollision;
    private PhaseMode activeMode;

    public Phase() {
        super(TntCoatingAddon.CATEGORY, "phase", "Clip into blocks with pearls, corner clipping or collision movement.");
    }

    @Override public void onActivate() {
        session++;
        pearlTicks = 0;
        prepared = false;
        activeMode = mode.get();
        if (mc.player == null || mc.level == null) { toggle(); return; }
        if (activeMode == PhaseMode.Clip) {
            clipToCorner();
            toggle();
        } else if (activeMode == PhaseMode.Pearl) {
            if (!canThrow()) { error("Have a ready pearl and close your inventory before phasing."); toggle(); return; }
            double dx = Math.floor(mc.player.getX()) + 0.5 - mc.player.getX();
            double dz = Math.floor(mc.player.getZ()) + 0.5 - mc.player.getZ();
            pearlYaw = dx == 0 && dz == 0 ? mc.player.getYRot() : Mth.wrapDegrees((float) Math.toDegrees(Math.atan2(dz, dx)) + 90);
            aimYaw = mc.player.getYRot();
            aimPitch = mc.player.getXRot();
        } else if (activeMode == PhaseMode.Normal && autoClip.get()) {
            double angle = Math.toRadians(mc.player.getYRot() + 90);
            mc.player.setPos(mc.player.getX() + Math.cos(angle) * blocks.get(), mc.player.getY(), mc.player.getZ() + Math.sin(angle) * blocks.get());
        }
    }

    @Override public void onDeactivate() {
        session++; // Invalidate queued rotation callbacks, including a disable/re-enable in the same tick.
        restorePhysics();
    }

    @EventHandler private void onLeave(GameLeftEvent event) {
        if (isActive()) toggle();
    }

    @EventHandler(priority = 100) private void onTick(TickEvent.Pre event) {
        if (mc.player == null || mc.level == null) return;
        if (activeMode != mode.get()) {
            restorePhysics();
            // Reinitialize one-shot mode state when the setting changes while enabled.
            onActivate();
            if (!isActive()) return;
        }
        if (activeMode != PhaseMode.Sand) restorePhysics();
        if (activeMode != PhaseMode.Pearl) return;
        if (!canThrow()) { error("Pearl phase interrupted: pearl, inventory or player state changed."); toggle(); return; }
        if (!prepared) {
            prepared = true;
            if (attack.get()) clearObstructions();
            if (selfFill.get()) { fillBesidePlayer(); return; }
        }
        if (++pearlTicks > 8) { error("Could not complete the pearl throw."); toggle(); return; }
        aimYaw += Mth.clamp(Mth.wrapDegrees(pearlYaw - aimYaw), -45f, 45f);
        aimPitch += Mth.clamp(pitch.get() - aimPitch, -45f, 45f);
        if (instantRotation.get()) { aimYaw = pearlYaw; aimPitch = pitch.get(); }
        boolean aligned = Math.abs(Mth.wrapDegrees(pearlYaw - aimYaw)) < 0.1 && Math.abs(pitch.get() - aimPitch) < 0.1;
        int request = session;
        var player = mc.player;
        var level = mc.level;
        Rotations.rotate(aimYaw, aimPitch, 40, aligned ? () -> {
            if (isActive() && request == session && mode.get() == PhaseMode.Pearl && mc.player == player && mc.level == level) {
                if (canThrow()) throwPearl();
                toggle();
            }
        } : null);
    }

    private int pearlSlot() {
        if (mc.player.getOffhandItem().is(Items.ENDER_PEARL)) return 40;
        for (int slot = 0; slot < (swapAlternative.get() ? 36 : 9); slot++) {
            if (mc.player.getInventory().getItem(slot).is(Items.ENDER_PEARL)) return slot;
        }
        return -1;
    }

    private boolean canThrow() {
        return mc.player != null && mc.level != null && mc.gameMode != null && mc.getConnection() != null
            && mc.screen == null && mc.player.containerMenu == mc.player.inventoryMenu
            && mc.player.containerMenu.getCarried().isEmpty() && !mc.player.isUsingItem()
            && !mc.player.isPassenger() && !mc.player.isFallFlying() && !mc.player.isSwimming()
            && !InventoryWeaponAttack.INSTANCE.isPending() && !InventorySlotLoan.INSTANCE.isBorrowing()
            && pearlSlot() >= 0 && !mc.player.getCooldowns().isOnCooldown(Items.ENDER_PEARL.getDefaultInstance());
    }

    private void throwPearl() {
        int source = pearlSlot();
        int selected = mc.player.getInventory().getSelectedSlot();
        boolean inventorySwap = source >= 9 && source < 36;
        InteractionHand hand = source == 40 ? InteractionHand.OFF_HAND : InteractionHand.MAIN_HAND;
        if (inventorySwap) mc.gameMode.handleContainerInput(mc.player.inventoryMenu.containerId, source, selected, ContainerInput.SWAP, mc.player);
        else if (source < 9) InvUtils.swap(source, false);
        try {
            ((GameModeInvoker) mc.gameMode).comet$ensureHasSentCarriedItem();
            ((GameModeInvoker) mc.gameMode).comet$startPrediction(mc.level,
                sequence -> new ServerboundUseItemPacket(hand, sequence, pearlYaw, pitch.get().floatValue()));
            if (swing.get()) mc.player.swing(hand);
            else mc.getConnection().send(new ServerboundSwingPacket(hand));
        } finally {
            if (inventorySwap) mc.gameMode.handleContainerInput(mc.player.inventoryMenu.containerId, source, selected, ContainerInput.SWAP, mc.player);
            else if (source < 9) InvUtils.swap(selected, false);
        }
    }

    private void clearObstructions() {
        if (mc.player.pick(3, 0, false) instanceof BlockHitResult hit) {
            for (var entity : mc.level.getEntities(mc.player, new AABB(hit.getBlockPos()).inflate(0.2))) {
                if (entity instanceof ItemFrame) mc.gameMode.attack(mc.player, entity);
            }
        }
        BlockPos pos = mc.player.blockPosition();
        if (mc.level.getBlockState(pos).getBlock() instanceof ScaffoldingBlock) {
            mc.getConnection().send(new ServerboundPlayerActionPacket(ServerboundPlayerActionPacket.Action.START_DESTROY_BLOCK, pos, Direction.UP));
            mc.getConnection().send(new ServerboundPlayerActionPacket(ServerboundPlayerActionPacket.Action.STOP_DESTROY_BLOCK, pos, Direction.UP));
        }
    }

    private void fillBesidePlayer() {
        int octant = Math.floorMod(Math.round(pearlYaw / 45f), 8);
        int[] x = {0, -1, -1, -1, 0, 1, 1, 1};
        int[] z = {1, 1, 0, -1, -1, -1, 0, 1};
        BlockPos pos = mc.player.blockPosition().offset(x[octant], 0, z[octant]);
        if (mc.level.getBlockState(pos.below()).canBeReplaced()) return;
        var block = InvUtils.findInHotbar(Items.OBSIDIAN, Items.CRYING_OBSIDIAN, Items.ENDER_CHEST,
            Items.RESPAWN_ANCHOR, Items.ENCHANTING_TABLE, Items.ANVIL);
        if (!block.found()) return;
        int request = session;
        var player = mc.player;
        var level = mc.level;
        Rotations.rotate(Rotations.getYaw(Vec3.atCenterOf(pos)), Rotations.getPitch(Vec3.atCenterOf(pos)), 40, () -> {
            if (!isActive() || request != session || mode.get() != PhaseMode.Pearl || mc.player != player || mc.level != level || !canThrow()) return;
            var current = InvUtils.findInHotbar(Items.OBSIDIAN, Items.CRYING_OBSIDIAN, Items.ENDER_CHEST,
                Items.RESPAWN_ANCHOR, Items.ENCHANTING_TABLE, Items.ANVIL);
            if (current.found()) BlockUtils.place(pos, current, false, 40, swing.get(), true);
        });
    }

    private void clipToCorner() {
        if (!mc.player.onGround() || mc.player.isPassenger()) return;
        double x = Math.floor(mc.player.getX()) + 0.5;
        double z = Math.floor(mc.player.getZ()) + 0.5;
        mc.player.setPos(x + (x > mc.player.getX() ? -0.2 : 0.2), mc.player.getY(), z + (z > mc.player.getZ() ? -0.2 : 0.2));
    }

    @EventHandler private void onMove(PlayerMoveEvent event) {
        if (mc.player == null || mc.level == null) return;
        switch (mode.get()) {
            case Normal -> {
                if (mc.player.isShiftKeyDown() && insideBlock()) {
                    double angle = Math.toRadians(mc.player.getYRot() + 90);
                    event.movement = new Vec3(Math.cos(angle) * distance.get(), event.movement.y, Math.sin(angle) * distance.get());
                }
            }
            case Sand -> {
                if (physicsPlayer != mc.player) {
                    restorePhysics();
                    physicsPlayer = mc.player;
                    previousNoPhysics = mc.player.noPhysics;
                }
                mc.player.noPhysics = true;
                event.movement = new Vec3(event.movement.x, verticalMotion(), event.movement.z);
            }
            case Climb -> {
                if (mc.player.horizontalCollision && (mc.options.keyJump.isDown() || mc.options.keyShift.isDown()))
                    event.movement = new Vec3(event.movement.x, verticalMotion(), event.movement.z);
            }
            default -> {}
        }
    }

    private double verticalMotion() {
        return mc.options.keyJump.isDown() ? 0.3 : mc.options.keyShift.isDown() ? -0.3 : 0;
    }

    private boolean insideBlock() {
        inspectingCollision = true;
        try {
            AABB body = mc.player.getBoundingBox().deflate(0.001);
            return BlockPos.betweenClosedStream(body).anyMatch(pos ->
                mc.level.getBlockState(pos).getCollisionShape(mc.level, pos).toAabbs().stream()
                    .anyMatch(box -> box.move(pos).intersects(body)));
        } finally {
            inspectingCollision = false;
        }
    }

    @EventHandler private void onCollision(CollisionShapeEvent event) {
        if (inspectingCollision || mc.player == null || mc.level == null || event.shape.isEmpty()) return;
        boolean clear = switch (mode.get()) {
            case Normal -> mc.player.isShiftKeyDown() && event.pos.getY() + event.shape.bounds().maxY > mc.player.getBoundingBox().minY;
            case Sand -> true;
            case Climb -> mc.player.horizontalCollision || mc.options.keyShift.isDown()
                || mc.options.keyJump.isDown() && event.pos.getY() > mc.player.getY();
            default -> false;
        };
        if (clear) { event.shape = Shapes.empty(); event.cancel(); }
    }

    @EventHandler private void onVelocity(PacketEvent.Receive event) {
        if (mc.player != null && suppressesPushOut() && event.packet instanceof ClientboundSetEntityMotionPacket packet
            && packet.id() == mc.player.getId() && packet.movement().lengthSqr() < 0.1) event.cancel();
    }

    public boolean suppressesPushOut() {
        return isActive() && (mode.get() == PhaseMode.Normal || mode.get() == PhaseMode.Sand || mode.get() == PhaseMode.Climb);
    }

    private void restorePhysics() {
        if (physicsPlayer != null) physicsPlayer.noPhysics = previousNoPhysics;
        physicsPlayer = null;
    }

    @Override public String getInfoString() { return mode.get().toString(); }
}
