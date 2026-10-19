package dev.tntcoating.modules;

import dev.tntcoating.TntCoatingAddon;
import dev.tntcoating.core.PacketBudget;
import dev.tntcoating.core.PlacementGeometry;
import dev.tntcoating.core.PlacementPriority;
import dev.tntcoating.core.SpongePlanner;
import meteordevelopment.meteorclient.events.packets.PacketEvent;
import meteordevelopment.meteorclient.events.world.TickEvent;
import meteordevelopment.meteorclient.settings.BoolSetting;
import meteordevelopment.meteorclient.settings.IntSetting;
import meteordevelopment.meteorclient.settings.Setting;
import meteordevelopment.meteorclient.settings.SettingGroup;
import meteordevelopment.meteorclient.systems.modules.Module;
import meteordevelopment.meteorclient.systems.modules.Modules;
import meteordevelopment.meteorclient.utils.player.FindItemResult;
import meteordevelopment.meteorclient.utils.player.InvUtils;
import meteordevelopment.meteorclient.utils.player.Rotations;
import meteordevelopment.meteorclient.utils.world.BlockUtils;
import meteordevelopment.orbit.EventHandler;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.protocol.game.ClientboundBlockUpdatePacket;
import net.minecraft.network.protocol.game.ClientboundPlayerPositionPacket;
import net.minecraft.network.protocol.game.ClientboundSectionBlocksUpdatePacket;
import net.minecraft.tags.FluidTags;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.BucketPickup;
import net.minecraft.world.level.block.LiquidBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Queue;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicInteger;

public final class SmartSponge extends Module {
    /** Used when Block Coat, which owns the shared packet limit, is not installed. */
    private static final int DEFAULT_PACKET_LIMIT = 150;

    private final SettingGroup general = settings.getDefaultGroup();
    private final Setting<Integer> radius = general.add(new IntSetting.Builder().name("search-radius")
        .description("Nearby cube to search for reachable sponge positions.").defaultValue(5).range(1, 8).build());
    private final Setting<Integer> interval = general.add(new IntSetting.Builder().name("scan-interval")
        .description("Ticks between placement searches.").defaultValue(5).range(1, 40).build());
    private final Setting<Boolean> collect = general.add(new BoolSetting.Builder().name("break-wet-sponges")
        .description("Break only server-confirmed wet sponges this activation placed. Drops must be picked up normally.")
        .defaultValue(false).build());

    private final PlacementPriority priority = new PlacementPriority();
    private final PacketBudget packets = new PacketBudget();
    // Packet callbacks may run on the network thread; only these fields are shared with it.
    private final AtomicInteger sentPackets = new AtomicInteger();
    private final Set<BlockPos> owned = ConcurrentHashMap.newKeySet();
    private final Queue<Update> updates = new ConcurrentLinkedQueue<>();
    private volatile BlockPos pending;
    private volatile boolean sent;
    private volatile boolean correction;
    private final Set<BlockPos> wet = new HashSet<>();
    private final Map<BlockPos, Integer> retry = new HashMap<>();
    private int ticks, nextScan, sentAt, generation;
    private ClientLevel world;
    private BlockPos mining;
    private String status = "Starting";

    private record Update(BlockPos pos, BlockState state) {}
    private record Spot(BlockPos pos, int score, double distance) {}

    public SmartSponge() {
        super(TntCoatingAddon.CATEGORY, "smart-sponge", "Choose reachable sponge placements to absorb connected water and waterlogged blocks.");
    }

    public boolean blocksCoating() {
        return isActive() && mc.player != null && mc.player.isAlive() && world == mc.level
            && (priority.blocks(ticks) || (collect.get() && mining != null));
    }

    @Override public String getInfoString() { return status; }
    @Override public void onActivate() { reset(); }
    @Override public void onDeactivate() { reset(); }

    private void stopMining() {
        if (mining != null && mc.gameMode != null) mc.gameMode.stopDestroyBlock();
        mining = null;
    }

    private void reset() {
        priority.reset();
        generation++;
        stopMining();
        pending = null;
        sent = false;
        correction = false;
        owned.clear();
        wet.clear();
        updates.clear();
        retry.clear();
        packets.reset();
        sentPackets.set(0);
        world = null;
        ticks = nextScan = 0;
    }

    @EventHandler private void receive(PacketEvent.Receive event) {
        if (event.packet instanceof ClientboundPlayerPositionPacket) correction = true;
        if (event.packet instanceof ClientboundBlockUpdatePacket packet) enqueue(packet.getPos(), packet.getBlockState());
        if (event.packet instanceof ClientboundSectionBlocksUpdatePacket packet) packet.runUpdates(this::enqueue);
    }

    @EventHandler private void onSent(PacketEvent.Sent event) { sentPackets.incrementAndGet(); }

    private void enqueue(BlockPos pos, BlockState state) {
        if ((sent && pos.equals(pending)) || owned.contains(pos)) updates.add(new Update(pos.immutable(), state));
    }

