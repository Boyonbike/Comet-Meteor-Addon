package dev.tntcoating.mixin;

import dev.tntcoating.core.TrajectoryFrameBudget;
import dev.tntcoating.core.TrajectoryPathAccess;
import dev.tntcoating.core.TrajectoryCachedPaths;
import dev.tntcoating.core.TrajectoryCollisionRevision;
import dev.tntcoating.core.TrajectoryFiredInput;
import dev.tntcoating.core.TrajectoryHeldInput;
import dev.tntcoating.core.TrajectorySimulationContext;
import meteordevelopment.meteorclient.events.entity.EntityAddedEvent;
import meteordevelopment.meteorclient.events.entity.EntityRemovedEvent;
import meteordevelopment.meteorclient.events.game.GameLeftEvent;
import meteordevelopment.meteorclient.events.render.Render3DEvent;
import meteordevelopment.meteorclient.events.world.BlockUpdateEvent;
import meteordevelopment.meteorclient.events.world.ChunkDataEvent;
import meteordevelopment.meteorclient.events.world.TickEvent;
import meteordevelopment.meteorclient.mixin.ProjectileInGroundAccessor;
import meteordevelopment.meteorclient.settings.*;
import meteordevelopment.meteorclient.systems.modules.Categories;
import meteordevelopment.meteorclient.systems.modules.Module;
import meteordevelopment.meteorclient.systems.modules.Modules;
import meteordevelopment.meteorclient.systems.modules.movement.NoSlow;
import meteordevelopment.meteorclient.systems.modules.movement.Sneak;
import meteordevelopment.meteorclient.systems.modules.render.Trajectories;
import meteordevelopment.meteorclient.utils.Utils;
import meteordevelopment.meteorclient.utils.entity.simulator.ProjectileEntitySimulator;
import meteordevelopment.meteorclient.utils.entity.simulator.SimulationStep;
import meteordevelopment.meteorclient.utils.player.Rotations;
import meteordevelopment.orbit.EventHandler;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.Pose;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.entity.projectile.Projectile;
import net.minecraft.world.entity.projectile.arrow.ThrownTrident;
import net.minecraft.world.entity.projectile.hurtingprojectile.WitherSkull;
import net.minecraft.world.item.CrossbowItem;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.enchantment.Enchantments;
import net.minecraft.world.level.border.WorldBorder;
import net.minecraft.world.phys.Vec3;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Mutable;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;

/** Reuse Meteor's paths and exact simulator, with bounded work and same-tick prediction reuse. */
@Mixin(value = Trajectories.class, remap = false)
public abstract class TrajectoriesMixin extends Module implements TrajectorySimulationContext {
    @Shadow @Final private Setting<List<Item>> items;
    @Shadow @Final private Setting<Boolean> otherPlayers;
    @Shadow @Final private Setting<Boolean> firedProjectiles;
    @Shadow @Final private Setting<Boolean> ignoreWitherSkulls;
    @Shadow @Final private Setting<Boolean> accurate;
    @Shadow @Final public Setting<Integer> simulationSteps;
    @Shadow @Final private Setting<Integer> ignoreFirstTicks;
    @Shadow @Final @Mutable private List<Object> paths;

    @Shadow private void calculatePath(Player player, float tickDelta) { throw new AssertionError(); }
    @Shadow private void calculateFiredPath(Entity entity, double tickDelta) { throw new AssertionError(); }

    @Unique private Setting<Boolean> comet$optimize;
    @Unique private Setting<Double> comet$range;
    @Unique private Setting<Integer> comet$frameSteps;
    @Unique private final TrajectoryFrameBudget comet$budget = new TrajectoryFrameBudget();
    @Unique private final Map<Entity, TrajectoryCachedPaths> comet$cache = new IdentityHashMap<>();
    @Unique private final List<Entity> comet$remote = new ArrayList<>();
    @Unique private List<Object> comet$stockPaths;
    @Unique private Object comet$world;
    @Unique private long comet$frame;
    @Unique private boolean comet$calculating;
    @Unique private double comet$borderMinX = Double.NaN, comet$borderMaxX, comet$borderMinZ, comet$borderMaxZ;

    protected TrajectoriesMixin() { super(Categories.Render, "trajectories", ""); }

