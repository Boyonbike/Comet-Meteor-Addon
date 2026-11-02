/*
 * Original Comet implementation by Boyonabike.
 * Independent: written from the pinned 26.2 artifact and Meteor build 35 behaviour; no third-party code.
 * SPDX-License-Identifier: CC0-1.0
 */

package dev.tntcoating.core.combat.mc;

import dev.tntcoating.core.combat.Box;
import dev.tntcoating.core.combat.Cell;
import dev.tntcoating.core.combat.ConnectionKey;
import dev.tntcoating.core.combat.CombatSession;
import dev.tntcoating.core.combat.EntityKey;
import dev.tntcoating.core.combat.PistonFacing;
import dev.tntcoating.core.combat.PistonGeometry;
import dev.tntcoating.core.combat.Snapshot;
import dev.tntcoating.core.combat.Vec;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import meteordevelopment.meteorclient.utils.player.Rotations;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.boss.enderdragon.EndCrystal;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.core.Direction;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;

/**
 * Production {@link CombatClient}. Read-only: it never sends packets, never changes the world and never
 * mutates Meteor settings. Rotation requests are the only outward effect, and only dispatch calls them.
 */
public final class MinecraftCombatClient implements CombatClient {
    private static final String END_CRYSTAL_ID = "minecraft:end_crystal";

    private final Minecraft mc;
    private final CombatSession session;

