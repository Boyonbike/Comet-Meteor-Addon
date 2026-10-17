package dev.tntcoating.compat;

import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.Level;

import java.util.List;

/** Optional XaeroPlus integration. Only {@link XaeroPlusIntegration} links against its classes. */
public interface XaeroPlusBridge {
    String SUPPORTED_VERSION = "2.36.6";

    /** Null when absent; an installed, unsupported version reports its reason without loading its classes. */
    static XaeroPlusBridge create() {
        var mod = FabricLoader.getInstance().getModContainer("xaeroplus");
        if (mod.isEmpty()) return null;
        String version = mod.get().getMetadata().getVersion().getFriendlyString();
        if (!SUPPORTED_VERSION.equals(version)) {
            return unavailable("XaeroPlus " + version + " is unsupported; Trail Hunter requires "
                + SUPPORTED_VERSION + " for Minecraft 26.2.");
        }
        try {
            return new XaeroPlusIntegration();
        } catch (LinkageError | RuntimeException e) {
            return unavailable("This XaeroPlus installation is incompatible with Trail Hunter ("
                + e.getClass().getSimpleName() + ").");
        }
    }

    private static XaeroPlusBridge unavailable(String reason) {
        return new XaeroPlusBridge() {
            public String unavailableReason(Detection detection) { return reason; }
            public void start(Detection detection) {}
            public void stop() {}
            public List<Chunk> drain() { return List.of(); }
        };
    }

    enum Detection { OLD_112, NON_FRESH, EITHER }

    /** Null when the selected detector is enabled. EITHER requires at least one of the two detectors. */
    String unavailableReason(Detection detection);

    /** Explicit fresh evidence is needed to distinguish a new-chunk wall from unknown terrain. */
    default boolean freshDetectionAvailable() { return false; }

    /** Listen to new arrivals only; existing map highlights do not count towards a trail lock. */
    void start(Detection detection);

    /** Listen using either enabled detector. */
    default void start() { start(Detection.EITHER); }

    /** Unregister the listener and discard arrivals. Safe to call more than once. */
    void stop();

    /**
     * Call on the client tick, outside XaeroPlus's ChunkDataEvent dispatch. This reads the results only after
     * XaeroPlus has completed both synchronous chunk checks. Includes known negative and fresh classifications;
     * callers must check old112/nonFresh before treating an arrival as trail evidence. Unknown entries are omitted.
     */
    List<Chunk> drain();

    /**
     * Read known classifications from XaeroPlus's current local caches, without loading chunks or map files.
     * Radius is clamped to 0..32 chunks. The zero receipt time marks snapshots, which must never be counted as
     * new arrivals towards acquiring a trail. Unsupported/absent integrations return an empty list.
     */
    default List<Chunk> sample(int centerChunkX, int centerChunkZ, int radius) { return List.of(); }

    /**
     * receivedAtMillis uses monotonic System.nanoTime()/1_000_000, including any wait for disk cache startup.
     * Zero means a cache snapshot, not an arrival. Only detectors selected by start() contribute trail flags;
     * fresh is a positive PaletteNewChunks classification regardless of the selected detection mode. It is
     * never inferred from a missing old classification. EITHER emits as soon as either trail result is positive.
     * modern is OldChunks' positive result that the chunk was generated after 1.12 (it files every chunk it scans
     * as one or the other), reported only when 1.12 detection is selected.
     */
    record Chunk(int x, int z, ResourceKey<Level> dimension, boolean old112, boolean nonFresh, boolean fresh,
                 long receivedAtMillis, boolean modern) {
        public Chunk(int x, int z, ResourceKey<Level> dimension, boolean old112, boolean nonFresh, boolean fresh,
                     long receivedAtMillis) {
            this(x, z, dimension, old112, nonFresh, fresh, receivedAtMillis, false);
        }

        public Chunk(int x, int z, ResourceKey<Level> dimension, boolean old112, boolean nonFresh, long receivedAtMillis) {
            this(x, z, dimension, old112, nonFresh, false, receivedAtMillis, false);
        }
    }
}
