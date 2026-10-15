package dev.tntcoating.compat;

import dev.tntcoating.core.WaypointMirror;
import dev.tntcoating.core.WaypointMirror.Dim;
import dev.tntcoating.core.WaypointMirror.Marker;
import net.minecraft.resources.Identifier;
import net.minecraft.world.level.Level;
import xaero.common.HudMod;
import xaero.common.minimap.waypoints.Waypoint;
import xaero.hud.minimap.BuiltInHudModules;
import xaero.hud.minimap.element.render.world.MinimapElementWorldRendererHandler;
import xaero.hud.minimap.module.MinimapSession;
import xaero.hud.minimap.waypoint.WaypointColor;
import xaero.hud.minimap.waypoint.WaypointPurpose;
import xaero.hud.minimap.waypoint.WaypointVisibilityType;
import xaero.hud.minimap.waypoint.render.WaypointDeleter;
import xaero.hud.minimap.waypoint.set.WaypointSet;
import xaero.hud.minimap.waypoint.thirdparty.ThirdPartyWaypoints;
import xaero.hud.minimap.world.MinimapWorld;
import xaero.hud.minimap.world.container.MinimapWorldContainer;
import xaero.hud.minimap.world.container.MinimapWorldRootContainer;
import java.lang.reflect.Field;
import java.util.*;

/**
 * Markers are added as Xaero "third-party" waypoints, the mechanism Xaero itself uses for Waystones. They live in each
 * dimension's waypoint container, so the minimap and the world map (which draws the minimap's waypoints for whichever
 * dimension it is showing) both pick them up, and Xaero never writes them to its waypoint files.
 */
final class XaeroMinimapBridge implements XaeroBridge {
    private static final Identifier ORIGIN = Identifier.fromNamespaceAndPath("tnt-coating", "meteor_waypoints");
    private static final WaypointColor[] COLORS = WaypointColor.values();
    private static final int[] PALETTE = Arrays.stream(COLORS).mapToInt(WaypointColor::getHex).toArray();
    private final Map<Dim, ThirdPartyWaypoints> sets = new EnumMap<>(Dim.class);
    private final Map<Dim, List<Marker>> shown = new EnumMap<>(Dim.class);
    private MinimapWorldRootContainer root;
    private Field hovered;

    @Override
    public boolean show(Map<Dim, List<Marker>> plan) {
        MinimapSession session = BuiltInHudModules.MINIMAP.getCurrentSession();
        MinimapWorldRootContainer current = session == null || session.getWorldManager() == null ? null : session.getWorldManager().getAutoRootContainer();
        if (current == null) return false;
        if (current != root) {
            // New server or session: what was added before went away with the old containers.
            root = current;
            sets.clear();
            shown.clear();
        }
        for (Dim dim : Dim.values()) {
            List<Marker> markers = plan.getOrDefault(dim, List.of());
            if (markers.isEmpty() && !shown.containsKey(dim)) continue;
            ThirdPartyWaypoints set = set(session, dim);
            if (set == sets.get(dim) && markers.equals(shown.get(dim))) continue;
            Set<String> keep = new HashSet<>();
            for (Marker marker : markers) keep.add(marker.id());
            // remove() also forgets edits made to that waypoint in Xaero and saves Xaero's config, so it is only used for
            // waypoints that are gone. add() replaces a waypoint with the same id and keeps those edits.
            for (String id : new ArrayList<>(set.getWaypoints().keySet())) if (!keep.contains(id)) set.remove(id);
            for (Marker marker : markers) set.add(marker.id(), waypoint(marker));
            sets.put(dim, set);
            if (markers.isEmpty()) shown.remove(dim);
            else shown.put(dim, markers);
        }
        return true;
    }

    @Override
    public void clear() {
        // clearOrigin() keeps Xaero's per-waypoint edits, so they come back when the markers are shown again.
        if (root != null) for (MinimapWorldContainer container : root.getSubContainers()) container.getThirdPartyWaypointManager().clearOrigin(ORIGIN);
        root = null;
        sets.clear();
        shown.clear();
    }

    @Override
    public Target lookedAt() {
        MinimapSession session = BuiltInHudModules.MINIMAP.getCurrentSession();
        if (session == null || session.getWorldManager() == null) return null;
        MinimapWorld world = session.getWorldManager().getCurrentWorld();
        if (world == null || !(hovered(HudMod.INSTANCE.getMinimap().getWorldRendererHandler()) instanceof Waypoint waypoint) || waypoint.isDisabled()) return null;
        String name = waypoint.getLocalizedName();
        if (waypoint.isThirdParty()) {
            if (!ORIGIN.equals(waypoint.getThirdPartyOrigin())) return null;
            for (Map.Entry<String, Waypoint> entry : world.getContainer().getThirdPartyWaypointManager().get(ORIGIN).getWaypoints().entrySet()) {
                if (entry.getValue() == waypoint) return new Target(name, waypoint.getX(), waypoint.getY(), waypoint.getZ(), entry.getKey(), null);
            }
            return null;
        }
        for (WaypointSet set : world.getIterableWaypointSets()) {
            for (Waypoint own : set.getWaypoints()) {
                if (own != waypoint) continue;
                return new Target(name, waypoint.getX(), waypoint.getY(), waypoint.getZ(), null, () -> {
                    // Xaero's own deletion path: removes it from every set of the world and saves the world file.
                    WaypointDeleter deleter = session.getWaypointSession().getDeleter();
                    deleter.begin();
                    deleter.add(waypoint);
                    deleter.deleteCollected(session, world, true);
                });
            }
        }
        // A server waypoint, one added through Xaero's older custom-waypoint API, or one already deleted.
        return null;
    }

    private ThirdPartyWaypoints set(MinimapSession session, Dim dim) {
        String directory = session.getDimensionHelper().getDimensionDirectoryName(switch (dim) {
            case OVERWORLD -> Level.OVERWORLD;
            case NETHER -> Level.NETHER;
            case END -> Level.END;
        });
        return root.addSubContainer(root.getPath().resolve(directory)).getThirdPartyWaypointManager().get(ORIGIN);
    }

    private static Waypoint waypoint(Marker marker) {
        Waypoint waypoint = new Waypoint(marker.x(), marker.y(), marker.z(), marker.name(), marker.initials(),
            COLORS[WaypointMirror.nearestColor(marker.rgb(), PALETTE)], WaypointPurpose.NORMAL);
        // Xaero ties minimap and in-world visibility together; "world map local" hides the marker from both.
        waypoint.setVisibility(marker.minimap() ? WaypointVisibilityType.LOCAL : WaypointVisibilityType.WORLD_MAP_LOCAL);
        return waypoint;
    }

    /** The element Xaero highlighted (and so shows details for) in the last frame's in-world pass. Xaero keeps it private. */
    private Object hovered(MinimapElementWorldRendererHandler handler) {
        try {
            if (hovered == null) {
                hovered = MinimapElementWorldRendererHandler.class.getDeclaredField("previousClosestHoveredElement");
                hovered.setAccessible(true);
            }
            return hovered.get(handler);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException("Xaero's Minimap no longer exposes the highlighted waypoint", e);
        }
    }
}
