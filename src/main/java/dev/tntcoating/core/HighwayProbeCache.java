package dev.tntcoating.core;

import java.util.HashMap;
import java.util.Map;
import java.util.function.Supplier;

/** Bounded reuse of terrain queries within one world tick and block-update revision. */
public final class HighwayProbeCache<V> {
    private final int capacity;
    private final Map<Long,V> entries = new HashMap<>();
    private Object world;
    private int tick;
    private int revision;
    public void clear() {
        entries.clear();
        world = null;
    }
    public HighwayProbeCache(int capacity) {
        if (capacity < 1) throw new IllegalArgumentException("capacity");
        this.capacity = capacity;
    }
    public V get(Object world, int tick, int revision, long key, Supplier<V> query) {
        if (this.world != world || this.tick != tick || this.revision != revision) {
            entries.clear(); this.world = world; this.tick = tick; this.revision = revision;
        }
        V cached = entries.get(key);
        if (cached != null) return cached;
        V value = query.get();
        if (entries.size() < capacity) entries.put(key, value);
        return value;
    }
}
