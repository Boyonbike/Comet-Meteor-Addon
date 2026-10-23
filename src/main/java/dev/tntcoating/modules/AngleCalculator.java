/*
 * Original Comet implementation by Boyonabike.
 * SPDX-License-Identifier: CC0-1.0
 */

package dev.tntcoating.modules;

import dev.tntcoating.TntCoatingAddon;
import meteordevelopment.meteorclient.events.world.TickEvent;
import meteordevelopment.meteorclient.settings.BlockPosSetting;
import meteordevelopment.meteorclient.settings.BoolSetting;
import meteordevelopment.meteorclient.settings.Setting;
import meteordevelopment.meteorclient.settings.SettingGroup;
import meteordevelopment.meteorclient.systems.modules.Module;
import meteordevelopment.orbit.EventHandler;
import net.minecraft.core.BlockPos;
import net.minecraft.util.Mth;

/**
 * Points your view at one X/Z coordinate, so walking or flying forward follows a straight line.
 * It only turns the camera and, while riding, the vehicle; it never moves you by itself.
 */
public final class AngleCalculator extends Module {
    private final SettingGroup general = settings.getDefaultGroup();
    private final Setting<BlockPos> destination = general.add(new BlockPosSetting.Builder()
        .name("to")
        .description("Block whose centre to aim at.")
        .defaultValue(new BlockPos(0, 64, 1000))
        .build());
    private final Setting<Boolean> levelPitch = general.add(new BoolSetting.Builder()
        .name("level-pitch")
        .description("Set pitch to zero as well, keeping the view level.")
        .defaultValue(true)
        .build());

    public AngleCalculator() {
        super(TntCoatingAddon.CATEGORY, "angle-calculator",
            "Aims your view at a fixed coordinate so you travel in a straight line.");
    }

    @Override
    public void onActivate() {
        if (mc.player == null || mc.level == null) {
            toggle();
            return;
        }
        if (!turnTowardsDestination()) {
            info("Already at the target coordinate; angle-calculator is off.");
            toggle();
        }
    }

    @EventHandler
    private void onTick(TickEvent.Pre event) {
        if (mc.player == null || mc.level == null) {
            toggle();
            return;
        }
        if (!turnTowardsDestination()) {
            info("Reached the target coordinate; angle-calculator is off.");
            toggle();
        }
    }

    /** @return false when the player is exactly above or below the destination's X/Z. */
    private boolean turnTowardsDestination() {
        double dx = destination.get().getX() + 0.5 - mc.player.getX();
        double dz = destination.get().getZ() + 0.5 - mc.player.getZ();
        if (dx == 0.0 && dz == 0.0) return false;

        float yaw = Mth.wrapDegrees((float) Math.toDegrees(Math.atan2(dz, dx)) - 90.0f);
        mc.player.setYRot(yaw);
        if (levelPitch.get()) mc.player.setXRot(0.0f);

        if (mc.player.isPassenger()) {
            mc.player.getVehicle().setYRot(yaw);
            if (levelPitch.get()) mc.player.getVehicle().setXRot(0.0f);
        }
        return true;
    }
}
