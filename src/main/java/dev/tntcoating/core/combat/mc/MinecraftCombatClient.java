/*
 * Original Comet implementation by Boyonabike.
 * Independent: written from the pinned 26.1.2 artifact and Meteor build 42 behaviour; no third-party code.
 * SPDX-License-Identifier: CC0-1.0
 */

package dev.tntcoating.core.combat.mc;

import dev.tntcoating.core.combat.Box;
import dev.tntcoating.core.combat.Cell;
import dev.tntcoating.core.combat.ConnectionKey;
import dev.tntcoating.core.combat.EntityKey;
import dev.tntcoating.core.combat.PistonFacing;
import dev.tntcoating.core.combat.PistonGeometry;
import dev.tntcoating.core.combat.Snapshot;
import dev.tntcoating.core.combat.Vec;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import meteordevelopment.meteorclient.MeteorClient;
import meteordevelopment.meteorclient.utils.player.Rotations;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.boss.enderdragon.EndCrystal;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.piston.PistonBaseBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.material.Fluid;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.CollisionContext;

/**
 * Production {@link CombatClient}: a read-only view over the live client world, player and connection.
 *
 * <p>Every method is a pure read or a rotation request. Nothing here sends a packet, mutates a Meteor
 * setting, or reads a predicted entity id: {@link #crystalAt} only returns a crystal that the client world
 * already contains.</p>
 */
public final class MinecraftCombatClient implements CombatClient {
    private static final String END_CRYSTAL_ID = "minecraft:end_crystal";

    private final Minecraft mc;
    private final CombatSessionView session;

    /** The small session surface this client needs; keeps the adapter testable without a live session. */
    public interface CombatSessionView {
        ConnectionKey connection();
    }

    public MinecraftCombatClient(Minecraft mc, CombatSessionView session) {
        this.mc = Objects.requireNonNull(mc, "mc");
        this.session = Objects.requireNonNull(session, "session");
    }

    @Override
    public boolean onClientThread() {
        return mc.isSameThread();
    }

    @Override
    public boolean connectionOpen() {
        return mc.getConnection() != null && mc.getConnection().getConnection().isConnected();
    }

    @Override
    public net.minecraft.world.level.Level level() {
        return mc.level;
    }

    @Override
    public EntityKey selfKey() {
        LocalPlayer player = mc.player;
        if (player == null) return null;
        return new EntityKey(session.connection(), player.getId(), player.getUUID());
    }

    @Override
    public EntityKey entity(int id) {
        if (mc.level == null || id < 0) return null;
        Entity entity = mc.level.getEntity(id);
        if (entity == null || entity.isRemoved()) return null;
        return new EntityKey(session.connection(), entity.getId(), entity.getUUID());
    }

    @Override
    public String entityType(EntityKey key) {
        Entity entity = current(key);
        return entity == null ? "" : entityTypeId(entity.getType());
    }

    @Override
    public EntityKey crystalAt(Vec position, double tolerance) {
        if (position == null || mc.level == null || !(tolerance >= 0.0)) return null;
        double limit = tolerance * tolerance;
        EntityKey found = null;
        for (Entity entity : mc.level.entitiesForRendering()) {
            if (!(entity instanceof EndCrystal crystal) || crystal.isRemoved() || !crystal.isAlive()) {
                continue;
            }
            Vec crystalPosition = toVec(crystal.position());
            if (crystalPosition.distanceSquared(position) > limit) continue;
            if (found != null) return null;
            found = new EntityKey(session.connection(), crystal.getId(), crystal.getUUID());
        }
        return found;
    }

    @Override
    public String dimensionId() {
        return mc.level == null ? "" : mc.level.dimension().identifier().toString();
    }

