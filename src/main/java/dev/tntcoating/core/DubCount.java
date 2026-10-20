package dev.tntcoating.core;

/** Dubs are double chests: two ordinary chest blocks make one, so an unpaired chest counts as half. */
public final class DubCount {
    private DubCount() {}

    /** Chest blocks as dubs, for example 25 blocks as "12.5". */
    public static String format(int chestBlocks) {
        return chestBlocks / 2 + (chestBlocks % 2 == 0 ? "" : ".5");
    }
}
