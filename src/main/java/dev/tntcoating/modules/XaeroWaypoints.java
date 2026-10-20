package dev.tntcoating.modules;

import dev.tntcoating.TntCoatingAddon;
import dev.tntcoating.compat.XaeroBridge;
import dev.tntcoating.core.WaypointMirror;
import dev.tntcoating.core.WaypointMirror.Dim;
import meteordevelopment.meteorclient.MeteorClient;
import meteordevelopment.meteorclient.events.world.TickEvent;
import meteordevelopment.meteorclient.settings.BoolSetting;
import meteordevelopment.meteorclient.settings.Setting;
import meteordevelopment.meteorclient.settings.SettingGroup;
import meteordevelopment.meteorclient.systems.modules.Module;
import meteordevelopment.meteorclient.systems.waypoints.Waypoint;
import meteordevelopment.meteorclient.systems.waypoints.Waypoints;
import meteordevelopment.meteorclient.systems.waypoints.events.WaypointAddedEvent;
import meteordevelopment.meteorclient.systems.waypoints.events.WaypointRemovedEvent;
import meteordevelopment.meteorclient.utils.render.color.SettingColor;
import meteordevelopment.orbit.EventHandler;
import net.minecraft.core.BlockPos;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

public final class XaeroWaypoints extends Module {
    private final SettingGroup general = settings.getDefaultGroup();
    private final Setting<Boolean> minimap = general.add(new BoolSetting.Builder().name("show-on-minimap").description("Also show them on the minimap and as Xaero's in-world markers. Off shows them on the world map only.").defaultValue(true).build());
    private final Setting<Boolean> hidden = general.add(new BoolSetting.Builder().name("include-hidden").description("Also show waypoints that are hidden in Meteor.").defaultValue(false).build());
    private XaeroBridge xaero;
    private boolean dirty;
    private int ticks;
    private String status;

    public XaeroWaypoints() { super(TntCoatingAddon.CATEGORY, "xaero-waypoints", "Shows Meteor waypoints on Xaero's minimap and world map."); }

    @Override public String getInfoString() { return status; }
    @Override public void onActivate() { dirty = true; status = null; }
    @Override public void onDeactivate() {
        try {
            if (xaero != null) xaero.clear();
        } catch (LinkageError | RuntimeException e) {
            MeteorClient.LOG.error("Could not remove Meteor waypoints from Xaero's Minimap", e);
        }
        xaero = null;
    }

    @EventHandler private void added(WaypointAddedEvent event) { dirty = true; }
    @EventHandler private void removed(WaypointRemovedEvent event) { dirty = true; }

    @EventHandler private void tick(TickEvent.Post event) {
        // Meteor posts no event when a waypoint is edited, so edits are picked up by checking every half second.
        if (!dirty && ++ticks < 10) return;
        ticks = 0;
        dirty = false;
        try {
            if (xaero == null) xaero = XaeroBridge.create();
            if (xaero == null) { status = "Xaero's Minimap not installed"; return; }
            Map<Dim, List<WaypointMirror.Marker>> plan = WaypointMirror.plan(sources(), hidden.get(), minimap.get());
            status = xaero.show(plan) ? String.valueOf(plan.values().stream().flatMap(List::stream).map(WaypointMirror.Marker::id).distinct().count()) : "Waiting for Xaero";
        } catch (LinkageError | RuntimeException e) {
            MeteorClient.LOG.error("Showing Meteor waypoints on Xaero's Minimap failed", e);
            error("Xaero's Minimap didn't accept the waypoints (%s). Turning off; see the log.", e);
            toggle();
        }
    }

    private static List<WaypointMirror.Source> sources() {
        List<WaypointMirror.Source> sources = new ArrayList<>();
        for (Waypoint waypoint : Waypoints.get()) {
            BlockPos pos = waypoint.pos.get();
            SettingColor color = waypoint.color.get();
            Dim dim = switch (waypoint.dimension.get()) {
                case Overworld -> Dim.OVERWORLD;
                case Nether -> Dim.NETHER;
                case End -> Dim.END;
            };
            sources.add(new WaypointMirror.Source(waypoint.uuid.toString(), waypoint.name.get(), pos.getX(), pos.getY(), pos.getZ(),
                dim, waypoint.opposite.get(), waypoint.visible.get(), color.r << 16 | color.g << 8 | color.b));
        }
        return sources;
    }
}
