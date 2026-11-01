/*
 * Original Comet test harness code by Boyonabike.
 * Independent: written for this repository against the pinned Minecraft 26.2
 * client classes; no third-party client code.
 * SPDX-License-Identifier: CC0-1.0
 */

package dev.tntcoating.gametest;

import dev.tntcoating.core.combat.mc.CombatRecorderHost;
import dev.tntcoating.core.combat.mc.ObservationHooks;
import dev.tntcoating.core.combat.mc.ObservationListener;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.protocol.game.ClientboundExplodePacket;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;

/**
 * Sends one side-effect-free sequenced block interaction from the after-application
 * explosion hook. This is the only way to send it before a same-tick health sync is
 * applied to the client, which is the ordering C20 needs.
 */
public final class ExplosionInteractionProbe implements ObservationListener {
    private final CombatRecorderHost trace;
    private final BlockPos floor;
    private boolean fired;

    public ExplosionInteractionProbe(CombatRecorderHost trace, BlockPos floor) {
        this.trace = trace;
        this.floor = floor;
    }

    public static void install(ExplosionInteractionProbe probe) {
        ObservationHooks.install(probe);
    }

    public static void uninstall(ExplosionInteractionProbe probe) {
        ObservationHooks.uninstall(probe);
    }

    @Override
    public void afterExplosion(ClientboundExplodePacket packet) {
        if (fired) return;
        fired = true;
        trace.label("explosion-after-application");
        Minecraft client = Minecraft.getInstance();
        if (client.player == null) return;
        Vec3 hit = Vec3.atCenterOf(floor).add(0.0, 0.5, 0.0);
        client.gameMode.useItemOn(client.player, InteractionHand.MAIN_HAND,
            new BlockHitResult(hit, Direction.UP, floor, false));
        trace.label("post-explosion-interaction-sent");
    }

    public boolean fired() {
        return fired;
    }
}
