package dev.tntcoating.mixin;

import dev.tntcoating.core.HighwayFlyConfigMigration;
import dev.tntcoating.core.LobbyRecoveryStore;
import meteordevelopment.meteorclient.MeteorClient;
import dev.tntcoating.modules.HighwayFly;
import meteordevelopment.meteorclient.systems.modules.Modules;
import net.minecraft.nbt.CompoundTag;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Migrate before Meteor looks up module records by their saved names. */
@Mixin(value = Modules.class, remap = false)
public abstract class HighwayFlyConfigMixin {
    @Inject(method = "fromTag(Lnet/minecraft/nbt/CompoundTag;)Lmeteordevelopment/meteorclient/systems/modules/Modules;", at = @At("HEAD"))
    private void comet$migrateHighwayFly(CompoundTag tag, CallbackInfoReturnable<Modules> info) {
        HighwayFly highway = ((Modules) (Object) this).get(HighwayFly.class);
        if (highway != null) highway.beginProfileLoad();
        boolean pendingTrip = new LobbyRecoveryStore(MeteorClient.FOLDER.toPath()
            .resolve("auto-lobby-recovery.json")).load().isPresent();
        HighwayFlyConfigMigration.migrateIndependent(tag, pendingTrip);
        if (highway != null) HighwayFlyConfigMigration.regroupHighwaySettings(tag, highway.settingGroups());
    }

    @Inject(method = "fromTag(Lnet/minecraft/nbt/CompoundTag;)Lmeteordevelopment/meteorclient/systems/modules/Modules;", at = @At("RETURN"))
    private void comet$finishHighwayProfileLoad(CompoundTag tag, CallbackInfoReturnable<Modules> info) {
        HighwayFly highway = ((Modules) (Object) this).get(HighwayFly.class);
        if (highway != null) highway.endProfileLoad();
    }

    @Inject(method = "toTag()Lnet/minecraft/nbt/CompoundTag;", at = @At("RETURN"))
    private void comet$saveOriginalReconnectSettings(CallbackInfoReturnable<CompoundTag> info) {
        HighwayFly highway = ((Modules) (Object) this).get(HighwayFly.class);
        if (highway != null) highway.restoreReconnectInSavedConfig(info.getReturnValue());
    }
}