    @Inject(method = "<init>", at = @At("RETURN"))
    private void comet$settings(CallbackInfo ci) {
        comet$stockPaths = paths;
        SettingGroup group = settings.createGroup("Comet", false);
        comet$optimize = group.add(new BoolSetting.Builder()
            .name("optimize-trajectories")
            .description("Reuses unchanged predictions within a tick and bounds simulation work per frame.")
            .defaultValue(true).build());
        comet$range = group.add(new DoubleSetting.Builder()
            .name("remote-trajectory-range")
            .description("Maximum distance for other players and fired projectiles. Your held item is always included.")
            .defaultValue(128).min(8).max(512).sliderRange(16, 256)
            .visible(comet$optimize::get).build());
        comet$frameSteps = group.add(new IntSetting.Builder()
            .name("trajectory-steps-per-frame")
            .description("Total simulation steps across all paths. Busy scenes show shorter paths; also bounds unlimited simulation-steps.")
            .defaultValue(8000).min(128).max(64000).sliderRange(1000, 32000)
            .visible(comet$optimize::get).build());
    }

    @Override public void onActivate() { comet$clearPredictions(); }
    @Override public void onDeactivate() { comet$clearPredictions(); }

    @EventHandler private void comet$onTick(TickEvent.Pre event) { TrajectoryCollisionRevision.invalidate(); }
    @EventHandler private void comet$onBlock(BlockUpdateEvent event) { TrajectoryCollisionRevision.invalidate(); }
    @EventHandler private void comet$onChunk(ChunkDataEvent event) { TrajectoryCollisionRevision.invalidate(); }
    @EventHandler private void comet$onEntityAdded(EntityAddedEvent event) { TrajectoryCollisionRevision.invalidate(); }
    @EventHandler private void comet$onEntityRemoved(EntityRemovedEvent event) { TrajectoryCollisionRevision.invalidate(); }
    @EventHandler private void comet$onLeave(GameLeftEvent event) { comet$clearPredictions(); }

    @Inject(method = "onRender", at = @At("HEAD"), cancellable = true)
    private void comet$renderPredictions(Render3DEvent event, CallbackInfo ci) {
        if (!comet$optimize.get()) {
            if (!comet$cache.isEmpty()) comet$clearPredictions();
            return;
        }
        ci.cancel();
        if (mc.level == null || mc.player == null) {
            comet$clearPredictions();
            return;
        }
        if (comet$world != mc.level) {
            comet$clearPredictions();
            comet$world = mc.level;
        }
        // A lerping border moves with wall-clock time between client ticks as well as packets.
        WorldBorder border = mc.level.getWorldBorder();
        double minX = border.getMinX(), maxX = border.getMaxX(), minZ = border.getMinZ(), maxZ = border.getMaxZ();
        if (comet$borderMinX != minX || comet$borderMaxX != maxX || comet$borderMinZ != minZ || comet$borderMaxZ != maxZ) {
            TrajectoryCollisionRevision.invalidate();
            comet$borderMinX = minX;
            comet$borderMaxX = maxX;
            comet$borderMinZ = minZ;
            comet$borderMaxZ = maxZ;
        }
        comet$frame++;
        comet$budget.beginFrame(comet$frameSteps.get());
        comet$remote.clear();
        double rangeSquared = comet$range.get() * comet$range.get();
        if (otherPlayers.get()) {
            for (Player player : mc.level.players()) {
                if (player != mc.player && player.distanceToSqr(mc.player) <= rangeSquared) comet$remote.add(player);
            }
        }
        if (firedProjectiles.get()) {
            for (Entity entity : mc.level.entitiesForRendering()) {
                if (!(entity instanceof Projectile) || entity.distanceToSqr(mc.player) > rangeSquared) continue;
                if (ignoreWitherSkulls.get() && entity instanceof WitherSkull) continue;
                if (entity instanceof ThrownTrident trident && trident.noPhysics) continue;
                comet$remote.add(entity);
            }
        }
        float delta = mc.level.tickRateManager().isFrozen() ? 1 : event.tickDelta;
        try {
            comet$renderEntity(mc.player, delta, event, true, comet$remote.size() + 1);
            int start = comet$budget.remoteStart(comet$remote.size());
            for (int i = 0; i < comet$remote.size(); i++) {
                comet$renderEntity(comet$remote.get((start + i) % comet$remote.size()), delta, event,
                    false, comet$remote.size() - i);
            }
        } finally {
            comet$calculating = false;
            paths = comet$stockPaths;
        }
        var iterator = comet$cache.values().iterator();
        while (iterator.hasNext()) {
            TrajectoryCachedPaths cached = iterator.next();
            if (cached.seen != comet$frame) {
                cached.clear();
                iterator.remove();
            }
        }
        comet$remote.clear();
    }