    public MinecraftCombatClient(Minecraft mc, CombatSession session) {
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
    public Level level() {
        return mc.level;
    }

    @Override
    public EntityKey selfKey() {
        if (mc.player == null) return null;
        return new EntityKey(session.connection(), mc.player.getId(), mc.player.getUUID());
    }

    @Override
    public EntityKey entity(int id) {
        if (mc.level == null) return null;
        Entity entity = mc.level.getEntity(id);
        if (entity == null) return null;
        return new EntityKey(session.connection(), entity.getId(), entity.getUUID());
    }

    @Override
    public String entityType(EntityKey entity) {
        Entity current = current(entity);
        if (current == null) return "";
        Identifier id = BuiltInRegistries.ENTITY_TYPE.getKey(current.getType());
        return id == null ? "" : id.toString();
    }

    @Override
    public EntityKey crystalAt(Vec position, double tolerance) {
        if (mc.level == null || position == null || !(tolerance >= 0.0)) return null;
        EntityKey found = null;
        for (Entity entity : mc.level.entitiesForRendering()) {
            if (!(entity instanceof EndCrystal) || !entity.isAlive() || entity.isRemoved()) continue;
            Vec at = vec(entity.position());
            if (at.distance(position) > tolerance) continue;
            // Two crystals within tolerance would make attribution ambiguous; the budget requires one.
            if (found != null) return null;
            found = key(entity);
        }
        return found;
    }

    @Override
    public String dimensionId() {
        if (mc.level == null) return "";
        return mc.level.dimension().identifier().toString();
    }

    @Override
    public List<Snapshot.Crystal> crystals() {
        if (mc.level == null) return List.of();
        List<Snapshot.Crystal> crystals = new ArrayList<>();
        for (Entity entity : mc.level.entitiesForRendering()) {
            if (entity instanceof EndCrystal && entity.isAlive() && !entity.isRemoved()) {
                crystals.add(new Snapshot.Crystal(key(entity), vec(entity.position())));
            }
        }
        return List.copyOf(crystals);
    }

    @Override
    public Vec eyePosition() {
        if (mc.player == null) return null;
        return vec(mc.player.getEyePosition());
    }

    @Override
    public Vec entityPosition(EntityKey entity) {
        Entity current = current(entity);
        return current == null ? null : vec(current.position());
    }

    @Override
    public Box entityBox(EntityKey entity) {
        Entity current = current(entity);
        return current == null ? null : box(current.getBoundingBox());
    }

    @Override
    public boolean entityPresent(EntityKey entity) {
        Entity current = current(entity);
        return current != null
            && current.getUUID().equals(entity.uuid())
            && current.isAlive()
            && !current.isRemoved();
    }

    @Override
    public double blockInteractionRange() {
        return mc.player == null ? 0.0 : mc.player.blockInteractionRange();
    }

    @Override
    public boolean supportBelow(Cell base) {
        return supportBelow(mc.level, base);
    }

    static boolean supportBelow(net.minecraft.world.level.BlockGetter world, Cell base) {
        if (world == null || base == null) return false;
        BlockPos pos = new BlockPos(base.x(), base.y(), base.z());
        BlockState state = world.getBlockState(pos);
        return state.isFaceSturdy(world, pos, Direction.UP);
    }

    @Override
    public String blockId(Cell cell) {
        if (mc.level == null || cell == null) return null;
        BlockState state = mc.level.getBlockState(new BlockPos(cell.x(), cell.y(), cell.z()));
        if (state.isAir()) return null;
        Identifier id = BuiltInRegistries.BLOCK.getKey(state.getBlock());
        return id == null ? null : id.toString();
    }

    @Override
    public boolean replaceable(Cell cell) {
        if (mc.level == null || cell == null) return false;
        return mc.level.getBlockState(new BlockPos(cell.x(), cell.y(), cell.z())).canBeReplaced();
    }

    @Override
    public boolean entityFree(Cell cell) {
        if (mc.level == null || cell == null) return false;
        BlockPos pos = new BlockPos(cell.x(), cell.y(), cell.z());
        return mc.level.getEntities((Entity) null, new AABB(pos).inflate(0.0),
            entity -> entity.isAlive() && !entity.isSpectator()).isEmpty();
    }

    @Override
    public PistonFacing pistonFacing(Cell cell) {
        if (mc.level == null || cell == null) return null;
        BlockState state = mc.level.getBlockState(new BlockPos(cell.x(), cell.y(), cell.z()));
        if (!(state.getBlock() instanceof net.minecraft.world.level.block.piston.PistonBaseBlock)) {
            return null;
        }
        Direction direction = state.getValue(net.minecraft.world.level.block.piston.PistonBaseBlock.FACING);
        for (PistonFacing facing : PistonGeometry.baseLayouts().stream()
            .map(PistonGeometry.Layout::facing).toList()) {
            if (facing.dx() == direction.getStepX() && facing.dy() == direction.getStepY()
                && facing.dz() == direction.getStepZ()) return facing;
        }
        return null;
    }

    @Override
    public boolean pistonPowered(Cell cell) {
        if (mc.level == null || cell == null) return false;
        BlockState state = mc.level.getBlockState(new BlockPos(cell.x(), cell.y(), cell.z()));
        return state.getBlock() instanceof net.minecraft.world.level.block.piston.PistonBaseBlock
            && state.getValue(net.minecraft.world.level.block.piston.PistonBaseBlock.EXTENDED);
    }

    @Override
    public double entityInteractionRange() {
        return mc.player == null ? 0.0 : mc.player.entityInteractionRange();
    }

    @Override
    public boolean visible(Vec eye, Box target) {
        if (mc.player == null || mc.level == null || eye == null || target == null) return false;
        Vec3 from = vec3(eye);
        Vec3 to = vec3(target.center());
        BlockHitResult hit = mc.level.clip(new ClipContext(from, to,
            ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, mc.player));
        return hit.getType() == HitResult.Type.MISS
            || hit.getLocation().distanceToSqr(to) <= 0.01;
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
        if (mc.player == null) return false;
        double yawError = Math.abs(wrapDegrees(mc.player.getYRot() - targetYaw));
        double pitchError = Math.abs(mc.player.getXRot() - targetPitch);
        return yawError <= 1.0 && pitchError <= 1.0;
    }

    @Override
    public boolean requestRotation(double targetYaw, double targetPitch, Runnable afterRotate) {
        if (mc.player == null) return false;
        Rotations.rotate(targetYaw, targetPitch, 50, afterRotate == null ? () -> { } : afterRotate);
        return true;
    }

    @Override
    public boolean screenOpen() {
        return mc.gui.screen() != null;
    }

    @Override
    public int selectedSlot() {
        return mc.player == null ? -1 : mc.player.getInventory().getSelectedSlot();
    }

    @Override
    public String hotbarItem(int slot) {
        if (mc.player == null || slot < 0 || slot > 8) return "";
        ItemStack stack = mc.player.getInventory().getItem(slot);
        if (stack == null || stack.isEmpty()) return "";
        Identifier id = BuiltInRegistries.ITEM.getKey(stack.getItem());
        return id == null ? "" : id.toString();
    }

    @Override
    public String offhandItem() {
        if (mc.player == null) return "";
        ItemStack stack = mc.player.getOffhandItem();
        if (stack == null || stack.isEmpty()) return "";
        Identifier id = BuiltInRegistries.ITEM.getKey(stack.getItem());
        return id == null ? "" : id.toString();
    }

    @Override
    public void selectHotbarSlot(int slot) {
        if (mc.player != null) mc.player.getInventory().setSelectedSlot(slot);
    }

    @Override
    public ConnectionKey connection() {
        return session.connection();
    }

    /** Registry id for a raw entity type, used by the intake's entity-event payloads. */
    static String entityTypeId(EntityType<?> type) {
        if (type == null) return "";
        Identifier id = BuiltInRegistries.ENTITY_TYPE.getKey(type);
        return id == null ? "" : id.toString();
    }

    private Entity current(EntityKey key) {
        if (key == null || mc.level == null) return null;
        Entity entity = mc.level.getEntity(key.id());
        if (entity == null) return null;
        return entity.getUUID().equals(key.uuid()) ? entity : null;
    }

    private EntityKey key(Entity entity) {
        return new EntityKey(session.connection(), entity.getId(), entity.getUUID());
    }

    private static Vec vec(Vec3 value) {
        return new Vec(value.x, value.y, value.z);
    }

    private static Vec3 vec3(Vec value) {
        return new Vec3(value.x(), value.y(), value.z());
    }

    private static Box box(AABB value) {
        return new Box(new Vec(value.minX, value.minY, value.minZ),
            new Vec(value.maxX, value.maxY, value.maxZ));
    }

    private static double wrapDegrees(double degrees) {
        double wrapped = degrees % 360.0;
        if (wrapped >= 180.0) wrapped -= 360.0;
        if (wrapped < -180.0) wrapped += 360.0;
        return wrapped;
    }

    /** End-crystal registry id used by dispatch's verified-entity check. */
    public static String endCrystalId() {
        return END_CRYSTAL_ID;
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
