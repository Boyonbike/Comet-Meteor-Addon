package dev.tntcoating.core;

/** Inventory selection independent of Minecraft's click and flight handling. */
public final class SmartElytraSwap {
    public record Candidate(int slot, boolean glider, int durability, double armor, double toughness) {}

    private SmartElytraSwap() {}

    /** Prefer durable gliders, or chestplates with the most armor, toughness, then durability. */
    public static int best(Iterable<Candidate> inventory, boolean glider) {
        Candidate best = null;
        for (Candidate candidate : inventory) {
            if (candidate.glider() != glider || candidate.durability() <= (glider ? 1 : 0)) continue;
            if (best == null || better(candidate, best, glider)) best = candidate;
        }
        return best == null ? -1 : best.slot();
    }

    private static boolean better(Candidate candidate, Candidate current, boolean glider) {
        if (!glider) {
            int armor = Double.compare(candidate.armor(), current.armor());
            if (armor != 0) return armor > 0;
            int toughness = Double.compare(candidate.toughness(), current.toughness());
            if (toughness != 0) return toughness > 0;
        }
        return candidate.durability() > current.durability();
    }
}
