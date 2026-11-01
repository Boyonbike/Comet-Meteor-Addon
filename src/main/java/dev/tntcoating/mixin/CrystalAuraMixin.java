/*
 * Original Comet implementation by Boyonabike.
 * Independent: written from the pinned 26.2 artifact and Meteor build 35 behaviour; no third-party code.
 * SPDX-License-Identifier: CC0-1.0
 */

package dev.tntcoating.mixin;

import dev.tntcoating.core.combat.CrystalCombatGate;
import meteordevelopment.meteorclient.events.world.TickEvent;
import meteordevelopment.meteorclient.systems.modules.combat.CrystalAura;
import meteordevelopment.meteorclient.utils.player.Rotations;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.BlockHitResult;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Keeps Meteor's own CrystalAura from sending while CrystalCombat owns combat (audit §3.8.3, §3.9.8).
 *
 * <p>Every audited send path gets a {@link CrystalCombatGate#suppressMeteor()} cancellation, and the two
 * delayed rotation callbacks that later attack or place are additionally wrapped in
 * {@link CrystalCombatGate#guard(Runnable)} so a callback queued before CrystalCombat was disabled can never
 * send afterwards. Meteor's enabled flag, settings and bookkeeping are never touched; the mixin only cancels
 * the current call.</p>
 *
 * <p>{@code doBreak(Entity)} and {@code attackCrystal(Entity)} also refuse a ledger-protected crystal through
 * {@link CrystalCombatGate#protects(java.util.UUID)}, independently of activation. The check is read-only: it
 * does not update Meteor's timers, counters or the ledger.</p>
 *
 * <p>The pre-existing null-world guards on both tick hooks are preserved. Observation-only
 * {@code onPacketSend}/{@code onPacketSent} are intentionally not injected.</p>
 */
@Mixin(value = CrystalAura.class, remap = false)
public abstract class CrystalAuraMixin {
    // Tick events can outlive the world, including when another listener disconnects during dispatch.
    @Inject(method = "onPreTick(Lmeteordevelopment/meteorclient/events/world/TickEvent$Pre;)V",
        at = @At("HEAD"), cancellable = true)
    private void comet$gatePreTick(TickEvent.Pre event, CallbackInfo ci) {
        if (CrystalCombatGate.suppressMeteor()) {
            ci.cancel();
            return;
        }
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null || mc.level == null) ci.cancel();
    }

    // The late rotation hook can still run after the main callback was skipped.
    @Inject(method = "onPreTickLast(Lmeteordevelopment/meteorclient/events/world/TickEvent$Pre;)V",
        at = @At("HEAD"), cancellable = true)
    private void comet$gatePreTickLast(TickEvent.Pre event, CallbackInfo ci) {
        if (CrystalCombatGate.suppressMeteor()) {
            ci.cancel();
            return;
        }
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null || mc.level == null) ci.cancel();
    }

    // Do not cancel onEntityAdded: it also updates Meteor's own placing/placedCrystals bookkeeping, which the
    // plan requires us to leave alone. Any fast-break it starts is stopped at doBreak(Entity) below.

    // Tick break: selects the best crystal and calls doBreak(Entity).
    @Inject(method = "doBreak()V", at = @At("HEAD"), cancellable = true)
    private void comet$gateTickBreak(CallbackInfo ci) {
        if (CrystalCombatGate.suppressMeteor()) ci.cancel();
    }

    // Pre-attack path. Cancelling here also skips the anti-weakness swap and the rotation request.
    @Inject(method = "doBreak(Lnet/minecraft/world/entity/Entity;)V", at = @At("HEAD"), cancellable = true)
    private void comet$gateBreak(Entity crystal, CallbackInfo ci) {
        if (CrystalCombatGate.suppressMeteor() || CrystalCombatGate.protects(crystal.getUUID())) ci.cancel();
    }

    // The single attack send; the protector check also applies while CrystalCombat is disabled (read-only).
    @Inject(method = "attackCrystal(Lnet/minecraft/world/entity/Entity;)V", at = @At("HEAD"), cancellable = true)
    private void comet$gateAttack(Entity crystal, CallbackInfo ci) {
        if (CrystalCombatGate.suppressMeteor() || CrystalCombatGate.protects(crystal.getUUID())) ci.cancel();
    }

    // Tick place: chooses the placement and calls placeCrystal.
    @Inject(method = "doPlace()V", at = @At("HEAD"), cancellable = true)
    private void comet$gatePlace(CallbackInfo ci) {
        if (CrystalCombatGate.suppressMeteor()) ci.cancel();
    }

    // The placement send (use-item-on prediction plus the optional support placement and slot work).
    @Inject(method = "placeCrystal(Lnet/minecraft/world/phys/BlockHitResult;DLnet/minecraft/core/BlockPos;)V",
        at = @At("HEAD"), cancellable = true)
    private void comet$gatePlaceCrystal(BlockHitResult result, double damage, BlockPos supportBlock, CallbackInfo ci) {
        if (CrystalCombatGate.suppressMeteor()) ci.cancel();
    }

    // Rotation stepping for the break path. Returning false skips the rotation and the break callback.
    @Inject(method = "doYawSteps(DD)Z", at = @At("HEAD"), cancellable = true)
    private void comet$gateYawSteps(double targetYaw, double targetPitch, CallbackInfoReturnable<Boolean> cir) {
        if (CrystalCombatGate.suppressMeteor()) cir.setReturnValue(false);
    }

    // Defence in depth: the delayed callback re-checks the suppression generation before it can attack.
    @Redirect(method = "doBreak(Lnet/minecraft/world/entity/Entity;)V",
        at = @At(value = "INVOKE",
            target = "Lmeteordevelopment/meteorclient/utils/player/Rotations;rotate(DDILjava/lang/Runnable;)V"))
    private void comet$guardBreakRotation(double yaw, double pitch, int priority, Runnable callback) {
        if (CrystalCombatGate.suppressMeteor()) return;
        Rotations.rotate(yaw, pitch, priority, CrystalCombatGate.guard(callback));
    }

    // Defence in depth for the delayed placement callback. In the pinned build doPlace() hands the delayed
    // rotation to BlockIterator.after through the synthetic method lambda$doPlace$1; the redirect must live
    // there because the rotate call is not in doPlace()'s own bytecode.
    @Redirect(method = "lambda$doPlace$1(Lcom/google/common/util/concurrent/AtomicDouble;"
        + "Ljava/util/concurrent/atomic/AtomicReference;Ljava/util/concurrent/atomic/AtomicBoolean;)V",
        at = @At(value = "INVOKE",
            target = "Lmeteordevelopment/meteorclient/utils/player/Rotations;rotate(DDILjava/lang/Runnable;)V"))
    private void comet$guardPlaceRotation(double yaw, double pitch, int priority, Runnable callback) {
        if (CrystalCombatGate.suppressMeteor()) return;
        Rotations.rotate(yaw, pitch, priority, CrystalCombatGate.guard(callback));
    }
}
