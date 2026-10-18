package dev.tntcoating.compat;

import net.lenni0451.lambdaevents.EventHandler;
import net.minecraft.client.Minecraft;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.Level;
import xaeroplus.XaeroPlus;
import xaeroplus.event.ChunkDataEvent;
import xaeroplus.module.ModuleManager;
import xaeroplus.module.impl.OldChunks;
import xaeroplus.module.impl.PaletteNewChunks;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

/** All references to XaeroPlus, including its shaded event library, stay in this optional class. */
public final class XaeroPlusIntegration implements XaeroPlusBridge {
    // Cache startup may defer highlight insertion until its database is ready. Bound both age and memory while waiting.
    private static final long MAX_WAIT_NANOS = TimeUnit.SECONDS.toNanos(5);
    private static final int MAX_PENDING = 4096;
    private static final int MAX_SAMPLE_RADIUS = 32;
    private final ArrayDeque<Pending> pending = new ArrayDeque<>();
    private final OldChunks oldChunks = ModuleManager.getModule(OldChunks.class);
    private final PaletteNewChunks paletteChunks = ModuleManager.getModule(PaletteNewChunks.class);
    private boolean listening;
    private String failure;
    private Detection selectedDetection;

    @Override public boolean freshDetectionAvailable() { return paletteChunks != null && paletteChunks.isEnabled(); }

    @Override
    public String unavailableReason(Detection detection) {
        if (failure != null) return failure;
        if (oldChunks == null || paletteChunks == null) return "XaeroPlus does not provide the supported chunk detectors.";
        boolean old = oldChunks.isEnabled();
        boolean palette = paletteChunks.isEnabled();
        return switch (detection) {
            case OLD_112 -> old ? null : "Enable XaeroPlus OldChunks highlighting to use 1.12 trail detection.";
            case NON_FRESH -> palette ? null : "Enable XaeroPlus Palette NewChunks to use non-fresh trail detection.";
            case EITHER -> old || palette ? null : "Enable XaeroPlus OldChunks or Palette NewChunks to detect trails.";
        };
    }

    @Override
    public void start(Detection detection) {
        java.util.Objects.requireNonNull(detection);
        if (listening && selectedDetection == detection) return;
        if (listening) stop();
        selectedDetection = detection;
        pending.clear();
        listening = true;
        try {
            XaeroPlus.EVENT_BUS.register(this);
        } catch (LinkageError | RuntimeException e) {
            listening = false;
            throw e;
        }
    }

    @Override
    public void stop() {
        boolean wasListening = listening;
        listening = false;
        pending.clear();
        if (wasListening) XaeroPlus.EVENT_BUS.unregister(this);
    }

    /**
     * XaeroPlus 2.36.6 dispatches this on the client thread from handleLevelChunkWithLight RETURN. OldChunks and
     * PaletteNewChunks scan synchronously in the same dispatch. Queue coordinates here; inspect caches in drain(),
     * after the entire dispatch returns, so registration order and event priorities cannot produce a stale result.
     * Verified against the 26.1.2 release source:
     * https://github.com/rfresh2/XaeroPlus/tree/5bb9236657d7165f4b8c17f92beb88243e3e7276/common/src/main/java/xaeroplus
     */
    @EventHandler
    public void onChunkData(ChunkDataEvent event) {
        if (!listening || failure != null || event.seenChunk()) return;
        try {
            var chunk = event.chunk();
            var level = chunk.getLevel();
            if (level != Minecraft.getInstance().level) return;
            if (pending.size() == MAX_PENDING) pending.removeFirst();
            pending.addLast(new Pending(chunk.getPos().x(), chunk.getPos().z(), level, System.nanoTime(),
                selectedDetection != Detection.NON_FRESH && oldChunks.isEnabled(),
                paletteChunks.isEnabled()));
        } catch (LinkageError | RuntimeException e) {
            failure = "XaeroPlus chunk events are incompatible with Trail Hunter (" + e.getClass().getSimpleName() + ").";
            pending.clear();
        }
    }

