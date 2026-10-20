package dev.tntcoating.mixin;

import com.google.common.collect.Streams;
import dev.tntcoating.core.NearestFirst;
import dev.tntcoating.core.ScaffoldPlacementFeedback;
import dev.tntcoating.core.ScaffoldPlacementTracker;
import meteordevelopment.meteorclient.events.world.TickEvent;
import meteordevelopment.meteorclient.settings.BoolSetting;
import meteordevelopment.meteorclient.settings.Setting;
import meteordevelopment.meteorclient.settings.SettingGroup;
import meteordevelopment.meteorclient.systems.modules.Category;
import meteordevelopment.meteorclient.systems.modules.Module;
import meteordevelopment.meteorclient.systems.modules.movement.Scaffold;
import meteordevelopment.meteorclient.utils.player.FindItemResult;
import meteordevelopment.meteorclient.utils.world.BlockUtils;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
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

import java.util.Comparator;
import java.util.List;
import java.util.stream.Stream;

/**
 * Narrow upgrades around Meteor's own Scaffold tick, so upstream fixes to its targeting and tower logic keep applying:
 * one-pass fallback support selection, an early-exit tower ceiling check, sticky switching and confirmation retries.
 */
@Mixin(value = Scaffold.class, remap = false)
public abstract class ScaffoldMixin extends Module implements ScaffoldPlacementFeedback {
    @Shadow @Final private Setting<Boolean> autoSwitch;

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
            .description("Keep the selected block slot after placement, reducing held-item swap packets.")
            .defaultValue(false)
            .visible(autoSwitch::get)
            .build());
        comet$confirmationRetries = group.add(new BoolSetting.Builder()
            .name("confirmation-retries")
            .description("Pace repeated attempts at unconfirmed or rejected cells; new bridge cells place immediately.")
            .defaultValue(true)
            .build());
    }

    // Scaffold defines neither hook, so these add Comet's reset without replacing Meteor code.
    @Override public void onActivate() { comet$reset(); }
    @Override public void onDeactivate() { comet$reset(); }

    @Unique private void comet$reset() {
        comet$placements.clear();
        comet$level = null;
        comet$tick = 0;
    }

    /** Advances retry bookkeeping before Meteor's tick places anything. */
    @Inject(method = "onTick", at = @At("HEAD"))
    private void comet$trackRetries(TickEvent.Pre event, CallbackInfo ci) {
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
    }

    /**
     * Meteor sorts every fallback support by distance and then takes the first. Moving the stable-sort minimum to the
     * front in one pass gives the same choice without sorting. Ordinal 0 is that fallback list; the air-place list
     * (ordinal 1) is consumed in full order, so it keeps Meteor's sort.
     */
    @Redirect(method = "onTick", at = @At(value = "INVOKE", target = "Ljava/util/List;sort(Ljava/util/Comparator;)V", ordinal = 0))
    private void comet$nearestSupport(List<BlockPos> supports, Comparator<? super BlockPos> distance) {
        NearestFirst.moveMinimumToFront(supports, distance);
    }

    /** The tower check only asks whether any collision exists above the player: stop at the first one. */
    @Redirect(method = "onTick", at = @At(value = "INVOKE", target = "Lcom/google/common/collect/Streams;stream(Ljava/lang/Iterable;)Ljava/util/stream/Stream;"))
    private Stream<?> comet$firstCollision(Iterable<?> collisions) {
        return Streams.stream(collisions).limit(1);
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
        if (!isActive() || comet$level == null || comet$level != mc.level
            || !mc.level.hasChunk(position.getX() >> 4, position.getZ() >> 4)) return;
        comet$placements.serverUpdate(position.asLong(), serverState.canBeReplaced(),
            mc.level.getBlockState(position).canBeReplaced());
    }
}
