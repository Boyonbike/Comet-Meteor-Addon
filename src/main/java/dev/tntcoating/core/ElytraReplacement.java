package dev.tntcoating.core;

/** When a worn elytra is nearly broken and whether a spare can still replace it. Durability here is what is left, not the damage taken. */
public final class ElytraReplacement {
    private ElytraReplacement() {}

    /**
     * True when the worn elytra is at or below the threshold. An elytra stops gliding with 1 durability left, so any
     * threshold of at least 1 also catches a broken one.
     *
     * @param worn remaining durability of the worn elytra; -1 when no elytra is worn
     */
    public static boolean due(int worn, int threshold) {
        return worn >= 0 && worn <= threshold;
    }

    /**
     * Index of the spare with the most durability left, or -1 when none is above the threshold: swapping one of those in
     * would only be due for swapping out again.
     *
     * @param spares remaining durability for each inventory slot, -1 for a slot without an elytra
     */
    public static int best(int[] spares, int threshold) {
        int best = -1;
        for (int i = 0; i < spares.length; i++) {
            if (spares[i] > threshold && (best < 0 || spares[i] > spares[best])) best = i;
        }
        return best;
    }

    /** Counts elytra with strictly more remaining durability than the configured swap threshold. */
    public static int countUsable(int[] remaining, int threshold) {
        int count = 0;
        for (int durability : remaining) {
            if (durability > threshold) count++;
        }
        return count;
    }

    /**
     * True when the worn elytra is at or below the threshold and no spare is left to replace it.
     *
     * @param spareThreshold a spare needs more durability left than this to count, as in {@link #best}
     */
    public static boolean stranded(int worn, int[] spares, int threshold, int spareThreshold) {
        return due(worn, threshold) && best(spares, spareThreshold) < 0;
    }
}
