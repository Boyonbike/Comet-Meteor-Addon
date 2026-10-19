package dev.tntcoating.core;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.Map;
import java.util.Set;

/**
 * Bounded reservations for TNT columns a player deliberately opens as a route. Mining intent is
 * short-lived; after removal the column remains protected until the player traverses it or the
 * approach timeout expires.
 */
public final class PassageClearance {
    public static final int MINING_TICKS = 40;
    public static final int APPROACH_TICKS = 100;

    public record Cell(int x, int y, int z) {}
    private record Column(int x, int z) {}

    private static final class Entry {
        int feetY;
        int lastActivity;
        boolean removed;
        boolean entered;
        final Set<Cell> mining = new HashSet<>();

        Entry(int feetY, int tick) {
            this.feetY = feetY;
            this.lastActivity = tick;
        }
    }

    private final Map<Column, Entry> entries = new HashMap<>();

    public void begin(Cell target, int feetY, int tick) {
        Column column = new Column(target.x, target.z);
        Entry entry = entries.computeIfAbsent(column, ignored -> new Entry(feetY, tick));
        entry.feetY = feetY;
        entry.lastActivity = tick;
        entry.mining.add(target);
    }

    public void removed(Cell target, int tick) {
        Entry entry = entries.get(new Column(target.x, target.z));
        if (entry == null || !entry.mining.remove(target)) return;
        entry.removed = true;
        entry.lastActivity = tick;
    }

    public boolean protects(Cell cell) {
        Entry entry = entries.get(new Column(cell.x, cell.z));
        return entry != null && (cell.y == entry.feetY || cell.y == entry.feetY + 1);
    }

    /** Observe the player's centre column and expire completed or abandoned reservations. */
    public void observe(double playerX, double playerZ, int tick) {
        Iterator<Map.Entry<Column, Entry>> iterator = entries.entrySet().iterator();
        while (iterator.hasNext()) {
            Map.Entry<Column, Entry> current = iterator.next();
            Column column = current.getKey();
            Entry entry = current.getValue();
            boolean occupied = playerX >= column.x && playerX < column.x + 1
                && playerZ >= column.z && playerZ < column.z + 1;
            if (entry.removed && occupied) {
                entry.entered = true;
                entry.lastActivity = tick;
            } else if (entry.removed && entry.entered) {
                iterator.remove();
            } else {
                int timeout = entry.removed ? APPROACH_TICKS : MINING_TICKS;
                if (tick - entry.lastActivity > timeout) iterator.remove();
            }
        }
    }

    public int size() { return entries.size(); }
    public void clear() { entries.clear(); }
}
