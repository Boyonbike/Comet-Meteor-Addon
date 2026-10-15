package dev.tntcoating.modules;

import dev.tntcoating.TntCoatingAddon;
import dev.tntcoating.compat.XaeroBridge;
import meteordevelopment.meteorclient.MeteorClient;
import meteordevelopment.meteorclient.events.meteor.KeyInputEvent;
import meteordevelopment.meteorclient.events.meteor.MouseClickEvent;
import meteordevelopment.meteorclient.settings.KeybindSetting;
import meteordevelopment.meteorclient.settings.Setting;
import meteordevelopment.meteorclient.settings.SettingGroup;
import meteordevelopment.meteorclient.systems.modules.Module;
import meteordevelopment.meteorclient.systems.modules.Modules;
import meteordevelopment.meteorclient.systems.modules.render.WaypointsModule;
import meteordevelopment.meteorclient.systems.waypoints.Waypoint;
import meteordevelopment.meteorclient.systems.waypoints.Waypoints;
import meteordevelopment.meteorclient.utils.misc.Keybind;
import meteordevelopment.meteorclient.utils.misc.input.KeyAction;
import meteordevelopment.meteorclient.utils.player.PlayerUtils;
import meteordevelopment.meteorclient.utils.render.NametagUtils;
import meteordevelopment.orbit.EventHandler;
import meteordevelopment.orbit.EventPriority;
import net.minecraft.client.Camera;
import net.minecraft.core.BlockPos;
import net.minecraft.util.Mth;
import net.minecraft.world.phys.Vec3;
import org.joml.Vector3d;
import org.joml.Vector3fc;
import org.lwjgl.glfw.GLFW;

public final class WaypointDelete extends Module {
    // Xaero's highlight is only refreshed while it draws in-world waypoints. If you look this far away from the one it
    // last highlighted, it is no longer showing details, so it is not deleted.
    private static final double XAERO_MAX_ANGLE = Math.toRadians(10);
    private final SettingGroup general = settings.getDefaultGroup();
    private final Setting<Keybind> key = general.add(new KeybindSetting.Builder().name("delete-key").description("Deletes the Meteor or Xaero waypoint whose name and distance are showing because you are looking at it.").defaultValue(Keybind.fromButton(GLFW.GLFW_MOUSE_BUTTON_MIDDLE)).build());
    private XaeroBridge xaero;
    private boolean xaeroChecked;

    public WaypointDelete() { super(TntCoatingAddon.CATEGORY, "waypoint-delete", "Delete the Meteor or Xaero waypoint you are looking at with a key or mouse button."); }

    @Override public void onActivate() { xaero = null; xaeroChecked = false; }

    // Highest priority, so a handled press can be cancelled before Meteor's other middle-click modules and vanilla pick-block see it.
    @EventHandler(priority = EventPriority.HIGHEST) private void onMouse(MouseClickEvent event) {
        if (event.action == KeyAction.Press && key.get().matches(event.input) && deleteLookedAt()) event.cancel();
    }

    @EventHandler(priority = EventPriority.HIGHEST) private void onKey(KeyInputEvent event) {
        if (event.action == KeyAction.Press && key.get().matches(event.input) && deleteLookedAt()) event.cancel();
    }

    /** True when a waypoint was deleted; the press is then not passed on. */
    private boolean deleteLookedAt() {
        if (mc.gui.screen() != null || mc.player == null || mc.gui.hud.isHidden()) return false;
        Camera camera = mc.gameRenderer.mainCamera();
        Vector3fc forward = camera.forwardVector();
        Vec3 eye = camera.position(), look = new Vec3(forward.x(), forward.y(), forward.z());
        Waypoint meteor = meteorTarget();
        XaeroBridge.Target target = xaeroTarget();
        if (target != null && angle(eye, look, target.point()) > XAERO_MAX_ANGLE) target = null;
        if (target != null && (meteor == null || angle(eye, look, target.point()) < angle(eye, look, point(meteor)))) {
            if (target.mirrorOf() == null) {
                try {
                    target.delete().run();
                } catch (LinkageError | RuntimeException e) {
                    MeteorClient.LOG.error("Deleting a Xaero waypoint failed", e);
                    error("Xaero didn't delete %s (%s). See the log.", target.name(), e);
                    return false;
                }
                info("Deleted Xaero waypoint %s at %d, %d, %d.", target.name(), target.x(), target.y(), target.z());
                return true;
            }
            meteor = find(target.mirrorOf());
        }
        if (meteor == null) return false;
        Waypoints.get().remove(meteor);
        BlockPos pos = meteor.pos.get();
        info("Deleted Meteor waypoint %s at %d, %d, %d (%s).", meteor.name.get(), pos.getX(), pos.getY(), pos.getZ(), meteor.dimension.get());
        return true;
    }

    /**
     * The waypoint Meteor's Waypoints module shows a name and distance for, using that module's own checks (see
     * WaypointsModule.onRender2D). Several can show text at once; the one closest to the crosshair wins.
     */
    private Waypoint meteorTarget() {
        WaypointsModule module = Modules.get().get(WaypointsModule.class);
        if (module == null || !module.isActive()) return null;
        Setting<?> fadeSetting = module.settings.get("waypoint-fade-distance");
        int fade = fadeSetting != null && fadeSetting.get() instanceof Integer i ? i : 0;
        Vector3d center = new Vector3d(mc.getWindow().getWidth() / 2.0, mc.getWindow().getHeight() / 2.0, 0);
        Waypoint best = null;
        double bestDistance = module.textRenderDistance.get();
        for (Waypoint waypoint : Waypoints.get()) {
            if (!waypoint.visible.get() || !Waypoints.checkDimension(waypoint)) continue;
            Vec3 point = point(waypoint);
            double distance = PlayerUtils.distanceToCamera(point.x, point.y, point.z);
            if (distance > waypoint.maxVisible.get()) continue;
            // Meteor fades waypoints out as you get close and stops drawing them entirely here.
            if (distance < fade && (distance - fade / 2.0) / (fade / 2.0) < 0.01) continue;
            Vector3d screen = new Vector3d(point.x, point.y, point.z);
            if (!NametagUtils.to2D(screen, waypoint.scale.get() - 0.2)) continue;
            double fromCrosshair = screen.distance(center);
            if (fromCrosshair <= bestDistance) {
                best = waypoint;
                bestDistance = fromCrosshair;
            }
        }
        return best;
    }

    private XaeroBridge.Target xaeroTarget() {
        try {
            if (!xaeroChecked) {
                xaeroChecked = true;
                xaero = XaeroBridge.create();
            }
            return xaero == null ? null : xaero.lookedAt();
        } catch (LinkageError | RuntimeException e) {
            MeteorClient.LOG.error("Reading the highlighted Xaero waypoint failed", e);
            error("Can't read Xaero's waypoints with this Xaero version (%s); only Meteor waypoints can be deleted. See the log.", e);
            xaero = null;
            return null;
        }
    }

    /** Where Meteor draws the waypoint in the current dimension. */
    private static Vec3 point(Waypoint waypoint) {
        BlockPos pos = waypoint.getPos();
        return new Vec3(pos.getX() + 0.5, pos.getY(), pos.getZ() + 0.5);
    }

    private static double angle(Vec3 eye, Vec3 look, Vec3 point) {
        return Math.acos(Mth.clamp(look.dot(point.subtract(eye).normalize()), -1, 1));
    }

    private static Waypoint find(String id) {
        for (Waypoint waypoint : Waypoints.get()) if (waypoint.uuid.toString().equals(id)) return waypoint;
        return null;
    }
}