    @EventHandler(priority = 200) private void tick(TickEvent.Post event) {
        if (mc.player == null || mc.level == null || mc.gameMode == null) { reset(); return; }
        if (world != mc.level) { reset(); world = mc.level; }
        ticks++;
        packets.record(ticks, sentPackets.getAndSet(0));
        if (correction) {
            correction = false;
            generation++;
            pending = null;
            sent = false;
            stopMining();
            priority.resolve(ticks, 10);
            nextScan = ticks + 10;
        }
        Update update;
        while ((update = updates.poll()) != null) {
            boolean sponge = update.state.is(Blocks.SPONGE) || update.state.is(Blocks.WET_SPONGE);
            if (update.pos.equals(pending)) {
                if (sponge) owned.add(update.pos);
                else retry.put(update.pos, ticks + 20);
                pending = null;
                sent = false;
                priority.resolve(ticks, sponge ? 2 : 0);
                nextScan = ticks + 2;
            }
            if (owned.contains(update.pos)) {
                if (update.state.is(Blocks.WET_SPONGE)) wet.add(update.pos);
                else wet.remove(update.pos);
                if (!sponge) owned.remove(update.pos);
            }
        }
        if (!mc.player.isAlive()) { stopMining(); return; }
        if (pending != null) {
            if (ticks - sentAt > 40) {
                retry.put(pending, ticks + 40);
                pending = null;
                sent = false;
                generation++;
                priority.resolve(ticks, 0);
            } else {
                status = "Waiting for server";
                return;
            }
        }
        // A passenger sends no movement packets, so Meteor would never run the rotation callbacks below.
        if (mc.player.isPassenger()) { stopMining(); status = "Paused while riding"; return; }
        if (ticks < nextScan) return;
        retry.entrySet().removeIf(entry -> entry.getValue() <= ticks);
        if (collect.get() && collectWet()) return;
        stopMining();
        nextScan = ticks + interval.get();
        FindItemResult item = InvUtils.findInHotbar(Items.SPONGE);
        if (!item.found()) { status = "No dry sponge in hotbar"; return; }
        // Swap there and back unless the sponge is already in hand, plus a possible extra rotation packet.
        boolean held = item.isOffhand() || item.slot() == mc.player.getInventory().getSelectedSlot();
        if (!packets.allows(ticks, PacketBudget.placementCost(held ? 0 : 2, true, false), packetLimit())) {
            status = "Packet limit";
            nextScan = ticks + 1;
            return;
        }
        BlockPos origin = mc.player.blockPosition();
        List<Spot> spots = new ArrayList<>();
        Map<BlockPos, Boolean> waterCache = new HashMap<>();
        int r = radius.get();
        for (BlockPos mutable : BlockPos.betweenClosed(origin.offset(-r, -r, -r), origin.offset(r, r, r))) {
            BlockPos pos = mutable.immutable();
            if (!placeable(pos) || retry.containsKey(pos)) continue;
            double distance = Vec3.atCenterOf(pos).distanceToSqr(mc.player.getEyePosition());
            if (distance > Math.pow(mc.player.blockInteractionRange() + 1, 2)) continue;
            if (!waterNearby(pos)) continue;
            int score = SpongePlanner.score(new SpongePlanner.Cell(pos.getX(), pos.getY(), pos.getZ()), cell -> {
                BlockPos key = new BlockPos(cell.x(), cell.y(), cell.z());
                return waterCache.computeIfAbsent(key, this::absorbable);
            }) + (absorbable(pos) ? 1 : 0);
            spots.add(new Spot(pos, score, distance));
        }
        // Scoring is cheap; ray-cast supports only in score order until one is reachable. Ties prefer the closer spot.
        spots.sort(Comparator.comparingInt(Spot::score).reversed().thenComparingDouble(Spot::distance));
        for (Spot spot : spots) {
            BlockHitResult hit = support(spot.pos());
            if (hit == null) continue;
            status = "Expected removal: " + spot.score();
            place(spot.pos(), hit);
            return;
        }
        status = "No reachable water target";
    }

    /** Block Coat's max-packets-per-second, so both modules stay under one limit. */
    private int packetLimit() {
        BlockCoat coat = Modules.get().get(BlockCoat.class);
        return coat == null ? DEFAULT_PACKET_LIMIT : coat.packetLimit();
    }

    private boolean loaded(BlockPos pos) {
        return !world.isOutsideBuildHeight(pos) && world.hasChunk(pos.getX() >> 4, pos.getZ() >> 4)
            && world.getWorldBorder().isWithinBounds(pos);
    }

    private boolean absorbable(BlockPos pos) {
        if (!loaded(pos)) return false;
        BlockState state = world.getBlockState(pos);
        return state.getFluidState().is(FluidTags.WATER) && (state.getBlock() instanceof BucketPickup
            || state.getBlock() instanceof LiquidBlock || state.is(Blocks.KELP) || state.is(Blocks.KELP_PLANT)
            || state.is(Blocks.SEAGRASS) || state.is(Blocks.TALL_SEAGRASS));
    }

    private boolean waterNearby(BlockPos pos) {
        if (absorbable(pos)) return true;
        for (Direction direction : Direction.values()) if (absorbable(pos.relative(direction))) return true;
        return false;
    }

