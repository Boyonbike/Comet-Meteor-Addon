package dev.tntcoating.modules;

import dev.tntcoating.TntCoatingAddon;
import dev.tntcoating.core.SpongePlanner;
import dev.tntcoating.core.PlacementPriority;
import dev.tntcoating.core.PlacementGeometry;
import meteordevelopment.meteorclient.events.world.TickEvent;
import meteordevelopment.meteorclient.events.packets.PacketEvent;
import meteordevelopment.meteorclient.settings.*;
import meteordevelopment.meteorclient.systems.modules.Module;
import meteordevelopment.meteorclient.utils.player.*;
import meteordevelopment.meteorclient.utils.world.BlockUtils;
import meteordevelopment.orbit.EventHandler;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.protocol.game.*;
import net.minecraft.tags.FluidTags;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.block.*;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.*;
import java.util.*;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.ConcurrentHashMap;

public final class SmartSponge extends Module {
    private final SettingGroup general = settings.getDefaultGroup();
    private final Setting<Integer> radius = general.add(new IntSetting.Builder().name("search-radius").description("Nearby cube to search for reachable sponge positions.").defaultValue(5).range(1,8).build());
    private final Setting<Integer> interval = general.add(new IntSetting.Builder().name("scan-interval").description("Ticks between placement searches.").defaultValue(5).range(1,40).build());
    private final Setting<Boolean> collect = general.add(new BoolSetting.Builder().name("break-wet-sponges").description("Break only server-confirmed wet sponges this activation placed. Drops must be picked up normally.").defaultValue(false).build());
    private final PlacementPriority priority = new PlacementPriority();
    private final Set<BlockPos> owned = ConcurrentHashMap.newKeySet();
    private final Set<BlockPos> wet = new HashSet<>();
    private final Map<BlockPos,Integer> retry = new HashMap<>();
    private record Update(BlockPos pos, BlockState state) {}
    private final Queue<Update> updates = new ConcurrentLinkedQueue<>();
    private volatile BlockPos pending;
    private volatile boolean sent;
    private volatile boolean correction;
    private int ticks, nextScan, sentAt, generation;
    private ClientLevel world;
    private BlockPos mining;
    private String status = "Starting";
    public SmartSponge() { super(TntCoatingAddon.CATEGORY,"smart-sponge","Choose reachable sponge placements to absorb connected water and waterlogged blocks."); }
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
        generation++; stopMining(); pending = null; sent = false; correction = false;
        owned.clear(); wet.clear(); updates.clear(); retry.clear(); world = null; ticks = nextScan = 0;
    }
    @EventHandler private void receive(PacketEvent.Receive event) {
        if (event.packet instanceof ClientboundPlayerPositionPacket) correction = true;
        if (event.packet instanceof ClientboundBlockUpdatePacket p) enqueue(p.getPos(),p.getBlockState());
        if (event.packet instanceof ClientboundSectionBlocksUpdatePacket p) p.runUpdates(this::enqueue);
    }
    private void enqueue(BlockPos pos, BlockState state) {
        if ((sent && pos.equals(pending)) || owned.contains(pos)) updates.add(new Update(pos.immutable(),state));
    }
    @EventHandler(priority = 200) private void tick(TickEvent.Post event) {
        if (mc.player == null || mc.level == null || mc.gameMode == null) { reset(); return; }
        if (world != mc.level) { reset(); world = mc.level; }
        ticks++;
        if (correction) { correction = false; generation++; pending = null; sent = false; stopMining(); priority.resolve(ticks,10); nextScan = ticks + 10; }
        Update update;
        while ((update = updates.poll()) != null) {
            boolean sponge = update.state.is(Blocks.SPONGE) || update.state.is(Blocks.WET_SPONGE);
            if (update.pos.equals(pending)) {
                if (sponge) owned.add(update.pos); else retry.put(update.pos,ticks+20);
                pending = null; sent = false; priority.resolve(ticks, sponge ? 2 : 0); nextScan = ticks + 2;
            }
            if (owned.contains(update.pos)) {
                if (update.state.is(Blocks.WET_SPONGE)) wet.add(update.pos);
                else wet.remove(update.pos);
                if (!sponge) owned.remove(update.pos);
            }
        }
        if (!mc.player.isAlive()) { stopMining(); return; }
        if (pending != null) {
            if (ticks - sentAt > 40) { retry.put(pending,ticks+40); pending = null; sent = false; generation++; priority.resolve(ticks,0); }
            else { status = "Waiting for server"; return; }
        }
        if (ticks < nextScan) return;
        retry.entrySet().removeIf(e -> e.getValue() <= ticks);
        if (collect.get() && collectWet()) return;
        stopMining();
        if (ticks < nextScan) return;
        nextScan = ticks + interval.get();
        FindItemResult item = InvUtils.findInHotbar(Items.SPONGE);
        if (!item.found()) { status = "No dry sponge in hotbar"; return; }
        BlockPos origin = mc.player.blockPosition();
        BlockPos best = null; BlockHitResult bestHit = null; int bestScore = 0; double bestDistance = Double.MAX_VALUE;
        Map<BlockPos,Boolean> waterCache = new HashMap<>();
        int r = radius.get();
        for (BlockPos mutable : BlockPos.betweenClosed(origin.offset(-r,-r,-r),origin.offset(r,r,r))) {
            BlockPos pos = mutable.immutable();
            if (!placeable(pos) || retry.containsKey(pos)) continue;
            double distance = Vec3.atCenterOf(pos).distanceToSqr(mc.player.getEyePosition());
            if (distance > Math.pow(mc.player.blockInteractionRange()+1,2)) continue;
            boolean nearWater = absorbable(pos);
            if (!nearWater) for (Direction d : Direction.values()) if (absorbable(pos.relative(d))) { nearWater = true; break; }
            if (!nearWater) continue;
            BlockHitResult hit = support(pos);
            if (hit == null) continue;
            int score = SpongePlanner.score(new SpongePlanner.Cell(pos.getX(),pos.getY(),pos.getZ()), cell -> {
                BlockPos key = new BlockPos(cell.x(),cell.y(),cell.z());
                return waterCache.computeIfAbsent(key,this::absorbable);
            }) + (absorbable(pos) ? 1 : 0);
            if (score > bestScore || (score == bestScore && distance < bestDistance)) {
                best = pos; bestHit = hit; bestScore = score; bestDistance = distance;
            }
        }
        if (best == null) { status = "No reachable water target"; return; }
        status = "Expected removal: " + bestScore;
        place(best,bestHit);
    }
    private boolean loaded(BlockPos p) { return !world.isOutsideBuildHeight(p) && world.hasChunk(p.getX()>>4,p.getZ()>>4) && world.getWorldBorder().isWithinBounds(p); }
    private boolean absorbable(BlockPos p) {
        if (!loaded(p)) return false;
        BlockState s = world.getBlockState(p);
        return s.getFluidState().is(FluidTags.WATER) && (s.getBlock() instanceof BucketPickup || s.getBlock() instanceof LiquidBlock
            || s.is(Blocks.KELP) || s.is(Blocks.KELP_PLANT) || s.is(Blocks.SEAGRASS) || s.is(Blocks.TALL_SEAGRASS));
    }
    private boolean waterNearby(BlockPos p) {
        if (absorbable(p)) return true;
        for (Direction d : Direction.values()) if (absorbable(p.relative(d))) return true;
        return false;
    }
    private boolean placeable(BlockPos p) {
        return loaded(p) && world.getBlockState(p).canBeReplaced()
            && (world.getFluidState(p).isEmpty() || world.getFluidState(p).is(FluidTags.WATER))
            && !new AABB(p).intersects(mc.player.getBoundingBox().inflate(.08,0,.08))
            && BlockUtils.canPlaceBlock(p,true,Blocks.SPONGE);
    }
    private boolean visible(BlockHitResult hit) {
        Vec3 eye = mc.player.getEyePosition();
        double reach = Math.max(0,mc.player.blockInteractionRange()-.15);
        if (eye.distanceToSqr(hit.getLocation()) > reach*reach) return false;
        Direction d = hit.getDirection();
        Vec3 inside = hit.getLocation().add(-d.getStepX()*.001,-d.getStepY()*.001,-d.getStepZ()*.001);
        BlockHitResult ray = world.clip(new ClipContext(eye,inside,ClipContext.Block.COLLIDER,ClipContext.Fluid.NONE,mc.player));
        return ray.getType()==HitResult.Type.BLOCK && ray.getBlockPos().equals(hit.getBlockPos()) && ray.getDirection()==d && ray.getLocation().distanceToSqr(hit.getLocation()) < .0004;
    }
    private BlockHitResult support(BlockPos target) {
        for (Direction d : Direction.values()) {
            BlockPos p = target.relative(d); if (!loaded(p)) continue;
            BlockState s = world.getBlockState(p);
            if (s.canBeReplaced() || BlockUtils.isClickable(s.getBlock())) continue;
            BlockHitResult hit = face(p,d.getOpposite());
            if (hit != null) return hit;
        }
        return null;
    }
    private BlockHitResult face(BlockPos p, Direction d) {
        Vec3 eye = mc.player.getEyePosition();
        for (AABB local : world.getBlockState(p).getCollisionShape(world,p).toAabbs()) {
            AABB b = local.move(p.getX(),p.getY(),p.getZ());
            var bounds = new PlacementGeometry.Box(b.minX,b.minY,b.minZ,b.maxX,b.maxY,b.maxZ);
            int axis = d.getAxis()==Direction.Axis.X ? 0 : d.getAxis()==Direction.Axis.Y ? 1 : 2;
            for (var point : PlacementGeometry.faceSamples(bounds,axis,d.getAxisDirection().getStep(),new PlacementGeometry.Point(eye.x,eye.y,eye.z))) {
                BlockHitResult hit = new BlockHitResult(new Vec3(point.x(),point.y(),point.z()),d,p,false);
                if (visible(hit)) return hit;
            }
        }
        return null;
    }
    private void place(BlockPos pos, BlockHitResult hit) {
        priority.reserve();
        pending = pos; sent = false; sentAt = ticks;
        int token = generation;
        Rotations.rotate(Rotations.getYaw(hit.getLocation()),Rotations.getPitch(hit.getLocation()),90,() -> {
            if (!isActive() || token != generation || mc.level != world || mc.player == null || correction || !pos.equals(pending)) return;
            FindItemResult item = InvUtils.findInHotbar(Items.SPONGE);
            if (!item.found() || !placeable(pos) || !waterNearby(pos) || !visible(hit) || world.getBlockState(hit.getBlockPos()).canBeReplaced() || BlockUtils.isClickable(world.getBlockState(hit.getBlockPos()).getBlock())) { pending=null; priority.resolve(ticks,0); return; }
            boolean offhand = item.isOffhand();
            if (!offhand) InvUtils.swap(item.slot(),true);
            try { sentAt=ticks; sent=true; BlockUtils.interact(hit,offhand ? InteractionHand.OFF_HAND : InteractionHand.MAIN_HAND,true); }
            finally { if (!offhand) InvUtils.swapBack(); }
        });
    }
    private boolean collectWet() {
        for (BlockPos pos : List.copyOf(wet)) {
            if (!loaded(pos) || !world.getBlockState(pos).is(Blocks.WET_SPONGE)) continue;
            for (Direction d : Direction.values()) {
                BlockHitResult hit = face(pos,d); if (hit == null) continue;
                if (mining != null && !mining.equals(pos)) stopMining();
                mining = pos; int token = generation;
                Rotations.rotate(Rotations.getYaw(hit.getLocation()),Rotations.getPitch(hit.getLocation()),90,() -> {
                    if (isActive() && collect.get() && token==generation && mc.level==world && mc.player!=null && !correction
                        && wet.contains(pos) && owned.contains(pos) && world.getBlockState(pos).is(Blocks.WET_SPONGE) && visible(hit)) BlockUtils.breakBlock(pos,true);
                });
                status = "Collecting wet sponge"; return true;
            }
        }
        return false;
    }
}
