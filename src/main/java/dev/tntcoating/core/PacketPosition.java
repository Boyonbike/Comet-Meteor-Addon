package dev.tntcoating.core;

/** A position reconstructed from packet coordinates, independent of rendered entity positions. */
public record PacketPosition(double x, double y, double z) {
    private static final double MOVEMENT_SCALE = 4096.0;

    public PacketPosition {
        if (!Double.isFinite(x) || !Double.isFinite(y) || !Double.isFinite(z)) {
            throw new IllegalArgumentException("Packet coordinates must be finite");
        }
    }

    /** Decode relative movement using Minecraft's fixed-point position codec. */
    public PacketPosition relativeMove(int dx, int dy, int dz) {
        return new PacketPosition(moveAxis(x, dx), moveAxis(y, dy), moveAxis(z, dz));
    }

    /** Resolve teleport coordinates, whose axes may independently be absolute or relative. */
    public PacketPosition teleport(PacketPosition change, boolean relativeX, boolean relativeY,
                                   boolean relativeZ) {
        return new PacketPosition(relativeX ? x + change.x : change.x,
            relativeY ? y + change.y : change.y,
            relativeZ ? z + change.z : change.z);
    }

    private static double moveAxis(double base, int delta) {
        return delta == 0 ? base : (Math.round(base * MOVEMENT_SCALE) + delta) / MOVEMENT_SCALE;
    }
}