    private boolean placeable(BlockPos pos) {
        return loaded(pos) && world.getBlockState(pos).canBeReplaced()
            && (world.getFluidState(pos).isEmpty() || world.getFluidState(pos).is(FluidTags.WATER))
            && !new AABB(pos).intersects(mc.player.getBoundingBox().inflate(.08, 0, .08))
            && BlockUtils.canPlaceBlock(pos, true, Blocks.SPONGE);
    }

    private boolean visible(BlockHitResult hit) {
        Vec3 eye = mc.player.getEyePosition();
        double reach = Math.max(0, mc.player.blockInteractionRange() - .15);
        if (eye.distanceToSqr(hit.getLocation()) > reach * reach) return false;
        Direction face = hit.getDirection();
        Vec3 inside = hit.getLocation().add(-face.getStepX() * .001, -face.getStepY() * .001, -face.getStepZ() * .001);
        BlockHitResult ray = world.clip(new ClipContext(eye, inside, ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, mc.player));
        return ray.getType() == HitResult.Type.BLOCK && ray.getBlockPos().equals(hit.getBlockPos())
            && ray.getDirection() == face && ray.getLocation().distanceToSqr(hit.getLocation()) < .0004;
    }

    private BlockHitResult support(BlockPos target) {
        for (Direction direction : Direction.values()) {
            BlockPos pos = target.relative(direction);
            if (!loaded(pos)) continue;
            BlockState state = world.getBlockState(pos);
            if (state.canBeReplaced() || BlockUtils.isClickable(state.getBlock())) continue;
            BlockHitResult hit = face(pos, direction.getOpposite());
            if (hit != null) return hit;
        }
        return null;
    }

    private BlockHitResult face(BlockPos pos, Direction face) {
        Vec3 eye = mc.player.getEyePosition();
        int axis = face.getAxis() == Direction.Axis.X ? 0 : face.getAxis() == Direction.Axis.Y ? 1 : 2;
        for (AABB local : world.getBlockState(pos).getCollisionShape(world, pos).toAabbs()) {
            AABB box = local.move(pos.getX(), pos.getY(), pos.getZ());
            var bounds = new PlacementGeometry.Box(box.minX, box.minY, box.minZ, box.maxX, box.maxY, box.maxZ);
            for (var point : PlacementGeometry.faceSamples(bounds, axis, face.getAxisDirection().getStep(),
                new PlacementGeometry.Point(eye.x, eye.y, eye.z))) {
                BlockHitResult hit = new BlockHitResult(new Vec3(point.x(), point.y(), point.z()), face, pos, false);
                if (visible(hit)) return hit;
            }
        }
        return null;
    }

    private void place(BlockPos pos, BlockHitResult hit) {
        priority.reserve();
        pending = pos;
        sent = false;
        sentAt = ticks;
        int token = generation;
        Rotations.rotate(Rotations.getYaw(hit.getLocation()), Rotations.getPitch(hit.getLocation()), 90, () -> {
            if (!isActive() || token != generation || mc.level != world || mc.player == null || correction || !pos.equals(pending)) return;
            FindItemResult item = InvUtils.findInHotbar(Items.SPONGE);
            BlockState support = world.getBlockState(hit.getBlockPos());
            if (!item.found() || !placeable(pos) || !waterNearby(pos) || !visible(hit)
                || support.canBeReplaced() || BlockUtils.isClickable(support.getBlock())) {
                pending = null;
                priority.resolve(ticks, 0);
                return;
            }
            boolean offhand = item.isOffhand();
            if (!offhand) InvUtils.swap(item.slot(), true);
            try {
                sentAt = ticks;
                sent = true;
                BlockUtils.interact(hit, offhand ? InteractionHand.OFF_HAND : InteractionHand.MAIN_HAND, true);
            } finally {
                if (!offhand) InvUtils.swapBack();
            }
        });
    }

    private boolean collectWet() {
        for (BlockPos pos : List.copyOf(wet)) {
            if (!loaded(pos) || !world.getBlockState(pos).is(Blocks.WET_SPONGE)) continue;
            for (Direction direction : Direction.values()) {
                BlockHitResult hit = face(pos, direction);
                if (hit == null) continue;
                if (mining != null && !mining.equals(pos)) stopMining();
                mining = pos;
                // Each mining tick sends a block action and a swing; wait while the shared limit is reached.
                if (!packets.allows(ticks, 2, packetLimit())) {
                    status = "Packet limit";
                    return true;
                }
                int token = generation;
                Rotations.rotate(Rotations.getYaw(hit.getLocation()), Rotations.getPitch(hit.getLocation()), 90, () -> {
                    if (isActive() && collect.get() && token == generation && mc.level == world && mc.player != null && !correction
                        && wet.contains(pos) && owned.contains(pos) && world.getBlockState(pos).is(Blocks.WET_SPONGE) && visible(hit)) {
                        BlockUtils.breakBlock(pos, true);
                    }
                });
                status = "Collecting wet sponge";
                return true;
            }
        }
        return false;
    }
}