    @Override
    public List<Chunk> drain() {
        if (!listening || failure != null || pending.isEmpty()) return List.of();
        var level = Minecraft.getInstance().level;
        if (level == null) {
            pending.clear();
            return List.of();
        }
        try {
            long now = System.nanoTime();
            var chunks = new ArrayList<Chunk>();
            int count = pending.size();
            for (int i = 0; i < count; i++) {
                Pending chunk = pending.removeFirst();
                // Level identity prevents old cache data crossing a server reconnect or dimension change.
                if (chunk.level != level) continue;
                var dimension = level.dimension();
                boolean useOld = chunk.oldEnabled && oldChunks.isEnabled();
                boolean usePalette = chunk.paletteEnabled && paletteChunks.isEnabled();
                var classification = read(chunk.x, chunk.z, dimension, useOld, usePalette);
                // A positive EITHER result is sufficient, even while the other cache is still starting.
                if (!classification.selectedPositive(selectedDetection)
                    && !classification.selectedComplete(selectedDetection, useOld, usePalette)
                    && now - chunk.receivedAt < MAX_WAIT_NANOS) {
                    // Preserve receipt order, including across drains. Cache startup must not make younger chunks overtake.
                    pending.addFirst(chunk);
                    break;
                }
                var result = classification.chunk(chunk.x, chunk.z, dimension, selectedDetection,
                    TimeUnit.NANOSECONDS.toMillis(chunk.receivedAt));
                if (result != null) chunks.add(result);
            }
            return chunks;
        } catch (LinkageError | RuntimeException e) {
            failCacheRead(e);
            return List.of();
        }
    }

    @Override
    public List<Chunk> sample(int centerChunkX, int centerChunkZ, int radius) {
        if (!listening || failure != null) return List.of();
        var level = Minecraft.getInstance().level;
        if (level == null) return List.of();
        try {
            var dimension = level.dimension();
            boolean useOld = selectedDetection != Detection.NON_FRESH && oldChunks.isEnabled();
            boolean usePalette = paletteChunks.isEnabled();
            int boundedRadius = Math.clamp(radius, 0, MAX_SAMPLE_RADIUS);
            var chunks = new ArrayList<Chunk>();
            for (int dx = -boundedRadius; dx <= boundedRadius; dx++) {
                for (int dz = -boundedRadius; dz <= boundedRadius; dz++) {
                    long x = (long) centerChunkX + dx;
                    long z = (long) centerChunkZ + dz;
                    if (x < Integer.MIN_VALUE || x > Integer.MAX_VALUE || z < Integer.MIN_VALUE || z > Integer.MAX_VALUE) continue;
                    var classification = read((int) x, (int) z, dimension, useOld, usePalette);
                    var chunk = classification.chunk((int) x, (int) z, dimension, selectedDetection, 0);
                    if (chunk != null) chunks.add(chunk);
                }
            }
            return chunks;
        } catch (LinkageError | RuntimeException e) {
            failCacheRead(e);
            return List.of();
        }
    }

    private XaeroPlusChunkClassification read(int x, int z, ResourceKey<Level> dimension,
                                             boolean useOld, boolean usePalette) {
        return new XaeroPlusChunkClassification(
            useOld && oldChunks.isOldChunk(x, z, dimension),
            useOld && oldChunks.isOldChunkInverse(x, z, dimension),
            usePalette && paletteChunks.isInverseNewChunk(x, z, dimension),
            usePalette && paletteChunks.isNewChunk(x, z, dimension));
    }

    private void failCacheRead(Throwable error) {
        failure = "XaeroPlus chunk caches are incompatible with Trail Hunter (" + error.getClass().getSimpleName() + ").";
        pending.clear();
    }

    private record Pending(int x, int z, Level level, long receivedAt, boolean oldEnabled, boolean paletteEnabled) {}
}
