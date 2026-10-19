package dev.tntcoating.core;

import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import net.minecraft.core.BlockPos;
import net.minecraft.core.SectionPos;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;

import java.util.function.Function;
import java.util.function.Predicate;

/** Live TNT checks: inactive circuits can power up in response to placement. */
public final class TntRedstoneSafety {
    public static final int BUFFER = 2;

    /** What a 16-block chunk section can say about redstone without reading every block. */
    public enum Section {
        /** Loaded, and its block palette holds no redstone component. */
        CLEAR,
        /** Loaded, and may hold a component: its blocks must be checked. */
        MAYBE,
        /** Not loaded: never treat it as clear of redstone. */
        UNKNOWN
    }

    @FunctionalInterface
    public interface Sections {
        Section at(int sectionX, int sectionY, int sectionZ);
    }

    /** Remembers section answers until cleared; clear it whenever blocks or chunks may have changed. */
    public static final class SectionMemo implements Sections {
        private final Sections source;
        private final Long2ObjectOpenHashMap<Section> known = new Long2ObjectOpenHashMap<>();

        public SectionMemo(Sections source) { this.source = source; }

        @Override public Section at(int sectionX, int sectionY, int sectionZ) {
            long key = SectionPos.asLong(sectionX, sectionY, sectionZ);
            Section section = known.get(key);
            if (section == null) {
                section = source.at(sectionX, sectionY, sectionZ);
                known.put(key, section);
            }
            return section;
        }

        public void clear() { known.clear(); }
    }

    private TntRedstoneSafety() {}

    /** A null loadedState result means unknown terrain; never treat it as clear of redstone. */
    public static boolean canPlace(boolean tnt, BlockPos target, Predicate<BlockPos> outsideBuildHeight,
                                   Function<BlockPos, BlockState> loadedState, Predicate<BlockPos> powered) {
        return canPlace(tnt, target, outsideBuildHeight, loadedState, powered, (x, y, z) -> Section.MAYBE);
    }

    /**
     * As above, reading blocks only in sections that may hold a component. Most terrain has no redstone, so the
     * 125-block buffer usually costs a few palette checks rather than 125 block reads.
     */
    public static boolean canPlace(boolean tnt, BlockPos target, Predicate<BlockPos> outsideBuildHeight,
                                   Function<BlockPos, BlockState> loadedState, Predicate<BlockPos> powered,
                                   Sections sections) {
        if (!tnt) return true;
        if (powered.test(target)) return false;
        Section section = null;
        int sectionX = 0, sectionY = 0, sectionZ = 0;
        // Include the target, diagonals and vertical offsets, even through intervening terrain.
        for (BlockPos pos : BlockPos.betweenClosed(target.offset(-BUFFER, -BUFFER, -BUFFER),
                                                  target.offset(BUFFER, BUFFER, BUFFER))) {
            if (outsideBuildHeight.test(pos)) continue;
            int x = pos.getX() >> 4, y = pos.getY() >> 4, z = pos.getZ() >> 4;
            if (section == null || x != sectionX || y != sectionY || z != sectionZ) {
                section = sections.at(x, y, z);
                sectionX = x;
                sectionY = y;
                sectionZ = z;
            }
            if (section == Section.UNKNOWN) return false;
            if (section == Section.CLEAR) continue;
            BlockState state = loadedState.apply(pos);
            if (state == null || component(state)) return false;
        }
        return true;
    }

    public static boolean component(BlockState state) {
        // Inspect capabilities, not the current power value. POWERED also covers tripwire
        // and activator/powered rails, which need not emit a signal themselves.
        return state.isSignalSource() || state.hasProperty(BlockStateProperties.POWER)
            || state.hasProperty(BlockStateProperties.POWERED);
    }
}