    @Override
    public List<Snapshot.Crystal> crystals() {
        if (mc.level == null) return List.of();
        List<Snapshot.Crystal> crystals = new ArrayList<>();
        for (Entity entity : mc.level.entitiesForRendering()) {
            if (!(entity instanceof EndCrystal crystal) || crystal.isRemoved() || !crystal.isAlive()) {
                continue;
            }
            crystals.add(new Snapshot.Crystal(
                new EntityKey(session.connection(), crystal.getId(), crystal.getUUID()),
                toVec(crystal.position())));
        }
        return List.copyOf(crystals);
    }

    @Override
    public Vec eyePosition() {
        LocalPlayer player = mc.player;
        return player == null ? null : toVec(player.getEyePosition());
    }

    @Override
    public Vec entityPosition(EntityKey key) {
        Entity entity = current(key);
        return entity == null ? null : toVec(entity.position());
    }

    @Override
    public Box entityBox(EntityKey key) {
        Entity entity = current(key);
        return entity == null ? null : toBox(entity.getBoundingBox());
    }

    @Override
    public boolean entityPresent(EntityKey key) {
        return current(key) != null;
    }

    @Override
    public double blockInteractionRange() {
        return mc.player == null ? 0.0 : mc.player.blockInteractionRange();
    }

    @Override
    public boolean supportBelow(Cell cell) {
        return supportBelow(mc.level, cell);
    }

    static boolean supportBelow(net.minecraft.world.level.BlockGetter world, Cell cell) {
        if (cell == null || world == null) return false;
        BlockPos below = blockPos(cell);
        return world.getBlockState(below).isFaceSturdy(world, below, Direction.UP);
    }

    @Override
    public String blockId(Cell cell) {
        if (cell == null || mc.level == null) return null;
        BlockState state = mc.level.getBlockState(blockPos(cell));
        if (state.isAir()) return null;
        Identifier id = BuiltInRegistries.BLOCK.getKey(state.getBlock());
        return id == null ? "" : id.toString();
    }

    @Override
    public boolean replaceable(Cell cell) {
        return cell != null && mc.level != null && mc.level.getBlockState(blockPos(cell)).canBeReplaced();
    }

    @Override
    public boolean entityFree(Cell cell) {
        if (cell == null || mc.level == null) return false;
        AABB box = new AABB(cell.x(), cell.y(), cell.z(), cell.x() + 1.0, cell.y() + 1.0, cell.z() + 1.0);
        return mc.level.getEntities((Entity) null, box,
            entity -> entity.isAlive() && !entity.isSpectator()).isEmpty();
    }

    @Override
    public PistonFacing pistonFacing(Cell cell) {
        if (cell == null || mc.level == null) return null;
        BlockState state = mc.level.getBlockState(blockPos(cell));
        if (!(state.getBlock() instanceof PistonBaseBlock) || !state.hasProperty(PistonBaseBlock.FACING)) {
            return null;
        }
        Direction direction = state.getValue(PistonBaseBlock.FACING);
        for (PistonGeometry.Layout layout : PistonGeometry.baseLayouts()) {
            PistonFacing facing = layout.facing();
            if (facing.dx() == direction.getStepX() && facing.dy() == direction.getStepY()
                && facing.dz() == direction.getStepZ()) {
                return facing;
            }
        }
        return null;
    }

    @Override
    public boolean pistonPowered(Cell cell) {
        if (cell == null || mc.level == null) return false;
        BlockState state = mc.level.getBlockState(blockPos(cell));
        return state.getBlock() instanceof PistonBaseBlock && state.hasProperty(PistonBaseBlock.EXTENDED)
            && state.getValue(PistonBaseBlock.EXTENDED);
    }

    @Override
    public double entityInteractionRange() {
        return mc.player == null ? 0.0 : mc.player.entityInteractionRange();
    }

    @Override
    public boolean visible(Vec eye, Box target) {
        if (eye == null || target == null || mc.level == null) return false;
        Vec3 from = toVec3(eye);
        Vec3 to = toVec3(target.center());
        BlockHitResult hit = mc.level.clip(new ClipContext(from, to, ClipContext.Block.COLLIDER,
            ClipContext.Fluid.NONE, CollisionContext.empty()));
        return hit.getType() == HitResult.Type.MISS || hit.getLocation().distanceToSqr(to) <= 0.01;
    }

