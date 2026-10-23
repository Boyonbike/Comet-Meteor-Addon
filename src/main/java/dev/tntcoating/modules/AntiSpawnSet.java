/*
 * Original Comet implementation by Boyonabike.
 * SPDX-License-Identifier: CC0-1.0
 */

package dev.tntcoating.modules;

import dev.tntcoating.TntCoatingAddon;
import meteordevelopment.meteorclient.events.packets.PacketEvent;
import meteordevelopment.meteorclient.settings.BoolSetting;
import meteordevelopment.meteorclient.settings.Setting;
import meteordevelopment.meteorclient.settings.SettingGroup;
import meteordevelopment.meteorclient.systems.modules.Module;
import meteordevelopment.orbit.EventHandler;
import net.minecraft.core.BlockPos;
import net.minecraft.network.protocol.game.ServerboundUseItemOnPacket;
import net.minecraft.world.level.block.BedBlock;
import net.minecraft.world.level.block.RespawnAnchorBlock;
import net.minecraft.world.level.block.state.BlockState;

/** Cancels the block-use packet before a bed or respawn anchor can change your spawn point. */
public final class AntiSpawnSet extends Module {
    private final SettingGroup general = settings.getDefaultGroup();
    private final Setting<Boolean> blockBeds = general.add(new BoolSetting.Builder()
        .name("block-beds")
        .description("Cancel bed interactions, including sleeping and using a bed as a spawn point.")
        .defaultValue(true)
        .build());
    private final Setting<Boolean> blockAnchors = general.add(new BoolSetting.Builder()
        .name("block-anchors")
        .description("Cancel respawn-anchor interactions, including charging and exploding one.")
        .defaultValue(true)
        .build());
    private final Setting<Boolean> notify = general.add(new BoolSetting.Builder()
        .name("notify")
        .description("Show a chat message when an interaction is cancelled.")
        .defaultValue(true)
        .build());

    public AntiSpawnSet() {
        super(TntCoatingAddon.CATEGORY, "anti-spawn-set",
            "Stops bed and respawn-anchor interactions from changing your spawn point.");
    }

    @EventHandler
    private void onSend(PacketEvent.Send event) {
        if (mc.player == null || mc.level == null) return;
        if (!(event.packet instanceof ServerboundUseItemOnPacket use)) return;

        BlockPos pos = use.getHitResult().getBlockPos();
        BlockState state = mc.level.getBlockState(pos);
        String blocked = null;
        if (blockBeds.get() && state.getBlock() instanceof BedBlock) blocked = "bed";
        else if (blockAnchors.get() && state.getBlock() instanceof RespawnAnchorBlock) blocked = "respawn anchor";
        if (blocked == null) return;

        event.cancel();
        if (notify.get()) info("Blocked a %s interaction.", blocked);
    }
}
