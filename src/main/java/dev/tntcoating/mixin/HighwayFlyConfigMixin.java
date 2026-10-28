package dev.tntcoating.mixin;

import dev.tntcoating.core.HighwayFlyConfigMigration;
import dev.tntcoating.core.LobbyRecoveryStore;
import meteordevelopment.meteorclient.MeteorClient;
import dev.tntcoating.modules.HighwayFly;
import dev.tntcoating.modules.boatfly.BoatFlyEngine;
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
        // Guard shared helpers before releasing Auto Boat Fly's temporary helper ownership.
        if (highway != null) highway.beginProfileLoad();
        for (BoatFlyEngine boat : BoatFlyEngine.engines()) boat.beginProfileLoad();
        boolean pendingTrip = new LobbyRecoveryStore(MeteorClient.FOLDER.toPath()
            .resolve("auto-lobby-recovery.json")).load().isPresent();
        HighwayFlyConfigMigration.migrateIndependent(tag, pendingTrip);
        // Dashboard settings leave the old boat-fly record before it is copied to auto-boat-fly.
        HighwayFlyConfigMigration.migrateFlightDashboard(tag);
        HighwayFlyConfigMigration.splitBoatFly(tag);
        if (highway != null) HighwayFlyConfigMigration.regroupHighwaySettings(tag, highway.settingGroups());
        for (BoatFlyEngine boat : BoatFlyEngine.engines())
            HighwayFlyConfigMigration.regroupSettings(tag, boat.name, boat.settingGroups());
    }

    @Inject(method = "fromTag(Lnet/minecraft/nbt/CompoundTag;)Lmeteordevelopment/meteorclient/systems/modules/Modules;", at = @At("RETURN"))
    private void comet$finishHighwayProfileLoad(CompoundTag tag, CallbackInfoReturnable<Modules> info) {
        HighwayFly highway = ((Modules) (Object) this).get(HighwayFly.class);
        if (highway != null) highway.endProfileLoad();
        for (BoatFlyEngine boat : BoatFlyEngine.engines()) boat.endProfileLoad();
    }

    @Inject(method = "toTag()Lnet/minecraft/nbt/CompoundTag;", at = @At("RETURN"))
    private void comet$saveOriginalReconnectSettings(CallbackInfoReturnable<CompoundTag> info) {
        HighwayFly highway = ((Modules) (Object) this).get(HighwayFly.class);
        if (highway != null) highway.restoreReconnectInSavedConfig(info.getReturnValue());
        for (BoatFlyEngine boat : BoatFlyEngine.engines()) boat.restoreBorrowedInSavedConfig(info.getReturnValue());
    }
}