    @Unique private void comet$renderEntity(Entity entity, float delta, Render3DEvent event,
                                           boolean local, int candidatesLeft) {
        ItemStack held = entity instanceof Player player ? comet$heldItem(player) : null;
        if (entity instanceof Player && held == null) return;
        boolean multishot = held != null && held.getItem() instanceof CrossbowItem
            && Utils.hasEnchantment(held, Enchantments.MULTISHOT);
        Object input = held == null ? comet$firedInput(entity) : comet$heldInput((Player) entity, held, delta, multishot);
        TrajectoryCachedPaths cached = comet$cache.computeIfAbsent(entity, _ -> new TrajectoryCachedPaths());
        cached.seen = comet$frame;
        int allowance = comet$budget.allowance(local, candidatesLeft);
        if (!cached.prediction.matches(mc.level, mc.level.getGameTime(), TrajectoryCollisionRevision.current(), input, allowance)) {
            // A stale collision result is hidden whenever this frame has no budget to refresh it.
            if (comet$budget.remaining() <= 0) return;
            comet$budget.beginCandidate(allowance, multishot ? 3 : 1);
            paths = cached.paths;
            comet$calculating = true;
            try {
                if (held != null) calculatePath((Player) entity, delta);
                else calculateFiredPath(entity, delta);
            } finally {
                comet$calculating = false;
            }
            if (input instanceof TrajectoryHeldInput heldInput) input = heldInput.snapshot();
            cached.prediction.update(mc.level, mc.level.getGameTime(), TrajectoryCollisionRevision.current(), input,
                allowance, comet$budget.limited());
        }
        for (Object path : cached.paths) ((TrajectoryPathAccess) path).comet$render(event);
    }

    @Unique private ItemStack comet$heldItem(Player player) {
        ItemStack stack = player.getMainHandItem();
        if (items.get().contains(stack.getItem())) return stack;
        stack = player.getOffhandItem();
        return items.get().contains(stack.getItem()) ? stack : null;
    }

    @Unique private TrajectoryHeldInput comet$heldInput(Player player, ItemStack stack, float delta, boolean multishot) {
        Pose pose = player.getPose();
        if (player == mc.player && (Modules.get().get(NoSlow.class).airStrict() || Modules.get().get(Sneak.class).doPacket())) {
            pose = Pose.CROUCHING;
        }
        float yaw = player == mc.player && Rotations.rotating ? Rotations.serverYaw : player.getYRot(delta);
        float pitch = player == mc.player && Rotations.rotating ? Rotations.serverPitch : player.getXRot(delta);
        TrajectoryHeldInput.Motion motion = new TrajectoryHeldInput.Motion(
            new Vec3(Mth.lerp(delta, player.xOld, player.getX()), Mth.lerp(delta, player.yOld, player.getY()),
                Mth.lerp(delta, player.zOld, player.getZ())),
            accurate.get() ? player.getKnownMovement() : Vec3.ZERO, pose, player.getEyeHeight(pose), yaw, pitch,
            multishot ? player.getViewVector(1) : Vec3.ZERO, multishot ? player.getUpVector(1) : Vec3.ZERO,
            player.getTicksUsingItem(), accurate.get() && player.onGround(), accurate.get(),
            simulationSteps.get(), ignoreFirstTicks.get());
        return new TrajectoryHeldInput(motion, stack);
    }

    @Unique private TrajectoryFiredInput comet$firedInput(Entity entity) {
        boolean inGround = entity instanceof ProjectileInGroundAccessor accessor && accessor.meteor$invokeIsInGround();
        return new TrajectoryFiredInput(entity.position(), entity.getDeltaMovement(), entity.getPose(), entity.tickCount,
            entity.isNoGravity(), entity.isInWater(), inGround, simulationSteps.get());
    }

    @Override public void comet$beginPath() { if (comet$calculating) comet$budget.beginPath(); }
    @Override public void comet$endPath() { if (comet$calculating) comet$budget.endPath(); }
    @Override public SimulationStep comet$step(ProjectileEntitySimulator simulator) {
        return !comet$calculating || comet$budget.tryStep() ? simulator.tick() : SimulationStep.MISS;
    }

    @Unique private void comet$clearPredictions() {
        for (TrajectoryCachedPaths cached : comet$cache.values()) cached.clear();
        comet$cache.clear();
        comet$remote.clear();
        comet$budget.clear();
        comet$world = null;
        comet$borderMinX = Double.NaN;
        comet$calculating = false;
        if (comet$stockPaths != null) paths = comet$stockPaths;
    }

}
