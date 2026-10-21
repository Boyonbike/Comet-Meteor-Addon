package dev.tntcoating.modules;

import dev.tntcoating.TntCoatingAddon;
import dev.tntcoating.core.DubCount;
import java.util.List;
import meteordevelopment.meteorclient.events.game.GameLeftEvent;
import meteordevelopment.meteorclient.events.world.TickEvent;
import meteordevelopment.meteorclient.systems.modules.Module;
import meteordevelopment.meteorclient.utils.Utils;
import meteordevelopment.orbit.EventHandler;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.LevelChunk;

/** Ordinary chest blocks in loaded chunks, shown as dubs (double chests). A port of Majkey's DubCounter addon. */
public final class DubCounter extends Module {
    private int chests, chunks, ticks;
    private String dubs = DubCount.format(0);

    public DubCounter() {
        super(TntCoatingAddon.CATEGORY, "dub-counter", "Counts ordinary chest blocks in loaded chunks, divided by two.");
    }

    @Override public void onActivate() {
        ticks = 0;
        count();
    }

    @Override public void onDeactivate() { clear(); }
    @EventHandler private void onGameLeft(GameLeftEvent event) { clear(); }

    @EventHandler private void onTick(TickEvent.Post event) {
        // Five counts a second, like Fast Storage ESP. Each reads only the chunks' block entity maps, allocating nothing per chest.
        if (++ticks % 4 == 0) count();
    }

    private void count() {
        if (mc.level == null || mc.player == null) {
            clear();
            return;
        }
        // Every loaded chunk has its own slot and every position at most one block entity, so nothing is counted twice.
        int chestBlocks = 0, loaded = 0;
        for (ChunkAccess chunk : Utils.chunks()) {
            if (!(chunk instanceof LevelChunk levelChunk)) continue;
            loaded++;
            chestBlocks += chestBlocks(levelChunk);
        }
        set(chestBlocks, loaded);
    }

    /** Ordinary chest blocks in one loaded chunk. */
    public static int chestBlocks(LevelChunk chunk) {
        int chestBlocks = 0;
        for (BlockEntity entity : chunk.getBlockEntities().values()) {
            // Trapped and copper chests are other blocks and do not count.
            if (!entity.isRemoved() && entity.getBlockState().is(Blocks.CHEST)) chestBlocks++;
        }
        return chestBlocks;
    }

    private void clear() { set(0, 0); }

    private void set(int chestBlocks, int loaded) {
        if (chestBlocks != chests) dubs = DubCount.format(chestBlocks);
        chests = chestBlocks;
        chunks = loaded;
    }

    /** The HUD element's lines, or null while the module is off. */
    public List<String> hudLines() {
        return isActive() ? List.of("Dubs: " + dubs, chests + " chests / " + chunks + " chunks") : null;
    }

    @Override public String getInfoString() { return dubs; }
}
