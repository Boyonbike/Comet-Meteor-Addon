package dev.tntcoating.compat;

import dev.tntcoating.core.WaypointMirror;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.world.phys.Vec3;
import java.util.List;
import java.util.Map;

/**
 * Xaero's Minimap, which is optional. Only {@link XaeroMinimapBridge} refers to Xaero's classes, and {@link #create()} loads
 * it only once the mod is known to be installed. Xaero has no stable API, so callers should treat a {@link LinkageError}
 * from any method as "this Xaero version is not supported".
 */
public interface XaeroBridge {
    /** Null when neither Xaero's Minimap nor Better PvP (which contains it) is installed. */
    static XaeroBridge create() {
        FabricLoader loader = FabricLoader.getInstance();
        return loader.isModLoaded("xaerominimap") || loader.isModLoaded("xaerobetterpvp") ? new XaeroMinimapBridge() : null;
    }

    /** Shows exactly these markers on the minimap and world map. Returns false while Xaero has no world loaded. */
    boolean show(Map<WaypointMirror.Dim, List<WaypointMirror.Marker>> plan);

    /** Removes every marker {@link #show} added. */
    void clear();

    /**
     * The in-world waypoint Xaero is currently showing a name and distance for, if it is one of the player's own waypoints
     * or a marker added by {@link #show}. Server waypoints and other mods' waypoints give null.
     */
    Target lookedAt();

    /**
     * @param mirrorOf the Meteor waypoint's id when this is a marker added by {@link #show}, otherwise null
     * @param delete removes the player's own waypoint from Xaero; null for a marker added by {@link #show}
     */
    record Target(String name, int x, int y, int z, String mirrorOf, Runnable delete) {
        /** Where Xaero draws it. */
        public Vec3 point() {
            return new Vec3(x + 0.5, y + 1, z + 0.5);
        }
    }
}
