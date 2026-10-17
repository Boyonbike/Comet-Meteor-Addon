package dev.tntcoating.mixin;

import dev.tntcoating.core.ScaffoldPlacementFeedback;
import dev.tntcoating.core.ScaffoldPlacementTracker;
import dev.tntcoating.core.ScaffoldSupportSelection;
import meteordevelopment.meteorclient.events.world.TickEvent;
import meteordevelopment.meteorclient.settings.BoolSetting;
import meteordevelopment.meteorclient.settings.Setting;
import meteordevelopment.meteorclient.settings.SettingGroup;
import meteordevelopment.meteorclient.systems.modules.Category;
import meteordevelopment.meteorclient.systems.modules.Module;
import meteordevelopment.meteorclient.systems.modules.movement.Scaffold;
import meteordevelopment.meteorclient.utils.player.FindItemResult;
import meteordevelopment.meteorclient.utils.player.InvUtils;
import meteordevelopment.meteorclient.utils.player.PlayerUtils;
import meteordevelopment.meteorclient.utils.world.BlockUtils;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/** Keeps Meteor's targeting/settings while removing the fallback support list and repeated slot round-trips. */
@Mixin(value = Scaffold.class, remap = false)
public abstract class ScaffoldMixin extends Module implements ScaffoldPlacementFeedback {
    @Shadow @Final private Setting<Boolean> fastTower;
    @Shadow @Final private Setting<Double> towerSpeed;
    @Shadow @Final private Setting<Boolean> whileMoving;
    @Shadow @Final private Setting<Boolean> onlyOnClick;
    @Shadow @Final private Setting<Boolean> autoSwitch;
    @Shadow @Final private Setting<Boolean> airPlace;
    @Shadow @Final private Setting<Double> aheadDistance;
    @Shadow @Final private Setting<Double> placeRange;
    @Shadow @Final private Setting<Double> radius;
    @Shadow @Final private Setting<Integer> blocksPerTick;
    @Shadow @Final private BlockPos.MutableBlockPos bp;

    @Shadow public abstract boolean towering();
    @Shadow private boolean validItem(ItemStack item, BlockPos position) { throw new AssertionError(); }
    @Shadow private boolean place(BlockPos position) { throw new AssertionError(); }

    @Unique private Setting<Boolean> comet$stickySwitch;
    @Unique private Setting<Boolean> comet$confirmationRetries;
    @Unique private final ScaffoldPlacementTracker comet$placements = new ScaffoldPlacementTracker();
    @Unique private ClientLevel comet$level;
    @Unique private long comet$tick;

    protected ScaffoldMixin(Category category, String name, String description) {
        super(category, name, description);
    }

    @Inject(method = "<init>", at = @At("RETURN"))
    private void comet$settings(CallbackInfo ci) {
        SettingGroup group = settings.createGroup("Comet", false);
        comet$stickySwitch = group.add(new BoolSetting.Builder()
            .name("sticky-switch")
            .description("Keeps the selected block slot after placement, reducing held-item swap packets.")
            .defaultValue(false)
            .visible(autoSwitch::get)
            .build());
        comet$confirmationRetries = group.add(new BoolSetting.Builder()
            .name("confirmation-retries")
            .description("Paces repeated attempts at unconfirmed or rejected cells; new bridge cells place immediately.")
            .defaultValue(true)
            .build());
    }

    @Override public void onActivate() { comet$reset(); }
    @Override public void onDeactivate() { comet$reset(); }

    @Unique private void comet$reset() {
        comet$placements.clear();
        comet$level = null;
        comet$tick = 0;
    }

