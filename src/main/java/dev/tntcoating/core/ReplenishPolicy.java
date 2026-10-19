package dev.tntcoating.core;

import java.util.function.IntPredicate;
import java.util.function.IntUnaryOperator;

/** Source ordering and refill thresholds, independent of inventory packet handling. */
public final class ReplenishPolicy {
    public enum StackPreference { FirstMatch, FullStacks, SmallStacks }

    private ReplenishPolicy() {}

    public static boolean needsRefill(int count, int maxCount, int threshold, boolean smart) {
        return count > 0 && count < maxCount && count <= (smart ? 1 : threshold);
    }

    public static int chooseSource(int start, int end, StackPreference preference,
                                   IntPredicate eligible, IntUnaryOperator count) {
        int best = -1;
        for (int slot = start; slot < end; slot++) {
            if (!eligible.test(slot) || count.applyAsInt(slot) <= 0) continue;
            if (preference == StackPreference.FirstMatch) return slot;
            if (best < 0 || (preference == StackPreference.FullStacks
                ? count.applyAsInt(slot) > count.applyAsInt(best)
                : count.applyAsInt(slot) < count.applyAsInt(best))) best = slot;
        }
        return best;
    }
}
