package dev.tntcoating.modules;

import dev.tntcoating.TntCoatingAddon;
import dev.tntcoating.mixin.MinecraftAccessor;
import meteordevelopment.meteorclient.events.world.TickEvent;
import meteordevelopment.meteorclient.settings.BoolSetting;
import meteordevelopment.meteorclient.settings.Setting;
import meteordevelopment.meteorclient.settings.SettingGroup;
import meteordevelopment.meteorclient.systems.modules.Module;
import meteordevelopment.meteorclient.utils.player.FindItemResult;
import meteordevelopment.meteorclient.utils.player.InvUtils;
import meteordevelopment.meteorclient.utils.player.Rotations;
import meteordevelopment.meteorclient.utils.player.SlotUtils;
import meteordevelopment.orbit.EventHandler;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.tags.ItemTags;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.animal.allay.Allay;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;

import java.util.HashMap;
import java.util.Map;
import java.util.function.Consumer;
import java.util.function.Predicate;

/** Shared targeting for the allay modules: the nearest wanted allay in reach, one at a time. */
public abstract class AllayModule extends Module {
    /** Vanilla's right-click delay, so right-clicks go no faster than a player clicking. */
    private static final int USE_DELAY = 4;
    /** Ticks an allay is left alone after use, so the server's answer arrives before it is tried again. */
    static final int REST_TICKS = 40;

    protected final SettingGroup general = settings.getDefaultGroup();
    private final Setting<Boolean> rotate = general.add(new BoolSetting.Builder().name("rotate")
        .description("Face each allay before using it.").defaultValue(true).build());

    // Allay entity IDs left alone until the given tick.
    private final Map<Integer, Integer> resting = new HashMap<>();
    private ClientLevel world;
    protected int ticks;
    protected String status = "Starting";

    protected AllayModule(String name, String description) {
        super(TntCoatingAddon.CATEGORY, name, description);
    }

    @Override public String getInfoString() { return status; }
    @Override public void onActivate() { reset(); }
    @Override public void onDeactivate() { reset(); }

    /** Clears per-world state; entity IDs are only meaningful within one world. */
    protected void reset() {
        resting.clear();
        world = null;
        ticks = 0;
        status = "Starting";
    }

    protected abstract void tick();

    @EventHandler private void onTick(TickEvent.Pre event) {
        if (mc.player == null || mc.level == null || mc.gameMode == null) return;
        if (world != mc.level) { reset(); world = mc.level; }
        ticks++;
        resting.values().removeIf(until -> until <= ticks);
        if (mc.player.isAlive()) tick();
    }

    static boolean isShulker(ItemStack stack) {
        return stack.is(ItemTags.SHULKER_BOXES);
    }

    /** The nearest living allay that passes {@code wanted} and is not resting. */
    protected Allay nearest(Predicate<Allay> wanted) {
        Allay best = null;
        double bestDistance = Double.MAX_VALUE;
        for (Entity entity : mc.level.entitiesForRendering()) {
            if (!(entity instanceof Allay allay) || !allay.isAlive() || resting.containsKey(allay.getId()) || !wanted.test(allay)) continue;
            double distance = allay.distanceToSqr(mc.player);
            if (distance < bestDistance) {
                best = allay;
                bestDistance = distance;
            }
        }
        return best;
    }

    /** Whether the player can see any of the allay past the blocks in between, as a player needs to click it. */
    protected boolean visible(Allay allay) {
        return visiblePoint(allay) != null;
    }

    /** The middle of the allay if nothing blocks the view to it, else the first visible corner, else null. */
    private Vec3 visiblePoint(Allay allay) {
        Vec3 eye = mc.player.getEyePosition();
        AABB box = allay.getBoundingBox().deflate(.05);
        Vec3 center = box.getCenter();
        if (clear(eye, center)) return center;
        for (int i = 0; i < 8; i++) {
            Vec3 corner = new Vec3((i & 1) == 0 ? box.minX : box.maxX, (i & 2) == 0 ? box.minY : box.maxY, (i & 4) == 0 ? box.minZ : box.maxZ);
            if (clear(eye, corner)) return corner;
        }
        return null;
    }

    private boolean clear(Vec3 from, Vec3 to) {
        return mc.level.clip(new ClipContext(from, to, ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, mc.player)).getType() == HitResult.Type.MISS;
    }

    /**
     * Faces a visible point on the allay, then runs the action with a point still visible from there, if the allay is
     * still there and in view.
     */
    protected void aim(Allay allay, int restTicks, Consumer<Vec3> action) {
        Vec3 target = visiblePoint(allay);
        if (target == null) return;
        if (restTicks > 0) resting.put(allay.getId(), ticks + restTicks);
        ClientLevel level = world;
        Runnable checked = () -> {
            if (!isActive() || mc.player == null || mc.gameMode == null || mc.level != level || !allay.isAlive()) return;
            Vec3 point = visiblePoint(allay);
            if (point != null) action.accept(point);
        };
        // A passenger sends no movement packets, so Meteor would never run the rotation callback.
        if (!rotate.get() || mc.player.isPassenger()) checked.run();
        else Rotations.rotate(Rotations.getYaw(target), Rotations.getPitch(target), 50, checked);
    }

    /**
     * Vanilla's right-click delay is shared, so allay right-clicks and your own together go no faster than one
     * right-click every four ticks.
     */
    boolean canRightClick() {
        return ((MinecraftAccessor) mc).comet$getRightClickDelay() <= 0;
    }

    void rightClicked() {
        ((MinecraftAccessor) mc).comet$setRightClickDelay(USE_DELAY);
    }

    /** The item in the offhand or hotbar, else the first one in the main inventory. */
    FindItemResult findItem(Predicate<ItemStack> item) {
        FindItemResult held = InvUtils.findInHotbar(item);
        return held.found() ? held : InvUtils.find(item, SlotUtils.MAIN_START, SlotUtils.MAIN_END);
    }

    /**
     * Runs the action with the found item in the hand it is given. A hotbar item is selected and the selection
     * restored; a main-inventory item is swapped into the held slot and back, like Meteor's Middle Click Extra.
     */
    void holding(FindItemResult found, Consumer<InteractionHand> action) {
        if (found.isOffhand()) {
            action.accept(InteractionHand.OFF_HAND);
            return;
        }
        int selected = mc.player.getInventory().getSelectedSlot();
        boolean hotbar = found.isHotbar();
        if (hotbar) InvUtils.swap(found.slot(), true);
        else InvUtils.quickSwap().fromId(selected).to(found.slot());
        try {
            action.accept(InteractionHand.MAIN_HAND);
        } finally {
            if (hotbar) InvUtils.swapBack();
            else InvUtils.quickSwap().fromId(selected).to(found.slot());
        }
    }
}
