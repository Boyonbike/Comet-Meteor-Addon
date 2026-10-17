package dev.tntcoating.core;

/** One-pass equivalent of Scaffold's stable sort followed by taking the first support. */
public final class ScaffoldSupportSelection {
    private final int targetX, targetY, targetZ;
    private double bestDistance = Double.POSITIVE_INFINITY;
    private int x, y, z;
    private boolean found;

    public ScaffoldSupportSelection(int targetX, int targetY, int targetZ) {
        this.targetX = targetX;
        this.targetY = targetY;
        this.targetZ = targetZ;
    }

    public void offer(int x, int y, int z) {
        double dx = (double) x - targetX, dy = (double) y - targetY, dz = (double) z - targetZ;
        double distance = dx * dx + dy * dy + dz * dz;
        // Strict comparison retains the original x/z/y iteration order for ties.
        if (distance < bestDistance) {
            found = true;
            bestDistance = distance;
            this.x = x;
            this.y = y;
            this.z = z;
        }
    }

    public boolean found() { return found; }
    public int x() { return x; }
    public int y() { return y; }
    public int z() { return z; }
}
