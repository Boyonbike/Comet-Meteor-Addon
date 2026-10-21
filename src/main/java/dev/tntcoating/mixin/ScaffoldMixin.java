package dev.tntcoating.mixin;

import com.google.common.collect.Streams;
import com.google.gson.JsonObject;
import dev.tntcoating.core.NearestFirst;
import dev.tntcoating.core.ScaffoldPlacementFeedback;
import dev.tntcoating.core.ScaffoldPlacementTracker;
import dev.tntcoating.core.ScaffoldSetbackLog;
import meteordevelopment.meteorclient.MeteorClient;
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
import net.minecraft.client.multiplayer.PlayerInfo;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.player.Input;
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

import java.io.IOException;
import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Stream;

/**
 * Narrow upgrades around Meteor's own Scaffold tick, so upstream fixes to its targeting and tower logic keep applying:
 * one-pass fallback support selection, an early-exit tower ceiling check, sticky switching, confirmation retries and
 * a setback log.
 */
@Mixin(value = Scaffold.class, remap = false)
public abstract class ScaffoldMixin extends Module implements ScaffoldPlacementFeedback {
    @Shadow @Final private Setting<Boolean> autoSwitch;

    @Unique private static final List<String> COMET$LOGGED_SETTINGS = List.of("fast-tower", "tower-speed", "while-moving",
        "rotate", "air-place", "ahead-distance", "blocks-per-tick", "auto-switch", "sticky-switch", "confirmation-retries");

    @Unique private Setting<Boolean> comet$stickySwitch;
    @Unique private Setting<Boolean> comet$confirmationRetries;
    @Unique private Setting<Boolean> comet$logSetbacks;
    @Unique private final ScaffoldPlacementTracker comet$placements = new ScaffoldPlacementTracker();
    @Unique private final ScaffoldSetbackLog comet$setbackLog = new ScaffoldSetbackLog();
    @Unique private ClientLevel comet$level;
    @Unique private long comet$tick;
    // The tick in progress, recorded once the client has reported its movement.
    @Unique private boolean comet$tickOpen, comet$towering;
    @Unique private int comet$placed, comet$paced;

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
        comet$logSetbacks = group.add(new BoolSetting.Builder()
            .name("log-setbacks")
            .description("Write every server setback while Scaffold is on to meteor-client/comet/scaffold-setback-logs (one file per Scaffold session, at most 4 MB): the move the server rejected, sent rotations, tower state and placements in the second before it.")
            .defaultValue(true)
            .build());
    }

    // Scaffold defines neither hook, so these add Comet's reset without replacing Meteor code.
    @Override public void onActivate() { comet$reset(); }
    @Override public void onDeactivate() { comet$reset(); }

    @Unique private void comet$reset() {
        comet$placements.clear();
        comet$setbackLog.close();
        comet$level = null;
        comet$tick = 0;
        comet$tickOpen = false;
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
        comet$closeTick();
        comet$tick++;
        if (comet$confirmationRetries.get()) comet$placements.expire(comet$tick);
        else comet$placements.clear();
        if (!comet$logSetbacks.get()) {
            comet$setbackLog.close();
            return;
        }
        comet$tickOpen = true;
        comet$towering = ((Scaffold) (Object) this).towering();
        comet$placed = comet$paced = 0;
    }

    /** Records the finished tick with what the client reported at its end. */
    @Unique private void comet$closeTick() {
        if (!comet$tickOpen) return;
        comet$tickOpen = false;
        LocalPlayerAccessor sent = (LocalPlayerAccessor) mc.player;
        comet$setbackLog.tick(new ScaffoldSetbackLog.Tick(comet$tick, sent.comet$xLast(), sent.comet$yLast(),
            sent.comet$zLast(), sent.comet$yRotLast(), sent.comet$xRotLast(), sent.comet$lastOnGround(), comet$towering,
            comet$placed, comet$paced));
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
        if (retries && !comet$placements.canAttempt(key, comet$tick, comet$underfoot(position))) {
            comet$paced++;
            return false;
        }
        boolean placed = BlockUtils.place(position, item, rotate, priority, swing, checkEntities,
            !autoSwitch.get() || !comet$stickySwitch.get());
        if (placed && retries) comet$placements.attempted(key, comet$tick);
        if (placed && comet$tickOpen) {
            comet$placed++;
            comet$setbackLog.placed(comet$tick, key);
        }
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

    @Override public void comet$serverSetback() {
        if (!isActive() || !comet$logSetbacks.get() || comet$level == null || comet$level != mc.level
            || mc.player == null || mc.player.isPassenger()) return;
        // The setback arrives after the tick's movement was reported, so that tick is complete.
        comet$closeTick();
        LocalPlayerAccessor sent = (LocalPlayerAccessor) mc.player;
        Vec3 target = mc.player.position();
        double dx = target.x - sent.comet$xLast(), dy = target.y - sent.comet$yLast(), dz = target.z - sent.comet$zLast();
        double distance = Math.sqrt(dx * dx + dy * dy + dz * dz);
        JsonObject record = new JsonObject();
        record.addProperty("time", Instant.now().toString());
        record.addProperty("tick", comet$tick);
        record.addProperty("dimension", mc.level.dimension().identifier().toString());
        record.addProperty("x", target.x);
        record.addProperty("y", target.y);
        record.addProperty("z", target.z);
        record.addProperty("correctionX", dx);
        record.addProperty("correctionY", dy);
        record.addProperty("correctionZ", dz);
        record.addProperty("correctionDistance", distance);
        record.addProperty("teleport", distance > 64);
        PlayerInfo info = mc.getConnection() == null ? null : mc.getConnection().getPlayerInfo(mc.player.getUUID());
        record.addProperty("ping", info == null ? -1 : info.getLatency());
        Input input = mc.player.getLastSentInput();
        record.addProperty("sneaking", input.shift());
        record.addProperty("sprinting", input.sprint());
        record.addProperty("jumping", input.jump());
        record.addProperty("towering", comet$towering);
        record.addProperty("unconfirmedPlacements", comet$confirmationRetries.get() ? comet$placements.size() : -1);
        JsonObject values = new JsonObject();
        for (String name : COMET$LOGGED_SETTINGS) {
            Setting<?> setting = settings.get(name);
            if (setting == null) continue;
            Object value = setting.get();
            if (value instanceof Boolean flag) values.addProperty(name, flag);
            else if (value instanceof Number number) values.addProperty(name, number);
            else values.addProperty(name, String.valueOf(value));
        }
        record.add("settings", values);
        comet$setbackLog.describe(record, comet$tick, target.x, target.y, target.z,
            BlockPos.containing(target.x, target.y - .5, target.z).asLong());
        boolean opening = comet$setbackLog.path() == null;
        try {
            comet$setbackLog.write(MeteorClient.FOLDER.toPath().resolve("comet/scaffold-setback-logs"), record);
        } catch (IOException | RuntimeException e) {
            warning("Could not open the setback log: %s", e.getMessage() == null ? e : e.getMessage());
            return;
        }
        if (opening && comet$setbackLog.path() != null) info("Logging setbacks to %s", comet$setbackLog.path().toAbsolutePath());
    }
}