    @Override
    public double yaw() {
        return mc.player == null ? 0.0 : mc.player.getYRot();
    }

    @Override
    public double pitch() {
        return mc.player == null ? 0.0 : mc.player.getXRot();
    }

    @Override
    public boolean rotationSatisfied(double targetYaw, double targetPitch) {
        return Math.abs(Mth.wrapDegrees(yaw() - targetYaw)) <= 1.0
            && Math.abs(Mth.wrapDegrees(pitch() - targetPitch)) <= 1.0;
    }

    @Override
    public boolean requestRotation(double targetYaw, double targetPitch, Runnable afterRotate) {
        Rotations.rotate(targetYaw, targetPitch, 50, afterRotate == null ? () -> { } : afterRotate);
        return true;
    }

    @Override
    public boolean screenOpen() {
        return mc.screen != null;
    }

    @Override
    public int selectedSlot() {
        return mc.player == null ? -1 : mc.player.getInventory().getSelectedSlot();
    }

    @Override
    public String hotbarItem(int slot) {
        if (mc.player == null || slot < 0 || slot > 8) return "";
        return itemId(mc.player.getInventory().getItem(slot));
    }

    @Override
    public String offhandItem() {
        return mc.player == null ? "" : itemId(mc.player.getOffhandItem());
    }

    @Override
    public void selectHotbarSlot(int slot) {
        if (mc.player == null || slot < 0 || slot > 8) return;
        mc.player.getInventory().setSelectedSlot(slot);
    }

    @Override
    public ConnectionKey connection() {
        return session.connection();
    }

    // ------------------------------------------------------------------ helpers

    private Entity current(EntityKey key) {
        if (key == null || mc.level == null) return null;
        Entity entity = mc.level.getEntity(key.id());
        if (entity == null || entity.isRemoved() || !entity.getUUID().equals(key.uuid())) return null;
        return entity;
    }

    private static String itemId(ItemStack stack) {
        if (stack == null || stack.isEmpty()) return "";
        Identifier id = BuiltInRegistries.ITEM.getKey(stack.getItem());
        return id == null ? "" : id.toString();
    }

    /** T1 registry identity; no EntityTypes helper exists. */
    public static String entityTypeId(net.minecraft.world.entity.EntityType<?> type) {
        Identifier id = BuiltInRegistries.ENTITY_TYPE.getKey(type);
        return id == null ? "" : id.toString();
    }

    public static String endCrystalId() {
        return END_CRYSTAL_ID;
    }

    static BlockPos blockPos(Cell cell) {
        return new BlockPos(cell.x(), cell.y(), cell.z());
    }

    static Vec toVec(Vec3 vec) {
        return new Vec(vec.x(), vec.y(), vec.z());
    }

    static Vec3 toVec3(Vec vec) {
        return new Vec3(vec.x(), vec.y(), vec.z());
    }

    static Box toBox(AABB box) {
        return new Box(new Vec(box.minX, box.minY, box.minZ), new Vec(box.maxX, box.maxY, box.maxZ));
    }

    static AABB toAabb(Box box) {
        return new AABB(box.min().x(), box.min().y(), box.min().z(),
            box.max().x(), box.max().y(), box.max().z());
    }

    @Override
    public HealthPair appliedHealth(int entityId) {
        if (mc.level == null) return null;
        if (!(mc.level.getEntity(entityId) instanceof LivingEntity living)) return null;
        return new HealthPair(living.getHealth(), living.getAbsorptionAmount());
    }

    @Override
    public HealthFields healthFields(int entityId) {
        if (mc.level == null) return HealthFields.NONE;
        Entity entity = mc.level.getEntity(entityId);
        if (entity instanceof Player) return HealthFields.HEALTH_AND_ABSORPTION;
        if (entity instanceof LivingEntity) return HealthFields.HEALTH;
        return HealthFields.NONE;
    }
}