    @Inject(method = "onTick", at = @At("HEAD"), cancellable = true)
    private void comet$efficientScaffold(TickEvent.Pre event, CallbackInfo ci) {
        ci.cancel();
        if (mc.player == null || mc.level == null) {
            comet$reset();
            return;
        }
        if (comet$level != mc.level) {
            comet$reset();
            comet$level = mc.level;
        }
        comet$tick++;
        if (comet$confirmationRetries.get()) comet$placements.expire(comet$tick);
        else comet$placements.clear();
        if (onlyOnClick.get() && !mc.options.keyUse.isDown()) return;

        Vec3 vec = mc.player.position().add(mc.player.getDeltaMovement()).add(0, -0.75, 0);
        if (airPlace.get()) bp.set(vec.x(), vec.y(), vec.z());
        else {
            Vec3 pos = mc.player.position();
            if (aheadDistance.get() != 0 && !towering()
                && !mc.level.getBlockState(mc.player.blockPosition().below())
                    .getCollisionShape(mc.level, mc.player.blockPosition()).isEmpty()) {
                Vec3 dir = Vec3.directionFromRotation(0, mc.player.getYRot())
                    .multiply(aheadDistance.get(), 0, aheadDistance.get());
                if (mc.options.keyUp.isDown()) pos = pos.add(dir.x, 0, dir.z);
                if (mc.options.keyDown.isDown()) pos = pos.add(-dir.x, 0, -dir.z);
                if (mc.options.keyLeft.isDown()) pos = pos.add(dir.z, 0, -dir.x);
                if (mc.options.keyRight.isDown()) pos = pos.add(-dir.z, 0, dir.x);
            }
            bp.set(pos.x, vec.y, pos.z);
        }
        if (mc.options.keyShift.isDown() && !mc.options.keyJump.isDown() && mc.player.getY() + vec.y > -1) {
            bp.setY(bp.getY() - 1);
        }
        if (bp.getY() >= mc.player.blockPosition().getY()) bp.setY(mc.player.blockPosition().getY() - 1);

        if (!airPlace.get() && BlockUtils.getPlaceSide(bp) == null && !comet$nearestSupport()) return;

        if (airPlace.get()) {
            // Radius mode needs multiple nearest cells; preserve Meteor's stable ordering and success limit.
            List<BlockPos> blocks = new ArrayList<>();
            for (int x = (int) (bp.getX() - radius.get()); x <= bp.getX() + radius.get(); x++) {
                for (int z = (int) (bp.getZ() - radius.get()); z <= bp.getZ() + radius.get(); z++) {
                    BlockPos position = BlockPos.containing(x, bp.getY(), z);
                    if (mc.player.position().distanceTo(Vec3.atCenterOf(position)) <= radius.get()
                        || (x == bp.getX() && z == bp.getZ())) blocks.add(position);
                }
            }
            blocks.sort(Comparator.comparingDouble(PlayerUtils::squaredDistanceTo));
            int counter = 0;
            for (BlockPos position : blocks) {
                if (place(position) && ++counter >= blocksPerTick.get()) break;
            }
        } else place(bp);

        FindItemResult result = InvUtils.findInHotbar(item -> validItem(item, bp));
        if (fastTower.get() && mc.options.keyJump.isDown() && !mc.options.keyShift.isDown()
            && result.found() && (autoSwitch.get() || result.getHand() != null)) {
            Vec3 velocity = mc.player.getDeltaMovement();
            if (!mc.level.getBlockCollisions(mc.player, mc.player.getBoundingBox().move(0, 1, 0)).iterator().hasNext()) {
                if (whileMoving.get() || !PlayerUtils.isMoving()) velocity = new Vec3(velocity.x, towerSpeed.get(), velocity.z);
                mc.player.setDeltaMovement(velocity);
            } else {
                mc.player.setDeltaMovement(velocity.x, Math.ceil(mc.player.getY()) - mc.player.getY(), velocity.z);
                mc.player.setOnGround(true);
            }
        }
    }

    @Unique private boolean comet$nearestSupport() {
        ScaffoldSupportSelection nearest = new ScaffoldSupportSelection(bp.getX(), bp.getY(), bp.getZ());
        double range = placeRange.get();
        Vec3 eye = mc.player.getEyePosition();
        for (int x = (int) (mc.player.getX() - range); x < mc.player.getX() + range; x++) {
            for (int z = (int) (mc.player.getZ() - range); z < mc.player.getZ() + range; z++) {
                for (int y = (int) Math.max(mc.level.getMinY(), mc.player.getY() - range);
                     y < Math.min(mc.level.getHeight(), mc.player.getY() + range); y++) {
                    bp.set(x, y, z);
                    if (BlockUtils.getPlaceSide(bp) == null || !BlockUtils.canPlace(bp)) continue;
                    Direction side = BlockUtils.getClosestPlaceSide(bp);
                    if (side == null) continue;
                    if (eye.distanceToSqr(Vec3.atCenterOf(bp.relative(side))) > 36) continue;
                    nearest.offer(x, y, z);
                }
            }
        }
        if (!nearest.found()) return false;
        bp.set(nearest.x(), nearest.y(), nearest.z());
        return true;
    }

    @Redirect(method = "place", at = @At(value = "INVOKE", target =
        "Lmeteordevelopment/meteorclient/utils/world/BlockUtils;place(Lnet/minecraft/core/BlockPos;Lmeteordevelopment/meteorclient/utils/player/FindItemResult;ZIZZ)Z"))
    private boolean comet$controlledPlace(BlockPos position, FindItemResult item, boolean rotate, int priority,
                                          boolean swing, boolean checkEntities) {
        long key = position.asLong();
        boolean retries = comet$confirmationRetries.get();
        if (retries && !comet$placements.canAttempt(key, comet$tick, comet$underfoot(position))) return false;
        boolean placed = BlockUtils.place(position, item, rotate, priority, swing, checkEntities,
            !autoSwitch.get() || !comet$stickySwitch.get());
        if (placed && retries) comet$placements.attempted(key, comet$tick);
        return placed;
    }

    @Unique private boolean comet$underfoot(BlockPos position) {
        int floor = mc.player.blockPosition().getY() - 1;
        Vec3 predicted = mc.player.position().add(mc.player.getDeltaMovement());
        return position.getY() <= floor && position.getY() >= floor - 1
            && Math.abs(position.getX() + 0.5 - predicted.x) < 1.5
            && Math.abs(position.getZ() + 0.5 - predicted.z) < 1.5;
    }

    @Override public void comet$serverBlockUpdate(BlockPos position, BlockState serverState) {
        if (!isActive() || comet$level == null || comet$level != mc.level || !mc.level.hasChunkAt(position)) return;
        comet$placements.serverUpdate(position.asLong(), serverState.canBeReplaced(),
            mc.level.getBlockState(position).canBeReplaced());
    }
}
