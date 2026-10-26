/*
 * Comet highway travel helpers.
 * SPDX-License-Identifier: GPL-3.0-only
 */

package dev.tntcoating.modules;

import dev.tntcoating.core.PortalGuard;
import meteordevelopment.meteorclient.events.game.GameLeftEvent;
import meteordevelopment.meteorclient.events.packets.PacketEvent;
import meteordevelopment.meteorclient.settings.BoolSetting;
import meteordevelopment.meteorclient.settings.Setting;
import meteordevelopment.meteorclient.settings.SettingGroup;
import meteordevelopment.orbit.EventHandler;
import net.minecraft.core.BlockPos;
import net.minecraft.network.protocol.game.ClientboundLoginPacket;
import net.minecraft.network.protocol.game.ClientboundPlayerPositionPacket;
import net.minecraft.network.protocol.game.ClientboundRespawnPacket;
import net.minecraft.network.protocol.game.ServerboundMovePlayerPacket;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

/** Independent helper, also available to Highway Fly through coordinated activation. */
public final class AntiPortal extends HighwayHelper {
    public AntiPortal() { super("anti-portal", "Prevent unwanted portal travel, including outside Highway Fly."); }

    /** One tick of ordinary movement is well under this; a farther target means the anchor is stale. */
    private static final double MAX_ANCHOR_DISTANCE = 16.0;

    private final SettingGroup general = settings.getDefaultGroup();
    private final Setting<Boolean> blockNether = general.add(new BoolSetting.Builder()
        .name("nether-portals")
        .description("Stop the server from seeing you enter Nether portals.")
        .defaultValue(true)
        .build());
    private final Setting<Boolean> blockEnd = general.add(new BoolSetting.Builder()
        .name("end-portals")
        .description("Also stop the server from seeing you enter End portals, including the exit portal.")
        .defaultValue(false)
        .build());
    private final Setting<Boolean> notify = general.add(new BoolSetting.Builder()
        .name("notify")
        .description("Show a chat message the first time an entry is blocked.")
        .defaultValue(true)
        .build());

    /** Last outbound position outside a blocked portal that the server was allowed to see. */
    private Vec3 anchor;
    private boolean warned;

    protected void started() {
        warned = false;
        anchor = mc.player != null && !blockedAt(mc.player.getBoundingBox()) ? mc.player.position() : null;
    }

    protected void stopped() {
        anchor = null;
        warned = false;
    }

    @EventHandler
    private void onSend(PacketEvent.Send event) {
        if (!isActive() || loadingProfile() || !highway().portalsAllowed()) { stopped(); return; }
        if (mc.player == null || mc.level == null || event.isCancelled()) return;
        if (!blockNether.get() && !blockEnd.get()) return;
        if (mc.player.isSpectator() || mc.player.isPassenger()) return;
        if (!(event.packet instanceof ServerboundMovePlayerPacket packet) || !packet.hasPosition()) return;

        Vec3 target = new Vec3(packet.getX(0.0), packet.getY(0.0), packet.getZ(0.0));
        AABB targetBox = mc.player.getBoundingBox().move(target.subtract(mc.player.position()));
        if (!blockedAt(targetBox)) {
            anchor = target;
            warned = false;
            return;
        }

        Vec3 safe = anchor;
        event.cancel();
        if (safe == null || !PortalGuard.anchorUsable(safe.distanceToSqr(target), MAX_ANCHOR_DISTANCE)) {
            // No safe position is known after a correction/join. Never transmit an entry packet.
            return;
        }
        if (packet instanceof ServerboundMovePlayerPacket.PosRot) {
            event.sendSilently(new ServerboundMovePlayerPacket.PosRot(
                safe.x, safe.y, safe.z,
                packet.getYRot(0.0F), packet.getXRot(0.0F),
                packet.isOnGround(), packet.horizontalCollision()));
        } else {
            event.sendSilently(new ServerboundMovePlayerPacket.Pos(
                safe.x, safe.y, safe.z,
                packet.isOnGround(), packet.horizontalCollision()));
        }

        if (notify.get() && !warned) {
            warned = true;
            info("Blocked portal travel; the server still sees you outside the portal.");
        }
    }

    @EventHandler
    private void onReceive(PacketEvent.Receive event) {
        if (event.packet instanceof ClientboundLoginPacket
            || event.packet instanceof ClientboundPlayerPositionPacket
            || event.packet instanceof ClientboundRespawnPacket) {
            anchor = null;
            warned = false;
        }
    }

    /** Whether the player box at a target position would be seen inside a portal the module blocks. */
    private boolean blockedAt(AABB box) {
        AABB query = box.deflate(1.0E-5);
        BlockPos min = BlockPos.containing(query.minX, query.minY, query.minZ);
        BlockPos max = BlockPos.containing(query.maxX, query.maxY, query.maxZ);
        boolean nether = false;
        boolean end = false;

        for (BlockPos pos : BlockPos.betweenClosed(min, max)) {
            BlockState state = mc.level.getBlockState(pos);
            if (state.is(Blocks.NETHER_PORTAL)) nether = true;
            else if (state.is(Blocks.END_PORTAL)) end = true;
            if (PortalGuard.shouldBlock(nether, end, blockNether.get(), blockEnd.get())) return true;
        }

        return false;
    }

    @EventHandler private void onLeft(GameLeftEvent event) { stopped(); }

}
