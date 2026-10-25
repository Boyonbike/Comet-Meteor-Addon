package dev.tntcoating.core.scaffold;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.function.BiConsumer;
import java.util.function.LongFunction;
import java.util.function.LongPredicate;

/** Client-thread authoritative overlay: packet receipt is not proof that a retained prediction was applied.
 * Complete state equality is required; neither time, a local prediction nor a changed property resolves it.
 */
public final class ScaffoldAuthority<S> {
    private static final int CAPACITY = 256;
    private final Map<Long, S> pending = new LinkedHashMap<>();
    private boolean overflow;

    public void receive(long position, S expected, LongFunction<S> live, BiConsumer<Long, S> applied) {
        Objects.requireNonNull(expected);
        if (expected.equals(live.apply(position))) {
            pending.remove(position);
            applied.accept(position, expected);
        } else if (pending.containsKey(position) || pending.size() < CAPACITY) {
            pending.put(position, expected);
        } else {
            // The omitted position cannot be identified later without unbounded storage. Refuse every anchor
            // until world departure; never evict an older unresolved position or silently trust the new one.
            overflow = true;
        }
    }
    public void reconcile(LongFunction<S> live, BiConsumer<Long, S> applied) {
        var iterator = pending.entrySet().iterator();
        while (iterator.hasNext()) {
            var entry = iterator.next();
            if (!entry.getValue().equals(live.apply(entry.getKey()))) continue;
            iterator.remove();
            applied.accept(entry.getKey(), entry.getValue());
        }
    }
    /** A fresh authoritative chunk replacement supersedes prior retained packet states in that chunk. */
    public void replaceRegion(LongPredicate region, LongFunction<S> live, BiConsumer<Long, S> applied) {
        var iterator = pending.entrySet().iterator();
        while (iterator.hasNext()) {
            var entry = iterator.next();
            if (!region.test(entry.getKey())) continue;
            S state = live.apply(entry.getKey());
            if (state == null) continue;
            iterator.remove();
            applied.accept(entry.getKey(), state);
        }
    }
    public boolean blocked(long position) { return overflow || pending.containsKey(position); }
    public boolean overflowed() { return overflow; }
    public int size() { return pending.size(); }
    public void clear() { pending.clear(); overflow = false; }
}
