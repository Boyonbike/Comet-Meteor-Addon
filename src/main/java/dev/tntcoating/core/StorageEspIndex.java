package dev.tntcoating.core;

import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import net.minecraft.core.BlockPos;

import java.util.ArrayDeque;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Client-thread storage index for bounded, persistent render batches. Each cell holds at most
 * 8 * 8 * 8 block positions; unchanged reconciliation leaves both its revision and dirty queue alone.
 */
public final class StorageEspIndex<T> {
    public record CellKey(int x, int y, int z) {
        public static CellKey of(BlockPos pos) {
            return new CellKey(pos.getX() >> 3, pos.getY() >> 3, pos.getZ() >> 3);
        }

        public int minX() { return x << 3; }
        public int minY() { return y << 3; }
        public int minZ() { return z << 3; }
    }

    public static final class Cell<T> {
        private final CellKey key;
        private final Long2ObjectOpenHashMap<T> entries = new Long2ObjectOpenHashMap<>();
        private final Collection<T> values = Collections.unmodifiableCollection(entries.values());
        private long revision;

        private Cell(CellKey key) { this.key = key; }

        public CellKey key() { return key; }
        public Collection<T> values() { return values; }
        public long revision() { return revision; }
        public int size() { return entries.size(); }
    }

    private final Map<CellKey, Cell<T>> cells = new HashMap<>();
    private final Collection<Cell<T>> cellsView = Collections.unmodifiableCollection(cells.values());
    private final Long2ObjectOpenHashMap<Set<CellKey>> chunks = new Long2ObjectOpenHashMap<>();
    private final ArrayDeque<CellKey> dirtyQueue = new ArrayDeque<>();
    private final Set<CellKey> queued = new HashSet<>();

    /** Stores a non-null value. Equal values already at this position do not invalidate the batch. */
    public void put(BlockPos pos, T value) {
        Objects.requireNonNull(value, "value");
        CellKey key = CellKey.of(pos);
        Cell<T> cell = cells.get(key);
        if (cell == null) {
            cell = new Cell<>(key);
            cells.put(key, cell);
            chunks.computeIfAbsent(chunkKey(key.x() >> 1, key.z() >> 1), ignored -> new HashSet<>()).add(key);
        }
        long packed = pos.asLong();
        if (Objects.equals(cell.entries.get(packed), value)) return;
        cell.entries.put(packed, value);
        cell.revision++;
        enqueue(key);
    }

    /** Leaves an empty cell available so its next rebuild can dispose of the old GPU batch. */
    public void remove(BlockPos pos) {
        CellKey key = CellKey.of(pos);
        Cell<T> cell = cells.get(key);
        if (cell == null || cell.entries.remove(pos.asLong()) == null) return;
        cell.revision++;
        enqueue(key);
    }

    /** Removes only this chunk's cells, including empty cells, and queues their batch cleanup. */
    public void removeChunk(int chunkX, int chunkZ) {
        Set<CellKey> keys = chunks.remove(chunkKey(chunkX, chunkZ));
        if (keys == null) return;
        for (CellKey key : keys) {
            Cell<T> cell = cells.remove(key);
            cell.entries.clear();
            cell.revision++;
            enqueue(key);
        }
    }

    public Cell<T> cell(CellKey key) { return cells.get(key); }

    /** A live, read-only view. Mutations and iteration must both happen on the client thread. */
    public Collection<Cell<T>> cells() { return cellsView; }

    public CellKey pollDirty() {
        CellKey key = dirtyQueue.pollFirst();
        if (key != null) queued.remove(key);
        return key;
    }

    /** Invalidates a cell even when its values are unchanged, or queues cleanup for an absent cell. */
    public void dirty(CellKey key) {
        Objects.requireNonNull(key, "key");
        Cell<T> cell = cells.get(key);
        if (cell != null) cell.revision++;
        enqueue(key);
    }

    public void dirtyAll() {
        for (Cell<T> cell : cells.values()) {
            cell.revision++;
            enqueue(cell.key);
        }
    }

    /** Discards all entries and pending work. The caller owns disposal of any existing GPU batches. */
    public void clear() {
        cells.clear();
        chunks.clear();
        dirtyQueue.clear();
        queued.clear();
    }

    private void enqueue(CellKey key) {
        if (queued.add(key)) dirtyQueue.addLast(key);
    }

    private static long chunkKey(int x, int z) {
        return (long) x << 32 | z & 0xffff_ffffL;
    }
}
