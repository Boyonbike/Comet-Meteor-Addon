package dev.tntcoating.modules;

import dev.tntcoating.core.PlacementTracker;
import dev.tntcoating.core.TntBlastPlanner;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;

import static meteordevelopment.meteorclient.MeteorClient.mc;

/**
 * Block Coat's conservative-TNT estimate: whether existing or queued TNT already covers every eligible terrain face
 * of a target. Predictions are cached across ticks and bounded per tick; client-thread only, except {@link #markDirty}.
 */
final class BlockCoatBlasts {
    /** Block Coat's coating rules, which decide what counts as terrain worth covering. */
    interface Terrain {
        boolean loaded(BlockPos pos);
        boolean coatable(BlockPos pos);
        boolean faceEnabled(Direction face);
    }

    private static final int PREDICTIONS_PER_TICK = 16;

    private final AtomicBoolean dirty = new AtomicBoolean(true);
    private final Set<TntBlastPlanner.Cell> nearbyTnt = new HashSet<>();
    private final Map<TntBlastPlanner.Cell, Set<TntBlastPlanner.Cell>> coverage = new HashMap<>();
    private final Map<TntBlastPlanner.Cell, TntBlastPlanner.Material> materials = new HashMap<>();
    private final Map<Long, Boolean> chunks = new HashMap<>();
    private BlockPos origin;
    private int radius, refreshedTick;
    private int predictions, predictionTick;
    private boolean limited;

    /** May be called from any thread when blocks or chunks near the coating cube change. */
    void markDirty() { dirty.set(true); }

    void clear() {
        nearbyTnt.clear(); coverage.clear(); materials.clear(); chunks.clear();
        origin = null; dirty.set(true);
        predictions = 0; predictionTick = -1; limited = false;
    }

    /** True when the last {@link #covered} call ran out of this tick's prediction budget. */
    boolean limited() { return limited; }

    /** Keep coverage separate from candidates: a failed or removed source must reopen its neighbours. */
    boolean covered(BlockPos target, int tick, int coatRadius, PlacementTracker<BlockPos, Block> tracker, Terrain terrain) {
        limited = false;
        refresh(tick, coatRadius, tracker, terrain);
        Set<TntBlastPlanner.Cell> remaining = new HashSet<>();
        for (Direction side : Direction.values()) {
            BlockPos face = target.relative(side);
            if (terrain.faceEnabled(side.getOpposite()) && terrain.coatable(face)) remaining.add(cell(face));
        }
        Set<TntBlastPlanner.Cell> uniqueSources = new HashSet<>(nearbyTnt);
        // Reservations prevent duplicate coverage within a batch, including deferred rotations.
        for (BlockPos source : tracker.pendingPositions()) uniqueSources.add(cell(source));
        List<TntBlastPlanner.Cell> sources = new ArrayList<>(uniqueSources);
        sources.removeIf(source -> Math.abs(source.x() - target.getX()) > TntBlastPlanner.RADIUS + 1
            || Math.abs(source.y() - target.getY()) > TntBlastPlanner.RADIUS + 1
            || Math.abs(source.z() - target.getZ()) > TntBlastPlanner.RADIUS + 1);
        sources.sort(Comparator.comparingDouble(source -> {
            double dx = source.x() - target.getX(), dy = source.y() - target.getY(), dz = source.z() - target.getZ();
            return dx * dx + dy * dy + dz * dz;
        }));
        return TntBlastPlanner.coveredByOthers(cell(target), remaining, sources,
            source -> sourceCoverage(source, tick, tracker, terrain));
    }

    private Set<TntBlastPlanner.Cell> sourceCoverage(TntBlastPlanner.Cell cell, int tick,
                                                     PlacementTracker<BlockPos, Block> tracker, Terrain terrain) {
        BlockPos source = new BlockPos(cell.x(), cell.y(), cell.z());
        PlacementTracker.Attempt<Block> pending = tracker.attempt(source);
        if (pending == null) {
            // Client prediction can leave a ghost after timeout; only trust confirmed world TNT.
            if (tracker.unconfirmed(source) || !terrain.loaded(source) || !confirmedTnt(mc.level.getBlockState(source))) return Set.of();
        } else if (pending.block != Blocks.TNT) return Set.of();
        Set<TntBlastPlanner.Cell> cached = coverage.get(cell);
        if (cached != null) return cached;
        if (predictionTick != tick) { predictionTick = tick; predictions = 0; }
        if (predictions >= PREDICTIONS_PER_TICK) { limited = true; return Set.of(); }
        predictions++;
        Set<TntBlastPlanner.Cell> predicted = TntBlastPlanner.coverage(cell,
            pos -> materials.computeIfAbsent(pos, at -> material(at, terrain)));
        coverage.put(cell, predicted);
        return predicted;
    }

    private TntBlastPlanner.Material material(TntBlastPlanner.Cell cell, Terrain terrain) {
        BlockPos pos = new BlockPos(cell.x(), cell.y(), cell.z());
        boolean chunkLoaded = terrain.loaded(pos);
        long chunk = ((long) (pos.getX() >> 4) << 32) | ((pos.getZ() >> 4) & 0xffffffffL);
        chunks.put(chunk, chunkLoaded);
        if (!chunkLoaded || mc.level.isOutsideBuildHeight(pos) || !mc.level.getWorldBorder().isWithinBounds(pos))
            return TntBlastPlanner.Material.BLOCKED;
        BlockState state = mc.level.getBlockState(pos);
        if (state.isAir() && state.getFluidState().isEmpty()) return TntBlastPlanner.Material.AIR;
        if (state.getDestroySpeed(mc.level, pos) < 0) return TntBlastPlanner.Material.BLOCKED;
        float resistance = Math.max(state.getBlock().getExplosionResistance(), state.getFluidState().getExplosionResistance());
        if (!Float.isFinite(resistance) || resistance < 0) return TntBlastPlanner.Material.BLOCKED;
        return new TntBlastPlanner.Material(resistance, terrain.coatable(pos));
    }

    private void refresh(int tick, int coatRadius, PlacementTracker<BlockPos, Block> tracker, Terrain terrain) {
        BlockPos center = mc.player.blockPosition();
        boolean invalidate = dirty.getAndSet(false) || !center.equals(origin) || radius != coatRadius;
        if (!invalidate && tick - refreshedTick < 5) return;
        if (!invalidate) {
            for (var entry : chunks.entrySet()) {
                long chunk = entry.getKey();
                if (mc.level.hasChunk((int) (chunk >> 32), (int) chunk) != entry.getValue()) { invalidate = true; break; }
            }
        }
        // Periodic source discovery preserves completed predictions, so large jobs can span ticks.
        if (invalidate) { coverage.clear(); materials.clear(); chunks.clear(); }
        nearbyTnt.clear();
        origin = center.immutable(); radius = coatRadius; refreshedTick = tick;
        int r = coatRadius + TntBlastPlanner.RADIUS + 1;
        for (BlockPos pos : BlockPos.betweenClosed(center.offset(-r, -r, -r), center.offset(r, r, r))) {
            if (terrain.loaded(pos) && !mc.level.isOutsideBuildHeight(pos) && !tracker.unconfirmed(pos)
                && confirmedTnt(mc.level.getBlockState(pos))) nearbyTnt.add(cell(pos));
        }
    }

    /** TNT that is not waterlogged or otherwise fluid-filled, read once per position. */
    private static boolean confirmedTnt(BlockState state) {
        return state.is(Blocks.TNT) && state.getFluidState().isEmpty();
    }

    private static TntBlastPlanner.Cell cell(BlockPos pos) {
        return new TntBlastPlanner.Cell(pos.getX(), pos.getY(), pos.getZ());
    }
}
